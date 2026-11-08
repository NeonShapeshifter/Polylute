package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B58 3.1 (#53): the DAC EQ writer says what it does. The owner turned the DAC's EQ on and the screen said "The DAC's EQ was not changed / Talking to the DAC failed" with
 * nothing in the log to say why. Every step of a write is a `DAC_HID` line (tag `DAC_HID`: the operation, each frame sent with its command and length, each reply or the lack of
 * one, the result, and whether the selection was put back), and the two ends that used to be silent (a session that could not run, a DAC that could not be opened) say so too.
 *
 * Over the scripted fake DAC: a success, a pulled cable, a DAC that ignores one SET (the read-back differs and the old state is restored), a DAC that wants another check byte
 * (a bad CRC: nothing is acknowledged), a restore that cannot be done, an interface that cannot be opened and a session that throws. Nothing is sent to a real DAC.
 */
class B58DacEqWriterLogTest {
    private val melody = signedMelody
    private val directories = mutableListOf<File>()

    @AfterEach fun cleanUp() = directories.forEach { it.deleteRecursively() }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> = HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    private inner class Rig(
        val dac: FakeFiioDac = FakeFiioDac(melody, activePreset = 0xF0),
        options: FiioWriteOptions = FiioWriteOptions(),
        opener: FiioHidOpener = FakeOpener(dac),
    ) {
        val dir: File = Files.createTempDirectory("dac-eq-backups-b58").toFile().also { directories += it }
        val store = DacEqBackupStore(dir)
        val log = mutableListOf<String>()
        val att = AttachedFiio("/dev/bus/usb/001/005", "dac-model", "profile-1", "SNOWSKY Melody", "/dev/bus/usb/001/005", melody)
        val writer = DacEqWriter(
            access = DacEqHidAccess(opener, Dispatchers.Unconfined), gate = DacEqWriteGate(options), backups = store, overrideAllowsWrites = { true },
            elapsedMs = { 0L }, wallClockMs = { 1_700_000_000_000L }, pause = {}, log = { log += it },
        )

        /** The SETs the first look at the app's slot sent: the fake counts SETs from the start, so a fault "at the second SET of the write" is this many later. */
        var lookSets = 0

        init {
            if (opener is FakeOpener) {
                check(runBlocking { writer.discoverSlot(att) } is DacEqWriteResult.Done)
                lookSets = dac.sets.size
                dac.written.clear()
                log.clear()
            }
        }

        fun apply(vararg gains: Pair<Int, Double>): DacEqWriteResult {
            val map = gains.toMap()
            val bands = (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], map[it] ?: 0.0, 0.71) }
            return runBlocking { writer.applyCurve(att, bands, WriteInitiator.USER_ACTION) }
        }

        fun lines(prefix: String) = log.filter { it.startsWith(prefix) }
    }

    @Test
    fun `a write that works says the operation, every frame, every acknowledgement and the result`() {
        val r = Rig()
        assertThat(r.apply(3 to 2.0)).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.lines("DAC_HID|OP APPLY_CURVE")).hasSize(1)
        assertThat(r.lines("DAC_HID|TX SET").size).isAtLeast(3)
        // each SET frame names its command and its length: `TX SET <name> ret=<sent> of <length>`
        r.lines("DAC_HID|TX SET").forEach { assertThat(it).containsMatch("ret=\\d+ of 64") }
        assertThat(r.lines("DAC_HID|ACK").size).isAtLeast(3)
        assertThat(r.lines("DAC_HID|RESULT APPLY_CURVE done")).hasSize(1)
    }

    @Test
    fun `a DAC that wants another check byte is a bad CRC and the log shows nothing acknowledged and the failure`() {
        val r = Rig()
        r.dac.requireCrc = FiioCrcMode.ZERO
        val result = r.apply(2 to 2.0)
        assertThat(result).isNotInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.rejectedSetCrcs).isGreaterThan(0)
        // the SET went out (a TX line) and the DAC never acknowledged it (an ACK ... none line): a timeout, said
        assertThat(r.lines("DAC_HID|TX SET")).isNotEmpty()
        assertThat(r.log.any { it.startsWith("DAC_HID|ACK") && it.contains("none within") }).isTrue()
        assertThat(r.lines("DAC_HID|RESULT APPLY_CURVE")).hasSize(1)
        assertThat(r.lines("DAC_HID|RESULT APPLY_CURVE").single()).doesNotContain("done:")
    }

    @Test
    fun `a DAC that ignores the pre-gain SET is a failure, and the log says what was not confirmed and what was put back`() {
        val r = Rig()
        r.dac.ignoreNthSet = r.lookSets + 2 // of the write: 1 select, 2 pre-gain (the scripted fault the confirmed-plan tests use)
        val result = r.apply(4 to 6.0)
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat(r.log.any { it.contains("CONFIRM pre-gain: no usable acknowledgement") }).isTrue()
        val line = r.lines("DAC_HID|RESULT APPLY_CURVE").single()
        assertThat(line).contains("FAILED")
        assertThat(line).contains("pre-gain was not confirmed")
        // whether the selection and the content were put back is always in the line: `restored=true`, `restored=false` or `restored=null` (nothing needed putting back)
        assertThat(line).containsMatch("restored=(true|false|null)")
    }

    @Test
    fun `a DAC pulled out in the middle says that the restore could not be done`() {
        val r = Rig()
        r.apply(1 to 1.0)
        r.log.clear()
        var sets = 0
        r.dac.onWrite = { report -> if ((report[1].toInt() and 0xFF) == 0xAA && ++sets == 3) r.dac.failWritesAfter = r.dac.written.size }
        val result = r.apply(1 to 2.0, 6 to 2.0, 8 to 2.0)
        assertThat(result is DacEqWriteResult.Failed || result is DacEqWriteResult.Mismatch).isTrue()
        val line = r.lines("DAC_HID|RESULT APPLY_CURVE").single()
        assertThat(line).containsMatch("MISMATCH|FAILED")
        assertThat(line).doesNotContain("restored=true")
        assertThat(r.log.any { it.startsWith("DAC_HID|ERROR") || it.contains("could not") || it.contains("SHORT WRITE") || it.contains("ret=-") }).isTrue()
    }

    @Test
    fun `an interface that cannot be opened is said with its reason`() {
        val dir = Files.createTempDirectory("dac-eq-b58-un").toFile().also { directories += it }
        val log = mutableListOf<String>()
        val writer = DacEqWriter(
            access = DacEqHidAccess(object : FiioHidOpener {
                override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> = HidUse.Unavailable("no USB permission for this DAC")
            }, Dispatchers.Unconfined),
            gate = DacEqWriteGate(), backups = DacEqBackupStore(dir), overrideAllowsWrites = { true }, elapsedMs = { 0 }, wallClockMs = { 1 }, pause = {}, log = { log += it },
        )
        val att = AttachedFiio("/dev/bus/usb/001/005", "dac-model", "profile-1", "SNOWSKY Melody", "/dev/bus/usb/001/005", melody)
        val bands = (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 0.0, 0.71) }
        val result = runBlocking { writer.applyCurve(att, bands, WriteInitiator.USER_ACTION) }
        assertThat(result).isEqualTo(DacEqWriteResult.Unavailable("no USB permission for this DAC"))
        assertThat(log).contains("DAC_HID|UNAVAILABLE APPLY_CURVE stale=false: no USB permission for this DAC")
    }

    @Test
    fun `a session that throws is said, with the class and the message, and ends as unavailable`() {
        val dir = Files.createTempDirectory("dac-eq-b58-th").toFile().also { directories += it }
        val log = mutableListOf<String>()
        val writer = DacEqWriter(
            access = DacEqHidAccess(object : FiioHidOpener {
                override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> = throw SecurityException("USB permission revoked")
            }, Dispatchers.Unconfined),
            gate = DacEqWriteGate(), backups = DacEqBackupStore(dir), overrideAllowsWrites = { true }, elapsedMs = { 0 }, wallClockMs = { 1 }, pause = {}, log = { log += it },
        )
        val att = AttachedFiio("/dev/bus/usb/001/005", "dac-model", "profile-1", "SNOWSKY Melody", "/dev/bus/usb/001/005", melody)
        val bands = (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 0.0, 0.71) }
        val result = runBlocking { writer.applyCurve(att, bands, WriteInitiator.USER_ACTION) }
        assertThat(result).isInstanceOf(DacEqWriteResult.Unavailable::class.java)
        assertThat(log).contains("DAC_HID|ERROR APPLY_CURVE the HID session could not run: SecurityException: USB permission revoked")
    }

    @Test
    fun `the screen writes what it tells the listener, with the reason the screen itself drops`() {
        val controller = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/DacEqScreenController.kt").readText()
        assertThat(controller).contains("describeWriteResult(result)")
        assertThat(describeWriteResult(DacEqWriteResult.Failed("the save could not be sent", restored = false, state = null))).isEqualTo("FAILED the save could not be sent restored=false")
    }
}
