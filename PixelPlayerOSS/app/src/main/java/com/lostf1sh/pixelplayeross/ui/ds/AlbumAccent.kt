package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * The accent a cover gives a surface (B29-L11).
 *
 * The app has one accent, the user's ([DapAccent]), and a few widgets wear it for "active": the shuffle and repeat dots, the heart, the
 * current row, the equaliser bars. On the full player and the lock screen that accent is the cover's instead, so the colour follows
 * the album the way it did in PixelPlayer, and **nothing else about the surface changes**: it is a substitution of one colour, not a new
 * look. The cover's scheme is the one the theme holder already extracts, once per cover, for every consumer; this only reads its primary.
 *
 * Pure. [fromScheme] gives null when there is no scheme (no cover, the player colour set to neutral, nothing extracted yet), and the caller
 * then keeps the app's accent. A colour that cannot be read on the surface (a pale cover on the light palette, a dark one on graphite)
 * is lifted toward white or black until it can, and given up on (null: the app's accent) if eight small steps do not get it to
 * [MIN_CONTRAST].
 */
object AlbumAccent {
    /** WCAG's floor for large text and graphical marks: what a dot, a bar or a glyph needs to be seen against its surface. */
    const val MIN_CONTRAST = 3f

    fun fromScheme(scheme: ColorScheme?, surface: Color): Color? =
        scheme?.primary?.let { readableOn(it, surface) }

    fun readableOn(color: Color, surface: Color): Color? {
        if (contrast(color, surface) >= MIN_CONTRAST) return color
        val toward = if (surface.luminance() < 0.5f) Color.White else Color.Black
        var candidate = color
        repeat(STEPS) {
            candidate = lerp(candidate, toward, STEP)
            if (contrast(candidate, surface) >= MIN_CONTRAST) return candidate
        }
        return null
    }

    /** WCAG relative contrast, 1 (the same colour) to 21 (black on white). */
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private const val STEPS = 8
    private const val STEP = 0.12f
}
