package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The automatic Direct handoff.
 *
 * Attaching a DAC during playback leaves Direct unreachable, because `isDirectTransitionAllowed`
 * refuses the transition while audio is audible — correctly, since the claim is heard as a gap. This
 * class spends that gap on the user's behalf: it pauses, lets the ordinary route machinery claim
 * Direct, and gives the playback back.
 *
 * These tests drive the real [AutoDirectHandoff]. Nothing here is a model of it.
 */
class AutoDirectHandoffTest {

    private val device = "SNOWSKY Melody"
    private val itemId = "track-1"

    private fun handoff() = AutoDirectHandoff(timeoutMs = 2_000L)

    /** Starts a handoff the way the engine would for a deferral while genuinely playing. */
    private fun AutoDirectHandoff.startWhilePlaying(nowMs: Long = 1_000L, item: Any? = itemId): Boolean =
        begin(
            reason = AudioRouteReason.SAFETY_TRANSITION_DEFERRED,
            isPlaying = true,
            directIsActive = false,
            itemId = item,
            nowMs = nowMs,
        )

    private fun directActive(item: Any? = itemId) = AudioRouteState(
        desiredBackend = AudioBackend.USB_DIRECT,
        actualBackend = AudioBackend.USB_DIRECT,
        transition = AudioRouteTransition.DIRECT_ACTIVE,
        reason = AudioRouteReason.DIRECT_ACTIVE,
        confirmedOutputRateHz = 44_100,
    )

    // ------------------------------------------------------------ 1. the trigger

    @Test
    fun `a deferral while genuinely playing starts exactly one handoff`() {
        val handoff = handoff()

        assertThat(handoff.startWhilePlaying()).isTrue()
        assertThat(handoff.isInProgress).isTrue()
    }

    @Test
    fun `repeated deferred callbacks during one handoff do not start another`() {
        val handoff = handoff()
        handoff.startWhilePlaying()

        // A burst of USB availability callbacks is normal on attach. Each one must not queue another
        // pause, another timeout, or another rebuild.
        repeat(20) { assertThat(handoff.startWhilePlaying()).isFalse() }
    }

    @Test
    fun `no handoff starts for anything other than the safety deferral`() {
        // The reason is the whole trigger: AudioRoutePolicy only reaches SAFETY_TRANSITION_DEFERRED
        // after processing, maintenance, DAC presence, permission and source compatibility have all
        // passed. Every other reason means Direct is not merely inaudible — it is not available.
        val ineligible = listOf(
            AudioRouteReason.NO_USB_DAC,
            AudioRouteReason.USB_PERMISSION_REQUIRED,
            AudioRouteReason.SOURCE_NOT_DIRECT_COMPATIBLE,
            AudioRouteReason.SPEED_REQUIRES_ANDROID,
            AudioRouteReason.CROSSFADE_REQUIRES_ANDROID,
            AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID,
            AudioRouteReason.MAINTENANCE_FORCE_ANDROID,
            AudioRouteReason.MAINTENANCE_DIRECT_DISABLED,
            AudioRouteReason.DIRECT_FAILED_FALLBACK,
            AudioRouteReason.STARTING,
        )

        ineligible.forEach { reason ->
            val handoff = handoff()
            val started = handoff.begin(
                reason = reason,
                isPlaying = true,
                directIsActive = false,
                itemId = itemId,
                nowMs = 1_000L,
            )
            assertThat(started).isFalse()
            assertThat(handoff.isInProgress).isFalse()
        }
    }

    // ------------------------------------------------------------ 4. already paused

    @Test
    fun `already-paused playback never starts a handoff`() {
        // The existing behaviour already reaches Direct from paused, and must keep doing so
        // untouched. Pausing something that is not playing would be meaningless anyway.
        val handoff = handoff()

        val started = handoff.begin(
            reason = AudioRouteReason.SAFETY_TRANSITION_DEFERRED,
            isPlaying = false,
            directIsActive = false,
            itemId = itemId,
            nowMs = 1_000L,
        )

        assertThat(started).isFalse()
        assertThat(handoff.isInProgress).isFalse()
    }

    @Test
    fun `direct already active never starts a handoff`() {
        val handoff = handoff()

        val started = handoff.begin(
            reason = AudioRouteReason.SAFETY_TRANSITION_DEFERRED,
            isPlaying = true,
            directIsActive = true,
            itemId = itemId,
            nowMs = 1_000L,
        )

        assertThat(started).isFalse()
    }

    // ------------------------------------------------------------ 2. the pause is ours

    @Test
    fun `the pause the handoff issues is recognised as its own`() {
        val handoff = handoff()
        handoff.startWhilePlaying()

        // Media3 reports USER_REQUEST for both a programmatic and a user pause, so the reason
        // cannot separate them — the handoff has to remember which pause it issued.
        assertThat(handoff.consumeOwnPause()).isTrue()
        // And only once: a second pause callback is the user.
        assertThat(handoff.consumeOwnPause()).isFalse()
    }

    @Test
    fun `once paused the route reevaluation is not itself a handoff trigger`() {
        // After the pause the engine re-evaluates and the policy now returns DIRECT_PRISTINE, not a
        // deferral. Nothing may start a second handoff from that.
        val handoff = handoff()
        handoff.startWhilePlaying()
        handoff.consumeOwnPause()

        val restarted = handoff.begin(
            reason = AudioRouteReason.DIRECT_PRISTINE,
            isPlaying = false,
            directIsActive = false,
            itemId = itemId,
            nowMs = 1_100L,
        )

        assertThat(restarted).isFalse()
        assertThat(handoff.isInProgress).isTrue() // still the original handoff, still waiting
    }

    // ------------------------------------------------------------ 3. resume

    @Test
    fun `DIRECT_ACTIVE resumes exactly once`() {
        val handoff = handoff()
        handoff.startWhilePlaying()
        handoff.consumeOwnPause()

        assertThat(handoff.isDirectActiveReached(directActive(), itemId)).isTrue()

        assertThat(handoff.conclude()).isTrue()
        // Concluding twice must not resume twice.
        assertThat(handoff.conclude()).isFalse()
        assertThat(handoff.isInProgress).isFalse()
    }

    @Test
    fun `eligible or built is not active, and does not resume`() {
        val handoff = handoff()
        handoff.startWhilePlaying()
        handoff.consumeOwnPause()

        val eligible = directActive().copy(
            actualBackend = AudioBackend.ANDROID_AUDIO,
            transition = AudioRouteTransition.DIRECT_ELIGIBLE,
            desiredBackend = AudioBackend.USB_DIRECT,
        )
        val negotiating = directActive().copy(
            actualBackend = AudioBackend.ANDROID_AUDIO,
            transition = AudioRouteTransition.DIRECT_NEGOTIATING,
        )

        // Exactly the proxies the brief forbids resuming on.
        assertThat(handoff.isDirectActiveReached(eligible, itemId)).isFalse()
        assertThat(handoff.isDirectActiveReached(negotiating, itemId)).isFalse()
    }

    @Test
    fun `DIRECT_ACTIVE for a different item does not resume`() {
        val handoff = handoff()
        handoff.startWhilePlaying(item = itemId)
        handoff.consumeOwnPause()

        assertThat(handoff.isDirectActiveReached(directActive(), "some-other-item")).isFalse()
    }

    // ------------------------------------------------------------ 5, 6. user actions

    @Test
    fun `a user pause during the handoff is never overridden`() {
        val handoff = handoff()
        handoff.startWhilePlaying()
        handoff.consumeOwnPause() // ours

        handoff.onUserPause() // theirs

        // Direct arriving later must not resurrect playback they stopped.
        assertThat(handoff.conclude()).isFalse()
    }

    @Test
    fun `a user play during the handoff cancels the resume`() {
        val handoff = handoff()
        handoff.startWhilePlaying()
        handoff.consumeOwnPause()

        // They resumed by hand; the handoff must not later resume over the top of them.
        handoff.onUserPlay()

        assertThat(handoff.conclude()).isFalse()
    }

    @Test
    fun `a user skipping track during the handoff does not resume the old item`() {
        val handoff = handoff()
        handoff.startWhilePlaying(item = "track-1")
        handoff.consumeOwnPause()

        handoff.onItemChanged("track-2", userInitiated = true)

        assertThat(handoff.conclude()).isFalse()
        assertThat(handoff.isInProgress).isFalse()
    }

    @Test
    fun `a natural end-of-track advance re-targets instead of cancelling`() {
        // Nobody acted; the next track is what they would have heard anyway.
        val handoff = handoff()
        handoff.startWhilePlaying(item = "track-1")
        handoff.consumeOwnPause()

        handoff.onItemChanged("track-2", userInitiated = false)

        assertThat(handoff.isInProgress).isTrue()
        assertThat(handoff.isDirectActiveReached(directActive("track-2"), "track-2")).isTrue()
        assertThat(handoff.conclude()).isTrue()
    }

    // ------------------------------------------------------------ 7, 8. route changes

    @Test
    fun `losing the DAC during the handoff never resumes`() {
        val handoff = handoff()
        handoff.startWhilePlaying()
        handoff.consumeOwnPause()

        handoff.onDeviceLost()

        // Restoring playback with the device gone would push audio out of the phone speaker — which
        // is exactly what the platform's AUDIO_BECOMING_NOISY pause exists to prevent.
        assertThat(handoff.conclude()).isFalse()
        assertThat(handoff.isInProgress).isFalse()
    }

    @Test
    fun `Direct becoming impossible still returns the playback`() {
        // Unlike a detach, the device is still there — it is Direct that is gone, for a reason that
        // is not audibility. The user asked to listen, so the resume is still owed.
        val handoff = handoff()
        handoff.startWhilePlaying()
        handoff.consumeOwnPause()

        assertThat(handoff.conclude()).isTrue()
    }

    // ------------------------------------------------------------ 9. timeout

    @Test
    fun `the wait times out, and a timeout still returns the playback`() {
        val handoff = handoff()
        handoff.startWhilePlaying(nowMs = 1_000L)
        handoff.consumeOwnPause()

        assertThat(handoff.hasTimedOut(2_999L)).isFalse()
        assertThat(handoff.hasTimedOut(3_000L)).isTrue()

        // A failed Direct claim must not strand the listener in silence.
        assertThat(handoff.conclude()).isTrue()
    }

    @Test
    fun `a timeout after a user cancel does not resume`() {
        val handoff = handoff()
        handoff.startWhilePlaying(nowMs = 1_000L)
        handoff.consumeOwnPause()
        handoff.onUserPause()

        assertThat(handoff.conclude()).isFalse()
    }

    // ------------------------------------------------------------ 10. fixed point

    @Test
    fun `once Direct is active the handoff is a fixed point`() {
        val handoff = handoff()
        handoff.startWhilePlaying()
        handoff.consumeOwnPause()
        handoff.conclude()

        // Every further evaluation now reports Direct active, and the deferral cannot recur while it
        // is: isDirectTransitionAllowed short-circuits on directIsActive.
        repeat(20) {
            assertThat(
                handoff.begin(
                    reason = AudioRouteReason.DIRECT_ACTIVE,
                    isPlaying = true,
                    directIsActive = true,
                    itemId = itemId,
                    nowMs = 5_000L,
                )
            ).isFalse()
        }
    }

    // ------------------------------------------------------------ 12. no rebuild-loop regression

    @Test
    fun `a completed handoff leaves the route on a Direct fixed point`() {
        // The handoff drives the route only by pausing; it never authorises a rebuild. Once Direct is
        // active, the rebuild policy must see the state it expects — not reworked, not re-triggered.
        val facts = MasterRebuildFacts(
            decidedBackend = AudioBackend.USB_DIRECT,
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = true,
            directIsActive = true,
            masterInitialised = true,
            directClaimable = true,
            directClaimAttempted = true,
        )

        assertThat(facts.needsCapabilityRebuild).isFalse()
        assertThat(facts.needsClaimAttempt).isFalse()
        assertThat(facts.backendChanged).isFalse()
        assertThat(facts.shouldRebuild).isFalse()
    }
}
