package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.toSong
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.audio.dsp.HeadphoneGraphicEqController
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabel
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabelSource
import com.lostf1sh.pixelplayeross.data.dac.fiio.ResonanceSource
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.media.Waveform
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqualizerCoordinator
import com.lostf1sh.pixelplayeross.data.repository.WaveformRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.ReplayGainRuntimeState
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import com.lostf1sh.pixelplayeross.utils.AudioMetaUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject

/** How the current track is being routed out of the device right now. */
enum class OutputRoute { USB_DIRECT, USB_ANDROID, BLUETOOTH, WIRED, SPEAKER, ANDROID_AUDIO }

/** What a USB Direct stream carries (B29-L4): the untouched signal, or the signal with the application's DSP. */
enum class DirectOutputMode { PURITY, RESONANCE }

/**
 * The mode of the stream when USB Direct is carrying it, null otherwise (the Android route has no such mode, and Details
 * must not say it has). Asked of [AudioStatus], the one account of the route every screen reads, so Details cannot say
 * "Purity" where the Audio page says "Resonance": [AudioStatus.purityHolds] and [AudioStatus.resonanceSource] both require
 * the route to say Direct is active.
 *
 * B30: Purity also needs the DAC's own EQ known off or flat ([dacEq]); Resonance is the app's DSP, the DAC's EQ, or both. A
 * Direct stream whose DAC EQ could not be read is neither: null, plain "USB Direct", and the Details row says the EQ is unknown.
 */
internal fun resolveDirectOutputMode(route: AudioRouteState, dacEq: DacEqLabel = DacEqLabel.NOT_APPLICABLE): DirectOutputMode? {
    val status = AudioStatus(route = route, dacEq = dacEq)
    return when {
        status.purityHolds -> DirectOutputMode.PURITY
        status.resonanceSource != null -> DirectOutputMode.RESONANCE
        else -> null
    }
}

/**
 * What the equalizer is *doing*, which is not the same as whether it is switched on.
 * [BYPASSED_USB_DIRECT] exists because USB Direct structurally removes the DSP from the path.
 */
enum class EqualizerState { OFF, GRAPHIC_10, GRAPHIC_15, BYPASSED_USB_DIRECT }

/**
 * What the equalizer is actually doing to the PCM right now.
 *
 * USB Direct hands samples to the DAC before the processor chain can run, so an equalizer that is
 * configured on is genuinely not touching the audio. Reporting it as on would be a lie that the
 * rest of this screen's precision makes believable, so the bypass is decided first and wins.
 */
internal fun resolveEqualizerState(
    isDirectActive: Boolean,
    isBypass: Boolean,
    bandCount: Int
): EqualizerState = when {
    isDirectActive -> EqualizerState.BYPASSED_USB_DIRECT
    isBypass -> EqualizerState.OFF
    bandCount >= GRAPHIC_15_BAND_COUNT -> EqualizerState.GRAPHIC_15
    else -> EqualizerState.GRAPHIC_10
}

/** Band count at which the graphic equalizer is the 15-band variant rather than the 10-band one. */
internal const val GRAPHIC_15_BAND_COUNT = 15

enum class ReplayGainState { NOT_REQUESTED, TRACK_APPLIED, ALBUM_APPLIED, REQUESTED_NOT_APPLIED }

internal fun resolveReplayGain(
    state: ReplayGainRuntimeState,
    route: AudioRouteState,
): ReplayGainState = when {
    !state.requested -> ReplayGainState.NOT_REQUESTED
    !state.applied || route.actualBackend != AudioBackend.ANDROID_AUDIO ->
        ReplayGainState.REQUESTED_NOT_APPLIED
    state.useAlbumGain -> ReplayGainState.ALBUM_APPLIED
    else -> ReplayGainState.TRACK_APPLIED
}

/**
 * What the player's own decoder reports for the track it is rendering.
 *
 * Every value here is something the player measured while decoding; none of it is inferred from a
 * file name or from the container extension. A details view opened for any other track carries
 * null and reads the file instead.
 */
data class LiveAudioFormat(
    /** The song this reading was taken for, so a reading for another track cannot be applied. */
    val songId: String,
    /** Codec token the decoder named, already human-readable, e.g. "EAC3". */
    val format: String? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val bitrateBps: Int? = null,
    val durationMs: Long? = null
) {
    companion object {
        /**
         * The player's reading of [songId], or null when the player is not rendering that song.
         *
         * The decoder is preferred over a tag reader for the fields it can name, because a reader
         * that cannot label a container still leaves a stream the decoder knows exactly. Duration
         * is the same argument: the player measures the track it is playing, whereas a container
         * timebase — MP4's in particular — can be misread by orders of magnitude.
         */
        fun from(
            metadata: PlaybackAudioMetadata,
            durationMs: Long,
            songId: String
        ): LiveAudioFormat? {
            if (metadata.mediaId != songId) return null
            return LiveAudioFormat(
                songId = songId,
                // Skipped when the player is describing its own decoded output — a FLAC being
                // rendered reports `audio/raw`, and "RAW" is not this file's codec.
                format = metadata.mimeType
                    ?.takeUnless(AudioMetaUtils::isDecoderOutputFormat)
                    ?.let(AudioMetaUtils::mimeTypeToFormat)
                    ?.takeIf { it != NO_FORMAT }
                    ?.uppercase(Locale.getDefault()),
                sampleRateHz = metadata.sampleRate?.takeIf { it > 0 },
                channels = metadata.channelCount?.takeIf { it > 0 },
                bitrateBps = metadata.bitrate?.takeIf { it > 0 },
                durationMs = durationMs.takeIf { it > 0 }
                // metadata.bitDepth is deliberately unused: the decoder's PCM encoding describes
                // the samples it hands the sink, which is not the resolution the file holds.
            )
        }

        /** What `mimeTypeToFormat` returns when it does not recognise the type. */
        private const val NO_FORMAT = "-"
    }
}

data class SourceDetails(
    val format: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val bitrateBps: Int? = null,
    val channels: Int? = null,
    val durationMs: Long? = null,
    val fileSizeBytes: Long? = null
)

/**
 * Reconciles the two independent accounts of a track: what a tag reader made of the stored file,
 * and what the player's decoder reports for the stream it is rendering.
 *
 * Neither source knows everything, so each is used for what it knows first-hand. The decoder wins
 * on the identity of the stream — codec, sample rate, channel count — because it is reading the
 * compressed frames themselves, whereas a reader that cannot label a container may answer with
 * punctuation or nothing. It also wins on duration, because it measured the track it is playing.
 *
 * The reader wins on the stored file's own properties, bit depth and bitrate. The decoder cannot
 * answer those: it only ever sees decoded PCM, whose resolution is a property of the decoder's
 * output rather than of the file, and its `averageBitrate` for a losslessly compressed track is
 * the uncompressed PCM rate rather than the file's. Reporting either would be a wrong number
 * wearing the costume of a measured one.
 */
internal fun mergeSourceDetails(
    song: Song?,
    technical: AudioMetadataReader.TechnicalAudioInfo?,
    live: LiveAudioFormat?,
    fileSizeBytes: Long?
): SourceDetails = SourceDetails(
    format = live?.format ?: technical?.format,
    sampleRateHz = live?.sampleRateHz
        ?: technical?.sampleRate
        ?: song?.sampleRate?.takeIf { it > 0 },
    bitDepth = technical?.bitDepth,
    bitrateBps = technical?.bitrate
        ?: live?.bitrateBps
        ?: song?.bitrate?.takeIf { it > 0 },
    channels = live?.channels ?: technical?.channels,
    durationMs = live?.durationMs
        ?: song?.duration?.takeIf { it > 0 }
        ?: technical?.durationMs,
    fileSizeBytes = fileSizeBytes
)

/** [this] with the stored-tag fields of [stored] (what the Metadata rows print), and nothing else. */
internal fun Song.withStoredTags(stored: Song): Song = copy(
    title = stored.title,
    artist = stored.artist,
    album = stored.album,
    albumArtist = stored.albumArtist,
    genre = stored.genre,
    trackNumber = stored.trackNumber,
    discNumber = stored.discNumber,
    year = stored.year,
)

/**
 * Whether the waveform area is still waiting on a decode after a bind.
 *
 * A bind for a song that was not already on screen starts one, so it is loading. A re-bind of the
 * song already shown must leave the flag as it found it: the player names a stream a moment after
 * playback starts, so that second bind arrives while the first bind's decode is still running, and
 * clearing the flag there would drop the view onto its "no waveform available" branch for a track
 * whose waveform is one moment away.
 */
internal fun nextWaveformLoadingState(isNewSong: Boolean, currentlyLoading: Boolean): Boolean =
    if (isNewSong) true else currentlyLoading

/**
 * Whether a song-scoped result may be applied to what the view is currently showing.
 *
 * Every asynchronous read this view starts — the file's technical pass, the waveform decode, the
 * MusicBrainz identifier lookup — belongs to the song it was started for. The view can change song
 * while one is still in flight, and cancelling the previous job is not sufficient on its own: work
 * that has already finished is past its last suspension point and will still deliver its result.
 *
 * A result for a song the view has left is dropped rather than merged. Otherwise the sheet would
 * show one track's sample rate and bitrate under another track's title, which is exactly what
 * following playback from one track to the next must never produce.
 */
internal fun isSongScopedResultCurrent(currentSongId: String?, resultSongId: String): Boolean =
    currentSongId != null && currentSongId == resultSongId

/**
 * Whether a bind call carries nothing new.
 *
 * It is redundant only when it names the song already on screen *and* brings the same reading.
 * Anything else is a real rebind: a different song, or the same song with a reading that has since
 * arrived. This is the whole of the difference between details that follow playback and details
 * that sit still — a bind naming a new song can never be swallowed here.
 */
internal fun isBindRedundant(
    observedSongId: String?,
    observedLive: LiveAudioFormat?,
    songId: String,
    reading: LiveAudioFormat?,
): Boolean = observedSongId == songId && observedLive == reading

data class OutputDetails(
    val route: OutputRoute = OutputRoute.ANDROID_AUDIO,
    val deviceName: String? = null,
    /**
     * The rate the output path has actually confirmed. Only ever populated for USB Direct, where
     * the sink reads the DAC clock back (UAC2 GET_CUR) and fails the stream if it does not match.
     * The Android path has no equivalent confirmation, so it stays null there.
     */
    val confirmedRateHz: Int? = null,
    /** Purity or Resonance while USB Direct carries the stream; null on every Android route (B29-L4), and while the DAC's EQ is unknown (B30). */
    val directMode: DirectOutputMode? = null,
    /** B30: what shapes a Resonance stream: the DAC's EQ, the app's DSP, or both. Null when [directMode] is not Resonance. */
    val resonanceSource: ResonanceSource? = null,
    /** B30: what the DAC's own EQ is doing, as far as the app could read it. */
    val dacEq: DacEqLabel = DacEqLabel.NOT_APPLICABLE,
    /** B30-P4: a listening choice (crossfade, speed, an Android effect) is what keeps the stream off USB Direct with a DAC connected. */
    val conflict: DacEqConflict? = null,
    /**
     * The Android mixer's own rate, from `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`. This is a
     * property of the device, never a claim about what this track is being played at.
     */
    val deviceMixerRateHz: Int? = null
)

data class TrackDetailsUiState(
    val song: Song? = null,
    val source: SourceDetails = SourceDetails(),
    val output: OutputDetails = OutputDetails(),
    val equalizer: EqualizerState = EqualizerState.OFF,
    val replayGain: ReplayGainState = ReplayGainState.NOT_REQUESTED,
    val waveform: Waveform? = null,
    val isLoadingWaveform: Boolean = false,
    val fileMissing: Boolean = false,
    /**
     * Canonical MusicBrainz identifiers, when this track has them.
     *
     * They live in the `songs` row rather than on [Song], so they are read separately. Null means
     * the row could not be read, which is different from "present but unenriched" — that is an
     * object of nulls.
     */
    val musicBrainzIds: MusicBrainzIds? = null
)

/**
 * The identifiers a MusicBrainz enrichment writes. They are the one part of the song's identity
 * that is not carried on [com.lostf1sh.pixelplayeross.data.model.Song].
 */
data class MusicBrainzIds(
    val recordingId: String?,
    val releaseId: String?,
    val artistId: String?,
) {
    /** True when the track has been enriched at all. */
    val isPresent: Boolean
        get() = !recordingId.isNullOrBlank() || !releaseId.isNullOrBlank() || !artistId.isNullOrBlank()
}

/**
 * Backs the Track Details sibling view: "what is this file?" and "how is PixelPlayer playing it
 * right now?".
 *
 * Everything here is read-only observation. Opening the view never seeks, never starts or stops
 * playback, and never touches the route, the profile, the queue or the volume.
 *
 * The technical read and the waveform are both lazy and cached per song, so reopening the sheet
 * on the same track is a state read rather than a second parse or a second decode.
 */
@HiltViewModel
class TrackDetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dualPlayerEngine: DualPlayerEngine,
    private val headphoneEqualizerCoordinator: HeadphoneEqualizerCoordinator,
    private val headphoneGraphicEqController: HeadphoneGraphicEqController,
    private val waveformRepository: WaveformRepository,
    private val musicDao: MusicDao,
    private val dispatcherProvider: DispatcherProvider,
    private val dacEqLabels: DacEqLabelSource,
) : ViewModel() {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _uiState = MutableStateFlow(TrackDetailsUiState())
    val uiState: StateFlow<TrackDetailsUiState> = _uiState.asStateFlow()

    private var technicalJob: Job? = null
    private var waveformJob: Job? = null
    private var storedTagsJob: Job? = null

    /**
     * Re-reads the output when an audio device comes or goes. On the Android path the route and the
     * device name are read from the platform, and the route-state flow does not change when a
     * headset is plugged in or a Bluetooth sink connects, so without this the Output group kept what
     * it read when the sheet opened (FIX-3 audit).
     */
    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
            refreshOutput(dualPlayerEngine.audioRouteState.value)

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
            refreshOutput(dualPlayerEngine.audioRouteState.value)
    }
    private var observedSongId: String? = null
    private var observedLive: LiveAudioFormat? = null

    /** What the DAC's EQ was last read as; the route refreshes below use it, so a late read relabels the sheet. */
    private var dacEq: DacEqLabel = dacEqLabels.label.value

    init {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        viewModelScope.launch {
            combine(
                dualPlayerEngine.audioRouteState,
                headphoneEqualizerCoordinator.effectiveStateFlow,
                dualPlayerEngine.replayGainRuntimeState,
                dacEqLabels.label,
            ) { route, _, replayGain, label -> Triple(route, replayGain, label) }.collect { (route, replayGain, label) ->
                dacEq = label
                refreshOutput(route)
                val settings = headphoneGraphicEqController.settings()
                val equalizer = resolveEqualizerState(
                    isDirectActive = route.isDirectActive,
                    isBypass = settings.isBypass,
                    bandCount = settings.bandCount
                )
                _uiState.update {
                    it.copy(equalizer = equalizer, replayGain = resolveReplayGain(replayGain, route))
                }
            }
        }
    }

    /**
     * Binds the view to [song], together with [live] when the player is rendering that same song.
     *
     * Re-binding the same song with the same reading is a no-op, so a recomposition costs nothing
     * and the file is not read twice for one view. A second call carrying a reading that did not
     * exist the first time — the player names a stream a moment after it starts, not before — is
     * cheap too: it re-reads the file but reuses the waveform already decoded for this song.
     */
    fun bind(song: Song, live: LiveAudioFormat? = null) {
        val reading = live?.takeIf { it.songId == song.id }
        if (isBindRedundant(observedSongId, observedLive, song.id, reading)) return
        val isNewSong = observedSongId != song.id
        observedSongId = song.id
        observedLive = reading
        if (isNewSong) {
            loadMusicBrainzIds(song)
            observeStoredTags(song)
        }
        technicalJob?.cancel()
        if (isNewSong) waveformJob?.cancel()
        _uiState.update {
            it.copy(
                song = song,
                source = SourceDetails(
                    durationMs = reading?.durationMs ?: song.duration.takeIf { it > 0 }
                ),
                waveform = if (isNewSong) null else it.waveform,
                // A re-bind of the song already on screen must leave the flag alone. The player
                // names a stream a moment after it starts, so the second bind lands while the
                // first one's decode is still running; clearing the flag there would drop the
                // view onto the "no waveform" branch for a track whose waveform is one moment
                // away. An ongoing load stays loading.
                isLoadingWaveform = nextWaveformLoadingState(
                    isNewSong = isNewSong,
                    currentlyLoading = it.isLoadingWaveform
                )
            )
        }
        refreshOutput(dualPlayerEngine.audioRouteState.value)
        loadTechnical(song, reading)
        if (isNewSong) loadWaveform(song)
    }

    private fun refreshOutput(routeState: AudioRouteState) {
        if (routeState.isDirectActive) {
            _uiState.update {
                it.copy(
                    output = it.output.copy(
                        route = OutputRoute.USB_DIRECT,
                        directMode = resolveDirectOutputMode(routeState, dacEq),
                        resonanceSource = AudioStatus(route = routeState, dacEq = dacEq).resonanceSource,
                        dacEq = dacEq,
                        deviceName = routeState.deviceName?.takeIf(String::isNotBlank),
                        // Safe to call this an output rate: the sink only reports ACTIVE once it
                        // has read this exact rate back off the DAC clock and the stream would
                        // otherwise have failed.
                        confirmedRateHz = routeState.confirmedOutputRateHz,
                        deviceMixerRateHz = null
                    )
                )
            }
            return
        }

        val route = if (routeState.transition == AudioRouteTransition.DIRECT_FAILED_FALLBACK) {
            OutputRoute.SPEAKER
        } else resolveAndroidRoute()

        _uiState.update {
            it.copy(
                output = OutputDetails(
                    route = route,
                    directMode = null,
                    dacEq = dacEq,
                    conflict = AudioStatus(route = routeState, dacEq = dacEq).dacEqConflict(),
                    deviceName = resolveAndroidDeviceName(route),
                    // Never populated off the direct path: nothing confirms it.
                    confirmedRateHz = null,
                    deviceMixerRateHz = androidMixerRate()
                )
            )
        }
    }

    private fun resolveAndroidRoute(): OutputRoute {
        val outputs = routedAndroidOutputs()

        val selected = outputs.firstOrNull { it.type in USB_AUDIO_DEVICE_TYPES }
            ?: outputs.firstOrNull { it.type in BLUETOOTH_AUDIO_DEVICE_TYPES }
            ?: outputs.firstOrNull { it.type in WIRED_AUDIO_DEVICE_TYPES }
            ?: outputs.firstOrNull { it.type in BUILT_IN_SPEAKER_TYPES }

        return when {
            selected == null -> OutputRoute.ANDROID_AUDIO
            selected.type in USB_AUDIO_DEVICE_TYPES -> OutputRoute.USB_ANDROID
            selected.type in BLUETOOTH_AUDIO_DEVICE_TYPES -> OutputRoute.BLUETOOTH
            selected.type in WIRED_AUDIO_DEVICE_TYPES -> OutputRoute.WIRED
            selected.type in BUILT_IN_SPEAKER_TYPES -> OutputRoute.SPEAKER
            else -> OutputRoute.ANDROID_AUDIO
        }
    }

    private fun resolveAndroidDeviceName(route: OutputRoute): String? {
        val outputs = routedAndroidOutputs()
        if (route == OutputRoute.BLUETOOTH) {
            return outputs.firstOrNull { it.type in BLUETOOTH_AUDIO_DEVICE_TYPES }
                ?.productName?.toString()?.takeIf(String::isNotBlank)
        }
        return outputs.firstOrNull { it.type in USB_AUDIO_DEVICE_TYPES }
            ?.productName?.toString()?.takeIf(String::isNotBlank)
            ?: outputs.firstOrNull { it.type in WIRED_AUDIO_DEVICE_TYPES }
                ?.productName?.toString()?.takeIf(String::isNotBlank)
    }

    private fun routedAndroidOutputs(): List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= 33) {
        runCatching { audioManager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES) }
            .getOrDefault(emptyList())
    } else {
        runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
            .getOrDefault(emptyList())
    }

    private fun androidMixerRate(): Int? = runCatching {
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    }.getOrNull()?.takeIf { it > 0 }

    private fun loadTechnical(song: Song, live: LiveAudioFormat?) {
        technicalJob = viewModelScope.launch {
            val info = withContext(dispatcherProvider.io) {
                val uri = song.contentUriString.takeIf { it.isNotBlank() }?.let(Uri::parse)
                val file = song.path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
                val technical = runCatching {
                    when {
                        file != null -> AudioMetadataReader.readTechnical(file)
                        uri != null -> AudioMetadataReader.readTechnical(context, uri)
                        else -> null
                    }
                }.getOrNull()
                val size = runCatching {
                    uri?.let { AudioMetadataReader.resolveFileSize(context, it, file) }
                        ?: file?.length()
                }.getOrNull()
                technical to size
            }
            val (technical, size) = info
            _uiState.update { state ->
                if (!isSongScopedResultCurrent(state.song?.id, song.id)) state
                else state.copy(
                    source = mergeSourceDetails(
                        song = state.song,
                        technical = technical,
                        live = live,
                        fileSizeBytes = size
                    ),
                    // Only claim the file is unreadable when neither the reader nor the player
                    // could say anything about it at all.
                    fileMissing = technical == null && live == null
                )
            }
        }
    }

    /**
     * Reads the canonical identifiers for [song], if it has any.
     *
     * Failure is not surfaced: a row that cannot be read is reported as "no identifiers", which is
     * what the section would show for an unenriched track anyway. A missing identifier is not an
     * error the user can act on.
     */
    private fun loadMusicBrainzIds(song: Song) {
        viewModelScope.launch {
            val ids = withContext(dispatcherProvider.io) {
                runCatching {
                    val songId = song.id.toLongOrNull() ?: return@runCatching null
                    musicDao.musicBrainzCurrentState(songId)
                }.getOrNull()
            }
            _uiState.update { state ->
                if (!isSongScopedResultCurrent(state.song?.id, song.id)) state
                else state.copy(
                    musicBrainzIds = ids?.let {
                        MusicBrainzIds(
                            recordingId = it.mbRecordingId,
                            releaseId = it.mbReleaseId,
                            artistId = it.mbArtistId,
                        )
                    }
                )
            }
        }
    }

    /**
     * Keeps the Metadata rows on what the library stores *now*.
     *
     * The sheet is bound to the `Song` it was opened with, a snapshot: a repair that unpacks a track
     * number or fills in a year while a song is playing, or an edit, changed the row and not that
     * object, so the rows kept showing the old values until the song was bound again. This follows the
     * one stored row for as long as the sheet shows this song, and replaces only the stored-tag fields.
     * A song with no library row (an external file) has nothing to follow.
     */
    private fun observeStoredTags(song: Song) {
        storedTagsJob?.cancel()
        val songId = song.id.toLongOrNull() ?: return
        storedTagsJob = viewModelScope.launch {
            musicDao.getSongById(songId).collect { entity ->
                val stored = entity?.toSong() ?: return@collect
                _uiState.update { state ->
                    if (!isSongScopedResultCurrent(state.song?.id, song.id)) state
                    else state.copy(
                        song = state.song?.withStoredTags(stored),
                        // The identifiers live in the same row: an enrichment that finishes while the
                        // sheet is open shows up here instead of after the next bind.
                        musicBrainzIds = MusicBrainzIds(
                            recordingId = entity.mbRecordingId,
                            releaseId = entity.mbReleaseId,
                            artistId = entity.mbArtistId,
                        ),
                    )
                }
            }
        }
    }

    private fun loadWaveform(song: Song) {
        waveformJob = viewModelScope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val waveform = runCatching { waveformRepository.waveformFor(song) }.getOrNull()
            // B29-fix1 F7: the strip under the title is this waveform; until it is decoded it holds a loading line, and when there is none
            // a note. How long the decode takes, and whether it ends in one, is what tells those two apart on a device.
            timber.log.Timber.tag("TrackDetails").i(
                "waveform %s after %d ms (%s)", if (waveform != null) "ready" else "none", android.os.SystemClock.elapsedRealtime() - startedAt, song.mimeType,
            )
            _uiState.update { state ->
                if (!isSongScopedResultCurrent(state.song?.id, song.id)) state
                else state.copy(waveform = waveform, isLoadingWaveform = false)
            }
        }
    }

    override fun onCleared() {
        // Closing the view stops an in-flight decode instead of letting it run to completion.
        technicalJob?.cancel()
        waveformJob?.cancel()
        storedTagsJob?.cancel()
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        super.onCleared()
    }

    private companion object {
        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val USB_AUDIO_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )
        val WIRED_AUDIO_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL
        )
        val BLUETOOTH_AUDIO_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        val BUILT_IN_SPEAKER_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
        )
    }
}
