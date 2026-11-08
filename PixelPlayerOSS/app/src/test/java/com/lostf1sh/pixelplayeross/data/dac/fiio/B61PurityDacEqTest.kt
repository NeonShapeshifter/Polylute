package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.DebugFlagStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B61 item 3 (owner 11-07: "the point of Purity is that it locks everything"): **Purity closes the DAC's EQ**, and puts it back exactly as it was when it is turned off.
 *
 * All of it on the fake HID (the Melody's frames as the logs show them); nothing is sent to a real DAC. The writes are the gate's own: turning Purity on is `CLOSE_EQ` (one `select 0xF0`, no band,
 * no gain, no save: the curve stays saved in its slot), turning it off is `APPLY_CURVE` of the remembered curve against the slot that still holds it (a plan of zero steps: the select and the
 * read-back, no band). A slot the app does not write is never selected; a DAC that is not there keeps the memory for its next attach.
 */
class B61PurityDacEqTest {
    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
        scopes.forEach { it.cancel() }
    }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
            HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    private class Flag(var value: Boolean) : DebugFlagStore {
        override fun read() = value
        override fun write(value: Boolean) { this.value = value }
    }

    private val att = AttachedFiio("path-a", "identity-a", "profile-a", "SNOWSKY Melody", "path-a", melody)

    private inner class Rig(selected: Int = 0xF0, writesAllowed: Boolean = true, ownedCurve: Boolean = true) {
        val dac = RecordedMelody.fakeDac(selected, melody)
        val log = mutableListOf<String>()
        val dir = Files.createTempDirectory("purity-dac-eq").toFile().also { dirs += it }
        val attached = MutableStateFlow<AttachedFiio?>(att)
        val controller = DacEqController(attached, MutableStateFlow(true), DacEqHidAccess(FakeOpener(dac), Dispatchers.Unconfined), { 0L }, settleMs = 10L, attachedNow = { attached.value })
        val backups = DacEqBackupStore(File(dir, "backups"))
        val service = DacEqService(
            controller, backups, DacEqWriteSwitch(Flag(writesAllowed), debugBuild = true),
            elapsedMs = { 0L }, wallClockMs = { 1_700_000_000_000L }, pause = {}, log = { log += it },
            playbackActive = { false }, ioDispatcher = Dispatchers.Unconfined,
        )
        val memories = PurityDacEqMemoryStore(File(dir, "backups"))
        val notes = PurityDacEqNotes()
        var purity = PurityDacEq(service, memories, notes, { 1_700_000_000_000L }, { log += it })
        /** The cuts-only curve the owner's USER 3 holds (the real row's pre-gain sense is unconfirmed, so a boost would be refused whatever the DAC does). */
        val curve = DacEqDefaults.flatBands(10).mapIndexed { i, b -> if (i == 3) b.copy(gainTenthsDb = -30) else if (i == 6) b.copy(gainTenthsDb = -15) else b }

        init {
            backups.save(DacEqBackup.of(att, FiioEqReader.read(FiioHidSession(dac, melody)), 1L)!!)
            if (ownedCurve) {
                // the app's slot, taken over and holding a curve, selected: what the owner has on USER 3 when he turns Purity on
                runBlocking { service.screenWriter.takeOver(att, WriteInitiator.USER_ACTION) }
                runBlocking { service.screenWriter.applyCurve(att, curve, WriteInitiator.USER_ACTION) }
            }
        }

        fun mark() = dac.sets.size
        fun setsSince(mark: Int) = dac.sets.drop(mark)
        fun selectsSince(mark: Int) = setsSince(mark).filter { it.first == FiioHidWrites.CMD_PRESET }.map { it.second[0].toInt() and 0xFF }
        fun nonSelectSetsSince(mark: Int) = setsSince(mark).filter { it.first != FiioHidWrites.CMD_PRESET }
        fun slotNow() = dac.slot(0xA2).let { DacEqSlotSnapshot(0xA2, it.preGainTenths, it.bands.toList()) }
        fun reconcile(on: Boolean, attached: AttachedFiio? = att) = runBlocking { purity.reconcile(on, attached, att.backupKey) }
        fun selected(): Int = (FiioEqReader.readPreset(FiioHidSession(dac, melody)) as EqRead.Read).value
    }

    @Test
    fun `Purity on closes the DAC's EQ with one select 0xF0 and writes no band, no gain and no save`() {
        val r = Rig(selected = 0xA2)
        assertThat(r.selected()).isEqualTo(0xA2)
        val before = r.slotNow()
        val mark = r.mark()
        assertThat(r.reconcile(true)).isEqualTo(PurityDacEqOutcome.CLOSED)
        // exactly one write: the select of Close EQ
        assertThat(r.selectsSince(mark)).containsExactly(0xF0)
        assertThat(r.nonSelectSetsSince(mark)).isEmpty()
        // the curve stays saved in its slot, untouched, and the DAC reports Close EQ
        assertThat(r.slotNow()).isEqualTo(before)
        assertThat(r.selected()).isEqualTo(0xF0)
        // what was on is remembered, with the curve (slot 0xA2: the app's own)
        val memory = r.memories.load("profile-a")!!
        assertThat(memory.slotId).isEqualTo(0xA2)
        assertThat(memory.bands).hasSize(10)
        assertThat(memory.bands[3].gainTenthsDb).isEqualTo(-30)
        assertThat(r.log.any { it.contains("PURITY on: closing the DAC's EQ (selected 0xA2") }).isTrue()
    }

    @Test
    fun `Purity off selects the slot again and reads the curve back equal, writing no band`() {
        val r = Rig(selected = 0xA2)
        val before = r.slotNow()
        r.reconcile(true)
        val mark = r.mark()
        assertThat(r.reconcile(false)).isEqualTo(PurityDacEqOutcome.REOPENED)
        assertThat(r.selectsSince(mark)).containsExactly(0xA2)
        assertThat(r.nonSelectSetsSince(mark)).isEmpty()                    // nothing written to the slot: it still held the curve
        assertThat(r.selected()).isEqualTo(0xA2)
        assertThat(r.slotNow()).isEqualTo(before)                           // the curve read back equal
        assertThat(r.memories.load("profile-a")).isNull()                   // put back: nothing left to remember
        assertThat(r.notes.note.value).isNull()
        assertThat(r.log.any { it.contains("PURITY off: putting back slot 0xA2") }).isTrue()
    }

    @Test
    fun `Purity off with the DAC absent sends nothing, says so, and puts the EQ back at the next attach`() {
        val r = Rig(selected = 0xA2)
        val before = r.slotNow()
        r.reconcile(true)
        val mark = r.mark()
        // the DAC is unplugged when Purity goes off
        assertThat(r.reconcile(false, attached = null)).isEqualTo(PurityDacEqOutcome.NOTHING)
        assertThat(r.setsSince(mark)).isEmpty()
        assertThat(r.notes.note.value).isEqualTo(PurityDacEqNote.REOPEN_WHEN_CONNECTED)
        assertThat(r.memories.load("profile-a")).isNotNull()
        // ... and the next attach (Purity is still off) brings it back
        assertThat(r.reconcile(false)).isEqualTo(PurityDacEqOutcome.REOPENED)
        assertThat(r.selectsSince(mark)).containsExactly(0xA2)
        assertThat(r.slotNow()).isEqualTo(before)
        assertThat(r.notes.note.value).isNull()
        assertThat(r.memories.load("profile-a")).isNull()
    }

    @Test
    fun `a DAC whose HID interface is busy at that moment keeps the memory and is tried again`() {
        val r = Rig(selected = 0xA2)
        r.reconcile(true)
        // the HID interface cannot be opened now
        val busy = object : FiioHidOpener {
            override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> = HidUse.Unavailable("the HID interface is busy")
        }
        val busyController = DacEqController(r.attached, MutableStateFlow(true), DacEqHidAccess(busy, Dispatchers.Unconfined), { 0L }, settleMs = 10L, attachedNow = { r.attached.value })
        val busyService = DacEqService(
            busyController, r.backups, DacEqWriteSwitch(Flag(true), debugBuild = true),
            elapsedMs = { 0L }, wallClockMs = { 1_700_000_000_000L }, pause = {}, playbackActive = { false }, ioDispatcher = Dispatchers.Unconfined,
        )
        val mark = r.mark()
        val purityBusy = PurityDacEq(busyService, r.memories, r.notes, { 1_700_000_000_000L }, { r.log += it })
        assertThat(runBlocking { purityBusy.reconcile(false, att, att.backupKey) }).isEqualTo(PurityDacEqOutcome.UNREACHABLE)
        assertThat(r.setsSince(mark)).isEmpty()
        assertThat(r.notes.note.value).isEqualTo(PurityDacEqNote.REOPEN_WHEN_CONNECTED)
        assertThat(r.memories.load("profile-a")).isNotNull()
        // the next attach, the interface free: it is done
        assertThat(r.reconcile(false)).isEqualTo(PurityDacEqOutcome.REOPENED)
        assertThat(r.selected()).isEqualTo(0xA2)
    }

    @Test
    fun `a slot the app does not write is never selected, before or after Purity`() {
        for (unowned in listOf(0xA0, 0xA1, 0x03)) {   // USER 1, USER 2 (not the app's USER 3) and a factory preset
            val r = Rig(selected = 0xF0, ownedCurve = false)
            r.dac.selectOnDevice(unowned)           // the DAC itself (a button on it) has this one selected when Purity is turned on
            val mark = r.mark()
            assertThat(r.reconcile(true)).isEqualTo(PurityDacEqOutcome.CLOSED)
            assertThat(r.selectsSince(mark)).containsExactly(0xF0)
            assertThat(r.memories.load("profile-a")!!.slotId).isEqualTo(unowned)
            assertThat(r.memories.load("profile-a")!!.bands).isEmpty()      // not the app's slot: its curve is not kept as ours
            val afterClose = r.mark()
            assertThat(r.reconcile(false)).isEqualTo(PurityDacEqOutcome.NOT_OURS)
            // nothing sent at all when Purity went off: the unowned preset was never selected
            assertThat(r.setsSince(afterClose)).isEmpty()
            assertThat(r.dac.sets.map { it.first to (it.second[0].toInt() and 0xFF) }.filter { it.first == FiioHidWrites.CMD_PRESET && it.second == unowned }).isEmpty()
            assertThat(r.selected()).isEqualTo(0xF0)
            assertThat(r.notes.note.value).isEqualTo(PurityDacEqNote.NOT_OURS_TO_REOPEN)
            assertThat(r.memories.load("profile-a")).isNull()
        }
    }

    @Test
    fun `an EQ that is closed already is left alone, and nothing is remembered`() {
        val r = Rig(selected = 0xF0, ownedCurve = false)
        val mark = r.mark()
        assertThat(r.reconcile(true)).isEqualTo(PurityDacEqOutcome.ALREADY_CLOSED)
        assertThat(r.setsSince(mark)).isEmpty()
        assertThat(r.memories.load("profile-a")).isNull()
        // Purity off with nothing remembered: nothing to put back, nothing sent
        assertThat(r.reconcile(false)).isEqualTo(PurityDacEqOutcome.NOTHING)
        assertThat(r.setsSince(mark)).isEmpty()
    }

    @Test
    fun `when writes to this DAC are not allowed Purity sends nothing and the page says it could not close the EQ`() {
        val r = Rig(selected = 0xA2, writesAllowed = false, ownedCurve = false)
        r.dac.selectOnDevice(0xA2)
        val mark = r.mark()
        assertThat(r.reconcile(true)).isEqualTo(PurityDacEqOutcome.FAILED)
        assertThat(r.setsSince(mark)).isEmpty()
        assertThat(r.notes.note.value).isEqualTo(PurityDacEqNote.CLOSE_FAILED)
    }

    @Test
    fun `the memory survives a restart of the app and a re-plug of the DAC`() {
        val r = Rig(selected = 0xA2)
        val before = r.slotNow()
        r.reconcile(true)
        // a new process: a new coordinator over the same folder knows what Purity closed
        val restarted = PurityDacEq(r.service, PurityDacEqMemoryStore(File(r.dir, "backups")), PurityDacEqNotes(), { 1_700_000_000_000L }, { r.log += it })
        // Purity is still on at the re-plug: the EQ is closed already, the memory is not replaced
        assertThat(runBlocking { restarted.reconcile(true, att, att.backupKey) }).isEqualTo(PurityDacEqOutcome.ALREADY_CLOSED)
        assertThat(PurityDacEqMemoryStore(File(r.dir, "backups")).load("profile-a")!!.slotId).isEqualTo(0xA2)
        assertThat(runBlocking { restarted.reconcile(false, att, att.backupKey) }).isEqualTo(PurityDacEqOutcome.REOPENED)
        assertThat(r.slotNow()).isEqualTo(before)
        assertThat(r.selected()).isEqualTo(0xA2)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the observer follows the Purity switch and the attached DAC - on closes, off puts back, a DAC plugged later is handled`() = runTest {
        val r = Rig(selected = 0xA2)
        val before = r.slotNow()
        val purityOn = MutableStateFlow(false)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }
        val observer = PurityDacEqObserver(r.purity, purityOn, r.controller)
        // the DAC is not attached yet when the observer starts and Purity is switched on
        r.attached.value = null
        r.controller.start(scope)
        observer.start(scope)
        advanceUntilIdle()
        purityOn.value = true
        advanceUntilIdle()
        assertThat(r.selected()).isEqualTo(0xA2)                  // no DAC attached: nothing closed, nothing sent
        // the DAC is plugged: Purity is on, so its EQ is closed at the attach
        r.attached.value = att
        advanceUntilIdle()
        assertThat(r.selected()).isEqualTo(0xF0)
        // Purity off: put back
        purityOn.value = false
        advanceUntilIdle()
        assertThat(r.selected()).isEqualTo(0xA2)
        assertThat(r.slotNow()).isEqualTo(before)
        assertThat(r.memories.load("profile-a")).isNull()
        scope.cancel()
    }
}
