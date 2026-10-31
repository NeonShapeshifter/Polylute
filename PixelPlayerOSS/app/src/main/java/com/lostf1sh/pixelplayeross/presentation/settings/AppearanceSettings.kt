package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.rounded.Abc
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.MainActivity
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.ANIMATED_LYRICS_BLUR_STRENGTH_MAX
import com.lostf1sh.pixelplayeross.data.preferences.ANIMATED_LYRICS_BLUR_STRENGTH_MIN
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtPaletteStyle
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.ImmersiveLyrics
import com.lostf1sh.pixelplayeross.data.preferences.AppLanguage
import com.lostf1sh.pixelplayeross.data.preferences.LaunchTab
import com.lostf1sh.pixelplayeross.data.preferences.LibraryNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreference
import com.lostf1sh.pixelplayeross.presentation.screens.SettingsItem
import com.lostf1sh.pixelplayeross.presentation.screens.SliderSettingsItem
import com.lostf1sh.pixelplayeross.presentation.screens.SwitchSettingItem
import com.lostf1sh.pixelplayeross.presentation.screens.ThemeSelectorItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapChevron
import com.lostf1sh.pixelplayeross.ui.theme.CoverThemeChoice
import com.lostf1sh.pixelplayeross.ui.theme.DapAppearanceChoice
import com.lostf1sh.pixelplayeross.ui.theme.appearanceChoiceOf
import java.util.Locale

/** What the Appearance page draws. */
@Immutable
data class AppearanceSettingsState(
    val appLanguageTag: String,
    val appThemeMode: String,
    val baseTheme: DapBaseTheme,
    val accent: DapAccent,
    /** B40-F1: [AppColorSource.THEME], [AppColorSource.ALBUM_ART], or (B41) [AppColorSource.COVER_GLASS]. */
    val appColorSource: String,
    /** B41-fix1 (B1): [CoverThemeChoice]: what "Theme" says while the cover decides the colours. */
    val coverThemeChoice: String = CoverThemeChoice.DARK,
    val playerThemePreference: String,
    val showPlayerFileInfo: Boolean,
    val albumArtPaletteStyle: AlbumArtPaletteStyle,
    val albumArtQuality: AlbumArtQuality,
    val launchTab: String,
    val libraryNavigationMode: String,
    /** B41 (Player): the cover glass drifts in the full player and on the lock screen. */
    val coverGlassMoving: Boolean = true,
    /** B41 (Player): two lines of synced lyrics under the cover. */
    val lyricsUnderCover: Boolean = true,
    val showBookmarkButton: Boolean = true,
    // B41 (Lyrics): the whole reading and effects part of what Settings, Lyrics used to hold; the same preferences.
    val immersiveLyricsEnabled: Boolean = false,
    val immersiveLyricsTimeout: Long = 4000L,
    val lyricsAlignment: String = "left",
    val showLyricsTranslation: Boolean = true,
    val showLyricsRomanization: Boolean = true,
    val useAnimatedLyrics: Boolean = false,
    val animatedLyricsBlurEnabled: Boolean = true,
    val animatedLyricsBlurStrength: Float = 2.5f,
    /** B41 (Lock screen): "DAP lock screen", moved here from Behaviour. */
    val dapLockScreenEnabled: Boolean = false,
) {
    companion object {
        fun from(ui: SettingsUiState) = AppearanceSettingsState(
            appLanguageTag = ui.appLanguageTag,
            appThemeMode = ui.appThemeMode,
            baseTheme = ui.baseTheme,
            accent = ui.accent,
            appColorSource = ui.appColorSource,
            coverThemeChoice = ui.coverThemeChoice,
            playerThemePreference = ui.playerThemePreference,
            showPlayerFileInfo = ui.showPlayerFileInfo,
            albumArtPaletteStyle = ui.albumArtPaletteStyle,
            albumArtQuality = ui.albumArtQuality,
            launchTab = ui.launchTab,
            libraryNavigationMode = ui.libraryNavigationMode,
            coverGlassMoving = ui.coverGlassMoving,
            lyricsUnderCover = ui.lyricsUnderCover,
            showBookmarkButton = ui.showBookmarkButton,
            immersiveLyricsEnabled = ui.immersiveLyricsEnabled,
            immersiveLyricsTimeout = ui.immersiveLyricsTimeout,
            lyricsAlignment = ui.lyricsAlignment,
            showLyricsTranslation = ui.showLyricsTranslation,
            showLyricsRomanization = ui.showLyricsRomanization,
            useAnimatedLyrics = ui.useAnimatedLyrics,
            animatedLyricsBlurEnabled = ui.animatedLyricsBlurEnabled,
            animatedLyricsBlurStrength = ui.animatedLyricsBlurStrength,
            dapLockScreenEnabled = ui.dapLockScreenEnabled,
        )
    }
}

/** Everything the Appearance page can do. Implemented by the host; the page never sees a ViewModel. */
interface AppearanceSettingsActions {
    fun onAppLanguageChange(value: String)
    fun onAccentChange(value: DapAccent)
    /** B40-F1: the whole app in the colours of the song that is playing, or the theme and accent below. */
    fun onAppColorSourceChange(value: String)
    /** B41-fix1 (B1): light, dark or system while the cover decides the colours ([CoverThemeChoice]); the explicit theme is left as it was. */
    fun onCoverThemeChoiceChange(value: String)
    fun onPlayerThemePreferenceChange(value: String)
    fun onShowBookmarkButtonChange(value: Boolean)
    fun onShowPlayerFileInfoChange(value: Boolean)
    fun onAlbumArtQualityChange(value: AlbumArtQuality)
    fun onLaunchTabChange(value: String)
    fun onLibraryNavigationModeChange(value: String)
    /** The one theme row: the choice is stored as a base theme and a mode, in an order that never draws the wrong one. */
    fun onThemeChoiceChange(choice: DapAppearanceChoice)
    fun onOpenPaletteStyle()

    /** B41 (Player): "Moving background". */
    fun onCoverGlassMovingChange(value: Boolean)
    /** B41 (Player): "Lyrics under the cover". */
    fun onLyricsUnderCoverChange(value: Boolean)

    // B41 (Lyrics): what Settings, Lyrics used to call; each one writes the preference it has always written.
    fun onImmersiveLyricsEnabledChange(value: Boolean)
    fun onImmersiveLyricsTimeoutChange(value: Long)
    fun onLyricsAlignmentChange(value: String)
    fun onShowLyricsTranslationChange(value: Boolean)
    fun onShowLyricsRomanizationChange(value: Boolean)
    fun onUseAnimatedLyricsChange(value: Boolean)
    fun onAnimatedLyricsBlurEnabledChange(value: Boolean)
    fun onAnimatedLyricsBlurStrengthChange(value: Float)

    /** B41 (Lock screen): "DAP lock screen", moved here from Behaviour. */
    fun onDapLockScreenEnabledChange(value: Boolean)
}

@Composable
fun AppearanceSettingsScreen(state: AppearanceSettingsState, actions: AppearanceSettingsActions) {
    val context = LocalContext.current

    // B41-fix1 (B1): "App colours" comes first because it is the question that decides what the rows under it mean. While the cover decides
    // the colours (From album art, Cover glass) the theme only decides light or dark and the accent only matters for a song with no cover;
    // with "Theme and accent" the five themes and the whole palette are what they were.
    val coverDecides = AppColorSource.takesColoursFromCover(state.appColorSource)
    SettingsSubsection(title = stringResource(R.string.setcat_global_theme)) {
        ThemeSelectorItem(
            label = stringResource(R.string.setcat_app_language_label),
            description = stringResource(R.string.setcat_app_language_desc),
            options = AppLanguage.getLanguageOptions(context),
            selectedKey = state.appLanguageTag,
            // B16-G3: no recreate() here. MainActivity recreates itself once when the language it was
            // created in is no longer the one in force (LanguageRecreateGate).
            onSelectionChanged = { actions.onAppLanguageChange(it) },
            leadingIcon = { Icon(Icons.Outlined.Language, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        // B40-F1: the colours of the app come from the theme and accent below, or follow the cover of each song.
        ThemeSelectorItem(
            label = stringResource(R.string.setcat_app_colors_label),
            description = stringResource(R.string.setcat_app_colors_desc),
            options = mapOf(
                AppColorSource.THEME to stringResource(R.string.setcat_app_colors_theme),
                AppColorSource.ALBUM_ART to stringResource(R.string.setcat_app_colors_album_art),
                // B41: the same colours as the option above, plus the glass behind the whole app.
                AppColorSource.COVER_GLASS to stringResource(R.string.setcat_app_colors_cover_glass),
            ),
            selectedKey = state.appColorSource,
            onSelectionChanged = { actions.onAppColorSourceChange(it) },
            leadingIcon = { Icon(Icons.Outlined.Style, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        if (coverDecides) {
            // B42-fix3 (item 7): no "Theme" row while the cover decides. The cover decides the lightness as well as the colours (the ink is
            // always light, on Graphite), so there is nothing left for it to say. The preference it used to write (`CoverThemeChoice`) is kept
            // as it was and read by nothing; "Theme and accent" shows the explicit theme the listener had, untouched.
            // The accent only applies to a song with no cover: one small row, not the whole palette.
            ThemeSelectorItem(
                label = stringResource(R.string.setcat_accent_without_cover_label),
                description = stringResource(R.string.setcat_accent_desc_album_art),
                options = DapAccent.entries.associate { it.storageKey to it.label },
                selectedKey = state.accent.storageKey,
                onSelectionChanged = { key -> actions.onAccentChange(DapAccent.fromStorageKey(key)) },
                leadingIcon = { Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.secondary) },
            )
        } else {
            // One theme row. "App theme" and "Base theme" used to be two rows that looked like the same question and could contradict
            // each other; see DapAppearanceChoice for how the one answer is stored.
            ThemeSelectorItem(
                label = stringResource(R.string.setcat_theme_label),
                description = stringResource(R.string.setcat_theme_desc),
                options = explicitThemeOptions { stringResource(it) },
                selectedKey = appearanceChoiceOf(state.appThemeMode, state.baseTheme).key,
                // The two writes this choice is (and their order) live in the actions: see AppearanceSettingsActionsImpl.
                onSelectionChanged = { key -> actions.onThemeChoiceChange(DapAppearanceChoice.fromKey(key)) },
                leadingIcon = { Icon(Icons.Outlined.Contrast, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            AccentSwatchRow(
                label = stringResource(R.string.setcat_accent_label),
                description = stringResource(R.string.setcat_accent_desc),
                selected = state.accent,
                onAccentSelected = { actions.onAccentChange(it) },
            )
        }
    }

    // B41: the Player section: the player's own colours and file line, and the two things that dress it (the glass that moves behind it
    // and the lyrics under its cover).
    SettingsSubsection(title = stringResource(R.string.setcat_now_playing)) {
        SwitchSettingItem(
            title = stringResource(R.string.show_bookmark_button),
            subtitle = stringResource(R.string.show_bookmark_button_note),
            checked = state.showBookmarkButton,
            onCheckedChange = actions::onShowBookmarkButtonChange,
            leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        SwitchSettingItem(
            title = stringResource(R.string.setcat_show_player_file_info_title),
            subtitle = stringResource(R.string.setcat_show_player_file_info_subtitle),
            checked = state.showPlayerFileInfo,
            onCheckedChange = { actions.onShowPlayerFileInfoChange(it) },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_attach_file_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
        // B41: there is a glass to move only once "Cover glass" is the app's colour source. It moves in the full player and on the lock
        // screen; the rest of the app holds still whatever this says.
        if (AppColorSource.hasGlass(state.appColorSource)) {
            SwitchSettingItem(
                title = stringResource(R.string.setcat_moving_background_title),
                subtitle = stringResource(R.string.setcat_moving_background_subtitle),
                checked = state.coverGlassMoving,
                onCheckedChange = { actions.onCoverGlassMovingChange(it) },
                leadingIcon = { Icon(Icons.Rounded.AutoAwesomeMotion, null, tint = MaterialTheme.colorScheme.secondary) },
            )
        }
        SwitchSettingItem(
            title = stringResource(R.string.setcat_lyrics_under_cover_title),
            subtitle = stringResource(R.string.setcat_lyrics_under_cover_subtitle),
            checked = state.lyricsUnderCover,
            onCheckedChange = { actions.onLyricsUnderCoverChange(it) },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_lyrics_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
    }

    // B41: how lyrics look is Appearance's: the reading and the effects moved here from Settings, Lyrics, which keeps the sources.
    LyricsAppearanceSection(state, actions)

    // B41: the DAP lock screen, moved here from Behaviour. Same preference, same switch.
    SettingsSubsection(title = stringResource(R.string.setcat_lock_screen_section)) {
        SwitchSettingItem(
            title = stringResource(R.string.setcat_dap_lock_screen_title),
            subtitle = stringResource(R.string.setcat_dap_lock_screen_subtitle),
            checked = state.dapLockScreenEnabled,
            onCheckedChange = { actions.onDapLockScreenEnabledChange(it) },
            leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.secondary) },
        )
    }



    // Artwork presentation. Album art resolution was a debug-only control
    // for a setting that every build applies — the full player has always
    // honoured it — so it is a product appearance choice and lives here.
    SettingsSubsection(title = stringResource(R.string.setcat_artwork)) {
        ThemeSelectorItem(
            label = stringResource(R.string.presentation_batch_f_exp_album_art_resolution),
            description = stringResource(R.string.setcat_album_art_resolution_desc),
            // The stored labels are one sentence with a rationale after a
            // dash ("Original - Maximum quality"). The selector shows the
            // choice, not the rationale, so the label is the part before
            // the dash — the same split the screen this moved from used.
            options = AlbumArtQuality.entries.associate {
                it.name to stringResource(albumArtQualityLabelRes(it))
                    .substringBefore(" - ")
            },
            selectedKey = state.albumArtQuality.name,
            onSelectionChanged = { key ->
                AlbumArtQuality.entries
                    .firstOrNull { it.name == key }
                    ?.let { actions.onAlbumArtQualityChange(it) }
            },
            leadingIcon = { Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.secondary) },
        )
    }

    SettingsSubsection(
        title = stringResource(R.string.setcat_app_navigation_section),
        addBottomSpace = false
    ) {
        ThemeSelectorItem(
            label = stringResource(R.string.setcat_default_tab_label),
            description = stringResource(R.string.setcat_default_tab_desc),
            options = mapOf(
                LaunchTab.HOME to stringResource(R.string.tab_home),
                LaunchTab.SEARCH to stringResource(R.string.search),
                LaunchTab.LIBRARY to stringResource(R.string.tab_library),
            ),
            selectedKey = state.launchTab,
            onSelectionChanged = { actions.onLaunchTabChange(it) },
            leadingIcon = { Icon(painterResource(R.drawable.tab_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
        ThemeSelectorItem(
            label = stringResource(R.string.setcat_library_navigation_label),
            description = stringResource(R.string.setcat_library_navigation_desc),
            options = mapOf(
                LibraryNavigationMode.TAB_ROW to stringResource(R.string.setcat_library_nav_tab_row),
                LibraryNavigationMode.COMPACT_PILL to stringResource(R.string.setcat_library_nav_compact_pill)
            ),
            selectedKey = state.libraryNavigationMode,
            onSelectionChanged = { actions.onLibraryNavigationModeChange(it) },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_library_music_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
    }
}

/** What the Theme row offers with "Theme and accent": the five themes, and following the system (Light by day, Graphite by night). */
internal inline fun explicitThemeOptions(text: (Int) -> String): Map<String, String> = mapOf(
    DapAppearanceChoice.Graphite.key to text(R.string.setcat_theme_option_graphite),
    DapAppearanceChoice.Black.key to text(R.string.setcat_theme_option_black),
    DapAppearanceChoice.Light.key to text(R.string.setcat_theme_option_light),
    DapAppearanceChoice.Gray.key to text(R.string.setcat_theme_option_gray),
    DapAppearanceChoice.Navy.key to text(R.string.setcat_theme_option_navy),
    DapAppearanceChoice.Automatic.key to text(R.string.setcat_theme_follow_system),
)

/**
 * B41: the lyrics' look and reading (it was Settings, Lyrics, "Reading" and "Effects"): immersive lyrics and their delay, alignment,
 * translations, romanisation, animated lyrics and the blur. The rows, their words and the preferences are the ones they were.
 */
@Composable
private fun LyricsAppearanceSection(state: AppearanceSettingsState, actions: AppearanceSettingsActions) {
    // A drag is drafted locally and written once, when it ends: the slider would otherwise write the preference on every frame.
    var blurStrengthDraft by remember(state.animatedLyricsBlurStrength) {
        mutableFloatStateOf(
            state.animatedLyricsBlurStrength.coerceIn(ANIMATED_LYRICS_BLUR_STRENGTH_MIN, ANIMATED_LYRICS_BLUR_STRENGTH_MAX)
        )
    }
    SettingsSubsection(title = stringResource(R.string.settings_category_lyrics_title)) {
        // B32-Q7: immersive lyrics were hidden in 1.0; B40-F5 opened the gate.
        if (ImmersiveLyrics.AVAILABLE) {
            SwitchSettingItem(
                title = stringResource(R.string.setcat_immersive_lyrics_title),
                subtitle = stringResource(R.string.setcat_immersive_lyrics_subtitle),
                checked = state.immersiveLyricsEnabled,
                onCheckedChange = { actions.onImmersiveLyricsEnabledChange(it) },
                leadingIcon = { Icon(painterResource(R.drawable.rounded_lyrics_24), null, tint = MaterialTheme.colorScheme.secondary) },
            )
            if (state.immersiveLyricsEnabled) {
                ThemeSelectorItem(
                    label = stringResource(R.string.setcat_auto_hide_delay_label),
                    description = stringResource(R.string.setcat_auto_hide_delay_desc),
                    options = mapOf(
                        "3000" to stringResource(R.string.setcat_lyrics_delay_3s),
                        "4000" to stringResource(R.string.setcat_lyrics_delay_4s),
                        "5000" to stringResource(R.string.setcat_lyrics_delay_5s),
                        "6000" to stringResource(R.string.setcat_lyrics_delay_6s)
                    ),
                    selectedKey = state.immersiveLyricsTimeout.toString(),
                    onSelectionChanged = { actions.onImmersiveLyricsTimeoutChange(it.toLong()) },
                    leadingIcon = { Icon(Icons.Rounded.Timer, null, tint = MaterialTheme.colorScheme.secondary) }
                )
            }
        }
        ThemeSelectorItem(
            label = stringResource(R.string.setcat_lyrics_alignment_label),
            description = stringResource(R.string.setcat_lyrics_alignment_desc),
            options = mapOf(
                "left" to stringResource(R.string.setcat_lyrics_alignment_left),
                "center" to stringResource(R.string.setcat_lyrics_alignment_center),
                "right" to stringResource(R.string.setcat_lyrics_alignment_right),
            ),
            selectedKey = state.lyricsAlignment,
            onSelectionChanged = { actions.onLyricsAlignmentChange(it) },
            leadingIcon = { Icon(Icons.Rounded.FormatAlignCenter, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        SwitchSettingItem(
            title = stringResource(R.string.setcat_lyrics_translation_title),
            subtitle = stringResource(R.string.setcat_lyrics_translation_subtitle),
            checked = state.showLyricsTranslation,
            onCheckedChange = { actions.onShowLyricsTranslationChange(it) },
            leadingIcon = { Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        SwitchSettingItem(
            title = stringResource(R.string.setcat_lyrics_romanization_title),
            subtitle = stringResource(R.string.setcat_lyrics_romanization_subtitle),
            checked = state.showLyricsRomanization,
            onCheckedChange = { actions.onShowLyricsRomanizationChange(it) },
            leadingIcon = { Icon(Icons.Rounded.Abc, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        SwitchSettingItem(
            title = stringResource(R.string.presentation_batch_f_exp_animated_lyrics_title),
            subtitle = stringResource(R.string.presentation_batch_f_exp_animated_lyrics_subtitle),
            checked = state.useAnimatedLyrics,
            onCheckedChange = { actions.onUseAnimatedLyricsChange(it) },
            leadingIcon = { Icon(Icons.Rounded.AutoAwesomeMotion, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        SwitchSettingItem(
            title = stringResource(R.string.presentation_batch_f_exp_lyric_blur_title),
            subtitle = stringResource(R.string.presentation_batch_f_exp_lyric_blur_subtitle),
            checked = state.animatedLyricsBlurEnabled,
            onCheckedChange = { actions.onAnimatedLyricsBlurEnabledChange(it) },
            leadingIcon = { Icon(Icons.Rounded.BlurOn, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        if (state.animatedLyricsBlurEnabled) {
            SliderSettingsItem(
                label = stringResource(R.string.presentation_batch_f_exp_blur_strength),
                value = blurStrengthDraft,
                valueRange = ANIMATED_LYRICS_BLUR_STRENGTH_MIN..ANIMATED_LYRICS_BLUR_STRENGTH_MAX,
                steps = 28,
                onValueChange = { blurStrengthDraft = it },
                onValueChangeFinished = { actions.onAnimatedLyricsBlurStrengthChange(blurStrengthDraft) },
                valueText = { value -> String.format(Locale.US, "%.1f×", value) },
            )
        }
    }
}

/**
 * The album art resolution options, labelled from the same strings the removed Experimental
 * screen used, so the wording does not change with the move.
 */
@Composable
private fun albumArtQualityLabelRes(quality: AlbumArtQuality): Int = when (quality) {
    AlbumArtQuality.LOW -> R.string.presentation_batch_f_album_art_quality_low_line
    AlbumArtQuality.MEDIUM -> R.string.presentation_batch_f_album_art_quality_medium_line
    AlbumArtQuality.HIGH -> R.string.presentation_batch_f_album_art_quality_high_line
    AlbumArtQuality.ORIGINAL -> R.string.presentation_batch_f_album_art_quality_original_line
}
