package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize

/**
 * B41: the glass's clock. One value, seconds of motion so far, read **only in the draw/layer phase** (inside `graphicsLayer { }`), so a tick
 * recomposes nothing: it moves the layers' RenderNodes and the render thread redraws them. Whoever shows the glass moving advances it
 * ([GlassTicker], once per display frame); everything that shows it reads it, so the lock screen's glass and the app's under it are always in the same phase and a
 * reveal never jumps.
 */
@Stable
class GlassClock {
    private var seconds by mutableFloatStateOf(0f)

    /**
     * B56-7.1: how much the background moves (Still, Gentle, Lively): the speed the clock runs at and how far the layers or blobs travel. Set by whoever hosts the glass; Gentle is
     * today's tuning, so a clock nobody sets behaves as it always did. Read in the draw phase, like the phase.
     */
    var motion: com.lostf1sh.pixelplayeross.data.preferences.BackgroundMotion by androidx.compose.runtime.mutableStateOf(com.lostf1sh.pixelplayeross.data.preferences.BackgroundMotion.GENTLE)

    val phase: Float get() = seconds

    fun advance(deltaSeconds: Float) {
        seconds = GlassMotion.wrap(seconds + deltaSeconds)
    }

    /** One step of motion: [deltaSeconds] of real time at the speed of [motion] (nothing for Still). A step is never negative: a held clock keeps its phase and resumes from it. */
    fun step(deltaSeconds: Float) {
        val scaled = deltaSeconds.coerceAtLeast(0f) * motion.speed
        if (scaled > 0f) advance(scaled)
    }

    /**
     * B42: a clock stopped at this one's phase, for a sheet's own copy of the glass. The read is not observed, so whoever asks (a
     * composition) is not subscribed to a clock that moves 60 or 120 times a second: a sheet's glass is the glass at the instant it
     * opened, and nothing after.
     */
    fun frozenCopy(): GlassClock {
        val copy = GlassClock()
        copy.advance(Snapshot.withoutReadObservation { seconds })
        copy.motion = Snapshot.withoutReadObservation { motion }
        return copy
    }
}

/**
 * What the backdrop shows now: the field that is on screen, the one it is replacing while the two cross-fade, and how far along that is.
 * A field of null is "no glass": the theme's own background.
 */
@Stable
class GlassCrossfade {
    var current: GlassField? by androidx.compose.runtime.mutableStateOf(null)
        private set
    var previous: GlassField? by androidx.compose.runtime.mutableStateOf(null)
        private set

    /** 0 when [previous] is all there is, 1 when [current] is. Read in the draw phase. */
    private val progress = Animatable(1f)
    val blend: Float get() = progress.value

    /** How many transitions have been started: what a test counts (a fast skip is one, not one per song passed). */
    var transitions: Int = 0
        private set

    /** The glass is on screen, or is still fading out: the pages stay see-through until it is gone. */
    val visible: Boolean get() = current != null || previous != null

    /**
     * Shows [next] (null: none). The old field gives way over [GlassMotion.CROSSFADE_MS]; the same field again is nothing at all.
     * Called from one coroutine at a time (it is a `collectLatest`'s body): a newer call cancels this one where it stands.
     */
    suspend fun show(next: GlassField?) {
        if (next === current || (next != null && current != null && next.argb.contentEquals(current!!.argb) && next.band == current!!.band && next.moves == current!!.moves)) return
        // B42-fix3 (item 7): there is no light and dark glass any more (the ink is always light), so every change between two fields is the same
        // 400 ms fade: no ground changes under the ink, and there is never a cut to take.
        previous = current
        current = next
        transitions++
        progress.snapTo(0f)
        progress.animateTo(1f, tween(GlassMotion.CROSSFADE_MS, easing = LinearEasing))
        previous = null
    }
}

/** The small field as a bitmap the GPU can filter, made once per field. */
private fun GlassField.toBitmap(): androidx.compose.ui.graphics.ImageBitmap =
    Bitmap.createBitmap(argb, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * Strength of what is laid over the field, toward the theme's own background (a deep, near-black colour: it darkens the glass and keeps its hue).
 *
 * B42-fix3 (item 7): a smaller veil in the player and on the lock screen ("less afraid of the cover"), a stronger one everywhere text sits on
 * the glass for long (pages), and the stronger still of a sheet. The three are related ([GlassSurfaces]): a panel is the veil that takes a page
 * to a sheet, so the mini player, the bottom bar and the queue that opens from them are one glass.
 */
object GlassScrim {
    /** Everywhere but the player: lists of text sit on a field this much quieter. */
    const val PAGE_ALPHA = 0.25f

    /** The full player and the lock screen: a gentle vertical gradient, strongest at the ends where the status bar and the controls are. B42-fix2 had 0.28. */
    const val EDGE_ALPHA = 0.10f

    /**
     * B42: a sheet's own copy of the glass. A sheet is a surface over a dimmed app, not a page: it has to read as a surface of its own and
     * its text sits on it for as long as it is open, so its veil of the theme's background is stronger than a page's ([PAGE_ALPHA]).
     */
    const val SHEET_ALPHA = 0.55f
}

/**
 * The glass behind the whole app, drawn **once**, at the root: the opaque base, the field (three layers that glide when [clock] is
 * advanced), and over them the scrim. Pages draw nothing of their own over it where the glass is on, so scrolling a list never touches
 * this layer.
 *
 * @param expansion how far the full player is open, 0..1, read in the draw phase: the page scrim gives way to the player's as it opens.
 * @param onFieldDrawn called each time a field layer's content is drawn (the tests count it: it must not move with a scroll).
 */
@Composable
fun CoverGlassBackdrop(
    crossfade: GlassCrossfade,
    clock: GlassClock,
    base: Color,
    expansion: () -> Float,
    modifier: Modifier = Modifier,
    onFieldDrawn: () -> Unit = {},
) {
    Box(modifier.fillMaxSize().drawBehind { drawRect(base) }) {
        GlassFieldStack(crossfade, clock, onFieldDrawn)
        GlassScrimLayer(base, expansion)
    }
}

/**
 * The field that is on screen and, while one gives way to another, the one leaving it: **the one transition** every copy of the glass
 * draws (the root's, the lock screen's, and (B42) each sheet's own still copy), so a copy is never a different glass from the app behind it
 * and a song change is one fade, in step, wherever it is seen.
 */
@Composable
internal fun GlassFieldStack(crossfade: GlassCrossfade, clock: GlassClock, onFieldDrawn: () -> Unit = {}) {
    crossfade.previous?.let { old ->
        GlassFieldLayers(old, clock, onFieldDrawn = onFieldDrawn, alpha = { if (crossfade.current == null) 1f - crossfade.blend else 1f })
    }
    // B41-fix1 (B5): the field that arrives fades in over what was there, **including the theme's own background when there was nothing**:
    // it used to be drawn at full strength whenever there was no previous field (the first cover, or a cover after a song with none), so the
    // 400 ms never showed. `blend` is 1 once a transition is over, so a settled field is at full strength as before.
    crossfade.current?.let { now ->
        GlassFieldLayers(now, clock, onFieldDrawn = onFieldDrawn, alpha = { crossfade.blend })
    }
}

/**
 * The three copies of one field. The first is never transformed (and so reads nothing that changes); the other two read the clock. A field that
 * does not move ([GlassField.moves] false: "From album art") is the first alone: nothing to glide, nothing drawn twice more.
 */
@Composable
fun GlassFieldLayers(
    field: GlassField,
    clock: GlassClock,
    modifier: Modifier = Modifier,
    onFieldDrawn: () -> Unit = {},
    alpha: () -> Float = { 1f },
) {
    val image = remember(field) { field.toBitmap() }
    // B56-7.1: on Android 13 and later a moving background is the live blobs over the still field; before it, the classic drifting layers (scaled by the motion setting)
    val live = field.moves && clock.motion.moves && LiveBlobs.available()
    Box(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha() }) {
        if (live && android.os.Build.VERSION.SDK_INT >= 33) {
            Box(Modifier.fillMaxSize().drawBehind { onFieldDrawn(); drawImage(image = image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low) })
            LiveBlobsLayer(field, clock)
            return@Box
        }
        for (index in 0 until (if (field.moves) GlassMotion.LAYERS else 1)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (index == 0) {
                            Modifier
                        } else {
                            Modifier.graphicsLayer {
                                val layer = GlassMotion.layer(clock.phase, index, travel = clock.motion.travel)
                                scaleX = if (layer.mirrored) -layer.scale else layer.scale
                                scaleY = layer.scale
                                rotationZ = layer.rotationDegrees
                                translationX = layer.translationX * size.width
                                translationY = layer.translationY * size.height
                                this.alpha = layer.alpha
                            }
                        }
                    )
                    .drawBehind {
                        onFieldDrawn()
                        drawImage(
                            image = image,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            filterQuality = FilterQuality.Low,
                        )
                    }
            )
        }
    }
}

/**
 * The scrim: for the pages a flat veil of the theme's background; for the player a gradient of it, fading in as the player opens. Both
 * are the theme's own background colour, so they pull the field toward a colour that already passes AA with the ink.
 */
@Composable
private fun GlassScrimLayer(base: Color, expansion: () -> Float) {
    val edge = remember(base) {
        Brush.verticalGradient(
            0f to base.copy(alpha = GlassScrim.EDGE_ALPHA),
            0.28f to base.copy(alpha = 0f),
            0.62f to base.copy(alpha = 0f),
            1f to base.copy(alpha = GlassScrim.EDGE_ALPHA),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = (1f - expansion()).coerceIn(0f, 1f) }
            .drawBehind { drawRect(base.copy(alpha = GlassScrim.PAGE_ALPHA)) }
    )
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = expansion().coerceIn(0f, 1f) }
            .drawBehind { drawRect(edge, topLeft = Offset.Zero, size = size) }
    )
}
