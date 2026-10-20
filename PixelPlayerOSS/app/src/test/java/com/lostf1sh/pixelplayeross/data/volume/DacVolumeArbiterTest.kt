package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/** B15-F1: the one gate to the DAC's volume. */
class DacVolumeArbiterTest {

    private class Dac(var step: Int = 30) {
        val writes = mutableListOf<Int>()
        fun write(to: Int): Boolean { writes += to; step = to; return true }
    }

    @Test
    fun `a temporary step is written while nobody else has written and the DAC is where the duck left it`() {
        val arbiter = DacVolumeArbiter(Any())
        val dac = Dac()
        val (ticket, _) = arbiter.observe { dac.step }

        val first = arbiter.temporaryWrite(ticket, 30, 29, { null }, { dac.step }) { dac.write(29) }
        val second = arbiter.temporaryWrite(ticket, 29, 28, { null }, { dac.step }) { dac.write(28) }

        assertThat(first).isEqualTo(TemporaryWrite.WRITTEN)
        assertThat(second).isEqualTo(TemporaryWrite.WRITTEN)
        assertThat(dac.writes).containsExactly(29, 28).inOrder()
    }

    @Test
    fun `a listener write after the duck read the DAC supersedes it and the step is not written`() {
        val arbiter = DacVolumeArbiter(Any())
        val dac = Dac()
        val (ticket, _) = arbiter.observe { dac.step }
        arbiter.listenerWrite { dac.write(5) }

        val result = arbiter.temporaryWrite(ticket, 30, 29, { null }, { dac.step }) { dac.write(29) }

        assertThat(result).isEqualTo(TemporaryWrite.SUPERSEDED)
        assertThat(dac.writes).containsExactly(5)
        assertThat(dac.step).isEqualTo(5)
    }

    @Test
    fun `a listener write that lands on the very level the duck holds still supersedes it`() {
        val arbiter = DacVolumeArbiter(Any())
        val dac = Dac(24)
        val (ticket, _) = arbiter.observe { dac.step }
        arbiter.listenerWrite { dac.write(24) }                       // the listener chose the level the duck happens to be at

        val result = arbiter.temporaryWrite(ticket, 24, 25, { null }, { dac.step }) { dac.write(25) }

        assertThat(result).isEqualTo(TemporaryWrite.SUPERSEDED)
        assertThat(dac.step).isEqualTo(24)
    }

    @Test
    fun `a DAC that reads anywhere but where the duck left it supersedes the step, whoever moved it`() {
        val arbiter = DacVolumeArbiter(Any())
        val dac = Dac()
        val (ticket, _) = arbiter.observe { dac.step }
        dac.step = 12                                                  // its own buttons

        assertThat(arbiter.temporaryWrite(ticket, 30, 29, { null }, { dac.step }) { dac.write(29) }).isEqualTo(TemporaryWrite.SUPERSEDED)
        assertThat(arbiter.temporaryWrite(ticket, 30, 29, { null }, { null }) { dac.write(29) }).isEqualTo(TemporaryWrite.SUPERSEDED)
        assertThat(dac.writes).isEmpty()
    }

    @Test
    fun `a DAC that will not take the step is REFUSED, not superseded`() {
        val arbiter = DacVolumeArbiter(Any())
        val (ticket, _) = arbiter.observe { 30 }

        val result = arbiter.temporaryWrite(ticket, 30, 29, { null }, { 30 }) { false }

        assertThat(result).isEqualTo(TemporaryWrite.REFUSED)
    }

    @Test
    fun `supersede ends outstanding ducks without a write`() {
        val arbiter = DacVolumeArbiter(Any())
        val (ticket, _) = arbiter.observe { 30 }
        arbiter.supersede()

        assertThat(arbiter.temporaryWrite(ticket, 30, 29, { null }, { 30 }) { true }).isEqualTo(TemporaryWrite.SUPERSEDED)
    }

    @Test
    fun `a fresh observation after a listener write is a fresh duck`() {
        val arbiter = DacVolumeArbiter(Any())
        arbiter.listenerWrite { }
        val (ticket, _) = arbiter.observe { 30 }

        assertThat(arbiter.temporaryWrite(ticket, 30, 29, { null }, { 30 }) { true }).isEqualTo(TemporaryWrite.WRITTEN)
    }

    @Test
    fun `a listener write waits for the duck step that is on the wire, they never interleave`() {
        val arbiter = DacVolumeArbiter(Any())
        val (ticket, _) = arbiter.observe { 30 }
        val inStep = CountDownLatch(1)
        val releaseStep = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())

        val duck = Thread {
            arbiter.temporaryWrite(ticket, 30, 29, { null }, { 30 }) {
                order += "duck:start"
                inStep.countDown()
                releaseStep.await(5, TimeUnit.SECONDS)
                order += "duck:end"
                true
            }
        }
        val listener = Thread { arbiter.listenerWrite { order += "listener" } }

        duck.start()
        assertThat(inStep.await(5, TimeUnit.SECONDS)).isTrue()
        listener.start()
        listener.join(200)
        assertThat(listener.isAlive).isTrue()                          // blocked behind the step in flight
        releaseStep.countDown()
        duck.join(5_000)
        listener.join(5_000)

        assertThat(order).containsExactly("duck:start", "duck:end", "listener").inOrder()
    }

    // ── B16-G2: the ceiling inside the compare-and-set ─────────────────────────────────────────

    @Test
    fun `a step that raises the DAC above the ceiling is refused without a write`() {
        val arbiter = DacVolumeArbiter(Any())
        val dac = Dac(31)
        val (ticket, _) = arbiter.observe { dac.step }

        val result = arbiter.temporaryWrite(ticket, 31, 32, { 31 }, { dac.step }) { dac.write(32) }

        assertThat(result).isEqualTo(TemporaryWrite.ABOVE_CEILING)
        assertThat(dac.writes).isEmpty()
        assertThat(dac.step).isEqualTo(31)
    }

    @Test
    fun `a step up to the ceiling itself is written, and so is any step when none is in force`() {
        val arbiter = DacVolumeArbiter(Any())
        val dac = Dac(30)
        val (ticket, _) = arbiter.observe { dac.step }

        assertThat(arbiter.temporaryWrite(ticket, 30, 31, { 31 }, { dac.step }) { dac.write(31) }).isEqualTo(TemporaryWrite.WRITTEN)
        assertThat(arbiter.temporaryWrite(ticket, 31, 32, { null }, { dac.step }) { dac.write(32) }).isEqualTo(TemporaryWrite.WRITTEN)
        assertThat(dac.writes).containsExactly(31, 32).inOrder()
    }

    @Test
    fun `a step down is written whatever the ceiling says, the ceiling is not even asked`() {
        val arbiter = DacVolumeArbiter(Any())
        val dac = Dac(36)
        val (ticket, _) = arbiter.observe { dac.step }
        var asked = false

        val result = arbiter.temporaryWrite(ticket, 36, 35, { asked = true; 20 }, { dac.step }) { dac.write(35) }

        assertThat(result).isEqualTo(TemporaryWrite.WRITTEN)
        assertThat(asked).isFalse()
    }

    @Test
    fun `the ceiling is read inside the lock, after the DAC was read and right before the write`() {
        val lock = Any()
        val arbiter = DacVolumeArbiter(lock)
        val dac = Dac(30)
        val (ticket, _) = arbiter.observe { dac.step }
        val order = mutableListOf<String>()

        arbiter.temporaryWrite(
            ticket, 30, 31,
            { order += "ceiling:held=${Thread.holdsLock(lock)}"; 40 },
            { order += "read:held=${Thread.holdsLock(lock)}"; dac.step },
        ) { order += "write:held=${Thread.holdsLock(lock)}"; dac.write(31) }

        assertThat(order).containsExactly("read:held=true", "ceiling:held=true", "write:held=true").inOrder()
    }

    @Test
    fun `a superseded duck is told so, not that it is above the ceiling`() {
        val arbiter = DacVolumeArbiter(Any())
        val dac = Dac(31)
        val (ticket, _) = arbiter.observe { dac.step }
        arbiter.listenerWrite { dac.write(5) }

        val result = arbiter.temporaryWrite(ticket, 31, 32, { 31 }, { dac.step }) { dac.write(32) }

        assertThat(result).isEqualTo(TemporaryWrite.SUPERSEDED)
    }
}
