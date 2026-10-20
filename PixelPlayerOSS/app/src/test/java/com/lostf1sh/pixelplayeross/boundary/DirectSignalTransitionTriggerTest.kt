package com.lostf1sh.pixelplayeross.boundary

import com.decent.usbaudio.media3.DirectSignalMode
import com.decent.usbaudio.media3.DirectSignalModeReconciler
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The signal-mode transition trigger, tested against the shape the hardware actually has.
 *
 * ## The failure being reproduced
 *
 * On the HONOR, with Direct active and a local FLAC playing, enabling the EQ produced:
 *
 * ```
 * transport.reason = DIRECT_PROCESSED   ← the route switched
 * signal.mode      = PRISTINE           ← the sink never did
 * modeTransitions=0  nativeEngineStops=0  stageProcessCalls=0
 * → StuckPlayerException: Player stuck playing with no progress for 10000 ms
 * ```
 *
 * The transition was discovered by `handleBuffer`, and `handleBuffer` never ran: while
 * `NativeAudioEngine` owns a local FLAC, `NativeEngineAwareLoadControl` stops ExoPlayer loading,
 * so the sink's PCM callback goes silent for the rest of the track.
 *
 * **These tests therefore never simulate a PCM callback at all.** There is no `handleBuffer` in any
 * of them and no equivalent — a trigger that needed one could not pass. That absence is the
 * assertion: the transition has to be reachable from the pushed request alone.
 */
class DirectSignalTransitionTriggerTest {

    @Test
    fun `a pushed request transitions with zero PCM callbacks`() {
        val reconciler = DirectSignalModeReconciler(DirectSignalMode.PRISTINE)

        val shouldRun = reconciler.onRequest()
        val transitions = reconciler.reconcile(DirectSignalMode.PROCESSED)

        assertThat(shouldRun).isTrue()
        assertThat(transitions).isTrue()
        assertThat(reconciler.applied).isEqualTo(DirectSignalMode.PROCESSED)
    }

    @Test
    fun `one request produces exactly one transition`() {
        val reconciler = DirectSignalModeReconciler()

        var transitions = 0
        if (reconciler.onRequest()) {
            if (reconciler.reconcile(DirectSignalMode.PROCESSED)) transitions++
        }
        // A second reconcile for the same settled state must find nothing to do — this is what
        // stops the PCM path's own observer from performing the transition a second time when it
        // eventually does wake up.
        if (reconciler.reconcile(DirectSignalMode.PROCESSED)) transitions++

        assertThat(transitions).isEqualTo(1)
    }

    @Test
    fun `a request arriving while one is owed is coalesced`() {
        val reconciler = DirectSignalModeReconciler()

        assertThat(reconciler.onRequest()).isTrue()
        // The owed pass will read the same latest state, so this one earns nothing.
        assertThat(reconciler.onRequest()).isFalse()
    }

    @Test
    fun `rapid pristine to processed to pristine performs no transition`() {
        // EQ on then off before the pass runs. The pass reads the *latest* mode, finds PRISTINE
        // already applied, and does nothing — rather than executing an obsolete round trip through
        // PROCESSED and back, which would tear the engine down and rebuild it for nothing.
        val reconciler = DirectSignalModeReconciler(DirectSignalMode.PRISTINE)

        reconciler.onRequest()
        reconciler.onRequest()
        val transitions = reconciler.reconcile(DirectSignalMode.PRISTINE)

        assertThat(transitions).isFalse()
        assertThat(reconciler.applied).isEqualTo(DirectSignalMode.PRISTINE)
    }

    @Test
    fun `rapid changes converge to the newest state in one transition`() {
        // EQ on → volume non-unity → EQ off, where digital volume still requires processing.
        // The newest state is PROCESSED, so exactly one transition reaches it.
        val reconciler = DirectSignalModeReconciler(DirectSignalMode.PRISTINE)

        reconciler.onRequest()
        reconciler.onRequest()
        reconciler.onRequest()
        val transitions = reconciler.reconcile(DirectSignalMode.PROCESSED)

        assertThat(transitions).isTrue()
        assertThat(reconciler.applied).isEqualTo(DirectSignalMode.PROCESSED)
    }

    @Test
    fun `the reverse direction is reachable the same way`() {
        val reconciler = DirectSignalModeReconciler(DirectSignalMode.PROCESSED)

        assertThat(reconciler.onRequest()).isTrue()
        assertThat(reconciler.reconcile(DirectSignalMode.PRISTINE)).isTrue()
        assertThat(reconciler.applied).isEqualTo(DirectSignalMode.PRISTINE)
    }

    @Test
    fun `repeated toggles alternate rather than oscillating`() {
        // Ten toggles must produce exactly ten transitions — one per genuine change — and end where
        // the last request asked. An oscillation would produce more; a dropped transition fewer.
        val reconciler = DirectSignalModeReconciler(DirectSignalMode.PRISTINE)
        var transitions = 0
        var desired = DirectSignalMode.PRISTINE

        repeat(10) {
            desired = if (desired == DirectSignalMode.PRISTINE) {
                DirectSignalMode.PROCESSED
            } else {
                DirectSignalMode.PRISTINE
            }
            reconciler.onRequest()
            if (reconciler.reconcile(desired)) transitions++
        }

        assertThat(transitions).isEqualTo(10)
        assertThat(reconciler.applied).isEqualTo(DirectSignalMode.PRISTINE)
    }

    @Test
    fun `a mode resolved at configure time does not cause a spurious transition`() {
        // `configure` decides the mode for a new stream before any request can arrive. Without
        // markApplied, the first reconcile would compare the host's mode against the stale default
        // and tear down a producer that was already correct — on every stream start.
        val reconciler = DirectSignalModeReconciler(DirectSignalMode.PRISTINE)
        reconciler.markApplied(DirectSignalMode.PROCESSED)

        reconciler.onRequest()
        assertThat(reconciler.reconcile(DirectSignalMode.PROCESSED)).isFalse()
    }

    @Test
    fun `releasing the stream invalidates what was applied`() {
        // The audio path is gone, so "already applied" is no longer true of anything. The next
        // reconcile must transition rather than skip.
        val reconciler = DirectSignalModeReconciler(DirectSignalMode.PROCESSED)
        reconciler.invalidate()

        reconciler.onRequest()
        assertThat(reconciler.reconcile(DirectSignalMode.PROCESSED)).isTrue()
    }
}
