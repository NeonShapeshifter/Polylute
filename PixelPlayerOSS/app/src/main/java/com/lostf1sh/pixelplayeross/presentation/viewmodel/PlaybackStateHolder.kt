package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.service.player.PlayPauseDecision
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.common.Player
import androidx.media3.common.C
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.di.MainAppScope
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import timber.log.Timber
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import kotlin.math.abs

@Singleton
class PlaybackStateHolder @Inject constructor(
    private val dualPlayerEngine: DualPlayerEngine,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val queueStateHolder: QueueStateHolder,
    @param:ApplicationContext private val appContext: Context,
    /**
     * Process-lifetime scope, injected rather than borrowed from a ViewModel.
     *
     * The sampling loop, the shuffle job and the preference writes all outlive any
     * single screen, so none of them may depend on a ViewModel's lifetime. The scope is
     * provided at application scope (see AppModule) and is a test seam in its own
     * right: a JVM test can install a TestScope and keep deterministic virtual time.
     */
    @MainAppScope private val appScope: CoroutineScope,
    /**
     * Injected rather than a literal [Dispatchers], so a JVM test can drive the holder on its own
     * scheduler and keep virtual time deterministic.
     */
    private val dispatchers: DispatcherProvider
) {
    companion object {
        private const val TAG = "PlaybackStateHolder"
        private const val DURATION_MISMATCH_TOLERANCE_MS = 1500L
        /** Media3's default `maxSeekToPreviousPosition`; the single "restart or previous" threshold. */
        private const val PAUSED_OVERRIDE_MAX_AGE_MS = 4_000L

        /** After [PlayPauseCommandGuard.HOLD_MS], so the read cannot land a clock tick inside the window. */
        private const val PLAY_PAUSE_RESYNC_MARGIN_MS = 50L

        /** The app's own writes to the state (see [updateStablePlayerState]). */
        private const val SOURCE_STATE = "state"

        /** A source with this prefix is the controller telling us the player's state (B12a-7). */
        const val SOURCE_CONTROLLER_PREFIX = "controller."
    }


    var mediaController: MediaController? = null
        private set
    private val mediaControllerStack = mutableListOf<MediaController>()

    private val _stablePlayerState = MutableStateFlow(StablePlayerState())
    val stablePlayerState: StateFlow<StablePlayerState> = _stablePlayerState.asStateFlow()
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _sliderUiMounted = MutableStateFlow(false)
    fun setSliderUiMounted(mounted: Boolean) {
        _sliderUiMounted.value = mounted
    }

    private var isSeeking = false
    private var activePositionOccurrenceMediaId: String? = null
    private var activePositionOccurrenceToken: Long = 0L
    private var nextPositionOccurrenceToken: Long = 1L
    private var pausedPositionOverrideMediaId: String? = null
    private var pausedPositionOverrideToken: Long? = null
    private var pausedPositionOverrideMs: Long? = null
    private var pausedPositionOverrideSetAtMs: Long = 0L
    private var coldStartSnapshotMediaId: String? = null
    private var coldStartSnapshotToken: Long? = null
    private var coldStartSnapshotPositionMs: Long? = null
    private var coldStartSnapshotSetAtMs: Long = 0L
    private val powerManager: PowerManager by lazy(LazyThreadSafetyMode.NONE) {
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private fun clearColdStartSnapshot() {
        coldStartSnapshotMediaId = null
        coldStartSnapshotToken = null
        coldStartSnapshotPositionMs = null
        coldStartSnapshotSetAtMs = 0L
    }

    /**
     * Binds a restored snapshot to the active playback occurrence when possible.
     *
     * If the first occurrence was already activated before the snapshot finished loading, we
     * attach the snapshot to that token so resume still works. If playback has already advanced
     * past the first occurrence, the snapshot is stale and must be discarded.
     */
    private fun rememberColdStartSnapshot(mediaId: String, positionMs: Long): Boolean {
        coldStartSnapshotMediaId = mediaId
        coldStartSnapshotToken = null
        coldStartSnapshotPositionMs = positionMs
        coldStartSnapshotSetAtMs = SystemClock.elapsedRealtime()

        if (nextPositionOccurrenceToken == 1L) {
            return true
        }

        if (
            activePositionOccurrenceToken == 1L &&
            nextPositionOccurrenceToken == 2L &&
            activePositionOccurrenceMediaId == mediaId
        ) {
            coldStartSnapshotToken = activePositionOccurrenceToken
            coldStartSnapshotSetAtMs = SystemClock.elapsedRealtime()
            return true
        }

        clearColdStartSnapshot()
        return false
    }

    fun initialize() {
        appScope.launch {
            val snapshot = runCatching {
                userPreferencesRepository.getPlaybackQueueSnapshotOnce()
            }.getOrNull() ?: return@launch

            val snapshotMediaId = snapshot.currentMediaId
                ?: snapshot.items.getOrNull(snapshot.currentIndex)?.mediaId
                ?: return@launch
            val snapshotPositionMs = snapshot.currentPositionMs.coerceAtLeast(0L)
            if (snapshotPositionMs <= 0L) return@launch
            if (!rememberColdStartSnapshot(snapshotMediaId, snapshotPositionMs)) {
                return@launch
            }

            val controller = mediaController
            if (
                controller != null &&
                !controller.isPlaying &&
                controller.currentMediaItem?.mediaId == snapshotMediaId &&
                _currentPosition.value == 0L
            ) {
                _currentPosition.value = snapshotPositionMs
            }
        }
    }

    fun setMediaController(controller: MediaController?) {
        if (controller == null) {
            mediaControllerStack.clear()
            mediaController = null
            return
        }

        mediaControllerStack.removeAll { it === controller }
        mediaControllerStack.add(controller)
        mediaController = controller
    }

    fun clearMediaController(controller: MediaController?) {
        if (controller == null) return

        mediaControllerStack.removeAll { it === controller }
        if (mediaController === controller) {
            mediaController = mediaControllerStack.lastOrNull()
        }
    }

    private fun guardPlaybackFlags(source: String, current: StablePlayerState, updated: StablePlayerState): StablePlayerState {
        if (updated.isPlaying == current.isPlaying && updated.playWhenReady == current.playWhenReady) return updated
        if (!source.startsWith(SOURCE_CONTROLLER_PREFIX)) {
            playPauseGuard.clear()
            return updated
        }
        val now = guardClockMs()
        var result = updated
        if (updated.isPlaying != current.isPlaying) {
            val r = playPauseGuard.resolvePlaying(current.isPlaying, updated.isPlaying, now)
            noteGuardOutcome(source, "isPlaying", updated.isPlaying, r)
            if (r.held) result = result.copy(isPlaying = r.value)
        }
        if (updated.playWhenReady != current.playWhenReady) {
            val r = playPauseGuard.resolveIntent(current.playWhenReady, updated.playWhenReady, now)
            noteGuardOutcome(source, "playWhenReady", updated.playWhenReady, r)
            if (r.held) result = result.copy(playWhenReady = r.value)
        }
        return result
    }

    private fun noteGuardOutcome(source: String, field: String, reported: Boolean, r: PlayPauseCommandGuard.Resolution) {
        if (r.held) {
            Timber.tag(TAG).i(
                "PlayPause: held a stale %s=%s from %s %d ms after the tap (the user asked for playing=%s)",
                field, reported, source, r.ageMs, r.target,
            )
        } else if (r.expired) {
            Timber.tag(TAG).i(
                "PlayPause: %s=%s from %s %d ms after the tap contradicts it and is shown: the player really did change",
                field, reported, source, r.ageMs,
            )
        }
    }

    private fun activeLocalPlayer(): Player {
        val controller = mediaController
        return if (controller?.isConnected == true) {
            controller
        } else {
            dualPlayerEngine.masterPlayer
        }
    }
    
    /**
     * B12a-7: the user's play/pause taps and the controller reports they race with; see [PlayPauseCommandGuard].
     * `internal` so a test can give it a clock.
     */
    internal val playPauseGuard = PlayPauseCommandGuard()
    internal var guardClockMs: () -> Long = { SystemClock.elapsedRealtime() }

    private val userCommands = java.util.concurrent.atomic.AtomicInteger()

    /**
     * How many play/pause commands the user (or another controller) has given since the process started (B12a-8 #5). What a
     * pending automatic `play()` compares to tell "the player stopped" from "the user stopped it": the engine's refusal
     * of audio focus pauses the player in the same way, so the player's state cannot say.
     */
    val userPlaybackCommandCount: Int
        get() = userCommands.get() + com.lostf1sh.pixelplayeross.data.service.ExternalPlaybackCommands.total

    /** A pause from outside this screen — the notification, a headset button — counts as the user's command. */
    fun noteExternalPause() {
        userCommands.incrementAndGet()
    }

    /**
     * The user tapped play/pause: [playbackWanted] is what they asked for. A pause is shown at once (the button answers
     * the finger, whatever the session takes to do it), and for [PlayPauseCommandGuard.HOLD_MS] the controller's echoes
     * of the state from before the tap cannot put the old glyph back. Call it before handing the command to the player.
     */
    fun noteUserPlayPause(playbackWanted: Boolean) {
        userCommands.incrementAndGet()
        if (!playbackWanted) {
            updateStablePlayerState("user.pause") { it.copy(isPlaying = false, playWhenReady = false) }
        }
        playPauseGuard.onUserCommand(playbackWanted, guardClockMs())
        schedulePlayPauseResync()
    }

    private var playPauseResyncJob: Job? = null

    /**
     * What the player really says right now: `isPlaying` and `playWhenReady`, or null with nothing to ask. Read
     * once, when a play/pause hold window has ended. `internal` so a test can stand in for the player.
     */
    internal var realPlaybackState: () -> Pair<Boolean, Boolean>? = {
        runCatching { activeLocalPlayer().let { it.isPlaying to it.playWhenReady } }.getOrNull()
    }

    /**
     * B13-6: when the hold window of a tap is over, believe the player again **even if no event arrives**.
     *
     * [PlayPauseCommandGuard] holds back, for [PlayPauseCommandGuard.HOLD_MS], a report that contradicts the tap. If
     * another controller really did resume inside that window (the notification, a Bluetooth headset), its report
     * was held, and nothing else was ever going to say "playing" again: the button stayed on "paused" while the music
     * played. The guard only decides about reports that arrive; this is the read that does not wait for one.
     * A later tap replaces the pending read: it starts its own window.
     */
    private fun schedulePlayPauseResync() {
        playPauseResyncJob?.cancel()
        playPauseResyncJob = appScope.launch {
            delay(PlayPauseCommandGuard.HOLD_MS + PLAY_PAUSE_RESYNC_MARGIN_MS)
            resyncPlayPauseFromPlayer()
        }
    }

    private fun resyncPlayPauseFromPlayer() {
        val real = realPlaybackState() ?: return
        // The window is over by definition; whatever the guard still holds is no longer an echo of anything.
        playPauseGuard.clear()
        val shown = _stablePlayerState.value
        if (shown.isPlaying == real.first && shown.playWhenReady == real.second) return
        Timber.tag(TAG).i(
            "PlayPause: window over, shown isPlaying=%s playWhenReady=%s, player says %s/%s: showing the player",
            shown.isPlaying, shown.playWhenReady, real.first, real.second
        )
        updateStablePlayerState("controller.resync") {
            it.copy(isPlaying = real.first, playWhenReady = real.second)
        }
    }

    /** A write that is not a controller report (see [updateStablePlayerState]); it supersedes a pending command. */
    fun updateStablePlayerState(update: (StablePlayerState) -> StablePlayerState) =
        updateStablePlayerState(SOURCE_STATE, update)

    /**
     * [source] says who is writing. A source starting with `controller.` is a **report** of the player's state and
     * is held back if it contradicts a play/pause the user just asked for ([PlayPauseCommandGuard]); the held write is
     * logged with the source. Any other source is the app's own decision (a new queue, the external VIEW, a reset): it
     * is applied as it is and ends the pending command.
     */
    fun updateStablePlayerState(source: String, update: (StablePlayerState) -> StablePlayerState) {
        _stablePlayerState.update { current ->
            val updated = guardPlaybackFlags(source, current, update(current))
            if (updated.currentMediaItemIndex == -1) {
                mediaController?.let { controller ->
                    updated.copy(currentMediaItemIndex = queuePositionOf(controller))
                } ?: if (dualPlayerEngine.isUsingWindowedQueue()) {
                    updated.copy(currentMediaItemIndex = dualPlayerEngine.getCurrentAbsoluteIndex())
                } else {
                    updated
                }
            } else {
                updated
            }
        }
    }

    fun setCurrentPosition(positionMs: Long) {
        _currentPosition.value = positionMs.coerceAtLeast(0L)
    }

    fun syncCurrentPositionFromPlayer(mediaId: String?, reportedPositionMs: Long) {
        _currentPosition.value = resolveUiPosition(mediaId, reportedPositionMs)
    }

    fun ensureCurrentPlaybackOccurrence(mediaId: String?) {
        activatePlaybackOccurrence(mediaId, forceNewOccurrence = false)
    }

    fun onPlaybackOccurrenceTransition(mediaId: String?) {
        activatePlaybackOccurrence(mediaId, forceNewOccurrence = true)
    }

    fun rememberPausedPositionOverride(mediaId: String?, positionMs: Long) {
        val safeMediaId = mediaId?.takeIf { it.isNotBlank() } ?: return
        val activeToken = activatePlaybackOccurrence(safeMediaId, forceNewOccurrence = false) ?: return
        val safePosition = positionMs.coerceAtLeast(0L)
        pausedPositionOverrideMediaId = safeMediaId
        pausedPositionOverrideToken = activeToken
        pausedPositionOverrideMs = safePosition
        pausedPositionOverrideSetAtMs = SystemClock.elapsedRealtime()
        _currentPosition.value = safePosition
    }

    fun clearCurrentPositionHints(mediaId: String? = null) {
        if (mediaId == null || pausedPositionOverrideMediaId == mediaId) {
            pausedPositionOverrideMediaId = null
            pausedPositionOverrideToken = null
            pausedPositionOverrideMs = null
            pausedPositionOverrideSetAtMs = 0L
        }
        if (mediaId == null || coldStartSnapshotMediaId == mediaId) {
            clearColdStartSnapshot()
        }
    }

    private fun resolveUiPosition(mediaId: String?, reportedPositionMs: Long): Long {
        val safeReportedPosition = reportedPositionMs.coerceAtLeast(0L)
        val safeMediaId = mediaId?.takeIf { it.isNotBlank() }
        if (safeMediaId == null) {
            return safeReportedPosition
        }

        val activeToken = activatePlaybackOccurrence(safeMediaId, forceNewOccurrence = false)
            ?: return safeReportedPosition

        val pausedOverride = pausedPositionOverrideMs
            ?.takeIf {
                pausedPositionOverrideMediaId == safeMediaId &&
                    pausedPositionOverrideToken == activeToken
            }
        val coldStartSeed = coldStartSnapshotPositionMs
            ?.takeIf {
                coldStartSnapshotMediaId == safeMediaId &&
                    coldStartSnapshotToken == activeToken
            }
        val preferredPosition = pausedOverride ?: coldStartSeed

        if (preferredPosition == null) {
            return safeReportedPosition
        }

        if (safeReportedPosition <= 0L) {
            return preferredPosition
        }

        val drift = abs(safeReportedPosition - preferredPosition)
        val pausedOverrideOwnsThisToken =
            pausedPositionOverrideMediaId == safeMediaId &&
                pausedPositionOverrideToken == activeToken
        val pausedOverrideActive = pausedOverride != null
        val overrideIsStale = pausedOverrideActive &&
            pausedPositionOverrideSetAtMs > 0L &&
            SystemClock.elapsedRealtime() - pausedPositionOverrideSetAtMs > PAUSED_OVERRIDE_MAX_AGE_MS
        val coldStartPassed = !pausedOverrideActive && safeReportedPosition >= preferredPosition
        // The cold-start seed is a restore hint, not an authority. Unlike the paused override it
        // used to have no expiry at all, so a player that reported 0 (or stayed behind the seed)
        // would have the seed re-written every tick forever — a frozen display with a perfectly
        // healthy sampling loop. It now ages out on the same clock the override does.
        val coldStartIsStale = coldStartSeed != null &&
            coldStartSnapshotSetAtMs > 0L &&
            SystemClock.elapsedRealtime() - coldStartSnapshotSetAtMs > PAUSED_OVERRIDE_MAX_AGE_MS
        if (drift <= DURATION_MISMATCH_TOLERANCE_MS || overrideIsStale || coldStartPassed || coldStartIsStale) {
            if (pausedOverrideOwnsThisToken) {
                pausedPositionOverrideMediaId = null
                pausedPositionOverrideToken = null
                pausedPositionOverrideMs = null
                pausedPositionOverrideSetAtMs = 0L
            }
            if (coldStartSnapshotMediaId == safeMediaId && coldStartSnapshotToken == activeToken) {
                clearColdStartSnapshot()
            }
            return safeReportedPosition
        }

        return preferredPosition
    }

    private fun activatePlaybackOccurrence(
        mediaId: String?,
        forceNewOccurrence: Boolean
    ): Long? {
        val safeMediaId = mediaId?.takeIf { it.isNotBlank() } ?: run {
            activePositionOccurrenceMediaId = null
            activePositionOccurrenceToken = 0L
            if (forceNewOccurrence) {
                pausedPositionOverrideMediaId = null
                pausedPositionOverrideToken = null
                pausedPositionOverrideMs = null
                pausedPositionOverrideSetAtMs = 0L
            }
            return null
        }

        val shouldAdvance =
            forceNewOccurrence ||
                activePositionOccurrenceToken == 0L ||
                activePositionOccurrenceMediaId != safeMediaId

        if (!shouldAdvance) {
            return activePositionOccurrenceToken
        }

        activePositionOccurrenceMediaId = safeMediaId
        activePositionOccurrenceToken = nextPositionOccurrenceToken++

        pausedPositionOverrideMediaId = null
        pausedPositionOverrideToken = null
        pausedPositionOverrideMs = null
        pausedPositionOverrideSetAtMs = 0L

        if (coldStartSnapshotToken != null) {
            clearColdStartSnapshot()
        } else if (coldStartSnapshotMediaId == safeMediaId && coldStartSnapshotPositionMs != null) {
            coldStartSnapshotToken = activePositionOccurrenceToken
            coldStartSnapshotSetAtMs = SystemClock.elapsedRealtime()
        } else if (coldStartSnapshotMediaId != null) {
            clearColdStartSnapshot()
        }

        return activePositionOccurrenceToken
    }
    
    fun playPause() {
        val controller = activeLocalPlayer()
        dualPlayerEngine.notePlayerOperation("BEGIN", "PLAY_PAUSE")
        // Intent, not `isPlaying` (false while buffering), and the same rule as the widget (AUD-066/067).
        PlayPauseDecision.apply(controller)
        dualPlayerEngine.notePlayerOperation("END", "PLAY_PAUSE")
    }

    /**
     * Declares the media position at which the next item start will begin, in milliseconds.
     *
     * `0` is START-FRESH. A resume must pass its real position, because the native Direct path is
     * started from this number while Media3 is started from the one given to `setMediaItems`.
     *
     * @see DualPlayerEngine.declareItemStartTarget
     */
    fun declareItemStartTarget(positionMs: Long) {
        dualPlayerEngine.declareItemStartTarget(positionMs)
    }

    fun seekTo(position: Long) {
        val targetPosition = position.coerceAtLeast(0L)
        val player = activeLocalPlayer()
        val currentMediaId = player.currentMediaItem?.mediaId
        rememberPausedPositionOverride(currentMediaId, targetPosition)
        // Hand over the target itself, not a hint that a seek happened. `targetPosition` is the
        // sanitised media-item position the user actually asked for, and it is the last point at
        // which that value exists — past here Media3 keeps it internal and the Direct path would
        // have to guess it back from renderer-domain state.
        dualPlayerEngine.notifyExternalSeekInitiated(targetPosition)
        dualPlayerEngine.notePlayerOperation("BEGIN", "SEEK_TO")
        player.seekTo(targetPosition)
        dualPlayerEngine.notePlayerOperation("END", "SEEK_TO")
    }

    /**
     * One threshold, one position, one explicit action (AUD-116). `MediaController.seekToPrevious()` masked the result with the
     * controller's own position — ~2 s behind the session — so around 3 s it showed the previous track while the session restarted
     * the current one. The decision uses the engine's position (same process, no IPC lag), and the action is explicit either way.
     *
     * B29-fix1 F2: **one authority for the position, the neighbour and the command.** The position came from the engine and the
     * neighbour from the controller, so a controller still on the first song while the engine was 1.2 s into the second read "no
     * previous" and restarted. The neighbour now comes from the same player as the position ([previousAuthority]). And the command
     * respects what Media3's controller does with it: `seekToPreviousMediaItem()` is **dropped on the spot** when the controller's
     * own cached commands do not include it (they come from the state it last heard, with no previous item), so a lagging controller
     * is not handed it. It gets `seekToPrevious()` instead, which sends the session the whole decision: the session's player takes
     * Media3's rule with its own position and its own neighbour (`MappingPlayer.seekToPrevious`), and that player is the engine's.
     * A restart needs no neighbour and goes out as before (`seekTo` carries a position, not an index, so it lands on the session's
     * current item whatever the controller thinks it is).
     */
    fun previousSong() {
        val controller = activeLocalPlayer()
        val authority = previousAuthority(controller)
        val positionMs = authority.currentPosition.coerceAtLeast(0L)
        // While a queue is still loading, "previous" early in a song means the previous song even if it is not in the player yet:
        // the session's player holds the skip until it is (B18-R2), and restarts the song if there turns out to be none. Once
        // every song that was to go in front is attached, the player knows (B19-S3): nothing before is a restart, at once.
        val loadingHoldsSkip = dualPlayerEngine.queueLoadHold.previousMayStillHaveSomewhereToGo
        if (PreviousTrackPolicy.restartsCurrent(positionMs, authority.hasPreviousMediaItem() || loadingHoldsSkip)) {
            // Route the restart through seekTo() so the position override, the engine seek hint and
            // the UI position are updated together; a bare controller.seekTo(0) left a stale
            // override behind and the displayed position could lag the restart.
            seekTo(0L)
        } else if (authority !== controller && !controllerIsInStepWith(authority, controller)) {
            controller.seekToPrevious()
        } else {
            controller.seekToPreviousMediaItem()
        }
    }

    /** The position "previous" decides with; the UI's carousel prediction reads the same one. */
    fun previousDecisionPositionMs(): Long = authoritativePositionMs(activeLocalPlayer())

    /**
     * The playing position from the engine, which is in this process and always right; the MediaController's own
     * estimate trails the session by up to ~2 s (measured on the HONOR), which is what the UI showed and what previous
     * decided with (AUD-116).
     */
    private fun authoritativePositionMs(controller: Player): Long =
        previousAuthority(controller).currentPosition.coerceAtLeast(0L)

    /**
     * The player whose state "previous" and the position read decide with: the engine's master, unless the controller is the better
     * witness (B29-L3, B29-fix1 F2).
     *
     * **Also when the controller is on another item than the engine**, outside a crossfade. A controller that is behind the engine
     * (the 20:40:31 shape: it still reported STORM I while the engine played STORM II) has the position of the song it thinks is
     * current, often a few seconds in, so a Previous 47 s into the real song read "within 3 s" and went back a track instead of
     * restarting it. During a crossfade the controller is deliberately ahead of the engine's master (it shows the incoming song),
     * and there its own state is the one that belongs to what is shown.
     */
    private fun previousAuthority(controller: Player): Player {
        val engine = dualPlayerEngine.masterPlayer
        return if (engine !== controller &&
            (engine.currentMediaItem?.mediaId == controller.currentMediaItem?.mediaId || !dualPlayerEngine.isTransitionRunning())
        ) {
            engine
        } else {
            controller
        }
    }

    /** The controller has heard what the authority knows: the same song is current and it has the same neighbour before it. */
    private fun controllerIsInStepWith(authority: Player, controller: Player): Boolean =
        authority.currentMediaItem?.mediaId == controller.currentMediaItem?.mediaId &&
            authority.hasPreviousMediaItem() == controller.hasPreviousMediaItem()

    fun nextSong() {
        activeLocalPlayer().seekToNext()
    }

    fun cycleRepeatMode() {
        val currentMode = _stablePlayerState.value.repeatMode
        val newMode = when (currentMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
        mediaController?.repeatMode = newMode
        appScope.launch { userPreferencesRepository.setRepeatMode(newMode) }
        _stablePlayerState.update { it.copy(repeatMode = newMode) }
    }

    fun setRepeatMode(mode: Int) {
        mediaController?.repeatMode = mode
        appScope.launch { userPreferencesRepository.setRepeatMode(mode) }
        _stablePlayerState.update { it.copy(repeatMode = mode) }
    }

    private var progressJob: kotlinx.coroutines.Job? = null

    /**
     * Reconciles duration reported by the player with the current song metadata duration.
     *
     * Why:
     * - During some transitions (notably crossfade player swaps), the reported duration can lag
     *   behind the currently visible track for a short period.
     * - Relying only on one source can make progress run too slow/fast.
     */
    private fun resolveEffectiveDuration(
        reportedDurationMs: Long,
        songDurationHintMs: Long,
        currentPositionMs: Long
    ): Long {
        val reported = when {
            reportedDurationMs == C.TIME_UNSET -> 0L
            reportedDurationMs < 0L -> 0L
            else -> reportedDurationMs
        }
        val hint = songDurationHintMs.coerceAtLeast(0L)
        val position = currentPositionMs.coerceAtLeast(0L)

        if (reported <= 0L) return hint
        if (hint <= 0L) return reported

        val diff = abs(reported - hint)
        if (diff <= DURATION_MISMATCH_TOLERANCE_MS) return reported

        if (position > hint + DURATION_MISMATCH_TOLERANCE_MS && reported >= position) {
            return reported
        }

        val resolved = minOf(reported, hint)
        if (diff > 10_000L) {
            Timber.tag(TAG).w(
                "Duration mismatch resolved (reported=%dms, hint=%dms, pos=%dms, resolved=%dms)",
                reported, hint, position, resolved
            )
        }
        return resolved
    }

    fun resolveDurationForPlaybackState(
        reportedDurationMs: Long,
        songDurationHintMs: Long,
        currentPositionMs: Long
    ): Long = resolveEffectiveDuration(
        reportedDurationMs = reportedDurationMs,
        songDurationHintMs = songDurationHintMs,
        currentPositionMs = currentPositionMs
    )

    fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = appScope.launch {
            _currentPosition.subscriptionCount.collectLatest { subscriberCount ->
                if (subscriberCount == 0) return@collectLatest
                coroutineScope {
                    while (isActive) {
                        val tickMs = currentProgressTickMs()
                        val controller = activeLocalPlayer()
                        if (shouldSampleLocalProgress(controller)) {
                            val visibleSong = _stablePlayerState.value.currentSong
                            val currentMediaId = controller.currentMediaItem?.mediaId
                            val hasMediaMismatch = visibleSong?.id != null &&
                                currentMediaId != null &&
                                visibleSong.id != currentMediaId

                            if (hasMediaMismatch) {
                                Timber.tag(TAG).v(
                                    "Skipping local progress tick due media mismatch (visible=%s, player=%s)",
                                    visibleSong.id,
                                    currentMediaId
                                )
                                delay(tickMs)
                                continue
                            }

                            val currentPosition = authoritativePositionMs(controller)
                            val songDurationHint = visibleSong?.duration ?: 0L
                            val duration = resolveEffectiveDuration(
                                reportedDurationMs = controller.duration,
                                songDurationHintMs = songDurationHint,
                                currentPositionMs = currentPosition
                            )

                            val resolvedPosition = resolveUiPosition(currentMediaId, currentPosition)
                            if (_currentPosition.value != resolvedPosition) {
                                _currentPosition.value = resolvedPosition
                            }

                            _stablePlayerState.update { state ->
                                if (state.totalDuration == duration) {
                                    state
                                } else {
                                    state.copy(totalDuration = duration)
                                }
                            }
                        }
                        delay(tickMs)
                    }
                }
            }
        }
    }

    /**
     * The playing position, read from the player now (B25-S3) — what one tick of [startProgressUpdates] would publish, for a
     * caller that is not one of its subscribers.
     *
     * The loop above samples only while something collects [currentPosition] (the work is worth doing only for someone who
     * shows it), so a reader of `currentPosition.value` with no collector of its own gets whatever the last collector left.
     * The DAP lock surface is that reader: the app under it is held, nothing else collects, and its elapsed time stood still.
     * This one asks the player, publishes what it found (so what the app reads when it is released is current), and
     * costs nothing while nobody calls it. Main thread, like the controller it asks.
     */
    fun samplePlaybackPositionNow(): Long {
        val controller = activeLocalPlayer()
        if (isSeeking || controller.mediaItemCount <= 0) return _currentPosition.value
        val visibleSongId = _stablePlayerState.value.currentSong?.id
        val mediaId = controller.currentMediaItem?.mediaId
        // The same guard the loop has: while the visible song and the player's are not yet the same, the position is the old one's.
        if (visibleSongId != null && mediaId != null && visibleSongId != mediaId) return _currentPosition.value
        val position = resolveUiPosition(mediaId, authoritativePositionMs(controller))
        if (_currentPosition.value != position) _currentPosition.value = position
        return position
    }

    private fun shouldSampleLocalProgress(controller: Player): Boolean {
        if (isSeeking) return false
        if (controller.mediaItemCount <= 0) return false
        if (controller.isPlaying) return true

        return controller.playWhenReady &&
            controller.playbackState != Player.STATE_IDLE &&
            controller.playbackState != Player.STATE_ENDED
    }

    private fun currentProgressTickMs(): Long = PlaybackProgressRates.tickMs(
        screenInteractive = powerManager.isInteractive,
        sliderMounted = _sliderUiMounted.value,
    )

    fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    // ---- shuffle (B17-H1) ---------------------------------------------------------------------------------------

    /**
     * Where the song that is playing sits in the queue **the app lists**, which is the queue in play order. Without shuffle
     * that is the player's own index; with shuffle on, the player's index is the song's place in the order the queue was
     * loaded in, and the queue screen lists the songs in the order they will play. A window of the queue the engine holds
     * after a crossfade is already listed whole (and in play order, when shuffle is on) by the engine.
     */
    fun queuePositionOf(player: Player): Int {
        if (dualPlayerEngine.isUsingWindowedQueue()) return dualPlayerEngine.getCurrentAbsoluteIndex()
        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET || !player.shuffleModeEnabled) return index
        val order = playOrderOf(player)
        return PlayOrder.positionOf(order, index).takeIf { it >= 0 } ?: index
    }

    private var orderMemoTimeline: androidx.media3.common.Timeline? = null
    private var orderMemo: IntArray = IntArray(0)

    /** The player's play order over its timeline, read once per timeline object (the timeline is replaced when the queue or its order changes). */
    fun playOrderOf(player: Player): IntArray {
        val timeline = player.currentTimeline
        if (timeline !== orderMemoTimeline) {
            orderMemo = PlayOrder.of(timeline, shuffleEnabled = true)
            orderMemoTimeline = timeline
        }
        return orderMemo
    }

    /**
     * Shuffle on or off. **Nothing is rebuilt and nothing is republished**: the engine flips Media3's shuffle mode and the
     * player keeps playing the song it is on and picks the next one from its shuffle order, or from the order the queue was
     * loaded in when it goes off. The old version rebuilt the timeline (two or three full publications of a 4,870-song
     * queue, ~5 s of main thread, a different song under the cursor if the rebuild raced a skip).
     *
     * The state shown follows at once; the player confirms it through `onShuffleModeEnabledChanged`, which is where the
     * queue screen is re-listed in the new order (off the main thread, without touching the session).
     */
    fun setShuffleEnabled(enabled: Boolean) {
        // A listener's switch: in classic mode, switching on also deals the queue again (B18-R1).
        dualPlayerEngine.setShuffleEnabled(enabled)
        _stablePlayerState.update { it.copy(isShuffleEnabled = enabled) }
    }

    /** Shuffle for a queue that is being loaded: the load brings its own order, so classic mode does not deal one (B18-R1). */
    fun setShuffleForQueueLoad(enabled: Boolean) {
        dualPlayerEngine.setShuffleForQueueLoad(enabled)
        _stablePlayerState.update { it.copy(isShuffleEnabled = enabled) }
    }

    /**
     * Flips what shuffle is going to be, not what the player's flag says now (B19-S2): while a crossfade holds a request back the
     * flag has not moved, and a second tap read it as the old state and asked for the same thing twice.
     */
    fun toggleShuffle() {
        setShuffleEnabled(!dualPlayerEngine.shuffleIntent)
    }

}
