package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * B56 phase 3.3: the synced lyrics' highlight, in one place so the owner can tune it.
 *
 * The line being sung stands out over any cover, light or dark, by a soft tonal backdrop of its own highlight ink ([ActiveBackdropAlpha] of it over the page) behind
 * the line. The lines already sung wear the page's dim ink (0.45 of the content colour, or the glass's tertiary ink) and the lines still to come a step stronger
 * (0.7, or the glass's secondary ink): what is ahead stays readable, what has been sung recedes.
 */
object DapLyricsHighlight {
    /** The highlight ink's strength behind the active line. 0 turns the backdrop off. */
    const val ActiveBackdropAlpha = 0.16f

    /** The backdrop's corner: the lines' own touch shape. */
    val ActiveBackdropRadius: Dp = 12.dp

    /** How far the backdrop reaches past the line's text on each side, so the words do not touch its edge. */
    val ActiveBackdropBleed: Dp = 8.dp
}

/** B57 2.7: the dimming behind the lyrics while the page is immersive, in one place. */
object DapLyricsImmersive {
    /** How dark the page gets behind the text. 0 turns it off. */
    const val DimAlpha = 0.28f

    /** How long it takes to dim and to clear. */
    const val DimMs = 350
}
