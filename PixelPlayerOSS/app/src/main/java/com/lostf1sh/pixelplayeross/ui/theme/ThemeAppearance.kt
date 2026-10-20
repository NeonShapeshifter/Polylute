package com.lostf1sh.pixelplayeross.ui.theme

import com.lostf1sh.pixelplayeross.data.preferences.AppThemeMode
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme

/**
 * The one "Theme" choice the user makes.
 *
 * There used to be two rows for it. "App theme" (light / dark / system) and "Base theme"
 * (Graphite / Black / Light) looked like the same question, but only the base theme painted the
 * app; the app-theme row only picked which half of an artwork-derived scheme the album, artist and
 * genre headers used. The two could contradict each other, and "follow system" never made the app
 * light. This is the single answer both used to half-give.
 *
 * It is stored in the two preferences that already exist, so no data format changes:
 * - Graphite / Black → that base theme, app theme mode DARK.
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

/** The pair to store for a choice: (base theme, app theme mode). */
fun storedPairFor(choice: DapAppearanceChoice): Pair<DapBaseTheme, String> = when (choice) {
    DapAppearanceChoice.Graphite -> DapBaseTheme.Graphite to AppThemeMode.DARK
    DapAppearanceChoice.Black -> DapBaseTheme.Black to AppThemeMode.DARK
    DapAppearanceChoice.Light -> DapBaseTheme.Light to AppThemeMode.LIGHT
    DapAppearanceChoice.Automatic -> DapBaseTheme.Light to AppThemeMode.FOLLOW_SYSTEM
}
