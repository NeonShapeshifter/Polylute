package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.ui.text.TextStyle

/**
 * B58 4 (#78): the lyrics text size the listener picks in the lyrics menu's "Reading" section, kept for the app. Four steps, [MEDIUM] being the size the page always had.
 *
 * **Immersive mode grows the chosen size by [IMMERSIVE_GROWTH]** (a quarter; one constant, so that trying 30 % is a one-character change): a small choice stays small in immersive
 * mode. Before, immersive was a fixed 1.4 times one style, which was too much for everybody and left no way to make the text smaller.
 *
 * The two lines under the cover on the player page are not this style (they are a fixed two-line block that shrinks to fit, `PlayerLyricsLines`) and do not follow it.
 */
enum class LyricsTextSize(val key: String, val factor: Float) {
    SMALL("small", 0.85f),
    MEDIUM("medium", 1f),
    LARGE("large", 1.15f),
    EXTRA_LARGE("xlarge", 1.3f);

    /** What the page's text is multiplied by, and what the immersive view's is: the chosen size, and the chosen size plus [IMMERSIVE_GROWTH]. */
    fun scale(immersive: Boolean): Float = if (immersive) factor * IMMERSIVE_GROWTH else factor

    companion object {
        val DEFAULT = MEDIUM

        /** Immersive mode shows the lyrics this much bigger than the chosen size (1.25 = 25 % bigger). */
        const val IMMERSIVE_GROWTH = 1.25f

        fun fromKey(key: String?): LyricsTextSize = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** [this] with its size and its line height multiplied by [scale]. */
internal fun TextStyle.scaledBy(scale: Float): TextStyle = if (scale == 1f) this else copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
