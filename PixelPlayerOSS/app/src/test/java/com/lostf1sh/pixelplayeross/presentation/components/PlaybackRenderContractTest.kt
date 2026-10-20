package com.lostf1sh.pixelplayeross.presentation.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The contracts that keep playback from redrawing the app at display refresh rate.
 *
 * Measured on the device before these held: an over-long title in the mini player alone held the
 * whole app at ~61 fps for the entire duration of playback, on every root surface. With them,
 * those surfaces render 0 frames while audio plays.
 *
 * Source assertions, as elsewhere in this suite: the claim is "this composable is not driven by a
 * per-frame loop", which a JVM test cannot ask of a running composition but which is decided
 * entirely by what the source says.
 */
class PlaybackRenderContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app", relative),
            File("src/main/java/com/lostf1sh/pixelplayeross/$relative"),
            File("app/src/main/java/com/lostf1sh/pixelplayeross/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    private val marquee by lazy { source("presentation/components/MarqueeText.kt") }
    private val miniPlayer by lazy { source("presentation/components/UnifiedPlayerSheetShared.kt") }
    private val miniPlayerHost by lazy { source("presentation/components/UnifiedPlayerSheetLayers.kt") }
    private val eqIcon by lazy { source("presentation/components/subcomps/PlayingEqIcon.kt") }
    private val slider by lazy { source("presentation/components/WavySliderExpressive.kt") }

    // --- marquee ---------------------------------------------------------------------------------

    @Test
    fun `the marquee is bounded rather than infinite`() {
        assertThat(marquee).contains("iterations = MARQUEE_ITERATIONS")
        assertThat(marquee).doesNotContain("iterations = Int.MAX_VALUE")
        // Guard against a vacuous pass: a finite count has to actually be small.
        val n = Regex("MARQUEE_ITERATIONS = (\\d+)").find(marquee)?.groupValues?.get(1)?.toInt()
        assertThat(n).isNotNull()
        assertThat(n!!).isAtMost(10)
    }

    @Test
    fun `the mini player does not opt into the scrolling marquee`() {
        // The mini player is visible on every root surface for the whole of playback, so a marquee
        // here is not a local animation -- it is the app's baseline frame rate.
        assertThat(miniPlayer).doesNotContain("AutoScrollingText(")
        assertThat(miniPlayer).doesNotContain("basicMarquee(")
        // Bounded, not scrolled: the title is cut at a fixed number of lines with an ellipsis. How many lines is a
        // layout decision (two in the stacked layout, one in the single-row layout) and not the contract, which
        // is that no title can ask for more time on the frame clock than a static one.
        val bounded = Regex("""maxLines = [12],\s*overflow = TextOverflow\.Ellipsis""")
        assertThat(bounded.containsMatchIn(miniPlayer)).isTrue()
        assertThat(miniPlayer).doesNotContain("maxLines = Int.MAX_VALUE")
        assertThat(miniPlayerHost).doesNotContain("canScroll =")
    }

    @Test
    fun `the marquee still only runs when the text actually overflows`() {
        assertThat(marquee).contains("val isOverflowing = textPlaceable.width > constraints.maxWidth")
        assertThat(marquee).contains("if (isOverflowing && canScroll)")
    }

    // --- playing indicator -----------------------------------------------------------------------

    @Test
    fun `the equaliser bars are sampled, not animated per frame`() {
        assertThat(eqIcon).contains("EQ_SAMPLE_INTERVAL_MS")
        assertThat(eqIcon).contains("delay(EQ_SAMPLE_INTERVAL_MS)")
        // The per-frame drivers are what held lists at 60 fps.
        assertThat(eqIcon).doesNotContain("animateTo(")
        assertThat(eqIcon).doesNotContain("withFrameNanos")
    }

    @Test
    fun `the equaliser sampling stays slow enough to be worth it`() {
        val n = Regex("EQ_SAMPLE_INTERVAL_MS = (\\d+)L").find(eqIcon)?.groupValues?.get(1)?.toLong()
        assertThat(n).isNotNull()
        // At least ~8 Hz of separation: fast enough to look like motion on curves whose periods
        // are measured in seconds, slow enough to actually save the frames.
        assertThat(n!!).isAtLeast(100L)
    }

    @Test
    fun `the equaliser still stops when playback is not running`() {
        assertThat(eqIcon).contains("if (!isPlaying) return@LaunchedEffect")
    }

    // --- progress --------------------------------------------------------------------------------

    @Test
    fun `the seek bar does not interpolate per frame during ordinary playback`() {
        // B11-1: the only frame-clock use is the wave-scroll gate (a bounded window that ends on a wave
        // cycle boundary), which interpolates nothing; the bar's own value is drawn directly.
        val body = slider.lines().filter { !it.startsWith("import ") }.joinToString("\n")
        assertThat(body.substringBefore("private fun rememberWaveScrolling(")).doesNotContain("withFrameNanos")
        // The direct-manipulation paths must survive: the finger's own value while dragging.
        assertThat(slider).contains("if (!enabled || isInteracting)")
        assertThat(slider).contains("renderedNormalizedProgress.floatValue = target")
    }
}
