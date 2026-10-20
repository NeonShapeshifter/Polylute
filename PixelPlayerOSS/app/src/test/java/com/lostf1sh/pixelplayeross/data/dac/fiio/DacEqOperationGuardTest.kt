package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B30-fix1 H2: what the writer does with the caller's guard. The guard (the screen's "is this edit still the one that was asked for")
 * is asked **after the HID lock is won**, and before the interface is opened, and at every stage until the first SET: a caller that
 * waited behind another session finds out there. Once a SET has gone out the operation finishes or restores: it is never cancelled half
 * written.
 */
class DacEqOperationGuardTest {
    private val melody = signedMelody
    private val slot = 0xA2
    private val dirs = mutableListOf<File>()

    @AfterEach
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun curve(vararg gains: Pair<Int, Double>): List<DacEqBand> {
        val map = gains.toMap()
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], map[it] ?: 0.0, 0.71) }
    }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        var opens = 0
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> {
            opens++
            return HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
        }
    }

    private inner class Rig(path: String? = "path-a") {
        val dac = FakeFiioDac(melody)
        val opener = FakeOpener(dac)
        val log = mutableListOf<String>()
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-guard").toFile().also { dirs += it })
        val att = AttachedFiio("path-a", "identity-a", "profile-a", "Melody A", path, melody)
        val access = DacEqHidAccess(opener, Dispatchers.Unconfined)
        val writer = DacEqWriter(access, DacEqWriteGate(), store, { true }, { 0L }, { 1L }, pause = {}, log = { log += it })

        init {
            val backup = DacEqBackup.of(att, FiioEqReader.read(FiioHidSession(dac, melody)), 1L)!!
            store.save(backup)
            store.update(backup.withSlot(dac.slot(slot).let { DacEqSlotSnapshot(slot, it.preGainTenths, it.bands.toList()) }))
            opener.opens = 0
            dac.written.clear()
        }
    }

    @Test
    fun `a caller that waited for the HID lock is asked again once it has it, and a changed answer ends it with nothing sent and nothing opened`() = runBlocking {
        val r = Rig()
        r.access.lock.lock()                      // another session is running
        val changed = java.util.concurrent.atomic.AtomicBoolean(false)
        val waiting = async(Dispatchers.Default) {
            r.writer.applyCurve(r.att, curve(4 to 2.0), WriteInitiator.USER_ACTION) { if (changed.get()) "the DAC was changed" else null }
        }
        delay(100)
        changed.set(true)                         // while it waited
        r.access.lock.unlock()
        val result = waiting.await()
        assertThat(result).isEqualTo(DacEqWriteResult.Refused(GateRefusal.EDIT_OUTDATED, "the DAC was changed"))
        assertThat(r.dac.written).isEmpty()       // not even a GET
        assertThat(r.opener.opens).isEqualTo(0)   // the interface was never opened
    }

    @Test
    fun `a guard that still says go lets the operation through`() {
        val r = Rig()
        val result = runBlocking { r.writer.applyCurve(r.att, curve(4 to 2.0), WriteInitiator.USER_ACTION) { null } }
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
    }

    @Test
    fun `a guard that says stop only after the first SET does not cancel the operation half written`() {
        val r = Rig()
        var asked = 0
        // After the select has gone out the answer flips: the operation is under way, so it finishes, and the pointer, pre-gain and bands all land.
        val result = runBlocking {
            r.writer.applyCurve(r.att, curve(4 to 2.0), WriteInitiator.USER_ACTION) { if (r.dac.sets.isEmpty()) null.also { asked++ } else "too late" }
        }
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(asked).isAtLeast(1)
        assertThat(r.dac.slot(slot).bands[4].gainTenthsDb).isEqualTo(20)
    }

    @Test
    fun `a guard is asked at every gate question until the first SET, not only at the start`() {
        val r = Rig()
        var calls = 0
        runBlocking { r.writer.applyCurve(r.att, curve(4 to 2.0), WriteInitiator.USER_ACTION) { calls++; null } }
        // once after the lock, and once for each question the gate was asked before the select went out.
        assertThat(calls).isAtLeast(2)
    }

    @Test
    fun `an attachment with no connection path is never written, there is nothing to tell its DAC from another`() {
        val r = Rig(path = null)
        val result = runBlocking { r.writer.applyCurve(r.att, curve(4 to 2.0), WriteInitiator.USER_ACTION) }
        assertThat(result).isInstanceOf(DacEqWriteResult.Unavailable::class.java)
        assertThat(r.opener.opens).isEqualTo(0)
        assertThat(r.dac.written).isEmpty()
    }
}
