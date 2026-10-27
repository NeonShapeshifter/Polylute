package com.lostf1sh.pixelplayeross.ui.theme

import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.data.preferences.AppThemeMode
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme

/**
 * The one "Theme" choice the user makes.
 *
 * There used to be two rows for it. "App theme" (light / dark / system) and "Base theme"
 * (Graphite / Black / Light, now also Gray and Navy) looked like the same question, but only the base theme painted the
 * app; the app-theme row only picked which half of an artwork-derived scheme the album, artist and
 * genre headers used. The two could contradict each other, and "follow system" never made the app
 * light. This is the single answer both used to half-give.
 *
 * It is stored in the two preferences that already exist, so no data format changes:
 * - Graphite / Black / Gray / Navy (the last two are B40-F1) → that base theme, app theme mode DARK.
 * - Light → base theme Light, app theme mode LIGHT.
 * - Automatic → base theme Light *and* app theme mode FOLLOW_SYSTEM: Light by day, Graphite by
 *   night.
 *
 * Automatic is deliberately the Light + follow-system pair, not "any pair with follow-system".
 * FOLLOW_SYSTEM is the app-theme default, so every install that never touched these rows holds
 * (Graphite, FOLLOW_SYSTEM) — and Graphite is what those installs have always drawn and must keep
 * drawing (see DapBaseThemeTest). Reading follow-system alone as Automatic would have turned them
 * light on a light system.
 */
enum class DapAppearanceChoice(val key: String) {
    Graphite("graphite"),
    Black("black"),
    Light("light"),
    Gray("gray"),
    Navy("navy"),
    Automatic("automatic");

    companion object {
        fun fromKey(key: String?): DapAppearanceChoice = entries.firstOrNull { it.key == key } ?: Graphite
    }
}

/** Which of the four choices the stored pair expresses. */
fun appearanceChoiceOf(appThemeMode: String, storedBaseTheme: DapBaseTheme): DapAppearanceChoice = when {
    isAutomatic(appThemeMode, storedBaseTheme) -> DapAppearanceChoice.Automatic
    storedBaseTheme == DapBaseTheme.Black -> DapAppearanceChoice.Black
    storedBaseTheme == DapBaseTheme.Light -> DapAppearanceChoice.Light
    storedBaseTheme == DapBaseTheme.Gray -> DapAppearanceChoice.Gray
    storedBaseTheme == DapBaseTheme.Navy -> DapAppearanceChoice.Navy
    else -> DapAppearanceChoice.Graphite
}

private fun isAutomatic(appThemeMode: String, storedBaseTheme: DapBaseTheme) =
    appThemeMode == AppThemeMode.FOLLOW_SYSTEM && storedBaseTheme == DapBaseTheme.Light

/**
 * The base theme the app actually draws with.
 *
 * Automatic follows the system: Light by day, Graphite by night. Every other pair draws the stored
 * base theme, whatever the legacy app-theme value says, so the two can no longer disagree.
 */
fun resolveBaseTheme(
    appThemeMode: String,
    storedBaseTheme: DapBaseTheme,
    systemDark: Boolean,
): DapBaseTheme = when {
    !isAutomatic(appThemeMode, storedBaseTheme) -> storedBaseTheme
    systemDark -> DapBaseTheme.Graphite
    else -> DapBaseTheme.Light
}

/**
 * B41-fix1 (B1): what "Theme" meant while the cover decides the colours: Dark, Light or System, from a store of its own.
 *
 * **B42-fix3 (item 7): no longer offered, and no longer read.** In the cover modes ("From album art" and "Cover glass") the cover decides the
 * lightness as well as the colours, and the ink is always light, so the app is drawn on Graphite whatever this says (see
 * [resolveDrawnBaseTheme]). The preference key, its store and what was saved there are **kept** (nothing is deleted or migrated, and a
 * backup made before still restores it); nothing reads it any more.
 */
object CoverThemeChoice {
    const val DARK = "dark"
    const val LIGHT = "light"
    const val SYSTEM = "system"

    /** A recognised value, or null (nothing stored, or something a newer build wrote). */
    fun normalize(value: String?): String? = value?.takeIf { it == DARK || it == LIGHT || it == SYSTEM }

    /**
     * What the stored pair says, for a listener who has not chosen here yet: an install that is already on "Cover glass" keeps the light or
     * dark it has been seeing. Light is [LIGHT], Automatic (Light with the system) is [SYSTEM], every other theme is dark.
     */
    fun derivedFrom(appThemeMode: String, storedBaseTheme: DapBaseTheme): String = when (appearanceChoiceOf(appThemeMode, storedBaseTheme)) {
        DapAppearanceChoice.Light -> LIGHT
        DapAppearanceChoice.Automatic -> SYSTEM
        else -> DARK
    }
}

/**
 * The base theme the app draws with: the stored theme as [resolveBaseTheme] reads it, except while the cover decides the colours, when it is
 * **Graphite**, always (B42-fix3, item 7: the cover decides the lightness too, the ink is light, and the Theme row is not shown there).
 * [colorSource] is an [AppColorSource].
 */
fun resolveDrawnBaseTheme(
    colorSource: String,
    appThemeMode: String,
    storedBaseTheme: DapBaseTheme,
    systemDark: Boolean,
): DapBaseTheme {
    if (!AppColorSource.takesColoursFromCover(colorSource)) return resolveBaseTheme(appThemeMode, storedBaseTheme, systemDark)
    return DapBaseTheme.Graphite
}

/** The pair to store for a choice: (base theme, app theme mode). */
fun storedPairFor(choice: DapAppearanceChoice): Pair<DapBaseTheme, String> = when (choice) {
    DapAppearanceChoice.Graphite -> DapBaseTheme.Graphite to AppThemeMode.DARK
    DapAppearanceChoice.Black -> DapBaseTheme.Black to AppThemeMode.DARK
    DapAppearanceChoice.Light -> DapBaseTheme.Light to AppThemeMode.LIGHT
    DapAppearanceChoice.Gray -> DapBaseTheme.Gray to AppThemeMode.DARK
    DapAppearanceChoice.Navy -> DapBaseTheme.Navy to AppThemeMode.DARK
    DapAppearanceChoice.Automatic -> DapBaseTheme.Light to AppThemeMode.FOLLOW_SYSTEM
}
