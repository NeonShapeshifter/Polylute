package com.lostf1sh.pixelplayeross.boundary

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.diagnostics.NoiseMark
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B39-A4: the product's names are **Purity** (the untouched path) and **Resonance** (the processed one). Maintenance printed the enum's own
 * name (`DIRECT_PRISTINE`), the hardware report said "Pristine", and the shared noise file dumped `signalMode=PRISTINE`. The enums, the
 * log keys and the persisted ids keep their names; every text a listener reads, or sends, does not say PRISTINE.
 */
class PurityWordingBoundaryTest {
    private val res = File("src/main/res")
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")

    private fun values(language: String, file: String): Map<String, String> {
        val xml = File(res, "$language/$file").readText().replace("\r\n", "\n")
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `no string a listener can read says pristine, in any language`() {
        val offenders = mutableListOf<String>()
        for (dir in listOf("values", "values-es", "values-sv")) {
            File(res, dir).listFiles { f -> f.extension == "xml" }.orEmpty().forEach { file ->
                Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(file.readText()).forEach { m ->
                        // an alias (`@string/pristine_lock_name`) is a reference to another string, which is checked on its own
                        if (!m.groupValues[2].startsWith("@string/") &&
                            Regex("pristine", RegexOption.IGNORE_CASE).containsMatchIn(m.groupValues[2])
                        ) offenders += "$dir/${file.name}: ${m.groupValues[1]}"
                    }
            }
        }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `every route reason and transition has a label in the three languages, and none of them says pristine`() {
        val keys = AudioRouteReason.entries.size + AudioRouteTransition.entries.size
        val en = values("values", "strings_route_labels.xml")
        assertThat(en).hasSize(keys)
        for (language in listOf("values-es", "values-sv")) {
            val other = values(language, "strings_route_labels.xml")
            assertThat(other.keys).isEqualTo(en.keys)
        }
        for (language in listOf("values", "values-es", "values-sv")) {
            for ((name, text) in values(language, "strings_route_labels.xml")) {
                assertThat(text.lowercase()).doesNotContain("pristine")
                assertThat(text).isNotEmpty()
                assertThat(name).startsWith("route_")
            }
        }
        // the two paths are named by the product's own words
        for (language in listOf("values", "values-es", "values-sv")) {
            val labels = values(language, "strings_route_labels.xml")
            assertThat(labels.getValue("route_reason_purity")).contains("Purity")
            assertThat(labels.getValue("route_reason_resonance")).contains("Resonance")
        }
    }

    @Test
    fun `Maintenance draws the route through its labels and not through the enum's name`() {
        val maintenance = File(main, "presentation/screens/MaintenanceScreen.kt").readText()
        assertThat(maintenance).contains("stringResource(route.reason.labelRes())")
        assertThat(maintenance).contains("stringResource(route.transition.labelRes())")
        assertThat(maintenance).doesNotContain("route.reason.name")
        assertThat(maintenance).doesNotContain("route.transition.name")
    }

    @Test
    fun `the hardware report and the shared noise file say Purity and Resonance, whichever route they describe`() {
        val hardware = File(main, "data/diagnostics/HardwareDiagnostics.kt").readText()
        assertThat(hardware).doesNotContain("\"Pristine\"")
        assertThat(hardware).doesNotContain("Direct — pristine")
        assertThat(hardware).contains("Purity (untouched)")
        assertThat(hardware).contains("Resonance (processed)")

        for (reason in AudioRouteReason.entries) for (mode in DirectSignalMode.entries) {
            val route = AudioRouteState(
                desiredBackend = AudioBackend.USB_DIRECT,
                actualBackend = AudioBackend.USB_DIRECT,
                transition = AudioRouteTransition.DIRECT_ACTIVE,
                reason = reason,
                signalMode = mode,
            )
            val line = NoiseMark.routeSummary(route)
            assertThat(line).doesNotContain("PRISTINE")
            assertThat(line).contains(if (mode == DirectSignalMode.PRISTINE) "signal=Purity" else "signal=Resonance")
            val usb = NoiseMark.usbSummary(UsbDirectAudioDiagnostics(signalMode = mode))
            assertThat(usb).doesNotContain("PRISTINE")
        }
        assertThat(NoiseMark.reasonName(AudioRouteReason.DIRECT_PRISTINE)).isEqualTo("Purity")
        assertThat(NoiseMark.reasonName(AudioRouteReason.DIRECT_PROCESSED)).isEqualTo("Resonance")
        // and the default (no Direct stream) says nothing of either
        assertThat(NoiseMark.usbSummary(UsbDirectAudioDiagnostics())).contains("signal=none")
    }
}
