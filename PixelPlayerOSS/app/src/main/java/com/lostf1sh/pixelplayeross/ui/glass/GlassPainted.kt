package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.lostf1sh.pixelplayeross.ui.ds.AlbumArtColors
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme

/**
 * What the app is painted with: the theme, and (while a cover backdrop is on screen) the cover's look with the glass's ink and see-through
 * panels on it.
 *
 * B76: [coverScheme] holds the debounced `ColorRoles` scheme in cover modes (`ALBUM_ART` / `COVER_GLASS`)
 * so containers, surfaces and tonal controls match Queue tinting without altering Theme mode or
 * invalidating the root `MaterialTheme`. [coverAccent] holds the un-washed cover accent for opaque
 * tinted menus (`DapMenu`).
 */
@Immutable
class GlassPainted(
    val baseTheme: DapBaseTheme,
    val useDark: Boolean,
    val look: AlbumArtColors.Look?,
    val glassShown: Boolean,
    val coverScheme: ColorScheme? = null,
    val coverAccent: Color? = null,
)

@Composable
fun rememberGlassPainted(
    base: DapBaseTheme,
    albumArtLook: AlbumArtColors.Look?,
    glassLook: GlassLook?,
    scene: CoverGlassScene,
    coverScheme: ColorScheme? = null,
): GlassPainted {
    val glassShown = scene.visible && glassLook != null
    val look = if (glassShown && glassLook != null && albumArtLook != null) {
        albumArtLook.copy(palette = albumArtLook.palette.onGlass(glassLook), accent = glassLook.ink.accent)
    } else {
        albumArtLook
    }
    return GlassPainted(
        baseTheme = base,
        useDark = !base.palette.isLight,
        look = look,
        glassShown = glassShown,
        coverScheme = coverScheme.takeIf { albumArtLook != null },
        coverAccent = albumArtLook?.accent,
    )
}
