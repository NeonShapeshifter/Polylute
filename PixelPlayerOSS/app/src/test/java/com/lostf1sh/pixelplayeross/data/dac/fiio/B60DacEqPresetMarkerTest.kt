package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B60 item 1 (#53): **the preset reads `0x0A` after the bands are touched.**
 *
 * The frames of 11-06 19:23 (`B59-review/logcat-dac.txt`): `select 0xA2` → `GET 16` answers `A2` twice → the app reads count, gain and the ten bands → (a plan of 0 steps: nothing
 * is sent) → the next `GET 16` answers `0A` (`07 BB 0B 00 C3 16 01 0A 5D EE`, a fresh frame) → `MISMATCH … (selected 0x0A)`, the DAC put back on `F0`, the music left paused.
 * After the take-over's save (`SET 19 A2`) the same question had been answered `A2`. The owner's 00:55 log had `0A` right after a band SET. The model in [FakeFiioDac.editingMarker]:
 * `GET 16` answers the marker once a band of a user slot was read or written, until the next select or save; "Close EQ" never does it.
 *
 * Here, against that model: take-over, apply (including the plan of 0 steps), the one-tap turn-on and restore-factory pass; a DAC selected elsewhere (it answers its own id, or the
 * marker with other bands) is still a mismatch and a restore; a failed take-over still gives the slot's own curve back; nothing is ever sent as `select 0x0A`; the save's reply
 * `07 00 EE 00 89` is what it is. Nothing is sent to a real DAC.
 */
class B60DacEqPresetMarkerTest {
    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val dirs = mutableListOf<File>()

    @AfterEach fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
            HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    private class FakePlayback(var playing: Boolean) : DacEqPlayback {
        override fun isPlaying() = playing
        override suspend fun pauseAndWait(): Boolean { playing = false; return true }
        override fun resume() { playing = true }
    }

    /** The owner's DAC as the logs show it: "Close EQ" selected, USER 3 holding a boosting curve, and the marker behaviour of the Melody. */
    private fun markerDac(selected: Int = 0xF0) = RecordedMelody.fakeDac(selected, melody).also { it.editingMarker = 0x0A }

    /** A curve that is not the factory pattern of the fake's presets 0..6 (even bands up, odd bands down): what a hidden foreign select would show. */
    private fun factoryPattern(dac: FakeFiioDac, slot: Int) {
        dac.setSlot(slot, 0, (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], if (it % 2 == 0) 2.0 else -1.5, 0.71) })
    }

    private inner class Rig(val dac: FakeFiioDac = markerDac(), withBackup: Boolean = true, playing: Boolean = false) {
        val log = mutableListOf<String>()
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-b60").toFile().also { dirs += it })
        val att = AttachedFiio("path-a", "identity-a", "profile-a", "SNOWSKY Melody", "path-a", melody)
        val playback = FakePlayback(playing)
        var writer = newWriter()
        val turnOn get() = DacEqTurnOn(writer, store, playback, { it.preGainSense }, { log += it })

        fun newWriter() = DacEqWriter(
            DacEqHidAccess(FakeOpener(dac), Dispatchers.Unconfined), DacEqWriteGate(), store, { true }, { 0L }, { 1_700_000_000_000L },
            pause = {}, log = { log += it }, playbackActive = { playback.playing },
        )

        init {
            if (withBackup) store.save(DacEqBackup.of(att, FiioEqReader.read(FiioHidSession(dac, melody)), 1L)!!)
        }

        fun flat() = DacEqDefaults.flatBands(10)
        /** A cut: written when the real row's pre-gain sense was unconfirmed and a boost was refused (B67: the row is BOOST now; a cut still needs no pre-gain). */
        fun curve() = flat().mapIndexed { i, b -> if (i == 3) b.copy(gainTenthsDb = -30) else b }
        fun takeOver() = runBlocking { writer.takeOver(att, WriteInitiator.USER_ACTION) }
        fun apply(bands: List<DacEqBand> = flat()) = runBlocking { writer.applyCurve(att, bands, WriteInitiator.USER_ACTION) }
        fun restoreFactory() = runBlocking { writer.restoreFactory(att, WriteInitiator.USER_ACTION) }
        fun go(bands: List<DacEqBand> = flat()) = runBlocking { turnOn.turnOn(att, bands, WriteInitiator.USER_ACTION) }
        fun backup() = store.load("profile-a")!!
        fun lines(prefix: String) = log.filter { it.startsWith(prefix) }
        fun selects() = dac.sets.filter { it.first == FiioHidWrites.CMD_PRESET }.map { it.second[0].toInt() and 0xFF }
        fun slot(id: Int) = dac.slot(id).let { DacEqSlotSnapshot(id, it.preGainTenths, it.bands.toList()) }
    }

    // ── the frames of 11-06, as fixtures ────────────────────────────────────────────────────────────────

    private fun hex(text: String, length: Int = 64): ByteArray = report(text, length)

    @Test
    fun `the 0x0A preset reply of 19-23-29 is a fresh well-formed reply and not a stale one`() {
        // the count reply and the preset reply, one after the other, from the device
        val count = FiioHidReplies.parse(hex("07 BB 0B 00 C2 18 01 0A 26 EE 3E 80 00 46 00 0E EE"), 0x07) as FiioReplyParse.Reply
        val preset = FiioHidReplies.parse(hex("07 BB 0B 00 C3 16 01 0A 5D EE 3E 80 00 46 00 0E EE"), 0x07) as FiioReplyParse.Reply
        assertThat(count.command).isEqualTo(0x18)
        assertThat(preset.command).isEqualTo(0x16)
        assertThat(preset.data.toList()).containsExactly(0x0A.toByte())
        // its own sequence number: the next after the count's, the CRC checked by the parser
        assertThat(preset.seq).isEqualTo(count.seq + 1)
        // the A2 reply of the same session, for comparison, is the same kind of frame
        val a2 = FiioHidReplies.parse(hex("07 BB 0B 00 B6 16 01 A2 19 EE 00 00"), 0x07) as FiioReplyParse.Reply
        assertThat(a2.data.toList()).containsExactly(0xA2.toByte())
        // the leftovers after the stop byte are the end of the band 9 reply that was in the buffer, not part of this frame
        assertThat(FiioHidReplies.parse(hex("07 BB 0B 00 C3 16 01 0A 5D EF 3E"), 0x07)).isInstanceOf(FiioReplyParse.NoAnswer::class.java)
    }

    @Test
    fun `the save reply 07 00 EE 00 89 is the DAC's bare counter, logged as that, and the stale 1A tail is not`() {
        val save = hex("07 00 EE 00 89 00 00 00 00 00 00 00")
        assertThat(FiioHidReplies.bareCounter(save, 0x07)).isEqualTo(0x89)
        assertThat((FiioHidReplies.parseAck(save, 0x07) as FiioReplyParse.NoAnswer).reason).isEqualTo(NoAnswerReason.NOT_A_REPLY)
        // the Melody's answer to 1A: the same first bytes over the leftovers of an earlier reply: not a counter
        assertThat(FiioHidReplies.bareCounter(hex("07 00 EE 00 03 16 01 F0 E1 EE"), 0x07)).isNull()
        assertThat(FiioHidReplies.bareCounter(hex("07 BB 0B 00 C3 16 01 0A 5D EE"), 0x07)).isNull()

        val log = mutableListOf<String>()
        val session = FiioHidSession(ReplayWire(listOf(listOf(save))), melody, { log += it })
        session.wire.write(ByteArray(64))
        val ack = session.awaitSetAck(0x19, byteArrayOf(0xA2.toByte()), "save 0xA2")
        // what the writer does with it did not change: not an acknowledgement, the read-back confirms
        assertThat(ack).isInstanceOf(FiioSetAck.Unusable::class.java)
        assertThat(log.any { it.contains("ACK save 0xA2 not an acknowledgement: NOT_A_REPLY") }).isTrue()
        assertThat(log.any { it.contains("ACK save 0xA2 is the DAC's bare counter (seq=137") }).isTrue()
    }

    // ── the model, then the operations against it ───────────────────────────────────────────────────────

    @Test
    fun `the model answers the marker only after a band of a user slot is touched, and a select or a save ends it`() {
        val dac = markerDac()
        val session = FiioHidSession(dac, melody)
        fun preset() = (FiioEqReader.readPreset(session) as EqRead.Read).value
        fun set(command: Int, data: ByteArray) {
            dac.write(FiioHidFrames.outputReport(melody.reportId, FiioHidWrites.setFrame(command, data, FiioCrcMode.MAXIM), 64))
            session.drainStale()
        }
        // Close EQ: band reads do not move it
        assertThat(preset()).isEqualTo(0xF0)
        FiioEqReader.readBand(session, 0)
        assertThat(preset()).isEqualTo(0xF0)
        // USER 3 selected: named, also after the count and the gain; the first band read ends the naming
        set(FiioHidWrites.CMD_PRESET, byteArrayOf(0xA2.toByte()))
        assertThat(preset()).isEqualTo(0xA2)
        FiioEqReader.read(session) // count, preset, gain, then the bands
        assertThat(preset()).isEqualTo(0x0A)
        assertThat(preset()).isEqualTo(0x0A)
        // a save names it again
        set(melody.saveCommand, byteArrayOf(0xA2.toByte()))
        assertThat(preset()).isEqualTo(0xA2)
        // a band SET (the owner's 00:55 log) moves it to the marker too, and a select of another slot ends it
        set(FiioHidWrites.CMD_BAND, FiioHidWrites.encodeBand(DacEqBand.of(2, FiioFilterType.PEAK, 125, 0.0, 0.71)))
        assertThat(preset()).isEqualTo(0x0A)
        set(FiioHidWrites.CMD_PRESET, byteArrayOf(0xA0.toByte()))
        assertThat(preset()).isEqualTo(0xA0)
        FiioEqReader.readBand(session, 1)
        assertThat(preset()).isEqualTo(0x0A)
        // a button on the DAC ends it as a select does; a factory preset never has it
        dac.selectOnDevice(0xA1)
        assertThat(preset()).isEqualTo(0xA1)
        dac.selectOnDevice(0x03)
        FiioEqReader.readBand(session, 1)
        assertThat(preset()).isEqualTo(0x03)
    }

    @Test
    fun `a plan of 0 steps after the take-over ends on the app's slot (the 19-23-29 case)`() {
        val r = Rig()
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.backup().ownedSlots).containsExactly(0xA2)
        val setsBefore = r.dac.sets.size
        val applied = r.apply()
        assertThat(applied).isInstanceOf(DacEqWriteResult.Done::class.java)
        val done = applied as DacEqWriteResult.Done
        // nothing but the select went out: the curve was already there
        assertThat(r.dac.sets.drop(setsBefore).map { it.first }).containsExactly(FiioHidWrites.CMD_PRESET)
        assertThat(r.dac.activePreset).isEqualTo(0xA2)
        // the state handed on says the app's slot, and says what the DAC really answered
        assertThat((done.state.activePreset as EqRead.Read).value).isEqualTo(0xA2)
        assertThat(done.state.presetReportedAs).isEqualTo(0x0A)
        assertThat(r.lines("DAC_HID|PRESET").any { it.contains("read as 0xA2") }).isTrue()
        assertThat(r.lines("DAC_HID|RESULT APPLY_CURVE").single()).contains("done:")
        assertThat(r.lines("DAC_HID|MISMATCH")).isEmpty()
    }

    @Test
    fun `an apply with steps passes - the save puts the name back before the read-back`() {
        val r = Rig()
        r.takeOver()
        val applied = r.apply(r.curve())
        assertThat(applied).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.sets.last().first).isEqualTo(melody.saveCommand)
        assertThat(r.slot(0xA2).bands[3].gainTenthsDb).isEqualTo(-30)
        assertThat(r.dac.activePreset).isEqualTo(0xA2)
    }

    @Test
    fun `one tap from the owner's DAC (USER 3 owned and flat, the selection on Close EQ) selects, writes, saves and is done`() {
        val r = Rig()
        r.takeOver() // the state the owner left it in on 11-06: slot owned, flat, the selection back on Close EQ
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        r.log.clear()
        val setsBefore = r.dac.sets.size
        val outcome = r.go(r.curve())
        assertThat(outcome.result).isInstanceOf(DacEqWriteResult.Done::class.java)
        // owned: no pause, no question, no take-over: the apply, and nothing before it
        assertThat(r.lines("DAC_HID|TURN_ON start").single()).contains("slotOwned=true")
        assertThat(r.lines("DAC_HID|OP ").map { it.substringAfter("OP ").substringBefore(' ') }).containsExactly("APPLY_CURVE")
        val sent = r.dac.sets.drop(setsBefore)
        assertThat(sent.first().first).isEqualTo(FiioHidWrites.CMD_PRESET) // select USER 3
        assertThat(sent.first().second[0].toInt() and 0xFF).isEqualTo(0xA2)
        assertThat(sent.last().first).isEqualTo(melody.saveCommand)
        assertThat(sent.map { it.first }.count { it == melody.saveCommand }).isEqualTo(1)
        assertThat(r.dac.activePreset).isEqualTo(0xA2)
        assertThat(r.lines("DAC_HID|TURN_ON done").single()).contains("the DAC's EQ is on")
    }

    @Test
    fun `turning the EQ on with a curve that is already there (0 steps) ends TURN_ON done, from the take-over on`() {
        val r = Rig(markerDac(), withBackup = false)
        val outcome = r.go() // flat: the take-over leaves exactly this, so the apply has nothing to write
        assertThat(outcome.result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.lines("DAC_HID|TURN_ON done")).hasSize(1)
        assertThat(r.lines("DAC_HID|TURN_ON ended")).isEmpty()
        // the slot was chosen by the app (USER 1 holds nothing) and taken over
        val slot = r.backup().appSlotFor(melody)
        assertThat(r.backup().ownedSlots).containsExactly(slot)
        assertThat(r.dac.activePreset).isEqualTo(slot)
    }

    @Test
    fun `restore-factory passes against the model and gives the slot's own curve back`() {
        val r = Rig()
        r.takeOver()
        r.apply(r.curve())
        val factoryBands = r.backup().slots.getValue(0xA2).bands
        val result = r.restoreFactory()
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.backup().ownedSlots).isEmpty()
        assertThat(r.slot(0xA2).bands.map { it.gainTenthsDb }).isEqualTo(factoryBands.map { it.gainTenthsDb })
        assertThat(r.dac.activePreset).isEqualTo(0xF0) // moved off the factory curve, as before
    }

    @Test
    fun `a second apply while the slot is still selected and the DAC answers the marker does not select it again`() {
        val r = Rig()
        r.takeOver()
        r.apply(r.curve())
        val selectsBefore = r.selects().size
        val second = r.apply(r.flat().mapIndexed { i, b -> if (i == 6) b.copy(gainTenthsDb = -20) else b })
        assertThat(second).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.selects().size).isEqualTo(selectsBefore) // the marker was read as USER 3: no select
        assertThat(r.slot(0xA2).bands[6].gainTenthsDb).isEqualTo(-20)
    }

    @Test
    fun `a new attachment knows nothing - the marker stays 0x0A, the app selects its slot again, and never sends select 0x0A`() {
        val r = Rig()
        r.takeOver()
        r.apply(r.curve())
        r.writer = r.newWriter() // the app was restarted: a new HID access, no claims; the DAC still answers the marker on USER 3
        r.log.clear()
        val again = r.apply(r.flat().mapIndexed { i, b -> if (i == 6) b.copy(gainTenthsDb = -20) else b })
        assertThat(again).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.lines("DAC_HID|PRESET").any { it.contains("cannot say which preset") }).isTrue()
        assertThat(r.selects()).doesNotContain(0x0A)
        assertThat(r.selects().last()).isEqualTo(0xA2)
    }

    @Test
    fun `a backup is not made from a pointer the model does not list`() {
        val r = Rig(markerDac(), withBackup = false)
        val state = FiioEqReader.read(FiioHidSession(r.dac, melody))
        assertThat(DacEqBackup.of(r.att, state, 1L)).isNotNull()
        val unnamed = state.copy(activePreset = EqRead.Read(0x0A))
        assertThat(DacEqBackup.of(r.att, unnamed, 1L)).isNull()
    }

    // ── a DAC selected elsewhere is still a mismatch and a restore ──────────────────────────────────────

    /** Calls [action] just before the [n]-th `GET 18` (the first request of a whole-state read) goes to the DAC. */
    private fun atRead(dac: FakeFiioDac, n: Int, action: () -> Unit) {
        var reads = 0
        dac.onWrite = { report ->
            val head = report[1].toInt() and 0xFF
            val command = report[5].toInt() and 0xFF
            if (head == 0xBB && command == 0x18 && ++reads == n) action()
        }
    }

    @Test
    fun `a select made on the DAC between two reads, answered with its own id, is a mismatch and a restore`() {
        val dac = markerDac()
        factoryPattern(dac, 0xA0)
        val r = Rig(dac)
        r.takeOver()
        // the apply reads three times: before, after selecting USER 3, and the read-back; a button press just before the third
        var presses = 0
        atRead(dac, 3) { presses++; dac.selectOnDevice(0xA0) }
        val result = r.apply()
        assertThat(presses).isEqualTo(1)
        assertThat(result).isInstanceOf(DacEqWriteResult.Mismatch::class.java)
        assertThat((result as DacEqWriteResult.Mismatch).restored).isTrue()
        assertThat(r.lines("DAC_HID|MISMATCH").single()).contains("(selected 0xA0)")
        assertThat(dac.activePreset).isEqualTo(0xF0)
        assertThat(r.selects()).doesNotContain(0x0A)
    }

    @Test
    fun `a select made on the DAC that the marker hides is a mismatch and a restore - the bands are not the ones the app has seen`() {
        val dac = markerDac()
        factoryPattern(dac, 0xA0)
        val r = Rig(dac)
        r.takeOver()
        atRead(dac, 3) { dac.selectOnDevice(0xA0, hiddenByMarker = true) }
        val result = r.apply()
        assertThat(result).isInstanceOf(DacEqWriteResult.Mismatch::class.java)
        assertThat((result as DacEqWriteResult.Mismatch).restored).isTrue()
        assertThat(r.lines("DAC_HID|MISMATCH").single()).contains("(selected 0x0A)")
        assertThat(r.lines("DAC_HID|PRESET").any { it.contains("cannot say which preset") }).isTrue()
        assertThat(dac.activePreset).isEqualTo(0xF0)
        assertThat(r.selects()).doesNotContain(0x0A)
    }

    // ── the restore of a failed take-over, which the marker used to turn into "left alone" ───────────────

    @Test
    fun `a take-over that fails after its first SET puts the slot's own curve back, with the marker on`() {
        val dac = markerDac().also { d -> d.storeBand = { b -> if (b.qHundredths == 71) b.copy(qHundredths = 69) else b } }
        val r = Rig(dac)
        val factory = r.slot(0xA2)
        val taken = r.takeOver()
        assertThat(taken).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat((taken as DacEqWriteResult.Failed).restored).isTrue()
        // before: "RESTORE slot 0xA2 left alone: SLOT_UNSAFE_TO_SELECT" (the marker read as another preset), and the slot kept what the take-over had written
        assertThat(r.lines("DAC_HID|RESTORE slot").none { it.contains("left alone") }).isTrue()
        assertThat(r.slot(0xA2).bands.map { it.gainTenthsDb }).isEqualTo(factory.bands.map { it.gainTenthsDb })
        assertThat(r.slot(0xA2).preGainTenthsDb).isEqualTo(factory.preGainTenthsDb)
        assertThat(dac.activePreset).isEqualTo(0xF0)
        assertThat(r.backup().ownedSlots).isEmpty()
    }

    @Test
    fun `every older Melody scenario still holds with the marker off`() {
        // the model is off by default: the take-over and the apply behave as in B59
        val r = Rig(RecordedMelody.fakeDac(0xF0, melody))
        assertThat(r.takeOver()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.apply(r.curve())).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.lines("DAC_HID|PRESET")).isEmpty()
    }
}
