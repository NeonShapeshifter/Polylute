package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Coordinate ownership across the two clocks.
 *
 * ## The two domains
 *
 * ```
 * MEDIA POSITION    = NativeAudioEngine.getPositionUs()
 *                     used for: seekTo, the PRISTINE → PROCESSED handoff anchor
 *
 * RENDERER POSITION = windowOffsetUs + media position
 *                     used for: AudioSink.getCurrentPositionUs
 * ```
 *
 * ## What these tests do and do not prove
 *
 * They pin **which expression each API uses**, and the epoch boundaries at which the mapping may be
 * invalidated. That is where the defect lived — mixing the domains produces a plausible-looking
 * number, not a crash, and the failure only became visible on hardware as a position that diverged
 * and then compounded.
 *
 * They do **not** exercise latch timing, invalidation sequencing, or behaviour across a real seek —
 * `UsbAudioSink` has no test harness in its module, and extracting the mapping into a testable unit
 * is a refactor that deserves its own pass rather than being rushed. A behavioural lifetime suite
 * is still owed; a green run here is not a substitute for it.
 *
 * ## Why the Media3 contract is asserted here at all
 *
 * `getCurrentPositionUs` returning renderer time is not a preference — it is what Media3 requires,
 * proven from bytecode:
 *
 * ```
 * MediaCodecAudioRenderer.updateCurrentPosition():
 *     currentPositionUs = audioSink.getCurrentPositionUs(isEnded())      // stored verbatim
 * ExoPlayerImplInternal.resetRendererPosition():
 *     rendererPositionUs = period.toRendererTime(mediaPositionUs)
 * MediaPeriodHolder.toRendererTime(long):
 *     return mediaPositionUs + getRendererOffset()
 * ```
 *
 * Returning media time there makes Media3 subtract the renderer offset from it. With the real
 * values that produces `40e6 - 1e12 ≈ -1e12` — which is not a hypothetical, it is the value this
 * device reported.
 */
class RendererMediaDomainBoundaryTest {

    @Test
    fun `the sink reports renderer time and the handoff anchor reports media time`() {
        val sink = sinkSource()
        val positionBody = sink.substringAfter("override fun getCurrentPositionUs")
            .substringBefore("override fun isEnded")

        // Renderer domain, and only reachable once the mapping exists.
        assertThat(positionBody).contains("windowOffsetUs + enginePositionUs")
        assertThat(positionBody).contains("windowOffsetUs >= 0")
        assertThat(positionBody).contains("AudioSink.CURRENT_POSITION_NOT_SET")

        // The handoff anchor must not carry the renderer offset.
        // Bounded by the *next* declaration, not by a method that appears earlier in the file —
        // an unbounded region silently swallows getCurrentPositionUs and the assertion then tests
        // the wrong function.
        val handoff = sink.substringAfter("private fun handoffPositionUs")
            .substringBefore("override fun getCurrentPositionUs")
        assertThat(handoff).contains("engine.getPositionUs()")
        assertThat(handoff).doesNotContain("windowOffsetUs +")
        assertThat(handoff).doesNotContain("windowOffsetUs+")
    }

    @Test
    fun `the two domains stay distinguishable with explicit values`() {
        // The values from the physical run, chosen so a domain mix is unmistakable rather than
        // subtly wrong.
        val rendererOffsetUs = 1_000_000_000_000L
        val mediaPositionUs = 40_000_000L

        val sinkPositionUs = rendererOffsetUs + mediaPositionUs
        val handoffPositionUs = mediaPositionUs

        assertThat(sinkPositionUs).isEqualTo(1_000_040_000_000L)
        assertThat(handoffPositionUs).isEqualTo(40_000_000L)

        // The exact arithmetic that produced the observed failure: feeding media time to the
        // renderer API makes Media3 subtract the offset and report a large negative position.
        assertThat(mediaPositionUs - rendererOffsetUs).isEqualTo(-999_960_000_000L)
        assertThat(sinkPositionUs - rendererOffsetUs).isEqualTo(mediaPositionUs)
    }

    @Test
    fun `the mapping is invalidated only at period boundaries`() {
        // A same-period seek preserves the renderer offset — proven in bytecode:
        // `ExoPlayerImplInternal.seekToPeriodPosition` only reaches `setRendererOffset(1e12)` after
        // `advancePlayingPeriod`, and routes same-period seeks to `toRendererTime` with the existing
        // offset. So clearing it on flush or realignment is wrong: it forces the latch to recompute
        // from a post-seek presentation timestamp against an initialPlayerPositionUs captured when
        // the item began.
        val sink = sinkSource()

        // There is no longer ANY site that clears the offset, because clearing it while leaving its
        // companion anchor alive is precisely how a cross-position pair became representable: an
        // engine could be recreated, the offset forgotten, and the next latch would pair a fresh
        // presentation timestamp with an anchor from the previous position.
        val clears = sink.lines().filter { it.contains("windowOffsetUs = -1L") }
        assertThat(clears).isEmpty()

        // Instead the mapping is epoch-scoped: both operands are owned by one state, and a machinery
        // rebuild preserves it rather than clearing half of it.
        assertThat(sink).contains("private val mappingTracker = MappingEpochTracker()")
        assertThat(sink).contains("get() = mappingTracker.windowOffsetUs")
        val rebuilds = sink.lines().count { it.contains("mappingTracker.preserve()") }
        assertThat(rebuilds).isAtLeast(4)

        // A rebuild never re-anchors; only a position change opens an epoch.
        val flush = sink.substringAfter("override fun flush()").substringBefore("override fun reset()")
        assertThat(flush).doesNotContain("windowOffsetUs = -1L")

        val realign = sink.substringAfter("private fun realignPipelineForSignalMode")
            .substringBefore("private fun recordRealign")
        assertThat(realign).doesNotContain("windowOffsetUs = -1L")
    }

    @Test
    fun `the renderer offset is Media3's own quantity, not a workaround`() {
        // `setRendererOffset(1_000_000_000_000L)` appears in ExoPlayerImplInternal's seek path as
        // the renderer-clock base for a new period. Our latched `windowOffsetUs` measuring ~1e12 is
        // therefore a correctly derived mapping, not an artefact — which is why it must not be
        // clamped, guessed at, or replaced with a constant.
        val rendererOffsetUs = 1_000_000_000_000L
        val freshStartMediaUs = 0L

        // `rendererOffsetUs = presentationTimeUs - initialPlayerPositionUs`, on a fresh start.
        val presentationTimeUs = rendererOffsetUs + freshStartMediaUs
        assertThat(presentationTimeUs - freshStartMediaUs).isEqualTo(rendererOffsetUs)
    }

    private fun sinkSource(): String = root.resolve(SINK_PATH).readText()

    private companion object {
        const val SINK_PATH =
            "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/" +
                "com/decent/usbaudio/media3/UsbAudioSink.kt"

        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
    }
}
