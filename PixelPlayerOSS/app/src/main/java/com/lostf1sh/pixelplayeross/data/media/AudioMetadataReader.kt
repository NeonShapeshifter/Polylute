package com.lostf1sh.pixelplayeross.data.media

import com.lostf1sh.pixelplayeross.data.metadata.MetadataNormalizer
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.kyant.taglib.TagLib
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import timber.log.Timber
import java.io.File

data class AudioMetadata(
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val genre: String?,
    val composer: String?,
    val lyrics: String?,
    val durationMs: Long?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val artwork: AudioMetadataArtwork?,
    val replayGainTrackGainDb: Float? = null,
    val replayGainAlbumGainDb: Float? = null,
    /** Channel count as reported by the tag/stream header. Free from TagLib. */
    val channels: Int? = null,
    /** Bit depth as reported by the stream header, when the format carries it. */
    val bitDepth: Int? = null,
    /** Lossless/container token as reported by the reader header (e.g. "Flac"), not the extension. */
    val containerFormat: String? = null,
    /** Raw tag field keys that were present, for diagnostics. */
    val tagFieldKeys: Set<String> = emptySet()
)

data class AudioMetadataArtwork(
    val bytes: ByteArray,
    val mimeType: String?
)

object AudioMetadataReader {

    private const val TAG = "AudioMetadataReader"

    /**
     * Reads metadata straight from a content URI's file descriptor.
     *
     * This deliberately does NOT copy the audio file into a temp file first. The previous
     * implementation copied the entire file (tens to hundreds of MB for hi-res lossless)
     * into cacheDir before TagLib could read a few KB of tags. That was slow, filled the
     * cache, and leaked whole-song temp files whenever the process died mid-copy.
     */
    fun read(context: Context, uri: Uri, readArtwork: Boolean = true): AudioMetadata? {
        val file = fileForUri(context, uri)
        return read(
            openFd = { context.contentResolver.openFileDescriptor(uri, "r") },
            file = file,
            readArtwork = readArtwork,
            supplement = true
        )
    }

    fun read(file: File, readArtwork: Boolean = true, supplement: Boolean = false): AudioMetadata? {
        return read(
            openFd = { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) },
            file = file,
            readArtwork = readArtwork,
            supplement = supplement
        )
    }

    /**
     * Only the release year of a file, from its tags: one open, no pictures, no fallback reader.
     *
     * This is what the library's year repair runs over thousands of files, so it asks TagLib for the
     * tag map once and nothing else. [read] opens the file twice and may fall back to the slow Java
     * tagger.
     */
    fun readYear(file: File): Int? = runCatching {
        openPfd { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }?.use { fd ->
            TagLib.getMetadata(fd.detachFd(), readPictures = false)?.propertyMap
        }
    }.getOrNull()?.let(::yearFromPropertyMap)

    /** What reading a file's year tag found (B12a-8 #4): a year, a file that was read and has none, or one that could not be read. */
    sealed interface YearTagRead {
        data class Found(val year: Int) : YearTagRead

        /** The tags were read and carry no valid year: a fact about the file, kept until the file changes. */
        data object NoYearTag : YearTagRead

        /** The file could not be read now (an I/O error, a busy file, the tag library found nothing): a fact about the moment. */
        data object Failed : YearTagRead
    }

    /**
     * [readYear] with the difference [readYear] throws away. Its `null` meant both "there is no year tag" and "the
     * read failed", so a transient error was recorded as a file without a year and never asked again until the file
     * changed. The same single open and no pictures; the property map is what separates the two.
     */
    fun readYearTag(file: File): YearTagRead {
        val propertyMap = try {
            openPfd { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }?.use { fd ->
                TagLib.getMetadata(fd.detachFd(), readPictures = false)?.propertyMap
            }
        } catch (e: Exception) {
            null
        } ?: return YearTagRead.Failed
        return yearFromPropertyMap(propertyMap)?.let(YearTagRead::Found) ?: YearTagRead.NoYearTag
    }

    /** The tag keys a release year can live under, best first. */
    private val YEAR_KEYS = listOf("DATE", "YEAR", "ORIGINALDATE", "ORIGINALYEAR", "RELEASEDATE")

    /** The first valid year among [YEAR_KEYS]; pure, so every spelling can be tested. */
    internal fun yearFromPropertyMap(propertyMap: Map<String, Array<String>>): Int? =
        YEAR_KEYS.firstNotNullOfOrNull { key ->
            propertyMap[key]?.firstNotNullOfOrNull { MetadataNormalizer.parseYearTag(it) }
        }

    /**
     * Raw tag field map, read without copying the file. Used by lyrics resolution, which
     * needs to choose between several embedded lyrics fields rather than one string.
     */
    fun readPropertyMap(context: Context, uri: Uri): Map<String, Array<String>>? = runCatching {
        openPfd { context.contentResolver.openFileDescriptor(uri, "r") }?.use { fd ->
            TagLib.getMetadata(fd.detachFd(), readPictures = false)?.propertyMap
        }
    }.getOrElse {
        Timber.tag(TAG).w(it, "Unable to read tag property map for: $uri")
        null
    }

    fun readPropertyMap(file: File): Map<String, Array<String>>? = runCatching {
        openPfd { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }?.use { fd ->
            TagLib.getMetadata(fd.detachFd(), readPictures = false)?.propertyMap
        }
    }.getOrElse {
        Timber.tag(TAG).w(it, "Unable to read tag property map for: ${file.name}")
        null
    }

    private inline fun openPfd(open: () -> ParcelFileDescriptor?): ParcelFileDescriptor? =
        try {
            open()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to open file descriptor")
            null
        }

    private fun read(
        openFd: () -> ParcelFileDescriptor?,
        file: File?,
        readArtwork: Boolean,
        supplement: Boolean = false
    ): AudioMetadata? {
        return try {
            // A fresh descriptor per TagLib call: the native side takes ownership of the raw fd.
            val audioProperties = openPfd(openFd)?.use { fd ->
                TagLib.getAudioProperties(fd.detachFd())
            }
            val durationMs = audioProperties?.length?.takeIf { it > 0 }?.let { it * 1000L }
            val bitrate = audioProperties?.bitrate?.takeIf { it > 0 }?.let { it * 1000 }
            val sampleRate = audioProperties?.sampleRate?.takeIf { it > 0 }
            val channels = audioProperties?.channels?.takeIf { it > 0 }

            val metadata = openPfd(openFd)?.use { fd ->
                TagLib.getMetadata(fd.detachFd(), readPictures = false)
            }
            val propertyMap = metadata?.propertyMap ?: emptyMap()

            val title = propertyMap["TITLE"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val artist = propertyMap["ARTIST"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val albumArtist = propertyMap["ALBUMARTIST"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: propertyMap["ALBUM ARTIST"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: propertyMap["BAND"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val album = propertyMap["ALBUM"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val genre = propertyMap["GENRE"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val composer = propertyMap["COMPOSER"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: propertyMap["TCOM"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val lyrics = firstEmbeddedLyricsField(propertyMap)
            val trackString = propertyMap["TRACKNUMBER"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: propertyMap["TRACK"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val trackNumber = trackString?.substringBefore('/')?.toIntOrNull()
            val discString = propertyMap["DISCNUMBER"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: propertyMap["DISC"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val discNumber = discString?.substringBefore('/')?.toIntOrNull()
            val year = yearFromPropertyMap(propertyMap)
            val replayGainTrackGainDb = extractReplayGainDb(
                propertyMap = propertyMap,
                keys = listOf("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_GAIN_DB", "R128_TRACK_GAIN")
            )
            val replayGainAlbumGainDb = extractReplayGainDb(
                propertyMap = propertyMap,
                keys = listOf("REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_GAIN_DB", "R128_ALBUM_GAIN")
            )

            val artwork = if (readArtwork) {
                val pictures = openPfd(openFd)?.use { fd -> TagLib.getPictures(fd.detachFd()) }
                pictures?.firstOrNull()?.let { picture ->
                    picture.data.takeIf { it.isNotEmpty() && isValidImageData(it) }?.let { data ->
                        AudioMetadataArtwork(
                            bytes = data,
                            mimeType = picture.mimeType.takeIf { it.isNotBlank() } ?: guessImageMimeType(data)
                        )
                    }
                }
            } else {
                null
            }

            // The pure-Java reader covers tags TagLib maps poorly and is the only source of
            // stream bit depth. It is expensive, so it is opt-in via [supplement]: callers that
            // want lyrics/composer/bit depth ask for it, bulk scans do not. Historically it ran
            // only when title/artist/artwork were missing, which meant a well-tagged file whose
            // embedded lyrics TagLib did not surface could never be rescued.
            val needsFallback = title == null || artist == null || (readArtwork && artwork == null) ||
                (supplement && (lyrics == null || composer == null))
            val fallback = if (needsFallback && file != null) {
                readWithJAudioTagger(file)
            } else {
                null
            }

            AudioMetadata(
                title = title ?: fallback?.title,
                artist = artist ?: fallback?.artist,
                albumArtist = albumArtist ?: fallback?.albumArtist,
                album = album ?: fallback?.album,
                genre = genre ?: fallback?.genre,
                composer = composer ?: fallback?.composer,
                lyrics = lyrics ?: fallback?.lyrics,
                durationMs = durationMs ?: fallback?.durationMs,
                trackNumber = trackNumber ?: fallback?.trackNumber,
                discNumber = discNumber ?: fallback?.discNumber,
                year = year ?: fallback?.year,
                bitrate = bitrate ?: fallback?.bitrate,
                sampleRate = sampleRate ?: fallback?.sampleRate,
                artwork = artwork ?: fallback?.artwork,
                replayGainTrackGainDb = replayGainTrackGainDb ?: fallback?.replayGainTrackGainDb,
                replayGainAlbumGainDb = replayGainAlbumGainDb ?: fallback?.replayGainAlbumGainDb,
                channels = channels ?: fallback?.channels,
                bitDepth = fallback?.bitDepth,
                containerFormat = fallback?.containerFormat,
                tagFieldKeys = propertyMap.keys
            )
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Unable to read metadata from file: ${file?.absolutePath}")
            null
        }
    }

    /**
     * Returns the first non-blank embedded lyrics field, preferring synchronized content.
     * The key list is intentionally broad: taggers disagree on where lyrics live.
     */
    private fun firstEmbeddedLyricsField(propertyMap: Map<String, Array<String>>): String? {
        var firstPlain: String? = null
        EMBEDDED_LYRICS_KEYS.forEach { key ->
            propertyMap[key].orEmpty().forEach { field ->
                val trimmed = field.trim()
                if (trimmed.isBlank()) return@forEach
                if (looksSynchronized(trimmed)) return trimmed
                if (firstPlain == null) firstPlain = trimmed
            }
        }
        return firstPlain
    }

    private fun looksSynchronized(value: String): Boolean = LRC_TIMESTAMP.containsMatchIn(value)

    private val LRC_TIMESTAMP = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")

    private val EMBEDDED_LYRICS_KEYS = listOf(
        "LYRICS",
        "SYNCEDLYRICS",
        "SYNCED LYRICS",
        "UNSYNCEDLYRICS",
        "UNSYNCED LYRICS",
        "TTML",
        "LYRIC"
    )

    /**
     * Fallback reader using JAudioTagger for files where TagLib can't map ID3 frames.
     * Called when TagLib leaves key metadata or requested artwork unresolved.
     */
    private fun readWithJAudioTagger(file: File): AudioMetadata? {
        return try {
            java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.OFF

            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag
            val header = audioFile.audioHeader

            val title = tag?.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() }
            val artist = tag?.getFirst(FieldKey.ARTIST)?.takeIf { it.isNotBlank() }
            val albumArtist = tag?.getFirst(FieldKey.ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
            val album = tag?.getFirst(FieldKey.ALBUM)?.takeIf { it.isNotBlank() }
            val genre = tag?.getFirst(FieldKey.GENRE)?.takeIf { it.isNotBlank() }
            val composer = tag?.getFirst(FieldKey.COMPOSER)?.takeIf { it.isNotBlank() }
            val lyrics = tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
            val trackNumber = tag?.getFirst(FieldKey.TRACK)?.takeIf { it.isNotBlank() }
                ?.substringBefore('/')?.toIntOrNull()
            val discNumber = tag?.getFirst(FieldKey.DISC_NO)?.takeIf { it.isNotBlank() }
                ?.substringBefore('/')?.toIntOrNull()
            val year = tag?.getFirst(FieldKey.YEAR)?.takeIf { it.isNotBlank() }
                ?.take(4)?.toIntOrNull()

            val durationMs = plausibleDurationMs(
                trackLength = runCatching { header?.trackLength }.getOrNull(),
                format = runCatching { header?.format }.getOrNull()
            )
            val bitrate = header?.bitRateAsNumber?.takeIf { it > 0 }?.toInt()?.let { it * 1000 }
            val sampleRate = header?.sampleRateAsNumber?.takeIf { it > 0 }
            val channels = runCatching { header?.channels?.trim()?.toIntOrNull() }.getOrNull()?.takeIf { it > 0 }
            val bitDepth = runCatching { header?.bitsPerSample }.getOrNull()?.takeIf { it > 0 }

            val artwork = tag?.firstArtwork?.let { art ->
                art.binaryData?.takeIf { it.isNotEmpty() && isValidImageData(it) }?.let { data ->
                    AudioMetadataArtwork(
                        bytes = data,
                        mimeType = art.mimeType?.takeIf { it.isNotBlank() } ?: guessImageMimeType(data)
                    )
                }
            }

            AudioMetadata(
                title = title,
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                genre = genre,
                composer = composer,
                lyrics = lyrics,
                durationMs = durationMs,
                trackNumber = trackNumber,
                discNumber = discNumber,
                year = year,
                bitrate = bitrate,
                sampleRate = sampleRate,
                artwork = artwork,
                channels = channels,
                bitDepth = bitDepth,
                containerFormat = runCatching { header?.format }.getOrNull()
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "JAudioTagger fallback FAILED for: ${file.name}")
            null
        }
    }

    /** Best-effort File for a URI, used only for the pure-Java reader and file size. */
    private fun fileForUri(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                if (index < 0) return@use null
                cursor.getString(index)?.let(::File)
            }
        }.getOrNull()
    }

    /**
     * Technical stream properties read from the container/codec itself, for Track Details.
     *
     * Every field is nullable and stays null when the reader genuinely does not know it. Bit
     * depth in particular only ever comes from the pure-Java reader's stream header — it is never
     * inferred from the file extension, because a `.flac` can hold 16- or 24-bit audio and a
     * `.m4a` can hold AAC or ALAC.
     */
    data class TechnicalAudioInfo(
        /** Reader header format token, e.g. "Flac", "MP3", "MP4". Not the file extension. */
        val format: String? = null,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        val bitrate: Int? = null,
        val channels: Int? = null,
        val durationMs: Long? = null
    )

    fun readTechnical(file: File): TechnicalAudioInfo? = runCatching {
        val header = AudioFileIO.read(file).audioHeader
        TechnicalAudioInfo(
            format = runCatching { header?.format }.getOrNull()?.let(::plausibleFormatName),
            sampleRate = runCatching { header?.sampleRateAsNumber }.getOrNull()?.takeIf { it > 0 },
            bitDepth = runCatching { header?.bitsPerSample }.getOrNull()?.takeIf { it > 0 },
            bitrate = runCatching { header?.bitRateAsNumber }.getOrNull()?.takeIf { it > 0 }?.toInt()?.times(1000),
            channels = runCatching { header?.channels?.trim()?.toIntOrNull() }.getOrNull()?.takeIf { it > 0 },
            durationMs = plausibleDurationMs(
                trackLength = runCatching { header?.trackLength }.getOrNull(),
                format = runCatching { header?.format }.getOrNull()
            )
        ).takeIf { info ->
            listOf(info.sampleRate, info.bitDepth, info.bitrate, info.channels, info.durationMs)
                .any { it != null }
        }
    }.getOrElse {
        Timber.tag(TAG).w(it, "Technical read failed for: ${file.name}")
        null
    }

    /**
     * A format name, or null when the reader did not really produce one.
     *
     * The tag reader falls back to punctuation for containers it cannot label — an MP4 carrying
     * E-AC-3 comes back as "*" — and publishing that as the codec would be worse than publishing
     * nothing, since the player's own decoder can name the stream it is rendering. Only a name
     * that contains a letter is treated as a name.
     */
    internal fun plausibleFormatName(raw: String?): String? =
        raw?.trim()?.takeIf { trimmed -> trimmed.any(Char::isLetter) }

    /**
     * A duration in milliseconds from a tag reader, or null when the reader is not entitled to one.
     *
     * The reader's length is only meaningful for containers it can name. When it cannot — an MP4
     * carrying E-AC-3 comes back unlabelled — the number it reports is the container's timebase
     * rather than seconds, so a twelve-second file would be published as one of three hours and
     * twenty minutes. Publishing nothing instead lets a caller that really knows the length, such
     * as the player measuring the track it is decoding, supply it.
     */
    internal fun plausibleDurationMs(trackLength: Int?, format: String?): Long? =
        trackLength
            ?.takeIf { it > 0 && plausibleFormatName(format) != null }
            ?.let { it * 1000L }

    fun readTechnical(context: Context, uri: Uri): TechnicalAudioInfo? {
        val file = fileForUri(context, uri) ?: return null
        return readTechnical(file)
    }

    /**
     * Credits as the file itself declares them.
     *
     * Every field stays null when the tag is absent, so the UI can omit the row rather than
     * printing "Composer: Unknown". Nothing here is looked up online — the point of the screen is
     * to show what this file carries.
     */
    data class AudioCredits(
        val performingArtist: String? = null,
        val albumArtist: String? = null,
        val composer: String? = null,
        val writer: String? = null,
        val producer: String? = null,
        val conductor: String? = null,
        val label: String? = null,
        val copyright: String? = null
    ) {
        val isEmpty: Boolean
            get() = listOf(
                performingArtist, albumArtist, composer, writer, producer, conductor, label, copyright
            ).all { it.isNullOrBlank() }
    }

    /** Reads credits from whichever source is available. Prefers a real file when there is one. */
    fun readCredits(context: Context, uri: Uri?, file: File?): AudioCredits? {
        val propertyMap = when {
            file != null -> readPropertyMap(file)
            uri != null -> readPropertyMap(context, uri)
            else -> null
        }
        return propertyMap?.let(::creditsFrom)
    }

    /**
     * Maps a tag property map onto credits. Several keys are tried per field because taggers
     * disagree — Vorbis writes `LABEL` where ID3 writes `TPUB`, and a conductor can be
     * `CONDUCTOR` or `TPE3`.
     */
    fun creditsFrom(propertyMap: Map<String, Array<String>>): AudioCredits = AudioCredits(
        performingArtist = firstTag(propertyMap, "ARTIST", "PERFORMER"),
        albumArtist = firstTag(propertyMap, "ALBUMARTIST", "ALBUM ARTIST", "BAND"),
        composer = firstTag(propertyMap, "COMPOSER", "TCOM"),
        writer = firstTag(propertyMap, "WRITER", "LYRICIST", "TEXT_WRITER", "LYRICS_BY"),
        producer = firstTag(propertyMap, "PRODUCER", "TPE4"),
        conductor = firstTag(propertyMap, "CONDUCTOR", "TPE3"),
        label = firstTag(propertyMap, "LABEL", "PUBLISHER", "TPUB", "ORGANIZATION"),
        copyright = firstTag(propertyMap, "COPYRIGHT", "TCOP")
    )

    /** First non-blank value among [keys], joined across multi-value tags. */
    private fun firstTag(propertyMap: Map<String, Array<String>>, vararg keys: String): String? {
        for (key in keys) {
            val values = propertyMap[key]
                ?: propertyMap.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
                ?: continue
            val joined = values.mapNotNull { it.trim().takeIf(String::isNotBlank) }
                .distinct()
                .joinToString(", ")
            if (joined.isNotBlank()) return joined
        }
        return null
    }

    /** File size in bytes, resolved without copying. */
    fun resolveFileSize(context: Context, uri: Uri, file: File?): Long? {
        file?.takeIf { it.exists() && it.isFile }?.let { return it.length() }
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) return@use null
                cursor.getLong(index).takeIf { it > 0 }
            }
        }.getOrNull()
    }

    private fun extractReplayGainDb(
        propertyMap: Map<String, Array<String>>,
        keys: List<String>
    ): Float? {
        for (key in keys) {
            val rawValue = propertyMap[key]?.firstOrNull() ?: continue
            val parsedValue = parseReplayGainDb(rawValue)
            if (parsedValue != null) {
                return parsedValue
            }
        }
        return null
    }

    private fun parseReplayGainDb(rawValue: String?): Float? {
        val cleanedValue = rawValue
            ?.trim()
            ?.replace(',', '.')
            ?.replace(Regex("(?i)[dD][bB]"), "")
            ?.trim()
            ?: return null
        return cleanedValue.toFloatOrNull()
    }
}
