package com.lostf1sh.pixelplayeross.presentation.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The modal volume-key contract.
 *
 * The rule is stated over a key type rather than over `KeyEvent` on purpose: `KeyEvent` cannot be
 * constructed in a JVM unit test, and the part of this bridge worth asserting is the *ordering* —
 * the volume authority is offered every key first, and only what it declines reaches the window.
 * A stand-in key drives exactly that ordering.
 */
class ModalInputRoutingTest {

    @Test
    fun `a key the authority takes is consumed and the window never sees it`() {
        var windowAsked = false

        val consumed = routeModalKey(
            key = "VOLUME_UP",
            authority = { true },
            window = {
                windowAsked = true
                false
            },
        )

        assertTrue(consumed, "a handled key must be reported as consumed")
        assertFalse(windowAsked, "a consumed key must not reach the window or the platform")
    }

    @Test
    fun `the authority is offered the key before the window`() {
        val order = mutableListOf<String>()

        routeModalKey(
            key = "VOLUME_DOWN",
            authority = {
                order += "authority"
                true
            },
            window = {
                order += "window"
                true
            },
        )

        assertEquals(listOf("authority"), order)
    }

    @Test
    fun `a key the authority declines is handed to the window unchanged`() {
        var seen: String? = null

        val consumed = routeModalKey(
            key = "VOLUME_DOWN",
            authority = { false },
            window = {
                seen = it
                false
            },
        )

        assertEquals("VOLUME_DOWN", seen, "the window must receive the same key the authority saw")
        assertFalse(consumed, "a declined key must not be reported as consumed")
    }

    @Test
    fun `the window's own answer is the answer when the authority declines`() {
        val windowTook = routeModalKey(
            key = "VOLUME_UP",
            authority = { false },
            window = { true },
        )
        val windowDeclined = routeModalKey(
            key = "VOLUME_UP",
            authority = { false },
            window = { false },
        )

        assertTrue(windowTook, "the window's consumption must be reported")
        assertFalse(windowDeclined, "an untouched key must fall through to the platform")
    }

    @Test
    fun `the rule is applied to every key, not only to volume keys`() {
        // The bridge does not decide what a volume key is — the authority does, exactly as it does
        // for MainActivity. Any filtering here would be a second, divergent definition of the key.
        val asked = mutableListOf<String>()

        listOf("VOLUME_UP", "VOLUME_DOWN", "BACK", "MEDIA_PLAY_PAUSE").forEach { key ->
            routeModalKey(key = key, authority = { asked += it; false }, window = { false })
        }

        assertEquals(listOf("VOLUME_UP", "VOLUME_DOWN", "BACK", "MEDIA_PLAY_PAUSE"), asked)
    }
}
