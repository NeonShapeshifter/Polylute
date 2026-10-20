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
 * B31 P1.3 / P1.4 / P1.5 / P1.2: **taking USER 3 over, giving its factory curve back, reading every preset, Close EQ's zero bands, and the
 * listening check**, against a fake Melody that holds what the owner's held: USER 3 with ten AutoEQ-shaped bands and a register of +8.0 dB,
 * "Close EQ" with ten bands that read all zeros and a register of +2.0 dB ([RecordedMelody]).
 *
 * Every state the DAC is in after a transfer is read (the fake's `afterSet`) under **each** reading of the pre-gain's sense and kept
 * against the ceiling the level rule gives it.
 */
class DacEqTakeOverTest {
    private val real = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val slot = 0xA2
    private val dirs = mutableListOf<File>()

    @AfterEach
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun curve(vararg gains: Pair<Int, Double>): List<DacEqBand> {
        val map = gains.toMap()
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, DacEqDefaults.FREQUENCIES[it], map[it] ?: 0.0, 0.71) }
    }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
            HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    /**
     * @param inspected the backup already holds USER 3's factory curve (a look, an inventory); false: only the preset that was selected
     * @param profile the row the app reads (its pre-gain sense is the point of several tests)
     */
    private inner class Rig(
        val profile: FiioHidProfile = real,
        startPreset: Int = 0xF0,
        var playing: Boolean = false,
        inspected: Boolean = false,
        val factory: Boolean = true,
    ) {
        val dac = if (factory) RecordedMelody.fakeDac(startPreset, profile) else FakeFiioDac(profile, activePreset = startPreset)
        val log = mutableListOf<String>()
        val pauses = mutableListOf<Long>()
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-takeover").toFile().also { dirs += it })
        val att = AttachedFiio("path-a", "identity-a", "profile-a", "Melody A", "path-a", profile)
        val published = mutableListOf<DacEqState>()
        val invalidated = mutableListOf<String>()
        val writer = DacEqWriter(
            DacEqHidAccess(FakeOpener(dac), Dispatchers.Unconfined), DacEqWriteGate(), store, { true }, { 0L }, { 1_700_000_000_000L },
            publish = { _, state -> published += state }, pause = { pauses += it }, log = { log += it },
            invalidate = { _, why -> invalidated += why },
            playbackActive = { playing },
        )

        /** For each reading of the sense, the level of the app's slot after each transfer that left it selected. */
        val levels: Map<PreGainSense, MutableList<Double>> = mapOf(PreGainSense.BOOST to mutableListOf(), PreGainSense.ATTENUATION to mutableListOf())
        val ceilings: Map<PreGainSense, Double>

        init {
            val state = FiioEqReader.read(FiioHidSession(dac, profile))
            var backup = DacEqBackup.of(att, state, 1L)!!
            if (inspected && factory) backup = backup.withSlot(RecordedMelody.user3Factory)
            store.save(backup)
            // What the factory curve is under each reading, where the take-over begins: the ceilings of the level rule.
            val start = DacEqLive.of(RecordedMelody.user3Factory, PreGainSense.UNCONFIRMED)
            ceilings = mapOf(
                PreGainSense.BOOST to maxOf(0.0, start.netPeaksDb[0]),
                PreGainSense.ATTENUATION to maxOf(0.0, start.netPeaksDb[1]),
            )
            dac.afterSet = { d, _ ->
                if (d.activePreset == slot) {
                    val live = d.slot(slot)
                    for ((reading, list) in levels) list += DacEqCurve.netPeakDb(live.bands, live.preGainTenths, reading)
                }
            }
        }

        fun takeOver() = runBlocking { writer.takeOver(att, WriteInitiator.USER_ACTION) }
        fun restoreFactory() = runBlocking { writer.restoreFactory(att, WriteInitiator.USER_ACTION) }
        fun inventory() = runBlocking { writer.inventory(att, WriteInitiator.REVIEWER_COMMAND) }
        fun discover() = runBlocking { writer.discoverSlot(att) }
        fun apply(bands: List<DacEqBand>) = runBlocking { writer.applyCurve(att, bands, WriteInitiator.USER_ACTION) }
        fun roundtrip() = runBlocking { writer.roundtrip(att, WriteInitiator.REVIEWER_COMMAND) }
        fun restore() = runBlocking { writer.restore(att, WriteInitiator.REVIEWER_COMMAND) }
        fun listen(low: Boolean = true, hold: Long = 5_000L) = runBlocking { writer.listeningCheck(att, low, hold) }
        fun backup() = store.load("profile-a")!!
        fun sent() = dac.sets.map { it.first }
        fun held() = dac.slot(slot).let { DacEqSlotSnapshot(slot, it.preGainTenths, it.bands.toList()) }
        fun setsOnWire() = dac.sets.map { (cmd, data) -> "%02X:%s".format(cmd, FiioHidFrames.hex(data)) }
    }

    private fun assertNoReadingLouderThan(r: Rig, what: String) {
        for ((reading, list) in r.levels) {
            assertWithMessage("$what: the level after each transfer under $reading was $list").that(list.maxOrNull() ?: Double.NEGATIVE_INFINITY)
                .isAtMost(r.ceilings.getValue(reading) + 1e-9)
        }
    }

    // ── while music plays, nothing is sent ──────────────────────────────────────────────────────────────

    @Test
    fun `the steps that select a preset nobody vetted are refused while music plays, with nothing sent`() {
        val r = Rig(playing = true, inspected = true)
        for (result in listOf(r.takeOver(), r.restoreFactory(), r.inventory(), r.discover())) {
            assertThat(result).isInstanceOf(DacEqWriteResult.Refused::class.java)
            assertThat((result as DacEqWriteResult.Refused).refusal).isEqualTo(GateRefusal.PLAYBACK_NOT_PAUSED)
        }
        assertThat(r.dac.sets).isEmpty()
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.log.any { it.contains("playing=true") }).isTrue() // the header says what was asked under
    }

    // ── the take-over, paused ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `taking USER 3 over with the sense unknown is select, bands flat, pre-gain 0, save, read back, select the original back`() {
        val r = Rig()
        val result = r.takeOver()
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        val wire = r.setsOnWire()
        // The sequence the prompt names, in the one order that keeps both readings from getting louder.
        assertThat(wire.first()).isEqualTo("16:A2")
        assertThat(wire.last()).isEqualTo("16:F0")
        val inside = r.dac.sets.drop(1).dropLast(1)
        assertThat(inside.last().first).isEqualTo(0x19) // the save
        assertThat(inside[inside.lastIndex - 1].first).isEqualTo(0x17) // the pre-gain, last of the content
        assertThat(inside[inside.lastIndex - 1].second.toList()).isEqualTo(listOf<Byte>(0, 0)) // at 0
        assertThat(inside.takeWhile { it.first == 0x15 }).isNotEmpty() // the bands first
        // The slot ends flat with the pre-gain at 0, saved, and the DAC is back on what it had.
        val now = r.held()
        assertThat(now.preGainTenthsDb).isEqualTo(0)
        assertThat(now.bands.all { it.isInert }).isTrue()
        assertThat(r.dac.saved.getValue(slot).preGainTenths).isEqualTo(0)
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        // The factory curve is in the backup, untouched, and the slot is the app's.
        val backup = r.backup()
        assertThat(backup.slots.getValue(slot)).isEqualTo(RecordedMelody.user3Factory)
        assertThat(backup.owns(slot)).isTrue()
        assertNoReadingLouderThan(r, "take-over, sense unknown")
        // Reading it as the app would now: flat, closed, and published as such.
        assertThat((result as DacEqWriteResult.Done).state.verdict).isEqualTo(DacEqVerdict.OFF)
    }

    @Test
    fun `with the sense known as amplify the take-over lowers the pre-gain first and no state is above the limit`() {
        val r = Rig(profile = real.copy(preGainSense = PreGainSense.BOOST))
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        // The select puts the slot on with whatever it holds (+10.7 dB if the register amplifies): nothing can silence it first. From the
        // first content SET on, with the direction known, every state is at or below the limit.
        assertThat(r.levels.getValue(PreGainSense.BOOST).first()).isGreaterThan(10.0)
        assertThat(r.levels.getValue(PreGainSense.BOOST).drop(1).max()).isAtMost(0.0)
        assertThat(r.held().preGainTenthsDb).isEqualTo(0)
        assertThat(r.backup().owns(slot)).isTrue()
        assertThat(r.dac.sets[1].first).isEqualTo(0x17) // the first content SET is the pre-gain, lowered
    }

    @Test
    fun `with the sense known as attenuate the take-over is within the limit at every step`() {
        val r = Rig(profile = real.copy(preGainSense = PreGainSense.ATTENUATION))
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.levels.getValue(PreGainSense.ATTENUATION).max()).isAtMost(0.0)
        assertThat(r.held().preGainTenthsDb).isEqualTo(0)
    }

    @Test
    fun `the factory curve is in the backup first even when the slot was never looked at, and the original is never overwritten`() {
        val r = Rig(inspected = false)
        assertThat(r.backup().slots.keys).containsExactly(0xF0)
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.backup().slots.getValue(slot)).isEqualTo(RecordedMelody.user3Factory)
        // A second take-over finds the slot already the app's: nothing is rewritten, nothing about the factory curve changes.
        r.dac.written.clear()
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.sets.map { it.first }.filter { it == 0x15 || it == 0x17 }).isEmpty()
        assertThat(r.backup().slots.getValue(slot)).isEqualTo(RecordedMelody.user3Factory)
    }

    @Test
    fun `when USER 3 is already the selected preset the take-over does not move the pointer`() {
        val r = Rig(startPreset = slot)
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.sets.none { it.first == 0x16 }).isTrue()
        assertThat(r.dac.activePreset).isEqualTo(slot)
        assertThat(r.backup().slots.getValue(slot)).isEqualTo(RecordedMelody.user3Factory)
    }

    // ── after it, the slot is the app's; before it, it is not safe to select ───────────────────────────

    @Test
    fun `an inspected slot that holds a boost is not selected by a write until the app has taken it over`() {
        val r = Rig(profile = real.copy(preGainSense = PreGainSense.BOOST), inspected = true)
        val refused = r.apply(curve(4 to -2.0)) as DacEqWriteResult.Refused
        assertThat(refused.refusal).isEqualTo(GateRefusal.SLOT_UNSAFE_TO_SELECT)
        assertThat(r.dac.sets).isEmpty()
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        r.dac.written.clear()
        for (list in r.levels.values) list.clear() // the take-over's own select was audible at whatever the slot held; from here it is the app's
        val written = r.apply(curve(4 to 3.0))
        assertThat(written).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.held().preGainTenthsDb).isLessThan(0)
        assertThat(r.levels.getValue(PreGainSense.BOOST).max()).isAtMost(0.0)
    }

    @Test
    fun `a band moved on the app's own slot starts from a flat curve, not from the factory one`() {
        val r = Rig(profile = real.copy(preGainSense = PreGainSense.BOOST), inspected = true)
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        r.dac.written.clear()
        for (list in r.levels.values) list.clear()
        val result = runBlocking { r.writer.setBand(r.att, 4, 1.0, WriteInitiator.REVIEWER_COMMAND) }
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        val bands = r.held().bands
        assertThat(bands.count { it.gainTenthsDb != 0 }).isEqualTo(1)
        assertThat(bands[4].gainTenthsDb).isEqualTo(10)
        assertThat(r.levels.getValue(PreGainSense.BOOST).max()).isAtMost(0.0)
    }

    @Test
    fun `a roundtrip of the app's own slot writes back what it holds now, not the factory curve`() {
        val r = Rig(profile = real.copy(preGainSense = PreGainSense.BOOST), inspected = true)
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        r.dac.written.clear()
        for (list in r.levels.values) list.clear()
        assertThat(r.roundtrip()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.held().preGainTenthsDb).isEqualTo(0)
        assertThat(r.held().bands.all { it.isInert }).isTrue()
        assertThat(r.levels.getValue(PreGainSense.BOOST).max()).isAtMost(0.0)
    }

    @Test
    fun `the generic restore leaves the app's own slot alone, and the factory curve comes back only through its own step`() {
        val r = Rig(profile = real.copy(preGainSense = PreGainSense.BOOST), inspected = true)
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        r.dac.written.clear()
        r.restore()
        assertThat(r.dac.sets.map { it.first }.filter { it == 0x15 || it == 0x17 }).isEmpty()
        assertThat(r.held().preGainTenthsDb).isEqualTo(0)
        // The way back: factory curve restored, the slot let go of.
        r.dac.written.clear()
        val back = r.restoreFactory()
        assertThat(back).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.held()).isEqualTo(RecordedMelody.user3Factory)
        assertThat(r.dac.saved.getValue(slot).preGainTenths).isEqualTo(RecordedMelody.USER3_PRE_GAIN_TENTHS)
        assertThat(r.backup().owns(slot)).isFalse()
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        // And it is not the app's again: a write refuses to select it.
        r.dac.written.clear()
        assertThat((r.apply(curve(4 to -2.0)) as DacEqWriteResult.Refused).refusal).isEqualTo(GateRefusal.SLOT_UNSAFE_TO_SELECT)
    }

    @Test
    fun `the factory curve can be given back while the sense is still unknown, by a route no reading gets louder than the factory curve on`() {
        val r = Rig(inspected = true)
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        for (list in r.levels.values) list.clear()
        r.dac.written.clear()
        val back = r.restoreFactory()
        assertWithMessage("$back; ${r.log.takeLast(8)}").that(back).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.held()).isEqualTo(RecordedMelody.user3Factory)
        // The ceilings of a restore are the factory curve's own levels under each reading.
        assertNoReadingLouderThan(r, "factory curve given back, sense unknown")
        assertThat(r.backup().owns(slot)).isFalse()
    }

    @Test
    fun `giving the factory curve back while USER 3 is the selected preset ends on a safe preset, never on the factory curve that boosts`() {
        val r = Rig(inspected = true)
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        // USER 3 is the selected preset, as the app leaves it after a band edit, or as the owner chose it on the DAC.
        r.dac.selectOnDevice(slot)
        r.dac.written.clear()
        val back = r.restoreFactory()
        assertWithMessage("$back; ${r.log.takeLast(8)}").that(back).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.held()).isEqualTo(RecordedMelody.user3Factory)
        // The factory curve plays +10.7 dB too loud if the register amplifies: the selection does not stay on it, whoever presses play next.
        // It goes to Close EQ, which is what the DAC had when the backup was taken.
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.setsOnWire().last()).isEqualTo("16:F0")
        assertThat(r.backup().owns(slot)).isFalse()
    }

    @Test
    fun `giving back a slot that was never taken over is refused, and so is the listening check`() {
        val r = Rig(inspected = true)
        assertThat((r.restoreFactory() as DacEqWriteResult.Refused).refusal).isEqualTo(GateRefusal.SLOT_NOT_TAKEN_OVER)
        assertThat((r.listen() as DacEqWriteResult.Refused).refusal).isEqualTo(GateRefusal.SLOT_NOT_TAKEN_OVER)
        assertThat(r.dac.sets).isEmpty()
    }

    // ── a take-over that fails half way ─────────────────────────────────────────────────────────────────

    @Test
    fun `a take-over whose save does not go out is rolled back to the factory curve, within the ceilings, and the slot is not the app's`() {
        val r = Rig(profile = real.copy(preGainSense = PreGainSense.BOOST), inspected = true)
        // 1 select, then the plan's pre-gain and band SETs, then the save: the transfer of the save is the one that comes back short.
        val plan = DacEqWritePlanner.plan(DacEqLive.of(RecordedMelody.user3Factory, PreGainSense.BOOST), DacEqLive(0, DacEqDefaults.flatBands(10), PreGainSense.BOOST))
        r.dac.shortWriteNthSet = 1 + plan.steps.size + 1
        val result = r.takeOver()
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat((result as DacEqWriteResult.Failed).why).contains("save could not be sent")
        assertThat(r.backup().owns(slot)).isFalse()
        // The select was audible at +10.7; every state after the first content SET is within the limit, up to the last one of the rollback,
        // which is the factory register put back (exempt: it is what the DAC had).
        val boostLevels = r.levels.getValue(PreGainSense.BOOST)
        assertThat(boostLevels.drop(1).filter { it < 10.0 }.maxOrNull() ?: 0.0).isAtMost(0.0)
        // Put back to what it held, with the pointer back where it was.
        assertThat(r.held()).isEqualTo(RecordedMelody.user3Factory)
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
    }

    // ── the inventory ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the inventory reads every preset the profile lists with pointer moves only, stores them, and puts the selection back`() {
        val r = Rig()
        val before = r.dac.live.mapValues { (_, s) -> s.preGainTenths to s.bands.toList() }
        val result = r.inventory()
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        // Only selects: no gain, no band, no save.
        assertThat(r.sent().toSet()).containsExactly(0x16)
        assertThat(r.dac.persistedSlots).isEmpty()
        // Every preset of the profile is in the backup, with its pre-gain and ten bands.
        val backup = r.backup()
        assertThat(backup.slots.keys).containsExactlyElementsIn(real.slots.map { it.id })
        assertThat(backup.slots.getValue(slot)).isEqualTo(RecordedMelody.user3Factory)
        assertThat(backup.slots.getValue(0xF0).bands).hasSize(10)
        for (id in real.slots.map { it.id }) assertThat(backup.slots.getValue(id).bands).hasSize(10)
        // Nothing the DAC holds changed, and the selection is where it was.
        assertThat(r.dac.live.mapValues { (_, s) -> s.preGainTenths to s.bands.toList() }).isEqualTo(before)
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        // Each preset's pre-gain and bands are in the log.
        assertThat(r.log.count { it.contains("INVENTORY 0xA2 USER 3 read:") }).isEqualTo(1)
        assertThat(r.log.any { it.contains("INVENTORY 0xA2 USER 3 band 8") && it.contains("3050 Hz +5.4 dB") }).isTrue()
        assertThat(r.log.any { it.contains("preGain=read +8.0 dB") }).isTrue()
    }

    @Test
    fun `the inventory keeps what is already in the backup and says when the DAC holds something else now`() {
        val r = Rig(inspected = true)
        r.dac.setSlot(slot, 0, curve())
        assertThat(r.inventory()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.backup().slots.getValue(slot)).isEqualTo(RecordedMelody.user3Factory)
        assertThat(r.log.any { it.contains("the DAC holds something else now: the backup keeps the first look") }).isTrue()
    }

    @Test
    fun `the inventory from a user slot goes back to that slot`() {
        val r = Rig(startPreset = 0xA0)
        assertThat(r.inventory()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.activePreset).isEqualTo(0xA0)
        assertThat(r.sent().toSet()).containsExactly(0x16)
    }

    // ── Close EQ's zero bands ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `bands outside the profile's limits are unset, not filters`() {
        val zero = DacEqBand(0, 0, 0, 0, 0)
        assertThat(zero.isUnset).isTrue()
        assertThat(zero.isInert).isTrue()
        assertThat(DacEqBand.of(0, FiioFilterType.PEAK, 1000, 0.0, 1.0).isUnset).isFalse()
        assertThat(DacEqBand.of(0, FiioFilterType.PEAK, 1000, 2.0, 1.0).isInert).isFalse()
        // An unset band sounds like a flat one, and is not the same as one that was moved.
        assertThat(zero.sameEffectAs(DacEqBand.of(0, FiioFilterType.PEAK, 32, 0.0, 0.71))).isTrue()
        assertThat(zero.sameEffectAs(DacEqBand.of(0, FiioFilterType.PEAK, 32, 1.0, 0.71))).isFalse()
        assertThat(DacEqBand.of(0, FiioFilterType.PEAK, 32, 0.0, 0.71).sameEffectAs(DacEqBand.of(0, FiioFilterType.PEAK, 64, 0.0, 0.71))).isFalse()
        assertThat(DacEqCurve.peakBoostDb(RecordedMelody.closeEq.bands)).isEqualTo(0.0)
    }

    @Test
    fun `restoring while Close EQ is what was selected is a select of Close EQ and nothing else`() {
        val r = Rig(profile = real.copy(preGainSense = PreGainSense.BOOST), inspected = true)
        // Someone presses the DAC's own button: another preset is selected.
        r.dac.selectOnDevice(0x00)
        r.dac.written.clear()
        val result = r.restore()
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.setsOnWire()).containsExactly("16:F0")
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.dac.live.getValue(0xF0).bands.all { it.frequencyHz == 0 }).isTrue() // still unset; nothing was written into it
    }

    @Test
    fun `an inventory puts unused user slots in the backup, and a restore neither refuses them nor writes zeros into them`() {
        val r = Rig(profile = real.copy(preGainSense = PreGainSense.BOOST))
        // USER 1 and USER 2 are unused: ten unset bands, a register of 0.
        for (id in listOf(0xA0, 0xA1)) r.dac.setSlot(id, 0, (0 until 10).map { DacEqBand(it, 0, 0, 0, 0) })
        assertThat(r.inventory()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.backup().slots.getValue(0xA0).bands.all { it.isUnset }).isTrue()
        r.dac.written.clear()
        val result = r.restore()
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        // Only the slot the app writes is ever restored: USER 1 and USER 2 are not selected, let alone written.
        assertThat(r.dac.sets.map { it.first }.filter { it == 0x15 || it == 0x17 || it == 0x19 }).isEmpty()
        assertThat(r.dac.live.getValue(0xA0).bands.all { it.frequencyHz == 0 }).isTrue()
    }

    @Test
    fun `writing flat where the slot held unset bands skips them, and the read-back still agrees`() {
        val r = Rig(profile = real.copy(preGainSense = PreGainSense.BOOST), factory = false, inspected = false)
        r.dac.setSlot(slot, 0, (0 until 10).map { DacEqBand(it, 0, 0, 0, 0) })
        assertThat(r.discover()).isInstanceOf(DacEqWriteResult.Done::class.java)
        r.dac.written.clear()
        val result = r.apply(curve())
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.sets.none { it.first == 0x15 }).isTrue() // an unset band is already what a flat one is
    }

    // ── the listening check ─────────────────────────────────────────────────────────────────────────────

    private fun takenOver(profile: FiioHidProfile = real): Rig = Rig(profile = profile, inspected = true).also {
        check(it.takeOver() is DacEqWriteResult.Done)
        it.dac.written.clear()
        it.dac.persistedSlots.clear()
        it.log.clear()
        for (list in it.levels.values) list.clear()
        it.pauses.clear()
    }

    @Test
    fun `the listening check puts the pre-gain at -6, 0, +6, 0 dB live and saves nothing`() {
        val r = takenOver()
        val result = r.listen()
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.setsOnWire()).containsExactly("16:A2", "17:FF C4", "17:00 00", "17:00 3C", "17:00 00", "16:F0").inOrder()
        assertThat(r.dac.persistedSlots).isEmpty() // not one save
        // The first entry is the select, onto a slot that is flat; then the four states of the check.
        assertThat(r.levels.getValue(PreGainSense.BOOST)).containsExactly(0.0, -6.0, 0.0, 6.0, 0.0).inOrder()
        assertThat(r.levels.getValue(PreGainSense.ATTENUATION)).containsExactly(0.0, 6.0, 0.0, -6.0, 0.0).inOrder()
        // Each state is held long enough to hear.
        assertThat(r.pauses.filter { it >= 5_000L }).hasSize(4)
        // It ends where it began: the register at 0, flat, the pointer back, and the DAC's saved slot as it was.
        assertThat(r.held().preGainTenthsDb).isEqualTo(0)
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.dac.saved.getValue(slot).preGainTenths).isEqualTo(0)
        assertThat(r.log.count { it.contains("LISTEN A") || it.contains("LISTEN B") || it.contains("LISTEN neutral") }).isEqualTo(4)
    }

    @Test
    fun `the listening check does not run without the reviewer saying the DAC is at a low level`() {
        val r = takenOver()
        val result = r.listen(low = false)
        assertThat((result as DacEqWriteResult.Refused).refusal).isEqualTo(GateRefusal.LOW_LEVEL_NOT_CONFIRMED)
        assertThat(r.dac.sets).isEmpty()
    }

    @Test
    fun `the screen cannot ask for the listening check`() {
        val r = takenOver()
        val state = FiioEqReader.read(FiioHidSession(r.dac, real))
        val context = WriteContext(real, "profile-a", state, 0, r.backup(), true)
        val decision = DacEqWriteGate().authorise(
            WriteRequest(WriteKind.LISTENING_CHECK, WriteInitiator.USER_ACTION, slot, confirmed = true), context,
        )
        assertThat((decision as GateDecision.Refused).refusal).isEqualTo(GateRefusal.REVIEWER_ONLY)
    }

    @Test
    fun `a listening check that cannot confirm a step puts the pre-gain back to 0 and says so`() {
        val r = takenOver()
        r.dac.contraryAcks += 0x17
        val result = r.listen()
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        // The acknowledgement contradicted, so the check stopped at once; the pre-gain it had sent is back at 0, confirmed by a read-back.
        assertThat(r.held().preGainTenthsDb).isEqualTo(0)
        assertThat(r.log.any { it.contains("LISTEN neutralised") }).isTrue()
        assertThat(r.dac.persistedSlots).isEmpty()
    }
}
