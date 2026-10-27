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
        // a ducking request is "transient or not" too, with sharing on; with it off the Direct duck is as it was
        assertThat(canDuck).contains("if (dacShare.active)")
        assertThat(canDuck).contains("directDuckListener?.onFocusEvent(DuckFocusEvent.CAN_DUCK, focusEvent)")
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
        assertThat(service).contains("userPreferencesRepository.dacShareEnabledFlow.collect { enabled -> engine.setDacShareEnabled(enabled) }")
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
        // what it hands over is the route fact; Purity is left out of the route decision as well
        assertThat(source("data/service/player/AudioRouteModel.kt"))
            .contains("facts.dacReleasedForSharing && facts.processing.requiresPcmProcessing -> android(AudioRouteReason.DAC_SHARED_WITH_OTHER_APPS)")
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
    fun `the switch is off by default, lives in the Audio page, and is a preference the engine is told about`() {
        assertThat(source("data/preferences/UserPreferencesRepository.kt")).contains("const val DEFAULT_DAC_SHARE_ENABLED = false")
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
}
