package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lostf1sh.pixelplayeross.R

/**
 * Typography for the DAP interface.
 *
 * Two families, seven sizes. Mono is not decoration: it marks a value the system observed.
 * Prose never uses it.
 *
 * The split is by *what the text is*, not by how important it is. Mono is not a hierarchy level —
 * a heading is not "more technical" than a body line. Anything the app says in its own voice
 * (titles, rows, actions, descriptions, section labels) is Manrope; anything the app reports back
 * from the system (a rate, a codec, a route, a state word, a count of tracks) is mono.
 *
 * Both families are bundled as variable fonts under the SIL Open Font License. See
 * `THIRD_PARTY_NOTICES.md` and `assets/licenses/OFL.txt`.
 */
@OptIn(ExperimentalTextApi::class)
private fun variableFamily(resId: Int, weights: List<FontWeight>): FontFamily = FontFamily(
    weights.map { weight ->
        Font(
            resId = resId,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
        )
    }
)

/** Manrope — prose, titles, rows, labels. */
val Manrope = variableFamily(
    R.font.manrope_variable,
    listOf(
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
        FontWeight.Bold,
    )
)

/** JetBrains Mono — every technical value the system observed. */
val JetBrainsMono = variableFamily(
    R.font.jetbrains_mono_variable,
    listOf(
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
    )
)

/**
 * The type roles. Names match the design system panel so a screen reads like the reference.
 */
object DapType {

    /** Title 26 / 600 — screen titles, profile names. */
    val Title = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.02).em,
    )

    /**
     * Display 64 / 500, tracked +2% — the DAP lock screen's clock.
     *
     * Manrope, not mono: the time is the app speaking, and the design (B22) sets it as the display role. [Clock] (mono 88)
     * stays the Dedicated Mode face.
     */
    val Display = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 64.sp,
        lineHeight = 68.sp,
        letterSpacing = 0.02.em,
    )

    /** Subtitle 18 / 400 — the artist under the track's title in the full player. */
    val Subtitle = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    )

    /** Body large 16 / 400 — the date under the clock, the artist under a title. */
    val BodyLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    /** Heading 22 / 600 — now playing title, sheet titles. */
    val Heading = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.01).em,
    )

    /** Heading 17 / 600 — compact track title on the Details sibling. */
    val HeadingSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.01).em,
    )

    /** Row 16 / 600 — list primary, emphasised. */
    val Row = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    )

    /** Row 15 / 500 — list primary. */
    val RowSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )

    /** Row 14 / 500 — media row title. */
    val MediaRow = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    )

    /** Row 14 / 500 — settings row label. */
    val SettingLabel = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )

    /** Body 14 / 400 text-2 — the default descriptive voice. */
    val Body = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /** Caption 13 — one step down from body. */
    val Caption = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )

    /** Caption 13 / 500 — trailing value in a settings row. */
    val CaptionMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )

    /** Note 12 text-3 — explanatory footnotes under a group. */
    val Note = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )

    /** Action 14 / 500 — text-only actions such as Rename, Forget, Undo. */
    val Action = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    )

    /** Button 14 / 600 — primary and secondary button labels. */
    val Button = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    )

    /** Nav label 11 / 500 — bottom navigation. */
    val NavLabel = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )

    /**
     * Label 11 / 500, tracked 10% — section headers.
     *
     * Manrope, not mono. A section header names a group of things — "Recently played", "Your
     * library" — and that is prose, not a measurement. Setting it in mono put the app's navigation
     * and its numbers in the same voice, which is what made Home read as an arbitrary mixture of
     * white proportional text and small grey technical text. The tracked-uppercase treatment is
     * kept, because it is what marks the label as a label.
     */
    val SectionHeader = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.1.em,
    )

    /** Value 13 mono / 500 — every technical value. */
    val Value = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** Value 12 mono / 400 — dense technical values, seek times. */
    val ValueSmall = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** Readout 12 mono / 500 — the signal-path line at the foot of every player sibling. */
    val Readout = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** State word 11 mono / 500, tracked 10% — the word beside a state mark. */
    val StateWord = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.1.em,
    )

    /** Player header tab 13 mono / 500 — Artwork · Lyrics · Details. */
    val PlayerTab = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    )

    /** Clock 88 mono / 400 — the Dedicated Mode face. */
    val Clock = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        fontSize = 88.sp,
        lineHeight = 88.sp,
        letterSpacing = (-0.04).em,
    )

    /** Axis 10 mono / 400 — EQ frequency and dB axis labels. */
    val Axis = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 13.sp,
    )

    /** Lyric line, current. */
    val LyricCurrent = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.01).em,
    )

    /** Lyric line, adjacent. */
    val LyricNear = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.01).em,
    )

    /** Lyric line, far. */
    val LyricFar = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.01).em,
    )

    /** Plain (unsynced) lyric line — prose, not a headline. */
    val LyricPlain = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    )
}
