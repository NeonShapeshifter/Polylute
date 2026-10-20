package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.AudioMetaUtils
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a track *is*, as the Now Playing line states it: format, sample rate, bit depth.
 *
 * The line is the source's identity, not the player's output. That distinction is the whole reason
 * this type exists: the same track can be described by three different authorities, and only two of
 * them are describing the file.
 */
internal data class SongTechnicalIdentity(
    val format: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
) {
    /** The canonical line: `FLAC · 96 kHz · 24-bit`, with only the tokens that are known. */
    val canonical: String? = compose(listOf(format, formatSampleRate(sampleRateHz), formatBitDepth(bitDepth)))

    /**
     * The one deterministic fallback: the same identity without its bit depth.
     *
     * Used only when the canonical line genuinely cannot fit the width it is given — an extreme
     * font scale on a narrow screen. It is a fixed second choice rather than a list of candidates:
     * the visible values must not depend on which of several strings happened to measure narrower
     * on a given frame.
     *
     * It never drops the format while the format is known. A line reading only `96 kHz` tells the
     * reader less than `FLAC · 96 kHz` and, worse, looks like a different kind of statement.
     */
    val withoutBitDepth: String? = compose(listOf(format, formatSampleRate(sampleRateHz)))

    val isPresent: Boolean get() = format != null || sampleRateHz != null || bitDepth != null

    /**
     * This identity with anything [previous] knew that this one does not.
     *
     * The identity is assembled from several authorities and one of them can go quiet — the player
     * clears its reading of a track when playback stops, and a library row can be re-read without
     * one of its fields. None of that is news about the file, so a value once established for a
     * track is kept rather than withdrawn until the track itself changes. This is what stops the
     * line collapsing from `FLAC · 96 kHz · 24-bit` back to `FLAC` in the middle of a song.
     */
    fun keepingWhatWasKnown(previous: SongTechnicalIdentity): SongTechnicalIdentity =
        SongTechnicalIdentity(
            format = format ?: previous.format,
            sampleRateHz = sampleRateHz ?: previous.sampleRateHz,
            bitDepth = bitDepth ?: previous.bitDepth,
        )

    private companion object {
        fun compose(tokens: List<String?>): String? =
            tokens.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}

/**
 * Which of the identity's two forms to draw, given whether the canonical one fits.
 *
 * Two fixed forms and one boolean — deliberately not a list of candidates where the winner depends
 * on which string happened to measure narrower on this frame. The visible values must be a
 * consequence of what is known about the track, not of the width it was drawn into.
 *
 * When the canonical form has already been ruled out by width, the fallback is the answer; and if
 * there is no fallback to give — a track whose identity is only a bit depth — the canonical form is
 * returned anyway, because drawing something narrow is better than drawing nothing.
 */
internal fun chooseTechnicalLine(
    canonical: String?,
    fallback: String?,
    canonicalFits: Boolean,
): String = when {
    canonical == null -> fallback.orEmpty()
    canonicalFits -> canonical
    else -> fallback ?: canonical
}

/**
 * The track's identity, resolved from the authorities that describe the *file*.
 *
 * The order is deliberate and is the fix for a real defect. The line used to take whatever the
 * player last reported and print it, so a FLAC being decoded read `96.0 kHz` — the format had been
 * dropped (the player reports `audio/raw` for decoded PCM, which is not a codec) and the rate came
 * with a decimal the rest of the app does not use.
 *
 * So the stable authorities go first and the runtime is only ever a gap-filler:
 *
 * - **Format** — the library's own MIME type, then the file's stream header, and only then what the
 *   player reports, and then only when it names a real codec. `audio/raw` and `audio/pcm` describe
 *   the decoder's output, never the file, so they are refused at every position rather than
 *   accepted and then discarded — which is what left the line with no format at all.
 * - **Sample rate** — the file's own header, then the library, then the player.
 * - **Bit depth** — the file's stream header, and nothing else. Media3's bit depth describes the
 *   PCM the decoder hands the sink, which is not the resolution the file holds; publishing it would
 *   be a wrong number wearing the costume of a measured one. The same conclusion Track Details
 *   already reached, and the reason this cannot be read from the player.
 */
internal fun resolveSongTechnicalIdentity(
    libraryMimeType: String?,
    runtimeMimeType: String?,
    librarySampleRateHz: Int?,
    runtimeSampleRateHz: Int?,
    reader: AudioMetadataReader.TechnicalAudioInfo?,
): SongTechnicalIdentity = SongTechnicalIdentity(
    format = sourceFormatToken(libraryMimeType)
        ?: readerFormatToken(reader?.format)
        ?: sourceFormatToken(runtimeMimeType),
    sampleRateHz = reader?.sampleRate?.takeIf { it > 0 }
        ?: librarySampleRateHz?.takeIf { it > 0 }
        ?: runtimeSampleRateHz?.takeIf { it > 0 },
    bitDepth = reader?.bitDepth?.takeIf { it > 0 },
)

/**
 * The identity of [song], read from the file once per track.
 *
 * The read is keyed on the song, so a track change discards the previous identity rather than
 * letting one track's format sit beside another's title, and the key doubles as the guard on the
 * result: a read that is still in flight when the song changes is cancelled before it can write.
 * While it is in flight the line shows the stable identity it already has and gains the bit depth
 * when the header arrives — an upgrade, never a regression.
 */
@Composable
internal fun rememberSongTechnicalIdentity(
    song: Song,
    runtimeMimeType: String?,
    runtimeSampleRateHz: Int?,
): SongTechnicalIdentity {
    val context = LocalContext.current
    var reader by remember(song.id) { mutableStateOf<AudioMetadataReader.TechnicalAudioInfo?>(null) }

    LaunchedEffect(song.id) {
        reader = withContext(Dispatchers.IO) {
            runCatching {
                val file = song.path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
                val uri = song.contentUriString.takeIf { it.isNotBlank() }
                when {
                    file != null -> AudioMetadataReader.readTechnical(file)
                    uri != null -> AudioMetadataReader.readTechnical(context, android.net.Uri.parse(uri))
                    else -> null
                }
            }.getOrNull()
        }
    }

    return resolveSongTechnicalIdentity(
        libraryMimeType = song.mimeType,
        runtimeMimeType = runtimeMimeType,
        librarySampleRateHz = song.sampleRate,
        runtimeSampleRateHz = runtimeSampleRateHz,
        reader = reader,
    )
}

/** What `mimeTypeToFormat` returns when it does not recognise the type. */
private const val NO_FORMAT_TOKEN = "-"

/**
 * A format token from a MIME type, or null when that MIME describes decoded output.
 *
 * The decoder-output check is a refusal, not a filter applied afterwards: a value that is refused
 * here lets the next authority answer, which is how the file's real codec survives.
 */
private fun sourceFormatToken(mimeType: String?): String? = mimeType
    ?.takeUnless(AudioMetaUtils::isDecoderOutputFormat)
    ?.let(AudioMetaUtils::mimeTypeToFormat)
    ?.takeIf { it != NO_FORMAT_TOKEN }
    ?.uppercase(Locale.US)

/** The reader's header token, e.g. `Flac` → `FLAC`. */
private fun readerFormatToken(format: String?): String? =
    format?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.US)
