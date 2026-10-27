package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.volume.HandoffResumeSafety
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import com.lostf1sh.pixelplayeross.data.volume.VolumeBackendSnapshot
import org.junit.jupiter.api.Test

/**
 * B40-P1: the S22 sequence of 2026-10-25 02:12:57-02:13:12 as a state test, with the real safety judgments and the real share controller.
 *
 * A Fosi DS2 at -21 dB, no Safety Max ceiling calibrated, Resonance, "Share the DAC" on. A video takes the floor and the DAC is released;
 * the video ends and the DAC is claimed again (Direct ACTIVE and paused in 0.3 s); the listener taps play six times. Before this batch the
 * claim waited for the automatic-resume verdict (HOLD at -21 dB without a ceiling) and turned every tap back until the 10 s timeout.
 */
class DacSharePlayAfterShareTest {
    private fun ds2(rawLevel: Int, decibels: Float, verified: Boolean = true, ceilingRaw: Int? = null) = SafetyVolumeState(
        backend = VolumeBackendSnapshot(
            SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, "dac:ds2", "Fosi Audio DS2", rawLevel, -5120, 0, true, levelVerified = verified,
        ),
        selectedProfileId = "p",
        safetyEnabled = ceilingRaw != null,
        calibration = ceilingRaw?.let {
            SafetyMaxCalibration("dac:ds2", SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, it, -5120, 0, 1L)
        },
        hardwareDecibels = decibels,
    )

    /** The engine's part: the published judgments decide the host's two readiness questions, and the route says whether Direct is on. */
    private class EngineStandIn : DacShareController.Host {
        var directActive = false
        var verdict = HandoffVolumeVerdict.WAIT
        var level = UserPlayLevel.UNCONFIRMED
        val resumes = mutableListOf<String>()
        val refusals = mutableListOf<DacShareController.ClaimRefusal>()
        override val enabled = true
        override val resonance = true
        override fun setReleased(released: Boolean) = Unit
        override fun requestFocus() = true
        override fun resume() { resumes += "resume" }
        override fun directReady() = directActive && verdict == HandoffVolumeVerdict.RESUME
        override fun userPlayReady() = directActive && level == UserPlayLevel.CONFIRMED
        override fun refused(reason: DacShareController.ClaimRefusal) { refusals += reason }

        fun publish(state: SafetyVolumeState) {
            verdict = HandoffResumeSafety.verdict(state)
            level = HandoffResumeSafety.userPlayLevel(state)
        }
    }

    private val engine = EngineStandIn()
    private val share = DacShareController(engine)

    private fun videoEndsAndTheDacIsClaimed(state: SafetyVolumeState) {
        engine.publish(state)
        share.onFocusLost(permanent = false)        // 02:12:57 the video takes the floor: pause, release
        share.onFocusGained(resumeOwedByTheEngine = true)   // 02:13:00.94 it ends: focus gain, DAC taken back
        engine.directActive = true                  // 02:13:01.26 USB Direct stream ACTIVE (paused)
        share.onRouteState()
    }

    @Test
    fun `the judgments say what the log showed - the automatic verdict holds and the level is confirmed`() {
        val state = ds2(rawLevel = -2688, decibels = -21f)
        assertThat(HandoffResumeSafety.verdict(state)).isEqualTo(HandoffVolumeVerdict.HOLD)
        assertThat(HandoffResumeSafety.userPlayLevel(state)).isEqualTo(UserPlayLevel.CONFIRMED)
    }

    @Test
    fun `six play taps after the DAC is back all go through at once`() {
        videoEndsAndTheDacIsClaimed(ds2(rawLevel = -2688, decibels = -21f))
        repeat(6) { assertThat(share.onPlayRequested()).isTrue() }
        assertThat(engine.refusals).isEmpty()
    }

    @Test
    fun `an automatic resume at that level holds, and nothing is started by the engine`() {
        videoEndsAndTheDacIsClaimed(ds2(rawLevel = -2688, decibels = -21f))
        assertThat(engine.resumes).isEmpty()
        assertThat(share.claiming).isFalse()
    }

    @Test
    fun `a tap while Direct is still coming up starts when the level is read, not when the claim times out`() {
        engine.publish(ds2(rawLevel = -2688, decibels = -21f))
        share.onFocusLost(permanent = false)
        share.onFocusGained(resumeOwedByTheEngine = true)
        assertThat(share.onPlayRequested()).isFalse()   // Direct is not up yet
        engine.directActive = true
        share.onRouteState()
        assertThat(engine.resumes).containsExactly("resume")
    }

    @Test
    fun `a level that could not be read back refuses with the reason after the claim timeout`() {
        videoEndsAndTheDacIsClaimedWithUnreadLevel()
        assertThat(share.onPlayRequested()).isFalse()
        share.onClaimTimeout(directActive = true)
        assertThat(engine.refusals).containsExactly(DacShareController.ClaimRefusal.LEVEL_UNCONFIRMED)
        assertThat(engine.resumes).isEmpty()
    }

    private fun videoEndsAndTheDacIsClaimedWithUnreadLevel() {
        engine.publish(ds2(rawLevel = -2688, decibels = -21f, verified = false))
        share.onFocusLost(permanent = false)
        share.onFocusGained(resumeOwedByTheEngine = false)
        engine.directActive = true
        // nothing owed: the listener's own tap is what claims
        share.onPlayRequested()
        share.onRouteState()
    }

    @Test
    fun `above an armed ceiling the listener's play waits for the clamp`() {
        val above = ds2(rawLevel = -1000, decibels = -8f, ceilingRaw = -2048)
        assertThat(HandoffResumeSafety.userPlayLevel(above)).isEqualTo(UserPlayLevel.UNCONFIRMED)
        val within = ds2(rawLevel = -2560, decibels = -20f, ceilingRaw = -2048)
        assertThat(HandoffResumeSafety.userPlayLevel(within)).isEqualTo(UserPlayLevel.CONFIRMED)
    }
}
