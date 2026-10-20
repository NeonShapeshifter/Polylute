package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Phase 13 — the master rebuild decision.
 *
 * The defect this pins is an ordering hazard, which is why the decision is pure. `desiredBackend`
 * is written both by the route policy *and* by `publishUsbDiagnostics` when a DAC becomes
 * available. If the DAC arrives after the running player was built Android-only, the backend is
 * already `USB_DIRECT` by the time the policy next runs, so a "did the backend change?" test says
 * no — and the player that could actually claim the exclusive route is never built. Direct then
 * never activates at all, for the rest of the session.
 */
class MasterRebuildPolicyTest {

    private fun facts(
        decidedBackend: AudioBackend,
        previousDesiredBackend: AudioBackend,
        playerBuiltWithDirectAccess: Boolean,
        directIsActive: Boolean = false,
        masterInitialised: Boolean = true,
        directClaimable: Boolean = false,
        directClaimAttempted: Boolean = false,
    ) = MasterRebuildFacts(
        decidedBackend = decidedBackend,
        previousDesiredBackend = previousDesiredBackend,
        playerBuiltWithDirectAccess = playerBuiltWithDirectAccess,
        directIsActive = directIsActive,
        masterInitialised = masterInitialised,
        directClaimable = directClaimable,
        directClaimAttempted = directClaimAttempted,
    )

    @Test fun `an ordinary backend change rebuilds`() {
        val f = facts(
            decidedBackend = AudioBackend.USB_DIRECT,
            previousDesiredBackend = AudioBackend.ANDROID_AUDIO,
            playerBuiltWithDirectAccess = false,
        )
        assertThat(f.backendChanged).isTrue()
        assertThat(f.shouldRebuild).isTrue()
    }

    /**
     * The hazard. Desired backend already says Direct because the DAC's arrival wrote it, but the
     * player in hand was built Android-only. Only the capability test catches this.
     */
    @Test fun `a DAC arriving after an Android-only player was built still rebuilds`() {
        val f = facts(
            decidedBackend = AudioBackend.USB_DIRECT,
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = false,
        )
        assertThat(f.backendChanged).isFalse()
        assertThat(f.needsCapabilityRebuild).isTrue()
        assertThat(f.shouldRebuild).isTrue()
    }

    @Test fun `a player that already has Direct capability is left alone`() {
        val f = facts(
            decidedBackend = AudioBackend.USB_DIRECT,
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = true,
        )
        assertThat(f.shouldRebuild).isFalse()
    }

    @Test fun `an already-active Direct stream is never rebuilt`() {
        val f = facts(
            decidedBackend = AudioBackend.USB_DIRECT,
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = true,
            directIsActive = true,
        )
        assertThat(f.shouldRebuild).isFalse()
    }

    /**
     * A listening choice pins Android while the player still has Direct capability. That is a
     * backend change (Direct → Android) and must rebuild, so the exclusive sink is released.
     */
    @Test fun `pinning Android releases a Direct-capable player`() {
        val f = facts(
            decidedBackend = AudioBackend.ANDROID_AUDIO,
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = true,
        )
        assertThat(f.backendChanged).isTrue()
        assertThat(f.shouldRebuild).isTrue()
    }

    /**
     * The symmetric hazard: the desired backend already reads Android (for example because a DAC
     * detach wrote it), but the player in hand still holds the exclusive sink.
     */
    @Test fun `an Android-only player is rebuilt when capability is stale`() {
        val f = facts(
            decidedBackend = AudioBackend.ANDROID_AUDIO,
            previousDesiredBackend = AudioBackend.ANDROID_AUDIO,
            playerBuiltWithDirectAccess = true,
        )
        assertThat(f.backendChanged).isFalse()
        assertThat(f.needsCapabilityRebuild).isTrue()
        assertThat(f.shouldRebuild).isTrue()
    }

    @Test fun `no master player means nothing to rebuild`() {
        val f = facts(
            decidedBackend = AudioBackend.USB_DIRECT,
            previousDesiredBackend = AudioBackend.ANDROID_AUDIO,
            playerBuiltWithDirectAccess = false,
            masterInitialised = false,
        )
        assertThat(f.shouldRebuild).isFalse()
    }

    @Test fun `a steady Android-only state is stable and rebuilds nothing`() {
        val f = facts(
            decidedBackend = AudioBackend.ANDROID_AUDIO,
            previousDesiredBackend = AudioBackend.ANDROID_AUDIO,
            playerBuiltWithDirectAccess = false,
        )
        assertThat(f.shouldRebuild).isFalse()
    }

    @Test fun `a steady Direct-capable state is stable and rebuilds nothing`() {
        val f = facts(
            decidedBackend = AudioBackend.USB_DIRECT,
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = true,
        )
        assertThat(f.shouldRebuild).isFalse()
    }

    // ---------------------------------------------------------------- the claim dead end

    /**
     * STATE B — Direct is wanted, the sink is in hand, Direct never claimed, and there is still
     * nothing to configure the sink from.
     *
     * This is the state the old model made terminal. It must not rebuild here (a second build with
     * an empty queue produces the identical player), but it must also not be a state nothing can
     * leave.
     */
    @Test fun `Direct wanted with an unclaimable source does not rebuild`() {
        val f = facts(
            decidedBackend = AudioBackend.USB_DIRECT,
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = true,
            directClaimable = false,
        )
        assertThat(f.backendChanged).isFalse()
        assertThat(f.needsCapabilityRebuild).isFalse()
        assertThat(f.needsClaimAttempt).isFalse()
        assertThat(f.shouldRebuild).isFalse()
    }

    /** The same facts asked repeatedly must decide the same way — this is the anti-loop property. */
    @Test fun `an unchanged unclaimable state rebuilds no matter how often it is asked`() {
        repeat(50) {
            val f = facts(
                decidedBackend = AudioBackend.USB_DIRECT,
                previousDesiredBackend = AudioBackend.USB_DIRECT,
                playerBuiltWithDirectAccess = true,
                directClaimable = false,
            )
            assertThat(f.shouldRebuild).isFalse()
        }
    }

    /**
     * And once a source appears, exactly one claim attempt becomes legitimate — the fact changed,
     * which is the only thing that is ever allowed to open this window.
     */
    @Test fun `a source appearing makes exactly one claim attempt eligible`() {
        val f = facts(
            decidedBackend = AudioBackend.USB_DIRECT,
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = true,
            directClaimable = true,
            directClaimAttempted = false,
        )
        assertThat(f.needsCapabilityRebuild).isFalse()
        assertThat(f.needsClaimAttempt).isTrue()
        assertThat(f.shouldRebuild).isTrue()
    }

    /**
     * Once that attempt has been spent the decision is stable again. A failed claim is Android
     * fallback, not a licence to rebuild — this is the exact shape of the old eleven-per-second
     * oscillation if the latch is dropped.
     */
    @Test fun `a spent claim attempt does not rebuild again`() {
        val f = facts(
            decidedBackend = AudioBackend.USB_DIRECT,
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = true,
            directClaimable = true,
            directClaimAttempted = true,
        )
        assertThat(f.needsClaimAttempt).isFalse()
        assertThat(f.shouldRebuild).isFalse()
    }

    /** A live Direct stream outranks everything: whatever the other facts say, leave it alone. */
    @Test fun `an active Direct stream is never rebuilt even with a spendable claim`() {
        val f = facts(
            decidedBackend = AudioBackend.USB_DIRECT,
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = true,
            directIsActive = true,
            directClaimable = true,
            directClaimAttempted = false,
        )
        assertThat(f.needsClaimAttempt).isFalse()
        assertThat(f.shouldRebuild).isFalse()
    }

    /**
     * Claimability alone is not a reason to want Direct. Processing that requires Android keeps the
     * route on Android however claimable the sink is.
     */
    @Test fun `claimability does not pull a deliberate Android route to Direct`() {
        val f = facts(
            decidedBackend = AudioBackend.ANDROID_AUDIO,
            previousDesiredBackend = AudioBackend.ANDROID_AUDIO,
            playerBuiltWithDirectAccess = false,
            directClaimable = true,
            directClaimAttempted = false,
        )
        assertThat(f.needsClaimAttempt).isFalse()
        assertThat(f.shouldRebuild).isFalse()
    }

    /** The claim reason needs capability, not just desire — an Android-only player is the second reason's job. */
    @Test fun `an Android-only player with a source rebuilds by capability and not by claim`() {
        val f = facts(
            decidedBackend = AudioBackend.USB_DIRECT,
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = false,
            directClaimable = true,
        )
        assertThat(f.needsClaimAttempt).isFalse()
        assertThat(f.needsCapabilityRebuild).isTrue()
        assertThat(f.shouldRebuild).isTrue()
    }

    // ------------------------------------------ the re-entrancy guard on the rebuild

    @Test fun `a rebuild in flight is not re-entered`() {
        val cycle = MasterRebuildCycle()

        assertThat(cycle.beginRebuild()).isTrue()
        assertThat(cycle.isRebuilding).isTrue()

        // The second caller must be refused: proceeding would release the player the in-flight
        // rebuild is still assembling.
        assertThat(cycle.beginRebuild()).isFalse()
        assertThat(cycle.isRebuilding).isTrue()
    }

    @Test fun `a reevaluation asked for during a rebuild is owed once the rebuild ends`() {
        val cycle = MasterRebuildCycle()
        cycle.beginRebuild()
        cycle.deferReevaluation()

        assertThat(cycle.endRebuild()).isTrue()
        assertThat(cycle.isRebuilding).isFalse()
    }

    @Test fun `a rebuild nobody interrupted owes no replay`() {
        val cycle = MasterRebuildCycle()
        cycle.beginRebuild()

        assertThat(cycle.endRebuild()).isFalse()
    }

    @Test fun `repeated requests during one rebuild coalesce into a single replay`() {
        val cycle = MasterRebuildCycle()
        cycle.beginRebuild()
        repeat(40) { cycle.deferReevaluation() }

        assertThat(cycle.endRebuild()).isTrue()
        // The debt is cleared by paying it, so the caller replays once and the loop cannot sustain
        // itself by re-deferring.
        assertThat(cycle.endRebuild()).isFalse()
    }

    @Test fun `the next rebuild starts with a clean slate`() {
        val cycle = MasterRebuildCycle()
        cycle.beginRebuild()
        cycle.deferReevaluation()
        cycle.endRebuild()

        // A debt left over from a previous rebuild must not be paid by the next one.
        assertThat(cycle.beginRebuild()).isTrue()
        assertThat(cycle.endRebuild()).isFalse()
    }
}
