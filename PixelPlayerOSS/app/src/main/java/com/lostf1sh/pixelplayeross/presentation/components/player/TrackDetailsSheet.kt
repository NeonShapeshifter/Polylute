package com.lostf1sh.pixelplayeross.presentation.components.player

import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.presentation.viewmodel.MusicBrainzIds
import com.lostf1sh.pixelplayeross.data.metadata.LibraryRepairPolicy
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.scoped.respectSystemBackEdgesForHorizontalDrag
import com.lostf1sh.pixelplayeross.presentation.components.scoped.systemBackEdgeInset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.media.Waveform
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabel
import com.lostf1sh.pixelplayeross.data.dac.fiio.ResonanceSource
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DirectOutputMode
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputDetails
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputRoute
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ReplayGainState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TrackDetailsUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapScreen
import com.lostf1sh.pixelplayeross.ui.ds.DapSkeletonBar
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapState
import com.lostf1sh.pixelplayeross.ui.ds.DapStateKind
import com.lostf1sh.pixelplayeross.ui.ds.DapTopBar
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.DapValue
import com.lostf1sh.pixelplayeross.ui.ds.DapValueRow
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAppAccent
import com.lostf1sh.pixelplayeross.utils.AudioMetaUtils
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToLong
import com.lostf1sh.pixelplayeross.utils.withoutPlaceholderMetadata
import com.lostf1sh.pixelplayeross.presentation.components.songTitleLabel
import com.lostf1sh.pixelplayeross.presentation.components.songArtistLabel
import com.lostf1sh.pixelplayeross.presentation.components.songAlbumLabel
import com.lostf1sh.pixelplayeross.presentation.components.AndroidRouteHintLine
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalBackNavigationMode
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes

/**
 * How often the waveform's position marker re-reads the player position. Four times a second is
 * enough for a marker that moves across a whole track, and keeps a playing track from doing
 * per-frame work on a screen that is not the transport.
 */
private const val POSITION_SAMPLE_INTERVAL_MS = 250L

/**
 * The Track Details sibling view: "what is this file?" and "how is PixelPlayer playing it?".
 *
 * Read-only. Nothing on this surface writes to the player, the route, the profile or the volume;
 * the waveform's seek control is the only interactive element and it delegates to the caller's
 * existing seek authority rather than holding a position of its own.
 */
@Composable
fun TrackDetailsSheet(
    state: TrackDetailsUiState,
    /**
     * Playback position, in milliseconds, read from the player's own position authority. Passed as
     * a provider rather than a value so the sheet can sample it on its own schedule instead of
     * recomposing on every position emission.
     */
    currentPositionProvider: () -> Long,
    /** Track duration in milliseconds, as the player reports it. Zero means "unknown". */
    totalDurationMs: Long,
    /** Delegates to the existing player seek authority. Never a position source of its own. */
    onSeek: (Long) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showsBackControl = LocalBackNavigationMode.current.showsVisibleBackAffordance
    DapBackScaffold(modifier.fillMaxSize(), onBack = onBackClick) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The bar that used to be the first child here was the only thing providing any
                // top spacing, and it did not actually provide the status-bar inset — the chevron
                // sat inside the status-bar band. The content takes the inset itself now.
                .statusBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = DapSpacing.MaxContentWidth)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DapSpacing.Gutter)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(DapSpacing.SectionGap)
                ) {
                    state.song?.let { song ->
                        Column(
                            Modifier.padding(
                                top = DapSpacing.Sm,
                                // The overlay's footprint, so the title begins beside the Back
                                // glyph rather than underneath it.
                                start = 0.dp,
                            )
                        ) {
                            Text(
                                text = songTitleLabel(song),
                                style = DapType.HeadingSmall,
                                color = Dap.primaryText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitle = listOfNotNull(
                                songArtistLabel(song),
                                songAlbumLabel(song)
                            ).joinToString(" \u00b7 ")
                            if (subtitle.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = subtitle,
                                    style = DapType.Caption,
                                    color = Dap.secondaryText,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    WaveformSection(
                        waveform = state.waveform,
                        isLoading = state.isLoadingWaveform,
                        songId = state.song?.id,
                        currentPositionProvider = currentPositionProvider,
                        totalDurationMs = totalDurationMs,
                        onSeek = onSeek
                    )

                    DetailGroup(title = stringResource(R.string.track_details_section_source)) {
                        DetailRow(
                            label = stringResource(R.string.track_details_format),
                            value = formatFormat(state)
                        )
                        formatBitrate(state)?.let {
                            DetailRow(label = stringResource(R.string.track_details_bitrate), value = it)
                        }
                        formatSampleRate(state.source.sampleRateHz)?.let {
                            DetailRow(label = stringResource(R.string.track_details_sample_rate), value = it)
                        }
                        state.source.bitDepth?.let {
                            DetailRow(
                                label = stringResource(R.string.track_details_bit_depth),
                                value = stringResource(R.string.track_details_bit_depth_value, it)
                            )
                        }
                        formatChannels(state.source.channels)?.let {
                            DetailRow(label = stringResource(R.string.track_details_channels), value = it)
                        }
                        formatDuration(state.source.durationMs)?.let {
                            DetailRow(label = stringResource(R.string.track_details_duration), value = it)
                        }
                        formatFileSize(state.source.fileSizeBytes)?.let {
                            DetailRow(label = stringResource(R.string.track_details_file_size), value = it)
                        }
                    }

                    DetailGroup(title = stringResource(R.string.track_details_section_output)) {
                        // The route is stated as what is carrying audio right now, never as a
                        // configured preference.
                        DetailRow(
                            label = stringResource(R.string.track_details_route),
                            value = routeText(state.output),
                            state = DapStateKind.Active
                        )
                        // B30-P4: what keeps playback off USB Direct, and how to stop it, for a DAC the app can ask (its EQ lives in the DAC and
                        // still applies on this route: the sentence says so). Never said when nothing is in the way.
                        val conflict = state.output.conflict
                        if (conflict != null && state.output.dacEq != DacEqLabel.NOT_APPLICABLE) {
                            AndroidRouteHintLine(
                                text = stringResource(conflict.textRes),
                                maxLines = com.lostf1sh.pixelplayeross.presentation.screens.CONFLICT_LINES,
                                modifier = Modifier.padding(vertical = DapSpacing.Sm),
                            )
                        }
                        state.output.deviceName?.let {
                            DetailRow(
                                label = stringResource(R.string.track_details_output_device),
                                value = it
                            )
                        }
                        // Only stated when the path physically read it back from the hardware.
                        state.output.confirmedRateHz?.let {
                            DetailRow(
                                label = stringResource(R.string.track_details_output_rate),
                                value = formatHz(it),
                                state = DapStateKind.Confirmed
                            )
                        }
                        // A property of the device, not a claim about this track.
                        state.output.deviceMixerRateHz?.let {
                            DetailRow(
                                label = stringResource(R.string.track_details_device_mixer_rate),
                                value = formatHz(it),
                                dim = true
                            )
                        }
                    }

                    DetailGroup(title = stringResource(R.string.track_details_section_processing)) {
                        DetailRow(
                            label = stringResource(R.string.track_details_equalizer),
                            value = equalizerLabel(state.equalizer),
                            state = equalizerMark(state.equalizer)
                        )
                        DetailRow(
                            label = stringResource(R.string.track_details_replaygain),
                            value = stringResource(replayGainLabel(state.replayGain)),
                            state = replayGainMark(state.replayGain)
                        )
                        // B30: the DAC's own EQ, as far as it could be read. Said for a DAC the app can ask (never for the Android
                        // route's speaker or a DAC outside the FiiO table), "unknown" included: that is what keeps Purity off.
                        if (state.output.dacEq != DacEqLabel.NOT_APPLICABLE) {
                            DetailRow(
                                label = stringResource(R.string.track_details_dac_eq),
                                value = stringResource(dacEqLabelText(state.output.dacEq)),
                                state = dacEqMark(state.output.dacEq)
                            )
                        }
                    }

                    // METADATA. The tags the song carries, as stored — no reformatting, no
                    // enrichment claims. Rows with no value are omitted rather than shown empty:
                    // a track with no genre has no genre row, and that is not a missing value the
                    // user needs told about.
                    state.song?.let { MetadataGroup(song = it, ids = state.musicBrainzIds) }

                    Text(
                        text = stringResource(R.string.track_details_read_only_note),
                        style = DapType.Note,
                        color = Dap.disabledText,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(DapSpacing.SectionGap))
                }
            }
        }
        // The Back affordance, floating over the sheet instead of in a bar. It is the overlay
        // and not the bar's own glyph because only the overlay consults the Back mode — in
        // Gestures the mode says a control that exists only to perform Back must not be drawn.
    }
}

@Composable
private fun WaveformSection(
    waveform: Waveform?,
    isLoading: Boolean,
    songId: String?,
    currentPositionProvider: () -> Long,
    totalDurationMs: Long,
    onSeek: (Long) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            waveform != null -> WaveformCanvas(
                waveform = waveform,
                songId = songId,
                currentPositionProvider = currentPositionProvider,
                totalDurationMs = totalDurationMs,
                onSeek = onSeek
            )

            // B31 P2.5: the decode of a long track takes seconds on the phone, and the strip held only a 2 dp line (the owner read it as an
            // empty band). It says what it is waiting for, above the same line.
            isLoading -> Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.track_details_waveform_loading),
                    style = DapType.Note,
                    color = Dap.disabledText,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                DapSkeletonBar(
                    width = 120.dp,
                    height = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            else -> Text(
                text = stringResource(R.string.track_details_waveform_unavailable),
                style = DapType.Note,
                color = Dap.disabledText,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Renders the envelope as one bar per bucket: the peak magnitude as the full bar and the RMS as a
 * brighter inner bar, so loud transients and sustained level are distinguishable at a glance.
 *
 * The vertical scale is absolute (full scale = full height). Nothing is normalised to the track's
 * own maximum, so a quiet master genuinely looks quiet and a silent track draws nothing.
 */
@Composable
private fun WaveformCanvas(
    waveform: Waveform,
    songId: String?,
    currentPositionProvider: () -> Long,
    totalDurationMs: Long,
    onSeek: (Long) -> Unit
) {
    val backEdge = systemBackEdgeInset()
    val playedColor = Dap.primaryText
    val unplayedColor = Dap.artPlaceholderDisabled
    val rmsPlayedColor = Dap.background
    val rmsUnplayedColor = Dap.artPlaceholderLow
    val markerColor = Dap.primaryText

    // The position marker samples the player's position on a slow timer rather than collecting
    // its flow, so a playing track does not recompose this screen several times a second. The
    // sampled value is read again inside the draw scope below, which lets Compose invalidate the
    // drawing alone.
    val sampledPositionMs = remember { mutableLongStateOf(0L) }
    LaunchedEffect(songId) {
        sampledPositionMs.longValue = currentPositionProvider()
        while (true) {
            delay(POSITION_SAMPLE_INTERVAL_MS)
            sampledPositionMs.longValue = currentPositionProvider()
        }
    }

    // While a drag is in flight the marker follows the finger from local state; the player is
    // only asked to seek once, on release. That keeps a drag from issuing a seek per frame.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    // 0 maps to the track start and 1 to its end. An unknown duration means the marker has no
    // meaningful place to sit, so it stays at the start rather than guessing.
    val seekToFraction: (Float) -> Unit = remember(totalDurationMs, onSeek) {
        { fraction ->
            if (totalDurationMs > 0L) {
                val target = (fraction.coerceIn(0f, 1f) * totalDurationMs).roundToLong()
                onSeek(target.coerceIn(0L, totalDurationMs))
            }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
            // A drag inward from the reserved Back edge is the system Back gesture, not a scrub.
            // Claiming it here keeps the seek from firing for the same motion.
            .respectSystemBackEdgesForHorizontalDrag()
            .pointerInput(waveform, totalDurationMs) {
                detectTapGestures { offset ->
                    // The reserved Back strip is not ours to seek from either. A tap reports the
                    // true down position, so this is an exact test.
                    if (!backEdge.owns(offset.x, size.width.toFloat())) {
                        seekToFraction(offset.x / size.width.toFloat())
                    }
                }
            }
            .pointerInput(waveform, totalDurationMs) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scrubFraction?.let(seekToFraction)
                        scrubFraction = null
                    },
                    onDragCancel = { scrubFraction = null }
                ) { change, _ ->
                    scrubFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                }
            }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val bucketCount = waveform.bucketCount
        if (bucketCount <= 0 || width <= 0f) return@Canvas

        val barPitch = width / bucketCount
        val barWidth = (barPitch * 0.72f).coerceAtLeast(0.7f)
        val halfHeight = height / 2f

        val liveFraction = if (totalDurationMs > 0L) {
            (sampledPositionMs.longValue.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val shownFraction = scrubFraction ?: liveFraction
        val playedX = width * shownFraction

        for (index in 0 until bucketCount) {
            val x = index * barPitch
            val played = x <= playedX

            val peak = waveform.peaks[index].coerceIn(0f, 1f)
            val rms = waveform.rms[index].coerceIn(0f, 1f)

            // A silent bucket draws nothing at all rather than a 1px hairline, so silence is
            // visibly empty instead of looking like a quiet passage.
            if (peak > 0f) {
                val peakHalf = (peak * halfHeight).coerceAtLeast(0.5f)
                drawRect(
                    color = if (played) playedColor else unplayedColor,
                    topLeft = Offset(x, centerY - peakHalf),
                    size = Size(barWidth, peakHalf * 2f)
                )
            }
            if (rms > 0f) {
                val rmsHalf = (rms * halfHeight).coerceAtLeast(0.5f)
                drawRect(
                    color = if (played) rmsPlayedColor else rmsUnplayedColor,
                    topLeft = Offset(x, centerY - rmsHalf),
                    size = Size(barWidth, rmsHalf * 2f)
                )
            }
        }

        drawLine(
            color = markerColor,
            start = Offset(playedX, 0f),
            end = Offset(playedX, height),
            strokeWidth = 2.dp.toPx()
        )
    }
}

@Composable
private fun DetailGroup(title: String, content: @Composable () -> Unit) {
    DapGroup(title = title) { content() }
}

/**
 * A technical row.
 *
 * [state] is only ever what the runtime actually reports: a confirmed output rate, the route that
 * is carrying audio, whether processing is applied, bypassed or simply not requested. Rows with no
 * state carry no mark.
 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    state: DapStateKind? = null,
    dim: Boolean = false,
) {
    DapValueRow(label = label) {
        if (state != null) {
            // B31 P3.1: the marks of Details are facts about the signal; they wear the app's accent, never a cover's.
            CompositionLocalProvider(LocalDapAccent provides LocalDapAppAccent.current) {
                DapState(kind = state, label = value)
            }
        } else {
            DapValue(value, color = if (dim) Dap.tertiaryText else Dap.primaryText)
        }
    }
}

/**
 * The song's stored tags, plus the MusicBrainz identifiers when the track has been enriched.
 *
 * Nothing here is derived or guessed. A row is drawn only when the song actually carries a value,
 * so an untagged track shows a shorter section rather than a column of placeholders — the brief's
 * rule that a missing optional field is not a value.
 */
@Composable
private fun MetadataGroup(song: Song, ids: MusicBrainzIds?) {
    val rows = buildList {
        // `withoutPlaceholderMetadata` rather than `takeIf { it.isNotBlank() }`: this section's
        // stated contract is that it draws no placeholders, and a stored `<unknown>` is not blank,
        // so the blank test let one through as if it were a fact about the track.
        add(R.string.track_details_meta_title to song.title.withoutPlaceholderMetadata())
        add(R.string.song_field_artist to song.displayArtist.withoutPlaceholderMetadata())
        add(R.string.song_field_album to song.album.withoutPlaceholderMetadata())
        add(R.string.song_field_album_artist to song.albumArtist.withoutPlaceholderMetadata())
        // `trackLabel` unpacks a stored `disc * 1000 + track` before it prints it, so a library that
        // has not been repaired yet still reads "1-12" and never "1-1012" (FIX-3).
        LibraryRepairPolicy.trackLabel(song.trackNumber, song.discNumber)?.let { label ->
            add(R.string.song_field_track_number to label)
        }
        song.discNumber?.takeIf { it > 0 && song.trackNumber <= 0 }?.let { disc ->
            add(R.string.song_field_disc_number to disc.toString())
        }
        song.year.takeIf { it > 0 }?.let { add(R.string.song_field_year to it.toString()) }
        add(R.string.song_field_genre to song.genre?.takeIf { it.isNotBlank() })
    }.mapNotNull { (labelRes, value) -> value?.let { labelRes to it } }

    if (rows.isEmpty() && ids?.isPresent != true) return

    DetailGroup(title = stringResource(R.string.track_details_section_metadata)) {
        rows.forEach { (labelRes, value) ->
            DetailRow(label = stringResource(labelRes), value = value)
        }
        // Identifiers are opaque and long; they wrap rather than ellipsise, because half a UUID
        // is worse than none. Mono, because they are identifiers.
        ids?.recordingId?.takeIf { it.isNotBlank() }?.let {
            IdentifierRow(label = stringResource(R.string.track_details_mb_recording), value = it)
        }
        ids?.releaseId?.takeIf { it.isNotBlank() }?.let {
            IdentifierRow(label = stringResource(R.string.track_details_mb_release), value = it)
        }
        ids?.artistId?.takeIf { it.isNotBlank() }?.let {
            IdentifierRow(label = stringResource(R.string.track_details_mb_artist), value = it)
        }
    }
}

/** A long opaque identifier: label above, wrapped mono value below. */
@Composable
private fun IdentifierRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = DapType.SettingLabel,
            color = Dap.secondaryText,
            modifier = Modifier.padding(top = DapSpacing.Sm),
        )
        Text(
            text = value,
            style = DapType.ValueSmall,
            color = Dap.primaryText,
            modifier = Modifier.padding(bottom = DapSpacing.Sm),
        )
        DapDivider()
    }
}

/** Codec/container token from real stream metadata; never inferred from the file extension. */
private fun formatFormat(state: TrackDetailsUiState): String {
    val format = state.source.format?.uppercase(Locale.getDefault())
    // The library's own type is the last resort, and even then not when it names decoded PCM
    // rather than a stored codec: this screen describes the file, not the player's output.
    val fromLibrary = state.song?.mimeType
        ?.takeUnless(AudioMetaUtils::isDecoderOutputFormat)
        ?.substringAfter('/')
        ?.uppercase(Locale.getDefault())
    return format ?: fromLibrary ?: "—"
}

private fun formatBitrate(state: TrackDetailsUiState): String? =
    state.source.bitrateBps?.takeIf { it > 0 }?.let {
        String.format(Locale.getDefault(), "%,d kbps", it / 1000)
    }

internal fun formatSampleRate(hz: Int?): String? =
    hz?.takeIf { it > 0 }?.let { formatHz(it) }

/**
 * A sample rate as a technical reading: `48 kHz`, `44.1 kHz`, `96 kHz`.
 *
 * `Locale.US` rather than the default locale, because a comma decimal separator turns `44,1 kHz`
 * into something that reads as a thousands-separated integer. Rates are the one number on these
 * surfaces where the separator is a correctness question, not a preference.
 *
 * This is the single implementation for both details surfaces. Song Info used to format rates
 * itself, always with one decimal, so the same 48 kHz source read `48 kHz` in Track Details and
 * `48.0 kHz` next to it.
 */
internal fun formatHz(hz: Int): String = when {
    hz % 1000 == 0 -> "${hz / 1000} kHz"
    else -> String.format(Locale.US, "%.1f kHz", hz / 1000.0)
}

/**
 * A bit depth as a technical reading: `16-bit`, `24-bit`.
 *
 * The compact token the Now Playing identity line uses. Track Details' own row spells it out
 * ("24 bits") because it is a labelled value there, but the number and the unit are the same
 * reading, and both come from the file's stream header rather than from anything the decoder
 * inferred.
 */
internal fun formatBitDepth(bits: Int?): String? =
    bits?.takeIf { it > 0 }?.let { "$it-bit" }

/** A bitrate as a technical reading: `1,411 kbps`. */
internal fun formatKbps(bps: Int): String =
    String.format(Locale.US, "%,d kbps", bps / 1000)

internal fun formatChannels(channels: Int?): String? = when (channels) {
    null -> null
    1 -> "Mono"
    2 -> "Stereo"
    else -> "$channels ch"
}

internal fun formatDuration(ms: Long?): String? {
    val total = ms?.takeIf { it > 0 } ?: return null
    val totalSeconds = total / 1000
    return String.format(
        Locale.getDefault(),
        "%d:%02d",
        totalSeconds / 60,
        totalSeconds % 60
    )
}

internal fun formatFileSize(bytes: Long?): String? {
    val value = bytes?.takeIf { it > 0 } ?: return null
    val mb = value / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.getDefault(), "%.1f MB", mb)
    } else {
        String.format(Locale.getDefault(), "%,d KB", value / 1024)
    }
}

/**
 * B29-L4: the route in plain words. USB Direct carries the stream: "USB Direct · Purity" or "USB Direct · Resonance · <source>"
 * (plain "USB Direct" while the mode is not known: B30, a DAC whose EQ could not be read gets neither word). Anything else is the
 * Android route, named as such, and says nothing about Purity or Resonance, which exist only on USB Direct.
 */
@Composable
private fun routeText(output: OutputDetails): String = when (output.route) {
    OutputRoute.USB_DIRECT -> stringResource(
        when (output.directMode) {
            DirectOutputMode.PURITY -> R.string.track_details_route_usb_direct_purity
            // B30: Resonance says which source shapes the sound; without one on record (an older caller) it is the plain word.
            DirectOutputMode.RESONANCE -> when (output.resonanceSource) {
                ResonanceSource.DAC_EQ -> R.string.track_details_route_usb_direct_resonance_dac
                ResonanceSource.APP_DSP -> R.string.track_details_route_usb_direct_resonance_app
                ResonanceSource.BOTH -> R.string.track_details_route_usb_direct_resonance_both
                null -> R.string.track_details_route_usb_direct_resonance
            }
            null -> R.string.track_details_route_usb_direct
        }
    )
    OutputRoute.ANDROID_AUDIO -> stringResource(R.string.track_details_route_android)
    else -> stringResource(R.string.track_details_route_android_via, stringResource(routeLabel(output.route)))
}

private fun routeLabel(route: OutputRoute): Int = when (route) {
    OutputRoute.USB_DIRECT -> R.string.track_details_route_usb_direct
    OutputRoute.USB_ANDROID -> R.string.track_details_route_usb_android
    OutputRoute.BLUETOOTH -> R.string.track_details_route_bluetooth
    OutputRoute.WIRED -> R.string.track_details_route_wired
    OutputRoute.SPEAKER -> R.string.track_details_route_speaker
    OutputRoute.ANDROID_AUDIO -> R.string.track_details_route_android
}

/**
 * Equalizer truth.
 *
 * Configured-but-not-in-the-signal is bypassed, which is a different statement from off. The
 * transparent path structurally removes the processing chain, so an equalizer configured while
 * USB Direct carries audio is reported as bypassed and never as applied.
 */
private fun equalizerMark(state: EqualizerState): DapStateKind = when (state) {
    EqualizerState.OFF -> DapStateKind.Off
    EqualizerState.GRAPHIC_10, EqualizerState.GRAPHIC_15 -> DapStateKind.Selected
    EqualizerState.BYPASSED_USB_DIRECT -> DapStateKind.Bypassed
}

private fun dacEqLabelText(label: DacEqLabel): Int = when (label) {
    DacEqLabel.ON -> R.string.track_details_dac_eq_on
    DacEqLabel.OFF_OR_FLAT -> R.string.track_details_dac_eq_off
    DacEqLabel.UNKNOWN, DacEqLabel.NOT_APPLICABLE -> R.string.track_details_dac_eq_unknown
}

/** Shaping is a state of the sound, not a fault; unknown is the one that warrants attention. */
private fun dacEqMark(label: DacEqLabel): DapStateKind = when (label) {
    DacEqLabel.ON -> DapStateKind.Selected
    DacEqLabel.OFF_OR_FLAT -> DapStateKind.Off
    DacEqLabel.UNKNOWN, DacEqLabel.NOT_APPLICABLE -> DapStateKind.Attention
}

/** Requested but not applied is the one state here that warrants attention. */
private fun replayGainMark(state: ReplayGainState): DapStateKind = when (state) {
    ReplayGainState.NOT_REQUESTED -> DapStateKind.Off
    ReplayGainState.TRACK_APPLIED, ReplayGainState.ALBUM_APPLIED -> DapStateKind.Selected
    ReplayGainState.REQUESTED_NOT_APPLIED -> DapStateKind.Attention
}

@Composable
private fun equalizerLabel(state: EqualizerState): String = when (state) {
    EqualizerState.OFF -> stringResource(R.string.track_details_eq_off)
    EqualizerState.GRAPHIC_10 -> stringResource(R.string.track_details_eq_graphic, 10)
    EqualizerState.GRAPHIC_15 -> stringResource(R.string.track_details_eq_graphic, 15)
    EqualizerState.BYPASSED_USB_DIRECT ->
        stringResource(R.string.track_details_eq_bypassed_direct)
}

private fun replayGainLabel(state: ReplayGainState): Int = when (state) {
    ReplayGainState.NOT_REQUESTED -> R.string.track_details_rg_off
    ReplayGainState.TRACK_APPLIED -> R.string.track_details_rg_track_applied
    ReplayGainState.ALBUM_APPLIED -> R.string.track_details_rg_album_applied
    ReplayGainState.REQUESTED_NOT_APPLIED -> R.string.track_details_rg_requested_not_applied
}
