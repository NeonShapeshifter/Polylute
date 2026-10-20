package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B30-P2: the writer against a fake DAC. What is proved here, in the order of the reviewer's runbook: backup (read-only), roundtrip,
 * one band, unplug and plug back, restore; and the failures: a mismatch restores, a refusal sends nothing.
 */
class DacEqWriterTest {
    private val melody = signedMelody
    private val slot = 0xA2
    private val directories = mutableListOf<File>()

    @AfterEach
    fun cleanUp() = directories.forEach { it.deleteRecursively() }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        var opens = 0
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> {
            opens++
            return HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
        }
    }

    /**
     * @param inspect B30-fix1 H1: the app's slot is looked at by one explicit step before any write (the gate refuses a write to a
     *   slot nobody has looked at), so most rigs begin with that step, and its SETs and published states are cleared: they are not what
     *   the test is about. The tests of the first look itself pass `false`.
     */
    private inner class Rig(
        val dac: FakeFiioDac = FakeFiioDac(melody, activePreset = 0xF0),
        override: Boolean = true,
        profile: FiioHidProfile = melody,
        options: FiioWriteOptions = FiioWriteOptions(),
        inspect: Boolean = true,
    ) {
        val dir: File = Files.createTempDirectory("dac-eq-backups").toFile().also { directories += it }
        val store = DacEqBackupStore(dir)
        val log = mutableListOf<String>()
        val published = mutableListOf<DacEqState>()
        val opener = FakeOpener(dac)
        val att = AttachedFiio("/dev/bus/usb/001/005", "dac-model", "profile-1", "SNOWSKY Melody", "/dev/bus/usb/001/005", profile)
        var overrideOn = override
        val writer = DacEqWriter(
            access = DacEqHidAccess(opener, Dispatchers.Unconfined),
            gate = DacEqWriteGate(options),
            backups = store,
            overrideAllowsWrites = { overrideOn },
            elapsedMs = { 0L },
            wallClockMs = { 1_700_000_000_000L },
            publish = { _, state -> published += state },
            pause = {},
            log = { log += it },
        )

        init {
            if (inspect) {
                check(runBlocking { writer.discoverSlot(att) } is DacEqWriteResult.Done)
                dac.written.clear()
                dac.persistedSlots.clear()
                published.clear()
                log.clear()
            }
        }

        fun backup(replace: Boolean = false) = runBlocking { writer.backup(att, replace) }
        fun discover() = runBlocking { writer.discoverSlot(att) }
        fun apply(bands: List<DacEqBand>) = runBlocking { writer.applyCurve(att, bands, WriteInitiator.REVIEWER_COMMAND) }
        fun setBand(index: Int, gain: Double) = runBlocking { writer.setBand(att, index, gain, WriteInitiator.REVIEWER_COMMAND) }
        fun roundtrip() = runBlocking { writer.roundtrip(att, WriteInitiator.REVIEWER_COMMAND) }
        fun restore() = runBlocking { writer.restore(att, WriteInitiator.REVIEWER_COMMAND) }
        fun close() = runBlocking { writer.closeEq(att, WriteInitiator.USER_ACTION) }
        fun state() = FiioEqReader.read(FiioHidSession(dac, melody))
    }

    private fun curve(vararg gains: Pair<Int, Double>): List<DacEqBand> {
        val map = gains.toMap()
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], map[it] ?: 0.0, 0.71) }
    }

    private val Pair<Int, ByteArray>.preset get() = second[0].toInt() and 0xFF

    // ── Backup: read-only ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the backup reads and stores, and sends nothing to the DAC`() {
        val r = Rig(inspect = false)
        val result = r.backup()
        assertThat(result).isInstanceOf(DacEqBackupResult.Created::class.java)
        assertThat(r.dac.sets).isEmpty()
        assertThat(r.dac.written.all { (it[1].toInt() and 0xFF) == 0xBB }).isTrue()
        val stored = r.store.load("profile-1")!!
        assertThat(stored.activePresetId).isEqualTo(0xF0)
        assertThat(stored.slots.keys).containsExactly(0xF0)
        assertThat(stored.isComplete).isTrue()
        assertThat(r.log.any { it.contains("DAC_HID|STATE") && it.contains("verdict=OFF") }).isTrue()
    }

    @Test
    fun `a backup is never overwritten silently, and a replaced one is kept aside`() {
        val r = Rig(inspect = false)
        r.backup()
        val first = r.store.load("profile-1")!!
        assertThat(r.backup()).isInstanceOf(DacEqBackupResult.Exists::class.java)
        assertThat(r.store.load("profile-1")).isEqualTo(first)
        assertThat(r.backup(replace = true)).isInstanceOf(DacEqBackupResult.Created::class.java)
        assertThat(r.dir.listFiles()!!.map { it.name }.filter { it.endsWith(".json") }).hasSize(2)
    }

    @Test
    fun `a state that could not be read completely is not backed up`() {
        val r = Rig(inspect = false)
        r.dac.dropReplies = true
        assertThat(r.backup()).isInstanceOf(DacEqBackupResult.NotReadable::class.java)
        assertThat(r.store.load("profile-1")).isNull()
    }

    // ── Refusals send nothing ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `without the override an unverified profile is refused and the DAC receives no SET`() {
        val r = Rig(override = false, inspect = false)
        val result = r.apply(curve(3 to 2.0))
        assertThat(result).isEqualTo(DacEqWriteResult.Refused(GateRefusal.PROFILE_NOT_VERIFIED, (result as DacEqWriteResult.Refused).detail))
        assertThat(r.dac.sets).isEmpty()
        assertThat(r.store.load("profile-1")).isNull() // not even a backup is made for a refusal that is not about the backup
    }

    @Test
    fun `a curve that breaks a limit is refused and the DAC receives no SET`() {
        val r = Rig()
        val bad = curve().mapIndexed { i, b -> if (i == 1) b.copy(gainTenthsDb = 150) else b }
        val result = r.apply(bad) as DacEqWriteResult.Refused
        assertThat(result.refusal).isEqualTo(GateRefusal.OUT_OF_LIMITS)
        assertThat(r.dac.sets.filter { it.first != FiioHidWrites.CMD_PRESET }).isEmpty()
    }

    @Test
    fun `an unreadable DAC is refused because the read does not work, and nothing is sent`() {
        val r = Rig()
        r.dac.dropReplies = true
        val result = r.apply(curve(3 to 2.0)) as DacEqWriteResult.Refused
        assertThat(result.refusal).isEqualTo(GateRefusal.READ_NOT_WORKING)
        assertThat(r.dac.sets).isEmpty()
    }

    // ── Apply ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a write on a slot nobody has looked at is refused after a backup and sends no SET at all, the look is its own step`() {
        val r = Rig(inspect = false)
        val result = r.apply(curve(3 to 2.0))
        assertThat(result).isInstanceOf(DacEqWriteResult.Refused::class.java)
        assertThat((result as DacEqWriteResult.Refused).refusal).isEqualTo(GateRefusal.SLOT_NOT_INSPECTED)
        // The backup of what the DAC reads was made (a read), the slot is not in it, and not one SET went out: no select either.
        assertThat(r.store.load("profile-1")!!.slots.keys).containsExactly(0xF0)
        assertThat(r.dac.sets).isEmpty()
        assertThat(r.published).isEmpty()
    }

    @Test
    fun `the look selects the slot, reads it, puts the selection back, and says what the pre-gain was on both presets`() {
        val r = Rig(inspect = false)
        val result = r.discover()
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.store.load("profile-1")!!.slots.keys).containsExactly(0xF0, slot)
        assertThat(r.dac.sets.map { it.first to it.second.toList() }).containsExactly(
            FiioHidWrites.CMD_PRESET to listOf(slot.toByte()), FiioHidWrites.CMD_PRESET to listOf(0xF0.toByte()),
        ).inOrder()
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.log.any { it.contains("DISCOVER selecting slot") && it.contains("audible") }).isTrue()
        assertThat(r.log.any { it.contains("DISCOVER pre-gain on the original preset") }).isTrue()
        // A second look finds it in the backup and selects nothing.
        r.dac.written.clear()
        assertThat(r.discover()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.sets).isEmpty()
    }

    @Test
    fun `the screen cannot ask for the look, only the reviewer's command can`() {
        val r = Rig(inspect = false)
        r.backup()
        val state = r.state()
        val decision = DacEqWriteGate().authorise(
            WriteRequest(WriteKind.PEEK_SLOT, WriteInitiator.USER_ACTION, slot),
            WriteContext(melody, "profile-1", state, 0, r.store.load("profile-1"), true),
        )
        assertThat((decision as GateDecision.Refused).refusal).isEqualTo(GateRefusal.REVIEWER_ONLY)
    }

    @Test
    fun `a write backs up first, selects the known slot, writes the curve, reads it back, and leaves the factory presets alone`() {
        val r = Rig()
        val factoryBefore = (0..6).associateWith { r.dac.slot(it).bands.map { b -> b.copy() } }
        var backupExistedAtFirstSet: Boolean? = null
        r.dac.onWrite = { report ->
            if ((report[1].toInt() and 0xFF) == 0xAA && backupExistedAtFirstSet == null) {
                backupExistedAtFirstSet = r.store.load("profile-1") != null
            }
        }
        val bands = curve(3 to 2.0, 4 to -1.5)
        val result = r.apply(bands)

        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        // The backup was on disk before the first SET left.
        assertThat(backupExistedAtFirstSet).isTrue()
        val backup = r.store.load("profile-1")!!
        assertThat(backup.slots.keys).containsExactly(0xF0, slot)

        // One select, of the slot the backup knows.
        val selects = r.dac.sets.filter { it.first == FiioHidWrites.CMD_PRESET }.map { it.preset }
        assertThat(selects).containsExactly(slot)

        // What the DAC holds now is the curve, with a pre-gain that keeps the net peak at or below 0 dB.
        val held = r.dac.slot(slot)
        assertThat(held.bands.map { it.gainTenthsDb }).containsExactlyElementsIn(bands.map { it.gainTenthsDb }).inOrder()
        assertThat(held.preGainTenths).isLessThan(0)
        assertThat(DacEqCurve.netPeakDb(held.bands, held.preGainTenths)).isAtMost(0.0)
        assertThat(r.dac.activePreset).isEqualTo(slot)
        // Saved into the slot: it survives an unplug.
        assertThat(r.dac.persistedSlots).contains(slot)
        r.dac.replug()
        assertThat(r.dac.slot(slot).bands.map { it.gainTenthsDb }).containsExactlyElementsIn(bands.map { it.gainTenthsDb }).inOrder()
        // Nothing else was touched: not a factory preset, not the other user slots, not "Close EQ".
        for (id in 0..6) assertThat(r.dac.slot(id).bands).isEqualTo(factoryBefore.getValue(id))
        assertThat(r.dac.sets.filter { it.first == FiioHidWrites.CMD_BAND }.size).isEqualTo(2) // only the two bands that differ
        // The label follows: the app published the state it read back.
        assertThat(r.published.single().verdict).isEqualTo(DacEqVerdict.ON)
    }

    @Test
    fun `every SET that reached the DAC is one the gate covered, and none is a volume command`() {
        val r = Rig()
        r.apply(curve(2 to 3.0))
        r.setBand(5, 1.0)
        r.roundtrip()
        r.restore()
        val commands = r.dac.sets.map { it.first }.toSet()
        assertThat(commands).containsAtLeast(0x15, 0x16, 0x17, 0x19)
        assertThat(commands - setOf(0x15, 0x16, 0x17, 0x19)).isEmpty()
        // Selections only ever name the app's slot or a preset the DAC had selected: never a factory preset it was not on.
        assertThat(r.dac.sets.filter { it.first == 0x16 }.map { it.preset }.toSet()).isEqualTo(setOf(slot, 0xF0))
        // Saves only ever name the app's slot.
        assertThat(r.dac.sets.filter { it.first == 0x19 }.map { it.preset }.toSet()).isEqualTo(setOf(slot))
    }

    @Test
    fun `the net peak is at or below 0 dB for what the DAC holds after every kind of curve`() {
        val curves = listOf(curve(0 to 3.0, 1 to 3.0, 2 to 3.0), curve(9 to 6.0), curve(4 to -6.0), curve(0 to 5.0, 9 to 5.0))
        for (bands in curves) {
            val r = Rig()
            assertThat(r.apply(bands)).isInstanceOf(DacEqWriteResult.Done::class.java)
            val held = r.dac.slot(slot)
            assertThat(DacEqCurve.netPeakDb(held.bands, held.preGainTenths)).isAtMost(0.0)
        }
    }

    @Test
    fun `a curve already on the DAC is not written again`() {
        val r = Rig()
        r.apply(curve(3 to 2.0))
        val before = r.dac.sets.size
        val again = r.apply(curve(3 to 2.0))
        assertThat(again).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.sets.size).isEqualTo(before)
    }

    // ── One band (the reviewer's step) ───────────────────────────────────────────────────────────────────

    @Test
    fun `one band changes by its gain, the pre-gain follows, and the read-back agrees`() {
        val r = Rig()
        val result = r.setBand(4, 1.0)
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        val held = r.dac.slot(slot)
        assertThat(held.bands[4].gainTenthsDb).isEqualTo(10)
        assertThat(held.bands.filterIndexed { i, _ -> i != 4 }.all { it.gainTenthsDb == 0 }).isTrue()
        assertThat(held.preGainTenths).isLessThan(0)
        assertThat(DacEqCurve.netPeakDb(held.bands, held.preGainTenths)).isAtMost(0.0)
        // And a minus one dB band needs no pre-gain.
        val cut = r.setBand(4, -1.0)
        assertThat(cut).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.slot(slot).preGainTenths).isEqualTo(0)
    }

    @Test
    fun `a band that does not exist is refused`() {
        val r = Rig()
        assertThat((r.setBand(10, 1.0) as DacEqWriteResult.Refused).refusal).isEqualTo(GateRefusal.OUT_OF_LIMITS)
        assertThat(r.dac.sets).isEmpty()
    }

    // ── Roundtrip and restore ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a roundtrip writes the stored state back unchanged, every frame, and the DAC reads the same`() {
        val r = Rig()
        r.dac.setSlot(slot, 0, curve(3 to 0.0))
        val before = r.dac.slot(slot).copy()
        val result = r.roundtrip()
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        val after = r.dac.slot(slot)
        assertThat(after.preGainTenths).isEqualTo(before.preGainTenths)
        assertThat(after.bands).isEqualTo(before.bands)
        // The whole machinery ran: a gain, all ten bands, a save of the app's slot.
        assertThat(r.dac.sets.count { it.first == 0x15 }).isAtLeast(10)
        assertThat(r.dac.sets.count { it.first == 0x17 }).isAtLeast(1)
        assertThat(r.dac.persistedSlots).contains(slot)
        // And the selection is back where it was.
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
    }

    @Test
    fun `restore writes the backup back, selects what was selected, and compares`() {
        val r = Rig()
        r.dac.setSlot(slot, 0, curve())
        val original = r.dac.slot(slot).copy()
        r.apply(curve(2 to 4.0, 7 to -3.0))
        assertThat(r.dac.activePreset).isEqualTo(slot)
        assertThat(r.dac.slot(slot).bands[2].gainTenthsDb).isEqualTo(40)

        val result = r.restore()
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.slot(slot).bands).isEqualTo(original.bands)
        assertThat(r.dac.slot(slot).preGainTenths).isEqualTo(original.preGainTenths)
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        r.dac.replug()
        assertThat(r.dac.slot(slot).bands).isEqualTo(original.bands)
        assertThat(r.state().verdict).isEqualTo(DacEqVerdict.OFF)
    }

    @Test
    fun `restore without a backup is refused and sends nothing, it does not invent a restore point`() {
        val r = Rig(inspect = false)
        val result = r.restore() as DacEqWriteResult.Refused
        assertThat(result.refusal).isEqualTo(GateRefusal.NO_BACKUP)
        assertThat(r.dac.sets).isEmpty()
        assertThat(r.store.load("profile-1")).isNull()
    }

    // ── Failures ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a mismatched read-back restores the backup and reports it`() {
        val r = Rig()
        r.dac.setSlot(slot, 0, curve())
        val original = r.dac.slot(slot).copy()
        // The DAC silently drops one band's write: what is read back differs from what was asked.
        r.dac.corruptBandOnWrite = 3
        val result = r.apply(curve(3 to 2.0, 5 to 2.0))
        assertThat(result).isInstanceOf(DacEqWriteResult.Mismatch::class.java)
        result as DacEqWriteResult.Mismatch
        assertThat(result.restored).isTrue()
        // The DAC reads as it did before.
        assertThat(r.dac.slot(slot).bands).isEqualTo(original.bands)
        assertThat(r.dac.slot(slot).preGainTenths).isEqualTo(original.preGainTenths)
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.log.any { it.contains("MISMATCH") }).isTrue()
        // The label is told what the DAC reads after the recovery (closed), never that a curve is on.
        assertThat(r.published.map { it.verdict }).containsExactly(DacEqVerdict.OFF)
    }

    @Test
    fun `a DAC that ignores every SET never gets past the select, and nothing is overwritten`() {
        val r = Rig()
        r.dac.ignoreSets = true
        val result = r.apply(curve(3 to 2.0))
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        // Only the pointer was asked to move: no content went out, and the selection is where it was (it never moved).
        assertThat(r.dac.sets.all { it.first == FiioHidWrites.CMD_PRESET }).isTrue()
        assertThat((result as DacEqWriteResult.Failed).restored).isTrue()
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.published.map { it.verdict }).containsExactly(DacEqVerdict.OFF)
    }

    @Test
    fun `a DAC pulled out in the middle is a failure, never an exception, and never a published success`() {
        val r = Rig()
        r.apply(curve(1 to 1.0)) // a first write, so the slot holds a curve
        r.published.clear()
        var sets = 0
        r.dac.onWrite = { report ->
            // The cable comes out after the second SET of the plan: from then on every transfer, a GET too, fails.
            if ((report[1].toInt() and 0xFF) == 0xAA && ++sets == 3) r.dac.failWritesAfter = r.dac.written.size
        }
        val result = r.apply(curve(1 to 2.0, 6 to 2.0, 8 to 2.0))
        assertWithMessage("result $result").that(result is DacEqWriteResult.Failed || result is DacEqWriteResult.Mismatch).isTrue()
        // What the labels are told is what could be read afterwards: with the DAC gone that is nothing complete.
        assertWithMessage("published ${r.published.map { it.verdict to it.isComplete }}").that(r.published.none { it.isComplete }).isTrue()
        assertThat(r.log.any { it.contains("FAILED") || it.contains("MISMATCH") }).isTrue()
    }

    @Test
    fun `an interface that cannot be opened is unavailable and sends nothing`() {
        val r = Rig()
        val unavailable = DacEqWriter(
            access = DacEqHidAccess(object : FiioHidOpener {
                override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
                    HidUse.Unavailable("no USB permission for this DAC")
            }, Dispatchers.Unconfined),
            gate = DacEqWriteGate(), backups = r.store, overrideAllowsWrites = { true }, elapsedMs = { 0 }, wallClockMs = { 1 }, pause = {},
        )
        val result = runBlocking { unavailable.applyCurve(r.att, curve(1 to 1.0), WriteInitiator.USER_ACTION) }
        assertThat(result).isEqualTo(DacEqWriteResult.Unavailable("no USB permission for this DAC"))
        assertThat(r.dac.written).isEmpty()
    }

    // ── Closing the EQ ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `closing the EQ selects the model's Close EQ preset and reads it back`() {
        val r = Rig(FakeFiioDac(melody, activePreset = 0xA2))
        val result = r.close()
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.dac.sets.map { it.first }.toSet()).isEqualTo(setOf(0x16))
        assertThat((result as DacEqWriteResult.Done).state.verdict).isEqualTo(DacEqVerdict.OFF)
        assertThat(r.published.single().verdict).isEqualTo(DacEqVerdict.OFF)
    }

    // ── The two things the sources disagree on ───────────────────────────────────────────────────────────

    @Test
    fun `the check byte the DAC computes is on every SET, and the zero form is available`() {
        val maxim = Rig()
        maxim.dac.requireCrc = FiioCrcMode.MAXIM
        assertThat(maxim.apply(curve(2 to 2.0))).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(maxim.dac.rejectedSetCrcs).isEqualTo(0)

        // A DAC that wants zeros rejects ours: nothing takes hold, and the writer says so instead of claiming a write.
        val strict = Rig()
        strict.dac.requireCrc = FiioCrcMode.ZERO
        assertThat(strict.apply(curve(2 to 2.0))).isNotInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(strict.dac.rejectedSetCrcs).isGreaterThan(0)

        val zero = Rig(options = FiioWriteOptions(crcMode = FiioCrcMode.ZERO))
        zero.dac.requireCrc = FiioCrcMode.ZERO
        assertThat(zero.apply(curve(2 to 2.0))).isInstanceOf(DacEqWriteResult.Done::class.java)
    }

    @Test
    fun `with no save the read-back still passes and an unplug loses the change - the reviewer's experiment`() {
        val r = Rig(options = FiioWriteOptions(saveForm = FiioSaveForm.NONE))
        r.dac.setSlot(slot, 0, curve())
        val result = r.apply(curve(4 to 2.0))
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.sets.map { it.first }).doesNotContain(0x19)
        assertThat(r.dac.persistedSlots).isEmpty()
        assertThat(r.dac.slot(slot).bands[4].gainTenthsDb).isEqualTo(20)
        r.dac.replug()
        // Nothing was saved: the DAC comes back with what it had.
        assertThat(r.dac.slot(slot).bands[4].gainTenthsDb).isEqualTo(0)
        // And the gate covers no save frame in that form, so none could have been sent.
        val permit = (DacEqWriteGate(FiioWriteOptions(saveForm = FiioSaveForm.NONE)).authorise(
            WriteRequest(WriteKind.CLOSE_EQ, WriteInitiator.USER_ACTION),
            WriteContext(melody, "profile-1", r.state(), 0, r.store.load("profile-1"), true),
        ) as GateDecision.Authorised).permit
        assertThat(permit.allows(melody.saveCommand, FiioHidWrites.encodePreset(slot))).isFalse()
        assertThat(permit.allows(melody.saveCommand, ByteArray(0))).isFalse()
    }

    @Test
    fun `the save names the slot, or carries no data when asked`() {
        val named = Rig()
        named.apply(curve(2 to 2.0))
        assertThat(named.dac.sets.filter { it.first == 0x19 }.map { it.second.size }.toSet()).containsExactly(1)

        val empty = Rig(options = FiioWriteOptions(saveForm = FiioSaveForm.EMPTY))
        empty.apply(curve(2 to 2.0))
        assertThat(empty.dac.sets.filter { it.first == 0x19 }.map { it.second.size }.toSet()).containsExactly(0)
        assertThat(empty.dac.persistedSlots).contains(slot) // the fake saves the active slot when the save names none
    }
}
