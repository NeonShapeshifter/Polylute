package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-002: the handoff may only give playback back once the DAC's level is known to be safe.
 *
 * `DIRECT_ACTIVE` is published synchronously from the sink, before `SafetyVolumeController` (an IO
 * collector) has read the DAC's Feature Unit and clamped it. Resuming on `DIRECT_ACTIVE` alone played
 * at whatever level the DAC happened to be at — the uncontrolled increase the deferral exists to
 * prevent.
 */
class AutoDirectHandoffVolumeSafetyTest {

    private val itemId = "track-1"

    private fun started() = AutoDirectHandoff(timeoutMs = 2_000L).also {
        it.begin(
            reason = AudioRouteReason.SAFETY_TRANSITION_DEFERRED,
            isPlaying = true,
            directIsActive = false,
            itemId = itemId,
            nowMs = 1_000L,
        )
        it.consumeOwnPause()
    }

    private val directActive = AudioRouteState(
        desiredBackend = AudioBackend.USB_DIRECT,
        actualBackend = AudioBackend.USB_DIRECT,
        transition = AudioRouteTransition.DIRECT_ACTIVE,
        reason = AudioRouteReason.DIRECT_ACTIVE,
        confirmedOutputRateHz = 44_100,
    )

    @Test
    fun `DIRECT_ACTIVE before the volume is reconciled does not resume`() {
        val handoff = started()

        val step = handoff.resumeStep(directActive, itemId, HandoffVolumeVerdict.WAIT)

        assertThat(step).isEqualTo(AutoDirectHandoff.ResumeStep.WAIT)
        assertThat(handoff.isInProgress).isTrue()
    }

    @Test
    fun `resumes once the DAC level is reconciled within the ceiling`() {
        val handoff = started()
        handoff.resumeStep(directActive, itemId, HandoffVolumeVerdict.WAIT)

        val step = handoff.resumeStep(directActive, itemId, HandoffVolumeVerdict.RESUME)

        assertThat(step).isEqualTo(AutoDirectHandoff.ResumeStep.RESUME)
    }

    @Test
    fun `a safe verdict alone, without DIRECT_ACTIVE, does not resume`() {
        val handoff = started()
        val negotiating = directActive.copy(transition = AudioRouteTransition.DIRECT_NEGOTIATING)

        assertThat(handoff.resumeStep(negotiating, itemId, HandoffVolumeVerdict.RESUME))
            .isEqualTo(AutoDirectHandoff.ResumeStep.WAIT)
    }

    @Test
    fun `a fixed output or an unbounded level concludes without resuming`() {
        val handoff = started()

        assertThat(handoff.resumeStep(directActive, itemId, HandoffVolumeVerdict.HOLD))
            .isEqualTo(AutoDirectHandoff.ResumeStep.CONCLUDE_WITHOUT_RESUME)
    }

    @Test
    fun `timing out after Direct is active, still waiting on the volume, does not resume`() {
        val handoff = started()
        handoff.resumeStep(directActive, itemId, HandoffVolumeVerdict.WAIT)

        assertThat(handoff.timeoutResumes()).isFalse()
    }

    @Test
    fun `timing out before Direct is reached still falls back to Android playback`() {
        val handoff = started()

        assertThat(handoff.timeoutResumes()).isTrue()
    }
}
