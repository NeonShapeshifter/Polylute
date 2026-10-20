package com.lostf1sh.pixelplayeross.presentation.components

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint
import com.lostf1sh.pixelplayeross.presentation.navigation.BackEdge
import com.lostf1sh.pixelplayeross.presentation.navigation.EdgeBackDecision
import com.lostf1sh.pixelplayeross.presentation.navigation.EdgeBackGesturePolicy
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * The modal key contract.
 *
 * The app's volume authority has first refusal on a key that reaches an app-owned modal window. A
 * key it takes is consumed — the window is never asked, and neither is the platform, so one press
 * stays one step. A key it declines belongs to the window, exactly as it would have without this
 * bridge. That is the same contract `MainActivity` has with the same authority on the activity's
 * own window, and it is deliberately the whole of the policy here: this file knows nothing about
 * what a volume key means.
 *
 * The rule is stated over a key type rather than over `KeyEvent` because the ordering it asserts —
 * authority first, window only if the authority declines — is the part worth testing, and
 * `KeyEvent` cannot be constructed in a JVM unit test. An ordering guarantee with no test is the
 * kind that quietly stops being true.
 */
internal fun <K> routeModalKey(
    key: K,
    authority: (K) -> Boolean,
    window: (K) -> Boolean,
): Boolean = authority(key) || window(key)

/**
 * Makes the app's input rules hold inside one of its own modal windows.
 *
 * ## Why a modal window needs this at all
 *
 * Every app-owned modal — a `ModalBottomSheet` such as Song Actions, a `Dialog` such as Track
 * Details, an `AlertDialog`, an editor — is a window of its own. Android delivers a key to the
 * window that holds focus and does not forward it to the activity, and it delivers touches to that
 * window's view tree alone. So while one of them is up, everything the activity installs on its own
 * window is simply not in the path:
 *
 * - **Hardware volume keys** never reached `MainActivity.dispatchKeyEvent`, so the volume authority
 *   never saw the press, no readout was emitted, and Safety Max stopped applying to the key.
 *   Measured on the device before this bridge existed: Volume Up over Song Actions was handled by
 *   the platform instead, with no ceiling and no readout.
 * - **The app's edge Back gesture** is a `pointerInput` on the activity's content, so it never saw a
 *   touch aimed at a modal either. Measured on the device: with Track Details open, a left-edge
 *   swipe did nothing at all while the system Back dismissed the sheet correctly.
 *
 * ## What it does
 *
 * It wraps the modal window's own `Window.Callback` — the same hook the framework already uses to
 * reach `MainActivity.dispatchKeyEvent` and `MainActivity.dispatchTouchEvent` on the activity's
 * window — and puts the app's two authorities in front of it:
 *
 * - hardware keys go to [SafetyVolumeController] first, and anything it declines is passed through;
 * - an edge swipe is read by the same [EdgeBackGesturePolicy] the activity's gesture uses, and on a
 *   trigger it performs **the window's own Back**, which is what the system Back key does in that
 *   window: it dismisses the sheet or dialog that is in front, at the same priority the back button
 *   and the system gesture already have.
 *
 * It adds no second authority and no second readout. It holds no volume and computes no level:
 * [SafetyVolumeController] remains the single authority and emits the one `VolumeHudEvent` the
 * shell readout renders. It does not re-implement Back either — it asks the window to do what it
 * already does when the platform delivers Back to it.
 *
 * ## Where it installs
 *
 * Only inside a window of the app that is not the activity's own — recognised by the view sitting
 * under a [DialogWindowProvider]. Composing it in the activity's window is a no-op on purpose: the
 * activity owns both paths already, and bridging them here as well would route one press, or one
 * swipe, twice. Dismissing the modal disposes the effect and puts the original callback back.
 *
 * Mount it as the first statement of the window's content, e.g.
 *
 * ```
 * ModalBottomSheet(onDismissRequest = { … }) {
 *     ModalInputBridge()
 *     …
 * }
 * ```
 */
@Composable
fun ModalInputBridge(
    controller: SafetyVolumeController = rememberVolumeKeyAuthority(),
    backGestureEnabled: Boolean = rememberBackGestureEnabled(),
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val context = LocalContext.current

    val edgeWidthPx = with(density) { EdgeBackGesturePolicy.EdgeWidth.toPx() }
    val activationPx = with(density) { EdgeBackGesturePolicy.ActivationDistance.toPx() }
    val slopPx = remember(context) { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }

    // The gesture's visual progress in this window. It is written by the callback below and read by
    // the indicator at the foot of this composable — the same indicator the activity draws, fed the
    // same way, so the two are the same thing on both sides of the window boundary.
    var gestureProgress by remember { mutableFloatStateOf(0f) }
    // B25-S6: the side the gesture started on, so the indicator below is drawn there.
    var gestureEdge by remember { mutableStateOf(BackEdge.LEFT) }
    // The screen's width (a dialog's own window may be narrower), read when a touch goes down so it is always the current one.
    val screenWidthPx: () -> Float = { view.context.resources.displayMetrics.widthPixels.toFloat() }

    DisposableEffect(view, controller, backGestureEnabled, edgeWidthPx, activationPx, slopPx) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val previous = window?.callback

        // A window whose callback is already ours keeps the one that is installed. Mounting the
        // bridge twice inside a single window — a nested dialog, a composition that re-runs the
        // effect — must not put two wrappers around one window, because a press would then be
        // offered to the authority twice.
        if (window == null || previous == null || previous is ModalInputCallback) {
            onDispose { }
        } else {
            window.callback = ModalInputCallback(
                delegate = previous,
                controller = controller,
                backGestureEnabled = backGestureEnabled,
                edgeWidthPx = edgeWidthPx,
                activationDistancePx = activationPx,
                slopPx = slopPx,
                screenWidthPx = screenWidthPx,
                onEdge = { gestureEdge = it },
                onProgress = { gestureProgress = it },
            )
            onDispose {
                if (window.callback is ModalInputCallback) window.callback = previous
                gestureProgress = 0f
            }
        }
    }

    // The same indicator the activity draws, in this window.
    //
    // It has to be a popup rather than a child of this composable: the bridge is mounted as the
    // first child of each modal's own content — a `ColumnScope` inside a sheet, a plain content
    // lambda inside a dialog — so anything it emitted would be laid out in that flow, pushing the
    // sheet's content down instead of floating over it. A popup belongs to this composition, lives
    // and dies with this window, and is positioned at the edge rather than in the parent's bounds.
    //
    // Composed only while the gesture is in progress, which matters: a popup that sat at the left
    // edge permanently would be a window under the very pixels the edge gesture starts from.
    if (gestureProgress > 0.001f) {
        Popup(
            popupPositionProvider = if (gestureEdge == BackEdge.LEFT) LeftEdgeCentrePopupPositionProvider else RightEdgeCentrePopupPositionProvider,
            properties = PopupProperties(focusable = false),
        ) {
            EdgeBackGestureIndicator(progress = gestureProgress, edge = gestureEdge)
        }
    }
}

/**
 * Places the indicator against the left edge of the window, vertically centred — where the activity
 * puts it, so the two are in the same place.
 *
 * Measured from the window's left rather than the layout direction's start: the gesture's strip is
 * the physical left edge of the screen, so the feedback has to be too.
 */
internal object LeftEdgeCentrePopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = 0,
        y = (windowSize.height - popupContentSize.height) / 2,
    )
}

/** The same, against the right edge, for a gesture that started there (B25-S6). */
internal object RightEdgeCentrePopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = windowSize.width - popupContentSize.width,
        y = (windowSize.height - popupContentSize.height) / 2,
    )
}

/**
 * The window callback that puts the app's authorities in front of a modal window's own.
 *
 * Every callback method other than the two it acts on is forwarded unchanged, so the window keeps
 * behaving exactly as it did — its own key handling, its own touch handling, its own focus and
 * window events, and its own dismissal.
 */
private class ModalInputCallback(
    private val delegate: Window.Callback,
    private val controller: SafetyVolumeController,
    private val backGestureEnabled: Boolean,
    private val edgeWidthPx: Float,
    private val activationDistancePx: Float,
    private val slopPx: Float,
    /** The screen's width, to tell the right edge strip from the middle; a dialog's own window is not the screen (B25-S6). */
    private val screenWidthPx: () -> Float,
    /** Which side a gesture started on, told once when it starts so the indicator is drawn there (B25-S6). */
    private val onEdge: (BackEdge) -> Unit,
    /**
     * Where the visual progress of the gesture is published, 0f to 1f.
     *
     * **Reporting only.** It is written after the policy has already decided, from the same deltas
     * the policy was given, and nothing reads it back: the decision to claim and the decision to go
     * Back are the policy's alone, exactly as before. Removing this sink would change nothing about
     * whether Back happens, only whether the user sees it coming.
     */
    private val onProgress: (Float) -> Unit,
) : Window.Callback by delegate {

    private var policy: EdgeBackGesturePolicy? = null
    private var edge = BackEdge.LEFT
    private var claimed = false
    private var fired = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastEventTime = 0L
    // The sum of the same x deltas that are handed to the policy, and nothing else: no threshold,
    // no slop and no velocity rule is repeated here. It exists only because the policy keeps its
    // own travel private, and the alternative — reading it out of the policy — would mean changing
    // the policy, which is the one thing this must not do.
    private var travelledX = 0f

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = routeModalKey(
        key = event,
        authority = { controller.handleVolumeKey(it) },
        window = { delegate.dispatchKeyEvent(it) },
    )

    /**
     * The app's edge Back gesture, read from this window's touch stream.
     *
     * Nothing is consumed until the gesture is claimed, so every ordinary touch — a scroll, a
     * slider, a sheet drag — passes through to the window untouched. Once claimed, the rest of the
     * gesture is consumed here, and any view that had already started tracking it is told the
     * gesture is over rather than being left holding a pointer that will never be lifted.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!backGestureEnabled) return delegate.dispatchTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                claimed = false
                fired = false
                travelledX = 0f
                onProgress(0f)
                // Measured against the screen, not the window: a dialog that does not fill the
                // display must not put its own edge in the middle of it.
                val startedAt = EdgeBackGesturePolicy.edgeAt(event.rawX, screenWidthPx(), edgeWidthPx)
                edge = startedAt ?: BackEdge.LEFT
                lastX = edge.inward(event.x)
                lastY = event.y
                lastEventTime = event.eventTime
                policy = if (startedAt != null) newPolicy() else null
                if (startedAt != null) onEdge(startedAt)
                return delegate.dispatchTouchEvent(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (claimed) {
                    // Already ours, so the window sees nothing more of it — but the travel is still
                    // worth reporting, or the indicator would freeze at the moment it was claimed.
                    // Once Back has fired it stays cleared; the gesture is over as far as the
                    // reader is concerned.
                    if (fired) return true
                    travelledX += edge.inward(event.x) - lastX
                    lastX = edge.inward(event.x)
                    lastY = event.y
                    lastEventTime = event.eventTime
                    onProgress((travelledX / activationDistancePx).coerceIn(0f, 1f))
                    return true
                }
                val active = policy ?: return delegate.dispatchTouchEvent(event)

                val dx = edge.inward(event.x) - lastX
                val dy = event.y - lastY
                val previousTime = lastEventTime
                lastX = edge.inward(event.x)
                lastY = event.y
                lastEventTime = event.eventTime
                travelledX += dx

                return when (active.onMove(dx, dy, previousTime, event.eventTime)) {
                    // Clearly a scroll, or not yet decided. The window keeps the gesture, and
                    // nothing is shown: an indicator for every touch near the edge would be noise.
                    EdgeBackDecision.IGNORED, EdgeBackDecision.PENDING -> {
                        onProgress(0f)
                        delegate.dispatchTouchEvent(event)
                    }

                    EdgeBackDecision.CLAIMED -> {
                        claim(event)
                        onProgress((travelledX / activationDistancePx).coerceIn(0f, 1f))
                        true
                    }

                    EdgeBackDecision.TRIGGERED -> {
                        claim(event)
                        // Cleared as Back fires, not left standing for the rest of the gesture.
                        onProgress(0f)
                        if (!fired) {
                            fired = true
                            performWindowBack()
                        }
                        true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasClaimed = claimed
                policy = null
                claimed = false
                travelledX = 0f
                // Released or cancelled: the indicator goes with the gesture, whether or not Back
                // fired.
                onProgress(0f)
                // A claimed gesture was already taken away from the view tree with a cancel, so
                // there is nothing left for the window to do with the release.
                return if (wasClaimed) true else delegate.dispatchTouchEvent(event)
            }

            else -> return if (claimed) true else delegate.dispatchTouchEvent(event)
        }
    }

    /** Takes the gesture away from the window, once, when the policy decides it is ours. */
    private fun claim(event: MotionEvent) {
        if (claimed) return
        claimed = true
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        delegate.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    /**
     * Asks the window to go back, exactly as the system Back key does in it.
     *
     * The window's own dispatcher is what a Back press reaches, so this keeps the priority the back
     * button and the system gesture already have: the sheet or dialog in front is dismissed, and the
     * screen underneath is left alone until there is nothing in front of it. Calling the activity's
     * dispatcher here would instead pop the navigation graph out from under an open dialog, which is
     * not what Back means while one is up.
     */
    private fun performWindowBack() {
        val dialog = delegate as? ComponentDialog ?: return
        dialog.onBackPressedDispatcher.onBackPressed()
    }

    private fun newPolicy() = EdgeBackGesturePolicy(
        edgeWidthPx = edgeWidthPx,
        activationDistancePx = activationDistancePx,
        slopPx = slopPx,
    )
}

/**
 * The volume authority, resolved where a modal window can reach it.
 *
 * A modal window's composition is not guaranteed to sit below anything the activity provides, so the
 * authority is asked of Hilt rather than read from a composition local. It is the same `@Singleton`
 * the activity holds.
 */
@Composable
private fun rememberVolumeKeyAuthority(): SafetyVolumeController {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, ModalInputEntryPoint::class.java)
            .safetyVolumeController()
    }
}

/**
 * Whether the app's edge Back gesture should run inside this window.
 *
 * Read from the same preference the activity's gesture reads, so the Back mode means the same thing
 * on both sides of the window boundary. It is not read from a composition local for the same reason
 * the authority is not: a modal window cannot assume it is below one.
 */
@Composable
private fun rememberBackGestureEnabled(): Boolean {
    val context = LocalContext.current
    val repository = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, ModalInputEntryPoint::class.java)
            .userPreferencesRepository()
    }
    val mode by repository.backNavigationModeFlow
        .collectAsStateWithLifecycle(initialValue = BackNavigationMode.DEFAULT)
    return mode.edgeGestureEnabled
}
