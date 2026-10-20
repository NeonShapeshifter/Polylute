package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test

/**
 * B16-G1: the lock order between the volume arbiter and the USB device.
 *
 * `FakeDevice` is the shape of `UsbAudioDevice` that matters here: its volume methods are `@Synchronized` (the instance
 * monitor), and its `reconfigure` is `restoreLevelAfterReconfigure`: it takes that monitor and, still holding it, calls
 * the volume guard the app installed, which tells the arbiter (`SafetyVolumeController.hardwareOpenTarget` →
 * `dacVolume.supersede()`) before the device lowers the level. The arbiter is the real [DacVolumeArbiter], built on the
 * device's monitor as the controller builds it.
 *
 * B15's arbiter had a lock of its own, taken *before* the device's (a volume write) while the guard took it *after*
 * (the reconfigure): a volume key at the moment of a rate change deadlocked. `LegacyTwoLockArbiter` below is that design,
 * verbatim in its locking, to show these tests do catch it: **the same interleaving hangs it** (and the JVM's own
 * deadlock detector names both threads).
 */
class DacVolumeLockOrderTest {

    private class FakeDevice(var level: Int = 30) {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** What the app installed as `UsbAudioDevice.volumeGuard`: runs under the monitor, answers the level to lower to. */
        var guard: () -> Int? = { null }

        @Synchronized
        fun read(): Int = level

        @Synchronized
        fun write(step: Int): Int {
            level = step
            log += "write:$step"
            return level
        }

        /** `restoreLevelAfterReconfigure`: the monitor, then the guard (under it), then the lowering. */
        @Synchronized
        fun reconfigure(whileHeld: () -> Unit = {}) {
            whileHeld()
            val target = guard()
            if (target != null && target < level) {
                level = target
                log += "lower:$target"
            } else {
                log += "reconfigure"
            }
        }
    }

    /** B15's design: a private lock, entered before the device's. Locking only; the counter logic is the same. */
    private class LegacyTwoLockArbiter {
        private val lock = Any()
        private var listenerWrites = 0L
        fun <T> listenerWrite(write: () -> T): T = synchronized(lock) { listenerWrites++; write() }
        fun supersede() { synchronized(lock) { listenerWrites++ } }
    }

    private fun deadlockedNames(): List<String> {
        val bean = ManagementFactory.getThreadMXBean()
        val ids = bean.findDeadlockedThreads() ?: return emptyList()
        return bean.getThreadInfo(ids).map { it.threadName }
    }

    private fun Thread.daemonNamed(name: String) = apply { isDaemon = true; this.name = name }

    /** Joins [threads]; fails if one is still alive, naming the ones the JVM sees deadlocked (only these, not a leaked legacy pair). */
    private fun joinAll(vararg threads: Thread, ms: Long = 10_000) {
        threads.forEach { it.join(ms) }
        val stuck = threads.filter { it.isAlive }.map { it.name }
        assertWithMessage("threads still running: $stuck, deadlocked: ${deadlockedNames().filter { it in stuck }}").that(stuck).isEmpty()
    }

    // ── the interleaving that hung B15, forced ─────────────────────────────────────────────────

    @Test
    fun `a volume write and a reconfigure that meet in the middle do not deadlock`() {
        val device = FakeDevice()
        val arbiter = DacVolumeArbiter(device)
        device.guard = { arbiter.supersede(); 20 }
        val inWriter = CountDownLatch(1)
        val inReconfigure = CountDownLatch(1)

        // Each waits (briefly) for the other to be inside its own critical section. With one lock only one can be.
        val writer = Thread {
            arbiter.listenerWrite {
                inWriter.countDown()
                inReconfigure.await(300, TimeUnit.MILLISECONDS)
                device.write(5)
            }
        }.daemonNamed("listener-write")
        val reconfigure = Thread {
            device.reconfigure {
                inReconfigure.countDown()
                inWriter.await(300, TimeUnit.MILLISECONDS)
            }
        }.daemonNamed("reconfigure")

        writer.start(); reconfigure.start()
        joinAll(writer, reconfigure)

        // Whoever went second saw the result of the first: the write then a reconfigure with nothing left to lower, or the
        // lowering and then the listener's own level on top of it.
        val log = device.log.toList()
        assertThat(log == listOf("write:5", "reconfigure") || log == listOf("lower:20", "write:5")).isTrue()
    }

    @Test
    fun `the same interleaving hangs B15's two-lock design, and the harness sees it`() {
        val device = FakeDevice()
        val arbiter = LegacyTwoLockArbiter()
        device.guard = { arbiter.supersede(); 20 }
        val inArbiter = CountDownLatch(1)
        val inDevice = CountDownLatch(1)

        val writer = Thread {
            arbiter.listenerWrite {
                inArbiter.countDown()
                inDevice.await(5, TimeUnit.SECONDS)
                device.write(5)
            }
        }.daemonNamed("legacy-listener-write")
        val reconfigure = Thread {
            device.reconfigure {
                inDevice.countDown()
                inArbiter.await(5, TimeUnit.SECONDS)
            }
        }.daemonNamed("legacy-reconfigure")

        writer.start(); reconfigure.start()
        writer.join(2_000); reconfigure.join(2_000)

        assertThat(writer.isAlive).isTrue()
        assertThat(reconfigure.isAlive).isTrue()
        assertThat(deadlockedNames()).containsAtLeast("legacy-listener-write", "legacy-reconfigure")
        // (Both threads stay blocked until the JVM exits: they are daemons, and a monitor wait cannot be interrupted.)
    }

    @Test
    fun `a duck step and a reconfigure that meet in the middle do not deadlock`() {
        val device = FakeDevice(30)
        val arbiter = DacVolumeArbiter(device)
        device.guard = { arbiter.supersede(); 20 }
        val (ticket, _) = arbiter.observe { device.read() }
        val inDuck = CountDownLatch(1)
        val inReconfigure = CountDownLatch(1)
        var result: TemporaryWrite? = null

        val duck = Thread {
            result = arbiter.temporaryWrite(ticket, 30, 29, { null }, { device.read() }) {
                inDuck.countDown()
                inReconfigure.await(300, TimeUnit.MILLISECONDS)
                device.write(29)
                true
            }
        }.daemonNamed("duck-step")
        val reconfigure = Thread {
            device.reconfigure {
                inReconfigure.countDown()
                inDuck.await(300, TimeUnit.MILLISECONDS)
            }
        }.daemonNamed("reconfigure")

        duck.start(); reconfigure.start()
        joinAll(duck, reconfigure)

        // Either the step went first (and the reconfigure lowered the level under it), or the reconfigure did and the
        // step is found to be stale. Never a step written on top of the lowering.
        assertThat(device.level).isEqualTo(20)
        if (result == TemporaryWrite.SUPERSEDED) assertThat(device.log).doesNotContain("write:29")
    }

    @Test
    fun `an observe and a reconfigure that meet in the middle do not deadlock`() {
        val device = FakeDevice(30)
        val arbiter = DacVolumeArbiter(device)
        device.guard = { arbiter.supersede(); 20 }
        val inObserve = CountDownLatch(1)
        val inReconfigure = CountDownLatch(1)

        val observer = Thread {
            arbiter.observe {
                inObserve.countDown()
                inReconfigure.await(300, TimeUnit.MILLISECONDS)
                device.read()
            }
        }.daemonNamed("observe")
        val reconfigure = Thread {
            device.reconfigure {
                inReconfigure.countDown()
                inObserve.await(300, TimeUnit.MILLISECONDS)
            }
        }.daemonNamed("reconfigure")

        observer.start(); reconfigure.start()
        joinAll(observer, reconfigure)
    }

    @Test
    fun `the volume guard may tell the arbiter and write through it while the driver holds its monitor`() {
        val device = FakeDevice(30)
        val arbiter = DacVolumeArbiter(device)
        device.guard = {
            // The monitor is held here (this is inside `reconfigure`): re-entering through the arbiter must just work.
            assertThat(Thread.holdsLock(device)).isTrue()
            arbiter.supersede()
            arbiter.listenerWrite { 1 }
            20
        }
        val thread = Thread { device.reconfigure() }.daemonNamed("reconfigure")

        thread.start()
        joinAll(thread)

        assertThat(device.level).isEqualTo(20)
    }

    // ── consistency of the final state ─────────────────────────────────────────────────────────

    @Test
    fun `a duck step is never written on top of a reconfigure's lowering`() {
        repeat(300) { round ->
            val device = FakeDevice(30)
            val arbiter = DacVolumeArbiter(device)
            device.guard = { arbiter.supersede(); 20 }
            val (ticket, _) = arbiter.observe { device.read() }
            var result: TemporaryWrite? = null
            val start = CountDownLatch(1)

            val duck = Thread {
                start.await()
                result = arbiter.temporaryWrite(ticket, 30, 29, { null }, { device.read() }) { device.write(29); true }
            }.daemonNamed("duck-$round")
            val reconfigure = Thread { start.await(); device.reconfigure() }.daemonNamed("reconfigure-$round")
            duck.start(); reconfigure.start(); start.countDown()
            joinAll(duck, reconfigure)

            val log = device.log.toList()
            assertThat(device.level).isEqualTo(20)                       // the guard's lowering is the last word, always
            when (result) {
                TemporaryWrite.WRITTEN -> assertThat(log.indexOf("write:29")).isLessThan(log.indexOf("lower:20"))
                TemporaryWrite.SUPERSEDED -> assertThat(log).doesNotContain("write:29")
                else -> error("unexpected $result")
            }
        }
    }

    @Test
    fun `reconfigures, listener writes and duck steps on three threads finish, and the listener's last write stands`() {
        val device = FakeDevice(30)
        val arbiter = DacVolumeArbiter(device)
        device.guard = { arbiter.supersede(); null }                       // the guard may or may not lower; here it only tells
        val rounds = 3_000
        val duckWrites = AtomicInteger()
        val duckSteps = AtomicInteger()

        val reconfigure = Thread { repeat(rounds) { device.reconfigure() } }.daemonNamed("reconfigure")
        val listener = Thread {
            repeat(rounds) { arbiter.listenerWrite { device.write(10 + it % 20) } }
        }.daemonNamed("listener")
        val duck = Thread {
            repeat(rounds) {
                val (ticket, level) = arbiter.observe { device.read() }
                duckSteps.incrementAndGet()
                val result = arbiter.temporaryWrite(ticket, level, level - 1, { null }, { device.read() }) {
                    device.write(level - 1); true
                }
                if (result == TemporaryWrite.WRITTEN) duckWrites.incrementAndGet()
            }
        }.daemonNamed("duck")

        reconfigure.start(); listener.start(); duck.start()
        joinAll(reconfigure, listener, duck, ms = 30_000)

        // Everything ran to the end. And the ordering rules held on the wire: a duck write happens only when its ticket
        // was still current, so a listener write that follows it in the log is the last word on the level.
        assertThat(duckSteps.get()).isEqualTo(rounds)
        arbiter.listenerWrite { device.write(7) }
        val (staleTicket, _) = arbiter.observe { device.read() }
        arbiter.listenerWrite { device.write(8) }                          // a write after the duck's observation
        val late = arbiter.temporaryWrite(staleTicket, 7, 6, { null }, { device.read() }) { device.write(6); true }
        assertThat(late).isEqualTo(TemporaryWrite.SUPERSEDED)
        assertThat(device.level).isEqualTo(8)
    }
}
