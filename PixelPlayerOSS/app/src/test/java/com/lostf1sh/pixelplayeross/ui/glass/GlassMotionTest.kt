package com.lostf1sh.pixelplayeross.ui.glass

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * B41: the glass moves slowly, on the render thread, at a low frame rate, and stops completely when nothing is looking: a paused player, a
 * hidden one, a dark screen, "Remove animations", Battery saver, a dozing lock screen. This is the policy and the motion's numbers; the
 * composables that act on them are in `CoverGlassBackdropTest`.
 */
class GlassMotionTest {
    private val on = GlassMotionPolicy.Inputs(
        glassShown = true, movingBackgroundOn = true, playing = true, playerExpanded = true, lockSurfaceVisible = false,
        resumed = true, screenInteractive = true, systemAnimationsAllowed = true,
    )

    // ── when it moves ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `it moves in the full player while a song plays, and on the lock screen`() {
        assertThat(GlassMotionPolicy.shouldMove(on)).isTrue()
        assertThat(GlassMotionPolicy.shouldMove(on.copy(playerExpanded = false, lockSurfaceVisible = true))).isTrue()
    }

    @Test
    fun `it holds still everywhere else, even while a song plays`() {
        // Home, Library, Search, Settings, the mini player: neither the full player nor the lock screen is up
        assertThat(GlassMotionPolicy.shouldMove(on.copy(playerExpanded = false, lockSurfaceVisible = false))).isFalse()
    }

    @Test
    fun `it stops for each of the conditions the prompt names`() {
        val stops = mapOf(
            "playback is paused" to on.copy(playing = false),
            "the screen is off" to on.copy(screenInteractive = false),
            "the player is hidden behind another screen (the activity is not in front)" to on.copy(resumed = false),
            "Remove animations or Battery saver is on" to on.copy(systemAnimationsAllowed = false),
            "the lock screen is dozing" to on.copy(playerExpanded = false, lockSurfaceVisible = true, screenInteractive = false),
            "the listener turned Moving background off" to on.copy(movingBackgroundOn = false),
            "Cover glass is not on, or there is no cover" to on.copy(glassShown = false),
        )
        for ((why, inputs) in stops) assertWithMessage(why).that(GlassMotionPolicy.shouldMove(inputs)).isFalse()
    }

    @Test
    fun `the lock screen over a paused song is still too`() {
        assertThat(GlassMotionPolicy.shouldMove(on.copy(playerExpanded = false, lockSurfaceVisible = true, playing = false))).isFalse()
    }

    // ── how often ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a step is as long as it really was, but never more than a quarter of a second`() {
        assertThat(GlassMotionPolicy.stepSeconds(50_000_000L)).isWithin(1e-6f).of(0.05f)
        // a frame of a 120 Hz display keeps its fraction of a millisecond (B41-fix1, B4: steps were whole milliseconds, 8 of 8.33)
        assertThat(GlassMotionPolicy.stepSeconds(8_333_333L)).isWithin(1e-6f).of(0.008333333f)
        // the app was paused, or the main thread stalled for a minute: the glass does not jump
        assertThat(GlassMotionPolicy.stepSeconds(60_000_000_000L)).isEqualTo(GlassMotion.MAX_STEP_SECONDS)
        assertThat(GlassMotionPolicy.stepSeconds(-5_000_000L)).isEqualTo(0f)
    }

    // ── how fast and how far ────────────────────────────────────────────────────────────────────────

    /** One frame of a 60 Hz display, the slowest the glass is stepped at (B41-fix1: it is stepped at the display's rate, 60 to 120 a second). */
    private val frame = 1f / 60f

    /** The biggest a layer's point moves between two steps, anywhere on a [width] x [height] screen, over two hours of phases. */
    private fun worstStepPx(width: Float, height: Float): Float {
        val corner = kotlin.math.hypot(width, height) / 2f
        var worst = 0f
        var t = 0f
        while (t < 7_200f) {
            val a = GlassMotion.layers(t)
            val b = GlassMotion.layers(t + frame)
            for (i in 1 until GlassMotion.LAYERS) {
                val dx = abs(a[i].translationX - b[i].translationX) * width
                val dy = abs(a[i].translationY - b[i].translationY) * height
                val dRot = abs(a[i].rotationDegrees - b[i].rotationDegrees) * (Math.PI / 180.0).toFloat() * corner
                val dScale = abs(a[i].scale - b[i].scale) * corner
                worst = max(worst, max(max(dx, dy), max(dRot, dScale)))
            }
            t += 7.3f
        }
        return worst
    }

    @Test
    fun `a step moves the glass about a pixel on the HONOR and two on the biggest screen, so it is smooth at the display's rate`() {
        val honor = worstStepPx(720f, 1610f)
        val big = worstStepPx(1440f, 3200f)
        println("B41 | biggest displacement per frame: ${"%.2f".format(honor)} px on the HONOR (720 x 1610), ${"%.2f".format(big)} px on 1440 x 3200")
        // under a pixel and a half on the phone this is for; the field is blurred, so a step of a pixel or two shows as nothing at all
        assertThat(honor).isLessThan(1.2f)
        assertThat(big).isLessThan(2.5f)
    }

    @Test
    fun `one layer at a phase is the one the list of layers has, for every layer and phase`() {
        var t = 0f
        while (t < 7_200f) {
            val all = GlassMotion.layers(t)
            for (i in 0 until GlassMotion.LAYERS) assertThat(GlassMotion.layer(t, i)).isEqualTo(all[i])
            t += 13.7f
        }
        org.junit.Assert.assertThrows(IndexOutOfBoundsException::class.java) { GlassMotion.layer(0f, GlassMotion.LAYERS) }
    }

    @Test
    fun `the base layer never moves, so asking for it costs nothing`() {
        assertThat(GlassMotion.layer(12.3f, 0)).isSameInstanceAs(GlassMotion.layer(98.7f, 0))
    }

    @Test
    fun `the layers always cover the whole screen, so no edge of one is ever seen`() {
        val aspect = listOf(1080 to 2400, 1440 to 3200, 720 to 1610, 2560 to 1600)
        for ((w, h) in aspect) {
            var t = 0f
            while (t < 4_000f) {
                val layers = GlassMotion.layers(t)
                for (i in 1 until GlassMotion.LAYERS) {
                    val l = layers[i]
                    val theta = Math.toRadians(l.rotationDegrees.toDouble())
                    for ((px, py) in listOf(0 to 0, w to 0, 0 to h, w to h)) {
                        val dx = px - w / 2f - l.translationX * w
                        val dy = py - h / 2f - l.translationY * h
                        val lx = (cos(theta) * dx + sin(theta) * dy).toFloat() / l.scale
                        val ly = (-sin(theta) * dx + cos(theta) * dy).toFloat() / l.scale
                        assertThat(abs(lx)).isAtMost(w / 2f)
                        assertThat(abs(ly)).isAtMost(h / 2f)
                    }
                }
                t += 3.7f
            }
        }
    }

    @Test
    fun `no layer repeats in under half a minute, and none is a multiple of another`() {
        // sample the first moving layer's x translation: its fastest swing from one extreme to the other takes about 20 s or more
        var t = 0f
        var last = GlassMotion.layers(0f)[1].translationX
        var peakToPeakTime = Float.MAX_VALUE
        var lastExtreme = 0f
        var rising = true
        while (t < 400f) {
            t += 0.1f
            val x = GlassMotion.layers(t)[1].translationX
            val nowRising = x >= last
            if (nowRising != rising) {
                if (lastExtreme > 0f) peakToPeakTime = minOf(peakToPeakTime, t - lastExtreme)
                lastExtreme = t
                rising = nowRising
            }
            last = x
        }
        assertThat(peakToPeakTime).isAtLeast(15f)
    }

    // ── the speed, against what was measured on the HONOR ───────────────────────────────────────────

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** The night cover with a blue sky on the left and red windows on the right: the colour has somewhere to go. */
    private fun cinemaLike(): IntArray {
        val px = IntArray(96 * 96)
        for (y in 0 until 96) for (x in 0 until 96) {
            val sky = (1f - x / 95f)
            val red = if (x > 55 && y in 35..80) 1f else 0f
            val r = (20 + 215 * red + 10 * sky).toInt().coerceIn(0, 255)
            val g = (30 + 20 * red + 60 * sky).toInt().coerceIn(0, 255)
            val b = (70 + 70 * sky - 40 * red).toInt().coerceIn(0, 255)
            px[y * 96 + x] = rgb(r, g, b)
        }
        return px
    }

    /** B42-fix2's two moving layers, as they were (periods of 37-89 s, a travel of 0.10-0.16, a turn of 3 degrees): what the owner called "barely moves". */
    private fun b42Fix2Layers(t: Float): List<GlassMotion.Layer> {
        fun w(period: Float, offset: Float) = sin(2.0 * Math.PI * t / period + offset).toFloat()
        return listOf(
            GlassMotion.layer(t, 0),
            GlassMotion.Layer(1.70f + 0.05f * w(53f, 0f), 3f * w(71f, 0.7f), 0.16f * w(71f, 1.3f), 0.10f * w(83f, 0.4f), 0.58f + 0.10f * w(37f, 2.1f), false),
            GlassMotion.Layer(1.62f + 0.04f * w(79f, 2.0f), -3f * w(89f, 1.0f), 0.14f * w(67f, 2.2f), 0.12f * w(61f, 0.9f), 0.50f + 0.10f * w(41f, 1.1f), false),
        )
    }

    private val motionPoints = listOf(20 to 30, 330 to 60, 180 to 400, 40 to 760, 320 to 740, 180 to 120, 90 to 560)

    private fun channelChange(a: Int, b: Int) =
        max(abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)), max(abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF))))

    /** The mean, over the points and over starting instants a minute and a half apart, of how far a point's colour goes in [seconds] on [field]. */
    private fun meanChangeOver(field: GlassField, seconds: Float, layersAt: (Float) -> List<GlassMotion.Layer>): Double {
        val w = 360
        val h = 800
        var sum = 0.0
        var n = 0
        for ((x, y) in motionPoints) {
            var start = 0f
            while (start < 900f) {
                sum += channelChange(
                    GlassSample.pixel(field, layersAt(start + seconds), w, h, x, y),
                    GlassSample.pixel(field, layersAt(start), w, h, x, y),
                )
                n++
                start += 37f
            }
        }
        return sum / n
    }

    @Test
    fun `at a fixed point the colour shifts gradually and continuously, never in a jump`() {
        val palette = DapBaseTheme.Graphite.palette
        val look = GlassBands.of(palette, palette.accentColor(DapAccent.Sage))
        val field = GlassFieldMath.field(GlassFieldMath.grid(cinemaLike(), 96, 96), look.band)
        val w = 360
        val h = 800
        fun at(t: Float, x: Int, y: Int) = GlassSample.pixel(field, GlassMotion.layers(t), w, h, x, y)
        var biggestStep = 0
        var widestRange = 0
        for ((x, y) in motionPoints) {
            var t = 0f
            var last = at(0f, x, y)
            val start = last
            var farthest = 0
            while (t < 60f) {
                t += frame
                val now = at(t, x, y)
                biggestStep = max(biggestStep, channelChange(now, last))
                farthest = max(farthest, channelChange(now, start))
                last = now
            }
            widestRange = max(widestRange, farthest)
        }
        println("B42-fix3 | speed: biggest step per frame $biggestStep levels in one channel; farthest a point goes in a minute $widestRange")
        // a step of the frame cap is a step of a level or two in any channel (never a jump)
        assertThat(biggestStep).isAtMost(3)
        assertThat(widestRange).isAtLeast(40)
    }

    @Test
    fun `the glass travels clearly further and faster than B42-fix2's, and is still not fast`() {
        val palette = DapBaseTheme.Graphite.palette
        val look = GlassBands.of(palette, palette.accentColor(DapAccent.Sage))
        // the same field under both motions, so only the motion is measured
        val field = GlassFieldMath.field(GlassFieldMath.grid(cinemaLike(), 96, 96), look.band)
        val oldMotion = meanChangeOver(field, 7.5f) { b42Fix2Layers(it) }
        val newMotion = meanChangeOver(field, 7.5f) { GlassMotion.layers(it) }
        val ratio = newMotion / oldMotion
        println("B42-fix3 | mean colour change at a point over 7.5 s on the same field: B42-fix2 ${"%.1f".format(oldMotion)} levels, now ${"%.1f".format(newMotion)} (x${"%.1f".format(ratio)})")
        // "notable": at least twice the travel it had; "not so fast": not past about six times, and never near the calm of a jump
        assertThat(ratio).isAtLeast(2.0)
        assertThat(ratio).isAtMost(6.0)
    }

    @Test
    fun `the motion is tuned in one place - ten periods that are distinct primes, none a multiple of another`() {
        val periods = GlassMotion.Tuning.DRIFTS.flatMap {
            listOf(it.scaleWave, it.rotationWave, it.translationXWave, it.translationYWave, it.alphaWave).map { w -> w.periodSeconds }
        }
        assertThat(periods).hasSize(10)
        assertThat(periods.toSet()).hasSize(10)
        for (p in periods) {
            val n = p.toInt()
            assertThat(p).isEqualTo(n.toFloat())
            assertThat((2 until n).none { n % it == 0 }).isTrue()
        }
        for (a in periods) for (b in periods) if (a != b) assertThat(a % b).isNotEqualTo(0f)
        // faster than the first reading of the brief would be a one-line change, and so is slower
        assertThat(GlassMotion.Tuning.SPEED).isEqualTo(1f)
        assertThat(GlassMotion.Tuning.TRAVEL).isEqualTo(1f)
    }

    @Test
    fun `the phase is kept in one long cycle`() {
        assertThat(GlassMotion.wrap(100f)).isEqualTo(100f)
        assertThat(GlassMotion.wrap(GlassMotion.PHASE_WRAP_SECONDS + 5f)).isWithin(1f).of(5f)
    }
}
