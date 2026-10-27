package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * B42: a sheet's own copy of the glass.
 *
 * A bottom sheet (and the queue over the full player) is a surface of its own over the app. The root's glass cannot be behind it (a sheet is
 * its own window over a dimmed app, and the queue sits over a glass that moves), so each carries **its own still copy**, the way the DAP lock
 * screen carries its copy (B41): the same field, the same cover, the same transition, drawn by the same code ([GlassFieldStack]), at one
 * phase, under a stronger scrim ([GlassScrim.SHEET_ALPHA]).
 *
 * - **The same field.** The copy reads the scene's [GlassCrossfade]; it never makes a field. Opening or closing a sheet builds nothing (the
 *   field is in the cache the root made it in), and a song change is the root's own fade, in the same frames.
 * - **Still.** The clock it is drawn with is a frozen copy of the root's, taken when the sheet opens ([GlassClock.frozenCopy]): the glass at
 *   that instant, so there is no jump at the open, and nothing after it. Nothing in it reads a clock that moves; the root's glass can step on
 *   every frame of the display behind it and the sheet's is not recomposed, not redrawn, not even invalidated.
 * - **Readable.** The ink is the glass's ([GlassInk], chosen against the lightest and darkest area of the band with no scrim at all); the veil
 *   pulls the field toward the theme's own background, the colour that ink already reads on, which takes the area that limits the ink (the
 *   lightest on a dark theme, the darkest on Light) a step further from it. `SheetGlassContrastTest` measures every ink on the glass a sheet
 *   shows, at every phase it can freeze at, and the sheet renders measure the text on the sheets' own pixels.
 */
object SheetGlass {
    /**
     * How far a sheet's glass reaches beyond the content it sits behind, above and below. The platform's sheet surface puts a drag handle above
     * its content and window insets around it; those are inside the surface and they are the sheet's too, so the glass is drawn under them and
     * the surface's own clip (its shape) cuts it to the sheet. Larger than any handle or inset; what is beyond the surface is never seen.
     */
    val REACH: Dp = 128.dp
}

/**
 * The frozen clock a sheet draws its copy with: this scene's clock at the instant the sheet was composed (it opened). Null without glass.
 * Keyed on the scene's clock, which lives as long as the app does, so a theme change (a new look, a new scene) does not re-take the phase
 * under a sheet that is open.
 */
@Composable
fun rememberStillGlassClock(scene: CoverGlassScene?): GlassClock? = remember(scene?.clock) { scene?.clock?.frozenCopy() }

/**
 * A sheet's container: nothing while it carries its own glass, [color] otherwise (a theme or a cover colour decides the surface, as before).
 * [scene] is the one the root provides ([LocalCoverGlass]); it is null in the other two options of "App colours", and there is nothing to do.
 */
fun glassSheetContainer(color: Color, scene: CoverGlassScene?): Color = if (scene != null) Color.Transparent else color

/**
 * The copy of the glass, filling its parent: the opaque base, the field (the same stack the root draws), and the veil.
 *
 * @param clock a frozen clock ([rememberStillGlassClock]); nothing here advances it.
 * @param scrimAlpha the veil of the theme's background over the field.
 * @param visibility how much of it shows, 0..1, read in the draw phase (the queue brings its glass in as it opens); null: all of it.
 */
@Composable
fun StillGlassBackdrop(
    scene: CoverGlassScene,
    clock: GlassClock,
    modifier: Modifier = Modifier,
    scrimAlpha: Float = GlassScrim.SHEET_ALPHA,
    visibility: (() -> Float)? = null,
    onFieldDrawn: () -> Unit = {},
) {
    val base = scene.base
    Box(
        modifier
            .fillMaxSize()
            // the layers are larger than the screen they cover (they glide over it): a copy never paints outside what it was given
            .clipToBounds()
            .then(if (visibility != null) Modifier.graphicsLayer { alpha = visibility().coerceIn(0f, 1f) } else Modifier)
            .drawBehind { drawRect(base) }
    ) {
        GlassFieldStack(scene.crossfade, clock, onFieldDrawn)
        Box(Modifier.fillMaxSize().drawBehind { drawRect(base.copy(alpha = scrimAlpha)) })
    }
}

/**
 * The window of a sheet that is a window of its own and fills it (a full-screen dialog): its copy of the glass, behind whatever the window
 * draws, when there is glass; nothing otherwise. Call it first, before the surface that is transparent over it.
 */
@Composable
fun GlassWindowBackdrop(scene: CoverGlassScene?, modifier: Modifier = Modifier) {
    val clock = rememberStillGlassClock(scene)
    if (scene != null && clock != null) StillGlassBackdrop(scene, clock, modifier)
}

/**
 * The body of a sheet whose surface is the platform's (a `ModalBottomSheet`): [content] in a column, over the sheet's copy of the glass when
 * [scene] is not null. The structure is the same with and without glass, so a cover that goes away while a sheet is open takes the glass
 * out from under it without rebuilding what the sheet holds (a scroll position, a half-typed search).
 *
 * The glass reaches [SheetGlass.REACH] beyond the content on both sides (the platform's surface puts its drag handle above the content, and
 * insets around it), and the body is drawn *before* the handle (`zIndex`), so the handle sits on the glass it is part of. The surface's own
 * clip cuts the glass to the sheet's shape.
 */
@Composable
fun StillGlassSheetBody(scene: CoverGlassScene?, clock: GlassClock?, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().zIndex(-1f)) {
        if (scene != null && clock != null) {
            StillGlassBackdrop(scene, clock, Modifier.matchParentSize().reaching(SheetGlass.REACH, SheetGlass.REACH))
        }
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** Measures with [top] and [bottom] more room than the parent gave, and reports the parent's size: what is drawn there is past its edges. */
private fun Modifier.reaching(top: Dp, bottom: Dp): Modifier = layout { measurable, constraints ->
    val t = top.roundToPx()
    val b = bottom.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight + t + b))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, -t) }
}
