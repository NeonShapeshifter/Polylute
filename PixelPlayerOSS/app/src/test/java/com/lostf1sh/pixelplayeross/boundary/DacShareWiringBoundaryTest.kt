package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B39-A7: the part of "Share the DAC with other apps" that is the engine's and the platform's, which cannot run on the JVM, asserted at
 * source level like the rest of the engine's wiring: where each event reaches the decisions ([DacShareController] has its own tests) and
 * what the sharing code is not allowed to touch.
 */
class DacShareWiringBoundaryTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")
    private fun source(relative: String) = File(main, relative).readText().replace("\r\n", "\n")

    private val engine = source("data/service/player/DualPlayerEngine.kt")
    private val listener = engine.substringAfter("private val focusChangeListener = AudioManager.OnAudioFocusChangeListener").substringBefore("private val masterPlayerListener")

    @Test
    fun `every kind of focus loss reaches the decision, the gain gives the resume to it, and nothing else is changed in the engine's own handling`() {
        assertThat(listener.substringAfter("AudioManager.AUDIOFOCUS_LOSS ->").substringBefore("AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->"))
            .contains("dacShare.onFocusLost(permanent = true)")
        assertThat(listener.substringAfter("AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->").substringBefore("AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->"))
            .contains("dacShare.onFocusLost(permanent = false)")
        val canDuck = listener.substringAfter("AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->").substringBefore("AudioManager.AUDIOFOCUS_GAIN ->")
        // B68: a ducking request (a notification sound) never pauses and never releases, in any mode: it is ducked on the DAC, as with sharing off
        assertThat(canDuck).contains("directDuckListener?.onFocusEvent(DuckFocusEvent.CAN_DUCK, focusEvent)")
        assertThat(canDuck).doesNotContain("dacShare.")
        assertThat(canDuck).doesNotContain("pauseForTransientFocusLoss()")
        assertThat(canDuck).doesNotContain("playWhenReady = false")
        val gain = listener.substringAfter("AudioManager.AUDIOFOCUS_GAIN ->")
        assertThat(gain).contains("val resumeOwed = isFocusLossPause")
        assertThat(gain).contains("if (!dacShare.onFocusGained(resumeOwed) && resumeOwed) {")
        // the pause itself is still the engine's own, before the DAC is let go
        assertThat(listener.indexOf("pauseForTransientFocusLoss()")).isLessThan(listener.indexOf("dacShare.onFocusLost(permanent = false)"))
    }

    @Test
    fun `a pause or an idle queue schedules the release, a play cancels it, and an engine hold is not a pause of the listener's`() {
        val playWhenReadyChanged = engine.substringAfter("override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {").substringBefore("override fun onIsPlayingChanged")
        assertThat(playWhenReadyChanged).contains("mainHandler.removeCallbacks(dacSharePauseRelease)")
        assertThat(playWhenReadyChanged).contains("scheduleDacShareReleaseIfPaused()")
        assertThat(engine.substringAfter("override fun onPlaybackStateChanged(playbackState: Int) {").take(400))
            .contains("playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE")
        val schedule = engine.substringAfter("private fun scheduleDacShareReleaseIfPaused()").substringBefore("private fun abandonAudioFocus()")
        assertThat(schedule).contains("autoDirectHandoff.isInProgress || androidUsbRouteGuard.isHolding || masterRebuildCycle.isRebuilding")
        assertThat(schedule).contains("DacShareController.PAUSE_GRACE_MS")
    }

    @Test
    fun `the play gate chain asks the DAC share after the fixed-output notice, on the session's own player, so every controller passes it`() {
        val service = source("data/service/MusicService.kt")
        assertThat(service).contains("playGate = { fixedOutputNotice.get().allowPlay() && engine.allowPlayForDacShare() },")
        assertThat(engine).contains("fun allowPlayForDacShare(): Boolean = dacShare.onPlayRequested()")
        // the preference reaches the engine, which does not read preferences itself
        assertThat(service).contains("userPreferencesRepository.dacShareChoiceFlow.collect { choice -> engine.setDacShareChoice(choice) }")
    }

    @Test
    fun `play takes full focus even when a request is held, and only a granted request lets the DAC be touched`() {
        val request = engine.substringAfter("private fun requestFullAudioFocus(): Boolean {").substringBefore("/** A pause or idle that is the listener's own")
        assertThat(request).contains("audioManager.requestAudioFocus(request)")
        assertThat(request).contains("AudioManager.AUDIOFOCUS_REQUEST_GRANTED ->")
        assertThat(request).contains("AudioManager.AUDIOFOCUS_REQUEST_DELAYED ->")
        assertThat(request.substringAfter("AudioManager.AUDIOFOCUS_REQUEST_GRANTED ->").substringBefore("AudioManager.AUDIOFOCUS_REQUEST_DELAYED ->")).contains("true")
        assertThat(request.substringAfter("AudioManager.AUDIOFOCUS_REQUEST_DELAYED ->")).doesNotContain("true\n            }\n            else")
    }

    @Test
    fun `the claim resumes only when Direct is active and the DAC level is bounded, with the same verdict the handoff uses`() {
        assertThat(engine).contains("_audioRouteState.value.isDirectActive && handoffVolumeVerdict == HandoffVolumeVerdict.RESUME")
        assertThat(engine.substringAfter("private fun onRouteStatePublished(state: AudioRouteState) {").take(300)).contains("dacShare.onRouteState()")
    }

    @Test
    fun `the sharing code adds no gain, touches no volume, stops no player and never calls another app`() {
        val sharing = engine.substringAfter("// ── B39-A7: \"Share the DAC with other apps\"").substringBefore("/** Whether the held request asked the system to hand over")
        val controller = source("data/service/player/DacShareController.kt")
        for (text in listOf(sharing, controller)) {
            for (forbidden in listOf("setVolume", "volume =", "setRawLevel", "setUserDigitalGain", "writeHardwareVolume", ".stop()", ".release()", "clearMediaItems", "MediaSessionManager", "dispatchMediaKeyEvent")) {
                assertThat(text).doesNotContain(forbidden)
            }
        }
        // what it hands over is the route fact; B68: the route reads the same share policy, so Purity at a DAP level is left out there as well
        assertThat(source("data/service/player/AudioRouteModel.kt"))
            .contains("facts.dacReleasedForSharing && facts.dacShareAllowed -> android(AudioRouteReason.DAC_SHARED_WITH_OTHER_APPS)")
    }

    @Test
    fun `the hand-back asks the kernel to rebind, and resets the bus once if an interface is still without a driver`() {
        val driver = File("../../decent-player/libs/decent-usb-audio-driver/src/main").let {
            File(it, "kotlin/com/decent/usbaudio/UsbAudioDevice.kt").readText().replace("\r\n", "\n")
        }
        val release = driver.substringAfter("fun releaseToAndroid() {").substringBefore("fun closeDevice()")
        assertThat(release).contains("nativeReattachKernelDriverIfUnbound(fd, iface.id)")
        assertThat(release).contains("nativeUsbResetForRebind(fd)")
        // streaming first, then control, then the reattach, then the reset: the order the HONOR measurements settled
        assertThat(release.indexOf("conn.releaseInterface(streaming)")).isLessThan(release.indexOf("conn.releaseInterface(control)"))
        assertThat(release.indexOf("nativeReattachKernelDriverIfUnbound")).isLessThan(release.indexOf("nativeUsbResetForRebind"))
        val jni = File("../../decent-player/libs/decent-usb-audio-driver/src/main/jni/usb-audio-output.cpp").readText().replace("\r\n", "\n")
        val reset = jni.substringAfter("Java_com_decent_usbaudio_UsbAudioStream_nativeUsbResetForRebind(").substringBefore("// Re-binds the kernel driver")
        assertThat(reset).contains("ioctl(fd, USBDEVFS_RESET, 0)")
        // unlike nativeUsbReset it claims nothing afterwards: the interfaces belong to Android now
        assertThat(reset).doesNotContain("USBDEVFS_CLAIMINTERFACE")
        assertThat(reset).doesNotContain("USBDEVFS_DISCONNECT")
    }

    @Test
    fun `the switch's default follows the role, lives in the Audio page, and is a preference the engine is told about`() {
        // owner 11-22: nothing stored = on in app mode, off at a DAP level; the one rule is DacSharePolicy.switchOn, read by the engine and the page
        assertThat(source("data/preferences/UserPreferencesRepository.kt")).doesNotContain("DEFAULT_DAC_SHARE_ENABLED")
        assertThat(source("data/preferences/UserPreferencesRepository.kt"))
            .contains("dataStore.data.map { preferences -> preferences[PreferencesKeys.DAC_SHARE_ENABLED] }")
        assertThat(source("presentation/viewmodel/SettingsViewModel.kt")).contains("val dacShareChoice: Boolean? = null,")
        assertThat(source("presentation/settings/AudioSettings.kt")).contains("dacShareEnabled = DacSharePolicy.switchOn(ui.dacShareChoice, productMode),")
        assertThat(engine).contains("get() = DacSharePolicy.switchOn(dacShareChoice) { runCatching { effectiveProductModeProvider() }.getOrNull() }")
        assertThat(source("presentation/settings/AudioSettings.kt")).contains("onCheckedChange = actions::onDacShareChange")
        assertThat(source("presentation/settings/SettingsActionsWiring.kt")).contains("override fun onDacShareChange(value: Boolean) = vm.setDacShareEnabled(value)")
    }

    @Test
    fun `B40-P1 - the listener's play is judged by the confirmed level, only the automatic resume by the conservative verdict`() {
        val host = engine.substringAfter("private val dacShare: DacShareController = DacShareController(object : DacShareController.Host {").substringBefore("private val dacSharePauseRelease")
        assertThat(host).contains("override fun directReady(): Boolean =\n            _audioRouteState.value.isDirectActive && handoffVolumeVerdict == HandoffVolumeVerdict.RESUME")
        assertThat(host).contains("override fun userPlayReady(): Boolean =\n            _audioRouteState.value.isDirectActive && userPlayLevel == UserPlayLevel.CONFIRMED")
        // a claim that ends without playing says so in one trace line and to the listener
        assertThat(host).contains("traceRoute(\"DAC_SHARE\", line)")
        assertThat(host).contains("Toast.makeText(context, message, Toast.LENGTH_LONG).show()")
        assertThat(engine).contains("dacShare.onClaimTimeout(directActive = _audioRouteState.value.isDirectActive)")
        // the safety controller publishes the second judgment next to the first
        val safety = source("data/volume/SafetyVolumeController.kt")
        assertThat(safety).contains("state.map(HandoffResumeSafety::userPlayLevel)")
        assertThat(safety).contains(".collect(dualPlayerEngine::onUserPlayLevel)")
    }

    // ── B68 (option D) ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `B68 - both layers read the one share policy - the controller's active and the route fact the engine builds`() {
        val controller = source("data/service/player/DacShareController.kt")
        assertThat(controller).contains("val active: Boolean get() = DacSharePolicy.shareAllowed(host.enabled, host.purityInForce) { host.effectiveProductMode() }")
        assertThat(controller).doesNotContain("host.resonance")
        // the route fact: read only while released, with the mode latched at the release
        assertThat(engine).contains(
            "dacShareAllowed = dacReleasedForSharing && DacSharePolicy.shareAllowed(\n" +
                "                    DacSharePolicy.switchOn(dacShareChoice, dacShareReleaseMode),\n" +
                "                    PristineModePolicy.lockInForce(pristineLockPreference, usbDacPresentFact),\n" +
                "                    dacShareReleaseMode,\n" +
                "                ),",
        )
        val host = engine.substringAfter("private val dacShare: DacShareController = DacShareController(object : DacShareController.Host {").substringBefore("private val dacSharePauseRelease")
        // "Resonance" is the Purity choice, not "the DSP has work" (B68-audit H1)
        assertThat(host).contains("override val purityInForce: Boolean get() = PristineModePolicy.lockInForce(pristineLockPreference, usbDacPresentFact)")
        assertThat(host).doesNotContain("requiresPcmProcessing")
        assertThat(host).contains("dacShareReleaseMode = if (released) effectiveProductMode() else null")
        assertThat(host.indexOf("dacShareReleaseMode = if (released)")).isLessThan(host.indexOf("dacReleasedForSharing = released"))
    }

    @Test
    fun `B68 - the service tells the engine Polylute's role from ProductModeController`() {
        val service = source("data/service/MusicService.kt")
        assertThat(service).contains("engine.effectiveProductModeProvider = { productModeForDacShare.currentState().effectiveMode }")
        assertThat(engine).contains("var effectiveProductModeProvider: () -> com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode? = { null }")
    }

    @Test
    fun `B68 - a settings change (switch, Purity, listening state) asks the controller with the pause state and schedules the grace`() {
        val changed = engine.substringAfter("private fun onDacShareSettingsChanged() {").substringBefore("\n    }\n")
        assertThat(changed).contains("val paused = ::playerA.isInitialized && !(playerA.isPlaying || playerA.playWhenReady)")
        assertThat(changed).contains("if (dacShare.onSettingsChanged(paused) == DacShareController.PauseOutcome.RELEASE_AFTER_GRACE) scheduleDacShareReleaseIfPaused()")
        assertThat(engine.substringAfter("fun setDacShareChoice(choice: Boolean?) {").substringBefore("\n    }\n")).contains("onDacShareSettingsChanged()")
        assertThat(engine.substringAfter("fun updateProcessingRequirements(").substringBefore("\n    }\n")).contains("mainHandler.post { onDacShareSettingsChanged() }")
    }

    @Test
    fun `B68 - the service stop gives the DAC back, after the players' USB stream and before the engine is done, in every mode`() {
        val release = engine.substringAfter("\n    fun release() {").substringBefore("\n    }\n")
        assertThat(release).contains("releaseDacOnStop()")
        // after the master player (whose release stops the writer and drains the URBs): usbfs would claim the interface again on a URB
        assertThat(release.indexOf("releasePlayer(playerA)")).isLessThan(release.indexOf("releaseDacOnStop()"))
        assertThat(release.indexOf("releaseDacOnStop()")).isLessThan(release.indexOf("isReleased = true"))
        val stop = engine.substringAfter("private fun releaseDacOnStop() {").substringBefore("\n    }\n")
        // no mode check: Dedicated included
        assertThat(stop).doesNotContain("ProductMode")
        assertThat(stop).contains("device.releaseToAndroidNow()")
        assertThat(stop.indexOf("device.releasedDriversListener = null")).isLessThan(stop.indexOf("device.releaseToAndroidNow()"))
        // the service's stop path reaches it
        val onDestroy = source("data/service/MusicService.kt").substringAfter("override fun onDestroy() {").substringBefore("\n    }\n")
        assertThat(onDestroy).contains("engine.release()")
        // the driver: the ordinary hand-back (streaming, then control; the reattach; the B40 reset rule; close), on the calling thread
        val driver = File("../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioDevice.kt").readText().replace("\r\n", "\n")
        val now = driver.substringAfter("fun releaseToAndroidNow() {").substringBefore("\n    }\n")
        assertThat(now).contains("owner.exclusive(\"service-stop\") {")
        assertThat(now).contains("owner.clearStream()")
        assertThat(now).contains("handBackToAndroid()")
        assertThat(now).doesNotContain("volumeExecutor")
    }
}
