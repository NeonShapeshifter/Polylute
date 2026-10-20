package com.lostf1sh.pixelplayeross.ui.ds

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R

/**
 * The interface icon set.
 *
 * Every glyph here is the approved design's own vector data, transcribed path-for-path into a
 * vector drawable under `res/drawable/dap_ic_*.xml`. Nothing is redrawn by hand: the SVG path
 * syntax in the export (`M L H V C S A Z`, absolute and relative, `fill` and `stroke`, butt caps,
 * miter joins) is the same grammar Android's `VectorDrawable` parses, so the artwork carries over
 * exactly.
 *
 * Strokes are authored in the drawable's own viewport units, so they scale with the icon and stay
 * optically consistent at every size. They are never re-declared here.
 *
 * The drawables are single-colour by construction (all-stroke with a transparent fill, or
 * all-fill), which is what makes one [ColorFilter] tint correct for the whole set.
 */
enum class DapIconKind(@DrawableRes internal val resId: Int) {
    /** Back. 10 x 16. */
    Chevron(R.drawable.dap_ic_chevron_left),

    /** Row disclosure. 8 x 14. */
    ChevronRight(R.drawable.dap_ic_chevron_right),

    /** Player header collapse. 16 x 10. */
    ChevronDown(R.drawable.dap_ic_chevron_down),

    /** Dedicated Mode wake affordance. 16 x 10. */
    ChevronUp(R.drawable.dap_ic_chevron_up),

    /** Root navigation. 20 x 20. */
    Home(R.drawable.dap_ic_home),
    Search(R.drawable.dap_ic_search),
    Library(R.drawable.dap_ic_library),

    /** Queue. 18 x 14. */
    List(R.drawable.dap_ic_list),

    /** Overflow. 18 x 4. */
    More(R.drawable.dap_ic_more),

    /** Full-player transport. 22 x 18. */
    SkipPrevious(R.drawable.dap_ic_skip_previous),
    SkipNext(R.drawable.dap_ic_skip_next),

    /** Play, at the full-player size. 12 x 14. */
    Play(R.drawable.dap_ic_play),

    /** Pause inside the 72 dp play button. 18 x 20. */
    Pause(R.drawable.dap_ic_pause),

    /** Play at row and resume size. 12 x 14. */
    PlaySmall(R.drawable.dap_ic_play),

    /** Pause at row size. 12 x 14. */
    PauseSmall(R.drawable.dap_ic_pause_small),

    /** Pause in the mini-player. 14 x 16. */
    PauseMini(R.drawable.dap_ic_pause_mini),

    /** Skip in the mini-player. 16 x 14. */
    SkipNextMini(R.drawable.dap_ic_skip_next_mini),

    /** Like. 20 x 18. */
    Heart(R.drawable.dap_ic_heart),

    /**
     * Shuffle. 24 x 24.
     *
     * The one glyph not taken from the design export, which had no shuffle mark. It is the
     * product's own `ic_shortcut_shuffle` vector — the app already shipped it for the launcher
     * shortcut — reused rather than redrawn, so the crossed-arrows shape is the same one the app
     * has always used.
     */
    Shuffle(R.drawable.ic_shortcut_shuffle),

    /**
     * Repeat and repeat-one. 1 x 1. Like Shuffle, the product's own vectors (the player has always
     * drawn repeat with them), reused rather than redrawn. Repeat-one is a different glyph, not a
     * differently tinted one, so the mode survives without colour.
     */
    Repeat(R.drawable.rounded_repeat_24),
    RepeatOne(R.drawable.rounded_repeat_one_24),

    /** Queue reorder. 14 x 10. */
    DragHandle(R.drawable.dap_ic_drag_handle),

    /** The no-artwork placeholder. 12 x 14. */
    Note(R.drawable.dap_ic_note),

    /**
     * Settings. 20 x 20.
     *
     * The standard gear, drawn at this set's own stroke weight rather than imported as a filled
     * Material glyph: the whole point of the set is that one line weight carries every surface, and
     * a filled gear beside stroked chevrons reads as a different product.
     */
    Gear(R.drawable.dap_ic_gear),

    /** Media volume, by level. 20 x 20. */
    VolumeHigh(R.drawable.dap_ic_volume_high),
    VolumeLow(R.drawable.dap_ic_volume_low),
    VolumeMute(R.drawable.dap_ic_volume_mute),
}

/**
 * The intrinsic aspect of each glyph, taken from its viewport.
 *
 * The drawable's own `width`/`height` already encode this, but keeping it here lets [DapIcon] lay
 * out a non-square glyph in a square slot without measuring, and keeps [size] meaning "the longer
 * edge" at every call site.
 */
private fun aspectOf(kind: DapIconKind): Float = when (kind) {
    DapIconKind.Chevron -> 10f / 16f
    DapIconKind.ChevronRight -> 8f / 14f
    DapIconKind.ChevronDown, DapIconKind.ChevronUp -> 16f / 10f
    DapIconKind.Home, DapIconKind.Search, DapIconKind.Library -> 1f
    DapIconKind.List -> 18f / 14f
    DapIconKind.More -> 18f / 4f
    DapIconKind.SkipPrevious, DapIconKind.SkipNext -> 22f / 18f
    DapIconKind.Play, DapIconKind.PlaySmall -> 12f / 14f
    DapIconKind.Pause -> 18f / 20f
    DapIconKind.PauseSmall -> 12f / 14f
    DapIconKind.PauseMini -> 14f / 16f
    DapIconKind.SkipNextMini -> 16f / 14f
    DapIconKind.Heart -> 20f / 18f
    DapIconKind.Shuffle, DapIconKind.Repeat, DapIconKind.RepeatOne -> 1f
    DapIconKind.DragHandle -> 14f / 10f
    DapIconKind.Note -> 12f / 14f
    DapIconKind.Gear,
    DapIconKind.VolumeHigh,
    DapIconKind.VolumeLow,
    DapIconKind.VolumeMute -> 1f
}

/**
 * Painters for the few drawables that repeat on every row of a list, created once for the app.
 *
 * `painterResource` on a vector drawable builds a `VectorPainter`, which composes its own
 * sub-composition. A song row asked for three of them (the "more" glyph and the artwork's
 * placeholder and error images), so every row that scrolled in built three extra compositions on
 * the main thread (B13). Rows now share these. They live above every list, so no row's disposal can
 * take one down.
 */
@Stable
class DapSharedPainters internal constructor(private val byResource: Map<Int, Painter>) {
    operator fun get(@DrawableRes resId: Int): Painter? = byResource[resId]
}

/** Null outside [ProvideDapSharedPainters] (previews, tests): callers then build their own painter. */
val LocalDapSharedPainters = compositionLocalOf<DapSharedPainters?> { null }

@Composable
fun ProvideDapSharedPainters(content: @Composable () -> Unit) {
    val more = painterResource(DapIconKind.More.resId)
    val placeholder = painterResource(R.drawable.ic_music_placeholder)
    val shared = remember(more, placeholder) {
        DapSharedPainters(
            mapOf(
                DapIconKind.More.resId to more,
                R.drawable.ic_music_placeholder to placeholder,
            )
        )
    }
    CompositionLocalProvider(LocalDapSharedPainters provides shared, content = content)
}

/**
 * Draws a glyph.
 *
 * [size] is the longer edge. The other edge follows the glyph's own aspect, so a 22 x 18 transport
 * arrow is never stretched into a square, and nothing is ever clipped by a box that does not match
 * its viewport.
 */
@Composable
fun DapIcon(
    kind: DapIconKind,
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val aspect = aspectOf(kind)
    val box = if (aspect >= 1f) {
        Modifier.size(width = size, height = size / aspect)
    } else {
        Modifier.size(width = size * aspect, height = size)
    }
    Image(
        painter = LocalDapSharedPainters.current?.get(kind.resId) ?: painterResource(kind.resId),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.then(box),
    )
}

/** Draws a glyph centred in a slot of its own, for callers that already own the touch target. */
@Composable
fun DapIconBox(
    kind: DapIconKind,
    tint: Color,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Box(
        modifier,
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        DapIcon(kind = kind, tint = tint, size = iconSize)
    }
}
