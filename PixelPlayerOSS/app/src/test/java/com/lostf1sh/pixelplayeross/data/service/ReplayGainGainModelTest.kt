package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The shared gain model: the user's level with ReplayGain *modifying* it.
 *
 * ## What changed and why
 *
 * ReplayGain used to write its multiplier straight into `player.volume`, replacing whatever the
 * user had set. Switching ReplayGain off then restored the user's value — often audibly, and always
 * by discarding one of the two contributors for as long as ReplayGain was on.
 *
 * The corrected model keeps them apart and multiplies once:
 *
 * ```
 * combinedGain = userDigitalGain * replayGainFactor
 * ```
 *
 * ## What these tests do and do not cover
 *
 * They cover the arithmetic contract and the attribution rules — the part where a mistake is silent
 * and plausible, and where "ReplayGain applied twice" would actually live. They do **not** cover
 * `ReplayGainProcessor`'s field-level state machine across crossfades, player rebuilds and
 * transition timing; that needs the engine and the players, and is exercised by the physical
 * matrix. Saying so explicitly so a green run here is not read as more than it is.
 */
class ReplayGainGainModelTest {

    private val half = 0.5f

    // ── The canonical cases ─────────────────────────────────────────────

    @Test
    fun `user half, replay gain unity`() {
        assertThat(ReplayGainProcessor.effectiveGainOf(0.5f, 1.0f)).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `user unity, replay gain half`() {
        assertThat(ReplayGainProcessor.effectiveGainOf(1.0f, half)).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `user half, replay gain half composes to a quarter`() {
        // The decisive identity. A product of 0.125 would mean ReplayGain was applied twice; 0.5
        // would mean one contributor was dropped.
        assertThat(ReplayGainProcessor.effectiveGainOf(half, half)).isWithin(1e-6f).of(0.25f)
    }

    @Test
    fun `no replay gain factor means the user's level stands alone`() {
        assertThat(ReplayGainProcessor.effectiveGainOf(0.5f, null)).isWithin(1e-6f).of(0.5f)
    }

    // ── Attribution stays independent ───────────────────────────────────

    @Test
    fun `replay gain attenuation is not attributed to the user`() {
        // user=1.0 with RG=0.5 produces a combined gain of 0.5 — but the user changed nothing, so
        // only ReplayGain may be reported as the cause. Deriving the requirement from the combined
        // value is exactly what would get this wrong.
        val combined = ReplayGainProcessor.effectiveGainOf(1.0f, half)

        assertThat(ReplayGainProcessor.requiresDigitalVolume(1.0f)).isFalse()
        assertThat(ReplayGainProcessor.requiresReplayGain(half)).isTrue()
        assertThat(combined).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `a unity factor from replay gain is not a requirement`() {
        // Enabled, tags read, and the answer is 0 dB. That is a mathematical identity, the same
        // way a flat enabled EQ is, and must not move the stream onto the processed path.
        assertThat(ReplayGainProcessor.requiresReplayGain(1.0f)).isFalse()
        assertThat(ReplayGainProcessor.requiresReplayGain(null)).isFalse()
    }

    @Test
    fun `the user's own level is a requirement on its own`() {
        assertThat(ReplayGainProcessor.requiresDigitalVolume(0.5f)).isTrue()
        assertThat(ReplayGainProcessor.requiresDigitalVolume(1.0f)).isFalse()
    }

    // ── Contributor survival ────────────────────────────────────────────

    @Test
    fun `recovering the user gain round trips, so toggling replay gain cannot lose it`() {
        // This is the invariant behind "RG ON -> OFF leaves the user's 0.5 at 0.5, and RG OFF -> ON
        // composes the same 0.5 again". An external write carries the composed value; inverting it
        // must return exactly the user's own contribution, whatever the factor was.
        for (userGain in listOf(0.25f, 0.5f, 0.75f, 1.0f)) {
            for (factor in listOf(null, 1.0f, 0.5f, 0.7943f)) {
                val composed = ReplayGainProcessor.effectiveGainOf(userGain, factor)
                val recovered = ReplayGainProcessor.userGainFrom(composed, factor)
                assertThat(recovered).isWithin(1e-4f).of(userGain)
            }
        }
    }

    @Test
    fun `changing the user level while replay gain is active keeps the factor`() {
        // The factor is state the user's gesture must not touch. Only userDigitalGain moves, and
        // the composed gain recomputes from both.
        val factor = 0.5f
        val before = ReplayGainProcessor.effectiveGainOf(1.0f, factor)
        val after = ReplayGainProcessor.effectiveGainOf(0.5f, factor)

        assertThat(before).isWithin(1e-6f).of(0.5f)
        assertThat(after).isWithin(1e-6f).of(0.25f)
        assertThat(ReplayGainProcessor.requiresReplayGain(factor)).isTrue()
    }

    @Test
    fun `a player rebuild reapplies both contributors from the retained state`() {
        // A rebuild reads the two stored contributors, not the released player's volume. Reapplying
        // from stored state is what makes the result identical before and after the swap.
        val userGain = 0.5f
        val factor = 0.5f
        val onNewPlayer = ReplayGainProcessor.effectiveGainOf(userGain, factor)

        assertThat(onNewPlayer).isWithin(1e-6f).of(0.25f)
    }

    // ── Crossfade target ────────────────────────────────────────────────

    @Test
    fun `the crossfade target is the composed gain, not the raw factor`() {
        // The fade loop ramps to a *level*. Handing it the bare factor would make the fade land at
        // a level that ignores the user's own volume — a jump at the instant the fade completes,
        // and only then.
        val userGain = 0.5f
        val factor = 0.5f

        val target = ReplayGainProcessor.effectiveGainOf(userGain, factor)

        assertThat(target).isWithin(1e-6f).of(0.25f)
        assertThat(target).isNotWithin(1e-6f).of(factor)
    }

    // ── No double application ───────────────────────────────────────────

    @Test
    fun `replay gain cannot be applied twice through the composition`() {
        // The only defect that produces 0.125 here is a second multiplication of the factor. This
        // asserts the composition is a single product, and that nothing about recovering the user
        // gain introduces another.
        val userGain = 0.5f
        val factor = 0.5f
        val composed = ReplayGainProcessor.effectiveGainOf(userGain, factor)
        val recovered = ReplayGainProcessor.userGainFrom(composed, factor)
        val recomposed = ReplayGainProcessor.effectiveGainOf(recovered, factor)

        assertThat(composed).isWithin(1e-4f).of(0.25f)
        assertThat(recomposed).isWithin(1e-4f).of(composed)
    }
}
