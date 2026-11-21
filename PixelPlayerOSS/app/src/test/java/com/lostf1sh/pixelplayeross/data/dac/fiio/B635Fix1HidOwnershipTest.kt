package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.decent.usbaudio.UsbDeviceOwner
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class B635Fix1HidOwnershipTest {
    private val profile = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val attached = AttachedFiio("test-attachment", "test-id", "test-profile", "DAC", "test-path", profile)
    private val opens = AtomicInteger()

    @Test
    fun `HID waits through a re-open and its settle interval`() = runTest {
        val owner = UsbDeviceOwner(nowMs = { testScheduler.currentTime })
        val facts = owner.exclusive("rebuild") { owner.publishStream(96_000, 1) }
        val access = access(owner, StandardTestDispatcher(testScheduler))
        val recovery = checkNotNull(owner.claimForRecovery(facts.generation, 3))
        try {
            val read = async { access.withSession(attached, {}) { "read" } }
            runCurrent()
            assertThat(opens.get()).isEqualTo(0)
            advanceTimeBy(100)
            runCurrent()
            assertThat(opens.get()).isEqualTo(0)
            recovery.close()
            advanceTimeBy(1_999)
            assertThat(opens.get()).isEqualTo(0)
            advanceTimeBy(51)
            assertThat(read.await()).isEqualTo(HidUse.Ran("read"))
            assertThat(opens.get()).isEqualTo(1)
        } finally {
            recovery.close()
        }
    }

    @Test
    fun `a running HID transfer excludes a recovery until it finishes`() = runBlocking {
        var now = 0L
        val owner = UsbDeviceOwner(nowMs = { now })
        val facts = owner.exclusive("rebuild") { owner.publishStream(96_000, 1) }
        now = 2_001L
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val access = access(owner, dispatcher) {
                entered.countDown()
                check(finish.await(3, TimeUnit.SECONDS))
            }
            val read = async(dispatcher) { access.withSession(attached, {}) { "read" } }
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
                val overlappingRecovery = owner.claimForRecovery(facts.generation, 2)
                overlappingRecovery?.close()
                assertThat(overlappingRecovery).isNull()
            } finally {
                finish.countDown()
            }
            assertThat(read.await()).isEqualTo(HidUse.Ran("read"))
            checkNotNull(owner.claimForRecovery(facts.generation, 2)).use { assertThat(it.commit("reset")).isTrue() }
        }
    }

    private fun access(owner: UsbDeviceOwner, dispatcher: CoroutineDispatcher, onOpen: () -> Unit = {}): DacEqHidAccess {
        val opener = object : FiioHidOpener {
            override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> {
                opens.incrementAndGet()
                onOpen()
                return HidUse.Ran(block(FiioHidSession(FakeFiioDac(profile, activePreset = 0xF0), profile, log)))
            }
        }
        // Feed the owner when supported, so the same regression also runs on the before copy.
        val owned = DacEqHidAccess::class.java.constructors.singleOrNull { it.parameterCount == 4 }
        return owned?.newInstance(opener, dispatcher, owner, { 1L }) as? DacEqHidAccess ?: DacEqHidAccess(opener, dispatcher)
    }
}
