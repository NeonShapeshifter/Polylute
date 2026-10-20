package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.AudioProcessingRequirements
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy.Activation
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy.HardwareVolume
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy.RiseReason
import kotlin.math.log10
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * F-02 "Pure Direct" (owner request): one switch that freezes — without applying or losing — every
 * contributor that would take the signal off PRISTINE, and never lets the effective level rise: the
 * attenuation being frozen moves onto the DAC's own volume and comes back when the mode is left.
 */
class PristineModeTest {

    private val rgMinus6 = Math.pow(10.0, -6.0 / 20.0).toFloat()

    private fun hw(current: Int, stepDb: Float = 0.5f, min: Int = 0, max: Int = 200, verified: Boolean = true) =
        HardwareVolume(currentStep = current, minStep = min, maxStep = max, stepDb = stepDb, verified = verified)

    @Test
    fun `digital 50 percent and ReplayGain -6 dB freeze 12 dB, and the DAC goes down at least that`() {
        val attenuation = PristineModePolicy.frozenAttenuationDb(0.5f, rgMinus6, 1f)
        assertThat(attenuation.toDouble()).isWithin(0.05).of(20 * log10(0.5) - 6.0)
        val plan = PristineModePolicy.activation(attenuation, directActive = true, hardware = hw(current = 150))
        plan as Activation.LowerDac
        // Rounded to whole DAC steps towards quieter: the effective level never rises.
        assertThat(plan.loweredDb).isAtLeast(-attenuation)
        assertThat(plan.loweredDb - (-attenuation)).isLessThan(0.5f)
        assertThat(plan.targetStep).isEqualTo(150 - plan.loweredSteps)
        // Effective level: before = DAC + attenuation, after = lowered DAC with no digital gain.
        val before = 150 * 0.5f + attenuation
        val after = plan.targetStep * 0.5f
        assertThat(after).isAtMost(before)
    }

    @Test
    fun `leaving restores the same steps, never above the Safety ceiling`() {
        assertThat(PristineModePolicy.restoreStep(currentStep = 125, loweredSteps = 25, maxStep = 200, ceilingStep = null))
            .isEqualTo(150)
        assertThat(PristineModePolicy.restoreStep(currentStep = 125, loweredSteps = 25, maxStep = 200, ceilingStep = 140))
            .isEqualTo(140)
        assertThat(PristineModePolicy.restoreStep(currentStep = 195, loweredSteps = 25, maxStep = 200, ceilingStep = null))
            .isEqualTo(200)
    }

    @Test
    fun `a DAC without hardware volume, an unverified one, or leaving the range needs confirmation`() {
        val att = -12f
        val fixed = PristineModePolicy.activation(att, directActive = true, hardware = null)
        assertThat(fixed).isEqualTo(Activation.NeedsConfirmation(12f, RiseReason.NO_HARDWARE_VOLUME))
        val unverified = PristineModePolicy.activation(att, directActive = true, hardware = hw(150, verified = false))
        assertThat((unverified as Activation.NeedsConfirmation).reason).isEqualTo(RiseReason.NO_HARDWARE_VOLUME)
        val nearBottom = PristineModePolicy.activation(att, directActive = true, hardware = hw(current = 10))
        nearBottom as Activation.NeedsConfirmation
        assertThat(nearBottom.reason).isEqualTo(RiseReason.DAC_RANGE)
        assertThat(nearBottom.riseDb).isWithin(0.01f).of(12f - 10 * 0.5f)
        val outside = PristineModePolicy.activation(att, directActive = false, hardware = null)
        assertThat((outside as Activation.NeedsConfirmation).reason).isEqualTo(RiseReason.OUTSIDE_DIRECT)
        // Nothing frozen: nothing to move, anywhere.
        assertThat(PristineModePolicy.activation(0f, directActive = false, hardware = null))
            .isEqualTo(Activation.NothingToCompensate)
    }

    @Test
    fun `while locked no DSP contributor asks for PROCESSED, and nothing is lost`() {
        val editing = AudioProcessingRequirements(graphicEq = true, replayGain = true, digitalVolume = true,
            androidSessionEffect = true, crossfade = false)
        val locked = PristineModePolicy.requirements(editing, locked = true)
        assertThat(locked.requiresPcmProcessing).isFalse()
        assertThat(locked.androidSessionEffect).isFalse()
        assertThat(PristineModePolicy.requirements(editing, locked = false)).isEqualTo(editing)
    }

    @Test
    fun `status says where it applies`() {
        assertThat(PristineModePolicy.status(locked = false, directActive = true)).isEqualTo(PristineModePolicy.Status.OFF)
        assertThat(PristineModePolicy.status(locked = true, directActive = true)).isEqualTo(PristineModePolicy.Status.ACTIVE)
        assertThat(PristineModePolicy.status(locked = true, directActive = false)).isEqualTo(PristineModePolicy.Status.ONLY_IN_DIRECT)
    }

    // ── Controller order ────────────────────────────────────────────

    private class FakeHost(
        var gain: Triple<Float, Float?, Float> = Triple(0.5f, null, 1f),
        var direct: Boolean = true,
        var step: Int? = 150,
        var ceiling: Int? = null,
        var applied: Boolean = true,
    ) : PristineModeHost {
        var locked = false
        var lowered = 0
        val events = mutableListOf<String>()
        override fun gainContributors() = gain
        override fun isDirectActive() = direct
        override fun hardwareVolume() = step?.let { HardwareVolume(it, 0, 200, 0.5f, true) }
        override fun ceilingStep() = ceiling
        override fun setHardwareStep(step: Int): Boolean { events += "dac=$step"; this.step = step; return true }
        override suspend fun awaitContributorsApplied(): Boolean { events += "await"; return applied }
        override suspend fun lockState() = locked to lowered
        override suspend fun persist(locked: Boolean, loweredSteps: Int) {
            events += "lock=$locked/$loweredSteps"; this.locked = locked; lowered = loweredSteps
        }
    }

    @Test
    fun `enabling lowers the DAC before the lock, disabling raises it only after the gain is back`() = runTest {
        val host = FakeHost()
        val controller = PristineModeController(host)
        val on = controller.enable()
        assertThat(on).isInstanceOf(PristineToggleResult.Enabled::class.java)
        assertThat(host.events).containsExactly("dac=137", "lock=true/13").inOrder()  // 6.02 dB → 13 × 0.5 dB
        host.events.clear()
        val off = controller.disable()
        assertThat(off).isEqualTo(PristineToggleResult.Disabled(6.5f))
        assertThat(host.events).containsExactly("lock=false/13", "await", "dac=150", "lock=false/0").inOrder()
    }

    @Test
    fun `the DAC is not raised if the digital gain never came back, nor above the ceiling`() = runTest {
        val host = FakeHost()
        val controller = PristineModeController(host)
        controller.enable()
        host.applied = false
        assertThat(controller.disable()).isEqualTo(PristineToggleResult.Disabled(0f))
        assertThat(host.step).isEqualTo(137)

        val capped = FakeHost(ceiling = 140)
        PristineModeController(capped).run { enable(); disable() }
        assertThat(capped.step).isEqualTo(140)
    }

    @Test
    fun `a rise is refused until confirmed, and a confirmed one moves nothing`() = runTest {
        val host = FakeHost(step = null)
        val controller = PristineModeController(host)
        val ask = controller.enable()
        assertThat(ask).isInstanceOf(PristineToggleResult.NeedsConfirmation::class.java)
        assertThat(host.locked).isFalse()
        assertThat(controller.enable(confirmRise = true)).isEqualTo(PristineToggleResult.Enabled(0f))
        assertThat(host.locked).isTrue()
        assertThat(host.events.none { it.startsWith("dac=") }).isTrue()
    }
}
