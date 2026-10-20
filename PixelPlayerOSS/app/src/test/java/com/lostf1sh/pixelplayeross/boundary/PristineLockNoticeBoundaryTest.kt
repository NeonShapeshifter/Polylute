package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.components.PristineFrozenFeature
import com.lostf1sh.pixelplayeross.presentation.components.PristineLockCopy
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B11-3 (F-02, v1 must-have): when the Pure Direct lock keeps EQ, ReplayGain or the digital level
 * from being applied, the app says so, with a way to change it, and the words live in one place so
 * the rebrand (Purity Lock / Purity / Resonance) renames them without touching code.
 */
class PristineLockNoticeBoundaryTest {

    private val main = File("src/main")
    private val java = "java/com/lostf1sh/pixelplayeross"
    private fun read(path: String) = File(main, path).readText().replace("\r\n", "\n")

    private fun strings(dir: String) = read("res/$dir/strings_pristine_mode.xml")

    @Test
    fun `the lock has one name`() {
        for (dir in listOf("values", "values-es", "values-sv")) {
            val xml = strings(dir)
            assertThat(Regex("""<string name="pristine_lock_name">""").findAll(xml).count()).isEqualTo(1)
        }
        assertThat(strings("values")).contains("<string name=\"pristine_lock_name\">Purity</string>")
    }

    @Test
    fun `every notice takes the name as an argument instead of spelling it`() {
        // B16-G4: crossfade and speed have a "pending" form too now: with no DAC the lock is not in force and they apply.
        val keys = listOf("eq", "replaygain", "digital", "crossfade", "speed")
            .flatMap { listOf("pristine_lock_notice_$it", "pristine_lock_notice_${it}_pending") }
        for (dir in listOf("values", "values-es", "values-sv")) {
            val xml = strings(dir)
            for (key in keys) {
                val body = Regex("""<string name="$key">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
                assertThat(body).isNotNull()
                assertThat(body).startsWith("%1\$s ")
                assertThat(body).doesNotContain("Pure Direct")
            }
        }
    }

    @Test
    fun `the notices and the Pure Direct sentences say what the owner asked for`() {
        assertThat(strings("values-es")).contains(
            "<string name=\"pristine_lock_notice_eq\">%1\$s activo · el EQ no se aplica</string>"
        )
        assertThat(strings("values")).contains("<string name=\"pristine_lock_notice_eq\">%1\$s on · EQ is not applied</string>")
        // B22: the one sentence under the switch is the design's, in the three languages. The explainer that used to sit
        // beside it ("two texts that say the same") is gone from all of them.
        assertThat(strings("values-es")).contains("<string name=\"pristine_status_active\">Activo — señal intacta hasta el DAC.</string>")
        assertThat(strings("values-es")).contains("En espera — sin DAC USB todo se aplica. Con un DAC congelará EQ, ReplayGain, volumen digital, fundido y velocidad.")
        assertThat(strings("values-es")).contains("Resonancia: USB Direct con el DSP de la app")
        for (dir in listOf("values", "values-es", "values-sv")) {
            assertThat(strings(dir)).doesNotContain("pristine_lock_explainer")
            assertThat(strings(dir)).contains("pristine_status_waiting")
        }
    }

    @Test
    fun `no code spells the lock's name, so renaming it is a resource edit`() {
        val offenders = File(main, java).walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().filter { (_, line) ->
                    val code = line.substringBefore("//").trim()
                    !code.startsWith("*") && !code.startsWith("/*") && Regex(""""[^"]*Pure Direct[^"]*"""").containsMatchIn(code)
                }.map { "${file.name}:${it.index + 1}" }.asSequence()
            }.toList()
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `EQ says so and leads to the switch, which is on the Audio page`() {
        val eq = read("$java/presentation/screens/EqualizerScreen.kt")
        // B22: the equalizer's status row is the way to the switch, and it carries the one status line.
        assertThat(eq).contains("EqualizerStatusRow(state, frozen) { actions.onOpenPurityLock() }")
        assertThat(eq).contains("FeatureStatusLine(state.status)")
        // B22: the switch moved from the Settings root to Audio, so "Change" leads to the Audio page.
        assertThat(read("$java/presentation/screens/ProfileActionsWiring.kt"))
            .contains("override fun onOpenPurityLock() = navigate(Screen.SettingsCategory.createRoute(SettingsCategory.AUDIO.id))")
        // The Audio page holds the switch itself; ReplayGain's row says so with its own status line instead of a note.
        val audio = read("$java/presentation/settings/AudioSettings.kt")
        assertThat(audio).contains("onCheckedChange = actions::onPureDirectChange,")
        assertThat(audio).contains("audio.replayGainStatus(")
        assertThat(audio).doesNotContain("onOpenPurityLock")
    }

    @Test
    fun `the lock's switch is no longer on the Settings root, and the locked digital row says it is not applied`() {
        val block = read("$java/presentation/screens/DapDeviceControlComponents.kt")
        assertThat(block).doesNotContain("DapPristineSection")
        assertThat(block).doesNotContain("pristine_lock_explainer")
        assertThat(block).doesNotContain("onPristineModeChanged")
        // B21: the digital row asks the status model, which answers with the same notice and nothing when the lock is off.
        assertThat(block).contains("state.audio.frozenNotice(PristineFrozenFeature.DIGITAL_VOLUME, assumeDirect = true)")
        assertThat(AudioStatus(purityPreference = true).frozenNotice(PristineFrozenFeature.DIGITAL_VOLUME, assumeDirect = true))
            .isEqualTo(PristineLockCopy.notice(PristineFrozenFeature.DIGITAL_VOLUME, directActive = true))
        assertThat(AudioStatus(purityPreference = false).frozenNotice(PristineFrozenFeature.DIGITAL_VOLUME, assumeDirect = true)).isNull()
    }

    @Test
    fun `the notice reads whether Direct is carrying the stream`() {
        // B21: one status, built once from the route the engine publishes and the lock's preference.
        val source = read("$java/presentation/viewmodel/AudioStatusSource.kt")
        assertThat(source).contains("dualPlayerEngine.audioRouteState")
        assertThat(source).contains("userPreferencesRepository.pristineLockFlow")
        val status = read("$java/presentation/viewmodel/AudioStatus.kt")
        assertThat(status).contains("directActive = assumeDirect || route.isDirectActive,")
        assertThat(status).contains("dacPresent = route.usbDacPresent != false,")
        assertThat(status).contains("if (!purityPreference) null else PristineLockCopy.notice(")
        val note = read("$java/presentation/components/PristineFrozenNote.kt")
        assertThat(note).contains("val notice = status.frozenNotice(feature) ?: return")
        // The note no longer asks a ViewModel of its own.
        assertThat(note).doesNotContain("hiltViewModel")
    }
}
