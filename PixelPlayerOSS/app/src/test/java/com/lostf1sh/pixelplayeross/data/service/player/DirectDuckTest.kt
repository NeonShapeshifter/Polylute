package com.lostf1sh.pixelplayeross.data.service.player

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqSettings
import com.lostf1sh.pixelplayeross.data.volume.DacVolumeArbiter
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import com.lostf1sh.pixelplayeross.data.volume.TemporaryWrite
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B14-3, audit A-05: a "may duck" focus request while the music goes over usbfs.
 *
 * On the Android route the system ducks the app's AudioTracks and does not even say so. On USB Direct the
 * music is not in a track, so the app has to attenuate what it sends, and it may not do that the same way in
 * every mode: Purity may not touch a sample. The matrix here is mode × focus event × DAC with and without a
 * hardware volume, against a fake DAC that records every write, and the stage's own PCM for the digital case.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectDuckTest {

    // ── The fake DAC and the host around it ────────────────────────────────────────────────────

    private class FakeHost(
        var direct: Boolean = true,
        var mode: DirectSignalMode = DirectSignalMode.PRISTINE,
        /** Null: a DAC with no hardware volume. */
        var dacStep: Int? = 30,
        val minStep: Int = 0,
        val maxStep: Int = 40,
        val stepDb: Float = 2f,
        var verified: Boolean = true,
        var ceiling: Int? = null,
        /** Writes the DAC refuses from this one on (counted from 1); 0: never. */
        var refuseFromWrite: Int = 0,
    ) : DirectDuckHost {
        /** The real owner of the DAC's volume, as `SafetyVolumeController` uses it. */
        val arbiter = DacVolumeArbiter(Any())
        val writes = mutableListOf<Int>()
        val digitalGains = mutableListOf<Float>()
        var pauses = 0
        val pauseGenerations = mutableListOf<Long>()

        override fun directActive() = direct
        override fun signalMode() = mode
        override fun hardwareVolume(): PristineModePolicy.HardwareVolume? = dacStep?.let {
            val (ticket, _) = arbiter.observe { it }
            PristineModePolicy.HardwareVolume(it, minStep, maxStep, stepDb, verified, ticket)
        }
        override fun ceilingStep() = ceiling
        override fun setHardwareStepTemporarily(step: Int, ticket: Long, expectedStep: Int): TemporaryWrite =
            arbiter.temporaryWrite(ticket, expectedStep, step, ceilingStep = { ceiling }, readStep = { dacStep }) {
                if (dacStep == null || (refuseFromWrite != 0 && writes.size + 1 >= refuseFromWrite)) {
                    false
                } else {
                    writes += step
                    dacStep = step
                    true
                }
            }
        override fun setDigitalDuckGain(gain: Float) { digitalGains += gain }
        override fun pauseForDuck(generation: Long) { pauses++; pauseGenerations += generation }
    }

    // backgroundScope: the controller's consumer never ends, and a test may not leave a child of its own scope running.
    private fun TestScope.controller(host: FakeHost) = DirectDuckController(host, backgroundScope, 20L, 40L)

    /**
     * Runs what the controller has queued, ramps included. Not `advanceUntilIdle`: that stops as soon as only
     * background tasks are left, and the controller's consumer is one.
     */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
    }

    private var focusGeneration = 0L

    private fun TestScope.send(controller: DirectDuckController, vararg events: DuckFocusEvent) {
        for (event in events) {
            controller.onFocusEvent(event, ++focusGeneration)
            settle()
        }
    }

    private val canDuck = DuckFocusEvent.CAN_DUCK
    private val gain = DuckFocusEvent.GAIN

    // ── The policy ─────────────────────────────────────────────────────────────────────────────

    private fun hw(step: Int = 30, verified: Boolean = true, stepDb: Float = 2f, min: Int = 0) =
        PristineModePolicy.HardwareVolume(step, min, 40, stepDb, verified)

    @Test
    fun `the action by route, mode and DAC`() {
        val pristine = DirectSignalMode.PRISTINE
        val processed = DirectSignalMode.PROCESSED
        // Android: the system's own ducking stands, whatever the DAC.
        assertThat(DirectDuckPolicy.actionFor(false, pristine, hw())).isEqualTo(DirectDuckPolicy.Action.SYSTEM)
        assertThat(DirectDuckPolicy.actionFor(false, processed, null)).isEqualTo(DirectDuckPolicy.Action.SYSTEM)
        // Resonance: digital, and the DAC is not asked.
        assertThat(DirectDuckPolicy.actionFor(true, processed, hw())).isEqualTo(DirectDuckPolicy.Action.DIGITAL_RAMP)
        assertThat(DirectDuckPolicy.actionFor(true, processed, null)).isEqualTo(DirectDuckPolicy.Action.DIGITAL_RAMP)
        // Purity: the DAC when it can be trusted, a pause when it cannot. Never digital.
        assertThat(DirectDuckPolicy.actionFor(true, pristine, hw())).isEqualTo(DirectDuckPolicy.Action.HARDWARE_VOLUME)
        assertThat(DirectDuckPolicy.actionFor(true, pristine, null)).isEqualTo(DirectDuckPolicy.Action.PAUSE)
        assertThat(DirectDuckPolicy.actionFor(true, pristine, hw(verified = false))).isEqualTo(DirectDuckPolicy.Action.PAUSE)
        assertThat(DirectDuckPolicy.actionFor(true, pristine, hw(stepDb = 0f))).isEqualTo(DirectDuckPolicy.Action.PAUSE)
    }

    @Test
    fun `a hardware duck goes 12 dB down, and a DAC with no room to go down is paused instead`() {
        assertThat(DirectDuckPolicy.usableHardwareDuckStep(hw(step = 30))).isEqualTo(24)        // 6 steps of 2 dB
        assertThat(DirectDuckPolicy.usableHardwareDuckStep(hw(step = 30, stepDb = 4f))).isEqualTo(27)
        assertThat(DirectDuckPolicy.usableHardwareDuckStep(hw(step = 4))).isEqualTo(0)          // 8 dB of room: shallower, still useful
        assertThat(DirectDuckPolicy.usableHardwareDuckStep(hw(step = 2))).isNull()              // 4 dB of room: a pause
        assertThat(DirectDuckPolicy.usableHardwareDuckStep(hw(step = 0))).isNull()
        assertThat(DirectDuckPolicy.usableHardwareDuckStep(null)).isNull()
    }

    @Test
    fun `the way back is the level it left, never above it, never above the ceiling, and never over a listener's change`() {
        // Exactly where it was.
        assertThat(DirectDuckPolicy.restoreStep(preDuck = 30, held = 24, written = 24, ceilingStep = null, maxStep = 40)).isEqualTo(30)
        // The ceiling came down during the duck: it caps the return.
        assertThat(DirectDuckPolicy.restoreStep(30, 24, 24, ceilingStep = 26, maxStep = 40)).isEqualTo(26)
        // A ceiling above the old level does not lift it.
        assertThat(DirectDuckPolicy.restoreStep(30, 24, 24, ceilingStep = 38, maxStep = 40)).isEqualTo(30)
        // The listener set the volume during the duck: it stands.
        assertThat(DirectDuckPolicy.restoreStep(30, held = 27, written = 24, ceilingStep = null, maxStep = 40)).isNull()
        assertThat(DirectDuckPolicy.restoreStep(30, held = 20, written = 24, ceilingStep = null, maxStep = 40)).isNull()
        // Nothing to give back (already at or above the target).
        assertThat(DirectDuckPolicy.restoreStep(30, held = 30, written = 30, ceilingStep = null, maxStep = 40)).isNull()
        // The ceiling below where the duck sits: nothing to raise.
        assertThat(DirectDuckPolicy.restoreStep(30, held = 24, written = 24, ceilingStep = 20, maxStep = 40)).isNull()
    }

    // ── Purity: the DAC, a ramp, the exact level back ──────────────────────────────────────────

    @Test
    fun `Purity with a hardware volume ducks 12 dB on the DAC one step at a time and restores the exact step`() = runTest {
        val host = FakeHost(dacStep = 30)
        val controller = controller(host)

        send(controller, canDuck)

        assertThat(host.writes).containsExactly(29, 28, 27, 26, 25, 24).inOrder()   // a ramp, every step read back
        assertThat(host.dacStep).isEqualTo(24)
        assertThat(host.digitalGains).isEmpty()                                      // not one sample touched
        assertThat(host.pauses).isEqualTo(0)

        send(controller, gain)

        assertThat(host.dacStep).isEqualTo(30)                                       // the exact level
        assertThat(host.writes.drop(6)).containsExactly(25, 26, 27, 28, 29, 30).inOrder()
        assertThat(host.digitalGains).isEmpty()
        // At no point did the duck write above where it began.
        assertThat(host.writes.max()).isAtMost(30)
    }

    @Test
    fun `Purity respects Safety Max on the way back and never rises above the level before the duck`() = runTest {
        val host = FakeHost(dacStep = 30, ceiling = 27)
        val controller = controller(host)

        send(controller, canDuck, gain)

        assertThat(host.dacStep).isEqualTo(27)
        assertThat(host.writes.max()).isAtMost(29)   // the first duck step; nothing ever went above the old level
        assertThat(host.dacStep).isAtMost(30)
    }

    @Test
    fun `a volume the listener sets during the duck is left alone`() = runTest {
        val host = FakeHost(dacStep = 30)
        val controller = controller(host)
        send(controller, canDuck)
        host.dacStep = 28                      // a key press while the prompt plays
        val writesBefore = host.writes.size

        send(controller, gain)

        assertThat(host.dacStep).isEqualTo(28)
        assertThat(host.writes.size).isEqualTo(writesBefore)
    }

    @Test
    fun `a DAC with no hardware volume pauses on the duck and gives nothing to restore`() = runTest {
        val host = FakeHost(dacStep = null)
        val controller = controller(host)

        send(controller, canDuck)

        assertThat(host.pauses).isEqualTo(1)
        assertThat(host.writes).isEmpty()
        assertThat(host.digitalGains).isEmpty()

        send(controller, gain)   // the engine resumes on the gain; the controller has nothing else to do

        assertThat(host.pauses).isEqualTo(1)
        assertThat(host.writes).isEmpty()
        assertThat(host.digitalGains).isEmpty()
    }

    @Test
    fun `a DAC that cannot be trusted or has no room to go down is paused, not nudged`() = runTest {
        for (host in listOf(FakeHost(dacStep = 30, verified = false), FakeHost(dacStep = 2))) {
            val controller = controller(host)
            send(controller, canDuck, gain)
            assertThat(host.pauses).isEqualTo(1)
            assertThat(host.writes).isEmpty()
            assertThat(host.digitalGains).isEmpty()
        }
    }

    @Test
    fun `a DAC that refuses the first write is paused, one that refuses later holds a shallower duck and still returns exactly`() = runTest {
        val refusesAtOnce = FakeHost(dacStep = 30, refuseFromWrite = 1)
        send(controller(refusesAtOnce), canDuck)
        assertThat(refusesAtOnce.pauses).isEqualTo(1)
        assertThat(refusesAtOnce.dacStep).isEqualTo(30)

        val refusesLater = FakeHost(dacStep = 30, refuseFromWrite = 4)
        val controller = controller(refusesLater)
        send(controller, canDuck)
        assertThat(refusesLater.dacStep).isEqualTo(27)       // three steps in, then it held
        assertThat(refusesLater.pauses).isEqualTo(0)
        refusesLater.refuseFromWrite = 0
        send(controller, gain)
        assertThat(refusesLater.dacStep).isEqualTo(30)
    }

    // ── Resonance: digital, and only digital ───────────────────────────────────────────────────

    @Test
    fun `Resonance ducks digitally 12 dB and releases to unity, and the DAC is never touched`() = runTest {
        val host = FakeHost(mode = DirectSignalMode.PROCESSED, dacStep = 30)
        val controller = controller(host)

        send(controller, canDuck)
        assertThat(host.digitalGains).containsExactly(DirectDuckPolicy.DUCK_GAIN)
        assertThat(20.0 * log10(DirectDuckPolicy.DUCK_GAIN.toDouble())).isWithin(0.01).of(-12.0)

        send(controller, gain)
        assertThat(host.digitalGains).containsExactly(DirectDuckPolicy.DUCK_GAIN, 1f).inOrder()
        assertThat(host.writes).isEmpty()
        assertThat(host.pauses).isEqualTo(0)
    }

    @Test
    fun `Resonance on a DAC with no hardware volume still ducks digitally and does not pause`() = runTest {
        val host = FakeHost(mode = DirectSignalMode.PROCESSED, dacStep = null)
        send(controller(host), canDuck)
        assertThat(host.digitalGains).containsExactly(DirectDuckPolicy.DUCK_GAIN)
        assertThat(host.pauses).isEqualTo(0)
    }

    // ── The matrix: mode × event × DAC ─────────────────────────────────────────────────────────

    private enum class Dac { WITH_HARDWARE_VOLUME, WITHOUT }

    @Test
    fun `no focus event other than a duck changes anything while nothing is ducked`() = runTest {
        for (mode in DirectSignalMode.values()) {
            for (dac in Dac.values()) {
                for (event in listOf(DuckFocusEvent.LOSS, DuckFocusEvent.LOSS_TRANSIENT, DuckFocusEvent.GAIN)) {
                    val host = FakeHost(mode = mode, dacStep = if (dac == Dac.WITH_HARDWARE_VOLUME) 30 else null)
                    send(controller(host), event)
                    assertThat(host.writes).isEmpty()
                    assertThat(host.digitalGains).isEmpty()
                    assertThat(host.pauses).isEqualTo(0)
                }
            }
        }
    }

    @Test
    fun `a loss that arrives during a duck ends it, whatever the mode and DAC, and leaves no attenuation behind`() = runTest {
        for (mode in DirectSignalMode.values()) {
            for (dac in Dac.values()) {
                for (end in listOf(DuckFocusEvent.LOSS, DuckFocusEvent.LOSS_TRANSIENT, DuckFocusEvent.GAIN)) {
                    val start = if (dac == Dac.WITH_HARDWARE_VOLUME) 30 else null
                    val host = FakeHost(mode = mode, dacStep = start)
                    val controller = controller(host)

                    send(controller, canDuck, end)

                    // The DAC is where it began; the digital duck is off.
                    assertThat(host.dacStep).isEqualTo(start)
                    assertThat(host.digitalGains.lastOrNull() ?: 1f).isEqualTo(1f)
                    // Purity never reached the samples.
                    if (mode == DirectSignalMode.PRISTINE) assertThat(host.digitalGains).isEmpty()
                }
            }
        }
    }

    @Test
    fun `the Android route keeps its behaviour, whatever the mode and DAC`() = runTest {
        for (mode in DirectSignalMode.values()) {
            for (start in listOf<Int?>(30, null)) {
                val host = FakeHost(direct = false, mode = mode, dacStep = start)
                send(controller(host), canDuck, gain)
                assertThat(host.writes).isEmpty()
                assertThat(host.digitalGains).isEmpty()
                assertThat(host.pauses).isEqualTo(0)
                assertThat(host.dacStep).isEqualTo(start)
            }
        }
    }

    @Test
    fun `two ducks in a row are one duck`() = runTest {
        val host = FakeHost(dacStep = 30)
        val controller = controller(host)

        send(controller, canDuck, canDuck)
        assertThat(host.dacStep).isEqualTo(24)
        assertThat(host.writes).hasSize(6)

        send(controller, gain)
        assertThat(host.dacStep).isEqualTo(30)
    }

    @Test
    fun `a gain that is already queued when the duck would begin means there is no duck at all`() = runTest {
        val host = FakeHost(dacStep = 30)
        val controller = controller(host)

        controller.onFocusEvent(canDuck, ++focusGeneration)
        controller.onFocusEvent(gain, ++focusGeneration)
        settle()

        // Nothing to duck for: the focus is back. (B15: it used to duck down and back up for nothing.)
        assertThat(host.dacStep).isEqualTo(30)
        assertThat(host.writes).isEmpty()
        assertThat(host.pauses).isEqualTo(0)
    }

    // ── The route changes under a duck ─────────────────────────────────────────────────────────

    @Test
    fun `a digital duck whose signal goes PRISTINE does not leave the level to jump, it pauses`() = runTest {
        val host = FakeHost(mode = DirectSignalMode.PROCESSED)
        val controller = controller(host)
        send(controller, canDuck)

        host.mode = DirectSignalMode.PRISTINE
        controller.onRouteChanged()
        settle()

        assertThat(host.digitalGains.last()).isEqualTo(1f)
        assertThat(host.pauses).isEqualTo(1)
    }

    @Test
    fun `a digital duck whose Direct ends is cleared so the next Direct stream does not start ducked`() = runTest {
        val host = FakeHost(mode = DirectSignalMode.PROCESSED)
        val controller = controller(host)
        send(controller, canDuck)

        host.direct = false
        controller.onRouteChanged()
        settle()

        assertThat(host.digitalGains.last()).isEqualTo(1f)
        assertThat(host.pauses).isEqualTo(0)
    }

    @Test
    fun `a hardware duck on a DAC that is gone has nothing to restore and writes nothing`() = runTest {
        val host = FakeHost(dacStep = 30)
        val controller = controller(host)
        send(controller, canDuck)
        val writes = host.writes.size

        host.direct = false
        controller.onRouteChanged()
        settle()
        send(controller, gain)

        assertThat(host.writes.size).isEqualTo(writes)
    }

    // ── The PROCESSED stage: PCM ───────────────────────────────────────────────────────────────

    private val rate = 48_000

    private fun stage(duck: () -> Float, volume: () -> Float = { 1f }, eq: GraphicEqSettings = GraphicEqSettings.BYPASS) =
        DirectPcmStageImpl(
            requirements = { AudioProcessingRequirements(graphicEq = !eq.isBypass) },
            graphicEq = { eq },
            masterVolume = volume,
            duckGain = duck,
        ).also { assertThat(it.configure(rate, 2)).isTrue() }

    private fun block(frames: Int, level: Float = 0.5f) = FloatArray(frames * 2) { level }

    @Test
    fun `the stage glides to the duck over 150 ms, holds exactly -12 dB, and glides back over 400 ms to bit-exact`() {
        var target = 1f
        val stage = stage({ target })
        val frames = rate  // one second blocks
        val input = block(frames)

        // Idle: neutral, the input is untouched.
        val idle = input.copyOf().also { stage.process(it, frames) }
        assertThat(idle).isEqualTo(input)

        // Duck.
        target = DirectDuckPolicy.DUCK_GAIN
        val down = input.copyOf().also { stage.process(it, frames) }
        val fall = rate * 150 / 1000
        assertThat(down[0]).isWithin(1e-3f).of(0.5f)                   // starts where it was
        for (i in 1 until frames) assertThat(down[i * 2]).isAtMost(down[(i - 1) * 2] + 1e-6f)   // monotonic
        val perSample = (1f - DirectDuckPolicy.DUCK_GAIN) * 0.5f / fall
        for (i in 1 until frames) assertThat(abs(down[i * 2] - down[(i - 1) * 2])).isAtMost(perSample * 1.01f)   // a glide, no step
        assertThat(down[(fall + 5) * 2]).isEqualTo(0.5f * DirectDuckPolicy.DUCK_GAIN)
        assertThat(down[(frames - 1) * 2]).isEqualTo(0.5f * DirectDuckPolicy.DUCK_GAIN)
        assertThat(down[1]).isEqualTo(down[0])                          // both channels alike

        // Release.
        target = 1f
        val up = input.copyOf().also { stage.process(it, frames) }
        val rise = rate * 400 / 1000
        for (i in 1 until frames) assertThat(up[i * 2]).isAtLeast(up[(i - 1) * 2] - 1e-6f)
        assertThat(up[(rise + 5) * 2]).isWithin(1e-6f).of(0.5f)

        // Settled at unity: the stage is neutral again, bit for bit.
        val after = input.copyOf().also { stage.process(it, frames) }
        assertThat(after).isEqualTo(input)
    }

    @Test
    fun `the duck composes after the volume and the EQ and never raises either`() {
        val stage = stage({ DirectDuckPolicy.DUCK_GAIN }, volume = { 0.5f })
        val frames = rate
        val out = FloatArray(frames * 2) { sin(2.0 * Math.PI * 1_000.0 * (it / 2) / rate).toFloat() * 0.8f }
        val reference = out.copyOf()

        stage.process(out, frames)

        // Settled: input × 0.5 (volume) × the duck, sample for sample.
        for (i in (rate / 2) until frames) {
            assertThat(out[i * 2]).isWithin(1e-6f).of(reference[i * 2] * 0.5f * DirectDuckPolicy.DUCK_GAIN)
        }
    }

    @Test
    fun `a new stream starts at the listener's duck, not gliding to it`() {
        val stage = stage({ DirectDuckPolicy.DUCK_GAIN })
        val out = block(1_000)

        stage.process(out, 1_000)

        assertThat(out[0]).isEqualTo(0.5f * DirectDuckPolicy.DUCK_GAIN)
        assertThat(out[1_999]).isEqualTo(0.5f * DirectDuckPolicy.DUCK_GAIN)
    }

    @Test
    fun `without a duck the stage is exactly what it was`() {
        val stage = stage({ 1f }, volume = { 0.7f })
        val out = block(480)

        stage.process(out, 480)

        for (sample in out) assertThat(sample).isEqualTo(0.5f * 0.7f)
    }
}
