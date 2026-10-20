package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-146 (device, 2026-10-06): the player screens drew at 60 fps for the whole of playback —
 * 719 frames in 12 s with the external player in front, `RenderThread` 21–33 s of CPU per minute.
 *
 * The cause was Material's `LinearWavyProgressIndicator`, which runs an endless wave-offset tween
 * whenever `waveSpeed > 0`, whatever the amplitude. The behaviour is tested as a rule in
 * `WaveScrollPolicyTest`; Compose cannot be driven on the JVM here, so this asserts the wiring: the
 * speed handed to Material goes through the gate, the gate only runs while the screen is being
 * drawn, and the rest of the player surfaces have no endless animation of their own.
 */
class WaveScrollBoundaryTest {

    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")

    private fun source(relative: String): String =
        File(main, relative).readText().replace("\r\n", "\n")

    private val slider = source("presentation/components/WavySliderExpressive.kt")

    @Test
    fun `the speed handed to Material is gated, never the raw parameter`() {
        val indicator = slider.substringAfter("LinearWavyProgressIndicator(").substringBefore("\n            )")
        assertThat(indicator).contains("waveSpeed = if (waveScrolling) waveSpeed else 0.dp")
        assertThat(indicator).doesNotContain("waveSpeed = waveSpeed\n")
    }

    @Test
    fun `the gate wants a visible wave with an amplitude, not merely a composed bar`() {
        assertThat(slider).contains(
            "wanted = WaveScrollPolicy.mayScroll(visible = isVisible, amplitudeShown = amplitudeShown)"
        )
        // Observed as a boolean, not as the amplitude's per-frame value.
        assertThat(slider).contains("derivedStateOf { animatedAmplitudeState.value > 0f }")
    }

    @Test
    fun `the scroll window runs only while the activity is started and ends on a cycle boundary`() {
        val gate = slider.substringAfter("private fun rememberWaveScrolling(")
        assertThat(gate).contains("repeatOnLifecycle(Lifecycle.State.STARTED)")
        assertThat(gate).contains("WaveScrollPolicy.shouldFreeze(")
        assertThat(gate).contains("scrolling.value = false")
    }

    @Test
    fun `the full player bar is still drawn flat, so it never asks for a scroll`() {
        // B21: the seek bar lives with the progress surface.
        val player = source("presentation/components/player/PlayerProgressSurface.kt")
        val efficientSlider = player.substringAfter("private fun EfficientSlider(").substringBefore("\n@Composable")
        assertThat(efficientSlider).contains("isPlaying = false,")
        assertThat(efficientSlider).contains("waveAmplitudeWhenPlaying = 0.dp,")
    }

    @Test
    fun `the external player re-arms the wave per track`() {
        assertThat(source("presentation/components/external/ExternalPlayerOverlay.kt"))
            .contains("scrollActivityKey = currentSong.id,")
    }

    @Test
    fun `the player surfaces carry no endless animation of their own`() {
        val surfaces = listOf(
            "presentation/components/player/FullPlayerContent.kt",
            "presentation/components/player/FullPlayerScreen.kt",
            "presentation/components/player/PlayerCoverSurface.kt",
            "presentation/components/player/PlayerTrackInfoSurface.kt",
            "presentation/components/player/PlayerProgressSurface.kt",
            "presentation/components/player/PlayerTechnicalLine.kt",
            "presentation/components/player/PlayerControlsSurface.kt",
            "presentation/components/player/PlayerLyricsEntry.kt",
            "presentation/components/player/PlayerDelayedContent.kt",
            "presentation/components/external/ExternalPlayerOverlay.kt",
            "presentation/components/UnifiedPlayerSheetV2.kt",
            "presentation/components/UnifiedPlayerSheetLayers.kt",
            "presentation/components/UnifiedPlayerSheetShared.kt",
            "presentation/lockscreen/DapLockScreen.kt",
            "presentation/lockscreen/DapLockScreenHost.kt",
        )
        for (path in surfaces) {
            val text = source(path)
            assertThat(text).doesNotContain("rememberInfiniteTransition")
            assertThat(text).doesNotContain("infiniteRepeatable")
            assertThat(text).doesNotContain("iterations = Int.MAX_VALUE")
        }
    }

    @Test
    fun `endless animations exist only where something is genuinely busy`() {
        // Each of these runs only while it is on screen and busy (loading, indeterminate, lyric gap
        // bubbles). A new entry here needs a reason: an endless animation holds the window at refresh rate.
        val allowed = setOf(
            "presentation/components/LyricsSheet.kt",
            "presentation/components/subcomps/SineWaveLine.kt",
            "ui/ds/DapControls.kt",
            "utils/LyricsUtils.kt",
        )
        val found = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter {
                val text = it.readText()
                text.contains("rememberInfiniteTransition(") || text.contains("infiniteRepeatable(")
            }
            .map { it.relativeTo(main).invariantSeparatorsPath }
            .toSet()
        assertThat(found).isEqualTo(allowed)
    }

    @Test
    fun `the position tick constants have one home`() {
        val holder = source("presentation/viewmodel/PlaybackStateHolder.kt")
        assertThat(holder).doesNotContain("SLIDER_TICK_MS = ")
        assertThat(holder).doesNotContain("MINIPLAYER_TICK_MS")
        assertThat(holder).contains("PlaybackProgressRates.tickMs(")
        assertThat(source("presentation/lockscreen/DapLockScreen.kt"))
            .contains("delay(PlaybackProgressRates.LOCK_SURFACE_TICK_MS)")
        assertThat(source("presentation/components/player/PlayerProgressSurface.kt"))
            .contains("PlaybackProgressRates.EXPANDED_BAR_SAMPLE_MS")
    }
}
