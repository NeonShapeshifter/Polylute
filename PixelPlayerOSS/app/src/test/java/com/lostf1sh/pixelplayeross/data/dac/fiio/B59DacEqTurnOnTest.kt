package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B59 item 1 (#53): **the DAC EQ on the Melody: the Q rounding, one tap, and the right slot**, over the scripted fake DAC (nothing is sent to a real one).
 *
 * The owner turned the DAC EQ on at 00:54 on 11-06: refused (USER 3 holds +10.7 dB), "Take over USER 3", refused until the music was paused, paused, pressed again, and the
 * take-over **failed twice the same way**: the app sent the flat band with Q 0.71 (`47`), the Melody stored and acknowledged 0.70 (`46`), and the writer wanted an exact echo.
 * Here: a DAC that stores Q one hundredth lower is confirmed (and logged once per band, and what it stored is the record), a Q off by two or a gain off by one still fails and
 * restores; turning the EQ on while music plays is one action (pause, take over, apply, resume) and on a failure the music stays paused; the slot is chosen once among the
 * user slots (a flat one without a question, a question only when all three boost, a slot already taken stays, the DAC's selection put back after the reading, restore-factory on
 * the chosen slot).
 */
class B59DacEqTurnOnTest {
    /** The real row: its pre-gain sense is unconfirmed, as on the owner's phone. */
    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val dirs = mutableListOf<File>()

    @AfterEach fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
            HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    /** The player as the screen sees it: a pause that is over when it says so, a resume only for a pause the app made. */
    private class FakePlayback(var playing: Boolean, val wire: () -> Int) : DacEqPlayback {
        val events = mutableListOf<String>()
        var pausedAtWrite = -1
        var resumedAtWrite = -1
        override fun isPlaying() = playing
        override suspend fun pauseAndWait(): Boolean {
            events += "pause"
            pausedAtWrite = wire()
            playing = false
            return true
        }
        override fun resume() {
            events += "resume"
            resumedAtWrite = wire()
            playing = true
        }
    }

    private fun boosting(dac: FakeFiioDac, slot: Int, atBand: Int = 4, db: Double = 3.0) {
        dac.setSlot(slot, 0, (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], if (it == atBand) db else 0.0, 1.0) })
    }

    private inner class Rig(
        val dac: FakeFiioDac = RecordedMelody.fakeDac(0xF0, melody),
        playing: Boolean = false,
        withBackup: Boolean = false,
    ) {
        val log = mutableListOf<String>()
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-b59").toFile().also { dirs += it })
        val att = AttachedFiio("path-a", "identity-a", "profile-a", "SNOWSKY Melody", "path-a", melody)
        val playback = FakePlayback(playing) { dac.written.size }
        val writer = DacEqWriter(
            DacEqHidAccess(FakeOpener(dac), Dispatchers.Unconfined), DacEqWriteGate(), store, { true }, { 0L }, { 1_700_000_000_000L },
            pause = {}, log = { log += it }, playbackActive = { playback.playing },
        )
        val turnOn = DacEqTurnOn(writer, store, playback, { it.preGainSense }, { log += it })

        init {
            if (withBackup) store.save(DacEqBackup.of(att, FiioEqReader.read(FiioHidSession(dac, melody)), 1L)!!)
        }

        fun flat() = DacEqDefaults.flatBands(10)
        fun takeOver() = runBlocking { writer.takeOver(att, WriteInitiator.USER_ACTION) }
        fun apply(bands: List<DacEqBand> = flat()) = runBlocking { writer.applyCurve(att, bands, WriteInitiator.USER_ACTION) }
        fun readSlots() = runBlocking { writer.readUserSlots(att, WriteInitiator.USER_ACTION) }
        fun restoreFactory() = runBlocking { writer.restoreFactory(att, WriteInitiator.USER_ACTION) }
        fun go(choose: suspend (DacEqSlotPlan.Ask) -> Int? = { error("the listener must not be asked") }) =
            runBlocking { turnOn.turnOn(att, flat(), WriteInitiator.USER_ACTION, chooseSlot = choose) }
        fun backup() = store.load("profile-a")!!
        fun bandSets() = dac.sets.filter { it.first == FiioHidWrites.CMD_BAND }
        fun contentSets() = dac.sets.filter { it.first != FiioHidWrites.CMD_PRESET }
        fun slot(id: Int) = dac.slot(id).let { DacEqSlotSnapshot(id, it.preGainTenths, it.bands.toList()) }
        fun lines(prefix: String) = log.filter { it.startsWith(prefix) }
    }

    // ── 1. the Q rounding ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a DAC that stores Q one hundredth lower takes the slot over, says so once per band, and the check passes`() {
        val dac = RecordedMelody.fakeDac(0xF0, melody).also { d -> d.storeBand = { b -> if (b.qHundredths == 71) b.copy(qHundredths = 70) else b } }
        val r = Rig(dac, withBackup = true)
        val taken = r.takeOver()
        assertThat(taken).isInstanceOf(DacEqWriteResult.Done::class.java)
        // what the DAC stored is Q 0.70, in every band the app wrote
        assertThat(r.slot(0xA2).bands.map { it.qHundredths }.toSet()).containsExactly(70)
        // said once per band, in the words of the prompt
        val said = r.lines("DAC_HID|CONFIRM band ").filter { it.endsWith("the DAC stored Q 0.70 for 0.71 (its rounding)") }
        assertThat(said).hasSize(10)
        assertThat(said.map { it.substringAfter("band ").substringBefore(':') }.toSet()).hasSize(10)
        assertThat(r.lines("DAC_HID|RESULT TAKE_OVER").single()).contains("done:")
        // the record is what the DAC stored: asking for the same flat curve again (Q 0.71) writes nothing and passes the check
        val bandsBefore = r.bandSets().size
        val applied = r.apply()
        assertThat(applied).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.bandSets().size).isEqualTo(bandsBefore)
        assertThat(r.slot(0xA2).bands.map { it.qHundredths }.toSet()).containsExactly(70)
    }

    @Test
    fun `a DAC that answers Q off by two fails the take-over and puts the slot back, as before`() {
        val dac = RecordedMelody.fakeDac(0xF0, melody).also { d -> d.storeBand = { b -> if (b.qHundredths == 71) b.copy(qHundredths = 69) else b } }
        val r = Rig(dac, withBackup = true)
        val taken = r.takeOver()
        assertThat(taken).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat(r.log.any { it.contains("ACK band") && it.contains("CONTRARY") }).isTrue()
        assertThat(r.lines("DAC_HID|CONFIRM band ")).isEmpty()
        assertThat((taken as DacEqWriteResult.Failed).why).contains("was not confirmed")
        // restored: the selection is back and the slot holds what it held (its factory bands, not the flat ones)
        assertThat(taken.restored).isTrue()
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.slot(0xA2).bands.any { it.qHundredths == 69 }).isFalse()
        assertThat(r.backup().ownedSlots).isEmpty()
    }

    @Test
    fun `a DAC that answers a gain off by one fails the take-over and puts the slot back, as before`() {
        val dac = RecordedMelody.fakeDac(0xF0, melody).also { d ->
            d.storeBand = { b -> if (b.qHundredths == 71 && b.gainTenthsDb == 0) b.copy(gainTenthsDb = 1) else b }
        }
        val r = Rig(dac, withBackup = true)
        val taken = r.takeOver()
        assertThat(taken).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat(r.log.any { it.contains("ACK band") && it.contains("CONTRARY") }).isTrue()
        assertThat((taken as DacEqWriteResult.Failed).restored).isTrue()
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.backup().ownedSlots).isEmpty()
    }

    @Test
    fun `the Q rounding is only the Q - one hundredth of tolerance, either side, and nothing else`() {
        val a = DacEqBand.of(1, FiioFilterType.PEAK, 64, 0.0, 0.71)
        assertThat(a.sameAsAllowingQRounding(a.copy(qHundredths = 70))).isTrue()
        assertThat(a.sameAsAllowingQRounding(a.copy(qHundredths = 72))).isTrue()
        assertThat(a.sameAsAllowingQRounding(a.copy(qHundredths = 69))).isFalse()
        assertThat(a.sameAsAllowingQRounding(a.copy(qHundredths = 73))).isFalse()
        assertThat(a.sameAsAllowingQRounding(a.copy(gainTenthsDb = 1))).isFalse()
        assertThat(a.sameAsAllowingQRounding(a.copy(frequencyHz = 65))).isFalse()
        assertThat(a.sameAsAllowingQRounding(a.copy(typeCode = 1))).isFalse()
        assertThat(a.sameAsAllowingQRounding(a.copy(index = 2))).isFalse()
        // the exact comparison is untouched
        assertThat(a.sameAs(a.copy(qHundredths = 70))).isFalse()
        // the acknowledgement's bytes, as the device sent them: 47 sent, 46 reported
        val sent = byteArrayOf(1, 0, 0, 0, 0x40, 0, 0x47, 0)
        assertThat(FiioBandAck.differsOnlyByQRounding(sent, byteArrayOf(1, 0, 0, 0, 0x40, 0, 0x46, 0))).isTrue()
        assertThat(FiioBandAck.differsOnlyByQRounding(sent, byteArrayOf(1, 0, 0, 0, 0x40, 0, 0x45, 0))).isFalse()
        assertThat(FiioBandAck.differsOnlyByQRounding(sent, byteArrayOf(1, 0, 0, 1, 0x40, 0, 0x46, 0))).isFalse()
        assertThat(FiioBandAck.differsOnlyByQRounding(sent, sent)).isFalse()
    }

    // ── 2. one tap ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `turning the EQ on while music plays pauses it first, takes the slot over, applies the curve and starts the music again`() {
        val r = Rig(playing = true)
        val out = r.go()
        assertThat(out.result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.playback.events).containsExactly("pause", "resume").inOrder()
        // the pause came before the first byte reached the DAC; the resume after the last
        assertThat(r.playback.pausedAtWrite).isEqualTo(0)
        assertThat(r.playback.resumedAtWrite).isEqualTo(r.dac.written.size)
        assertThat(r.playback.playing).isTrue()
        assertThat(out.pausedMusic).isTrue()
        assertThat(out.resumedMusic).isTrue()
        // the slot is the app's, flat, selected: the DAC's EQ is on
        val chosen = r.backup().chosenSlot!!
        assertThat(r.backup().ownedSlots).containsExactly(chosen)
        assertThat(r.dac.activePreset).isEqualTo(chosen)
        assertThat(r.slot(chosen).bands.all { it.isInert }).isTrue()
        assertThat(r.slot(chosen).preGainTenthsDb).isEqualTo(0)
        // no "pause the music first" refusal reached anyone
        assertThat(r.log.none { it.contains("PLAYBACK_NOT_PAUSED") }).isTrue()
        assertThat(r.lines("DAC_HID|TURN_ON")).isNotEmpty()
    }

    @Test
    fun `when music is not playing nothing is paused and nothing is started`() {
        val r = Rig(playing = false)
        val out = r.go()
        assertThat(out.result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.playback.events).isEmpty()
        assertThat(out.pausedMusic).isFalse()
    }

    @Test
    fun `a failure restores as before and the music stays paused, with the reason in the result`() {
        // all three user slots boost, so the app takes USER 3 (preselected) and has real bands to write; the DAC ignores the second SET of the take-over
        val dac = RecordedMelody.fakeDac(0xF0, melody).also { boosting(it, 0xA0); boosting(it, 0xA1) }
        val r = Rig(dac, playing = true)
        // SETs on the wire before the bands: 3 selects and the selection back (reading the user slots), then the select of USER 3; the 7th is the take-over's second band
        dac.ignoreNthSet = 7
        val out = r.go { it.preselected }
        assertThat(out.result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat((out.result as DacEqWriteResult.Failed).why).contains("was not confirmed")
        assertThat(r.playback.events).containsExactly("pause")
        assertThat(r.playback.playing).isFalse()
        assertThat(out.pausedMusic).isTrue()
        assertThat(out.resumedMusic).isFalse()
        // the DAC is back on its own selection and the slot is not the app's
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.backup().ownedSlots).isEmpty()
        assertThat(r.lines("DAC_HID|TURN_ON ended at the take-over").single()).contains("the music stays paused")
    }

    @Test
    fun `a music player that will not pause stops the turn-on before a byte is sent`() {
        val r = Rig(playing = true)
        val stubborn = object : DacEqPlayback {
            override fun isPlaying() = true
            override suspend fun pauseAndWait() = false
            override fun resume() = error("never started: it was never paused by the app")
        }
        val out = runBlocking { DacEqTurnOn(r.writer, r.store, stubborn, { it.preGainSense }, { r.log += it }).turnOn(r.att, r.flat(), WriteInitiator.USER_ACTION) }
        assertThat(out.result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat(r.dac.written).isEmpty()
    }

    @Test
    fun `when the slot is the app's already, turning the EQ on does not pause anything and applies the curve`() {
        val r = Rig(playing = false)
        assertThat(r.go().result).isInstanceOf(DacEqWriteResult.Done::class.java)
        r.playback.playing = true
        r.playback.events.clear()
        val bands = r.flat().mapIndexed { i, b -> if (i == 3) b.copy(gainTenthsDb = -30) else b }
        val out = runBlocking { r.turnOn.turnOn(r.att, bands, WriteInitiator.USER_ACTION) }
        assertThat(out.result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.playback.events).isEmpty()
        assertThat(out.pausedMusic).isFalse()
    }

    // ── 3. the right slot ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a flat USER 1 is used without a question, and USER 3 with its curve is left alone`() {
        val r = Rig() // USER 1 and USER 2 are flat, USER 3 holds +8 dB
        val before = r.slot(0xA2)
        val out = r.go() // the chooser would fail the test if it were asked
        assertThat(out.result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.backup().chosenSlot).isEqualTo(0xA0)
        assertThat(r.backup().ownedSlots).containsExactly(0xA0)
        assertThat(r.dac.activePreset).isEqualTo(0xA0)
        assertThat(r.slot(0xA2)).isEqualTo(before)
        assertThat(r.lines("DAC_HID|SLOT USER 1").single()).contains("without asking")
    }

    @Test
    fun `when every user slot boosts the listener is asked, USER 3 preselected, each with its level, and the one picked is used`() {
        val dac = RecordedMelody.fakeDac(0xF0, melody).also { boosting(it, 0xA0, db = 3.0); boosting(it, 0xA1, db = 6.0) }
        val r = Rig(dac)
        var asked: DacEqSlotPlan.Ask? = null
        val out = r.go { ask -> asked = ask; 0xA1 }
        assertThat(asked).isNotNull()
        assertThat(asked!!.options.map { it.slotId }).containsExactly(0xA0, 0xA1, 0xA2).inOrder()
        assertThat(asked!!.preselected).isEqualTo(0xA2)
        assertThat(asked!!.options.map { it.name }).containsExactly("USER 1", "USER 2", "USER 3").inOrder()
        assertThat(asked!!.options.all { it.netPeakDb > 0.0 }).isTrue()
        assertThat(out.result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.backup().chosenSlot).isEqualTo(0xA1)
        assertThat(r.backup().ownedSlots).containsExactly(0xA1)
        assertThat(r.dac.activePreset).isEqualTo(0xA1)
    }

    @Test
    fun `the listener who gives no slot gets nothing written and the music back`() {
        val dac = RecordedMelody.fakeDac(0xF0, melody).also { boosting(it, 0xA0); boosting(it, 0xA1) }
        val r = Rig(dac, playing = true)
        val out = r.go { null }
        assertThat(out.result).isNull()
        assertThat(r.contentSets()).isEmpty() // only pointer moves (the reading of the slots)
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.playback.events).containsExactly("pause", "resume").inOrder()
        assertThat(r.backup().chosenSlot).isNull()
        assertThat(r.backup().ownedSlots).isEmpty()
    }

    @Test
    fun `a slot the app already took over stays the app's, and nothing is read or asked again`() {
        val dac = RecordedMelody.fakeDac(0xF0, melody).also { boosting(it, 0xA0); boosting(it, 0xA1) }
        val r = Rig(dac)
        assertThat(r.go { 0xA1 }.result).isInstanceOf(DacEqWriteResult.Done::class.java)
        r.log.clear()
        val setsBefore = r.dac.sets.size
        val second = r.go() // the chooser would fail the test
        assertThat(second.result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.backup().chosenSlot).isEqualTo(0xA1)
        assertThat(r.lines("DAC_HID|OP READ_USER_SLOTS")).isEmpty()
        assertThat(r.lines("DAC_HID|OP TAKE_OVER")).isEmpty()
        assertThat(r.dac.sets.size - setsBefore).isAtMost(2) // at most a select (and a save) for a curve that is already there
    }

    @Test
    fun `a slot taken over before the choice existed (USER 3) stays the app's`() {
        val r = Rig(withBackup = true)
        // B31's record: the app took USER 3 over; no choice was ever recorded
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.backup().ownedSlots).containsExactly(0xA2)
        assertThat(r.backup().chosenSlot).isEqualTo(0xA2)
        // an older file has no chosen slot at all
        r.store.update(r.backup().withChosenSlot(null))
        assertThat(r.backup().appSlotFor(melody)).isEqualTo(0xA2)
        val out = r.go()
        assertThat(out.result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.lines("DAC_HID|OP READ_USER_SLOTS")).isEmpty()
        assertThat(r.dac.activePreset).isEqualTo(0xA2)
    }

    @Test
    fun `reading the user slots puts the DAC's own selection back and sends pointer moves only`() {
        val dac = RecordedMelody.fakeDac(0x03, melody).also { boosting(it, 0xA0) } // Dance selected
        val r = Rig(dac)
        val result = r.readSlots()
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.activePreset).isEqualTo(0x03)
        assertThat(r.dac.sets.map { it.first }.toSet()).containsExactly(FiioHidWrites.CMD_PRESET)
        assertThat(r.dac.sets.map { it.second[0].toInt() and 0xFF }).containsAtLeast(0xA0, 0xA1, 0xA2)
        assertThat(r.dac.sets.map { it.second[0].toInt() and 0xFF }).doesNotContain(0xF0) // the factory presets are not read for this
        assertThat(r.backup().slots.keys).containsAtLeast(0xA0, 0xA1, 0xA2, 0x03)
        assertThat(r.backup().slots.keys).doesNotContain(0x00)
    }

    @Test
    fun `restore-factory gives back the slot the app used, and forgets the choice`() {
        val dac = RecordedMelody.fakeDac(0xF0, melody).also { boosting(it, 0xA0); boosting(it, 0xA1, db = 6.0) }
        val r = Rig(dac)
        val factory = r.slot(0xA1)
        assertThat(r.go { 0xA1 }.result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.slot(0xA1)).isNotEqualTo(factory)
        val back = r.restoreFactory()
        assertThat(back).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.slot(0xA1).preGainTenthsDb).isEqualTo(factory.preGainTenthsDb)
        assertThat(r.slot(0xA1).bands.map { it.gainTenthsDb }).isEqualTo(factory.bands.map { it.gainTenthsDb })
        assertThat(r.backup().ownedSlots).isEmpty()
        assertThat(r.backup().chosenSlot).isNull()
        // it never touched USER 3
        assertThat(r.slot(0xA2).bands.map { it.gainTenthsDb }).isEqualTo(RecordedMelody.user3Bands.map { it.gainTenthsDb })
    }

    @Test
    fun `the choice among the user slots is a pure decision - holds nothing first, then the lowest harmless, else ask`() {
        fun backupOf(vararg slots: DacEqSlotSnapshot): DacEqBackup =
            DacEqBackup("k", melody.id, "d", 1L, 0xF0, null, slots.associateBy { it.presetId })
        fun flatSlot(id: Int, pre: Int = 0) = DacEqSlotSnapshot(id, pre, DacEqDefaults.flatBands(10))
        fun cutSlot(id: Int) = DacEqSlotSnapshot(id, 0, DacEqDefaults.flatBands(10).mapIndexed { i, b -> if (i == 2) b.copy(gainTenthsDb = -40) else b })
        fun boostSlot(id: Int) = DacEqSlotSnapshot(id, 0, DacEqDefaults.flatBands(10).mapIndexed { i, b -> if (i == 2) b.copy(gainTenthsDb = 40) else b })
        val unconfirmed = PreGainSense.UNCONFIRMED
        // a cut curve in USER 1, nothing in USER 2: nothing to lose in USER 2 wins even though USER 1 comes first
        val plan1 = DacEqSlotChoice.plan(melody, backupOf(cutSlot(0xA0), flatSlot(0xA1), boostSlot(0xA2)), unconfirmed)
        assertThat((plan1 as DacEqSlotPlan.Use).option.slotId).isEqualTo(0xA1)
        // USER 1 and USER 2 both harmless with content: the lowest-numbered
        val plan2 = DacEqSlotChoice.plan(melody, backupOf(cutSlot(0xA0), cutSlot(0xA1), boostSlot(0xA2)), unconfirmed)
        assertThat((plan2 as DacEqSlotPlan.Use).option.slotId).isEqualTo(0xA0)
        // a flat curve with a positive register can amplify under one reading: not harmless
        val plan3 = DacEqSlotChoice.plan(melody, backupOf(flatSlot(0xA0, pre = 30), flatSlot(0xA1, pre = 30), boostSlot(0xA2)), unconfirmed)
        assertThat(plan3).isInstanceOf(DacEqSlotPlan.Ask::class.java)
        assertThat((plan3 as DacEqSlotPlan.Ask).preselected).isEqualTo(0xA2)
        // a slot nobody read is not known to be harmless
        val plan4 = DacEqSlotChoice.plan(melody, backupOf(boostSlot(0xA0), boostSlot(0xA2)), unconfirmed)
        assertThat((plan4 as DacEqSlotPlan.Ask).options.map { it.slotId }).containsExactly(0xA0, 0xA1, 0xA2).inOrder()
        assertThat(plan4.options.single { it.slotId == 0xA1 }.netPeakDb).isPositiveInfinity()
    }
}
