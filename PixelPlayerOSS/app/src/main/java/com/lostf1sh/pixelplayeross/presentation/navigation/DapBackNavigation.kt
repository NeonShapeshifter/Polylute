package com.lostf1sh.pixelplayeross.presentation.navigation

import android.os.SystemClock
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.staticCompositionLocalOf
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode

/**
 * The one Back authority.
 *
 * There is exactly one way Back happens in this app, and both input mechanisms call it: the visible
 * affordance and the app's edge gesture. They are not two implementations of Back that have to be
 * kept in agreement — they are one call with two triggers.
 *
 * The call is [OnBackPressedDispatcher.onBackPressed], which is the same entry point the system's own
 * Back reaches. That is what makes modal priority fall out for free instead of being re-implemented:
 * a sheet in its own window has already consumed Back before this is reached, a `BackHandler` deeper
 * in the composition is asked before the nav graph, and the nav graph pops last. A gesture that
 * called `popBackStack()` directly would skip every layer in front of the host.
 *
 * ## The dedupe window
 *
 * On a device in gesture navigation, a left-edge swipe is *also* seen by the platform, which performs
 * Back itself — the composition receives the same pointer stream and the platform performs the same
 * navigation. The app cannot stop it and does not try to. What it can do is refuse to perform the
 * same Back twice: a dispatch that lands within [DEDUPE_WINDOW_MS] of the previous one is dropped,
 * so one swipe is one step whether the platform, the gesture, or the button got there first.
 *
 * The window is far shorter than a deliberate double-Back, so pressing Back twice on purpose is
 * still two steps.
 */
class DapBackAuthority(
    private val dispatcher: OnBackPressedDispatcher,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    /**
     * Zero means "nothing has been dispatched yet", and is a sentinel rather than a very small
     * number because the uptime clock is never zero in a running process. A negative sentinel would
     * make the subtraction below overflow on the first call, and the first Back after launch would
     * be the one that got swallowed.
     */
    private var lastDispatchAt = 0L

    fun back() {
        val now = clock()
        if (lastDispatchAt != 0L && now - lastDispatchAt < DEDUPE_WINDOW_MS) return
        lastDispatchAt = now
        dispatcher.onBackPressed()
    }

    companion object {
        const val DEDUPE_WINDOW_MS = 250L
    }
}

/**
 * The authority as the composition sees it.
 *
 * A screen never reaches for the dispatcher or the nav controller itself: it calls what the shell
 * provided. That is what keeps the gesture, the overlay affordance and the system's own Back
 * indistinguishable in effect.
 */
val LocalDapBackNavigation = staticCompositionLocalOf<DapBackAuthority> {
    error("LocalDapBackNavigation was read outside the DAP shell")
}

/**
 * The active Back mode.
 *
 * Provided once by the shell and read by every surface that owns a Back affordance, so a screen does
 * not each decide for itself what "gestures only" means.
 */
val LocalBackNavigationMode = staticCompositionLocalOf { BackNavigationMode.DEFAULT }
