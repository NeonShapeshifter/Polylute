package com.lostf1sh.pixelplayeross.presentation.components.scoped

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * When a seek bar may hand authority back to the player.
 *
 * The bar shows the requested position while a seek settles. What must never happen is taking the
 * player's position back too early, while it still reads the *old* value — that is the snap-back
 * the user sees as "my seek was undone".
 */
class SeekReconciliationTest {

    private fun settled(
        authoritative: Float,
        target: Float,
        anchor: Float,
        elapsed: Long = 0L,
    ) = SeekReconciliation.hasSettled(authoritative, target, anchor, elapsed)

    @Test
    fun `holds while the player is still reporting the pre-seek position`() {
        // User dragged from 0.10 to 0.80; the player has not moved yet.
        assertThat(settled(authoritative = 0.10f, target = 0.80f, anchor = 0.10f)).isFalse()
    }

    @Test
    fun `hands back as soon as the player arrives at the requested position`() {
        assertThat(settled(authoritative = 0.80f, target = 0.80f, anchor = 0.10f)).isTrue()
    }

    @Test
    fun `converges when the player lands near but not exactly on the target`() {
        // Within the settle tolerance: adopt the player's truth rather than holding the request.
        assertThat(settled(authoritative = 0.805f, target = 0.80f, anchor = 0.10f)).isTrue()
    }

    @Test
    fun `converges when the player moved toward the target even if it is not there yet`() {
        // Mid-flight: the player is between the anchor and the target. This is genuine movement
        // for the seek, so the bar may follow it rather than stay pinned.
        assertThat(settled(authoritative = 0.45f, target = 0.80f, anchor = 0.10f)).isTrue()
    }

    @Test
    fun `does not hand back to a position further from the target than the anchor`() {
        // A player that drifted backwards has not honoured the seek; holding is correct.
        assertThat(settled(authoritative = 0.02f, target = 0.80f, anchor = 0.10f)).isFalse()
    }

    @Test
    fun `backwards seeks behave the same way`() {
        assertThat(settled(authoritative = 0.90f, target = 0.20f, anchor = 0.90f)).isFalse()
        assertThat(settled(authoritative = 0.55f, target = 0.20f, anchor = 0.90f)).isTrue()
        assertThat(settled(authoritative = 0.20f, target = 0.20f, anchor = 0.90f)).isTrue()
    }

    @Test
    fun `an ignored seek is released by the backstop, and the backstop is not seconds long`() {
        // The safety timeout is the path for a seek the player never acted on. It must be short
        // enough that it is never the normal user-visible mechanism.
        assertThat(SeekReconciliation.SAFETY_TIMEOUT_MS).isAtMost(1_500L)

        val ignored = settled(
            authoritative = 0.10f,
            target = 0.80f,
            anchor = 0.10f,
            elapsed = SeekReconciliation.SAFETY_TIMEOUT_MS,
        )
        assertThat(ignored).isTrue()
    }

    @Test
    fun `a stream of player updates never holds past the backstop`() {
        // Sweep a plausible reconciliation window; every sample must resolve by the timeout.
        var heldFor = 0L
        while (heldFor < 4_000L) {
            val done = settled(
                authoritative = 0.10f, target = 0.80f, anchor = 0.10f, elapsed = heldFor,
            )
            if (done) return
            heldFor += 16L
        }
        throw AssertionError("stale display held for the whole 4s sweep")
    }
}
