package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.lostf1sh.pixelplayeross.ui.ds.DapPalette

/**
 * B42-fix3 (item 8): the surfaces that sit on the glass (a card, a tile, a bar, a header's panel) are **frosted glass**: more of the same glass,
 * under a stronger veil, not the theme's flat grey.
 *
 * B42 made them see-through versions of the theme's surface colour at 72 %, which on a dark theme is a neutral grey that hides the glass: the
 * HONOR showed black slabs (about RGB 22, 26, 25) with the glass leaking between them. A surface here is **nothing but a veil**: the theme's own
 * background colour over whatever glass is behind it, so the glass shows through it with its own hue and the veil only makes it deeper, and the
 * text on it more readable than on the page.
 *
 * One veil, one place. How strong it is is *derived*, not chosen: a page's own veil is [GlassScrim.PAGE_ALPHA] over the field, a sheet's own copy
 * of the glass has [GlassScrim.SHEET_ALPHA], and a panel is the veil that takes a page to a sheet ([PANEL_ALPHA]), so the mini player and the
 * bottom bar are exactly the glass of the queue sheet that opens from them: no seam. It is a flat colour with an alpha, the same for every
 * song: nothing here changes with the cover, nothing is recomposed when it does (the glass behind changes; this is drawn over it), and no field
 * is drawn per card (one backdrop for the whole screen, one veil per surface).
 */
@Immutable
data class GlassSurfaces(val panel: Color, val panelRaised: Color) {
    companion object {
        /** The extra veil that takes the page's glass ([GlassScrim.PAGE_ALPHA]) to a sheet's ([GlassScrim.SHEET_ALPHA]): (1 - page)(1 - panel) = 1 - sheet. */
        val PANEL_ALPHA: Float = 1f - (1f - GlassScrim.SHEET_ALPHA) / (1f - GlassScrim.PAGE_ALPHA)

        /** What a raised control (a secondary button, an input) adds over a panel: a little light, so it lifts without turning grey. */
        const val RAISED_LIGHT_ALPHA = 0.10f

        fun of(palette: DapPalette): GlassSurfaces = GlassSurfaces(
            panel = palette.background.copy(alpha = 1f).copy(alpha = PANEL_ALPHA),
            panelRaised = Color.White.copy(alpha = RAISED_LIGHT_ALPHA),
        )
    }
}
