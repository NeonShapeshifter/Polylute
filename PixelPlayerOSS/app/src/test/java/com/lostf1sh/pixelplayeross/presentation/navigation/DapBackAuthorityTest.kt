package com.lostf1sh.pixelplayeross.presentation.navigation

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The one Back authority.
 *
 * Both the affordance and the gesture call this and nothing else, which is what makes "the button
 * and the gesture do the same thing" true by construction rather than by two implementations being
 * kept in agreement.
 */
class DapBackAuthorityTest {

    private fun dispatcher(dispatches: MutableList<String>): OnBackPressedDispatcher {
        val dispatcher = OnBackPressedDispatcher()
        // Registered without a lifecycle owner: the authority's job is to decide *whether* to
        // dispatch, and a lifecycle would only be a second thing that could disable the callback
        // for reasons the test is not about.
        dispatcher.addCallback(
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    dispatches += "back"
                }
            },
        )
        return dispatcher
    }

    @Test
    fun `both input mechanisms reach the same dispatch`() {
        val dispatches = mutableListOf<String>()
        var now = 1_000L
        val authority = DapBackAuthority(dispatcher(dispatches), clock = { now })

        // The affordance.
        authority.back()
        now += DapBackAuthority.DEDUPE_WINDOW_MS

        // The gesture, a moment later.
        authority.back()

        assertEquals(listOf("back", "back"), dispatches)
    }

    @Test
    fun `one swipe is one step even when the platform also performs Back`() {
        val dispatches = mutableListOf<String>()
        val now = 5_000L
        val authority = DapBackAuthority(dispatcher(dispatches), clock = { now })

        // On a device in gesture navigation the platform performs Back for the same edge swipe the
        // app is also tracking. Both arrive within a few milliseconds of each other; the second is
        // swallowed rather than popping two destinations for one gesture.
        authority.back()
        authority.back()

        assertEquals(listOf("back"), dispatches)
    }

    @Test
    fun `two deliberate Back presses are still two steps`() {
        val dispatches = mutableListOf<String>()
        var now = 1_000L
        val authority = DapBackAuthority(dispatcher(dispatches), clock = { now })

        authority.back()
        now += DapBackAuthority.DEDUPE_WINDOW_MS + 1
        authority.back()

        assertEquals(listOf("back", "back"), dispatches)
    }
}
