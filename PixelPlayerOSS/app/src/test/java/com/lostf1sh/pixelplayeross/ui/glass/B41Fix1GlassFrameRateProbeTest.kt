package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * B41-fix1 (B4): how many different positions the glass is drawn at in a second, seen from the frames of the display (a compose test's own,
 * which draws one every 16 ms). The real host, in the full player, with a song playing. It compiles against the tree before this batch and
 * fails there: B41 moved the glass 20 times a second, whatever the display did.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// B56-7.1: the classic glass (before Android 13) is stepped on every display frame; from 13 the live blobs are stepped at 30 a second (B56BackgroundMotionTest)
@Config(sdk = [32], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
class B41Fix1GlassFrameRateProbeTest {
    @get:Rule
    val compose = createComposeRule()

    private val palette = DapBaseTheme.Graphite.palette
    private val look = GlassBands.of(palette, palette.accentColor(DapAccent.Sage))!!
    private val field: GlassField by lazy {
        val px = IntArray(96 * 96) { i -> (0xFF shl 24) or (((i % 96) * 2) shl 16) or ((40 + i / 96) shl 8) or (200 - i % 96) }
        GlassFieldMath.field(GlassFieldMath.grid(px, 96, 96), look.band)
    }

    @Test
    fun the_glass_is_at_a_new_position_on_nearly_every_frame_the_display_draws() {
        val crossfade = GlassCrossfade()
        val clock = GlassClock()
        val ok = GlassEnvironment(mutableStateOf(true), mutableStateOf(true), mutableStateOf(true))
        val seen = HashSet<Float>()
        var frames = 0
        // a loop that waits for the display's next frame is an animation without an end: the test must not wait for the screen to be idle
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LaunchedEffect(Unit) { crossfade.show(field) }
            CoverGlassHost(
                scene = CoverGlassScene(crossfade, clock, look.band.base),
                playing = flowOf(true),
                playerExpansion = { 1f },
                movingBackgroundOn = true,
                lockSurfaceVisible = false,
                lockCovers = false,
                environment = ok,
            )
            LaunchedEffect(Unit) {
                // The platform's clock follows the display's frames, as it does on a phone (the tree before this batch steps by it).
                var last = withFrameNanos { it }
                while (true) withFrameNanos { t ->
                    ShadowSystemClock.advanceBy(Duration.ofNanos(t - last))
                    last = t
                    if (clock.phase > 0f) { seen += clock.phase; frames++ }
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.waitUntil(10_000) { crossfade.current != null }
        seen.clear()
        frames = 0
        compose.mainClock.advanceTimeBy(2_000)
        println("B41-fix1 B4 | in 2 s of the display's frames: $frames frames seen the glass at ${seen.size} different positions (${seen.size / 2} a second)")
        // the display draws 62 frames a second: the glass is at a new position in nearly every one (B41: 20 a second)
        assertThat(seen.size).isAtLeast(2 * 55)
    }
}
