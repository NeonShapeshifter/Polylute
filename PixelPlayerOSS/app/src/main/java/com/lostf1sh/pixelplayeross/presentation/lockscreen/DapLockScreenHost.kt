package com.lostf1sh.pixelplayeross.presentation.lockscreen

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.lostf1sh.pixelplayeross.ui.ds.dismissKeyboard
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapKeyboardRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import timber.log.Timber

/**
 * Decides when to raise the DAP lock surface, and raises it.
 *
 * ## Why this is a state machine and not an event handler
 *
 * The screen on/off broadcast is the trigger, because a dedicated player's screen going dark is not
 * an activity lifecycle event — the activity stays resumed while it holds Lock Task.
 *
 * The *handoff* — the part that waits for Android to finish with its own Keyguard — cannot be a
 * single handler. Instrumentation on the HONOR LGN-NX3 recorded a complete wake-and-swipe sequence
 * in which:
 *
 * * `ACTION_USER_PRESENT` **never arrived**, despite being the documented signal for exactly this,
 * * `ON_RESUME` arrived while `isKeyguardLocked` was still `true` — too early to act on,
 * * a window-focus gain arrived 257 ms later with `isKeyguardLocked == false` — the real signal.
 *
 * So the cycle is held as state from `SCREEN_OFF` until something authoritative proves the Keyguard
 * is gone, and an early event leaves it *waiting* rather than spending it. See [WakeCycleMachine].
 *
 * ## What this deliberately does not do
 *
 * Nothing here draws outside the app's own composition, and nothing here touches the Keyguard. At
 * every instant where `isKeyguardLocked` is true the surface stays hidden — the rule is *wait until
 * Android is done*, never *get to the screen before Android does*. Pinned by test.
 *
 * State lives for the duration of the composition; there is no lock-screen store and no saved flag,
 * so repeated cycles cannot accumulate surfaces.
 *
 * ## Time (B17-H2)
 *
 * An armed cycle waits for as long as the screen is off; the 60 s cap of [WakeCycleMachine.WAKE_CYCLE_TIMEOUT_MS]
 * counts from `SCREEN_ON`. And everything that decides or loses a cycle is logged under the `DapLockScreen` tag
 * (`SCREEN_OFF`, `SCREEN_ON`, evidence, `reset reason=…`, the receiver coming and going), so a surface that did not
 * show can be explained from `adb logcat -s DapLockScreen`.
 */
@Composable
fun DapLockScreenHost(
    state: StablePlayerState,
    enabled: Boolean,
    dedicated: Boolean,
    /**
     * Whether there is a current song **now**. The composed [state] is not enough: with the screen off the
     * activity is stopped, nothing recomposes, and the song seen at `SCREEN_ON` would be the one from before
     * the screen went dark.
     */
    currentSongPresent: () -> Boolean,
    currentPositionProvider: () -> Long,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    /** Where the sound is going, as the shell already describes it; null when unknown. */
    outputLine: String?,
    batteryPercent: Int?,
    /** Whether the surface is on screen, so the shell can hide what is behind it from TalkBack. */
    onSurfaceVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The signal reaches the DAC untouched: the mark before the output line is filled. */
    outputUntouched: Boolean = false,
    /** Whether the surface has been moved off the app (B22), so the shell can place the app under it. */
    onRevealingChange: (Boolean) -> Unit = {},
    /**
     * B25-S4: told, from the wake cycle's own events, whether the surface has the app covered or is about to (armed at
     * `SCREEN_OFF` through to the surface going away), so the app under it can be held before the screen comes back on.
     */
    cover: CoverSignal? = null,
) {
    val context = LocalContext.current
    val song = state.currentSong

    // Read through holders so the receiver never closes over a stale flag: it is registered once per
    // `enabled` change, and the values it consults change far more often than that. (The song is not
    // read through one: see `currentSongPresent`.)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestDedicated by rememberUpdatedState(dedicated)

    val keyguard = remember(context) {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }
    fun keyguardLocked(): Boolean = keyguard?.isKeyguardLocked == true

    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    // Unknown counts as "on": a host that cannot tell must not arm a cycle on a guess.
    fun screenInteractive(): Boolean = power?.isInteractive != false
    fun nowMs(): Long = SystemClock.elapsedRealtime()
    val latestCurrentSongPresent by rememberUpdatedState(currentSongPresent)
    fun songPresent(): Boolean = latestCurrentSongPresent()

    // The machine is pure and holds the authoritative state; this mirrors it into Compose so the
    // surface recomposes and the bounded re-check can be keyed on it.
    val machine = remember { WakeCycleMachine() }
    var cycleState by remember { mutableStateOf(WakeCycleState.IDLE) }
    // B25-S4: from SCREEN_OFF (armed) until the cycle ends the app under the surface is not to be woken: every state but IDLE
    // covers it, except a VISIBLE cycle that has no song to show (nothing would be on screen over a held app).
    fun publishCover() {
        cover?.covered = WakeCycleCover.covers(machine.state, songPresent())
    }
    fun sync() {
        cycleState = machine.state
        publishCover()
    }
    // The signal belongs to this host: when the host goes, the app is released.
    DisposableEffect(cover) { onDispose { cover?.covered = false } }

    /** One line per decision that matters; an ordinary focus change with no cycle behind it stays silent. */
    fun trace(event: String, before: WakeCycleState, detail: String = "") {
        Timber.tag(LOG_TAG).i("%s %s -> %s%s", event, before, machine.state, detail)
    }

    fun abandon(reason: String, detail: String = "") {
        val before = machine.state
        machine.reset()
        sync()
        if (before != WakeCycleState.IDLE) {
            Timber.tag(LOG_TAG).i("reset reason=%s %s -> %s%s", reason, before, machine.state, detail)
        }
    }

    /** The one authoritative resolution path: re-read the Keyguard and let the machine decide. */
    fun resolve(source: String) {
        val before = machine.state
        val locked = keyguardLocked()
        val hasSong = songPresent()
        machine.onUnlockEvidence(
            enabled = latestEnabled,
            dedicated = latestDedicated,
            keyguardLocked = locked,
            hasSong = hasSong,
            nowMs = nowMs(),
        )
        sync()
        if (before != machine.state || machine.isLive) {
            trace("EVIDENCE($source)", before, " keyguardLocked=$locked hasSong=$hasSong")
        }
    }

    // The post-Keyguard signal this device actually produces. Gated by the machine: with no live
    // cycle behind it, an ordinary focus change — opening Settings, closing a dialog, returning
    // home — resolves nothing, because `onUnlockEvidence` only acts on a live cycle.
    val view = LocalView.current
    DisposableEffect(view, latestEnabled) {
        val observer = view.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) resolve("focus")
        }
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            runCatching {
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Too early on its own — the trace caught it firing with the Keyguard still up — but
            // it still starts the bounded re-check, which is what closes that gap.
            if (event == Lifecycle.Event.ON_RESUME) resolve("resume")
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A song going away while the surface is up would leave it describing nothing. An *armed* cycle is
    // not touched (B17-H2): the screen is off, the song is judged at the wake, and a queue rebuild that
    // blinks the song to null in between (the composition only sees that if it recomposes at all) must not
    // cost the cycle.
    DisposableEffect(song?.id) {
        if (song == null && machine.state != WakeCycleState.ARMED) {
            abandon("song-null")
        }
        onDispose { }
    }

    DisposableEffect(latestEnabled, latestDedicated) {
        if (!latestEnabled || !latestDedicated) {
            // Eligibility lost while a cycle was live: abandon it rather than let it fire later.
            abandon("ineligible", " enabled=$latestEnabled dedicated=$latestDedicated")
            onDispose { }
        } else {
            // Attached with the screen already dark (the activity was recreated while it was off: a
            // language or night-mode change, Lock Task or Maintenance entering/leaving): nobody saw the
            // SCREEN_OFF this host would have armed on.
            val interactive = screenInteractive()
            val attachedFrom = machine.state
            machine.onHostAttached(
                enabled = latestEnabled,
                dedicated = latestDedicated,
                screenInteractive = interactive,
                nowMs = nowMs(),
            )
            sync()
            Timber.tag(LOG_TAG).i(
                "receiver registered screenInteractive=%s %s -> %s",
                interactive, attachedFrom, machine.state,
            )
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            val before = machine.state
                            machine.onScreenOff(
                                enabled = latestEnabled,
                                dedicated = latestDedicated,
                                nowMs = nowMs(),
                            )
                            sync()
                            trace("SCREEN_OFF", before, " enabled=$latestEnabled dedicated=$latestDedicated")
                        }

                        Intent.ACTION_SCREEN_ON -> {
                            val before = machine.state
                            val offForMs = machine.armedAtMs
                                .takeIf { it != WakeCycleMachine.NOT_WOKEN }
                                ?.let { nowMs() - it }
                            val locked = keyguardLocked()
                            val hasSong = songPresent()
                            machine.onScreenOn(
                                enabled = latestEnabled,
                                dedicated = latestDedicated,
                                keyguardLocked = locked,
                                hasSong = hasSong,
                                nowMs = nowMs(),
                            )
                            sync()
                            trace(
                                "SCREEN_ON", before,
                                " keyguardLocked=$locked hasSong=$hasSong offForMs=$offForMs",
                            )
                        }

                        Intent.ACTION_USER_PRESENT -> {
                            // Kept for devices that do send it. On this unit it never fires, which
                            // is precisely why it cannot be the only trigger.
                            resolve("user-present")
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose {
                runCatching { context.unregisterReceiver(receiver) }
                Timber.tag(LOG_TAG).i("receiver unregistered state=%s", machine.state)
            }
        }
    }

    // Bounded re-check for a Keyguard that lags the events that announce it. Not a poll: the ladder
    // is finite, and a woken cycle is capped (from SCREEN_ON) so it can never linger and fire later.
    // An armed cycle has no timer at all: the screen is simply still off (B17-H2).
    LaunchedEffect(cycleState, latestEnabled, latestDedicated) {
        superviseWaitingCycle(
            machine = machine,
            nowMs = ::nowMs,
            recheck = { resolve("recheck") },
            onExpired = {
                sync()
                Timber.tag(LOG_TAG).i(
                    "reset reason=timeout the Keyguard did not clear within %d ms of the wake",
                    WakeCycleMachine.WAKE_CYCLE_TIMEOUT_MS,
                )
            },
        )
    }

    val displayedSong = song
    val surfaceVisible = machine.shouldShowSurface && displayedSong != null
    val latestVisibleChange by rememberUpdatedState(onSurfaceVisibleChange)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val editorRegistry = LocalDapKeyboardRegistry.current
    LaunchedEffect(surfaceVisible) {
        Timber.tag(LOG_TAG).i("surface visible=%s state=%s", surfaceVisible, machine.state)
        if (surfaceVisible) {
            editorRegistry?.dismissFocusedFields()
            dismissKeyboard(focusManager, keyboard)
        }
        latestVisibleChange(surfaceVisible)
    }
    if (surfaceVisible && displayedSong != null) {
        DapLockScreen(
            song = displayedSong,
            isPlaying = state.isPlaying,
            isShuffleEnabled = state.isShuffleEnabled,
            repeatMode = state.repeatMode,
            currentPositionProvider = currentPositionProvider,
            totalDuration = state.totalDuration,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onToggleShuffle = onToggleShuffle,
            onToggleRepeat = onToggleRepeat,
            outputLine = outputLine,
            outputUntouched = outputUntouched,
            batteryPercent = batteryPercent,
            onRevealingChange = onRevealingChange,
            onDismiss = {
                abandon("dismissed")
            },
            modifier = modifier.fillMaxSize(),
        )
    }
}

private const val LOG_TAG = "DapLockScreen"
