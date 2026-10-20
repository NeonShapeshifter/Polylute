package com.lostf1sh.pixelplayeross.presentation.components

import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt
import com.lostf1sh.pixelplayeross.ui.ds.formatDecibels
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.volume.SafetyOverridePolicy
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import com.lostf1sh.pixelplayeross.data.volume.VolumeHudEvent
import com.lostf1sh.pixelplayeross.data.volume.VolumeHudPolicy
import com.lostf1sh.pixelplayeross.ui.ds.DapVolumeHud
import com.lostf1sh.pixelplayeross.ui.ds.DapVolumeNotice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's own volume readout.
 *
 * ## Why the readout is a window rather than a composable in the activity's layout
 *
 * The readout has to appear over *every* surface, and several of this app's surfaces are not in the
 * activity's window at all: a `ModalBottomSheet` and a `Dialog` each get a window of their own, and
 * anything composed inside the activity is drawn underneath them. A readout that disappeared
 * whenever Song Actions or Track Details was open would be exactly the inconsistency this pass
 * exists to remove.
 *
 * ## Why it is driven from the lifecycle rather than from a composable
 *
 * The window is created by a plain coroutine and taken down by a plain coroutine, so the readout
 * never depends on the activity recomposing to appear. Measured on the device, that matters: a
 * readout mounted inside the activity's composition was reliable on every ordinary surface and
 * never appeared at all once another window of this app held focus.
 *
 * ## The state this cannot cover, verified on the device
 *
 * Android delivers a hardware key to the focused window and does not forward it to the activity
 * when that window does not handle it. With a `ModalBottomSheet` or a `Dialog` of this app focused,
 * `Activity.dispatchKeyEvent` is therefore not called at all: the controller never sees the press,
 * and there is nothing for a readout to report. Measured with the volume parked on a calibrated
 * Safety Max ceiling and Song Actions open, Volume Up left the level at the ceiling — neither the
 * app's ceiling nor the platform's own panel acted on it.
 *
 * That is a property of key dispatch, not of this window, and it is recorded rather than worked
 * around: the alternatives are reflection into the platform or a second, vendor-specific key path,
 * both of which would trade a real gap for a brittle one. The readout is app-owned and consistent
 * on every surface that is in the activity's window, which is where volume is actually changed.
 *
 * The window is deliberately as small and as quiet as it can be:
 *
 * - `FLAG_NOT_FOCUSABLE` — it never takes focus, so a volume press while typing cannot close the
 *   keyboard or move the caret out of a search field.
 * - it is sized to the pill and nothing else, and `FLAG_NOT_TOUCH_MODAL` makes that explicit, so a
 *   touch anywhere else on screen reaches the app exactly as it would have.
 * - no dim and no background drawable: nothing is darkened while it is up.
 * - it exists only while it is being read.
 *
 * It is added afresh on every press, which is also what puts it above a sheet that was already open:
 * a window added now is ordered above one added earlier. A sheet opened *during* the readout's brief
 * life would land on top of it — a rare ordering that corrects itself in about a second.
 */
class VolumeHudController(
    private val activity: ComponentActivity,
    private val controller: SafetyVolumeController,
) {
    private val visible: MutableState<Boolean> = mutableStateOf(false)
    private val refused: MutableState<Boolean> = mutableStateOf(false)
    private val volume: MutableState<SafetyVolumeState> = mutableStateOf(SafetyVolumeState())
    private val digital: MutableState<Float?> = mutableStateOf(null)

    private var view: ComposeView? = null
    private var collectJob: Job? = null
    private var hideJob: Job? = null

    /** A finger is on the readout: it stays up until the finger lifts. */
    private var touching = false

    /** Begins observing the volume authority. Idempotent. */
    fun start() {
        if (collectJob != null) return
        collectJob = activity.lifecycleScope.launch {
            launch { controller.state.collect { volume.value = it } }
            launch { controller.digitalLevel.collect { digital.value = it } }
            controller.hudEvent.collect { onInteraction(it) }
        }
    }

    /** Stops observing and takes the window down. */
    fun stop() {
        collectJob?.cancel()
        collectJob = null
        hideJob?.cancel()
        hideJob = null
        removeWindow()
    }

    private fun onInteraction(event: VolumeHudEvent) {
        if (event.interactionId == 0L) return
        refused.value = event.refusedBySafetyMax
        addWindow()
        // Every press restarts this, so a burst of presses extends the readout rather than stacking
        // a second one, and only the last press decides when it goes away. The deadline is measured
        // from when that press landed rather than from when this ran, so a collector that was busy
        // for a moment still shows the readout for the time it actually has left.
        if (touching) return
        scheduleHide(VolumeHudPolicy.remainingMillis(event.interactionAtMs, SystemClock.uptimeMillis()))
    }

    private fun scheduleHide(afterMs: Long) {
        hideJob?.cancel()
        hideJob = activity.lifecycleScope.launch {
            delay(afterMs)
            removeWindow()
        }
    }

    private fun onTouchActive(active: Boolean) {
        touching = active
        if (active) hideJob?.cancel() else scheduleHide(VolumeHudPolicy.VISIBLE_MILLIS)
    }

    /**
     * A drag on the output pill. Same path as a key: the controller applies Safety Max and, on a DAC,
     * writes its hardware volume. Only whole steps are written — each one is a USB control transfer.
     */
    private fun onLevelDrag(fraction: Float) {
        val backend = volume.value.backend
        if (!backend.controllable) return
        val raw = backend.rawMin + (fraction * (backend.rawMax - backend.rawMin)).roundToInt()
        if (raw != backend.rawLevel) controller.setRawLevel(raw)
    }

    private fun onDigitalDrag(fraction: Float) {
        val current = digital.value ?: return
        val percent = (fraction * 100f).roundToInt()
        if (percent != (current * 100f).roundToInt()) controller.setDigitalLevel(percent / 100f)
    }

    private fun addWindow() {
        if (view != null) {
            visible.value = true
            return
        }
        val density = activity.resources.displayMetrics.density
        val params = WindowManager.LayoutParams().apply {
            // The same window type the platform gives a dialog: an application window, which is
            // ordered above the activity's own and below anything the system owns.
            type = WindowManager.LayoutParams.TYPE_APPLICATION
            // At the edge the physical keys are on, a little above centre where they sit, so the
            // readout appears beside the finger that moved it and never over a screen title.
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            x = (EDGE_MARGIN.value * density).toInt()
            y = -(ABOVE_CENTRE.value * density).toInt()
            setFitInsetsTypes(0)
        }

        val composeView = ComposeView(activity).apply {
            // The readout's window is not part of the activity's view tree, so it is told which
            // lifecycle, view-model store and saved-state registry it belongs to rather than
            // inheriting them from a parent view it does not have.
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setContent {
                VolumeReadout(
                    visible,
                    refused,
                    volume,
                    digital,
                    onLevelChange = ::onLevelDrag,
                    onDigitalChange = ::onDigitalDrag,
                    onTouchActive = ::onTouchActive,
                )
            }
        }

        visible.value = true
        val added = runCatching { activity.windowManager.addView(composeView, params) }.isSuccess
        // A readout is a nicety. If the platform refuses the window, the volume change has already
        // happened, and the right outcome is no readout — not a crash on a hardware key press.
        view = composeView.takeIf { added }
    }

    private fun removeWindow() {
        touching = false
        visible.value = false
        val current = view ?: return
        view = null
        runCatching { activity.windowManager.removeViewImmediate(current) }
    }

    private companion object {
        val EDGE_MARGIN = 10.dp
        val ABOVE_CENTRE = 72.dp
    }
}

/**
 * The readout itself.
 *
 * It renders the volume the *system* is actually at, never a number of its own: the level, the range
 * and the Safety Max state all come from the controller's authoritative state, which reconciles
 * against the platform. There is no second volume here.
 */
@Composable
private fun VolumeReadout(
    visible: MutableState<Boolean>,
    refused: MutableState<Boolean>,
    volume: MutableState<SafetyVolumeState>,
    digital: MutableState<Float?>,
    onLevelChange: (Float) -> Unit,
    onDigitalChange: (Float) -> Unit,
    onTouchActive: (Boolean) -> Unit,
) {
    if (!visible.value) return
    val state = volume.value
    val digitalLevel = digital.value
    val notice = noticeFor(state, refused.value)
    DapVolumeHud(
        // A DAC's hardware volume is read in dB, which is what its steps are made of; anything else
        // reads as the same percentage the Settings control shows.
        label = state.hardwareDecibels?.let {
            stringResource(R.string.dap_volume_db, formatDecibels(it))
        } ?: "${state.displayPercent}%",
        level = state.backend.rawLevel,
        maxLevel = state.backend.rawMax,
        notice = notice,
        // FIX-4: an override that is on says so, and says what ends it (the same two rules the
        // policy applies; the minutes are the policy's own constant, not a number typed here).
        noticeDetail = stringResource(
            R.string.volume_hud_override_detail,
            (SafetyOverridePolicy.IDLE_END_MS / 60_000L).toInt(),
        ).takeIf { notice == DapVolumeNotice.OVERRIDE },
        outputName = stringResource(R.string.dap_volume_row_dac),
        digitalFraction = digitalLevel,
        digitalLabel = digitalLevel?.let { "${(it * 100f).roundToInt()}%" },
        digitalName = stringResource(R.string.dap_volume_row_digital),
        // PRISTINE shows only the output pill, so only it can be touched; in PROCESSED both can.
        onLevelChange = onLevelChange.takeIf { state.backend.controllable },
        onDigitalChange = onDigitalChange.takeIf { digitalLevel != null },
        onTouchActive = onTouchActive,
        // Room for the shadow inside the wrap-content window.
        modifier = Modifier.padding(HUD_SHADOW_ROOM),
    )
}

private val HUD_SHADOW_ROOM = 8.dp

/**
 * Picks the truthful state word for the volume right now.
 *
 * Ordered most-specific first. A refused press is an event and outranks a state; a level sitting on
 * the configured ceiling outranks the plain top of the backend's range; and an override is only
 * claimed while the level is genuinely past its ceiling, which is what stops the readout saying
 * "OVERRIDE" for the rest of the session after a single press above the limit.
 */
internal fun noticeFor(state: SafetyVolumeState, refusedBySafetyMax: Boolean): DapVolumeNotice {
    val backend = state.backend
    val ceiling = state.calibration?.ceilingRaw?.takeIf { state.safetyEnabled }
    return when {
        refusedBySafetyMax -> DapVolumeNotice.BLOCKED
        ceiling != null && !state.temporaryOverride && backend.rawLevel >= ceiling ->
            DapVolumeNotice.AT_LIMIT
        ceiling != null && state.temporaryOverride && backend.rawLevel > ceiling ->
            DapVolumeNotice.OVERRIDE
        backend.rawLevel >= backend.rawMax -> DapVolumeNotice.AT_MAXIMUM
        else -> DapVolumeNotice.NONE
    }
}
