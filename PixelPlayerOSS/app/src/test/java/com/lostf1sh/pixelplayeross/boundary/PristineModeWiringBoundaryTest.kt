package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** F-02 and AUD-120 wiring; the rules are tested in PristineModeTest and DigitalGainSliderTest. */
class PristineModeWiringBoundaryTest {
    private val main = File("src/main")
    private fun read(path: String) = File(main, path).readText().replace("\r\n", "\n")
    private val java = "java/com/lostf1sh/pixelplayeross"

    @Test
    fun `the lock is applied where every requirement reader gets its value`() {
        val service = read("$java/data/service/MusicService.kt")
        // B9-4: still the single point, now fed by the persisted lock as the process-only diagnostic override
        // leaves it (off unless the debug bridge sets it; never persisted).
        assertThat(service).contains(".combine(PristineLockDiagnostic.requirementsLock(userPreferencesRepository.pristineLockFlow)) { requirements, locked ->")
        // B16-G4: the service delivers the lock as a *preference* with the listening state; the engine is where it is applied,
        // and only while the router has found a USB DAC.
        assertThat(service).contains("engine.updateProcessingRequirements(requirements, pristineLock = locked)")
        val engine = read("$java/data/service/player/DualPlayerEngine.kt")
        assertThat(engine).contains("PristineModePolicy.effectiveRequirements(rawProcessingRequirements, pristineLockPreference, usbDacPresentFact)")
        assertThat(engine).contains("usbDacPresentFact = device != null")
        assertThat(engine).contains("processing = processingRequirements,")
    }

    @Test
    fun `the volume block keeps the snap and the double tap, and the switch is on the Audio page`() {
        val block = read("$java/presentation/screens/DapDeviceControlComponents.kt")
        assertThat(block).contains("DigitalGainSlider.snapPercent(fraction)")
        assertThat(block).contains("onDoubleTap = { onDigitalGainChanged(100) }")
        // B22: the switch, its sentence, the "digital volume keeps it off" notice and the rise dialog moved to Audio.
        assertThat(block).doesNotContain("Switch(checked = state.pristineLocked")
        assertThat(block).doesNotContain("pristine_rise_body")
        assertThat(read("$java/presentation/screens/SettingsScreen.kt")).doesNotContain("pristineActions")
        val audio = read("$java/presentation/settings/AudioSettings.kt")
        assertThat(audio).contains("if (state.pureDirect.digitalBlocksPristine) {")
        assertThat(audio).contains("onCheckedChange = actions::onPureDirectChange,")
        assertThat(read("$java/presentation/settings/PureDirectRiseDialog.kt")).contains("R.string.pristine_rise_body")
        // The same action as always.
        assertThat(read("$java/presentation/settings/SettingsActionsWiring.kt"))
            .contains("override fun onPureDirectChange(value: Boolean) = device.setPristineMode(value)")
    }

    @Test
    fun `frozen settings say so, in all three languages`() {
        assertThat(read("$java/presentation/screens/EqualizerScreen.kt")).contains("FeatureStatusLine(")
        // B22: the Audio page says it with the status line of each row (`AudioFeatureStatus`), not with a note.
        assertThat(read("$java/presentation/settings/AudioSettings.kt")).contains("FeatureStatusLine(")
        for (dir in listOf("values", "values-es", "values-sv")) {
            val strings = read("res/$dir/strings_pristine_mode.xml")
            // B11-3 replaced `pristine_frozen_note` by one notice per setting; see PristineLockNoticeBoundaryTest.
            for (name in listOf("pristine_status_only_direct", "pristine_status_waiting", "pristine_lock_notice_eq",
                "digital_reset_full", "pristine_rise_body")) {
                assertThat(strings).contains("name=\"$name\"")
            }
            // What the Audio page no longer says, in every language.
            for (name in listOf("pristine_lock_explainer", "digital_blocks_pristine", "pristine_enable_action")) {
                assertThat(strings).doesNotContain("name=\"$name\"")
            }
        }
    }
}
