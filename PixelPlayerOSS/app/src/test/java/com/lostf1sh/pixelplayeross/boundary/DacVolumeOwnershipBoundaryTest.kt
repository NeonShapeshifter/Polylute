package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B15-F1: `DacVolumeArbiterTest` and `DirectDuckInterleavingTest` test the rule; the volume authority is Android-bound
 * (AudioManager, a claimed USB device), so that every write to the DAC's volume really goes through the rule is checked
 * at source level, as the rest of this area is.
 */
class DacVolumeOwnershipBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    private val controller = File("src/main", "$java/data/volume/SafetyVolumeController.kt")
        .readText().replace("\r\n", "\n")
    private val engine = File("src/main", "$java/data/service/player/DualPlayerEngine.kt")
        .readText().replace("\r\n", "\n")
    private val arbiter = File("src/main", "$java/data/volume/DacVolumeArbiter.kt")
        .readText().replace("\r\n", "\n")
    private val device = File("../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioDevice.kt")
        .readText().replace("\r\n", "\n")

    /** The `{ ... }` block of the first function whose declaration starts with [signature]. */
    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val open = source.indexOf('{', source.indexOf(')', start))
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced $signature")
    }

    private val lockWords = listOf("synchronized(", "@Synchronized", "Mutex", "ReentrantLock", ".withLock", "runBlocking", "sessionLock")

    @Test
    fun `the controller writes the DAC's volume from exactly two places, each through the arbiter`() {
        val writes = Regex("""usbDevice\.writeHardwareVolume\(""").findAll(controller).count()
        assertThat(writes).isEqualTo(2)

        val listener = controller.substringAfter("private fun setBackendRaw(").substringBefore("selfWriteRaw = raw")
        assertThat(listener).contains("dacVolume.listenerWrite {")
        assertThat(listener.substringAfter("dacVolume.listenerWrite {").substringBefore("}"))
            .contains("usbDevice.writeHardwareVolume(")

        val duck = controller.substringAfter("fun setHardwareStepTemporarily(").substringBefore("/** Sets the app's digital level")
        assertThat(duck).contains("dacVolume.temporaryWrite(")
        assertThat(duck).contains("usbDevice.writeHardwareVolume(")
    }

    @Test
    fun `the duck's read of the DAC takes the ticket with it, and a lowering at open or another DAC supersedes`() {
        val read = controller.substringAfter("fun pristineHardwareVolume()").substringBefore("/** F-02: the Safety Max ceiling")
        assertThat(read).contains("dacVolume.observe {")
        assertThat(read).contains("ticket = ticket")
        assertThat(controller).contains("if (target != null) dacVolume.supersede()")
        assertThat(controller).contains("previousContext != backend.outputContextId")
    }

    // ── B16-G1: one lock, the device's ─────────────────────────────────────────────────────────

    @Test
    fun `the arbiter is built on the USB device's own monitor and owns no lock of its own`() {
        assertThat(controller).contains("private val dacVolume = DacVolumeArbiter(usbDevice)")
        // The only monitor it ever enters is the one it was given.
        val entered = Regex("""synchronized\(([^)]*)\)""").findAll(arbiter).map { it.groupValues[1] }.toSet()
        assertThat(entered).containsExactly("lock")
        assertThat(arbiter).doesNotContain("= Any()")
        assertThat(arbiter).doesNotContain("Mutex")
        assertThat(arbiter).contains("class DacVolumeArbiter(private val lock: Any)")
        // The order is written down at both classes, as one sentence.
        assertThat(arbiter).contains("the only lock there is")
        assertThat(device).contains("is the only lock on the DAC's volume")
    }

    @Test
    fun `the driver's volume methods lock the device itself and no other lock is in that part of the file`() {
        for (signature in listOf("fun readHardwareVolume(", "fun writeHardwareVolume(", "fun restoreLevelAfterReconfigure(")) {
            val at = device.indexOf(signature)
            assertThat(at).isAtLeast(0)
            assertThat(device.substring((at - 60).coerceAtLeast(0), at)).contains("@Synchronized")
        }
        val region = device.substringAfter("// ── Hardware volume (UAC2 Feature Unit)").substringBefore("private fun dbOf(")
        assertThat(region).doesNotContain("synchronized(")
        assertThat(region).doesNotContain("Mutex")
        assertThat(region).doesNotContain("ReentrantLock")
    }

    @Test
    fun `the callbacks the driver runs under its monitor are the volume guard only, and the guard takes no lock`() {
        // `volumeGuard` is invoked from the two restore functions; the release listeners are called from a function that is
        // not synchronized.
        assertThat(body(device, "fun restoreLevelAfterReconfigure(")).contains("volumeGuard")
        val release = device.indexOf("fun releaseToAndroid()")
        assertThat(device.substring((release - 40).coerceAtLeast(0), release)).doesNotContain("@Synchronized")

        val guard = body(controller, "private fun hardwareOpenTarget(")
        for (word in lockWords) assertThat(guard).doesNotContain(word)
        assertThat(guard).contains("dacVolume.supersede()")
        // What it reads for the verdict is lock-free too.
        val observation = body(controller, "private fun observationFor(")
        for (word in lockWords) assertThat(observation).doesNotContain(word)
        // The override session it reads is a volatile field, replaced under the session lock but never read under it here.
        assertThat(controller).contains("@Volatile\n    private var overrideSession")
    }

    @Test
    fun `nothing that holds the session lock reaches the DAC or the arbiter`() {
        // The one lock the controller has besides the device's. If any block holding it called into the DAC, the device
        // monitor would be taken under it; and the guard (under the device monitor) never takes it: the order is closed.
        val blocks = Regex("""synchronized\(sessionLock\) \{""").findAll(controller).count() +
            Regex("""synchronized\(sessionLock\)""").findAll(controller).count()
        assertThat(blocks).isGreaterThan(0)
        for (name in listOf("evaluateOverride", "endOverride", "onPlaybackChanged", "restoreOverride", "commitOverride", "forgetOverride")) {
            val code = body(controller, "private fun $name(")
            assertThat(code).doesNotContain("usbDevice.")
            assertThat(code).doesNotContain("dacVolume.")
        }
    }

    // ── B16-G2: the ceiling is judged inside the compare-and-set ────────────────────────────────

    @Test
    fun `a duck step is judged against the ceiling as the store holds it at the moment of the write`() {
        val duck = controller.substringAfter("fun setHardwareStepTemporarily(").substringBefore("/** Sets the app's digital level")
        assertThat(duck).contains("step = step")
        assertThat(duck).contains("ceilingStep = ::pristineCeilingStep")
        val ceiling = body(controller, "fun pristineCeilingStep(")
        assertThat(ceiling).contains("SafetyVolumePolicy.ceilingNow(")
        assertThat(ceiling).contains("profiles.state.value.selectedProfile")
        for (word in lockWords) assertThat(ceiling).doesNotContain(word)
    }

    @Test
    fun `the engine takes a focus number at the top of the callback and drops a pause a newer event has overtaken`() {
        val callback = engine.substringAfter("private val focusChangeListener").substringBefore("private val masterPlayerListener")
        assertThat(callback.substringAfter("{").trimStart()).startsWith("focusChange ->\n        val focusEvent = focusSequence.next()")
        for (event in listOf("LOSS", "LOSS_TRANSIENT", "CAN_DUCK", "GAIN")) {
            assertThat(callback).contains("onFocusEvent(DuckFocusEvent.$event, focusEvent)")
        }
        val pause = engine.substringAfter("fun pauseForFocusDuck(").substringBefore("/** A Direct duck that could not attenuate")
        assertThat(pause).contains("if (!focusSequence.isCurrent(generation))")
        assertThat(pause.indexOf("isCurrent")).isLessThan(pause.indexOf("pauseForTransientFocusLoss()"))
    }
}
