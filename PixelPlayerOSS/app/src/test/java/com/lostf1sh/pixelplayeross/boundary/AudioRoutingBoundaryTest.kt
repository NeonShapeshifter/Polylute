package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import java.io.File
import org.junit.jupiter.api.Test

class AudioRoutingBoundaryTest {
    @Test fun `the Audio category is configuration, not a manual route switch`() {
        // "Audio" is a product category: it owns devices, processing and transitions. What must not
        // come back is a transport control — a user-facing switch that forces USB Direct or Android
        // audio. Routing is decided by the engine, so the category must carry no way to override it.
        assertThat(SettingsCategory.entries.map { it.id }).contains("audio")

        val sources = root.resolve("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertThat(sources).doesNotContain("settings_category_transport")
        assertThat(sources).doesNotContain("usbDirectSwitch")
        assertThat(sources).doesNotContain("setPreferredAudioBackend")
    }

    @Test fun `engine gives only the master player conditional Direct access`() {
        val engine = source("data/service/player/DualPlayerEngine.kt")
        assertThat(engine).contains("buildPlayer(allowDirect = desiredBackend == AudioBackend.USB_DIRECT)")
        assertThat(engine).contains("buildPlayer(allowDirect = false)")
        // The config field is named for the transport it enables. It used to be called
        // `bitPerfectEnabled`, which made "use the USB transport" and "the signal is unmodified"
        // the same flag — and that equivalence is what made a processed Direct path unrepresentable.
        assertThat(engine).contains("directEnabled = allowDirect")
        assertThat(engine).doesNotContain("bitPerfectEnabled")
    }

    @Test fun `the Direct transport carries the live signal source rather than a fixed mode`() {
        val engine = source("data/service/player/DualPlayerEngine.kt")
        val directConfig = engine.substringAfter("return UsbAudioSink(").substringBefore("private fun registerUsbSink")
        assertThat(directConfig).contains("signalSource = directSignalController")
        // A mode captured at build time could not change without rebuilding the player, and a
        // rebuild of a Direct player releases and re-claims the exclusive USB session.
        assertThat(directConfig).doesNotContain("signalMode =")
    }

    @Test fun `processing is not smuggled into the Android sink chain for the Direct path`() {
        // The Android-path EQ processor is still constructed for the delegate, and still never runs
        // on Direct — `UsbAudioSink.handleBuffer` returns before `DefaultAudioSink.handleBuffer`.
        // What must not appear is a second EQ or a resampler wired into the Direct config itself,
        // which would apply processing twice or change the negotiated rate behind the DAC's back.
        val engine = source("data/service/player/DualPlayerEngine.kt")
        val directConfig = engine.substringAfter("return UsbAudioSink(").substringBefore("private fun registerUsbSink")
        assertThat(directConfig).doesNotContain("Sonic")
        assertThat(directConfig).doesNotContain("resampl")
    }

    @Test fun `legacy transport preferences have no runtime authority`() {
        val sources = root.resolve("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertThat(sources).doesNotContain("usbDacDirectEnabledFlow")
        assertThat(sources).doesNotContain("setUsbDacDirectEnabled")
        assertThat(sources).doesNotContain("hiFiModeEnabledFlow")
        assertThat(sources).doesNotContain("setHiFiMode")
    }

    @Test fun `public resources contain no manual route controls or legacy HiFi surface`() {
        val resources = root.resolve("src/main/res").walkTopDown()
            .filter { it.extension == "xml" }.joinToString("\n") { it.readText() }
        assertThat(resources).doesNotContain("usb_direct_switch")
        assertThat(resources).doesNotContain("setcat_hifi_mode")
        // The Audio category title exists; what must not exist is a control under it that switches
        // the audio route by hand.
        assertThat(resources).contains("settings_category_audio_title")
        assertThat(resources).doesNotContain("settings_category_audio_transport")
        assertThat(resources).doesNotContain("setcat_audio_route")
    }

    @Test fun `remembered DAC headphone association has an application runtime owner`() {
        val controller = source("data/headphones/DacHeadphoneAssociationController.kt")
        val application = source("PixelPlayerApplication.kt")
        assertThat(controller).contains("headphones.currentState()")
        assertThat(controller).doesNotContain("ViewModel")
        assertThat(application).contains("dacHeadphoneAssociationController.get().start()")
    }

    @Test fun `routing never writes Android media volume`() {
        val engine = source("data/service/player/DualPlayerEngine.kt")
        assertThat(engine).doesNotContain("setStreamVolume")
        assertThat(engine).doesNotContain("adjustStreamVolume")
    }

    @Test fun `legacy EQ store is migration input not a runtime mirror`() {
        val preferences = source("data/preferences/EqualizerPreferencesRepository.kt")
        assertThat(preferences).contains("readLegacyStateForMigration")
        assertThat(preferences).doesNotContain("effectiveStateFlow")
        assertThat(preferences).doesNotContain("replaceEffectiveState")
        assertThat(preferences).doesNotContain("setEqualizerEnabled")
    }

    @Test fun `MusicService profile EQ coordinator is injected before startup use`() {
        val service = source("data/service/MusicService.kt")
        // Whitespace-insensitive: the source's indentation and line endings (CRLF) are not the contract.
        assertThat(service).containsMatch(
            """@Inject\s+lateinit var headphoneEqualizerCoordinator: HeadphoneEqualizerCoordinator""",
        )
    }

    private fun source(relative: String) =
        root.resolve("src/main/java/com/lostf1sh/pixelplayeross/$relative").readText()

    private companion object {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
    }
}
