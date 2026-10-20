package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * The keyboard, as one behaviour for the whole app.
 *
 * 1. **It gets out of the way of what you type.** [DapKeyboardAwareColumn] shrinks by the keyboard's
 *    height and scrolls, so the field and whatever it produces (a PIN's dots) stay visible. The
 *    window is edge-to-edge, so `adjustResize` alone no longer moves anything on Android 15; each
 *    surface has to take the IME inset itself.
 * 2. **Enter finishes.** [dapDismissKeyboardActions] closes the keyboard on Done/Go/Search/Send,
 *    after running whatever the field submits.
 * 3. **Touching anything else closes it.** The shell installs [dapKeyboardDismissHost]; a text field
 *    opts in with [dapTextField]. A touch that lands outside every registered field clears focus
 *    and hides the keyboard, and the touch still does what it would have done — nothing is consumed.
 * 4. **Changing page closes it.** The shell calls [dismissKeyboard] when the route or root tab
 *    changes; Home, Search and Library stay composed when hidden, so without this a hidden Search
 *    field kept focus and the keyboard stayed up on top of Home.
 */
class DapKeyboardRegistry internal constructor() {
    private val fields = HashMap<Any, Rect>()
    private val focused = HashSet<Any>()
    private val dismissals = HashMap<Any, () -> Unit>()
    internal var hostCoordinates: LayoutCoordinates? = null

    internal fun place(token: Any, boundsInWindow: Rect) { fields[token] = boundsInWindow }
    internal fun focus(token: Any, isFocused: Boolean) {
        if (isFocused) focused += token else focused -= token
    }
    internal fun remove(token: Any) {
        fields -= token
        focused -= token
        dismissals -= token
    }

    internal fun registerDismiss(token: Any, dismiss: () -> Unit) { dismissals[token] = dismiss }

    /** Includes editors in modal windows that inherit the shell registry. */
    internal fun dismissFocusedFields() {
        focused.toList().forEach { token -> dismissals[token]?.invoke() }
    }

    /** True when a registered field has focus and [windowPoint] is on no registered field. */
    internal fun isOutside(windowPoint: androidx.compose.ui.geometry.Offset): Boolean =
        focused.isNotEmpty() && fields.values.none { it.contains(windowPoint) }
}

val LocalDapKeyboardRegistry = staticCompositionLocalOf<DapKeyboardRegistry?> { null }

/** Provides the registry for a window's content. The shell wraps its root once. */
@Composable
fun DapKeyboardScope(content: @Composable () -> Unit) {
    val registry = remember { DapKeyboardRegistry() }
    CompositionLocalProvider(LocalDapKeyboardRegistry provides registry, content = content)
}

/** Clears focus and hides the keyboard. Clearing focus alone leaves some IMEs up. */
fun dismissKeyboard(focusManager: FocusManager, keyboard: SoftwareKeyboardController?) {
    focusManager.clearFocus(force = true)
    keyboard?.hide()
}

/**
 * The shell's outside-touch rule. Sees every touch first ([PointerEventPass.Initial]) and consumes
 * none; when a registered field has focus and the touch lands on no registered field, it dismisses.
 * Touching a second field therefore moves focus without the keyboard flickering closed and open.
 */
fun Modifier.dapKeyboardDismissHost(): Modifier = composed {
    val registry = LocalDapKeyboardRegistry.current ?: return@composed this
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    this
        .onGloballyPositioned { registry.hostCoordinates = it }
        .pointerInput(registry) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val host = registry.hostCoordinates ?: return@awaitEachGesture
                if (registry.isOutside(host.localToWindow(down.position))) {
                    dismissKeyboard(focusManager, keyboard)
                }
            }
        }
}

/**
 * Registers a text field (or the container that wraps one, such as the search bar with its clear
 * button) with the outside-touch rule. Uses `hasFocus`, so it can sit on a container.
 */
fun Modifier.dapTextField(): Modifier = composed {
    val registry = LocalDapKeyboardRegistry.current ?: return@composed this
    val token = remember { Any() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(registry, token, focusManager, keyboard) {
        registry.registerDismiss(token) { dismissKeyboard(focusManager, keyboard) }
        onDispose { registry.remove(token) }
    }
    this
        .onGloballyPositioned { registry.place(token, it.boundsInWindow()) }
        .onFocusChanged { registry.focus(token, it.hasFocus) }
}

/**
 * Enter finishes: runs [onSubmit] (if any) and closes the keyboard, for every single-line action.
 * A multi-line field never reaches this — its Enter is a newline.
 */
@Composable
fun dapDismissKeyboardActions(onSubmit: (() -> Unit)? = null): KeyboardActions {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val finish: () -> Unit = {
        onSubmit?.invoke()
        dismissKeyboard(focusManager, keyboard)
    }
    return KeyboardActions(
        onDone = { finish() },
        onGo = { finish() },
        onSearch = { finish() },
        onSend = { finish() },
    )
}

/**
 * A page body that stays usable with the keyboard up: centred while it fits, and shrunk by the
 * keyboard and scrollable when it does not, so a focused field is scrolled into view above it.
 */
@Composable
fun DapKeyboardAwareColumn(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Scroll first, then the minimum: the content is at least a viewport tall (so
                // Center still centres it) and grows past it, scrollably, when the keyboard is up.
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}
