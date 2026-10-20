package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Phase 14.0C — a Direct-eligible play intent must reach Direct, and must then stay there.
 *
 * ## The defect these tests pin
 *
 * The permission to enter Direct used to be
 * `directIsActive || !initialised || (!playWhenReady && !isPlaying)`. With a play intent present
 * the last term was false, so the expression collapsed to `isDirectActive` — a statement about the
 * player that the rebuild it authorises destroys. Direct could therefore only be chosen from a
 * state the choice itself cancelled:
 *
 * ```
 * decision Android → rebuild → play intent restored → re-evaluate → Android → rebuild → …
 * ```
 *
 * On the HONOR that ran at roughly eleven `ExoPlayer` constructions per second, each republishing
 * playback state, rebuilding the media notification and rendering the global mini-player, so every
 * screen idled at ~16 fps. Pausing broke it (the play intent was the term that closed the gate);
 * playing broke it the other way (Direct finally claimed and stayed claimed).
 *
 * ## What is asserted
 *
 * The composed decision — policy, then rebuild facts — is driven to a **fixed point**. A single
 * legitimate rebuild to build the Direct-capable player is expected; a second one, with no new
 * input, is the bug.
 */
class DirectTransitionConvergenceTest {

    /** A DAC that is present, permitted and fed a source Direct can carry, with transparent DSP. */
    private val directEligibleHardware = AudioRouteFacts(
        usbDacPresent = true,
        usbPermissionGranted = true,
        sourceDirectCompatible = true,
        processing = AudioProcessingRequirements(),
    )

    private companion object {
        /**
         * Generous headroom over the handful of steps a converged loop takes, and low enough that
         * a re-entrant storm fails fast with a readable message rather than a stack overflow.
         */
        const val STEP_LIMIT = 200
    }

    /**
     * One turn of the engine's loop.
     *
     * `previousDesiredBackend` and `playerBuiltWithDirectAccess` are carried across turns the way
     * `_audioRouteState` and the engine field carry them; `directIsActive` is supplied by the
     * caller because it is a statement about the player the rebuild just replaced.
     */
    private data class Turn(
        val decision: AudioBackend,
        val reason: AudioRouteReason,
        val shouldRebuild: Boolean,
    )

    private fun turn(
        facts: AudioRouteFacts,
        previousDesiredBackend: AudioBackend,
        playerBuiltWithDirectAccess: Boolean,
        directIsActive: Boolean,
        directClaimable: Boolean = false,
        directClaimAttempted: Boolean = false,
    ): Turn {
        val decision = AudioRoutePolicy.decide(facts)
        val rebuild = MasterRebuildFacts(
            decidedBackend = decision.backend,
            previousDesiredBackend = previousDesiredBackend,
            playerBuiltWithDirectAccess = playerBuiltWithDirectAccess,
            directIsActive = directIsActive,
            masterInitialised = true,
            directClaimable = directClaimable,
            directClaimAttempted = directClaimAttempted,
        )
        return Turn(decision.backend, decision.reason, rebuild.shouldRebuild)
    }

    /**
     * The engine's route loop, modelled faithfully enough to be asked whether it converges.
     *
     * It carries the same state the engine carries — the desired backend, the capability of the
     * player in hand, the claim latch, whether Direct owns the route, and whether there is a source
     * the sink could be configured from — and applies the same update the engine applies when a
     * rebuild is authorised: the new player takes the sink its desired backend asks for, adopts the
     * queue, and (because the queue is non-empty) is prepared, which is what lets the sink claim.
     *
     * The claim succeeding is a parameter, because "the claim failed" is precisely the case that
     * must not license another rebuild.
     */
    private inner class EngineSimulation(
        private val claimSucceeds: Boolean = true,
        /**
         * Whether the rebuild honours [MasterRebuildCycle] the way the engine does. `false`
         * reproduces the pre-fix engine, whose rebuild ran — inline, against a half-built engine —
         * the route reevaluation its own diagnostic refresh asked for. Used only by the test that
         * proves this suite actually pins the storm.
         */
        private val honoursRebuildCycle: Boolean = true,
    ) {
        var desiredBackend = AudioBackend.ANDROID_AUDIO
        var builtWithDirectAccess = false
        var directClaimAttempted = false
        var directIsActive = false
        var mediaItemCount = 0
        var rebuilds = 0

        private val cycle = MasterRebuildCycle()

        /** Steps taken, so an unguarded storm fails an assertion instead of a stack overflow. */
        var steps = 0
            private set

        /**
         * One turn of `reevaluateAutomaticRoute`. Returns whether this turn rebuilt.
         *
         * [base] is the hardware/processing picture the turn is evaluated against; the transition
         * permission is derived here, from this engine's own Direct state, exactly as the engine
         * derives it.
         */
        fun evaluate(
            isPlaying: Boolean,
            base: AudioRouteFacts = directEligibleHardware,
        ): Boolean {
            check(steps++ < STEP_LIMIT) {
                "route loop exceeded $STEP_LIMIT steps: the rebuild is re-entering itself"
            }
            if (honoursRebuildCycle && cycle.isRebuilding) {
                cycle.deferReevaluation()
                return false
            }
            val decision = AudioRoutePolicy.decide(
                factsWith(isPlaying = isPlaying, directIsActive = directIsActive, base = base)
            )
            val facts = MasterRebuildFacts(
                decidedBackend = decision.backend,
                previousDesiredBackend = desiredBackend,
                playerBuiltWithDirectAccess = builtWithDirectAccess,
                directIsActive = directIsActive,
                masterInitialised = true,
                directClaimable = mediaItemCount > 0,
                directClaimAttempted = directClaimAttempted,
            )
            desiredBackend = decision.backend
            if (!facts.shouldRebuild) return false

            if (honoursRebuildCycle && !cycle.beginRebuild()) return false
            rebuilds++

            // The steps of `rebuildPlayersPreservingMasterState`, in the order the engine takes
            // them. The ordering is the whole point of this model: the pre-fix version applied all
            // of this atomically, which is why it could not see the defect.
            val queuedItems = mediaItemCount
            val builtFor = desiredBackend

            // 1. The previous player is released. Nothing has been restored yet.
            mediaItemCount = 0
            // 2. `refreshUsbAudioDiagnostics()`, which ends by asking for a route reevaluation.
            //    Evaluated against a half-built engine: no queue, and the capability latch below
            //    not yet written, so `playerBuiltWithDirectAccess` still describes the released
            //    player. Running this request inline is what recursed.
            evaluate(isPlaying, base)
            // 3. Restore the queue and prepare. `directClaimable` only becomes true here.
            mediaItemCount = queuedItems
            // 4. `recordDirectCapability()`, after the restore.
            builtWithDirectAccess = builtFor == AudioBackend.USB_DIRECT
            directClaimAttempted = builtWithDirectAccess && mediaItemCount > 0
            directIsActive = builtWithDirectAccess && mediaItemCount > 0 && claimSucceeds

            // 5. The engine is whole; replay the deferred reevaluation, once.
            if (honoursRebuildCycle && cycle.endRebuild()) {
                evaluate(isPlaying, base)
            }
            return true
        }
    }

    /** The permission the engine derives, expressed through the same named function it calls. */
    private fun factsWith(
        isPlaying: Boolean,
        directIsActive: Boolean,
        base: AudioRouteFacts = directEligibleHardware,
    ) = base.copy(
        directTransitionAllowed = isDirectTransitionAllowed(
            masterInitialised = true,
            directIsActive = directIsActive,
            isPlaying = isPlaying,
        )
    )

    // ---------------------------------------------------------------- CASE A

    @Test
    fun `a play intent that has not begun to sound is granted Direct`() {
        // The formerly-broken case: DAC attached, transparent processing, user presses Play, so a
        // play intent exists but no audio is coming out of Android yet.
        val first = turn(
            facts = factsWith(isPlaying = false, directIsActive = false),
            previousDesiredBackend = AudioBackend.ANDROID_AUDIO,
            playerBuiltWithDirectAccess = false,
            directIsActive = false,
        )

        assertThat(first.decision).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(first.reason).isEqualTo(AudioRouteReason.DIRECT_PRISTINE)
        // Not SAFETY_TRANSITION_DEFERRED, and not Android. That refusal was the loop.
        assertThat(first.reason).isNotEqualTo(AudioRouteReason.SAFETY_TRANSITION_DEFERRED)
    }

    // ---------------------------------------------------------------- CASE B

    @Test
    fun `the only rebuild is the one that builds the Direct-capable player`() {
        val first = turn(
            facts = factsWith(isPlaying = false, directIsActive = false),
            previousDesiredBackend = AudioBackend.ANDROID_AUDIO,
            playerBuiltWithDirectAccess = false,
            directIsActive = false,
        )
        assertThat(first.shouldRebuild).isTrue()

        // The rebuild that just happened was legitimate: it is the one that endows the master with
        // the Direct sink. Re-evaluate with the facts that rebuild produced.
        val second = turn(
            facts = factsWith(isPlaying = false, directIsActive = false),
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = true,
            directIsActive = false,
        )

        assertThat(second.decision).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(second.shouldRebuild).isFalse()
    }

    @Test
    fun `once Direct is streaming the decision is a fixed point`() {
        val settled = turn(
            facts = factsWith(isPlaying = true, directIsActive = true),
            previousDesiredBackend = AudioBackend.USB_DIRECT,
            playerBuiltWithDirectAccess = true,
            directIsActive = true,
        )

        assertThat(settled.decision).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(settled.shouldRebuild).isFalse()
    }

    @Test
    fun `no number of further turns produces a rebuild without new input`() {
        // The engine's rebuilt player re-evaluates from its own callbacks, so a converged state
        // must survive being asked repeatedly with nothing changing.
        var desired = AudioBackend.USB_DIRECT
        var builtDirect = true
        repeat(20) {
            val t = turn(
                facts = factsWith(isPlaying = true, directIsActive = true),
                previousDesiredBackend = desired,
                playerBuiltWithDirectAccess = builtDirect,
                directIsActive = true,
            )
            assertThat(t.shouldRebuild).isFalse()
            desired = t.decision
            builtDirect = t.decision == AudioBackend.USB_DIRECT
        }
    }

    // ------------------------------------------------- STATE B — the claim dead end

    /**
     * The regression this test exists for.
     *
     * A Direct-capable master was built while the queue was empty, so the sink was never configured
     * and Direct never claimed. Desired says Direct, the player holds the Direct sink, and Direct
     * is not active. Under the old model `playerBuiltWithDirectAccess != (decidedBackend ==
     * USB_DIRECT)` evaluated `true != true` — a fact about the player's construction being read as
     * a fact about its claim — and every later evaluation reached the same `false`, for the rest of
     * the session.
     *
     * With the facts unchanged, this state must stay quiet. It is not terminal, though: the test
     * right after shows the one thing allowed to move it.
     */
    @Test
    fun `a Direct-capable player that never claimed is not rebuilt from unchanged facts`() {
        val engine = EngineSimulation()
        engine.desiredBackend = AudioBackend.USB_DIRECT
        engine.builtWithDirectAccess = true
        engine.directClaimAttempted = false
        engine.mediaItemCount = 0

        repeat(20) { assertThat(engine.evaluate(isPlaying = false)).isFalse() }
        assertThat(engine.rebuilds).isEqualTo(0)
    }

    /**
     * The way out. A source arrives, so the sink can finally be configured and the claim the
     * player was built for can actually be made. Exactly one rebuild — the rebuild is how the
     * engine drives `prepare()`, which is what reaches `UsbAudioSink.configure()`.
     */
    @Test
    fun `a source arriving gives the Direct-capable player its one claim attempt`() {
        val engine = EngineSimulation()
        engine.desiredBackend = AudioBackend.USB_DIRECT
        engine.builtWithDirectAccess = true
        engine.directClaimAttempted = false
        engine.mediaItemCount = 0

        assertThat(engine.evaluate(isPlaying = false)).isFalse()

        // The queue is restored. Nothing else changes.
        engine.mediaItemCount = 1

        assertThat(engine.evaluate(isPlaying = false)).isTrue()
        assertThat(engine.rebuilds).isEqualTo(1)
        assertThat(engine.directIsActive).isTrue()

        // Direct owns the route, so this is the end of it.
        repeat(20) { assertThat(engine.evaluate(isPlaying = false)).isFalse() }
        assertThat(engine.rebuilds).isEqualTo(1)
    }

    /**
     * The same window, but the claim does not take. Android fallback is a resting state, not a
     * reason to build another `ExoPlayer` — this is the shape the old oscillation had.
     */
    @Test
    fun `a claim attempt that fails does not authorise another rebuild`() {
        val engine = EngineSimulation(claimSucceeds = false)
        engine.desiredBackend = AudioBackend.USB_DIRECT
        engine.builtWithDirectAccess = true
        engine.directClaimAttempted = false
        engine.mediaItemCount = 1

        assertThat(engine.evaluate(isPlaying = false)).isTrue()
        assertThat(engine.rebuilds).isEqualTo(1)
        assertThat(engine.directIsActive).isFalse()

        repeat(50) { assertThat(engine.evaluate(isPlaying = false)).isFalse() }
        assertThat(engine.rebuilds).isEqualTo(1)
    }

    /**
     * The whole path, from an empty Android-only engine to a settled Direct stream, counting the
     * constructions. Two rebuilds are legitimate and no more: one to build the Direct sink, one to
     * let it claim once there is a source. Ten more turns with nothing changing add none.
     */
    @Test
    fun `the whole path to Direct costs two rebuilds and then stops`() {
        val engine = EngineSimulation()
        engine.mediaItemCount = 0

        // A DAC arrives while the engine is idle and Android-only.
        assertThat(engine.evaluate(isPlaying = false)).isTrue()
        assertThat(engine.builtWithDirectAccess).isTrue()
        assertThat(engine.directClaimAttempted).isFalse()

        // Building the Direct sink against an empty queue cannot claim it.
        repeat(5) { assertThat(engine.evaluate(isPlaying = false)).isFalse() }

        // The user picks something to play: the source is what the sink was waiting for.
        engine.mediaItemCount = 1
        assertThat(engine.evaluate(isPlaying = false)).isTrue()
        assertThat(engine.directIsActive).isTrue()

        repeat(10) { assertThat(engine.evaluate(isPlaying = false)).isFalse() }
        assertThat(engine.rebuilds).isEqualTo(2)
    }

    /**
     * Claimability is not a licence to interrupt audible Android playback. The safety gate still
     * outranks it, so a source arriving mid-playback waits for the pause exactly as before.
     */
    @Test
    fun `a source arriving during audible playback does not force the claim`() {
        val engine = EngineSimulation()
        engine.desiredBackend = AudioBackend.ANDROID_AUDIO
        engine.builtWithDirectAccess = false
        engine.mediaItemCount = 1

        assertThat(engine.evaluate(isPlaying = true)).isFalse()
        assertThat(engine.rebuilds).isEqualTo(0)
        assertThat(engine.desiredBackend).isEqualTo(AudioBackend.ANDROID_AUDIO)

        // Pausing is what opens the gate, and then the claim is worth exactly one attempt.
        assertThat(engine.evaluate(isPlaying = false)).isTrue()
        assertThat(engine.rebuilds).isEqualTo(1)
    }

    /**
     * Android steady state, with and without a source, is never a reason to build a player — and a
     * claimable source must not talk the router into Direct either. No DAC, so Android is the
     * answer for its own reasons.
     */
    @Test
    fun `an Android steady state rebuilds nothing whether or not a source is loaded`() {
        val noDac = directEligibleHardware.copy(usbDacPresent = false)
        for (items in listOf(0, 1, 50)) {
            val engine = EngineSimulation()
            engine.mediaItemCount = items
            repeat(10) { assertThat(engine.evaluate(isPlaying = false, base = noDac)).isFalse() }
            assertThat(engine.rebuilds).isEqualTo(0)
            assertThat(engine.desiredBackend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        }
    }

    /**
     * Direct → Android is a backend change, and the exclusive sink must be released exactly once.
     * Here the user turns on an Android-session effect, which only the Android path can serve.
     */
    @Test
    fun `leaving an active Direct stream for Android rebuilds once and settles`() {
        val engine = EngineSimulation()
        engine.desiredBackend = AudioBackend.USB_DIRECT
        engine.builtWithDirectAccess = true
        engine.directClaimAttempted = true
        engine.directIsActive = true
        engine.mediaItemCount = 1

        val effectRequiresAndroid = directEligibleHardware.copy(
            processing = AudioProcessingRequirements(androidSessionEffect = true)
        )

        assertThat(engine.evaluate(isPlaying = false, base = effectRequiresAndroid)).isTrue()
        assertThat(engine.rebuilds).isEqualTo(1)
        assertThat(engine.builtWithDirectAccess).isFalse()
        assertThat(engine.directIsActive).isFalse()

        repeat(20) { assertThat(engine.evaluate(isPlaying = false, base = effectRequiresAndroid)).isFalse() }
        assertThat(engine.rebuilds).isEqualTo(1)

        // Releasing the effect is a backend change back the other way: one more rebuild, then
        // Direct — and Direct again means the exclusive sink is re-claimed exactly once.
        assertThat(engine.evaluate(isPlaying = false)).isTrue()
        assertThat(engine.rebuilds).isEqualTo(2)
        assertThat(engine.builtWithDirectAccess).isTrue()
        assertThat(engine.directIsActive).isTrue()
        repeat(20) { assertThat(engine.evaluate(isPlaying = false)).isFalse() }
        assertThat(engine.rebuilds).isEqualTo(2)
    }

    // ------------------------------------------------- CASE C2 — processed Direct

    /**
     * Enabling the EQ on a playing Direct stream must not rebuild the player.
     *
     * This is the difference the dual-path model buys. Under the previous model the EQ pinned the
     * route to Android, so this gesture was a backend change — a new player, a released and
     * re-claimed exclusive USB session, a renegotiated DAC clock, and an audible interruption, all
     * to engage a filter bank. Now the backend does not move: the sink switches which component
     * produces PCM and the transport keeps its claim.
     *
     * The loop is longer than the convergence tests above on purpose. The failure this guards
     * against is the old architecture's roughly-eleven-players-per-second oscillation, and an
     * oscillation that only showed after a few evaluations would pass a three-iteration check.
     */
    @Test
    fun `enabling the EQ on an active Direct stream does not rebuild`() {
        val engine = EngineSimulation()
        engine.desiredBackend = AudioBackend.USB_DIRECT
        engine.builtWithDirectAccess = true
        engine.directClaimAttempted = true
        engine.directIsActive = true
        engine.mediaItemCount = 1

        val processed = directEligibleHardware.copy(
            processing = AudioProcessingRequirements(graphicEq = true)
        )

        repeat(50) { assertThat(engine.evaluate(isPlaying = false, base = processed)).isFalse() }
        assertThat(engine.rebuilds).isEqualTo(0)
        assertThat(engine.desiredBackend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(engine.directIsActive).isTrue()

        // And back: clearing the requirement must land on pristine Direct, still without a rebuild.
        repeat(50) { assertThat(engine.evaluate(isPlaying = false, base = directEligibleHardware)).isFalse() }
        assertThat(engine.rebuilds).isEqualTo(0)
        assertThat(engine.desiredBackend).isEqualTo(AudioBackend.USB_DIRECT)
    }

    /**
     * Toggling every carried requirement repeatedly is still a fixed point.
     *
     * The stability gate asks for repeated toggles with no oscillation; this is that, at the level
     * the decision is actually made.
     */
    @Test
    fun `repeatedly toggling the EQ and ReplayGain never rebuilds a Direct stream`() {
        val engine = EngineSimulation()
        engine.desiredBackend = AudioBackend.USB_DIRECT
        engine.builtWithDirectAccess = true
        engine.directClaimAttempted = true
        engine.directIsActive = true
        engine.mediaItemCount = 1

        val on = directEligibleHardware.copy(
            processing = AudioProcessingRequirements(graphicEq = true, replayGain = true)
        )

        repeat(10) {
            assertThat(engine.evaluate(isPlaying = false, base = on)).isFalse()
            assertThat(engine.evaluate(isPlaying = false, base = directEligibleHardware)).isFalse()
        }
        assertThat(engine.rebuilds).isEqualTo(0)
        assertThat(engine.directIsActive).isTrue()
    }

    // ---------------------------------------------------------------- RULE B

    @Test
    fun `audible Android playback is not interrupted to obtain Direct`() {
        // Rule B: Direct becoming eligible mid-playback must not glitch the audio that is already
        // coming out of Android.
        val whilePlaying = turn(
            facts = factsWith(isPlaying = true, directIsActive = false),
            previousDesiredBackend = AudioBackend.ANDROID_AUDIO,
            playerBuiltWithDirectAccess = false,
            directIsActive = false,
        )

        assertThat(whilePlaying.decision).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(whilePlaying.reason).isEqualTo(AudioRouteReason.SAFETY_TRANSITION_DEFERRED)
    }

    @Test
    fun `the same hardware is granted Direct once the audio stops`() {
        // The deferral above is a deferral, not a refusal: nothing about the hardware changed.
        val afterStopping = turn(
            facts = factsWith(isPlaying = false, directIsActive = false),
            previousDesiredBackend = AudioBackend.ANDROID_AUDIO,
            playerBuiltWithDirectAccess = false,
            directIsActive = false,
        )

        assertThat(afterStopping.decision).isEqualTo(AudioBackend.USB_DIRECT)
    }

    // ------------------------------------------------- CASE C — processing

    @Test
    fun `processing choices split by whether the Direct path can carry them`() {
        // Two lists rather than one, because the answer is no longer a single "does it block".
        // The carried list is the one that used to force Android and no longer does; the blocked
        // list is what genuinely still has no Direct implementation in this pass.
        val carriedOnDirect = listOf(
            AudioProcessingRequirements(graphicEq = true),
            AudioProcessingRequirements(replayGain = true),
            AudioProcessingRequirements(digitalVolume = true),
        )
        val blocked = listOf(
            AudioProcessingRequirements(nonDefaultSpeed = true) to AudioRouteReason.SPEED_REQUIRES_ANDROID,
            AudioProcessingRequirements(crossfade = true) to AudioRouteReason.CROSSFADE_REQUIRES_ANDROID,
            AudioProcessingRequirements(androidSessionEffect = true) to AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID,
        )

        for (processing in carriedOnDirect) {
            val decision = AudioRoutePolicy.decide(
                factsWith(isPlaying = false, directIsActive = false)
                    .copy(processing = processing)
            )
            assertThat(decision.backend).isEqualTo(AudioBackend.USB_DIRECT)
            assertThat(decision.reason).isEqualTo(AudioRouteReason.DIRECT_PROCESSED)
        }

        for ((processing, expected) in blocked) {
            val decision = AudioRoutePolicy.decide(
                factsWith(isPlaying = false, directIsActive = false)
                    .copy(processing = processing)
            )
            assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
            assertThat(decision.reason).isEqualTo(expected)
        }
    }

    // -------------------------------------------- CASES D/E/F — capability gates

    @Test
    fun `a missing DAC, a refused permission and an unsupported source each keep Android`() {
        val cases = listOf(
            directEligibleHardware.copy(usbDacPresent = false) to AudioRouteReason.NO_USB_DAC,
            directEligibleHardware.copy(usbPermissionGranted = false) to AudioRouteReason.USB_PERMISSION_REQUIRED,
            directEligibleHardware.copy(sourceDirectCompatible = false) to AudioRouteReason.SOURCE_NOT_DIRECT_COMPATIBLE,
        )

        for ((facts, expected) in cases) {
            val decision = AudioRoutePolicy.decide(
                facts.copy(
                    directTransitionAllowed = isDirectTransitionAllowed(
                        masterInitialised = true,
                        directIsActive = false,
                        isPlaying = false,
                    )
                )
            )
            assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
            assertThat(decision.reason).isEqualTo(expected)
        }
    }

    // ------------------------------------------------- the predicate itself

    @Test
    fun `the transition permission does not depend on a play intent`() {
        // The exact regression. Both of these describe "a play intent exists, Direct is eligible,
        // nothing is audible yet" — and the permission must be identical, because the play intent
        // is not what the rule is about.
        val noIntent = isDirectTransitionAllowed(
            masterInitialised = true, directIsActive = false, isPlaying = false,
        )
        assertThat(noIntent).isTrue()

        // And it must not require Direct to already exist: that was the circularity.
        assertThat(
            isDirectTransitionAllowed(masterInitialised = true, directIsActive = false, isPlaying = false)
        ).isTrue()
    }

    @Test
    fun `an uninitialised master always permits the transition`() {
        assertThat(
            isDirectTransitionAllowed(masterInitialised = false, directIsActive = false, isPlaying = false)
        ).isTrue()
    }

    // ------------------------------- the rebuild's own re-entrant diagnostic refresh

    /**
     * The ordering defect this suite was structurally blind to.
     *
     * `rebuildPlayersPreservingMasterState` calls `refreshUsbAudioDiagnostics` *before* it restores
     * the queue and *before* it writes `recordDirectCapability`. That refresh ends by asking for a
     * route reevaluation, so the pre-fix engine re-entered the decision loop against a half-built
     * engine: no queue, and a capability latch still describing the player it had just released.
     * `needsCapabilityRebuild` was true again on the way in, and true forever after.
     *
     * Measured on the HONOR LGN-NX3: 40 rebuilds in 1.611 s (about 25 `ExoPlayer` constructions per
     * second), every one reporting `builtWithDirectAccess=false` and `mediaItems=0`, until the
     * system killed the process.
     *
     * Every assertion below is about the *ordering*, which is why the simulation now models the
     * rebuild step by step rather than applying it atomically.
     */
    @Test
    fun `the rebuild's own diagnostic refresh does not re-enter the rebuild`() {
        val engine = EngineSimulation()
        engine.mediaItemCount = 3

        // DAC attaches while paused, against a player built Android-only — the device scenario.
        val rebuilt = engine.evaluate(isPlaying = false)

        assertThat(rebuilt).isTrue()
        assertThat(engine.rebuilds).isEqualTo(1)
    }

    @Test
    fun `the queue survives the rebuild that the refresh used to interrupt`() {
        val engine = EngineSimulation()
        engine.mediaItemCount = 3
        engine.evaluate(isPlaying = false)

        // Pre-fix this read 0 and stayed 0: the restore sits after the re-entry, so the recursion
        // destroyed the very queue it was rebuilding to fill.
        assertThat(engine.mediaItemCount).isEqualTo(3)
    }

    @Test
    fun `capability and claim are recorded once the rebuild is whole`() {
        val engine = EngineSimulation()
        engine.mediaItemCount = 3
        engine.evaluate(isPlaying = false)

        assertThat(engine.builtWithDirectAccess).isTrue()
        assertThat(engine.directClaimAttempted).isTrue()
    }

    @Test
    fun `the deferred reevaluation settles instead of rebuilding again`() {
        val engine = EngineSimulation()
        engine.mediaItemCount = 3
        engine.evaluate(isPlaying = false)
        val afterFirst = engine.rebuilds

        // The engine replays exactly one reevaluation once it is whole. Those facts are a fixed
        // point, so twenty further turns must add nothing.
        repeat(20) { engine.evaluate(isPlaying = false) }

        assertThat(engine.rebuilds).isEqualTo(afterFirst)
        assertThat(engine.mediaItemCount).isEqualTo(3)
    }

    @Test
    fun `a rebuild against an empty queue also refuses to re-enter`() {
        val engine = EngineSimulation()
        engine.mediaItemCount = 0

        engine.evaluate(isPlaying = false)

        // Capability is established, the claim is still owed for when a source arrives, and
        // nothing stormed — the empty queue is no longer self-sustaining.
        assertThat(engine.rebuilds).isEqualTo(1)
        assertThat(engine.builtWithDirectAccess).isTrue()
        assertThat(engine.directClaimAttempted).isFalse()
    }

    @Test
    fun `without the guard the identical ordering storms, so these tests are load-bearing`() {
        val engine = EngineSimulation(honoursRebuildCycle = false)
        engine.mediaItemCount = 3

        // With the pre-fix engine the same scenario runs away and trips the step limit rather than
        // converging. If this test ever stops throwing, the assertions above have gone vacuous.
        val failure = assertThrows<IllegalStateException> { engine.evaluate(isPlaying = false) }

        assertThat(failure).hasMessageThat().contains("re-entering itself")
        assertThat(engine.rebuilds).isGreaterThan(1)
    }
}
