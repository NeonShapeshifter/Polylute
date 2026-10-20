package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.lostf1sh.pixelplayeross.data.service.player.DebugFlagStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * B31 P1: **what the reviewer's runbook will print, from the fake Melody** (the one that holds what the owner's held: [RecordedMelody]).
 *
 * Not a check: it writes, once per debug command of the runbook, the lines that command logs against the fake DAC, so the runbook's
 * "expect" is a transcript of the real code and not a description of it. Opt-in (`B31_TRANSCRIPTS=1`); the folder is
 * `$B31_TRANSCRIPTS_OUT` or `B31-evidence/fake-transcripts`. The Melody will differ in the values it reports, not in the shape of the lines
 * (and its own acknowledgements, which nobody has seen yet, are the point of the first run: the fake answers with the SET head the KA15 capture shows).
 */
class DacEqTranscriptsTest {
    private val real = FiioHidProfiles.all.single { it.id == "snowsky-melody" }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
            HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    private class MemoryFlag(var value: Boolean) : DebugFlagStore {
        override fun read() = value
        override fun write(value: Boolean) { this.value = value }
    }

    private class MemoryText(var value: String? = null) : DebugTextStore {
        override fun read() = value
        override fun write(value: String?) { this.value = value }
    }

    private class Session(val real: FiioHidProfile) {
        val senseSwitch = DacEqPreGainSenseSwitch(MemoryText(), debugBuild = true)
        val dac = RecordedMelody.fakeDac(0xF0, real)
        val att = AttachedFiio("path-a", "identity-a", "dac-a", "Melody", "path-a", real)
        val dir: File = Files.createTempDirectory("dac-eq-transcripts").toFile()
        var playing = false
        val attachedFlow = MutableStateFlow<AttachedFiio?>(att)
        val controller = DacEqController(attachedFlow, MutableStateFlow(true), DacEqHidAccess(FakeOpener(dac), Dispatchers.Unconfined), { 0L }, settleMs = 10L, attachedNow = { attachedFlow.value })
        val service = DacEqService(
            controller, DacEqBackupStore(dir), DacEqWriteSwitch(MemoryFlag(true), debugBuild = true),
            elapsedMs = { 0L }, wallClockMs = { 1_700_000_000_000L }, pause = {},
            senseSwitch = senseSwitch, playbackActive = { playing }, ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun out(name: String, lines: List<String>) {
        val dir = File(System.getenv("B31_TRANSCRIPTS_OUT") ?: "../../../analysis/audit-2026-09-27/batches/B31-evidence/fake-transcripts")
        dir.mkdirs()
        File(dir, "$name.txt").writeText(lines.joinToString("\n") + "\n")
    }

    /** What `DacEqDebugCommands` logs before it does anything, then what the command logs. */
    private fun command(s: Session, action: String, block: (sink: (String) -> Unit) -> Unit): List<String> {
        val lines = ArrayList<String>()
        val sink: (String) -> Unit = { lines += it }
        sink(
            "DAC_HID|$action dac=${s.att.displayName} profile=${s.att.profile.id} verified=${s.att.profile.verified} override=${s.service.writeSwitch.isAllowed()} " +
                "preGainSense=${s.service.sense(s.att)} playing=${s.service.isPlaying()} availability=${s.service.availability(s.att)}"
        )
        block(sink)
        return lines
    }

    private fun report(sink: (String) -> Unit, result: DacEqWriteResult) {
        sink("DAC_HID|RESULT $result")
        if (result is DacEqWriteResult.Done) DacEqStateFormat.lines(result.state, "DAC_HID|FINAL").forEach(sink)
    }

    @Test
    fun `the transcripts of the runbook against the fake Melody`() {
        Assumptions.assumeTrue(System.getenv("B31_TRANSCRIPTS") == "1")
        val s = Session(real)
        runBlocking {
            out("01-read", command(s, "DAC_EQ_READ") { sink -> runBlocking { s.service.read(s.att, sink) } })
            out("02-backup", command(s, "DAC_EQ_BACKUP") { sink ->
                val result = runBlocking { s.service.writer(sink = sink).backup(s.att, false) }
                if (result is DacEqBackupResult.Created) sink("DAC_HID|BACKUP stored: key=${s.att.backupKey} active=0x%02X slots=${result.backup.slots.keys.map { "0x%02X".format(it) }}".format(result.backup.activePresetId))
            })
            s.playing = true
            out("03a-inventory-refused-while-playing", command(s, "DAC_EQ_INVENTORY") { sink ->
                report(sink, runBlocking { s.service.writer(sink = sink).inventory(s.att, WriteInitiator.REVIEWER_COMMAND) })
            })
            s.playing = false
            out("03b-inventory", command(s, "DAC_EQ_INVENTORY") { sink ->
                report(sink, runBlocking { s.service.writer(sink = sink).inventory(s.att, WriteInitiator.REVIEWER_COMMAND) })
            })
            s.playing = true
            out("04a-take-over-refused-while-playing", command(s, "DAC_EQ_TAKE_OVER") { sink ->
                report(sink, runBlocking { s.service.writer(sink = sink).takeOver(s.att, WriteInitiator.REVIEWER_COMMAND) })
            })
            s.playing = false
            out("04b-take-over", command(s, "DAC_EQ_TAKE_OVER") { sink ->
                report(sink, runBlocking { s.service.writer(sink = sink).takeOver(s.att, WriteInitiator.REVIEWER_COMMAND) })
            })
            s.playing = true
            out("05-listen-check", command(s, "DAC_EQ_LISTEN_CHECK") { sink ->
                report(sink, runBlocking { s.service.writer(sink = sink).listeningCheck(s.att, lowLevel = true) })
            })
            s.playing = false
            out("06-roundtrip", command(s, "DAC_EQ_ROUNDTRIP") { sink ->
                report(sink, runBlocking { s.service.writer(sink = sink).roundtrip(s.att, WriteInitiator.REVIEWER_COMMAND) })
            })
            out("07a-set-band-5-plus1-refused-sense-unconfirmed", command(s, "DAC_EQ_SET_BAND") { sink ->
                report(sink, runBlocking { s.service.writer(sink = sink).setBand(s.att, 4, 1.0, WriteInitiator.REVIEWER_COMMAND) })
            })
            out("07b-set-band-5-minus1-sense-unconfirmed", command(s, "DAC_EQ_SET_BAND") { sink ->
                report(sink, runBlocking { s.service.writer(sink = sink).setBand(s.att, 4, -1.0, WriteInitiator.REVIEWER_COMMAND) })
            })
            // The reviewer's answer to the listening check: a positive register amplifies.
            out("07c-set-dac-eq-pre-gain-sense-boost", listOf("DAC_HID|SET_DAC_EQ_PRE_GAIN_SENSE").let { head ->
                s.senseSwitch.set(PreGainSense.BOOST)
                head + "DAC_HID|DAC_EQ_PRE_GAIN_SENSE override=${s.senseSwitch.override() ?: "none"} (a debug build's persisted answer to the listening check; a release build ignores it)"
            })
            out("07d-set-band-5-plus1-sense-boost", command(s, "DAC_EQ_SET_BAND") { sink ->
                report(sink, runBlocking { s.service.writer(sink = sink).setBand(s.att, 4, 1.0, WriteInitiator.REVIEWER_COMMAND) })
            })
            out("07e-set-band-5-minus1-sense-boost", command(s, "DAC_EQ_SET_BAND") { sink ->
                report(sink, runBlocking { s.service.writer(sink = sink).setBand(s.att, 4, -1.0, WriteInitiator.REVIEWER_COMMAND) })
            })
            s.senseSwitch.set(PreGainSense.ATTENUATION)
            out("07f-set-band-5-plus1-sense-attenuation", command(s, "DAC_EQ_SET_BAND") { sink ->
                report(sink, runBlocking { s.service.writer(sink = sink).setBand(s.att, 4, 1.0, WriteInitiator.REVIEWER_COMMAND) })
            })
            s.senseSwitch.set(PreGainSense.UNCONFIRMED)
            out("08-after-replug-read", command(s, "DAC_EQ_READ") { sink -> runBlocking { s.service.read(s.att, sink) } })
            out("09-restore-factory", command(s, "DAC_EQ_RESTORE_FACTORY") { sink ->
                report(sink, runBlocking { s.service.writer(sink = sink).restoreFactory(s.att, WriteInitiator.REVIEWER_COMMAND) })
            })
        }
    }
}
