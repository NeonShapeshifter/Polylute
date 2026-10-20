package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.MainActivity
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtPaletteStyle
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.AppLanguage
import com.lostf1sh.pixelplayeross.data.preferences.LaunchTab
import com.lostf1sh.pixelplayeross.data.preferences.LibraryNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreference
import com.lostf1sh.pixelplayeross.presentation.screens.SettingsItem
import com.lostf1sh.pixelplayeross.presentation.screens.SwitchSettingItem
import com.lostf1sh.pixelplayeross.presentation.screens.ThemeSelectorItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapChevron
import com.lostf1sh.pixelplayeross.ui.theme.DapAppearanceChoice
import com.lostf1sh.pixelplayeross.ui.theme.appearanceChoiceOf

/** What the Appearance page draws. */
@Immutable
data class AppearanceSettingsState(
    val appLanguageTag: String,
    val appThemeMode: String,
    val baseTheme: DapBaseTheme,
    val accent: DapAccent,
    val playerThemePreference: String,
    val showPlayerFileInfo: Boolean,
    val albumArtPaletteStyle: AlbumArtPaletteStyle,
    val albumArtQuality: AlbumArtQuality,
    val launchTab: String,
    val libraryNavigationMode: String,
) {
    companion object {
        fun from(ui: SettingsUiState) = AppearanceSettingsState(
            appLanguageTag = ui.appLanguageTag,
            appThemeMode = ui.appThemeMode,
            baseTheme = ui.baseTheme,
            accent = ui.accent,
            playerThemePreference = ui.playerThemePreference,
            showPlayerFileInfo = ui.showPlayerFileInfo,
            albumArtPaletteStyle = ui.albumArtPaletteStyle,
            albumArtQuality = ui.albumArtQuality,
            launchTab = ui.launchTab,
            libraryNavigationMode = ui.libraryNavigationMode,
        )
    }
}

/** Everything the Appearance page can do. Implemented by the host; the page never sees a ViewModel. */
interface AppearanceSettingsActions {
    fun onAppLanguageChange(value: String)
    fun onAccentChange(value: DapAccent)
    fun onPlayerThemePreferenceChange(value: String)
    fun onShowPlayerFileInfoChange(value: Boolean)
    fun onAlbumArtQualityChange(value: AlbumArtQuality)
    fun onLaunchTabChange(value: String)
    fun onLibraryNavigationModeChange(value: String)
    /** The one theme row: the choice is stored as a base theme and a mode, in an order that never draws the wrong one. */
    fun onThemeChoiceChange(choice: DapAppearanceChoice)
    fun onOpenPaletteStyle()
}

@Composable
fun AppearanceSettingsScreen(state: AppearanceSettingsState, actions: AppearanceSettingsActions) {
    val context = LocalContext.current

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
        // One theme row. "App theme" and "Base theme" used to be two rows
        // that looked like the same question and could contradict each
        // other; see DapAppearanceChoice for how the one answer is stored.
        ThemeSelectorItem(
            label = stringResource(R.string.setcat_theme_label),
            description = stringResource(R.string.setcat_theme_desc),
            options = mapOf(
                DapAppearanceChoice.Graphite.key to stringResource(R.string.setcat_theme_option_graphite),
                DapAppearanceChoice.Black.key to stringResource(R.string.setcat_theme_option_black),
                DapAppearanceChoice.Light.key to stringResource(R.string.setcat_theme_option_light),
                DapAppearanceChoice.Automatic.key to stringResource(R.string.setcat_theme_follow_system),
            ),
            selectedKey = appearanceChoiceOf(state.appThemeMode, state.baseTheme).key,
            // The two writes this choice is (and their order) live in the actions: see AppearanceSettingsActionsImpl.
                    onSelectionChanged = { key -> actions.onThemeChoiceChange(DapAppearanceChoice.fromKey(key)) },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Contrast,
                    null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            },
        )
        AccentSwatchRow(
            label = stringResource(R.string.setcat_accent_label),
            description = stringResource(R.string.setcat_accent_desc),
            selected = state.accent,
            onAccentSelected = { actions.onAccentChange(it) },
        )
    }

    SettingsSubsection(title = stringResource(R.string.setcat_now_playing)) {
        ThemeSelectorItem(
            label = stringResource(R.string.setcat_player_theme_label),
            description = stringResource(R.string.setcat_player_theme_desc),
            options = mapOf(
                ThemePreference.ALBUM_ART to stringResource(R.string.setcat_player_theme_album_art),
                ThemePreference.DYNAMIC to stringResource(R.string.setcat_player_theme_dynamic)
            ),
            selectedKey = state.playerThemePreference,
            onSelectionChanged = { actions.onPlayerThemePreferenceChange(it) },
            leadingIcon = { Icon(Icons.Outlined.PlayCircle, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        SwitchSettingItem(
            title = stringResource(R.string.setcat_show_player_file_info_title),
            subtitle = stringResource(R.string.setcat_show_player_file_info_subtitle),
            checked = state.showPlayerFileInfo,
            onCheckedChange = { actions.onShowPlayerFileInfoChange(it) },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_attach_file_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
        // The palette style only shapes colour taken from the artwork, so it
        // is offered only when the player takes its colour from the artwork.
        if (state.playerThemePreference == ThemePreference.ALBUM_ART) {
            SettingsItem(
                title = stringResource(R.string.setcat_album_art_palette_title),
                subtitle = stringResource(R.string.setcat_album_art_palette_subtitle, state.albumArtPaletteStyle.label),
                leadingIcon = { Icon(Icons.Outlined.Style, null, tint = MaterialTheme.colorScheme.secondary) },
                trailingIcon = { DapChevron() },
                onClick = { actions.onOpenPaletteStyle() }
            )
        }
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
