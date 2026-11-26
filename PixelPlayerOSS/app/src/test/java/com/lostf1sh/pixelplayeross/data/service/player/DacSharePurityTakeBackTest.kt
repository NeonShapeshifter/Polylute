package com.lostf1sh.pixelplayeross.data.service.player

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.volume.HandoffResumeSafety
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import com.lostf1sh.pixelplayeross.data.volume.VolumeBackendSnapshot
import org.junit.jupiter.api.Test

/**
 * B68 item 3: Purity releases in app mode, and play takes the DAC back to **the same route a Purity play that never released has**:
 * `DIRECT_PRISTINE`, with the level confirmed before anything starts (B40-P1).
 *
 * The real share controller, the real [DacSharePolicy], the real [AudioRoutePolicy], the real Purity lock ([PristineModePolicy]) and the
 * real level judgments ([HandoffResumeSafety]). The stand-in is the engine's host, line for line where it builds the facts:
 * `processing = effectiveRequirements(raw, lock, dac)`, `dacShareAllowed = dacReleased && shareAllowed(switch, lockInForce, releaseMode)`,
 * and the release mode latched in `setReleased`. The bytes on the wire are the native gate's (Pristine 43/43); this proves the route that
 * selects them is the same one.
 */
class DacSharePurityTakeBackTest {
    /** The Melody at -30 dB, level read back, no ceiling: the open guard's ordinary result. */
    private val melody = SafetyVolumeState(
        backend = VolumeBackendSnapshot(
            SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, "dac:melody", "SNOWSKY Melody", -7680, -25600, 0, true, levelVerified = true,
        ),
        selectedProfileId = "p",
        safetyEnabled = false,
        calibration = null,
        hardwareDecibels = -30f,
    )

    private class Engine(var mode: EffectiveProductMode?) : DacShareController.Host {
        // the listening state as published: EQ and ReplayGain on, which the Purity lock holds off
        val raw = AudioProcessingRequirements(graphicEq = true, replayGain = true)
        var lockPreference = true
        val dacPresent = true
        override var enabled = true
        var dacReleased = false
        var releaseMode: EffectiveProductMode? = null
        var directActive = true
        var level = UserPlayLevel.UNCONFIRMED
        var verdict = HandoffVolumeVerdict.WAIT
        var focusRequests = 0
        val resumes = mutableListOf<String>()

        override val usbDacPresent get() = dacPresent
        override val purityInForce get() = PristineModePolicy.lockInForce(lockPreference, dacPresent)
        override fun effectiveProductMode() = mode
        override fun setReleased(released: Boolean) {
            releaseMode = if (released) effectiveProductMode() else null
            this.dacReleased = released
            directActive = false   // the rebuild goes on: Direct is not up until the sink says so
        }
        override fun requestFocus(): Boolean { focusRequests++; return true }
        override fun resume() { resumes += "resume" }
        override fun directReady() = directActive && verdict == HandoffVolumeVerdict.RESUME
        override fun userPlayReady() = directActive && level == UserPlayLevel.CONFIRMED

        fun decision(): AudioRouteDecision = AudioRoutePolicy.decide(
            AudioRouteFacts(
                usbDacPresent = dacPresent,
                usbPermissionGranted = true,
                processing = PristineModePolicy.effectiveRequirements(raw, lockPreference, dacPresent),
                dacReleasedForSharing = dacReleased,
                dacShareAllowed = dacReleased && DacSharePolicy.shareAllowed(enabled, purityInForce, releaseMode),
                androidUsbRouteAvailable = true,
            ),
        )

        fun sinkUp(state: SafetyVolumeState) {
            directActive = true
            level = HandoffResumeSafety.userPlayLevel(state)
            verdict = HandoffResumeSafety.verdict(state)
        }
    }

    @Test
    fun `app mode, Purity - pause, release, play - DIRECT_PRISTINE again, identical to a play that never released, level confirmed`() {
        val engine = Engine(EffectiveProductMode.APP)
        val share = DacShareController(engine)
        val neverReleased = engine.decision()
        assertThat(neverReleased.reason).isEqualTo(AudioRouteReason.DIRECT_PRISTINE)
        assertThat(neverReleased.signalMode).isEqualTo(DirectSignalMode.PRISTINE)

        // the listener pauses; 3 s later the DAC goes to Android
        assertThat(share.onPaused()).isEqualTo(DacShareController.PauseOutcome.RELEASE_AFTER_GRACE)
        share.onPauseGraceElapsed(playing = false)
        assertThat(engine.decision().backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(engine.decision().reason).isEqualTo(AudioRouteReason.DAC_SHARED_WITH_OTHER_APPS)

        // play: focus first, then the ordinary Direct claim; nothing starts before the level is read back
        assertThat(share.onPlayRequested()).isFalse()
        assertThat(engine.focusRequests).isEqualTo(1)
        val takenBack = engine.decision()
        assertThat(takenBack).isEqualTo(neverReleased)
        share.onRouteState()
        assertThat(engine.resumes).isEmpty()
        engine.sinkUp(melody)
        assertThat(engine.level).isEqualTo(UserPlayLevel.CONFIRMED)
        share.onRouteState()
        assertThat(engine.resumes).containsExactly("resume")
        assertThat(engine.decision()).isEqualTo(neverReleased)
    }

    @Test
    fun `app mode, Purity - a video takes the floor and ends - the automatic resume comes back on DIRECT_PRISTINE too`() {
        val engine = Engine(EffectiveProductMode.APP)
        val share = DacShareController(engine)
        val neverReleased = engine.decision()
        share.onFocusLost(permanent = false)
        assertThat(engine.decision().reason).isEqualTo(AudioRouteReason.DAC_SHARED_WITH_OTHER_APPS)
        share.onFocusGained(resumeOwedByTheEngine = true)
        assertThat(engine.decision()).isEqualTo(neverReleased)
        engine.sinkUp(melody)   // -30 dB holds: the conservative verdict lets an automatic resume start
        share.onRouteState()
        assertThat(engine.resumes).containsExactly("resume")
    }

    @Test
    fun `Dedicated, Purity - nothing ever leaves DIRECT_PRISTINE`() {
        val engine = Engine(EffectiveProductMode.DEDICATED_ACTIVE)
        val share = DacShareController(engine)
        val neverReleased = engine.decision()
        share.onPaused()
        share.onPauseGraceElapsed(playing = false)
        share.onFocusLost(permanent = false)
        share.onFocusLost(permanent = true)
        assertThat(engine.dacReleased).isFalse()
        assertThat(engine.decision()).isEqualTo(neverReleased)
        assertThat(share.onPlayRequested()).isTrue()   // straight through: nothing to take back
        assertThat(engine.focusRequests).isEqualTo(0)
    }

    @Test
    fun `the mode changes while released - the route keeps the release until play takes the DAC back as usual`() {
        val engine = Engine(EffectiveProductMode.APP)
        val share = DacShareController(engine)
        val neverReleased = engine.decision()
        share.onFocusLost(permanent = true)
        // the listener makes Polylute the home app (LAUNCHER) while YouTube plays on the DAC: nothing is forced
        engine.mode = EffectiveProductMode.LAUNCHER
        assertThat(engine.decision().reason).isEqualTo(AudioRouteReason.DAC_SHARED_WITH_OTHER_APPS)
        assertThat(share.released).isTrue()
        // play takes it back as usual
        assertThat(share.onPlayRequested()).isFalse()
        engine.sinkUp(melody)
        share.onRouteState()
        assertThat(engine.resumes).containsExactly("resume")
        assertThat(engine.decision()).isEqualTo(neverReleased)
        // and from now on, at the DAP level, Purity keeps it
        share.onPaused()
        share.onPauseGraceElapsed(playing = false)
        assertThat(share.released).isFalse()
    }
}
