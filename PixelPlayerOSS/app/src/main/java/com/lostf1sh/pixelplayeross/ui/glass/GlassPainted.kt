package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.lostf1sh.pixelplayeross.ui.ds.AlbumArtColors
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme

/**
 * What the app is painted with: the theme, and (while a cover backdrop is on screen) the cover's look with the glass's ink and see-through
 * panels on it.
 *
 * B41-fix1 (B3) held this together with the field on screen, because a switch between a dark theme and a light one changed the ink under a
 * field that was still the other lightness. B42-fix3 (item 7) has no such switch: in the cover modes the ink is always light and the theme
 * under it is always Graphite, so what is painted is decided at once, and **what it is does not change with the song**: the palette is the
 * same for every cover (the glass behind it and the accent are what change), so a song change hands the theme the same palette and a new accent.
 *
 * @property baseTheme the theme the app is drawn with.
 * @property useDark whether it is a dark one.
 * @property look the palette and accent to draw, with the glass's ink and see-through panels when a field is on screen; null when the
 *   cover decides nothing (the theme's own palette).
 * @property glassShown the glass is on screen (the lock screen and the root draw it).
 */
@Immutable
class GlassPainted(val baseTheme: DapBaseTheme, val useDark: Boolean, val look: AlbumArtColors.Look?, val glassShown: Boolean)

/**
 * The painting for this composition: the cover's look with the glass's ink and panels on it ([onGlass]) while a field is on screen.
 *
 * @param base the theme asked for.
 * @param albumArtLook the look the cover makes of [base]'s palette, or null with "Theme and accent".
 * @param glassLook the band and ink for [albumArtLook], or null with "Theme and accent".
 */
@Composable
fun rememberGlassPainted(
    base: DapBaseTheme,
    albumArtLook: AlbumArtColors.Look?,
    glassLook: GlassLook?,
    scene: CoverGlassScene,
): GlassPainted {
    val glassShown = scene.visible && glassLook != null
    val look = if (glassShown && glassLook != null && albumArtLook != null) {
        albumArtLook.copy(palette = albumArtLook.palette.onGlass(glassLook), accent = glassLook.ink.accent)
    } else {
        albumArtLook
    }
    return GlassPainted(base, !base.palette.isLight, look, glassShown)
}
