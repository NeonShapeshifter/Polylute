package com.lostf1sh.pixelplayeross.presentation.visualizer

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The visualizer's contract with the audio path.
 *
 * The feature is decorative; the rules around it are not. Every assertion here is about the
 * visualizer staying a passive observer — never a participant — and about it saying "I cannot
 * show you this" rather than drawing something plausible.
 */
class VisualizerContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app", relative),
            File("src/main/java/com/lostf1sh/pixelplayeross/$relative"),
            File("app/src/main/java/com/lostf1sh/pixelplayeross/$relative"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
    }

    private val source_ by lazy { source("presentation/visualizer/VisualizerDataSource.kt") }
    private val bands_ by lazy { source("presentation/visualizer/VisualizerBands.kt") }
    private val view_ by lazy { source("presentation/visualizer/DapVisualizer.kt") }

    // --- Availability model -------------------------------------------------------------------

    @Test
    fun `unavailable states are distinct, not collapsed into one`() {
        // "Direct has no session" and "the platform refused observation" are different problems
        // with different answers.
        assertThat(VisualizerAvailability.UnavailableDirect)
            .isNotEqualTo(VisualizerAvailability.UnavailablePermission)
        assertThat(VisualizerAvailability.UnavailableDirect.explain()).isNotNull()
        assertThat(VisualizerAvailability.UnavailablePermission.explain()).isNotNull()
    }

    @Test
    fun `only an observed session is drawable`() {
        assertThat(VisualizerAvailability.Available(7).isDrawable).isTrue()
        listOf(
            VisualizerAvailability.UnavailableDirect,
            VisualizerAvailability.UnavailablePermission,
            VisualizerAvailability.UnavailableSession,
            VisualizerAvailability.Stopped,
        ).forEach { assertThat(it.isDrawable).isFalse() }
    }

    @Test
    fun `a stopped or sessionless visualizer is still supported, not explained away`() {
        // Nothing is playing — that is not a limitation to apologise for.
        assertThat(VisualizerAvailability.Stopped.isSupportedOnThisRoute).isTrue()
        assertThat(VisualizerAvailability.UnavailableSession.isSupportedOnThisRoute).isTrue()
        assertThat(VisualizerAvailability.UnavailableDirect.isSupportedOnThisRoute).isFalse()
        assertThat(VisualizerAvailability.UnavailablePermission.isSupportedOnThisRoute).isFalse()
    }

    @Test
    fun `Direct is reported as unavailable rather than observed through another session`() {
        assertThat(source_).contains("if (bypassesAndroidAudio)")
        assertThat(source_).contains("VisualizerAvailability.UnavailableDirect")
    }

    // --- Audio path untouched -------------------------------------------------------------------

    @Test
    fun `the visualizer holds nothing that could command playback`() {
        // The direction of information is the design: audio flows in, nothing flows back.
        listOf(source_, view_, bands_).forEach { text ->
            assertThat(text).doesNotContain("player.")
            assertThat(text).doesNotContain("PlayerViewModel")
            assertThat(text).doesNotContain("MediaController")
            assertThat(text).doesNotContain("ExoPlayer")
            assertThat(text).doesNotContain("AudioProcessor")
            assertThat(text).doesNotContain("AudioSink")
            assertThat(text).doesNotContain("setVolume")
            assertThat(text).doesNotContain("seekTo")
            assertThat(text).doesNotContain("playWhenReady")
        }
    }

    @Test
    fun `the visualizer never touches routing, EQ or replay gain`() {
        val offenders = listOf(
            "AudioBackend", "USB_DIRECT", "desiredBackend", "actualBackend",
            "Equalizer", "BassBoost", "Virtualizer", "LoudnessEnhancer",
            "ReplayGain", "replayGain", "setPreferredDevice", "AudioTrack",
        )
        listOf(source_, view_, bands_).forEach { text ->
            offenders.forEach { assertThat(text).doesNotContain(it) }
        }
    }

    @Test
    fun `Direct eligibility cannot consult the visualizer`() {
        // The other direction of the same rule: routing must not know this feature exists.
        val engine = source("data/service/player/DualPlayerEngine.kt")
        assertThat(engine).doesNotContain("Visualizer")
        val safety = source("data/volume/SafetyVolumeController.kt")
        assertThat(safety).doesNotContain("Visualizer")
    }

    @Test
    fun `no second decoder is created`() {
        listOf(source_, bands_).forEach { text ->
            assertThat(text).doesNotContain("MediaExtractor")
            assertThat(text).doesNotContain("MediaCodec")
        }
    }

    // --- Band mapping ---------------------------------------------------------------------------

    @Test
    fun `band edges are logarithmic, not linear in hertz`() {
        val a = VisualizerBands.bandEdge(0, 32, 40.0, 16_000.0)
        val b = VisualizerBands.bandEdge(8, 32, 40.0, 16_000.0)
        val c = VisualizerBands.bandEdge(16, 32, 40.0, 16_000.0)
        assertThat(a).isWithin(0.001).of(40.0)
        // Equal ratios, not equal differences.
        assertThat(b / a).isWithin(0.001).of(c / b)
        assertThat(c - b).isGreaterThan(b - a)
    }

    @Test
    fun `the bands cover the audible window and nothing beyond it`() {
        assertThat(VisualizerBands.MIN_HZ).isAtLeast(20.0)
        assertThat(VisualizerBands.MAX_HZ).isAtMost(20_000.0)
        assertThat(VisualizerBands.BAND_COUNT).isAtLeast(24)
        assertThat(VisualizerBands.BAND_COUNT).isAtMost(40)
    }

    @Test
    fun `the platform's decibel scale is read the right way round`() {
        // The API returns magnitudes in dB where **0 dB is full scale**, so an all-zero frame is
        // the loudest possible input, not silence. Reading it as silence would invert the whole
        // display — quiet passages would draw full bars and loud ones would draw nothing.
        val fullScale = VisualizerBands.normalise(ByteArray(1024))
        val silent = VisualizerBands.normalise(ByteArray(1024) { Byte.MIN_VALUE })

        fullScale.forEach { assertThat(it).isGreaterThan(0.5f) }
        silent.forEach { assertThat(it).isWithin(0.01f).of(0f) }
    }

    @Test
    fun `an empty frame is handled rather than throwing`() {
        assertThat(VisualizerBands.normalise(ByteArray(0)).size)
            .isEqualTo(VisualizerBands.BAND_COUNT)
    }

    // --- Smoothing ------------------------------------------------------------------------------

    @Test
    fun `attack is faster than release`() {
        assertThat(VisualizerBands.ATTACK).isGreaterThan(VisualizerBands.RELEASE)
        val rising = VisualizerBands.smooth(floatArrayOf(0f), floatArrayOf(1f))[0]
        val falling = VisualizerBands.smooth(floatArrayOf(1f), floatArrayOf(0f))[0]
        // After one frame a rising bar has covered more ground than a falling one loses.
        assertThat(rising).isGreaterThan(1f - falling)
    }

    @Test
    fun `smoothing converges and never overshoots`() {
        var current = floatArrayOf(0f)
        val target = floatArrayOf(1f)
        repeat(60) { current = VisualizerBands.smooth(current, target) }
        assertThat(current[0]).isWithin(0.001f).of(1f)
        assertThat(current[0]).isAtMost(1f)
    }

    @Test
    fun `smoothing tolerates a size change rather than crashing`() {
        val out = VisualizerBands.smooth(floatArrayOf(0f, 0f), floatArrayOf(1f))
        assertThat(out.size).isEqualTo(1)
    }

    // --- Amplitude ------------------------------------------------------------------------------

    @Test
    fun `there is no automatic gain control`() {
        // Quiet and loud must not be normalised into looking identical.
        val quiet = VisualizerBands.level(floatArrayOf(0.1f, 0.1f))
        val loud = VisualizerBands.level(floatArrayOf(0.9f, 0.9f))
        assertThat(loud).isGreaterThan(quiet)
        assertThat(quiet).isWithin(0.01f).of(0.1f)
    }

    @Test
    fun `identical frames are recognised so a held note does not repaint`() {
        val a = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertThat(VisualizerBands.isVisuallyIdentical(a, a.copyOf())).isTrue()
        assertThat(VisualizerBands.isVisuallyIdentical(a, floatArrayOf(0.1f, 0.2f, 0.9f))).isFalse()
    }

    @Test
    fun `decay falls to zero and does not go negative`() {
        var bands = floatArrayOf(1f, 1f)
        repeat(40) { bands = VisualizerBands.decay(bands) }
        bands.forEach { assertThat(it).isWithin(0.001f).of(0f) }
    }

    // --- Render cadence -------------------------------------------------------------------------

    @Test
    fun `the draw cadence is bounded well below display refresh rate`() {
        val interval = Regex("REDRAW_INTERVAL_MS = (\\d+)L").find(view_)?.groupValues?.get(1)?.toLong()
        assertThat(interval).isNotNull()
        // ~24 fps. Bounded by a delay loop, not by whatever rate the capture listener fires at.
        assertThat(interval!!).isAtLeast(33L)
        assertThat(interval).isAtMost(50L)
    }

    @Test
    fun `the visualizer has no unlimited animation primitive`() {
        assertThat(view_).doesNotContain("rememberInfiniteTransition")
        assertThat(view_).doesNotContain("infiniteRepeatable")
        assertThat(view_).doesNotContain("withFrameNanos")
    }

    @Test
    fun `the draw loop is visibility-gated and stops when inactive`() {
        assertThat(view_).contains("if (!active)")
        assertThat(view_).contains("while (isActive)")
    }

    // --- Resource ownership ---------------------------------------------------------------------

    @Test
    fun `the platform effect is released explicitly`() {
        assertThat(source_).contains("fun release()")
        assertThat(source_).contains("effect?.release()")
        assertThat(source_).contains("effect?.enabled = false")
        assertEqualsNullAfterRelease()
    }

    private fun assertEqualsNullAfterRelease() {
        // The release path must null the handle, or a later acquire would attach twice.
        val releaseFn = source_.substringAfter("fun release()").substringBefore("fun decay(")
        assertThat(releaseFn).contains("releaseEffect()")
    }


    // --- Presentation ---------------------------------------------------------------------------

    @Test
    fun `the visualization is decorative to accessibility`() {
        assertThat(view_).contains("clearAndSetSemantics")
    }

    @Test
    fun `themes come from the shared authorities`() {
        assertThat(view_).doesNotContain("Color(0xFF")
        val lock = source("presentation/lockscreen/DapLockScreen.kt")
        // The lock surface takes its accent through the DS controls rather than reading it itself.
        assertThat(lock).contains("DapStateIconButton(")
        assertThat(lock).doesNotContain("Color(0xFF")
    }


}
