package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.BackgroundMotion
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** B56 phase 7.1 (#48): Background motion = Still / Gentle / Lively, speed and amplitude only, and the stops of the live background. */
class B56BackgroundMotionTest {
    @Test
    fun `the three values are two keys read as one choice, and gentle is the default`() {
        assertThat(BackgroundMotion.DEFAULT).isEqualTo(BackgroundMotion.GENTLE)
        assertThat(BackgroundMotion.of(moving = true, lively = false)).isEqualTo(BackgroundMotion.GENTLE)
        assertThat(BackgroundMotion.of(moving = true, lively = true)).isEqualTo(BackgroundMotion.LIVELY)
        assertThat(BackgroundMotion.of(moving = false, lively = true)).isEqualTo(BackgroundMotion.STILL)      // off is off, whatever the level was
        assertThat(BackgroundMotion.fromStorageKey("lively")).isEqualTo(BackgroundMotion.LIVELY)
        assertThat(BackgroundMotion.fromStorageKey("x")).isNull()
    }

    @Test
    fun `gentle is exactly today's tuning and lively is faster and wider`() {
        assertThat(BackgroundMotion.GENTLE.speed).isEqualTo(GlassMotion.Tuning.SPEED)
        assertThat(BackgroundMotion.GENTLE.travel).isEqualTo(GlassMotion.Tuning.TRAVEL)
        for (phase in listOf(0f, 7.5f, 31f, 90f)) for (index in 1..2) {
            assertThat(GlassMotion.layer(phase, index, travel = BackgroundMotion.GENTLE.travel)).isEqualTo(GlassMotion.layer(phase, index))
        }
        assertThat(BackgroundMotion.LIVELY.speed).isGreaterThan(BackgroundMotion.GENTLE.speed)
        assertThat(BackgroundMotion.LIVELY.travel).isGreaterThan(BackgroundMotion.GENTLE.travel)
        val gentle = GlassMotion.layer(30f, 1, travel = BackgroundMotion.GENTLE.travel)
        val lively = GlassMotion.layer(30f, 1, travel = BackgroundMotion.LIVELY.travel)
        assertThat(abs(lively.translationX)).isGreaterThan(abs(gentle.translationX) * 1.2f)
    }

    @Test
    fun `still runs nothing and keeps the layers where they were left, which is today's still glass`() {
        val clock = GlassClock().apply { motion = BackgroundMotion.STILL }

        clock.step(5f)
        clock.step(5f)

        assertThat(clock.phase).isEqualTo(0f)
        assertThat(BackgroundMotion.STILL.travel).isEqualTo(GlassMotion.Tuning.TRAVEL)            // the same layers as the switch off always drew
        assertThat(GlassMotion.layer(clock.phase, 1, travel = BackgroundMotion.STILL.travel)).isEqualTo(GlassMotion.layer(0f, 1))
    }

    @Test
    fun `the clock runs at the speed of its motion and never backwards`() {
        val gentle = GlassClock().apply { motion = BackgroundMotion.GENTLE }
        val lively = GlassClock().apply { motion = BackgroundMotion.LIVELY }

        repeat(10) { gentle.step(0.5f); lively.step(0.5f) }
        gentle.step(-3f)

        assertThat(gentle.phase).isWithin(0.001f).of(5f)
        assertThat(lively.phase).isWithin(0.001f).of(5f * BackgroundMotion.LIVELY.speed)
    }

    @Test
    fun `a paused clock resumes from its phase with no jump`() {
        val clock = GlassClock().apply { motion = BackgroundMotion.LIVELY }
        clock.step(3f)
        val held = clock.phase

        // paused: nobody steps it (the ticker is not running); the time that passes is not added
        assertThat(clock.phase).isEqualTo(held)
        clock.step(1f / 30f)

        assertThat(clock.phase - held).isWithin(0.0001f).of(BackgroundMotion.LIVELY.speed / 30f)   // the first step after a pause is one frame
    }

    @Test
    fun `a copy for a sheet is still, at the phase and the motion it was taken at`() {
        val clock = GlassClock().apply { motion = BackgroundMotion.LIVELY; step(4f) }

        val copy = clock.frozenCopy()

        assertThat(copy.phase).isEqualTo(clock.phase)
        assertThat(copy.motion).isEqualTo(BackgroundMotion.LIVELY)
    }

    @Test
    fun `the live background exists only on Android 13 and later`() {
        assertThat(LiveBlobs.available(29)).isFalse()
        assertThat(LiveBlobs.available(32)).isFalse()
        assertThat(LiveBlobs.available(33)).isTrue()
        assertThat(LiveBackgroundPolicy.shouldRun(motionMoves = true, sdkInt = 32, glassMoves = true, lockSurfaceVisible = false, sheetOpen = false)).isFalse()
    }

    @Test
    fun `the live background holds still under the DAP lock screen and under a sheet, and when the setting is still`() {
        fun run(motion: Boolean = true, lock: Boolean = false, sheet: Boolean = false, glass: Boolean = true) =
            LiveBackgroundPolicy.shouldRun(motion, 35, glass, lock, sheet)

        assertThat(run()).isTrue()
        assertThat(run(lock = true)).isFalse()
        assertThat(run(sheet = true)).isFalse()
        assertThat(run(motion = false)).isFalse()
        assertThat(run(glass = false)).isFalse()                                                   // the classic policy's stops (paused, screen off, background, ...) come first
    }

    @Test
    fun `the live background is stepped at thirty a second`() {
        assertThat(LiveBlobs.FPS).isEqualTo(30)
        assertThat(LiveBlobs.FRAME_NANOS).isEqualTo(33_333_333L)
    }

    @Test
    fun `the palette is three to five distinct colours of the cover`() {
        val cover = IntArray(32 * 32) { i ->
            when {
                i % 4 == 0 -> 0xFFE6145A.toInt()     // magenta
                i % 4 == 1 -> 0xFF18D4E6.toInt()     // cyan
                i % 4 == 2 -> 0xFFFFD21A.toInt()     // yellow
                else -> 0xFF0B1630.toInt()           // navy
            }
        }

        val palette = CoverColours.palette(cover, 32, 32)

        assertThat(palette.size).isAtLeast(3)
        assertThat(palette.size).isAtMost(5)
        for (a in palette.indices) for (b in a + 1 until palette.size) assertThat(CoverColours.distinct(palette[a], palette[b])).isTrue()
    }

    @Test
    fun `a cover of one colour still gives three, the others deeper shades of it`() {
        val palette = CoverColours.palette(IntArray(16 * 16) { 0xFF3366CC.toInt() }, 16, 16)

        assertThat(palette).hasSize(3)
        assertThat(palette.first()).isEqualTo(Color(0xFF3366CC))
    }

    @Test
    fun `the shader is told at most five colours`() {
        assertThat(LiveBlobs.colorsOf(List(8) { Color.Red })).hasSize(5)
        assertThat(LiveBlobs.AGSL).contains("uniform float time")
        assertThat(LiveBlobs.AGSL).contains("c4")
    }
}
