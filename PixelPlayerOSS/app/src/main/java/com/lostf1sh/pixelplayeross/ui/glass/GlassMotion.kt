package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * B41: how the glass moves. The same small field is drawn as three copies, the first filling the screen and two larger ones that glide,
 * turn and breathe over it at their own periods (tens of seconds, none a multiple of another), so the colour areas melt into each other and
 * never repeat in a way the eye catches. It is a few numbers per layer: nothing is computed per pixel.
 *
 * Everything here is a function of one value, [phase] (seconds of motion so far). A still glass is the same three layers at the phase it
 * was left at, so a paused player looks like the moving one at that instant.
 *
 * **B42-fix3 (item 7): more travel, and faster.** B42-fix2's two moving layers had periods of 37-89 s, a translation of 0.10-0.16 of the screen,
 * a turn of 3 degrees and a breath of 0.04-0.05: the owner: "ours barely moves". They now travel about 0.24-0.26 of the screen, turn 8
 * degrees, breathe 0.08 and fade 0.15, with periods of 23-61 s: the colour at a fixed point changes noticeably over about ten seconds, and
 * it is slower and gentler than the first reading of the brief (the owner asked for "notable", not fast). **All of the tuning is in
 * [Tuning]**; [Tuning.SPEED] and [Tuning.TRAVEL] scale it all with a one-line change.
 */
object GlassMotion {
    /** One drawn copy of the field. Translation is a fraction of the screen's width and height; the origin is the screen's centre. */
    @Immutable
    data class Layer(
        val scale: Float,
        val rotationDegrees: Float,
        val translationX: Float,
        val translationY: Float,
        val alpha: Float,
        /** Drawn mirrored left to right. (No layer is, at present: a mirrored copy's gradient runs against its twin's and cancels it.) */
        val mirrored: Boolean,
    )

    /** One wave: how far ([amplitude]) and how long a cycle takes ([periodSeconds]), starting at [offset] radians. */
    @Immutable
    data class Wave(val amplitude: Float, val periodSeconds: Float, val offset: Float)

    /** The whole motion of one moving layer. [scale] and [alpha] are the middle they swing around. */
    @Immutable
    data class Drift(
        val scale: Float,
        val scaleWave: Wave,
        val rotationWave: Wave,
        val translationXWave: Wave,
        val translationYWave: Wave,
        val alpha: Float,
        val alphaWave: Wave,
    )

    /**
     * Every number of the glass's motion, in one place. Periods are primes of seconds, so no two ever line up and the picture does not come
     * back to where it was in a way the eye catches. The base scale is what keeps a moving layer over the whole screen at the worst of its
     * travel and turn at once (`GlassMotionTest` checks it): raise the travel and the scale has to follow.
     */
    object Tuning {
        /** Multiplies every speed: 1 is as tuned, 2 is twice as fast. The one number to change to make all of it faster or slower. */
        const val SPEED = 1f

        /** Multiplies every translation, rotation and breath (not the scales they swing around: see [Drift.scale]). */
        const val TRAVEL = 1f

        val FIRST = Drift(
            scale = 2.05f,
            scaleWave = Wave(0.08f, 43f, 0f),
            rotationWave = Wave(8f, 41f, 0.7f),
            translationXWave = Wave(0.26f, 31f, 1.3f),
            translationYWave = Wave(0.20f, 37f, 0.4f),
            alpha = 0.58f,
            alphaWave = Wave(0.15f, 29f, 2.1f),
        )

        val SECOND = Drift(
            scale = 2.00f,
            scaleWave = Wave(0.08f, 59f, 2.0f),
            rotationWave = Wave(-8f, 53f, 1.0f),
            translationXWave = Wave(0.24f, 23f, 2.2f),
            translationYWave = Wave(0.18f, 47f, 0.9f),
            alpha = 0.50f,
            alphaWave = Wave(0.15f, 61f, 1.1f),
        )

        /** The moving layers, in the order they are drawn after the base. */
        val DRIFTS: List<Drift> = listOf(FIRST, SECOND)
    }

    const val LAYERS = 3

    private const val TAU = (2.0 * PI).toFloat()

    private fun wave(phase: Float, wave: Wave, travel: Float = Tuning.TRAVEL): Float =
        wave.amplitude * travel * sin(TAU * phase * Tuning.SPEED / wave.periodSeconds + wave.offset)

    /** The three layers at [phase] seconds: the base, and the two that move. */
    fun layers(phase: Float): List<Layer> = List(LAYERS) { layer(phase, it) }

    /**
     * One layer at [phase]. What the draw phase asks for, once per layer per frame: B41 built the whole list of three (and so computed the
     * sines of all three) in each of the two moving layers' lambdas, 2 lists and 6 layers where 2 are needed; at the display's rate (B41-fix1,
     * B4) that is the per-frame cost, so it is only what is drawn.
     */
    fun layer(phase: Float, index: Int, travel: Float = Tuning.TRAVEL): Layer {
        if (index == 0) return BASE
        val drift = Tuning.DRIFTS.getOrNull(index - 1) ?: throw IndexOutOfBoundsException("no glass layer $index")
        return Layer(
            scale = drift.scale + wave(phase, drift.scaleWave, travel),
            rotationDegrees = wave(phase, drift.rotationWave, travel),
            translationX = wave(phase, drift.translationXWave, travel),
            translationY = wave(phase, drift.translationYWave, travel),
            alpha = drift.alpha + wave(phase, drift.alphaWave, travel),
            mirrored = false,
        )
    }

    private val BASE = Layer(scale = 1f, rotationDegrees = 0f, translationX = 0f, translationY = 0f, alpha = 1f, mirrored = false)

    /** The phase is kept inside one long cycle so a float never loses the precision a slow glide needs, however long the player plays. */
    const val PHASE_WRAP_SECONDS = 3_600f * 24f * 7f

    fun wrap(phase: Float): Float = if (phase >= PHASE_WRAP_SECONDS) phase % PHASE_WRAP_SECONDS else phase

    /** A step longer than this (the app was paused, the main thread stalled) is taken as this long: the glass never jumps. */
    const val MAX_STEP_SECONDS = 0.25f

    /** How long the old field takes to give way to the new one (after the 350 ms the app already waits for a song to settle). */
    const val CROSSFADE_MS = 400

    /** How long a cover must stay the song's before the glass changes to it: the app's own colour settle, so skipping fast changes it once. */
    const val SETTLE_MS = 350L

    /** Changes to what the glass is made from that arrive within this long of each other are one change. */
    const val COALESCE_MS = 40L
}

/**
 * B41: when the glass moves, and when it holds still. It moves only while all of these are true, and a paused or hidden player costs
 * nothing at all (no ticker runs, nothing is invalidated).
 */
object GlassMotionPolicy {
    /** What the decision reads. */
    @Immutable
    data class Inputs(
        /** "Cover glass" is the app's colour source and there is a field to draw. */
        val glassShown: Boolean = false,
        /** Appearance, Player, "Moving background". */
        val movingBackgroundOn: Boolean = true,
        /** A song is playing (not paused, not buffering to a stop). */
        val playing: Boolean = false,
        /** The full player is fully open. */
        val playerExpanded: Boolean = false,
        /** The DAP lock screen is up. */
        val lockSurfaceVisible: Boolean = false,
        /** The activity is resumed: the app is in front (another screen, the system's, a permission dialog is not). */
        val resumed: Boolean = true,
        /** The screen is on and interactive. The lock screen is dozing when it is not. */
        val screenInteractive: Boolean = true,
        /** Android's "Remove animations" (animator duration scale 0) is off and Battery saver is off. */
        val systemAnimationsAllowed: Boolean = true,
    )

    /** The glass moves only where it is the thing in front: the full player, or the lock screen. Everywhere else it is still. */
    fun shouldMove(i: Inputs): Boolean =
        i.glassShown && i.movingBackgroundOn && i.playing && i.resumed && i.screenInteractive && i.systemAnimationsAllowed &&
            (i.playerExpanded || i.lockSurfaceVisible)

    /** The step to take after [elapsedNanos] since the last frame: capped, so a stall never makes the glass jump. */
    fun stepSeconds(elapsedNanos: Long): Float =
        (elapsedNanos.coerceAtLeast(0L) / 1_000_000_000f).coerceAtMost(GlassMotion.MAX_STEP_SECONDS)
}

/**
 * What a pixel of the glass is, computed on the CPU in exactly the terms the layers are drawn with: for the tests that measure the ink's
 * contrast on the field (in the corners of the moving glass, at the phases where the layers lie over each other worst) and for the renders.
 * The app never calls it per frame.
 */
object GlassSample {
    /** Bilinear sample of [field] at ([u], [v]) in 0..1, texel centres at half cells, the edge repeated: what the GPU's filter does. */
    fun bilinear(field: GlassField, u: Float, v: Float): Int {
        val n = field.size
        val x = (u * n - 0.5f).coerceIn(0f, n - 1f)
        val y = (v * n - 0.5f).coerceIn(0f, n - 1f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = minOf(x0 + 1, n - 1)
        val y1 = minOf(y0 + 1, n - 1)
        val fx = x - x0
        val fy = y - y0
        fun channel(shift: Int): Float {
            fun c(ix: Int, iy: Int) = ((field.argb[iy * n + ix] shr shift) and 0xFF).toFloat()
            val top = c(x0, y0) * (1 - fx) + c(x1, y0) * fx
            val bottom = c(x0, y1) * (1 - fx) + c(x1, y1) * fx
            return top * (1 - fy) + bottom * fy
        }
        return (0xFF shl 24) or (channel(16).toInt() shl 16) or (channel(8).toInt() shl 8) or channel(0).toInt()
    }

    /**
     * The screen's pixel ([px], [py]) of a [width] x [height] window, with [layers] over each other (the base first) and the scrim over
     * them. The layers are placed about the screen's centre: scaled, turned (clockwise, as `rotationZ`), then moved.
     */
    fun pixel(
        field: GlassField,
        layers: List<GlassMotion.Layer>,
        width: Int,
        height: Int,
        px: Int,
        py: Int,
        scrimArgb: Int = 0,
        scrimAlpha: Float = 0f,
    ): Int {
        var r = 0f
        var g = 0f
        var b = 0f
        val cx = width / 2f
        val cy = height / 2f
        layers.forEachIndexed { index, layer ->
            // inverse of: scale about the centre, rotate, translate
            val dx = px + 0.5f - cx - layer.translationX * width
            val dy = py + 0.5f - cy - layer.translationY * height
            val theta = Math.toRadians(layer.rotationDegrees.toDouble())
            val cosT = cos(theta).toFloat()
            val sinT = sin(theta).toFloat()
            // R(theta) is clockwise on a y-down screen: x' = cos*x - sin*y, y' = sin*x + cos*y; invert with R(-theta)
            var lx = (cosT * dx + sinT * dy) / layer.scale
            val ly = (-sinT * dx + cosT * dy) / layer.scale
            if (layer.mirrored) lx = -lx
            val u = (lx + cx) / width
            val v = (ly + cy) / height
            if (index > 0 && (u < 0f || u > 1f || v < 0f || v > 1f)) return@forEachIndexed
            val s = bilinear(field, u, v)
            val a = layer.alpha
            r = if (index == 0) ((s shr 16) and 0xFF).toFloat() else r * (1 - a) + ((s shr 16) and 0xFF) * a
            g = if (index == 0) ((s shr 8) and 0xFF).toFloat() else g * (1 - a) + ((s shr 8) and 0xFF) * a
            b = if (index == 0) (s and 0xFF).toFloat() else b * (1 - a) + (s and 0xFF) * a
        }
        if (scrimAlpha > 0f) {
            r = r * (1 - scrimAlpha) + ((scrimArgb shr 16) and 0xFF) * scrimAlpha
            g = g * (1 - scrimAlpha) + ((scrimArgb shr 8) and 0xFF) * scrimAlpha
            b = b * (1 - scrimAlpha) + (scrimArgb and 0xFF) * scrimAlpha
        }
        return (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }
}
