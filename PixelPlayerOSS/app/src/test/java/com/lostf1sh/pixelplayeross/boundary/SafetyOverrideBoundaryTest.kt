package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * FIX-4: the Android-bound half of the override's lifetime (AudioManager, the claimed DAC, the media
 * engine) cannot run on the JVM, so the wiring is asserted at source level, as the rest of the volume
 * area is. The rules themselves (what ends the override and what does not) are in
 * `SafetyOverridePolicyTest`.
 */
class SafetyOverrideBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    private fun source(relative: String): String =
        File("src/main", "$java/$relative").readText().replace("\r\n", "\n")

    private fun resource(relative: String): String =
        File("src/main", "res/$relative").readText().replace("\r\n", "\n")

    private val controller = source("data/volume/SafetyVolumeController.kt")
    private val policy = source("data/volume/SafetyOverridePolicy.kt")
    private val store = source("data/volume/SafetyOverrideStore.kt")

    private val reconcile = controller.substringAfter("    fun reconcile(externalVolumeChange: Boolean = false) {")
        .substringBefore("    /** What the override did at one evaluation")

    @Test
    fun `a change of output no longer clears the override in reconcile`() {
        // The flicker at a stream rebuild used to clear it here (that was the whole bug).
        assertThat(reconcile).doesNotContain("temporaryOverride = false")
        assertThat(reconcile).doesNotContain("contextChanged")
        assertThat(reconcile).doesNotContain("backendChanged")
        assertThat(reconcile).doesNotContain("profileChanged")
        // The verdict comes from the session, and is what the clamp and the state use.
        assertThat(reconcile).contains("val outcome = evaluateOverride(SystemClock.elapsedRealtime(), backend, ceilingRaw)")
        assertThat(reconcile).contains("val override = outcome.active")
        assertThat(reconcile).contains("SafetyVolumePolicy.mustClamp(backend.rawLevel, ceilingRaw, override)")
        // What a change of output does reset is the gesture and its dialog, as before.
        assertThat(reconcile).contains("gesture.reset()")
        assertThat(reconcile).contains("it.copy(showOverrideConfirmation = false)")
    }

    @Test
    fun `the DAC open guard keeps the level while an override stands for that DAC`() {
        val guard = controller.substringAfter("private fun hardwareOpenTarget(").substringBefore("// USB control transfers are blocking")
        assertThat(guard).contains("SafetyOverridePolicy.coversOpen(")
        assertThat(guard).contains("overrideSession")
        assertThat(guard).contains("UsbHardwareVolumePolicy.openTarget(currentUnits, saved, ceilingUnits.takeUnless { overrideStands })")
        // It is still the same guard: only ever lowers, to what was chosen last.
        assertThat(guard).doesNotContain("writeHardwareVolume")
        assertThat(guard).contains("val saved = hardwareStore.saved(contextId)")
    }

    @Test
    fun `an override is granted in one place and only from the confirmed gesture`() {
        assertThat(Regex("""SafetyOverridePolicy\.grant\(""").findAll(controller).count()).isEqualTo(1)
        assertThat(controller.substringAfter("fun allowTemporaryOverride()").substringBefore("\n    }\n"))
            .contains("SafetyOverridePolicy.grant(")
        // The ceiling and the gesture are the same ones: three blocked presses, then the dialog.
        assertThat(controller).contains("gesture.blockedVolumeUp(")
    }

    @Test
    fun `the idle clock follows playback and every reader asks whether time has ended it`() {
        assertThat(controller).contains("dualPlayerEngine.isPlayingFlow.collect(::onPlaybackChanged)")
        val engine = source("data/service/player/DualPlayerEngine.kt")
        assertThat(engine.substringAfter("override fun onIsPlayingChanged(isPlaying: Boolean) {").take(200))
            .contains("_isPlaying.value = isPlaying")
        // The timer can be late (a coroutine delay does not run while the device sleeps): the readers
        // ask first.
        val keys = controller.substringAfter("fun handleVolumeKey(event: KeyEvent): Boolean {").substringBefore("private fun announceHud")
        assertThat(keys.indexOf("refreshOverride()")).isAtLeast(0)
        assertThat(keys.indexOf("refreshOverride()")).isLessThan(keys.indexOf("val current = _state.value\n        if (!current.backend.controllable) return false"))
        val remote = controller.substringAfter("override fun setLevel(level: Int) {").substringBefore("\n        }\n    }")
        assertThat(remote.indexOf("refreshOverride()")).isLessThan(remote.indexOf("val current = _state.value"))
        val setRaw = controller.substringAfter("fun setRawLevel(requested: Int, origin: String = \"ui\") {").substringBefore("\n    }\n")
        assertThat(setRaw).contains("refreshOverride()")
        // Lowering is judged on the level just written, so lowering to the ceiling ends it at once.
        assertThat(setRaw).contains("evaluateOverride(SystemClock.elapsedRealtime(), lowered, ceiling)")
    }

    @Test
    fun `switching Safety Max off ends the override immediately`() {
        val toggle = controller.substringAfter("fun setSafetyEnabled(enabled: Boolean) {").substringBefore("\n    }\n")
        assertThat(toggle).contains("endOverride(SafetyOverrideEnd.SAFETY_OFF)")
    }

    @Test
    fun `an override that ends fades only when it ended in that pass and music plays`() {
        assertThat(reconcile).contains("endedHere = outcome.ended != null")
        assertThat(reconcile).contains("playing = dualPlayerEngine.isPlayingFlow.value")
        // Every other clamp stays immediate: the immediate branch is still the default one.
        assertThat(reconcile).contains("setBackendRaw(manager, backend, clamp, \"clamp\")")
        // The fade writes through the same path as every other write, labelled in the Feature Unit log.
        val ramp = controller.substringAfter("private fun startRamp(").substringBefore("\n    /**\n     * @param origin")
        assertThat(ramp).contains("setBackendRaw(manager, current, level, \"ramp\")")
        // B12a-8: each step is taken from what the backend reads, never from the ramp's own counter alone
        assertThat(ramp).contains("level = SafetyOverrideRamp.next(current.rawLevel, level, ceilingRaw) ?: break")
        assertThat(ramp).doesNotContain("level = SafetyOverrideRamp.nextLevel(level, ceilingRaw)")
        // A rise while fading is clamped at once: the ramp is cancelled before the immediate clamp.
        assertThat(reconcile).contains("if (rampedTo != null) cancelRamp()")
    }

    @Test
    fun `a restored override is judged only once the profile store has answered`() {
        // B12a-8 #3: the observation carries the flag, and the controller flips it after the store's own first read
        assertThat(controller).contains("contextLoaded = contextLoaded,")
        val start = controller.substringAfter("fun start() {").substringBefore("fun reconcile(")
        assertThat(start.indexOf("restoreOverride()")).isLessThan(start.indexOf("profiles.currentState()"))
        assertThat(start).contains("profiles.state.first { it == real }")
        assertThat(start.indexOf("contextLoaded = true")).isGreaterThan(start.indexOf("profiles.currentState()"))
        // and it is judged right after: a reconcile compares against the loaded profile
        assertThat(start.substringAfter("contextLoaded = true")).contains("reconcile()")
    }

    @Test
    fun `the override survives a restart only through the policy and is forgotten when it ends`() {
        assertThat(controller).contains("SafetyOverridePolicy.restore(record, bootCount(), SystemClock.elapsedRealtime())")
        assertThat(controller.substringAfter("private fun forgetOverride() {").substringBefore("\n    }\n"))
            .contains("overrideStore.clear()")
        // It is read before the first reconcile and before anything can open the DAC.
        val start = controller.substringAfter("fun start() {").substringBefore("\n    fun reconcile(")
        assertThat(start.indexOf("restoreOverride()")).isLessThan(start.lastIndexOf("reconcile()"))
        assertThat(start.indexOf("usbDevice.volumeGuard = ::hardwareOpenTarget")).isLessThan(start.indexOf("restoreOverride()"))
        // The persisted record keeps the boot it belongs to.
        assertThat(controller).contains("Settings.Global.BOOT_COUNT")
    }

    @Test
    fun `every persisted field is written and read back under the same key`() {
        val keys = Regex("""const val (\w+) = "([a-z_]+)"""").findAll(store).map { it.groupValues[1] }.toList()
        assertThat(keys).hasSize(9)
        val save = store.substringAfter("fun save(").substringBefore("fun load()")
        val load = store.substringAfter("fun load()").substringBefore("fun clear()")
        for (key in keys) {
            assertThat(save).contains("($key,")
            assertThat(load).contains("($key")
        }
    }

    @Test
    fun `locking the screen is not an input of the override`() {
        for (text in listOf(policy, controller.substringAfter("val overrideStore").substringBefore("private val volumeObserver"))) {
            assertThat(text).doesNotContain("SCREEN_OFF")
            assertThat(text).doesNotContain("isInteractive")
            assertThat(text).doesNotContain("KeyguardManager")
            assertThat(text).doesNotContain("ACTION_USER_PRESENT")
        }
        val override = controller.substringAfter("private fun observationFor(").substringBefore("private fun commitOverride(")
        assertThat(override).doesNotContain("Screen")
    }

    @Test
    fun `the policy and the store know nothing of the audio path`() {
        for (text in listOf(policy, store)) {
            assertThat(text).doesNotContain("UsbAudioSink")
            assertThat(text).doesNotContain("DualPlayerEngine")
            assertThat(text).doesNotContain("com.decent.usbaudio")
        }
    }

    @Test
    fun `the readout says an override is on and when it ends, in both languages`() {
        val hud = source("presentation/components/VolumeHudHost.kt")
        assertThat(hud).contains("R.string.volume_hud_override_detail")
        assertThat(hud).contains("SafetyOverridePolicy.IDLE_END_MS / 60_000L")
        assertThat(hud).contains("takeIf { notice == DapVolumeNotice.OVERRIDE }")
        assertThat(source("ui/ds/DapOverlays.kt")).contains("noticeDetail: String? = null")
        val main = source("MainActivity.kt")
        assertThat(main).contains("R.string.safety_max_override_lasts")
        for (file in listOf("values/strings_safety_volume.xml", "values-es/strings_safety_volume.xml")) {
            val strings = resource(file)
            assertThat(strings).contains("name=\"volume_hud_override_detail\"")
            assertThat(strings).contains("name=\"safety_max_override_lasts\"")
            // The minutes are a placeholder, never typed in the sentence.
            assertThat(Regex("""name="volume_hud_override_detail">[^<]*%1\${'$'}d""").containsMatchIn(strings)).isTrue()
            assertThat(Regex("""name="safety_max_override_lasts">[^<]*%1\${'$'}d""").containsMatchIn(strings)).isTrue()
        }
    }
}
