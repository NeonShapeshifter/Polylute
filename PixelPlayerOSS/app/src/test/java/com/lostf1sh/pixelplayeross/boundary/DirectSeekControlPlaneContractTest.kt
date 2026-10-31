package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The user's seek target must reach the native producer, and the producer must actually move.
 *
 * ## What went wrong
 *
 * `PlaybackStateHolder.seekTo` held the exact requested media target and passed only a *hint* that a
 * seek had happened. The Direct path then had to reconstruct the target, and every source it could
 * reconstruct from was either the wrong domain or delivered by a callback PRISTINE is allowed to
 * starve:
 *
 *  - `flush()` said "Native engine handles its own flush/seek internally" and never seeked it,
 *    deferring to `handleBuffer` — which `NativeEngineAwareLoadControl` may never call again,
 *    because while the engine owns the track ExoPlayer is deliberately not loading PCM.
 *  - The engine's eventual target was `presentationTimeUs - windowOffsetUs`, a renderer-domain
 *    reconstruction rather than the value the user asked for.
 *
 * Measured on the HONOR: `enginePosUs` advanced 240264000 → 240744000 straight through a
 * `seekTo(0)`, while the player reported 0 and then converged back onto the engine. The seek was
 * accepted by Media3 and never performed by the engine.
 *
 * A second face of the same hole: `onMediaItemTransition` read the start position from
 * `attachedPlayer.currentPosition`, which at that instant can still be the *outgoing* item's
 * position. Starting the track produced `initialPlayerPositionUs = 356904 ms` — the previous EOF —
 * so the new engine was seeked to the end of the file and playback finished in 20 ms.
 *
 * ## What is and is not covered here
 *
 * These are source assertions, like the rest of this suite: they pin *where the target comes from*,
 * *that the seek is pushed rather than reconstructed*, and the ordering that makes the barrier and
 * the completion protocol mean anything. They do not execute the decoder or the USB stack — the
 * generation protocol lives in C++ with no JVM harness, and the sink's module still has no test
 * source set. A green run here is a regression guard; the physical run is the behavioural proof.
 */
class DirectSeekControlPlaneContractTest {

    private fun appSource(relative: String): String = read(root("app"), relative)

    private fun read(base: File, relative: String): String {
        val f = File(base, relative)
        if (!f.isFile) error("Could not locate $relative under ${base.absolutePath}")
        return f.readText()
    }

    /** Walks up to the PixelPlayerOSS module root. */
    private val ppossRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find the project root from ${File(".").absolutePath}")
    }

    private fun root(@Suppress("UNUSED_PARAMETER") module: String): File =
        File(ppossRoot, "app")

    private val siblingRoot: File by lazy { File(ppossRoot.parentFile, "decent-player") }

    private val sinkSource: String by lazy {
        read(
            siblingRoot,
            "libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt",
        )
    }

    private val usbOutputSource: String by lazy {
        read(
            siblingRoot,
            "libs/decent-usb-audio-driver/src/main/jni/usb-audio-output.cpp",
        )
    }

    // ── Target authority ────────────────────────────────────────────────

    @Test
    fun `the requested target is handed over, not reduced to a hint`() {
        val holder = appSource("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlaybackStateHolder.kt")
        val seekBody = holder.substringAfter("fun seekTo(position: Long)")
            .substringBefore("fun previousSong")

        // The sanitised media target itself crosses the boundary.
        assertThat(seekBody).contains("notifyExternalSeekInitiated(targetPosition)")

        // The regression: a no-argument hint that left the target behind for someone to re-derive.
        assertThat(seekBody).doesNotContain("notifyExternalSeekInitiated()")
    }

    @Test
    fun `the host pushes the target to the sink in microseconds of media time`() {
        val engine = appSource("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt")
        val body = engine.substringAfter("fun notifyExternalSeekInitiated(targetPositionMs: Long)")
            .substringBefore("fun removeTransitionFinishedListener")

        assertThat(body).contains("requestMediaSeek(targetPositionMs * 1_000L)")
        // Pushed directly, so the seek survives ExoPlayer never delivering PCM.
        assertThat(body).contains("playerUsbSinks[playerA]")
    }

    // ── The seek hook Media3 actually calls ─────────────────────────────

    @Test
    fun `flush no longer claims the engine handles its own seek`() {
        val flush = sinkSource.substringAfter("override fun flush()")
            .substringBefore("override fun reset()")

        // The false premise that hid the hole.
        assertThat(flush).doesNotContain("handles its own flush/seek internally")
        // A user seek is never re-derived from the renderer buffer timestamp here.
        assertThat(flush).doesNotContain("engine.seek(")
    }

    // ── Latest-wins / completion ────────────────────────────────────────

    // ── Ordering: barrier, decoder, then audio ──────────────────────────

    /** Bounded to the function body — an unbounded slice swallows later callers and tests them. */
    private fun barrierBody(): String =
        usbOutputSource.substringAfter("void discardQueuedPcm(UsbAudioContext *ctx)")
            .substringBefore("// ── JNI entry points")

    @Test
    fun `the barrier discards instead of playing the queue out`() {
        val barrier = barrierBody()

        // Straight to discard — a natural reap of an in-flight audio URB means that millisecond
        // of pre-seek PCM just reached the DAC.
        assertThat(barrier).contains("drainAllUrbs(ctx, true, &tally)")
        // The partial frame and packetisation phase belonged to the old position.
        assertThat(barrier).contains("ctx->residualBytes = 0;")
        assertThat(barrier).contains("ctx->frameAccumulator = 0.0;")
    }

    @Test
    fun `the barrier keeps the stream, the claim and the route`() {
        val barrier = barrierBody()

        // A seek must not cost the DAC: no stop, no release, no re-claim.
        assertThat(barrier).doesNotContain("running.store(false)")
        assertThat(barrier).doesNotContain("freeRing")
        // The feedback URB is the only thing keeping the DAC's real clock tracked, and the drain
        // cancels it too.
        assertThat(barrier).contains("submitFeedbackUrb(ctx)")
    }

    // ── Item start ──────────────────────────────────────────────────────

    @Test
    fun `an item start never samples the outgoing item's position`() {
        val transition = sinkSource.substringAfter("override fun onMediaItemTransition")
            .substringBefore("private fun resolveTrackPath")

        // The stale read that put a fresh engine at the previous item's EOF.
        assertThat(transition).doesNotContain("attachedPlayer?.currentPosition")
        assertThat(transition).doesNotContain("initialPlayerPositionUs = (attachedPlayer")
    }

    @Test
    fun `an explicit start target is declared, and defaults to start-fresh`() {
        assertThat(sinkSource).contains("fun requestStartTargetUs(targetUs: Long)")
        // There is deliberately no independently-mutable operand left: it is derived from the one
        // piece of epoch state, so it cannot drift away from the offset computed from it.
        assertThat(sinkSource).doesNotContain("private var initialPlayerPositionUs")
        assertThat(sinkSource).contains("get() = mappingTracker.anchorMediaPositionUs")
    }

    // ── One authority for the item-start position ───────────────────────
    //
    // The measured failure: Media3 was resumed at 149s via setMediaItems(..., positionMs) while the
    // native engine was independently started at its 0 default. initialPlayerPositionUs was 0, the
    // first presentationTimeUs was 1e12 + 149s, and the latch baked 149s into windowOffsetUs — the
    // player then read 149s ahead of the native engine for the life of the item. The latch formula
    // was right; its inputs described two different intentions.

    @Test
    fun `a resume declares its position to both sides`() {
        val engine = appSource("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt")
        val reassemble = engine.substringAfter("private fun reassembleMasterPlayer")
            .substringBefore("private fun buildPlayer")

        // The declaration sits with the Media3 call, carrying the same value.
        val declareAt = reassemble.indexOf("declareItemStartTarget(positionMs)")
        val setItemsAt = reassemble.indexOf("playerA.setMediaItems(mediaItems, currentIndex, positionMs)")
        assertThat(declareAt).isGreaterThan(-1)
        assertThat(setItemsAt).isGreaterThan(-1)
        assertThat(declareAt).isLessThan(setItemsAt)
    }

    @Test
    fun `turning shuffle on or off never restarts the item, so there is no start to declare`() {
        // B17-H1. This test used to pin `replacePlayerQueue`, the physical shuffle: a `setMediaItems(items, index, position)`
        // that restarted the playing item at a position and so had to declare it. Shuffle is now Media3's shuffle mode, a
        // flag; the item that is playing is not replaced, restarted or re-prepared, so there is nothing to declare. What
        // would break that is any `setMediaItems` / `replaceMediaItem(s)` of the playing item on the toggle's path.
        val holder = appSource("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlaybackStateHolder.kt")
        assertThat(holder).doesNotContain("replacePlayerQueue")
        assertThat(holder).doesNotContain("setMediaItems(")
        assertThat(holder).doesNotContain("replaceMediaItems(")

        val engine = appSource("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt")
        val setShuffle = engine.substringAfter("fun setShuffleEnabled(").substringBefore("fun setPlayOrder(")
        assertThat(setShuffle).doesNotContain("setMediaItems(")
        assertThat(setShuffle).contains("player.shuffleModeEnabled = enabled")

        // The one place that does replace items around the playing one (widening a crossfade window) keeps the playing item.
        val unwindow = engine.substringAfter("private fun unwindowQueue()").substringBefore("private var isReleased")
        assertThat(unwindow).doesNotContain("setMediaItems(")
        assertThat(unwindow).contains("player.replaceMediaItems(0, windowIndex, before)")
        assertThat(unwindow).contains("base[baseIndex].mediaId != playing.mediaId")
    }

    @Test
    fun `an explicit track selection declares start-fresh explicitly`() {
        val vm = appSource("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlayerViewModel.kt")
        val play = vm.substringAfter("private fun playLoadedControllerItem(")
            .substringBefore("private fun Song.requiresHydration")

        // Declared rather than left to omission, so a standing resume declaration cannot leak in.
        assertThat(play).contains("declareItemStartTarget(0L)")
        assertThat(play).contains("controller.seekTo(targetIndex, 0L)")
    }

    @Test
    fun `the declaration expires after one item start`() {
        val transition = sinkSource.substringAfter("override fun onMediaItemTransition")
            .substringBefore("private fun resolveTrackPath")

        // Consumed, so an auto-advanced item cannot inherit the previous item's resume position.
        // The declaration now opens the item's mapping epoch, which is what makes the previous
        // item's anchor unreachable from this one: the epoch owns both operands that the latch uses.
        assertThat(transition).contains("val declaredStartUs = pendingStartTargetUs")
        assertThat(transition).contains("openEpoch(")
        assertThat(transition).contains("anchorUs = declaredStartUs")
        assertThat(transition).contains("pendingStartTargetUs = 0L")
    }

    // ── Seek failure is not success ─────────────────────────────────────

    // ── Control-plane ownership ─────────────────────────────────────────
    //
    // These pin the fix for the measured failure: an explicit item-start seek of 0 (gen=3) was
    // superseded by a realign that inferred 356893ms from a contaminated player position and
    // issued gen=4, seeking the engine to EOF. Generation ordering alone could not prevent that,
    // because every realign can mint a newer generation — so authority, not recency, decides.

    @Test
    fun `a renderer-domain candidate is refused as a media anchor`() {
        val handoff = sinkSource.substringAfter("private fun handoffPositionUs()")
            .substringBefore("override fun getCurrentPositionUs")

        // The measured contaminant: Player.currentPosition x 1000 = 1e12 + 336ms reached a realign.
        // The host's position is no longer a source at all (AUD-139), so there is no host branch to
        // guard; every remaining candidate is refused when it is in the renderer domain.
        assertThat(handoff).doesNotContain("fromHost")
        assertThat(handoff).contains("return -1L")
        // The engine's own counter is refused on the same terms — nativeSeek stores the requested
        // target in framesDecoded verbatim, so a bad target poisons getPositionUs() too.
        assertThat(handoff).contains("if (pipelinePositionUs >= RENDERER_DOMAIN_FLOOR_US)")
        // Refused, not translated. Checked as arithmetic on the candidate rather than a bare
        // mention of the constant, because the surrounding comment legitimately names it when
        // explaining why windowOffsetUs is not added.
        assertThat(handoff).doesNotContain("RENDERER_DOMAIN_FLOOR_US -")
        assertThat(handoff).doesNotContain("RENDERER_DOMAIN_FLOOR_US +")
        assertThat(handoff).doesNotContain("coerceAtMost")
    }

    // ── Renderer mapping ────────────────────────────────────────────────

    @Test
    fun `a user seek does not invalidate the same-period renderer mapping`() {
        val flush = sinkSource.substringAfter("override fun flush()")
            .substringBefore("override fun reset()")

        // Same-period seek preserves rendererPositionOffsetUs; clearing it re-latched a mismatched
        // epoch and is what made the old position error compound.
        assertThat(flush).doesNotContain("windowOffsetUs = -1")
        assertThat(flush).doesNotContain("DirectPathTelemetry.latchWindowOffsetUs = ")
    }

    @Test
    fun `the seek target is media time and carries no renderer offset`() {
        val seek = sinkSource.substringAfter("fun requestMediaSeek(targetUs: Long)")
            .substringBefore("private val positionAudit")

        // No +/- 1e12, and no deriving the target from the renderer clock.
        assertThat(seek).doesNotContain("windowOffsetUs")
        assertThat(seek).doesNotContain("1_000_000_000_000")
        assertThat(seek).doesNotContain("presentationTimeUs")
    }
}
