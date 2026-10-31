package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.UsbDeviceOwner
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Test

/**
 * B48-1: the device's one owner and its generation, on their own (no writer thread, no sink). The scenarios with a writer thread and a recording device are in
 * [B48RecoveryOwnershipTest].
 */
class B48DeviceOwnerTest {

    private val logs = CopyOnWriteArrayList<String>()

    private fun owner(waitMs: Long = 3_000, gone: () -> Boolean = { false }) = UsbDeviceOwner(exclusiveWaitMs = waitMs, deviceGone = gone, log = { logs += it })

    @Test
    fun `a recovery started for an older generation is superseded and says so in one line`() {
        val owner = owner()
        owner.exclusive("configure") { owner.publishStream(44_100, 1) }
        val generation = owner.generation
        owner.exclusive("configure") { owner.publishStream(96_000, 2) }          // the rate change
        assertThat(owner.claimForRecovery(generation, 1)).isNull()
        assertThat(logs.filter { it.startsWith("DAC_RECOVERY") })
            .containsExactly("DAC_RECOVERY step=1 result=superseded gen=$generation now=${owner.generation} by=generation")
    }

    @Test
    fun `a recovery of the current generation reads the rate and alt of its own stream`() {
        val owner = owner()
        owner.exclusive("configure") { owner.publishStream(44_100, 1) }
        val claim = owner.claimForRecovery(owner.generation, 1)!!
        assertThat(claim.facts.sampleRateHz).isEqualTo(44_100)
        assertThat(claim.facts.altSetting).isEqualTo(1)
        // the stream is replaced while the recovery holds the device: its facts are still those of its own generation
        val done = CountDownLatch(1)
        Thread { owner.exclusive("configure") { owner.publishStream(96_000, 2) }; done.countDown() }.start()
        Thread.sleep(50)
        assertThat(claim.facts.sampleRateHz).isEqualTo(44_100)
        assertThat(claim.facts.altSetting).isEqualTo(1)
        claim.close()
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(owner.facts!!.sampleRateHz).isEqualTo(96_000)
    }

    @Test
    fun `a section that arrives during a recovery waits for it and the recovery before its first call is superseded`() {
        val owner = owner()
        owner.exclusive("configure") { owner.publishStream(44_100, 1) }
        val claim = owner.claimForRecovery(owner.generation, 1)!!
        val entered = AtomicBoolean(false)
        val sectionDone = CountDownLatch(1)
        Thread {
            owner.exclusive("configure") { entered.set(true) }
            sectionDone.countDown()
        }.start()
        Thread.sleep(100)
        assertThat(entered.get()).isFalse()                    // it waits for the recovery to let go
        assertThat(claim.commit("setAlt0")).isFalse()          // the recovery had not made a device call: it is superseded
        claim.close()
        assertThat(sectionDone.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(entered.get()).isTrue()
    }

    @Test
    fun `a recovery that has made its first call finishes when a section arrives, and only the DAC leaving stops it`() {
        val owner = owner()
        owner.exclusive("configure") { owner.publishStream(44_100, 1) }
        val claim = owner.claimForRecovery(owner.generation, 2)!!
        assertThat(claim.commit("setAlt0")).isTrue()
        val sectionDone = CountDownLatch(1)
        Thread { owner.exclusive("hand-back") { }; sectionDone.countDown() }.start()
        Thread.sleep(50)
        assertThat(claim.mayTouchDevice("bus-reset")).isTrue()       // the generation moved, but the sequence is begun: it goes on
        assertThat(claim.mayTouchDevice("reconfigure")).isTrue()
        owner.deviceDetached()
        assertThat(claim.mayTouchDevice("level")).isFalse()          // the DAC is gone: nothing more
        assertThat(logs.single { it.startsWith("DAC_RECOVERY step=2") }).contains("by=detached before=level")
        claim.close()
        assertThat(sectionDone.await(2, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `the unplug never waits for a recovery`() {
        val owner = owner()
        owner.exclusive("configure") { owner.publishStream(44_100, 1) }
        val claim = owner.claimForRecovery(owner.generation, 1)!!
        claim.commit("setAlt0")
        val startedNs = System.nanoTime()
        owner.deviceDetached()
        val tookMs = (System.nanoTime() - startedNs) / 1_000_000
        assertThat(tookMs).isLessThan(250)
        assertThat(owner.facts).isNull()
        claim.close()
    }

    @Test
    fun `a detach with nothing in flight is not a new generation and not a log line`() {
        val owner = owner()
        val before = owner.generation
        repeat(5) { owner.deviceDetached() }       // the engine says it on every refresh of the bus while no DAC is there
        assertThat(owner.generation).isEqualTo(before)
        assertThat(logs).isEmpty()
    }

    @Test
    fun `after the bound a section goes on and says it was forced`() {
        val owner = owner(waitMs = 120)
        owner.exclusive("configure") { owner.publishStream(44_100, 1) }
        val claim = owner.claimForRecovery(owner.generation, 1)!!       // never closed: a recovery that hangs
        val ran = AtomicBoolean(false)
        val startedNs = System.nanoTime()
        owner.exclusive("hand-back") { ran.set(true) }
        val waitedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertThat(ran.get()).isTrue()
        assertThat(waitedMs).isAtLeast(100)
        assertThat(waitedMs).isLessThan(1_500)
        assertThat(logs.single { it.startsWith("OWNER_FORCED") }).contains("who=hand-back")
        claim.close()
    }

    @Test
    fun `sections nest on one thread and a section on another thread waits for the first`() {
        val owner = owner()
        val order = CopyOnWriteArrayList<String>()
        val inner = CountDownLatch(1)
        val second = Thread {
            inner.await()
            owner.exclusive("release") { order += "second" }
        }
        second.start()
        owner.exclusive("configure") {
            owner.exclusive("hand-back") { order += "nested" }     // a failed claim hands back from inside the configure
            inner.countDown()
            Thread.sleep(80)
            order += "first-end"
        }
        second.join(2_000)
        assertThat(order).containsExactly("nested", "first-end", "second").inOrder()
    }

    @Test
    fun `a stream that was handed back or released has no facts to recover`() {
        val owner = owner()
        owner.exclusive("configure") { owner.publishStream(44_100, 1) }
        val generation = owner.generation
        owner.exclusive("release") { owner.clearStream() }
        assertThat(owner.claimForRecovery(generation, 1)).isNull()
        assertThat(owner.claimForRecovery(owner.generation, 1)).isNull()
    }
}
