package com.lostf1sh.pixelplayeross.data.diagnostics

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B40-F7: what makes the detailed log reach a release build and what keeps it clean, pinned where the code is: R8 keeps the info lines,
 * the tree is planted, the sources are hooked, the backup leaves it out, the privacy text says it, and the lines of the tags it writes
 * name no song.
 */
class DetailedLoggingWiringTest {
    private val project: File = listOf(File("."), File(".."), File("../.."))
        .map { it.canonicalFile }.first { File(it, "app/src/main").isDirectory || File(it, "src/main").isDirectory }
        .let { if (File(it, "app/src/main").isDirectory) it else it.parentFile }

    private fun file(relative: String) = File(project, relative)
    private fun text(relative: String) = file(relative).readText()
    private val sources get() = file("app/src/main/java/com/lostf1sh/pixelplayeross")
    private fun source(relative: String) = File(sources, relative).readText()

    private fun rule(name: String): String =
        text("app/proguard-rules.pro").substringAfter("-assumenosideeffects class $name {").substringBefore("}")

    @Test
    fun `release keeps the info lines of Timber and strips verbose and debug as before`() {
        listOf("timber.log.Timber", "timber.log.Timber\$Tree").forEach { cls ->
            val block = rule(cls)
            assertThat(block).contains("void v(...)")
            assertThat(block).contains("void d(...)")
            assertThat(block).doesNotContain("void i(...)")
        }
    }

    @Test
    fun `the USB driver's android util Log info lines stay out of release, so nothing it names reaches the system log`() {
        val block = rule("android.util.Log")
        assertThat(block).contains("int v(...)")
        assertThat(block).contains("int d(...)")
        assertThat(block).contains("int i(...)")
    }

    @Test
    fun `the application plants the tree next to the release one`() {
        val app = source("PixelPlayerApplication.kt")
        assertThat(app).contains("Timber.plant(ReleaseTree())")
        assertThat(app).contains("Timber.plant(detailedLogging.get().tree)")
    }

    @Test
    fun `the DAC conversation's trace is an info line, so it is what a release build records`() {
        val module = source("di/DacEqModule.kt")
        assertThat(Regex("""Timber\.tag\("DAC_HID"\)\.i\(line\)""").findAll(module).count()).isEqualTo(2)
        assertThat(DetailedLogPolicy.TECHNICAL_TAGS).contains("DAC_HID")
    }

    @Test
    fun `the hardware events and the route trace reach the log through its own door`() {
        assertThat(source("data/diagnostics/HardwareEventLog.kt")).contains("DetailedLog.record(\"Hardware\")")
        assertThat(source("data/service/player/DualPlayerEngine.kt")).contains("DetailedLog.record(\"RouteTrace\")")
    }

    @Test
    fun `Android's backup leaves the log and its state out`() {
        listOf("app/src/main/res/xml/backup_rules.xml", "app/src/main/res/xml/data_extraction_rules.xml").forEach {
            val xml = text(it)
            assertThat(xml).contains("path=\"detailed-logs/\"")
            assertThat(xml).contains("path=\"detailed_logging.xml\"")
        }
    }

    @Test
    fun `the privacy text says what is written, what is not, and that it stays on the phone`() {
        val privacy = text("PRIVACY.md")
        assertThat(privacy).contains("**Detailed log.**")
        assertThat(privacy).contains("files/detailed-logs/")
        assertThat(privacy).contains("off by default")
        assertThat(privacy).contains("30 minutes")
        assertThat(privacy).contains("Include song titles and file paths")
        assertThat(privacy).contains("The app never sends it")
        assertThat(privacy).contains("This page describes version 1.1.0.")
    }

    @Test
    fun `the section is on the Maintenance page and its words exist in all three languages`() {
        assertThat(source("presentation/screens/MaintenanceScreen.kt")).contains("MaintenanceDetailedLogging(")
        val keys = Regex("""R\.string\.(detailed_logging_\w+)""").findAll(
            source("presentation/screens/MaintenanceDetailedLogging.kt") + source("data/diagnostics/DetailedLoggingService.kt"),
        ).map { it.groupValues[1] }.toSet()
        assertThat(keys).isNotEmpty()
        listOf("values", "values-es", "values-sv").forEach { dir ->
            val xml = text("app/src/main/res/$dir/strings_detailed_logging.xml")
            keys.forEach { key -> assertThat(xml).contains("name=\"$key\"") }
        }
    }

    @Test
    fun `the state a log carries leaves out the name of an output and the song's title unless titles were asked for`() {
        val route = AudioRouteState(deviceName = "Ale's Galaxy Buds")
        assertThat(NoiseMark.routeSummary(route, withDeviceName = true)).contains("Ale's Galaxy Buds")
        val withheld = NoiseMark.routeSummary(route, withDeviceName = false)
        assertThat(withheld).doesNotContain("Galaxy")
        assertThat(withheld).contains("device=${NoiseMark.WITHHELD}")
        val usb = UsbDirectAudioDiagnostics(deviceName = "Fosi Audio DS2")
        assertThat(NoiseMark.usbSummary(usb, withDeviceName = false)).doesNotContain("Fosi")
        // a noise mark, which the listener makes on purpose, still says it all
        assertThat(NoiseMark.usbSummary(usb)).contains("Fosi Audio DS2")
    }

    // -- the lines of the tags it writes --

    private val mediaWords = Regex("""\b(title|artist|album|playlist|path|uri|displayName|fileName|lyrics|query)\b|\.name\b""", RegexOption.IGNORE_CASE)

    /** The `Timber.tag(X).i/w/e(...)` calls of a file whose tag is one the log writes, as text up to their closing bracket. */
    private fun technicalCalls(code: String): List<String> {
        val constants = Regex("""(?:const )?val\s+(\w*TAG\w*)\s*(?::\s*String)?\s*=\s*"([^"]+)"""").findAll(code).associate { it.groupValues[1] to it.groupValues[2] }
        val out = ArrayList<String>()
        Regex("""Timber\.tag\(([^)]+)\)\.(i|w|e)\(""").findAll(code).forEach { m ->
            val arg = m.groupValues[1].trim()
            val tag = if (arg.startsWith("\"")) arg.trim('"') else constants[arg]
            if (tag !in DetailedLogPolicy.TECHNICAL_TAGS) return@forEach
            var i = m.range.last + 1
            var depth = 1
            while (i < code.length && depth > 0) {
                when (code[i]) {
                    '"' -> { i++; while (code[i] != '"') { if (code[i] == '\\') i++; i++ } }
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            out += code.substring(m.range.first, i)
        }
        return out
    }

    @Test
    fun `no info, warning or error line of a tag the log writes names a song, a playlist or a file`() {
        val offenders = ArrayList<String>()
        sources.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            technicalCalls(f.readText()).forEach { call ->
                // a DAC's own product name (`dac.name`) is the one name these lines carry; it names a device, not media
                val withoutDevice = call.replace("dac.name", "").replace("notice.dac.name", "")
                if (mediaWords.containsMatchIn(withoutDevice)) offenders += "${f.name}: ${call.replace(Regex("\\s+"), " ").take(160)}"
            }
        }
        // "Unable to delete imported lyrics" is a fixed sentence that names no lyrics
        assertThat(offenders.filterNot { it.contains("Unable to delete imported lyrics") }).isEmpty()
    }

    @Test
    fun `the audit sees the calls it is about`() {
        val seen = sources.walkTopDown().filter { it.extension == "kt" }.sumOf { technicalCalls(it.readText()).size }
        assertThat(seen).isGreaterThan(60)
    }
}
