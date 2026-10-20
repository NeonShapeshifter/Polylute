package com.lostf1sh.pixelplayeross.data.service.player

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneSafetyPolicy
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.volume.DacVolumeArbiter
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumePolicy
import com.lostf1sh.pixelplayeross.data.volume.TemporaryWrite
import com.lostf1sh.pixelplayeross.data.volume.VolumeBackendSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B15 F1 and F4: what happens when something changes *between two steps* of a duck's ramp, and when a focus gain
 * overtakes the duck's pause.
 *
 * `DirectDuckTest` changes things between ramps (a duck, then a key, then the gain). The reviews that found F1 did it
 * inside the ramp, which is where the duck used to carry on blind: its ceiling and the listener's level were read
 * once, before the loop. Here a simulated DAC runs the *real* [DirectDuckController] and the *real* [DacVolumeArbiter]
 * (what `SafetyVolumeController` writes through), and a hook runs just before the n-th step is attempted, which is
 * where a test injects the change.
 *
 * Against the B14 controller (same scenarios, its own interface) 7 of the 8 reproductions failed: the three rows of
 * the review's table, an override ending, the route changing during either ramp, and F4. The one that passed is
 * "the DAC refuses a step", which is here as a regression guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectDuckInterleavingTest {

    private class Sim(
        var direct: Boolean = true,
        var mode: DirectSignalMode = DirectSignalMode.PRISTINE,
        /** Null: a DAC with no hardware volume. */
        var dac: Int? = 30,
        val minStep: Int = 0,
        val maxStep: Int = 40,
    ) : DirectDuckHost {
        val arbiter = DacVolumeArbiter(Any())
        var ceiling: Int? = null

        /**
         * Where the ceiling comes from, asked **both** by the controller (its plan, before the step) and by the arbiter
         * (inside the compare-and-set, at the write): by default the plain `ceiling` field, and a test can point it at the
         * real policy over a profile and an override.
         */
        var ceilingSource: () -> Int? = { ceiling }

        /** The DAC refuses the n-th temporary write (1-based, counting attempts that reach it); 0: never. */
        var refuseAttempt = 0

        /** Every temporary write that reached the DAC. */
        val writes = mutableListOf<Int>()
        private var attempts = 0
        var pauses = 0
        val pauseGenerations = mutableListOf<Long>()
        val digitalGains = mutableListOf<Float>()

        /** Runs just before the n-th (1-based) temporary step is attempted: where a test injects a change. */
        var beforeStep: (Int) -> Unit = {}

        /** Runs inside the blocking read of the DAC, which is where a focus event can arrive while the controller waits. */
        var duringRead: () -> Unit = {}
        var onPause: (Long) -> Unit = {}

        /** The listener or Safety sets the volume through the app (a key, the readout, the ceiling clamp, the fade). */
        fun listenerSets(step: Int) = arbiter.listenerWrite { dac = step }

        /** The DAC's own buttons: no write of ours, nothing to tell the owner. */
        fun dacButton(step: Int) { dac = step }

        override fun directActive() = direct
        override fun signalMode() = mode
        override fun hardwareVolume(): PristineModePolicy.HardwareVolume? {
            duringRead()
            val level = dac ?: return null
            val (ticket, _) = arbiter.observe { level }
            return PristineModePolicy.HardwareVolume(level, minStep, maxStep, 2f, verified = true, ticket = ticket)
        }
        override fun ceilingStep() = ceilingSource()
        override fun setHardwareStepTemporarily(step: Int, ticket: Long, expectedStep: Int): TemporaryWrite {
            beforeStep(++attempts)
            return arbiter.temporaryWrite(ticket, expectedStep, step, ceilingStep = { ceilingSource() }, readStep = { dac }) {
                if (refuseAttempt == attempts) {
                    false
                } else {
                    writes += step
                    dac = step
                    true
                }
            }
        }
        override fun setDigitalDuckGain(gain: Float) { digitalGains += gain }
        override fun pauseForDuck(generation: Long) { pauses++; pauseGenerations += generation; onPause(generation) }
    }

    private var focusGeneration = 0L
    private fun TestScope.controller(host: Sim) = DirectDuckController(host, backgroundScope, 20L, 40L)
    private fun TestScope.settle() { runCurrent(); advanceTimeBy(2_000); runCurrent() }
    private fun TestScope.send(c: DirectDuckController, event: DuckFocusEvent) {
        c.onFocusEvent(event, ++focusGeneration)
        settle()
    }

    private val canDuck = DuckFocusEvent.CAN_DUCK
    private val gain = DuckFocusEvent.GAIN

    // ── F1: the review's table ─────────────────────────────────────────────────────────────────

    @Test
    fun `the listener picks a level in the middle of the restore and it stays there`() = runTest {
        val sim = Sim(); val c = controller(sim)
        send(c, canDuck)
        assertThat(sim.dac).isEqualTo(24)
        val duckWrites = sim.writes.size
        sim.beforeStep = { n -> if (n == duckWrites + 3) sim.listenerSets(5) }

        send(c, gain)

        assertThat(sim.dac).isEqualTo(5)
        assertThat(sim.writes.size).isEqualTo(duckWrites + 2)       // 25, 26 landed; the third step was refused unwritten
        // The duck is over for good: nothing else moves it, now or on a later event.
        send(c, gain)
        assertThat(sim.dac).isEqualTo(5)
    }

    @Test
    fun `Safety Max coming down in the middle of the restore stops the ramp at the new ceiling`() = runTest {
        val sim = Sim(dac = 36); val c = controller(sim)
        send(c, canDuck)
        assertThat(sim.dac).isEqualTo(30)
        val duckWrites = sim.writes.size
        sim.beforeStep = { n -> if (n == duckWrites + 2) sim.ceiling = 32 }

        send(c, gain)

        assertThat(sim.dac).isEqualTo(32)
        assertThat(sim.writes.max()).isAtMost(36)
    }

    @Test
    fun `Safety Max clamping the level in the middle of the restore is a write that ends the duck`() = runTest {
        val sim = Sim(dac = 36); val c = controller(sim)
        send(c, canDuck)
        val duckWrites = sim.writes.size
        // The ceiling falls to 16 and Safety's clamp (a listener-side write) puts the DAC there.
        sim.beforeStep = { n -> if (n == duckWrites + 2) { sim.ceiling = 16; sim.listenerSets(16) } }

        send(c, gain)

        assertThat(sim.dac).isEqualTo(16)
    }

    // ── B16-G2: the ceiling is judged at the write, not at the controller's last reading ────────────

    @Test
    fun `Safety Max set at the level the DAC already holds, between the controller's reading and the write, refuses the next step`() = runTest {
        // The review's exact reproduction: start 36, duck to 30, restore to 31, then Safety Max is set to 31 while the DAC
        // is at 31. The DAC is not above the new ceiling, so nothing clamps and nothing is written: no ticket bumps.
        val sim = Sim(dac = 36); val c = controller(sim)
        send(c, canDuck)
        assertThat(sim.dac).isEqualTo(30)
        val duckWrites = sim.writes.size
        sim.beforeStep = { n -> if (n == duckWrites + 2) sim.ceiling = 31 }   // the controller read "no ceiling"; this is step 32

        send(c, gain)

        assertThat(sim.dac).isEqualTo(31)
        assertThat(sim.writes.drop(duckWrites)).containsExactly(31).inOrder()   // 32 was refused, not written
        send(c, gain)                                                          // nothing is left to give back, and nothing moves it
        assertThat(sim.dac).isEqualTo(31)
    }

    @Test
    fun `an override that ends between the controller's reading and the write puts the ceiling on that very step`() = runTest {
        // While an override stands the ceiling is null (the host reports none); it ends just as step 33 is about to go.
        val sim = Sim(dac = 36); val c = controller(sim)
        send(c, canDuck)
        val duckWrites = sim.writes.size
        var override = true
        val backend = VolumeBackendSnapshot(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, "dac:melody", "Melody", 0, 0, 40, true)
        val safety = HeadphoneSafetyPolicy(
            enabled = true,
            calibrations = mapOf(
                SafetyMaxCalibration.storageKey("dac:melody", SafetyVolumeBackendKind.USB_HARDWARE_VOLUME) to
                    SafetyMaxCalibration("dac:melody", SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, 32, 0, 40, 1L),
            ),
        )
        sim.ceilingSource = { SafetyVolumePolicy.ceilingNow(safety, backend, overrideActive = override) }
        sim.beforeStep = { n -> if (n == duckWrites + 3) override = false }       // attempt 3 is step 33

        send(c, gain)

        assertThat(sim.dac).isEqualTo(32)
        assertThat(sim.writes.max()).isAtMost(36)
        assertThat(sim.writes.drop(duckWrites)).containsExactly(31, 32).inOrder()
    }

    @Test
    fun `a profile change in the middle of the restore puts its Safety Max on the next step`() = runTest {
        // Profile A has no Safety Max; the listener switches to profile B (Safety Max on, ceiling 31) while the DAC is at 31.
        val sim = Sim(dac = 36); val c = controller(sim)
        send(c, canDuck)
        val duckWrites = sim.writes.size
        val backend = VolumeBackendSnapshot(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, "dac:melody", "Melody", 0, 0, 40, true)
        val profileA = HeadphoneSafetyPolicy(enabled = false)
        val profileB = HeadphoneSafetyPolicy(
            enabled = true,
            calibrations = mapOf(
                SafetyMaxCalibration.storageKey("dac:melody", SafetyVolumeBackendKind.USB_HARDWARE_VOLUME) to
                    SafetyMaxCalibration("dac:melody", SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, 31, 0, 40, 1L),
            ),
        )
        var selected = profileA
        sim.ceilingSource = { SafetyVolumePolicy.ceilingNow(selected, backend, overrideActive = false) }
        sim.beforeStep = { n -> if (n == duckWrites + 2) selected = profileB }

        send(c, gain)

        assertThat(sim.dac).isEqualTo(31)
        assertThat(sim.writes.drop(duckWrites)).containsExactly(31).inOrder()
    }

    @Test
    fun `a ceiling that keeps changing under the restore ends it instead of spinning`() = runTest {
        // A host that always answers "above the ceiling" for the step the controller still wants: bounded, and nothing is written.
        val sim = Sim(dac = 36); val c = controller(sim)
        send(c, canDuck)
        val duckWrites = sim.writes.size
        var calls = 0
        // The controller's plan sees no ceiling; the compare-and-set always sees one at the DAC's own level.
        sim.ceilingSource = { if (++calls % 2 == 1) null else (sim.dac ?: 0) - 1 }

        send(c, gain)

        assertThat(sim.writes.size).isEqualTo(duckWrites)
        assertThat(sim.dac).isEqualTo(30)
    }

    @Test
    fun `the listener picks a level in the middle of the duck and neither the ramp nor the restore moves it`() = runTest {
        val sim = Sim(); val c = controller(sim)
        sim.beforeStep = { n -> if (n == 3) sim.listenerSets(5) }

        send(c, canDuck)

        assertThat(sim.dac).isEqualTo(5)
        assertThat(sim.writes).containsExactly(29, 28).inOrder()    // the third step was never written
        assertThat(sim.pauses).isEqualTo(0)                         // the listener's volume stands; no pause either
        send(c, gain)
        assertThat(sim.dac).isEqualTo(5)
        assertThat(sim.writes).hasSize(2)
    }

    @Test
    fun `the DAC's own buttons between two steps are found by the read and left alone`() = runTest {
        val sim = Sim(); val c = controller(sim)
        sim.beforeStep = { n -> if (n == 4) sim.dacButton(12) }

        send(c, canDuck)
        send(c, gain)

        assertThat(sim.dac).isEqualTo(12)
        assertThat(sim.writes).containsExactly(29, 28, 27).inOrder()
    }

    // ── F1: the other interleavings ────────────────────────────────────────────────────────────

    @Test
    fun `an override that ends in the middle of the restore puts the ceiling on the rest of it`() = runTest {
        val sim = Sim(dac = 36); val c = controller(sim)     // an override: the level is above any ceiling and none is reported
        send(c, canDuck)
        assertThat(sim.ceiling).isNull()
        val duckWrites = sim.writes.size
        sim.beforeStep = { n -> if (n == duckWrites + 3) sim.ceiling = 33 }   // it ends: the ceiling applies again

        send(c, gain)

        assertThat(sim.dac).isEqualTo(33)
    }

    @Test
    fun `an override that ends with Safety's fade writing the level ends the duck`() = runTest {
        val sim = Sim(dac = 36); val c = controller(sim)
        send(c, canDuck)
        val duckWrites = sim.writes.size
        sim.beforeStep = { n -> if (n == duckWrites + 3) { sim.ceiling = 20; sim.listenerSets(20) } }

        send(c, gain)

        assertThat(sim.dac).isEqualTo(20)
    }

    @Test
    fun `the route changing in the middle of the restore stops it where it is`() = runTest {
        val sim = Sim(); val c = controller(sim)
        send(c, canDuck)
        val duckWrites = sim.writes.size
        sim.beforeStep = { n -> if (n == duckWrites + 3) sim.direct = false }

        send(c, gain)

        // The route is looked at before each step: the step that was already in flight when it changed may land,
        // and nothing more is attempted.
        assertThat(sim.writes.size).isEqualTo(duckWrites + 3)
        assertThat(sim.dac).isEqualTo(27)
    }

    @Test
    fun `the route changing in the middle of the duck stops it where it is`() = runTest {
        val sim = Sim(); val c = controller(sim)
        sim.beforeStep = { n -> if (n == 3) sim.direct = false }

        send(c, canDuck)

        assertThat(sim.writes).hasSize(3)                           // the step that began as the route changed may land
        assertThat(sim.pauses).isEqualTo(0)
        sim.direct = true
        send(c, gain)                                               // the duck ended with the route: nothing to restore
        assertThat(sim.writes).hasSize(3)
    }

    @Test
    fun `a DAC swapped or reopened under the duck is found by the ticket`() = runTest {
        val sim = Sim(); val c = controller(sim)
        sim.beforeStep = { n -> if (n == 3) sim.arbiter.supersede() }   // SafetyVolumeController: another DAC, or the open guard lowered it

        send(c, canDuck)

        assertThat(sim.writes).hasSize(2)
        send(c, gain)
        assertThat(sim.writes).hasSize(2)
        assertThat(sim.dac).isEqualTo(28)
    }

    @Test
    fun `the DAC refusing a step in the middle of the duck holds a shallower duck that still returns exactly`() = runTest {
        val sim = Sim(); val c = controller(sim)
        sim.refuseAttempt = 4
        send(c, canDuck)
        assertThat(sim.dac).isEqualTo(27)
        assertThat(sim.pauses).isEqualTo(0)

        send(c, gain)

        assertThat(sim.dac).isEqualTo(30)
    }

    @Test
    fun `the DAC refusing a step in the middle of the restore ends the duck there, for good`() = runTest {
        val sim = Sim(); val c = controller(sim)
        send(c, canDuck)
        val duckWrites = sim.writes.size
        sim.refuseAttempt = duckWrites + 3

        send(c, gain)

        assertThat(sim.dac).isEqualTo(26)
        val writes = sim.writes.size
        send(c, gain)                                                // nothing is left to give back
        assertThat(sim.writes.size).isEqualTo(writes)
    }

    @Test
    fun `the DAC refusing the very first step pauses, as for a DAC without volume`() = runTest {
        val sim = Sim(); val c = controller(sim)
        sim.refuseAttempt = 1

        send(c, canDuck)

        assertThat(sim.pauses).isEqualTo(1)
        assertThat(sim.dac).isEqualTo(30)
    }

    @Test
    fun `a ramp never writes above where the duck began, whatever happens to the ceiling`() = runTest {
        val sim = Sim(dac = 30); val c = controller(sim)
        send(c, canDuck)
        sim.ceiling = 40                                            // the ceiling is far above: the old level still caps the way back

        send(c, gain)

        assertThat(sim.writes.max()).isAtMost(30)
        assertThat(sim.dac).isEqualTo(30)
    }

    // ── F4: a gain that arrives while the duck is deciding ─────────────────────────────────────

    /**
     * The engine's focus handling as `DualPlayerEngine` has it (the callback on the main thread, the duck's pause
     * posted to it), with the main looper as a queue the test runs when it chooses. The generation gate is the
     * engine's own class.
     */
    private class SimEngine {
        val sequence = FocusSequence()
        var playing = true
        var isFocusLossPause = false
        var pausedByDuck = false
        val main = ArrayDeque<() -> Unit>()
        lateinit var controller: DirectDuckController

        fun focus(event: DuckFocusEvent) {
            val generation = sequence.next()
            when (event) {
                DuckFocusEvent.GAIN -> {
                    pausedByDuck = false
                    if (isFocusLossPause) { isFocusLossPause = false; playing = true }
                }
                DuckFocusEvent.LOSS_TRANSIENT -> pauseForTransientFocusLoss()
                DuckFocusEvent.LOSS -> { pausedByDuck = false; isFocusLossPause = false; playing = false }
                DuckFocusEvent.CAN_DUCK -> Unit
            }
            controller.onFocusEvent(event, generation)
        }

        fun pauseForFocusDuck(generation: Long) {
            main.addLast {
                if (sequence.isCurrent(generation)) {
                    pauseForTransientFocusLoss()
                    pausedByDuck = isFocusLossPause
                }
            }
        }

        private fun pauseForTransientFocusLoss() {
            isFocusLossPause = playing || pausedByDuck
            playing = false
        }

        fun runMain() { while (main.isNotEmpty()) main.removeFirst()() }
    }

    private fun TestScope.wired(sim: Sim): Pair<SimEngine, DirectDuckController> {
        val engine = SimEngine()
        val c = controller(sim)
        engine.controller = c
        sim.onPause = { engine.pauseForFocusDuck(it) }
        return engine to c
    }

    @Test
    fun `a gain that arrives while the DAC is being read leaves playback playing, on a DAC the duck can only pause`() = runTest {
        val sim = Sim(dac = null)
        val (engine, _) = wired(sim)
        sim.duringRead = { engine.focus(gain) }                       // the read is pending when the gain comes

        engine.focus(canDuck)
        settle()
        engine.runMain()
        settle()

        assertThat(engine.playing).isTrue()
        assertThat(sim.pauses).isEqualTo(0)                            // no pause was even decided
    }

    @Test
    fun `a gain that overtakes the posted pause drops it, so playback is not left paused`() = runTest {
        val sim = Sim(dac = null)
        val (engine, _) = wired(sim)
        // The duck decides to pause; before the main looper runs the post, the gain is handled there.
        sim.onPause = { generation -> engine.pauseForFocusDuck(generation); engine.focus(gain) }

        engine.focus(canDuck)
        settle()
        engine.runMain()                                               // the pause lands after the gain
        settle()

        assertThat(sim.pauses).isEqualTo(1)
        assertThat(engine.playing).isTrue()
        assertThat(engine.isFocusLossPause).isFalse()
    }

    @Test
    fun `the same pause with no gain in between does pause, and the gain then resumes`() = runTest {
        val sim = Sim(dac = null)
        val (engine, _) = wired(sim)

        engine.focus(canDuck)
        settle()
        engine.runMain()
        assertThat(engine.playing).isFalse()
        assertThat(engine.isFocusLossPause).isTrue()

        engine.focus(gain)
        settle()

        assertThat(engine.playing).isTrue()
    }

    @Test
    fun `a gain in the middle of a hardware ramp down stops it where it stands and the way back starts from there`() = runTest {
        val sim = Sim()
        val (engine, _) = wired(sim)
        sim.beforeStep = { n -> if (n == 3) engine.focus(gain) }       // the gain is handled on main while step 3 is in flight

        engine.focus(canDuck)
        settle()
        engine.runMain()

        // Down 29, 28, 27 (the third was already in flight), then straight back up: never to 24, and never over 30.
        assertThat(sim.writes.take(3)).containsExactly(29, 28, 27).inOrder()
        assertThat(sim.writes.drop(3)).containsExactly(28, 29, 30).inOrder()
        assertThat(sim.dac).isEqualTo(30)
        assertThat(sim.writes).doesNotContain(24)
        assertThat(engine.playing).isTrue()
        assertThat(sim.pauses).isEqualTo(0)
    }

    @Test
    fun `a gain queued before the duck begins means there is no duck`() = runTest {
        val sim = Sim()
        val c = controller(sim)
        c.onFocusEvent(canDuck, ++focusGeneration)
        c.onFocusEvent(gain, ++focusGeneration)

        settle()

        assertThat(sim.writes).isEmpty()
        assertThat(sim.pauses).isEqualTo(0)
        assertThat(sim.dac).isEqualTo(30)
    }

    @Test
    fun `a duck that follows an ended one is a duck again`() = runTest {
        val sim = Sim()
        val c = controller(sim)
        c.onFocusEvent(canDuck, ++focusGeneration)
        c.onFocusEvent(gain, ++focusGeneration)
        c.onFocusEvent(canDuck, ++focusGeneration)

        settle()

        assertThat(sim.dac).isEqualTo(24)
        send(c, gain)
        assertThat(sim.dac).isEqualTo(30)
    }

    @Test
    fun `the focus sequence says current only for the newest event`() {
        val sequence = FocusSequence()
        val first = sequence.next()
        assertThat(sequence.isCurrent(first)).isTrue()
        val second = sequence.next()
        assertThat(sequence.isCurrent(first)).isFalse()
        assertThat(sequence.isCurrent(second)).isTrue()
    }
}
