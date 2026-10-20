package com.lostf1sh.pixelplayeross.data.service

import android.app.BackgroundServiceStartNotAllowedException
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.lostf1sh.pixelplayeross.PixelPlayerApplication
import com.lostf1sh.pixelplayeross.MainActivity
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.audio.dsp.HeadphoneGraphicEqController.Companion.toDspSettings
import com.lostf1sh.pixelplayeross.data.diagnostics.AdvancedPerformanceDiagnostics
import com.lostf1sh.pixelplayeross.data.model.PlayerInfo
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueItemSnapshot
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreferencesRepository
import com.lostf1sh.pixelplayeross.utils.timedSection
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.volume.PristineLockDiagnostic
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqualizerCoordinator
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.ListenAudioFacts
import com.lostf1sh.pixelplayeross.data.stats.ListenAudioFactsAssembler
import com.lostf1sh.pixelplayeross.data.stats.ListenContext
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.data.stats.TrackEndReason
import com.lostf1sh.pixelplayeross.data.stats.trackEndReasonFromTransition
import com.lostf1sh.pixelplayeross.data.service.player.AndroidOutputReader
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.OutputLossListener
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioProcessingRequirements
import com.lostf1sh.pixelplayeross.data.service.player.TransitionController
import com.lostf1sh.pixelplayeross.ui.glancewidget.ControlWidget4x2
import com.lostf1sh.pixelplayeross.ui.glancewidget.PixelPlayerGlanceWidget
import com.lostf1sh.pixelplayeross.ui.glancewidget.PlayerActions
import com.lostf1sh.pixelplayeross.ui.glancewidget.PlayerInfoStateDefinition
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerManager
import com.lostf1sh.pixelplayeross.data.equalizer.ExternalAudioEffectSession
import com.lostf1sh.pixelplayeross.data.model.WidgetThemeColors
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtColorAccuracy
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtPaletteStyle
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemeProcessor
import androidx.compose.ui.graphics.toArgb
import com.lostf1sh.pixelplayeross.ui.glancewidget.BarWidget4x1
import com.lostf1sh.pixelplayeross.ui.glancewidget.GridWidget2x2
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreference
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemePair
import com.lostf1sh.pixelplayeross.utils.ArtworkTransportSanitizer
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import com.lostf1sh.pixelplayeross.di.AppScope
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ListeningStatsTracker
import kotlin.math.abs
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision

import javax.inject.Inject
import androidx.core.net.toUri

/**
 * Task removal must never tear down active playback when the user has opted into
 * background playback; in every other case the service should shut down cleanly
 * instead of lingering half-alive until the system reclaims it.
 */
internal fun shouldContinuePlaybackAfterTaskRemoved(
    hasForegroundPlaybackIntent: Boolean,
    keepPlayingInBackground: Boolean
): Boolean {
    return hasForegroundPlaybackIntent && keepPlayingInBackground
}

suspend fun loadArtworkBytesViaCoil(context: Context, uri: Uri): ByteArray? {
    val appContext = context.applicationContext
    val request = ImageRequest.Builder(appContext)
        .data(uri)
        .size(
            ArtworkTransportSanitizer.WIDGET_CONFIG.maxDimensionPx,
            ArtworkTransportSanitizer.WIDGET_CONFIG.maxDimensionPx,
        )
        .precision(Precision.INEXACT)
        .allowHardware(false)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
        .build()

    return runCatching {
        val drawable = appContext.imageLoader.execute(request).drawable ?: return@runCatching null
        val fallbackSizePx = ArtworkTransportSanitizer.WIDGET_CONFIG.maxDimensionPx
        val bitmap = drawable.toBitmap(
            width = drawable.intrinsicWidth.takeIf { it > 0 } ?: fallbackSizePx,
            height = drawable.intrinsicHeight.takeIf { it > 0 } ?: fallbackSizePx,
            config = Bitmap.Config.ARGB_8888,
        )
        val encodedBytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
            output.toByteArray()
        }
        ArtworkTransportSanitizer.sanitizeEncodedBytes(
            data = encodedBytes,
            config = ArtworkTransportSanitizer.WIDGET_CONFIG,
        )
    }.getOrElse { error ->
        Timber.tag("MusicService_PixelPlayer").w(error, "Artwork read failed via Coil for uri=%s", uri)
        null
    }
}


@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class MusicService : MediaSessionService() {

    @Inject
    lateinit var engine: DualPlayerEngine
    @Inject
    lateinit var controller: TransitionController
    @Inject
    lateinit var musicRepository: MusicRepository
    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository
    @Inject
    lateinit var sleepTimerStateHolder: com.lostf1sh.pixelplayeross.presentation.viewmodel.SleepTimerStateHolder
    @Inject
    lateinit var headphoneEqualizerCoordinator: HeadphoneEqualizerCoordinator
    @Inject
    lateinit var graphicEqController: com.lostf1sh.pixelplayeross.data.audio.dsp.HeadphoneGraphicEqController
    @Inject
    lateinit var safetyVolumeController: dagger.Lazy<com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController>
    @Inject
    lateinit var directDuckController: com.lostf1sh.pixelplayeross.data.service.player.DirectDuckController
    @Inject
    lateinit var themePreferencesRepository: ThemePreferencesRepository
    @Inject
    lateinit var equalizerManager: EqualizerManager
    @Inject
    lateinit var externalAudioEffectSession: ExternalAudioEffectSession
    @Inject
    lateinit var colorSchemeProcessor: ColorSchemeProcessor
    @Inject
    lateinit var replayGainManager: com.lostf1sh.pixelplayeross.data.media.ReplayGainManager
    @Inject
    lateinit var listeningStatsTracker: ListeningStatsTracker
    @Inject
    @AppScope
    lateinit var appScope: CoroutineScope

    /**
     * The speed the player runs at: the listener's choice, or 1.0× while the Purity lock holds it (B15-F2). The choice
     * itself stays in the preferences and applies again when the lock is left.
     */
    private var effectivePlaybackSpeed = 1f

    /** The lock is on and holding the speed at 1.0×: a speed set by anything else (an external controller) is put back. */
    @Volatile
    private var playbackSpeedFrozen = false

    // ReplayGain volume-normalization state + logic, extracted to a standalone
    // component. Lazily built so the Hilt-injected engine/replayGainManager and the
    // service scope are ready before first use (first playback event).
    private val replayGainProcessor by lazy {
        ReplayGainProcessor(
            engine = engine,
            replayGainManager = replayGainManager,
            scope = serviceScope,
            currentSessionMediaItem = { mediaSession?.player?.currentMediaItem },
        ).also { processor ->
            // The engine cannot reach the processor (it lives here), so the debug bridge's
            // deterministic-factor control is registered as a callback rather than a reference.
            engine.registerDiagnosticReplayGainSetter(processor::setDiagnosticReplayGainFactor)
            engine.registerDiagnosticUserGainSetter(processor::setDiagnosticUserGain)
        }
    }

    private var favoriteSongIds = emptySet<String>()
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var keepPlayingInBackground = true
    private var isManualShuffleEnabled = false
    private var persistentShuffleEnabled = false
    private var previousMainThreadExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private val playbackTimerController by lazy {
        PlaybackTimerController(
            playerProvider = { mediaSession?.player ?: engine.masterPlayer },
            alarmScheduler = AlarmManagerSleepTimerScheduler(this),
            onCountedPlayChanged = com.lostf1sh.pixelplayeross.data.CountedPlayStateHolder::setTarget,
        )
    }
    private var playbackSnapshotPersistJob: Job? = null
    /** AUD-121: the position alone, every 5 s while playing, so a process death loses at most that. */
    private var positionCheckpointJob: Job? = null
    private val positionCheckpointer = PlaybackPositionCheckpointer { mediaId, positionMs ->
        userPreferencesRepository.setPlaybackPosition(mediaId, positionMs)
    }
    private var playbackSnapshotUnloadWriteJob: Job? = null
    private var isRestoringPlaybackSnapshot = false
    private var isPlaybackUnloadInProgress = false
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var headsetReconnectCallback: AudioDeviceCallback? = null
    /** B25-S1: the resume-on-reconnect rule (its setting, what arms it, when it fires), kept apart so it can be tested. */
    private val headsetReconnectRule = HeadsetReconnectRule()
    private var temporaryForegroundStartedInOnCreate = false
    private var temporaryForegroundNotificationVisible = false

    companion object {
        private const val TAG = "MusicService_PixelPlayer"
        const val NOTIFICATION_ID = 101
        const val ACTION_SLEEP_TIMER_EXPIRED = "com.lostf1sh.pixelplayeross.ACTION_SLEEP_TIMER_EXPIRED"
        /** AUD-103: the widget queue tap's media id, numeric or not. */
        const val EXTRA_WIDGET_MEDIA_ID = "com.lostf1sh.pixelplayeross.extra.WIDGET_MEDIA_ID"
        const val EXTRA_FORCE_FOREGROUND_ON_START =
            "com.lostf1sh.pixelplayeross.extra.FORCE_FOREGROUND_ON_START"
        private const val PLAYBACK_SNAPSHOT_DEBOUNCE_MS = 1500L
        private const val FORCED_WIDGET_STATE_DEBOUNCE_MS = 250L
        private const val MEDIA_SESSION_BUTTON_DEBOUNCE_MS = 250L
        private const val DEFERRED_SERVICE_STARTUP_WORK_DELAY_MS = 1_000L
        private const val PAUSED_RESTORE_PREPARE_QUEUE_LIMIT = 50
        private val pendingMediaButtonForegroundStarts = AtomicInteger(0)

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val DEFAULT_STREAM_BUFFER_SIZE = 8 * 1024
        private const val WIDGET_ART_FAILURE_RETRY_MS = 30_000L
        private const val WIDGET_QUEUE_PREVIEW_LIMIT = 4

        fun markPendingMediaButtonForegroundStart() {
            pendingMediaButtonForegroundStarts.incrementAndGet()
        }

        fun unmarkPendingMediaButtonForegroundStart() {
            while (true) {
                val currentCount = pendingMediaButtonForegroundStarts.get()
                if (currentCount <= 0) return
                if (pendingMediaButtonForegroundStarts.compareAndSet(currentCount, currentCount - 1)) {
                    return
                }
            }
        }

        private fun consumePendingMediaButtonForegroundStart(): Boolean {
            while (true) {
                val currentCount = pendingMediaButtonForegroundStarts.get()
                if (currentCount <= 0) return false
                if (pendingMediaButtonForegroundStarts.compareAndSet(currentCount, currentCount - 1)) {
                    return true
                }
            }
        }
    }

    private val playerSwapListener: (Player) -> Unit = { newPlayer ->
        publishMediaSessionPlayer(newPlayer, "Swapped MediaSession player to new instance.")
        // Counted play follows the new session player instead of staying on the old one (AUD-051).
        playbackTimerController.onPlayerSwapped()
        replayGainProcessor.prepareForTransition(newPlayer)
        applyPlaybackSpeed(newPlayer)
    }

    private val transitionDisplayPlayerListener: (Player) -> Unit = { displayPlayer ->
        publishMediaSessionPlayer(
            displayPlayer,
            "Published incoming crossfade player to MediaSession."
        )
        replayGainProcessor.prepareForTransition(displayPlayer)
        applyPlaybackSpeed(displayPlayer)
    }

    private val transitionFinishedListener: () -> Unit = {
        replayGainProcessor.onTransitionFinished()
    }

    private fun Player.unwrapMappingPlayer(): Player {
        return (this as? com.lostf1sh.pixelplayeross.data.service.player.MappingPlayer)?.innerPlayer ?: this
    }

    private fun Player.unwrapFadingPlayer(): Player {
        return (this as? com.lostf1sh.pixelplayeross.data.service.player.FadingPlayer)?.innerPlayer ?: this
    }

    /** The two ends of the bracket that times what the session does with a timeline change (B19-S4); see [SessionPublicationMeter]. */
    private val publicationHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val publicationMeter = SessionPublicationMeter(
        clockMs = { SystemClock.elapsedRealtime() },
        post = { block -> publicationHandler.post(block) },
        log = { sessionMs, controllersMs, items, reason ->
            Timber.tag("SongChange").i(
                "session publication %d ms (session %d ms, controllers %d ms) items=%d reason=%d",
                sessionMs + controllersMs, sessionMs, controllersMs, items, reason,
            )
        },
        // B31 P2.3: the "controllers" part is the wait of a message at the end of the main queue; this names what it waited behind.
        busy = MainLooperBusyProbe(
            clockMs = { SystemClock.elapsedRealtime() },
            attach = { printer -> Looper.getMainLooper().setMessageLogging(printer) },
        ),
        logBusy = { controllersMs, busiest ->
            Timber.tag("SongChange").i(
                "session publication waited %d ms behind: %s",
                controllersMs, busiest.joinToString("; ") { "${it.ms} ms ${it.what}" },
            )
        },
    )

    private fun wrapFadingPlayer(player: Player): Player {
        val fadingPlayer = com.lostf1sh.pixelplayeross.data.service.player.FadingPlayer(
            innerPlayer = player,
            scope = appScope
        )
        return com.lostf1sh.pixelplayeross.data.service.player.MappingPlayer(
            innerPlayer = fadingPlayer,
            context = this,
            // The DAC's hardware level as the session's remote volume while Direct uses it (AUD-027).
            remoteVolume = safetyVolumeController.get().remoteVolume,
            scope = appScope,
            // Every position seek from any controller reaches the Direct authority (AUD-117).
            onPositionSeek = engine::notifyExternalSeekInitiated,
            // A skip with nowhere to go while a queue is still being loaded is held, not lost (B18-R2).
            queueLoadHold = engine.queueLoadHold,
            onPause = engine::notifyExternalPauseInitiated,
            // What the listener asked of the transport is how the listen it closes is recorded to have ended (B28).
            onUserTransport = listeningStatsTracker::noteUserDeparture,
        // Before the session registers its own listener on it: the start of the bracket that times a publication (B19-S4).
        ).also { it.addListener(publicationMeter.begin) }
    }

    private fun publishMediaSessionPlayer(player: Player, logMessage: String) {
        val session = mediaSession ?: return
        val oldPlayer = session.player
        val unwrappedOld = oldPlayer.unwrapMappingPlayer().unwrapFadingPlayer()
        if (unwrappedOld !== player) {
            oldPlayer.removeListener(playerListener)
            oldPlayer.removeListener(publicationMeter.begin)
            oldPlayer.removeListener(publicationMeter.end)
            val wrappedPlayer = wrapFadingPlayer(player)
            session.player = wrappedPlayer
            wrappedPlayer.addListener(publicationMeter.end)
            wrappedPlayer.addListener(playerListener)
        }

        Timber.tag("MusicService").d(logMessage)
        syncLocalListeningStatsFromPlayer(player)
        requestWidgetFullUpdate(force = true)
        refreshMediaSessionUi(session)
    }

    /** B28a: the Android output the music goes to when the app is not driving the DAC; read when outputs change, never per song. */
    private val androidOutputReader by lazy { AndroidOutputReader(audioManager) }
    private var outputRouteCallback: AudioDeviceCallback? = null

    /**
     * The audio the app is playing through right now, from what it already holds: the engine's route and Direct
     * diagnostics, the player's live format and the Android output. No probing of the DAC (B28a).
     */
    private fun currentListenAudioFacts(): ListenAudioFacts {
        val route = engine.audioRouteState.value
        return ListenAudioFactsAssembler.assemble(
            route = route,
            diagnostics = engine.usbAudioDiagnostics.value,
            androidOutput = if (route.isDirectActive) null else androidOutputReader.current(),
            liveFormat = engine.currentAudioFormatSnapshot(),
            fileMimeType = fileMimeTypeOf(engine.masterPlayer.currentMediaItem),
        )
    }

    /**
     * The codec of the file that is playing: the media item's own type (the library row's, or the one an external item
     * declared). The sink only sees decoded samples, so this is what "formats heard" is read from (B28).
     */
    private fun fileMimeTypeOf(item: MediaItem?): String? =
        item?.localConfiguration?.mimeType?.takeIf { it.isNotBlank() }
            ?: item?.mediaMetadata?.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_MIME_TYPE)?.takeIf { it.isNotBlank() }

    private fun pushListenAudioFacts() = listeningStatsTracker.onAudioFacts(currentListenAudioFacts())

    /**
     * Where the play came from, as far as the player knows for certain: an external VIEW item says so, and shuffle is the
     * player's own flag. The queue holds a display name, not a typed source, so the rest is left unrecorded.
     */
    private fun listenContextOf(player: Player, mediaItem: MediaItem): ListenContext = ListenContext(
        kind = if (mediaItem.mediaMetadata.extras?.getBoolean(MediaItemBuilder.EXTERNAL_EXTRA_FLAG, false) == true) {
            ListenSourceKind.EXTERNAL_VIEW
        } else {
            null
        },
        shuffle = player.shuffleModeEnabled,
    )

    private fun syncLocalListeningStatsFromPlayer(
        player: Player = engine.masterPlayer,
        forceNewSession: Boolean = false,
        previousEndReason: TrackEndReason = TrackEndReason.UNKNOWN
    ) {
        val mediaItem = player.currentMediaItem
        val songId = mediaItem?.mediaId?.takeIf { it.isNotBlank() }
        if (songId == null) {
            if (
                player.mediaItemCount == 0 ||
                player.playbackState == Player.STATE_IDLE ||
                player.playbackState == Player.STATE_ENDED
            ) {
                listeningStatsTracker.onPlaybackStopped()
            }
            return
        }

        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration
        val fallbackDurationMs = mediaItem.mediaMetadata.extras
            ?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION, 0L)
            ?: 0L

        // First, so a session that opens below books its time against the audio that is playing now (B28a).
        pushListenAudioFacts()
        val context = listenContextOf(player, mediaItem)
        // The library identity is not passed: the item carries the locator only, and the tracker asks for the identity
        // itself, off the main thread, when the session opens (an external item has none and records none).
        if (forceNewSession) {
            listeningStatsTracker.onTrackChanged(
                songId = songId,
                positionMs = positionMs,
                durationMs = durationMs,
                fallbackDurationMs = fallbackDurationMs,
                isPlaying = player.isPlaying,
                previousEndReason = previousEndReason,
                context = context,
            )
        } else {
            listeningStatsTracker.ensureSession(
                songId = songId,
                positionMs = positionMs,
                durationMs = durationMs,
                fallbackDurationMs = fallbackDurationMs,
                isPlaying = player.isPlaying,
                context = context,
            )
        }
    }

    override fun onCreate() {
        val existingHandler = Thread.currentThread().uncaughtExceptionHandler
        previousMainThreadExceptionHandler = existingHandler
        Thread.currentThread().setUncaughtExceptionHandler { thread, throwable ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                throwable is ForegroundServiceStartNotAllowedException
            ) {
                Timber.tag(TAG).w(throwable, "Suppressed ForegroundServiceStartNotAllowedException from Media3 internal path")
            } else {
                existingHandler?.uncaughtException(thread, throwable)
            }
        }

        // Only promote to foreground here when a media-button start is actually
        // pending. Promoting unconditionally made every service creation — including
        // plain in-app binds when the user opens the app — flash the "Processing
        // playback action…" placeholder notification. Started paths that require
        // foreground (widget actions, media buttons) are promoted in onStartCommand,
        // which runs right after onCreate and well within the FGS grace window.
        temporaryForegroundStartedInOnCreate = consumePendingMediaButtonForegroundStart()
        if (temporaryForegroundStartedInOnCreate) {
            startTemporaryForegroundForCommand()
        }

        super.onCreate()
        listeningStatsTracker.initialize(appScope)
        
        engine.initialize()
        replayGainProcessor.captureUserVolume(engine.masterPlayer.volume)
        syncLocalListeningStatsFromPlayer(engine.masterPlayer)

        engine.masterPlayer.addListener(playerListener)
        registerListenAudioFactsFeed()

        engine.setOnPlayerAboutToBeReleasedListener { oldPlayer ->
            oldPlayer.removeListener(playerListener)
        }
        engine.addPlayerSwapListener(playerSwapListener)
        engine.addTransitionDisplayPlayerListener(transitionDisplayPlayerListener)
        engine.addTransitionFinishedListener(transitionFinishedListener)
        // The active headphone profile's boost is compensated as a gain contributor of its own,
        // recomputed for every published profile EQ — including the first after a restart (AUD-025).
        serviceScope.launch {
            headphoneEqualizerCoordinator.effectiveStateFlow.collect { eq ->
                replayGainProcessor.setProfileCompensation(
                    com.lostf1sh.pixelplayeross.data.headphones.ProfileGainCompensation.factor(eq)
                )
            }
        }

        controller.initialize()
        registerHeadsetReconnectMonitor()

        serviceScope.launch {
            // Only the Android effect extras are restored here. The Graphic EQ is software DSP and
            // follows the persisted EQ state stream directly, so it re-establishes itself after a
            // process restart without MusicService having to feed it anything.
            headphoneEqualizerCoordinator.ensureProfileAuthority()
            val eq = headphoneEqualizerCoordinator.currentEffectiveState()

            equalizerManager.restoreState(
                eq.bassBoostEnabled, eq.bassBoostStrength,
                eq.virtualizerEnabled, eq.virtualizerStrength,
                eq.loudnessEnhancerEnabled, eq.loudnessEnhancerStrength,
            )

        }

        serviceScope.launch {
            combine(engine.activeAudioSessionId, engine.audioRouteState) { sessionId, route ->
                sessionId to route.actualBackend
            }.collect { (sessionId, backend) ->
                if (backend == AudioBackend.ANDROID_AUDIO) {
                    externalAudioEffectSession.open(sessionId)
                    if (sessionId != 0) equalizerManager.attachToAudioSessionIfNeeded(sessionId)
                } else {
                    externalAudioEffectSession.close()
                    equalizerManager.release()
                }
            }
        }

        serviceScope.launch {
            userPreferencesRepository.keepPlayingInBackgroundFlow.collect { enabled ->
                keepPlayingInBackground = enabled
            }
        }

        serviceScope.launch {
            // Do not make a transport decision from the coordinator's safe startup bypass before
            // the durable profile (or its one-time legacy migration) has been loaded.
            headphoneEqualizerCoordinator.ensureProfileAuthority()
            combine(
                // Paired with whether the EQ editor is open: editing holds the signal PROCESSED so a
                // slider move never toggles the mode and rebuilds the producer (AUD-110/111).
                headphoneEqualizerCoordinator.effectiveStateFlow
                    .combine(graphicEqController.editorOpen) { eq, open -> eq to open },
                userPreferencesRepository.replayGainEnabledFlow,
                userPreferencesRepository.playbackSpeedFlow,
                userPreferencesRepository.isCrossfadeEnabledFlow,
                // The effective gain. A digital level below unity is PCM work like any other, so it
                // selects the processed signal path rather than doing nothing — which is what it did
                // while Direct was active, because `player.volume` only ever reached the muted
                // delegate. ReplayGain writes this same field, so it also reads as a gain here; the
                // stage names whichever of the two is actually responsible.
                engine.gainModel,
            ) { (eq, editorOpen), replayGainPreference, speed, crossfade, gain ->
                AudioProcessingRequirements(
                    // Bypass, not enablement. An EQ that is switched on with every band at 0 dB is
                    // a mathematical identity, and the DSP is built to pass those samples through
                    // unread — so treating it as a processing requirement would move a pristine
                    // Direct stream onto the processed path to apply nothing. Derived through the
                    // same mapping the DSP itself uses, so the requirement and the filter bank
                    // cannot disagree about what "bypassed" means.
                    graphicEq = !eq.toDspSettings().isBypass || editorOpen,
                    // Attribution comes from the contributor, never from `player.volume` — that
                    // holds the *product* of both, so reading it would report ReplayGain's
                    // attenuation as the user's own volume and name the wrong processor.
                    //
                    // Non-unity, not merely enabled: ReplayGain switched on for a track whose tags
                    // ask for 0 dB is a mathematical identity, the same way a flat enabled EQ is,
                    // and neither is a reason to leave the pristine path.
                    replayGain = gain.replayGainFactor?.let {
                        kotlin.math.abs(it - 1f) > 0.0001f
                    } ?: false,
                    // Unity means no gain stage is needed. The threshold is tight because this
                    // decides a producer switch: too loose and a level that is audibly unity keeps
                    // the stream on the processed path for nothing.
                    digitalVolume = kotlin.math.abs(gain.userDigitalGain - 1f) > 0.0001f,
                    nonDefaultSpeed = kotlin.math.abs(speed - 1f) > 0.0001f,
                    crossfade = crossfade,
                    androidSessionEffect = eq.bassBoostEnabled || eq.virtualizerEnabled ||
                        eq.loudnessEnhancerEnabled,
                )
            }
                // F-02: the Pure Direct lock freezes every DSP contributor's requirement (the
                // settings themselves are kept and apply again when it is left).
                // B9-4: the persisted lock, unless the debug bridge asked this process to read it as off
                // (a measurement of PROCESSED; never persisted, a release build never sets it).
                // B16-G4: the lock is only a *preference* here. It is delivered with the listening state, and the engine applies
                // it only while the router has found a USB DAC (PristineModePolicy.lockInForce): without one there is
                // nothing to keep untouched and everything applies, the preference kept for when a DAC is plugged in.
                .combine(PristineLockDiagnostic.requirementsLock(userPreferencesRepository.pristineLockFlow)) { requirements, locked ->
                    requirements to locked
                }
                .collect { (requirements, locked) -> engine.updateProcessingRequirements(requirements, pristineLock = locked) }
        }

        // B14-3 (audit A-05): on USB Direct the music does not go through an AudioTrack, so the system's own
        // ducking cannot reach it. The engine forwards the focus callbacks; the controller attenuates.
        engine.directDuckListener = directDuckController
        // B25-S1: the engine pauses when the USB DAC it plays to is unplugged; the same resume-on-reconnect rule as a headset applies.
        engine.outputLossListener = OutputLossListener { reason ->
            Timber.tag(TAG).w("Playback paused: the output went away (%s)", reason)
            headsetReconnectRule.onOutputLostPause(SystemClock.elapsedRealtime())
        }
        serviceScope.launch {
            engine.audioRouteState
                .map { it.isDirectActive to it.signalMode }
                .distinctUntilChanged()
                .collect {
                    engine.syncFocusRequestToRoute()
                    directDuckController.onRouteChanged()
                }
        }

        // F-07: with the phone locked or the screen off, a volume raise the Safety Max ceiling
        // refuses reaches no readout, so it is said as a quiet notification instead.
        val safetyRefusalNotifier = com.lostf1sh.pixelplayeross.data.volume.SafetyMaxRefusalNotifier(this)
        serviceScope.launch {
            safetyVolumeController.get().refusals.collect { safetyRefusalNotifier.post() }
        }

        serviceScope.launch {
            userPreferencesRepository.resumeOnHeadsetReconnectFlow.collect { enabled ->
                headsetReconnectRule.enabled = enabled
            }
        }

        serviceScope.launch {
            userPreferencesRepository.persistentShuffleEnabledFlow.collect { enabled ->
                persistentShuffleEnabled = enabled
            }
        }

        // B18-R1: what switching shuffle on does to the order. The engine is the one that deals it, so it holds the choice.
        serviceScope.launch {
            userPreferencesRepository.randomModeFlow.collect { mode ->
                engine.randomMode = mode
            }
        }

        serviceScope.launch {
            userPreferencesRepository.replayGainEnabledFlow.collect { enabled ->
                replayGainProcessor.setEnabled(enabled)
                replayGainProcessor.apply(mediaSession?.player?.currentMediaItem)
            }
        }
        serviceScope.launch {
            userPreferencesRepository.replayGainUseAlbumGainFlow.collect { useAlbum ->
                replayGainProcessor.setUseAlbumGain(useAlbum)
                replayGainProcessor.apply(mediaSession?.player?.currentMediaItem)
            }
        }

        serviceScope.launch {
            // B15-F2: the Pure Direct lock freezes the speed like it freezes the EQ. Same reading of the lock as the
            // route's requirements (including the debug override), so the two cannot disagree.
            // B16-G4: held at 1.0× only while the lock is in force, i.e. with a USB DAC; without one the speed applies.
            userPreferencesRepository.playbackSpeedFlow
                .combine(
                    PristineLockDiagnostic.inForce(
                        userPreferencesRepository.pristineLockFlow,
                        engine.audioRouteState.map { it.usbDacPresent }.distinctUntilChanged(),
                    ),
                ) { speed, locked ->
                    speed to locked
                }
                .collect { (speed, locked) ->
                    playbackSpeedFrozen = locked
                    effectivePlaybackSpeed = PristineModePolicy.effectiveSpeed(speed, locked)
                    applyPlaybackSpeed(mediaSession?.player ?: engine.masterPlayer)
                }
        }

        serviceScope.launch {
            val persistent = userPreferencesRepository.persistentShuffleEnabledFlow.first()
            if (persistent) {
                isManualShuffleEnabled = userPreferencesRepository.isShuffleOnFlow.first()
                // The player's own flag, so a queue loaded from now on is dealt in shuffle order (B17-H1). A restored
                // snapshot sets it from what was saved.
                if (isManualShuffleEnabled && !isRestoringPlaybackSnapshot) {
                    withContext(Dispatchers.Main.immediate) { engine.setShuffleEnabled(true) }
                }
                mediaSession?.let { refreshMediaSessionUi(it) }
            }
        }

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val controllerPackage = controller.packageName
                val hintKeys = controller.connectionHints.keySet().joinToString(",")
                Timber.tag(TAG).d(
                    "onConnect from package=%s uid=%s trusted=%s version=%s hints=[%s]",
                    controllerPackage,
                    controller.uid,
                    controller.isTrusted,
                    controller.controllerVersion,
                    hintKeys
                )
                val defaultResult = super.onConnect(session, controller)
                val sessionCommandsBuilder = SessionCommands.Builder()
                    .addSessionCommands(defaultResult.availableSessionCommands.commands)
                if (isPrivilegedController(controller)) {
                    val customCommands = listOf(
                        MusicNotificationProvider.CUSTOM_COMMAND_CLOSE_PLAYER,
                        MusicNotificationProvider.CUSTOM_COMMAND_LIKE,
                        MusicNotificationProvider.CUSTOM_COMMAND_SET_FAVORITE_STATE,
                        MusicNotificationProvider.CUSTOM_COMMAND_TOGGLE_SHUFFLE,
                        MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_ON,
                        MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_OFF,
                        MusicNotificationProvider.CUSTOM_COMMAND_SET_SHUFFLE_STATE,
                        MusicNotificationProvider.CUSTOM_COMMAND_CYCLE_REPEAT_MODE,
                        MusicNotificationProvider.CUSTOM_COMMAND_COUNTED_PLAY,
                        MusicNotificationProvider.CUSTOM_COMMAND_CANCEL_COUNTED_PLAY,
                        MusicNotificationProvider.CUSTOM_COMMAND_SET_SLEEP_TIMER_DURATION,
                        MusicNotificationProvider.CUSTOM_COMMAND_SET_SLEEP_TIMER_END_OF_TRACK,
                        MusicNotificationProvider.CUSTOM_COMMAND_CANCEL_SLEEP_TIMER,
                    ).map { SessionCommand(it, Bundle.EMPTY) }
                    customCommands.forEach { sessionCommandsBuilder.add(it) }
                }
                grantArtworkUriPermissions(
                    controller.packageName,
                    listOfNotNull(session.player.currentMediaItem)
                )

                // B19-S4: only the app's own controller gets the queue. The media notification controller's commands are also the
                // platform session's, so this is what keeps the system's queue of every song (11 MB for 4,870 of them) from being
                // rebuilt on every source update. See [SessionQueuePolicy] for what an external controller loses.
                val getsQueue = SessionQueuePolicy.getsQueue(
                    appPackage = packageName,
                    controllerPackage = controller.packageName,
                    isMediaNotificationController = session.isMediaNotificationController(controller),
                )
                // B31 P2.3: who is connected and who is given the queue (the controllers of a publication, by name).
                Timber.tag("SongChange").i(
                    "session controller connected: package=%s notification=%s getsQueue=%s",
                    controller.packageName, session.isMediaNotificationController(controller), getsQueue,
                )
                return MediaSession.ConnectionResult.accept(
                    sessionCommandsBuilder.build(),
                    SessionQueuePolicy.playerCommands(defaultResult.availablePlayerCommands, getsQueue)
                )
            }

            override fun onPlayerCommandRequest(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                playerCommand: Int
            ): Int {
                // B13-5: a pause or play from the notification, a headset, Bluetooth or the system UI is the
                // user's decision, and this is the one place every such command passes before it runs.
                if (ExternalPlaybackCommands.countsAsUserCommand(
                        playerCommand = playerCommand,
                        controllerPackage = controller.packageName,
                        appPackage = packageName,
                        isMediaNotificationController = session.isMediaNotificationController(controller)
                    )
                ) {
                    ExternalPlaybackCommands.note()
                    Timber.tag(TAG).d(
                        "External play/pause command %d from %s noted as the user's decision",
                        playerCommand,
                        controller.packageName
                    )
                }
                return super.onPlayerCommandRequest(session, controller, playerCommand)
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                Timber.tag("MusicService")
                    .d("onCustomCommand received: ${customCommand.customAction}")
                if (!isPrivilegedController(controller)) {
                    Timber.tag(TAG).w(
                        "Rejected custom command %s from untrusted package=%s",
                        customCommand.customAction,
                        controller.packageName
                    )
                    return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                    )
                }
                when (customCommand.customAction) {
                    MusicNotificationProvider.CUSTOM_COMMAND_CLOSE_PLAYER -> {
                        closeNotificationPlayer()
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_COUNTED_PLAY -> {
                        val count = args.getInt("count", 1)
                        playbackTimerController.startCountedPlay(count)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_CANCEL_COUNTED_PLAY -> {
                        playbackTimerController.stopCountedPlay()
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SET_SLEEP_TIMER_DURATION -> {
                        val minutes = args.getInt(
                            MusicNotificationProvider.EXTRA_SLEEP_TIMER_MINUTES,
                            0
                        )
                        playbackTimerController.setDurationSleepTimer(minutes)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SET_SLEEP_TIMER_END_OF_TRACK -> {
                        val enabled = args.getBoolean(
                            MusicNotificationProvider.EXTRA_END_OF_TRACK_ENABLED,
                            true
                        )
                        playbackTimerController.setEndOfTrackSleepTimer(enabled)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_CANCEL_SLEEP_TIMER -> {
                        playbackTimerController.cancelSleepTimers()
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_TOGGLE_SHUFFLE -> {
                        // What shuffle is going to be (a request may be waiting for a crossfade), not the service's copy of the flag (B19-S2).
                        val enabled = !engine.shuffleIntent
                        updateManualShuffleState(session, enabled = enabled)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_ON -> {
                        Timber.tag("MusicService")
                            .d("Executing SHUFFLE_ON. Current shuffleMode: ${session.player.shuffleModeEnabled}")
                        updateManualShuffleState(session, enabled = true)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_OFF -> {
                        Timber.tag("MusicService")
                            .d("Executing SHUFFLE_OFF. Current shuffleMode: ${session.player.shuffleModeEnabled}")
                        updateManualShuffleState(session, enabled = false)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SET_SHUFFLE_STATE -> {
                        val enabled = args.getBoolean(
                            MusicNotificationProvider.EXTRA_SHUFFLE_ENABLED,
                            false
                        )
                        updateManualShuffleState(session, enabled = enabled)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_CYCLE_REPEAT_MODE -> {
                        val currentMode = session.player.repeatMode
                        val newMode = when (currentMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                            else -> Player.REPEAT_MODE_OFF
                        }
                        session.player.repeatMode = newMode
                        refreshMediaSessionUi(session)
                        requestWidgetFullUpdate(force = true)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_LIKE -> {
                        val songId = session.player.currentMediaItem?.mediaId
                            ?: return@onCustomCommand Futures.immediateFuture(
                                SessionResult(SessionError.ERROR_UNKNOWN)
                            )
                        val targetFavoriteState = !favoriteSongIds.contains(songId)
                        return setCurrentSongFavoriteState(
                            session = session,
                            targetFavoriteState = targetFavoriteState
                        )
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SET_FAVORITE_STATE -> {
                        val enabled = args.getBoolean(
                            MusicNotificationProvider.EXTRA_FAVORITE_ENABLED,
                            false
                        )
                        return setCurrentSongFavoriteState(
                            session = session,
                            targetFavoriteState = enabled
                        )
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                return serviceScope.future {
                    resolveMediaItemsByIds(
                        requestedItems = mediaItems,
                        exposeInternalArtwork = controller.packageName == packageName
                    ).also { resolvedItems ->
                        grantArtworkUriPermissions(
                            controller.packageName,
                            resolvedItems.trustedArtworkGrantItems
                        )
                    }.mediaItems
                }
            }

            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                return serviceScope.future {
                    val resolvedItems = resolveMediaItemsByIds(
                        requestedItems = mediaItems,
                        exposeInternalArtwork = controller.packageName == packageName
                    )
                    grantArtworkUriPermissions(
                        controller.packageName,
                        resolvedItems.trustedArtworkGrantItems
                    )
                    val safeStartIndex = startIndex.coerceIn(
                        0,
                        (resolvedItems.mediaItems.size - 1).coerceAtLeast(0)
                    )
                    MediaSession.MediaItemsWithStartPosition(
                        resolvedItems.mediaItems,
                        safeStartIndex,
                        startPositionMs
                    )
                }
            }
        }

        mediaSession = MediaSession.Builder(this, wrapFadingPlayer(engine.masterPlayer))
            .setCallback(callback)
            .setSessionActivity(getOpenAppPendingIntent())
            .setBitmapLoader(CoilBitmapLoader(this, serviceScope))
            .build()
        // The session has registered its listener: the end of the bracket that times a publication goes right behind it (B19-S4).
        mediaSession?.player?.addListener(publicationMeter.end)

        val localOnlyProvider = LocalOnlyMediaNotificationProvider(this).also {
            it.setSmallIcon(R.drawable.monochrome_player)
        }
        setMediaNotificationProvider(localOnlyProvider)
        if (temporaryForegroundStartedInOnCreate) {
            serviceScope.launch {
                delay(2_000L)
                if (mediaSession?.player?.hasForegroundPlaybackIntent() != true) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    clearTemporaryForegroundNotification()
                }
            }
        }
        serviceScope.launch {
            restorePlaybackQueueSnapshotIfNeeded()
            mediaSession?.let { refreshMediaSessionUi(it) }
            requestWidgetFullUpdate(force = true)
        }

        serviceScope.launch {
            musicRepository.getFavoriteSongIdsFlow().collect { ids ->
                Timber.tag("MusicService")
                    .d("favoriteSongIdsFlow(Room) collected. New ids size: ${ids.size}")
                val oldIds = favoriteSongIds
                favoriteSongIds = ids
                val currentSongId = mediaSession?.player?.currentMediaItem?.mediaId
                if (currentSongId != null) {
                    val wasFavorite = oldIds.contains(currentSongId)
                    val isFavorite = ids.contains(currentSongId)
                    if (wasFavorite != isFavorite) {
                        Timber.tag("MusicService")
                            .d("Favorite status changed for current song. Updating notification.")
                        mediaSession?.let { refreshMediaSessionUi(it) }
                        requestWidgetFullUpdate(force = true)
                    }
                }
            }
        }
    }

    private fun startTemporaryForegroundForCommand() {
        val notification = NotificationCompat.Builder(
            this,
            PixelPlayerApplication.NOTIFICATION_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.monochrome_player)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_processing_action))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(getOpenAppPendingIntent())
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            temporaryForegroundNotificationVisible = true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to promote service to foreground for external command")
        }
    }

    /**
     * Media3's notification provider posts under its own id, so promoting the real
     * media notification does not replace the temporary placeholder — without an
     * explicit cancel the "Processing playback action…" notification stays behind
     * forever once the session notification takes over the foreground.
     */
    private fun clearTemporaryForegroundNotification() {
        if (!temporaryForegroundNotificationVisible) return
        temporaryForegroundNotificationVisible = false
        runCatching {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "Failed to cancel temporary foreground notification")
        }
    }

    private fun isServiceAlreadyForeground(): Boolean {
        val player = mediaSession?.player ?: return false
        return player.hasForegroundPlaybackIntent()
    }

    private fun Player.hasForegroundPlaybackIntent(): Boolean {
        return playWhenReady &&
            mediaItemCount > 0 &&
            playbackState != Player.STATE_IDLE &&
            playbackState != Player.STATE_ENDED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedTemporaryForegroundInOnCreate = temporaryForegroundStartedInOnCreate
        temporaryForegroundStartedInOnCreate = false
        val pendingMediaButtonForegroundStart = consumePendingMediaButtonForegroundStart()
        val forcedForegroundStart =
            intent?.getBooleanExtra(EXTRA_FORCE_FOREGROUND_ON_START, false) == true
        val isMediaButtonIntent = intent?.action == Intent.ACTION_MEDIA_BUTTON
        val needsTemporaryForeground = forcedForegroundStart ||
            pendingMediaButtonForegroundStart ||
            (isMediaButtonIntent &&
                !startedTemporaryForegroundInOnCreate &&
                !isServiceAlreadyForeground()) ||
            when (intent?.action) {
                PlayerActions.PLAY_PAUSE,
                PlayerActions.NEXT,
                PlayerActions.PREVIOUS,
                PlayerActions.FAVORITE,
                PlayerActions.PLAY_FROM_QUEUE,
                PlayerActions.SHUFFLE,
                PlayerActions.REPEAT -> true
                else -> false
            }
        if (needsTemporaryForeground && !startedTemporaryForegroundInOnCreate) {
            startTemporaryForegroundForCommand()
        }

        intent?.action?.let { action ->
            Timber.tag(TAG).d("onStartCommand widget action: %s", action)
            val player = mediaSession?.player ?: engine.masterPlayer
            when (action) {
                PlayerActions.PLAY_PAUSE -> {
                    // B13-5: the widget is an entry point of its own; it does not pass through the session's command check.
                    ExternalPlaybackCommands.note()
                    // The in-app rule (AUD-067): intent-based, and an ended queue restarts.
                    com.lostf1sh.pixelplayeross.data.service.player.PlayPauseDecision.apply(player)
                    requestWidgetFullUpdate(force = true)
                }
                PlayerActions.NEXT -> {
                    player.seekToNext()
                    requestWidgetFullUpdate(force = true)
                }
                PlayerActions.PREVIOUS -> {
                    player.seekToPrevious()
                    requestWidgetFullUpdate(force = true)
                }
                PlayerActions.FAVORITE -> {
                    val songId = player.currentMediaItem?.mediaId
                    if (!songId.isNullOrBlank()) {
                        serviceScope.launch {
                            val updatedFavorite = musicRepository.toggleFavoriteStatus(songId)
                            favoriteSongIds = if (updatedFavorite) {
                                favoriteSongIds + songId
                            } else {
                                favoriteSongIds - songId
                            }
                            mediaSession?.let { refreshMediaSessionUi(it) }
                            requestWidgetFullUpdate(force = true)
                        }
                    }
                }
                PlayerActions.PLAY_FROM_QUEUE -> {
                    val timeline = player.currentTimeline
                    if (!timeline.isEmpty) {
                        val window = Timeline.Window()
                        val queueMediaIds = (0 until timeline.windowCount).map { i ->
                            timeline.getWindow(i, window).mediaItem.mediaId
                        }
                        val index = WidgetQueueSelection.indexOf(
                            queueMediaIds,
                            intent.getStringExtra(EXTRA_WIDGET_MEDIA_ID),
                            intent.getLongExtra("song_id", -1L),
                        )
                        if (index >= 0) {
                            player.seekTo(index, C.TIME_UNSET)
                            player.prepare()
                            player.play()
                        }
                    }
                }
                PlayerActions.SHUFFLE -> {
                    val newState = !isManualShuffleEnabled
                    mediaSession?.let { session ->
                        updateManualShuffleState(session, enabled = newState)
                    } ?: run {
                        isManualShuffleEnabled = newState
                        requestWidgetFullUpdate(force = true)
                    }
                }
                PlayerActions.REPEAT -> {
                    val newMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                        else -> Player.REPEAT_MODE_OFF
                    }
                    player.repeatMode = newMode
                    requestWidgetFullUpdate(force = true)
                }
                ACTION_SLEEP_TIMER_EXPIRED -> {
                    Timber.tag(TAG).d("Sleep timer expired action received. Pausing player.")
                    playbackTimerController.onDurationSleepTimerExpired()
                    sleepTimerStateHolder.cancelSleepTimer(suppressDefaultToast = true)
                }
            }
        }
        val startCommandResult = super.onStartCommand(intent, flags, startId)
        if (needsTemporaryForeground || startedTemporaryForegroundInOnCreate) {
            if (mediaSession?.player?.hasForegroundPlaybackIntent() != true) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                clearTemporaryForegroundNotification()
                if (needsTemporaryForeground) {
                    stopSelfResult(startId)
                }
            }
        }
        return startCommandResult
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** Checkpoints the position while playing (AUD-121); the pause itself is covered by the snapshot. */
    private fun updatePositionCheckpointing(isPlaying: Boolean) {
        positionCheckpointJob?.cancel()
        positionCheckpointJob = if (isPlaying) {
            serviceScope.launch {
                positionCheckpointer.run { withContext(Dispatchers.Main.immediate) { playingPosition() } }
            }
        } else {
            null
        }
    }

    /** The playing item and position, or null when nothing is playing. Main thread. */
    private fun playingPosition(): Pair<String, Long>? {
        val player = engine.masterPlayer
        val mediaId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return null
        if (!player.isPlaying) return null
        return mediaId to player.currentPosition.coerceAtLeast(0L)
    }

    /** Writes the position now, on the app scope so it survives the service going away (AUD-121). */
    private fun checkpointPositionNow() {
        val (mediaId, positionMs) = runCatching { playingPosition() }.getOrNull() ?: return
        appScope.launch {
            runCatching { userPreferencesRepository.setPlaybackPosition(mediaId, positionMs) }
                .onFailure { e -> Timber.tag(TAG).w(e, "Failed to checkpoint playback position") }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The process may be killed next; nothing else would record where playback was.
        checkpointPositionNow()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        checkpointPositionNow()
        val session = mediaSession
        val continuePlayback = shouldContinuePlaybackAfterTaskRemoved(
            hasForegroundPlaybackIntent = session?.player?.hasForegroundPlaybackIntent() == true,
            keepPlayingInBackground = keepPlayingInBackground
        )
        if (continuePlayback && session != null) {
            // Media3's default onTaskRemoved pauses all players and stops the service
            // whenever it is not in foreground state, and OEMs freeze cached processes
            // that have no foreground service — either way playback dies with the task.
            if (!isPlaybackOngoing()) {
                Timber.tag(TAG).w(
                    "Task removed while playing without foreground state; re-promoting."
                )
                onUpdateNotification(session, startInForegroundRequired = true)
            }
            return
        }
        stopPlaybackAndUnload(reason = "task_removed")
    }

    override fun onDestroy() {
        checkpointPositionNow()
        positionCheckpointJob?.cancel()
        PlaybackActivityTracker.setPlaybackActive(false)
        listeningStatsTracker.finalizeCurrentSession(
            endReason = TrackEndReason.INTERRUPTED,
            forceSynchronousPersistence = true
        )
        flushPendingPlaybackSnapshotOnDestroy()
        playbackSnapshotPersistJob?.cancel()
        mediaSessionButtonRefreshJob?.cancel()
        followUpMediaSessionUiRefreshJob?.cancel()
        debouncedWidgetUpdateJob?.cancel()
        unregisterHeadsetReconnectMonitor()
        unregisterListenAudioFactsFeed()
        replayGainProcessor.cancel()

        engine.directDuckListener = null
        engine.outputLossListener = null
        engine.removePlayerSwapListener(playerSwapListener)
        engine.removeTransitionDisplayPlayerListener(transitionDisplayPlayerListener)
        engine.removeTransitionFinishedListener(transitionFinishedListener)
        engine.setOnPlayerAboutToBeReleasedListener {}
        mediaSession?.player?.removeListener(playerListener)
        engine.masterPlayer.removeListener(playerListener)

        mediaSession?.run {
            release()
            mediaSession = null
        }
        equalizerManager.release()
        externalAudioEffectSession.close()
        engine.release()
        controller.release()
        serviceScope.cancel()
        Thread.currentThread().setUncaughtExceptionHandler(previousMainThreadExceptionHandler)
        previousMainThreadExceptionHandler = null
        super.onDestroy()
    }

    private var consecutivePlaybackErrors = 0
    private val maxConsecutivePlaybackErrors = 5

    private val playerListener = object : Player.Listener {
        override fun onVolumeChanged(volume: Float) {
            // A play/pause fade step is not the user choosing a level.
            if (com.lostf1sh.pixelplayeross.data.service.player.FadingPlayer.isRampWrite) return
            replayGainProcessor.onPlayerVolumeChanged(volume)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // B15-F2: while the Purity lock holds the speed, a controller that sets another one (a car, a watch, another
            // app) does not get it: the stream is on Direct, which has no time-stretch, and songs join exactly.
            if (playbackSpeedFrozen && abs(playbackParameters.speed - effectivePlaybackSpeed) > 0.001f) {
                applyPlaybackSpeed(mediaSession?.player ?: engine.masterPlayer)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = mediaSession?.player ?: engine.masterPlayer
            Timber.tag(TAG).d("onIsPlayingChanged: $isPlaying. Duration: ${player.duration}, Seekable: ${player.isCurrentMediaItemSeekable}")
            PlaybackActivityTracker.setPlaybackActive(isPlaying)
            syncLocalListeningStatsFromPlayer(player)

            if (isPlaying) {
                replayGainProcessor.reapplyLastAppliedVolume(player)
            }
            updatePositionCheckpointing(isPlaying)
            requestWidgetFullUpdate(force = true)
            mediaSession?.let { refreshMediaSessionUi(it) }
            schedulePlaybackSnapshotPersist()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            headsetReconnectRule.onPlayWhenReadyChanged(
                playWhenReady = playWhenReady,
                noisyPause = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                nowMs = SystemClock.elapsedRealtime(),
            )
            if (headsetReconnectRule.isArmed) Timber.tag(TAG).d("Marked playback for headset reconnect resume")
            requestWidgetFullUpdate(force = true)
            mediaSession?.let { refreshMediaSessionUi(it) }
            schedulePlaybackSnapshotPersist()
        }

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            val canSeek = availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            val player = engine.masterPlayer
            Timber.tag(TAG).w("onAvailableCommandsChanged. Can Seek Command? $canSeek. IsSeekable? ${player.isCurrentMediaItemSeekable}. Duration: ${player.duration}")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            Timber.tag(TAG).d("Playback state changed: $playbackState")
            if (playbackState == Player.STATE_READY) {
                consecutivePlaybackErrors = 0
            }
            if (playbackState == Player.STATE_ENDED) {
                // The one end reason the platform states unambiguously. Everything that stops
                // without reaching here is an interruption, not a decision.
                listeningStatsTracker.finalizeCurrentSession(endReason = TrackEndReason.NATURAL_END)
            } else {
                syncLocalListeningStatsFromPlayer(mediaSession?.player ?: engine.masterPlayer)
            }
            mediaSession?.let { refreshMediaSessionUi(it) }
            schedulePlaybackSnapshotPersist(immediate = playbackState == Player.STATE_IDLE)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = timedSection("MusicService.onTimelineChanged") {
            // B18-R3: which publications a song change really causes. reason 1 (source update) is the duration of the item that was
            // just prepared; each one is a full publication of the queue to every controller, so it is logged with the size.
            Timber.tag("SongChange").d(
                "Timeline changed reason=%d items=%d (%d ms after the last transition)",
                reason, timeline.windowCount, SystemClock.elapsedRealtime() - lastTransitionLoggedAtMs,
            )
            requestWidgetFullUpdate(force = true)
            schedulePlaybackSnapshotPersist(immediate = timeline.isEmpty)
            val player = engine.masterPlayer
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex != androidx.media3.common.C.INDEX_UNSET) {
                runCatching { replayGainProcessor.prefetch(player.getMediaItemAt(nextIndex)) }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // B28a: a seek inside the item is the same occurrence, unless it goes back to the start after the track was heard.
            if (reason == Player.DISCONTINUITY_REASON_SEEK &&
                oldPosition.mediaItem?.mediaId != null &&
                oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex
            ) {
                listeningStatsTracker.onSeekWithinItem(oldPosition.positionMs, newPosition.positionMs)
            }
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
                reason == Player.DISCONTINUITY_REASON_SEEK
            ) {
                val currentItem = mediaSession?.player?.currentMediaItem
                val oldMediaId = oldPosition.mediaItem?.mediaId
                val newMediaId = newPosition.mediaItem?.mediaId
                if (oldMediaId != null && oldMediaId == newMediaId) {
                    replayGainProcessor.reapplyLastAppliedVolume(engine.masterPlayer)
                } else {
                    replayGainProcessor.apply(currentItem)
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = timedSection("MusicService.onMediaItemTransition") {
            lastTransitionLoggedAtMs = SystemClock.elapsedRealtime()
            timedSection("MusicService.grantArtworkUriPermissions") {
                mediaItem?.let(::grantArtworkUriPermissionsToConnectedControllers)
            }
            timedSection("MusicService.listeningStats") {
                syncLocalListeningStatsFromPlayer(
                    mediaSession?.player ?: engine.masterPlayer,
                    forceNewSession = true,
                    previousEndReason = trackEndReasonFromTransition(reason)
                )
            }
            playbackTimerController.handleMediaItemTransition(mediaItem, reason)
            replayGainProcessor.apply(mediaSession?.player?.currentMediaItem)
            val player = engine.masterPlayer
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex != androidx.media3.common.C.INDEX_UNSET) {
                runCatching { replayGainProcessor.prefetch(player.getMediaItemAt(nextIndex)) }
            }
            requestWidgetFullUpdate(force = false)
            mediaSession?.let { refreshMediaSessionUi(it) }
            schedulePlaybackSnapshotPersist()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            requestWidgetFullUpdate(force = true)
            mediaSession?.let { refreshMediaSessionUiWithFollowUp(it) }
            val activePlayer = mediaSession?.player ?: engine.masterPlayer
            replayGainProcessor.onMediaMetadataChanged(activePlayer.currentMediaItem)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            Timber.tag("MusicService")
                .d("playerListener.onShuffleModeEnabledChanged: $shuffleModeEnabled")
            // B17-H1: the player's flag is the one truth about shuffle (the app, the widget, the notification and a head unit
            // all end up here), so the service's own copy and the remembered preference follow it.
            isManualShuffleEnabled = shuffleModeEnabled
            if (persistentShuffleEnabled && !isRestoringPlaybackSnapshot) {
                serviceScope.launch { userPreferencesRepository.setShuffleOn(shuffleModeEnabled) }
            }
            requestWidgetFullUpdate(force = true)
            mediaSession?.let { refreshMediaSessionUi(it) }
            schedulePlaybackSnapshotPersist()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            requestWidgetFullUpdate(force = true)
            mediaSession?.let { refreshMediaSessionUi(it) }
            schedulePlaybackSnapshotPersist()
        }

        override fun onPlayerError(error: PlaybackException) {
            val player = mediaSession?.player ?: engine.masterPlayer
            Timber.tag(TAG).e(error, "Player error on item %s", player.currentMediaItem?.mediaId)
            AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                type = AdvancedPerformanceDiagnostics.EventTypes.PLAYBACK,
                name = "player_error"
            ) {
                mapOf(
                    "code" to error.errorCodeName,
                    "message" to (error.message ?: error.javaClass.simpleName)
                )
            }
            if (player.hasNextMediaItem() && consecutivePlaybackErrors < maxConsecutivePlaybackErrors) {
                consecutivePlaybackErrors++
                player.seekToNextMediaItem()
                player.prepare()
            } else {
                consecutivePlaybackErrors = 0
            }
        }
    }

    /** Applies the user's playback speed to [player], preserving pitch (time-stretch). */
    private fun applyPlaybackSpeed(player: Player) {
        if (abs(player.playbackParameters.speed - effectivePlaybackSpeed) > 0.001f) {
            player.setPlaybackSpeed(effectivePlaybackSpeed)
        }
    }

    /**
     * B28a: keeps the listening tracker told what the audio is while nothing else syncs it: the engine publishing a new route
     * or new Direct diagnostics, and an output coming or going on the Android route. Equal facts are dropped by the tracker.
     */
    private fun registerListenAudioFactsFeed() {
        serviceScope.launch {
            combine(engine.audioRouteState, engine.usbAudioDiagnostics) { route, diagnostics -> route to diagnostics }
                .collect { pushListenAudioFacts() }
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = outputsChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = outputsChanged()

            private fun outputsChanged() {
                androidOutputReader.refresh()
                pushListenAudioFacts()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        outputRouteCallback = callback
    }

    private fun unregisterListenAudioFactsFeed() {
        outputRouteCallback?.let { runCatching { audioManager.unregisterAudioDeviceCallback(it) } }
        outputRouteCallback = null
    }

    private fun registerHeadsetReconnectMonitor() {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (!addedDevices.any { HeadsetReconnectRule.isReconnectableOutputType(it.type) }) return
                maybeResumeAfterHeadsetReconnect()
            }
        }

        audioManager.registerAudioDeviceCallback(callback, null)
        headsetReconnectCallback = callback
    }

    private fun unregisterHeadsetReconnectMonitor() {
        headsetReconnectCallback?.let { callback ->
            runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
        }
        headsetReconnectCallback = null
        clearHeadsetReconnectResume()
    }

    private fun maybeResumeAfterHeadsetReconnect() {
        if (!headsetReconnectRule.enabled || !headsetReconnectRule.isArmed) return
        val player = engine.masterPlayer
        val resume = headsetReconnectRule.onOutputAdded(
            nowMs = SystemClock.elapsedRealtime(),
            reconnectableOutputPresent = hasReconnectableHeadsetOutput(),
            hasMediaItem = player.currentMediaItem != null,
            playWhenReady = player.playWhenReady,
            idleOrEnded = player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED,
        )
        if (!resume) return
        Timber.tag(TAG).d("Resuming playback after headset reconnect")
        player.play()
    }

    private fun hasReconnectableHeadsetOutput(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { HeadsetReconnectRule.isReconnectableOutputType(it.type) }
    }

    private fun clearHeadsetReconnectResume() {
        headsetReconnectRule.clear()
    }

    private fun schedulePlaybackSnapshotPersist(immediate: Boolean = false) {
        if (isPlaybackUnloadInProgress) {
            return
        }
        playbackSnapshotPersistJob?.cancel()
        playbackSnapshotPersistJob = serviceScope.launch {
            if (!immediate) {
                delay(PLAYBACK_SNAPSHOT_DEBOUNCE_MS)
            }
            persistPlaybackSnapshot()
        }
    }

    private suspend fun persistPlaybackSnapshot(playWhenReadyOverride: Boolean? = null) {
        if (isRestoringPlaybackSnapshot) return
        val snapshot = capturePlaybackSnapshot(playWhenReadyOverride)
        runCatching {
            userPreferencesRepository.setPlaybackQueueSnapshot(snapshot)
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "Failed to persist playback snapshot")
        }
    }

    private suspend fun capturePlaybackSnapshot(playWhenReadyOverride: Boolean? = null): PlaybackQueueSnapshot? =
        withContext(Dispatchers.Main.immediate) {
            capturePlaybackSnapshotFromPlayer(playWhenReadyOverride)
        }

    private fun capturePlaybackSnapshotFromPlayer(
        playWhenReadyOverride: Boolean? = null
    ): PlaybackQueueSnapshot? = timedSection("MusicService.capturePlaybackSnapshot") {
        capturePlaybackSnapshotNow(playWhenReadyOverride)
    }

    private fun capturePlaybackSnapshotNow(
        playWhenReadyOverride: Boolean? = null
    ): PlaybackQueueSnapshot? {
        val player = engine.masterPlayer
        val mediaItemCount = player.mediaItemCount
        if (mediaItemCount <= 0) {
            return null
        }

        // A windowed master (after a crossfade on a long queue) holds only part of the queue; the
        // engine has the rest and the absolute index (AUD-023).
        // B17-H1: a window cut from the play order (shuffle on) keeps the queue it was cut from, in its own order, and the
        // order itself: that is what is saved, not the window.
        val shuffleOn = player.shuffleModeEnabled
        val windowedShuffle = if (shuffleOn) engine.windowedShuffleQueue() else null
        val windowed = engine.isUsingWindowedQueue() && windowedShuffle == null
        val fullQueue = when {
            windowedShuffle != null -> windowedShuffle.first
            windowed -> engine.getFullQueue()
            else -> emptyList()
        }
        val sourceIndex = if (windowedShuffle != null) {
            windowedShuffle.second.getOrNull(engine.getCurrentAbsoluteIndex()) ?: 0
        } else {
            QueueSource.select(
                windowed = windowed,
                fullQueue = fullQueue,
                absoluteIndex = if (windowed) engine.getCurrentAbsoluteIndex() else 0,
                playerItems = emptyList<MediaItem>(),
                playerIndex = player.currentMediaItemIndex,
            ).second
        }
        // The items only change with the queue (AUD-108): rebuilt when the Timeline or the full
        // queue is a new object, not on every play/pause/transition.
        // B18-R3: keyed on the queue's revision, not on the timeline object. ExoPlayer replaces the timeline for every source update
        // (the duration of the song that was just prepared: one or two per song change), and each replacement used to rebuild the
        // snapshot of all ~4,870 items here, on the main thread, 1.5 s after every song change.
        val (snapshotItems, keptIndices) = snapshotItemsMemo.get(engine.queueRevision, player, windowed || windowedShuffle != null, fullQueue) {
            buildSnapshotItems(
                if (windowedShuffle != null) {
                    fullQueue
                } else {
                    QueueSource.select(
                        windowed = windowed,
                        fullQueue = fullQueue,
                        absoluteIndex = 0,
                        playerItems = List(mediaItemCount, player::getMediaItemAt),
                        playerIndex = 0,
                    ).first
                }
            )
        }

        if (snapshotItems.isEmpty()) {
            return null
        }

        val currentMediaId = player.currentMediaItem?.mediaId
        // Positional, not `indexOfFirst(mediaId)`: a repeated track keeps its own occurrence, and
        // skipped items before the current one do not shift it onto another track (AUD-044).
        val safeCurrentIndex = snapshotRemapMemo.currentIndex(keptIndices, sourceIndex)

        val safeRepeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF,
            Player.REPEAT_MODE_ONE,
            Player.REPEAT_MODE_ALL -> player.repeatMode
            else -> Player.REPEAT_MODE_OFF
        }

        // The order the songs play in, over the items that were kept (B17-H1). The queue itself is in the base order.
        val playOrder: IntArray? = when {
            !shuffleOn -> null
            windowedShuffle != null -> windowedShuffle.second
            windowed -> null // a window whose play-ordered source is gone: the saved list is the sequence
            else -> shuffleOrderMemo.get(engine.queueRevision, player) {
                PlayOrder.of(player.currentTimeline, shuffleEnabled = true)
            }
        }
        val savedOrder = playOrder?.let { order ->
            val sourceCount = if (windowedShuffle != null) windowedShuffle.first.size else mediaItemCount
            snapshotRemapMemo.savedOrder(order, sourceCount, keptIndices)
        }

        return PlaybackQueueSnapshot(
            items = snapshotItems,
            currentMediaId = currentMediaId,
            currentIndex = safeCurrentIndex,
            currentPositionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = playWhenReadyOverride ?: player.playWhenReady,
            repeatMode = safeRepeatMode,
            shuffleEnabled = shuffleOn,
            shuffleOrder = savedOrder,
        )
    }

    /** When the last song change reached this service, for the log line that says how long after it a publication came (B18-R3). */
    private var lastTransitionLoggedAtMs = 0L

    /** The play order read off the queue, once per revision of it (the capture runs on every state change). */
    private val shuffleOrderMemo = IdentityMemo<IntArray>()

    /** B23-P4: the saved order and the cursor remap, once per revision too: they are over the whole queue and ran on every capture. */
    private val snapshotRemapMemo = SnapshotRemapMemo()

    /** Last snapshot items per queue object (AUD-108); main thread, like the capture. */
    private val snapshotItemsMemo =
        IdentityMemo<Pair<List<PlaybackQueueItemSnapshot>, List<Int>>>()

    private fun buildSnapshotItems(
        sourceItems: List<MediaItem>,
    ): Pair<List<PlaybackQueueItemSnapshot>, List<Int>> {
        val snapshotItems = ArrayList<PlaybackQueueItemSnapshot>(sourceItems.size)
        val keptIndices = ArrayList<Int>(sourceItems.size)
        for ((index, mediaItem) in sourceItems.withIndex()) {
            val metadata = mediaItem.mediaMetadata
            val playerUri = mediaItem.localConfiguration?.uri?.toString()
            val uri = playerUri
                ?: metadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)

            if (mediaItem.mediaId.isBlank() || uri.isNullOrBlank()) {
                continue
            }

            val durationMs = metadata.extras
                ?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION)
                ?.takeIf { it > 0L }

            snapshotItems.add(
                PlaybackQueueItemSnapshot(
                    mediaId = mediaItem.mediaId,
                    uri = uri,
                    title = metadata.title?.toString(),
                    artist = metadata.artist?.toString(),
                    albumTitle = metadata.albumTitle?.toString(),
                    artworkUri = resolveStoredArtworkUriString(metadata),
                    durationMs = durationMs,
                )
            )
            keptIndices.add(index)
        }
        return snapshotItems to keptIndices
    }

    private suspend fun restorePlaybackQueueSnapshotIfNeeded() {
        val alreadyHasQueue = withContext(Dispatchers.Main.immediate) {
            !RestoreQueuePolicy.mayApply(engine.masterPlayer.mediaItemCount)
        }
        if (alreadyHasQueue) return

        val snapshot = runCatching {
            userPreferencesRepository.getPlaybackQueueSnapshotOnce()
        }.getOrNull() ?: return

        if (snapshot.items.isEmpty()) {
            return
        }

        val allowBackgroundPlayback = runCatching {
            userPreferencesRepository.keepPlayingInBackgroundFlow.first()
        }.getOrDefault(keepPlayingInBackground)
        // Always paused after a restore (AUD-125).
        val shouldRestorePlaying = RestorePlaybackPolicy.playWhenReadyAfterRestore(snapshot, allowBackgroundPlayback)

        val restoredItems = ArrayList<MediaItem>(snapshot.items.size)
        val keptIndices = ArrayList<Int>(snapshot.items.size)
        snapshot.items.forEachIndexed { index, item ->
            if (!com.lostf1sh.pixelplayeross.utils.LocalArtworkUri.isLikelyLocalMedia(item.uri)) return@forEachIndexed
            buildMediaItemFromSnapshot(item)?.let {
                restoredItems.add(it)
                keptIndices.add(index)
            }
        }
        if (restoredItems.isEmpty()) {
            userPreferencesRepository.setPlaybackQueueSnapshot(null)
            return
        }

        // The saved index points into the unfiltered list; carry it across the filter by position
        // (AUD-044). Only an index outside the saved list falls back to the media id.
        val resolvedIndex = when {
            snapshot.currentIndex in snapshot.items.indices ->
                QueueIndexMapping.remap(keptIndices, snapshot.currentIndex)
            !snapshot.currentMediaId.isNullOrBlank() -> {
                restoredItems.indexOfFirst { it.mediaId == snapshot.currentMediaId }
                    .takeIf { it >= 0 } ?: 0
            }
            else -> 0
        }

        // F-09: only what can still be played comes back. A queue whose files were deleted from storage
        // used to be restored as saved: the player prepared the missing current item, skipped a few
        // more and sat in ERROR, and did it again after every restart. The saved current item and
        // the ones after it are probed (nearest first) until one opens; the rest are not touched.
        val probeStartedAt = SystemClock.elapsedRealtime()
        val selection = withContext(Dispatchers.IO) {
            RestoreQueuePolicy.select(restoredItems.size, resolvedIndex) { position ->
                val item = restoredItems[position]
                // B12a-8: where the library keeps the file, so an item on an SD card that is out is told from a deleted
                // one. A blocking lookup on the IO thread, once per probed item (normally one).
                val libraryPath = runCatching {
                    kotlinx.coroutines.runBlocking { musicRepository.getSong(item.mediaId).first()?.path }
                }.getOrNull()
                QueueFileProbe.presence(applicationContext, item.localConfiguration?.uri, libraryPath)
            }
        }
        Timber.tag(TAG).i(
            "Restore probed %d of %d queue items in %d ms",
            selection.probedCount,
            restoredItems.size,
            SystemClock.elapsedRealtime() - probeStartedAt
        )
        val startIndexInQueue = selection.startPosition
        if (startIndexInQueue == null) {
            Timber.tag(TAG).w(
                "Playback snapshot not restored: all %d probed files are gone",
                selection.droppedCount
            )
            userPreferencesRepository.setPlaybackQueueSnapshot(null)
            return
        }
        val playableItems = selection.keptPositions.map { restoredItems[it] }
        val restoreStartPositionMs =
            if (selection.startWasReplaced) 0L else snapshot.currentPositionMs.coerceAtLeast(0L)
        if (selection.droppedCount > 0) {
            Timber.tag(TAG).i(
                "Restore dropped %d queue items whose file is gone (current item replaced=%s)",
                selection.droppedCount,
                selection.startWasReplaced
            )
        }

        val applied = withContext(Dispatchers.Main.immediate) {
            val player = engine.masterPlayer
            if (!RestoreQueuePolicy.mayApply(player.mediaItemCount)) {
                return@withContext false
            }

            val safeRepeatMode = when (snapshot.repeatMode) {
                Player.REPEAT_MODE_OFF,
                Player.REPEAT_MODE_ONE,
                Player.REPEAT_MODE_ALL -> snapshot.repeatMode
                else -> Player.REPEAT_MODE_OFF
            }

            isRestoringPlaybackSnapshot = true
            try {
                // A resume at the saved position: the Direct path is told the same number before the
                // item starts, or a Direct-capable sink starts the native engine at 0 (AUD-045).
                engine.declareItemStartTarget(restoreStartPositionMs)
                player.setMediaItems(
                    playableItems,
                    startIndexInQueue,
                    restoreStartPositionMs
                )
                if (shouldRestorePlaying || playableItems.size <= PAUSED_RESTORE_PREPARE_QUEUE_LIMIT) {
                    player.prepare()
                }
                player.repeatMode = safeRepeatMode
                // B17-H1: shuffle is the player's own mode; the timeline is in the order it was saved in. The saved play order
                // refers to the saved list, the player now holds only what could be restored, so it is carried across both
                // filters. A snapshot from before the order was saved (its items were already the sequence that was playing)
                // plays that sequence front to back.
                player.shuffleModeEnabled = snapshot.shuffleEnabled
                if (snapshot.shuffleEnabled) {
                    val restoredOrder = ShuffleOrderPersistence.orderToRestore(
                        saved = snapshot.shuffleOrder,
                        savedCount = snapshot.items.size,
                        keptByLocalFilter = keptIndices,
                        afterLocalFilterCount = restoredItems.size,
                        keptByProbe = selection.keptPositions,
                        restoredCount = playableItems.size,
                    )
                    if (!engine.setPlayOrder(restoredOrder)) {
                        Timber.tag(TAG).w("Restore: the saved shuffle order does not fit the %d restored items", playableItems.size)
                    }
                }
                isManualShuffleEnabled = snapshot.shuffleEnabled
                if (shouldRestorePlaying) {
                    player.playWhenReady = true
                } else {
                    player.playWhenReady = false
                }
            } finally {
                isRestoringPlaybackSnapshot = false
            }
            true
        }

        if (!applied) {
            // FIX-1c: something newer loaded while the snapshot was being read and probed (an external
            // VIEW, a song chosen in the app). It wins; the snapshot is not applied, and not claimed.
            Timber.tag(TAG).i(
                "Playback snapshot not restored: the player already has a queue (%d items)",
                playableItems.size
            )
            return
        }

        Timber.tag(TAG).i(
            "Restored playback snapshot: items=%d index=%d playWhenReady=%s",
            playableItems.size,
            startIndexInQueue,
            shouldRestorePlaying
        )
        schedulePlaybackSnapshotPersist(immediate = true)
    }

    private fun buildMediaItemFromSnapshot(snapshotItem: PlaybackQueueItemSnapshot): MediaItem? {
        if (snapshotItem.mediaId.isBlank() || snapshotItem.uri.isBlank()) {
            return null
        }

        val metadataBuilder = MediaMetadata.Builder()
        snapshotItem.title?.takeIf { it.isNotBlank() }?.let { metadataBuilder.setTitle(it) }
        snapshotItem.artist?.takeIf { it.isNotBlank() }?.let { metadataBuilder.setArtist(it) }
        snapshotItem.albumTitle?.takeIf { it.isNotBlank() }?.let { metadataBuilder.setAlbumTitle(it) }
        val restoredArtworkUri = MediaItemBuilder.externalControllerArtworkUri(
            context = this,
            rawArtworkUri = snapshotItem.artworkUri
        )
        restoredArtworkUri?.let { metadataBuilder.setArtworkUri(it) }

        val extras = Bundle().apply {
            putBoolean(
                MediaItemBuilder.EXTERNAL_EXTRA_FLAG,
                snapshotItem.mediaId.startsWith("external:")
            )
            putString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI, snapshotItem.uri)
            snapshotItem.albumTitle?.takeIf { it.isNotBlank() }?.let {
                putString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM, it)
            }
            (restoredArtworkUri?.toString() ?: snapshotItem.artworkUri)
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    putString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM_ART, it)
                }
            snapshotItem.durationMs?.takeIf { it > 0L }?.let {
                putLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION, it)
            }
        }
        metadataBuilder.setExtras(extras)

        return MediaItem.Builder()
            .setMediaId(snapshotItem.mediaId)
            .setUri(MediaItemBuilder.playbackUri(snapshotItem.uri))
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun getOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            setPackage(packageName)
            action = "com.lostf1sh.pixelplayeross.action.OPEN_PLAYER"
            addCategory(Intent.CATEGORY_DEFAULT)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ACTION_SHOW_PLAYER", true)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private var debouncedWidgetUpdateJob: Job? = null
    private var followUpMediaSessionUiRefreshJob: Job? = null
    private var mediaSessionButtonRefreshJob: Job? = null
    private var lastAppliedMediaButtonSignature: String? = null
    private val widgetStateDebounceMs = 300L

    private fun requestWidgetFullUpdate(force: Boolean = false) {
        debouncedWidgetUpdateJob?.cancel()
        debouncedWidgetUpdateJob = serviceScope.launch {
            val debounceMs = if (force) {
                FORCED_WIDGET_STATE_DEBOUNCE_MS
            } else {
                widgetStateDebounceMs
            }
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            processWidgetUpdateInternal()
        }
    }

    private var lastWidgetPlayerInfo: PlayerInfo? = null

    private fun shouldUpdateWidget(old: PlayerInfo, new: PlayerInfo): Boolean {
        if (old.songTitle != new.songTitle) return true
        if (old.artistName != new.artistName) return true
        if (old.isPlaying != new.isPlaying) return true
        if (old.albumArtUri != new.albumArtUri) return true
        if ((old.albumArtBitmapData == null) != (new.albumArtBitmapData == null)) return true
        if (old.isFavorite != new.isFavorite) return true
        if (old.queue != new.queue) return true
        if (old.themeColors != new.themeColors) return true
        if (old.isShuffleEnabled != new.isShuffleEnabled) return true
        if (old.repeatMode != new.repeatMode) return true
        if (old.totalDurationMs != new.totalDurationMs) return true

        val drift = kotlin.math.abs(old.currentPositionMs - new.currentPositionMs)
        return drift > 3000L
    }

    private suspend fun processWidgetUpdateInternal() {
        // AUD-113: nothing to update, nothing to build. The state carries the cover as JPEG bytes, and
        // building it decoded, re-encoded and copied one for every track change with no widget to
        // receive it (`No widgets found to update` x52 in one session). The last state is dropped so
        // the update after a widget appears is a first update, not "unchanged".
        if (!hasInstalledWidgets()) {
            lastWidgetPlayerInfo = null
            return
        }
        val playerInfo = buildPlayerInfo()
        val oldInfo = lastWidgetPlayerInfo

        val shouldUpdateWidgets = oldInfo == null || shouldUpdateWidget(oldInfo, playerInfo)
        if (shouldUpdateWidgets) {
            lastWidgetPlayerInfo = playerInfo
            updateGlanceWidgets(playerInfo)
        }
    }

    /** Whether any of the widgets this service updates is on a home screen. Fails open. */
    private suspend fun hasInstalledWidgets(): Boolean = withContext(Dispatchers.IO) {
        try {
            val manager = GlanceAppWidgetManager(applicationContext)
            manager.getGlanceIds(PixelPlayerGlanceWidget::class.java).isNotEmpty() ||
                manager.getGlanceIds(BarWidget4x1::class.java).isNotEmpty() ||
                manager.getGlanceIds(ControlWidget4x2::class.java).isNotEmpty() ||
                manager.getGlanceIds(GridWidget2x2::class.java).isNotEmpty()
        } catch (e: Exception) {
            // Not being able to ask must not silence a widget that exists.
            Timber.tag(TAG).w(e, "Could not list the installed widgets; building the state anyway")
            true
        }
    }

    private suspend fun buildPlayerInfo(): PlayerInfo {
        val player = engine.masterPlayer
        var currentItem: MediaItem? = null
        var isPlaying = false
        var repeatMode = Player.REPEAT_MODE_OFF
        var currentPosition = 0L
        var totalDuration = 0L
        var snapshotWindowIndex = 0
        var snapshotTimeline: Timeline = Timeline.EMPTY
        var snapshotShuffle = false
        withContext(Dispatchers.Main) {
            currentItem = player.currentMediaItem
            isPlaying = player.isPlaying
            repeatMode = player.repeatMode
            currentPosition = player.currentPosition
            totalDuration = player.duration.coerceAtLeast(0)
            snapshotWindowIndex = player.currentMediaItemIndex
            snapshotTimeline = player.currentTimeline
            snapshotShuffle = player.shuffleModeEnabled
        }

        var shuffleEnabled = isManualShuffleEnabled

        var title = currentItem?.mediaMetadata?.title?.toString().orEmpty()
        var artist = currentItem?.mediaMetadata?.artist?.toString().orEmpty()
        var mediaId = currentItem?.mediaId
        var artworkUri = resolveWidgetArtworkUriCandidates(currentItem?.mediaMetadata).firstOrNull()
        var artworkData = currentItem?.mediaMetadata?.artworkData

        val artworkCandidates = resolveWidgetArtworkUriCandidates(
            metadata = currentItem?.mediaMetadata,
            preferredArtworkUri = artworkUri,
        )
        val (artBytes, artUriString) = getAlbumArtForWidget(
            mediaId = mediaId,
            embeddedArt = artworkData,
            artUris = artworkCandidates,
        )

        val (playerTheme, paletteStyle, colorAccuracyLevel) = withContext(Dispatchers.IO) {
            Triple(
                themePreferencesRepository.playerThemePreferenceFlow.first(),
                AlbumArtPaletteStyle.fromStorageKey(themePreferencesRepository.albumArtPaletteStyleFlow.first().storageKey),
                AlbumArtColorAccuracy.clamp(themePreferencesRepository.albumArtColorAccuracyFlow.first())
            )
        }

        val schemePair: ColorSchemePair? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && playerTheme == ThemePreference.DYNAMIC ->
                ColorSchemePair(
                    light = dynamicLightColorScheme(applicationContext),
                    dark = dynamicDarkColorScheme(applicationContext)
                )
            artUriString != null ->
                if (
                    artUriString == cachedSchemeArtUri &&
                    paletteStyle == cachedSchemePaletteStyle &&
                    colorAccuracyLevel == cachedSchemeColorAccuracy
                ) {
                    cachedColorSchemePair
                } else {
                    colorSchemeProcessor.getOrGenerateColorScheme(
                        albumArtUri = artUriString,
                        paletteStyle = paletteStyle,
                        colorAccuracyLevel = colorAccuracyLevel
                    ).also {
                        cachedSchemeArtUri = artUriString
                        cachedSchemePaletteStyle = paletteStyle
                        cachedSchemeColorAccuracy = colorAccuracyLevel
                        cachedColorSchemePair = it
                    }
                }
            else -> null
        }

        val widgetColors = schemePair?.let {
            WidgetThemeColors(
                lightSurfaceContainer = it.light.surfaceContainer.toArgb(),
                lightSurfaceContainerLowest = it.light.surfaceContainerLowest.toArgb(),
                lightSurfaceContainerLow = it.light.surfaceContainerLow.toArgb(),
                lightSurfaceContainerHigh = it.light.surfaceContainerHigh.toArgb(),
                lightSurfaceContainerHighest = it.light.surfaceContainerHighest.toArgb(),
                lightTitle = it.light.onSurface.toArgb(),
                lightArtist = it.light.onSurfaceVariant.toArgb(),
                lightPlayPauseBackground = it.light.primary.toArgb(),
                lightPlayPauseIcon = it.light.onPrimary.toArgb(),
                lightPrevNextBackground = it.light.onPrimary.toArgb(),
                lightPrevNextIcon = it.light.primary.toArgb(),
                
                darkSurfaceContainer = it.dark.surfaceContainer.toArgb(),
                darkSurfaceContainerLowest = it.dark.surfaceContainerLowest.toArgb(),
                darkSurfaceContainerLow = it.dark.surfaceContainerLow.toArgb(),
                darkSurfaceContainerHigh = it.dark.surfaceContainerHigh.toArgb(),
                darkSurfaceContainerHighest = it.dark.surfaceContainerHighest.toArgb(),
                darkTitle = it.dark.onSurface.toArgb(),
                darkArtist = it.dark.onSurfaceVariant.toArgb(),
                darkPlayPauseBackground = it.dark.primary.toArgb(),
                darkPlayPauseIcon = it.dark.onPrimary.toArgb(),
                darkPrevNextBackground = it.dark.onPrimary.toArgb(),
                darkPrevNextIcon = it.dark.primary.toArgb()
            )
        }
        val isFavorite = isSongFavorite(mediaId)
        val queueItems = mutableListOf<com.lostf1sh.pixelplayeross.data.model.QueueItem>()
        if (!snapshotTimeline.isEmpty) {
            val window = Timeline.Window()

            val startIndex = if (snapshotWindowIndex + 1 < snapshotTimeline.windowCount) snapshotWindowIndex + 1 else 0

            val endIndex = (startIndex + 4).coerceAtMost(snapshotTimeline.windowCount)
            // With shuffle on, "up next" is what the shuffle order visits next, not the neighbours in the queue (B17-H1).
            val upcoming = if (snapshotShuffle) {
                PlayOrder.upcoming(PlayOrder.of(snapshotTimeline, shuffleEnabled = true), snapshotWindowIndex, 4)
            } else {
                (startIndex until endIndex).toList()
            }
            for (i in upcoming) {
                snapshotTimeline.getWindow(i, window)
                val mediaItem = window.mediaItem
                // Every queued item, numeric or not (AUD-103): the tap carries the media id itself; the
                // numeric id stays for widgets laid out by older versions.
                val queueMediaId = mediaItem.mediaId
                val songId = queueMediaId.toLongOrNull() ?: queueMediaId.hashCode().toLong()
                if (queueMediaId.isNotBlank()) {
                    val initialQueueArtworkUri = resolveWidgetArtworkUriCandidates(mediaItem.mediaMetadata)
                        .firstOrNull()
                    val queueArtworkUri = when {
                        initialQueueArtworkUri == null -> resolveRepositoryArtworkUri(mediaItem.mediaId)
                        initialQueueArtworkUri.scheme?.lowercase() == "content" &&
                            initialQueueArtworkUri.authority == "$packageName.provider" ->
                            resolveRepositoryArtworkUri(mediaItem.mediaId) ?: initialQueueArtworkUri
                        else -> initialQueueArtworkUri
                    }
                    queueItems.add(
                        com.lostf1sh.pixelplayeross.data.model.QueueItem(
                            id = songId,
                            albumArtUri = queueArtworkUri?.toString(),
                            mediaId = queueMediaId,
                        )
                    )
                }
            }
        }

        return PlayerInfo(
            songTitle = title,
            artistName = artist,
            isPlaying = isPlaying,
            albumArtUri = artUriString,
            albumArtBitmapData = artBytes,
            currentPositionMs = currentPosition,
            totalDurationMs = totalDuration,
            isFavorite = isFavorite,
            queue = queueItems,
            themeColors = widgetColors,
            isShuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
        )
    }

    private var cachedSchemeArtUri: String? = null
    private var cachedSchemePaletteStyle: AlbumArtPaletteStyle? = null
    private var cachedSchemeColorAccuracy: Int = AlbumArtColorAccuracy.DEFAULT
    private var cachedColorSchemePair: ColorSchemePair? = null
    private var cachedWidgetArtSourceKey: String? = null
    private var cachedWidgetArtResolvedUri: String? = null
    private var cachedWidgetArtBytes: ByteArray? = null
    private var cachedWidgetArtLoadFailureKey: String? = null
    private var cachedWidgetArtLoadFailureAtMs: Long = 0L

    private suspend fun getAlbumArtForWidget(
        mediaId: String?,
        embeddedArt: ByteArray?,
        artUris: List<Uri>,
    ): Pair<ByteArray?, String?> = withContext(Dispatchers.IO) {
        val sanitizedFromEmbedded = embeddedArt?.takeIf { it.isNotEmpty() }?.let { bytes ->
            runCatching {
                ArtworkTransportSanitizer.sanitizeEncodedBytes(
                    data = bytes,
                    config = ArtworkTransportSanitizer.WIDGET_CONFIG,
                )
            }.getOrNull()
        }
        val candidateUriStrings = LinkedHashSet<String>().apply {
            artUris.forEach { candidate ->
                candidate.toString()
                    .takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }.toList()
        val preferredUriString = candidateUriStrings.firstOrNull()
        val sourceKey = buildWidgetArtworkSourceKey(
            mediaId = mediaId,
            candidateUriStrings = candidateUriStrings,
        )

        if (sanitizedFromEmbedded != null) {
            cachedWidgetArtSourceKey = sourceKey
            cachedWidgetArtResolvedUri = preferredUriString
            cachedWidgetArtBytes = sanitizedFromEmbedded
            cachedWidgetArtLoadFailureKey = null
            cachedWidgetArtLoadFailureAtMs = 0L
            return@withContext sanitizedFromEmbedded to preferredUriString
        }

        if (sourceKey != null && sourceKey == cachedWidgetArtSourceKey && cachedWidgetArtBytes != null) {
            return@withContext cachedWidgetArtBytes to (cachedWidgetArtResolvedUri ?: preferredUriString)
        }
        if (sourceKey != null && sourceKey == cachedWidgetArtLoadFailureKey) {
            val failureAgeMs = SystemClock.elapsedRealtime() - cachedWidgetArtLoadFailureAtMs
            if (failureAgeMs < WIDGET_ART_FAILURE_RETRY_MS) {
                return@withContext null to preferredUriString
            }
        }

        val repositoryArtUriString = if (mediaId.isNullOrBlank()) {
            null
        } else {
            resolveRepositoryArtworkUri(mediaId)?.toString()
        }
        val resolvedUriStrings = LinkedHashSet<String>().apply {
            addAll(candidateUriStrings)
            repositoryArtUriString
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }

        for (candidateUriString in resolvedUriStrings) {
            val candidateUri = parseArtworkUriString(candidateUriString) ?: continue
            val loadedBytes = loadArtworkBytesForWidget(candidateUri)
            if (loadedBytes != null) {
                cachedWidgetArtSourceKey = sourceKey
                cachedWidgetArtResolvedUri = candidateUriString
                cachedWidgetArtBytes = loadedBytes
                cachedWidgetArtLoadFailureKey = null
                cachedWidgetArtLoadFailureAtMs = 0L
                return@withContext loadedBytes to candidateUriString
            }
        }

        cachedWidgetArtLoadFailureKey = sourceKey
        cachedWidgetArtLoadFailureAtMs = SystemClock.elapsedRealtime()
        return@withContext null to (repositoryArtUriString ?: preferredUriString)
    }

    private fun resolveStoredArtworkUriString(metadata: MediaMetadata?): String? {
        metadata ?: return null
        return metadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM_ART)
            ?.takeIf { it.isNotBlank() }
            ?: metadata.artworkUri
                ?.toString()
                ?.takeIf { it.isNotBlank() }
    }

    private fun resolveWidgetArtworkUriCandidates(
        metadata: MediaMetadata?,
        preferredArtworkUri: Uri? = null,
    ): List<Uri> {
        val candidates = LinkedHashSet<String>()
        preferredArtworkUri
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let(candidates::add)
        resolveStoredArtworkUriString(metadata)?.let(candidates::add)
        metadata?.artworkUri
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let(candidates::add)
        return candidates.mapNotNull(::parseArtworkUriString)
    }

    private fun parseArtworkUriString(rawArtworkUri: String?): Uri? {
        if (rawArtworkUri.isNullOrBlank()) {
            return null
        }

        return MediaItemBuilder.artworkUri(rawArtworkUri)
            ?: if (rawArtworkUri.startsWith("/")) {
                Uri.fromFile(java.io.File(rawArtworkUri))
            } else {
                runCatching { rawArtworkUri.toUri() }.getOrNull()
            }
    }

    private fun buildWidgetArtworkSourceKey(
        mediaId: String?,
        candidateUriStrings: List<String>,
    ): String? {
        val normalizedMediaId = mediaId?.takeIf { it.isNotBlank() }
        if (normalizedMediaId == null && candidateUriStrings.isEmpty()) {
            return null
        }
        return buildString {
            normalizedMediaId?.let {
                append("mediaId=")
                append(it)
            }
            if (candidateUriStrings.isNotEmpty()) {
                if (isNotEmpty()) append('|')
                append(candidateUriStrings.joinToString(separator = ","))
            }
        }
    }

    private fun resolveArtworkUri(metadata: MediaMetadata?): Uri? {
        metadata ?: return null
        metadata.artworkUri?.let { return it }
        val extrasUri = metadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM_ART)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return parseArtworkUriString(extrasUri)
    }

    private suspend fun resolveRepositoryArtworkUri(mediaId: String?): Uri? {
        val songId = mediaId?.takeIf { it.isNotBlank() } ?: return null
        val song = withContext(Dispatchers.IO) {
            musicRepository.getSong(songId).first()
        } ?: return null

        return MediaItemBuilder.artworkUri(song.albumArtUriString)
            ?: song.albumArtUriString
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    if (raw.startsWith("/")) Uri.fromFile(java.io.File(raw))
                    else runCatching { Uri.parse(raw) }.getOrNull()
                }
    }

    public suspend fun loadArtworkBytesForWidget(uri: Uri): ByteArray? {
        val uriString = uri.toString()
        val scheme = uri.scheme?.lowercase()
        val isLocalArtworkUri = com.lostf1sh.pixelplayeross.utils.LocalArtworkUri.isLocalArtworkUri(uriString)
        return when {
            isLocalArtworkUri || scheme == "content" || scheme == "file" || scheme == "android.resource" -> {
                runCatching {
                    AlbumArtUtils.openArtworkInputStream(applicationContext, uri)?.use { input ->
                        readBytesCapped(input, ArtworkTransportSanitizer.WIDGET_CONFIG.sourceBytesLimit)
                            ?.let { bytes ->
                                ArtworkTransportSanitizer.sanitizeEncodedBytes(
                                    data = bytes,
                                    config = ArtworkTransportSanitizer.WIDGET_CONFIG,
                                )
                            }
                    }
                }.getOrElse { error ->
                    Timber.tag(TAG).w(error, "Widget artwork read failed for local uri=%s", uri)
                    null
                }
            }
            scheme == "http" || scheme == "https" -> {
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL(uriString).openConnection() as? HttpURLConnection)
                        ?: return null
                    connection.connectTimeout = 4_000
                    connection.readTimeout = 6_000
                    connection.instanceFollowRedirects = true
                    connection.doInput = true
                    connection.inputStream.use { input ->
                        readBytesCapped(input, ArtworkTransportSanitizer.WIDGET_CONFIG.sourceBytesLimit)
                            ?.let { bytes ->
                                ArtworkTransportSanitizer.sanitizeEncodedBytes(
                                    data = bytes,
                                    config = ArtworkTransportSanitizer.WIDGET_CONFIG,
                                )
                            }
                    }
                } catch (error: Exception) {
                    Timber.tag(TAG).w(error, "Widget artwork read failed for remote uri=%s", uri)
                    null
                } finally {
                    connection?.disconnect()
                }
            }
            else -> loadArtworkBytesViaCoil(applicationContext, uri)
        }
    }

    private fun readBytesCapped(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(DEFAULT_STREAM_BUFFER_SIZE * 4)
        val buffer = ByteArray(DEFAULT_STREAM_BUFFER_SIZE)
        var totalRead = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            totalRead += read
            if (totalRead > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    private suspend fun updateGlanceWidgets(playerInfo: PlayerInfo) = withContext(Dispatchers.IO) {
        try {
            val glanceManager = GlanceAppWidgetManager(applicationContext)
            val widgetPlayerInfo = playerInfo.toWidgetTransportState()

            val glanceIds = glanceManager.getGlanceIds(PixelPlayerGlanceWidget::class.java)
            glanceIds.forEach { id ->
                updateAppWidgetState(applicationContext, PlayerInfoStateDefinition, id) { widgetPlayerInfo }
                PixelPlayerGlanceWidget().update(applicationContext, id)
            }

            val barGlanceIds = glanceManager.getGlanceIds(BarWidget4x1::class.java)
            barGlanceIds.forEach { id ->
                updateAppWidgetState(applicationContext, PlayerInfoStateDefinition, id) { widgetPlayerInfo }
                BarWidget4x1().update(applicationContext, id)
            }

            val controlGlanceIds = glanceManager.getGlanceIds(ControlWidget4x2::class.java)
            controlGlanceIds.forEach { id ->
                updateAppWidgetState(applicationContext, PlayerInfoStateDefinition, id) { widgetPlayerInfo }
                ControlWidget4x2().update(applicationContext, id)
            }

            val gridGlanceIds = glanceManager.getGlanceIds(GridWidget2x2::class.java)
            gridGlanceIds.forEach { id ->
                updateAppWidgetState(applicationContext, PlayerInfoStateDefinition, id) { widgetPlayerInfo }
                GridWidget2x2().update(applicationContext, id)
            }
            
            if (glanceIds.isNotEmpty() || barGlanceIds.isNotEmpty() || controlGlanceIds.isNotEmpty() || gridGlanceIds.isNotEmpty()) {
                Timber.tag(TAG)
                    .d("Widgets updated: ${playerInfo.songTitle} (Original: ${glanceIds.size}, Bar: ${barGlanceIds.size}, Control: ${controlGlanceIds.size})")
            } else {
                Timber.tag(TAG).w("No widgets found to update")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error updating the widget")
        }
    }

    private fun PlayerInfo.toWidgetTransportState(): PlayerInfo {
        return copy(
            lyrics = null,
            isLoadingLyrics = false,
            queue = queue.take(WIDGET_QUEUE_PREVIEW_LIMIT),
        )
    }

    fun isSongFavorite(songId: String?): Boolean {
        return songId != null && favoriteSongIds.contains(songId)
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val hasPlaybackIntent = session.player.hasForegroundPlaybackIntent()

        val shouldStartInForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startInForegroundRequired || hasPlaybackIntent
        } else {
            startInForegroundRequired
        }

        try {
            super.onUpdateNotification(session, shouldStartInForeground)
            if (session.player.mediaItemCount > 0) {
                clearTemporaryForegroundNotification()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "onUpdateNotification suppressed: ${e.message}")
        }
    }

    override fun startForegroundService(serviceIntent: Intent?): ComponentName? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return try {
                super.startForegroundService(serviceIntent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Timber.tag(TAG).w(
                    e,
                    "startForegroundService not allowed; ignoring redundant self-start request"
                )
                serviceIntent?.component ?: ComponentName(this, javaClass)
            } catch (e: BackgroundServiceStartNotAllowedException) {
                Timber.tag(TAG).w(
                    e,
                    "startForegroundService blocked (app in background); ignoring self-start request"
                )
                serviceIntent?.component ?: ComponentName(this, javaClass)
            }
        }
        return super.startForegroundService(serviceIntent)
    }

    private fun refreshMediaSessionUi(session: MediaSession, force: Boolean = false) {
        val pendingSignature = buildMediaButtonPreferencesSignature(session)
        if (!force && pendingSignature == lastAppliedMediaButtonSignature) {
            return
        }

        mediaSessionButtonRefreshJob?.cancel()
        mediaSessionButtonRefreshJob = serviceScope.launch {
            if (!force) {
                delay(MEDIA_SESSION_BUTTON_DEBOUNCE_MS)
            }
            if (mediaSession !== session) {
                return@launch
            }

            val latestSignature = buildMediaButtonPreferencesSignature(session)
            if (latestSignature == lastAppliedMediaButtonSignature) {
                return@launch
            }

            val buttons = buildMediaButtonPreferences(session)
            session.setMediaButtonPreferences(buttons)
            lastAppliedMediaButtonSignature = latestSignature
        }
    }

    private fun closeNotificationPlayer() {
        stopPlaybackAndUnload(
            reason = "notification_close_button",
            preservePlaybackSnapshot = false
        )
    }

    private fun stopPlaybackAndUnload(
        reason: String,
        preservePlaybackSnapshot: Boolean = true,
    ) {
        Timber.tag(TAG).d(
            "Stopping playback and unloading service. reason=%s",
            reason
        )
        isPlaybackUnloadInProgress = true
        followUpMediaSessionUiRefreshJob?.cancel()
        mediaSessionButtonRefreshJob?.cancel()
        debouncedWidgetUpdateJob?.cancel()
        playbackSnapshotPersistJob?.cancel()

        val sessionToRelease = mediaSession
        val player = sessionToRelease?.player ?: engine.masterPlayer

        clearHeadsetReconnectResume()
        playbackTimerController.release()

        if (preservePlaybackSnapshot) {
            persistPlaybackSnapshotOnUnload()
        } else {
            clearPlaybackSnapshotOnUnload()
        }

        listeningStatsTracker.finalizeCurrentSession(
            endReason = TrackEndReason.INTERRUPTED,
            forceSynchronousPersistence = true
        )

        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()

        requestWidgetFullUpdate(force = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        clearTemporaryForegroundNotification()

        stopSelf()
    }

    private fun persistPlaybackSnapshotOnUnload() {
        val snapshot = capturePlaybackSnapshotFromPlayer(playWhenReadyOverride = false)
        writePlaybackSnapshotOnUnload(snapshot)
    }

    private fun clearPlaybackSnapshotOnUnload() {
        writePlaybackSnapshotOnUnload(null)
    }

    private fun writePlaybackSnapshotOnUnload(snapshot: PlaybackQueueSnapshot?) {
        playbackSnapshotUnloadWriteJob?.cancel()
        playbackSnapshotUnloadWriteJob = appScope.launch {
            runCatching {
                userPreferencesRepository.setPlaybackQueueSnapshot(snapshot)
            }.onFailure { e ->
                Timber.tag(TAG).w(e, "Failed to persist playback snapshot during unload")
            }
        }
    }

    /**
     * A debounced snapshot persist that is still pending at [onDestroy] would be
     * silently cancelled, leaving a stale "playing" snapshot behind — reopening the
     * app would then fake-resume playback that the system already tore down. Flush
     * it now, forced to a paused state, so the next restore comes back paused at
     * the correct position.
     */
    private fun flushPendingPlaybackSnapshotOnDestroy() {
        if (isPlaybackUnloadInProgress || isRestoringPlaybackSnapshot) return
        if (playbackSnapshotPersistJob?.isActive != true) return
        playbackSnapshotPersistJob?.cancel()
        val snapshot = capturePlaybackSnapshotFromPlayer(playWhenReadyOverride = false)
            ?: return
        writePlaybackSnapshotOnUnload(snapshot)
    }

    private fun refreshMediaSessionUiWithFollowUp(
        session: MediaSession,
        delayMs: Long = 250L
    ) {
        refreshMediaSessionUi(session, force = true)
        followUpMediaSessionUiRefreshJob?.cancel()
        followUpMediaSessionUiRefreshJob = serviceScope.launch {
            delay(delayMs)
            if (mediaSession === session) {
                refreshMediaSessionUi(session)
            }
        }
    }

    /**
     * Shuffle on or off, from whoever asked: the notification, the widget, the app, a head unit (B17-H1).
     *
     * The engine flips the player's flag and nothing else: no media item moves, nothing is republished, the song that is
     * playing carries on. The old version of this told the app to rebuild its queue (`SET_SHUFFLE_STATE` broadcast, then a
     * physical reorder of the whole timeline in the app); the app now just hears the player's own
     * `onShuffleModeEnabledChanged`, as any controller does. The service's copy and the stored preference follow the player
     * in [playerListener].
     */
    private fun updateManualShuffleState(
        session: MediaSession,
        enabled: Boolean,
    ) {
        engine.setShuffleEnabled(enabled)
        isManualShuffleEnabled = enabled
        refreshMediaSessionUi(session)
        requestWidgetFullUpdate(force = true)
    }

    private fun setCurrentSongFavoriteState(
        session: MediaSession,
        targetFavoriteState: Boolean
    ): ListenableFuture<SessionResult> {
        val songId = session.player.currentMediaItem?.mediaId
            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))

        val isCurrentlyFavorite = favoriteSongIds.contains(songId)
        if (isCurrentlyFavorite == targetFavoriteState) {
            refreshMediaSessionUi(session)
            requestWidgetFullUpdate(force = true)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        favoriteSongIds = if (targetFavoriteState) {
            favoriteSongIds + songId
        } else {
            favoriteSongIds - songId
        }

        refreshMediaSessionUi(session)
        requestWidgetFullUpdate(force = true)

        serviceScope.launch {
            Timber.tag("MusicService")
                .d("Applying favorite=$targetFavoriteState for songId: $songId")
            musicRepository.setFavoriteStatus(songId, targetFavoriteState)
            refreshMediaSessionUi(session)
            requestWidgetFullUpdate(force = true)
        }

        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private suspend fun resolveMediaItemsByIds(
        requestedItems: List<MediaItem>,
        exposeInternalArtwork: Boolean = false,
    ): TrustedMediaItemsResolution {
        val songIds = requestedItems.map { it.mediaId }
        val songs = musicRepository.getSongsByIds(songIds).first()
        val songMap = songs.associateBy { it.id }

        return resolveMediaItemsWithTrustedArtworkGrants(requestedItems) { mediaId ->
            songMap[mediaId]?.let { song ->
                if (exposeInternalArtwork) {
                    MediaItemBuilder.build(song)
                } else {
                    MediaItemBuilder.buildForExternalController(this, song)
                }
            }
        }
    }

    /**
     * Custom session commands mutate app state, so they are limited to our own controllers
     * (in-app UI, media notification) and controllers the system marks as trusted
     * (System UI and trusted notification listeners).
     */
    private fun isPrivilegedController(controller: MediaSession.ControllerInfo): Boolean {
        return controller.packageName == packageName || controller.isTrusted
    }

    /**
     * The artwork provider is not exported, so controllers connected before the current item
     * changed need a fresh grant for each new current item.
     */
    private fun grantArtworkUriPermissionsToConnectedControllers(mediaItem: MediaItem) {
        val session = mediaSession ?: return
        val targets = buildSet {
            session.connectedControllers
                .mapTo(this) { it.packageName }
            systemUiArtworkGrantTarget()?.let(::add)
        }
        targets.asSequence()
            .distinct()
            .filter { it.isNotBlank() && it != packageName }
            .forEach { grantArtworkUriPermissions(it, listOf(mediaItem)) }
    }

    /**
     * Media3 can report the framework controller as `android` even though SystemUI performs the
     * later artwork read. Grant only the installed system-signed UI package a per-item read URI;
     * the provider itself remains non-exported and no broad read permission is introduced.
     */
    @Suppress("DEPRECATION")
    private fun systemUiArtworkGrantTarget(): String? {
        // B23-P4: the system UI package is the same for the life of the process; the PackageManager lookup ran on every song.
        if (systemUiTargetResolved) return systemUiTarget
        val resolved = runCatching {
            packageManager.getApplicationInfo(SYSTEM_UI_PACKAGE, 0)
        }.getOrNull()?.takeIf { info ->
            info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        }?.packageName
        systemUiTarget = resolved
        systemUiTargetResolved = true
        return resolved
    }

    private var systemUiTargetResolved = false
    private var systemUiTarget: String? = null

    /** B23-P4: what has been granted to whom, so a song change only grants what is new. */
    private val artworkGrants = ArtworkGrantLedger()

    private fun grantArtworkUriPermissions(
        targetPackage: String,
        mediaItems: List<MediaItem>
    ) {
        if (targetPackage.isBlank()) return

        val providerAuthority = "$packageName.provider"
        val artworkAuthority = "$packageName.artwork"
        mediaItems.forEach { mediaItem ->
            val storedArtworkUri = resolveStoredArtworkUriString(mediaItem.mediaMetadata)
            val artworkUri = MediaItemBuilder.externalControllerArtworkUri(
                context = this,
                rawArtworkUri = storedArtworkUri
            ) ?: resolveArtworkUri(mediaItem.mediaMetadata) ?: return@forEach
            val authority = artworkUri.authority
            if (artworkUri.scheme?.lowercase() != "content" ||
                (authority != providerAuthority && authority != artworkAuthority)
            ) {
                return@forEach
            }

            val uriString = artworkUri.toString()
            if (!artworkGrants.needsGrant(targetPackage, uriString)) return@forEach
            runCatching {
                grantUriPermission(targetPackage, artworkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onSuccess {
                artworkGrants.recordGranted(targetPackage, uriString)
            }.onFailure { error ->
                Timber.tag(TAG).w(
                    error,
                    "Failed to grant artwork URI permission to package=%s uri=%s",
                    targetPackage,
                    artworkUri
                )
            }
        }
    }

    private fun buildMediaButtonPreferencesSignature(session: MediaSession): String {
        val player = session.player
        return buildString {
            append(player.currentMediaItem?.mediaId.orEmpty())
            append('|')
            append(isSongFavorite(player.currentMediaItem?.mediaId))
            append('|')
            append(isManualShuffleEnabled)
            append('|')
            append(player.repeatMode)
        }
    }

    private fun buildMediaButtonPreferences(session: MediaSession): List<CommandButton> {
        val player = session.player
        val songId = player.currentMediaItem?.mediaId
        val isFavorite = isSongFavorite(songId)
        val likeButton = CommandButton.Builder(
            if (isFavorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        )
            .setDisplayName("Like")
            .setSessionCommand(SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_LIKE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        val shuffleOn = isManualShuffleEnabled
        val shuffleCommandAction = if (shuffleOn) {
            MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_OFF
        } else {
            MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_ON
        }
        val shuffleButton = CommandButton.Builder(
            if (shuffleOn) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        )
            .setDisplayName("Shuffle")
            .setSessionCommand(SessionCommand(shuffleCommandAction, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        val repeatButton = CommandButton.Builder(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            }
        )
            .setDisplayName("Repeat")
            .setSessionCommand(SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_CYCLE_REPEAT_MODE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        val closeButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.rounded_close_24)
            .setDisplayName(getString(R.string.close_notification_player))
            .setSessionCommand(SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_CLOSE_PLAYER, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        return listOf(likeButton, closeButton, shuffleButton, repeatButton)
    }

    /**
     * Bridges a suspend block into a [ListenableFuture] for Media3 callback methods.
     */
    private fun <T> CoroutineScope.future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        launch(Dispatchers.IO) {
            try {
                future.set(block())
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }
}
