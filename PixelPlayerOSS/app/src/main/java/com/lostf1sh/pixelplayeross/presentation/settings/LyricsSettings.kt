package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.presentation.screens.SwitchSettingItem
import com.lostf1sh.pixelplayeross.presentation.screens.ThemeSelectorItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState

/**
 * What the Lyrics page draws.
 *
 * B41: only where the lyrics come from. How they read and look (alignment, animated lyrics, the blur, immersive lyrics and its delay,
 * translations, romanisation) is Appearance's now, with the lyrics under the cover; the preferences did not change.
 */
@Immutable
data class LyricsSettingsState(
    val externalLyricsEnabled: Boolean,
    val lyricsSourcePreference: LyricsSourcePreference,
    val autoScanLrcFiles: Boolean,
) {
    companion object {
        fun from(ui: SettingsUiState) = LyricsSettingsState(
            externalLyricsEnabled = ui.externalLyricsEnabled,
            lyricsSourcePreference = ui.lyricsSourcePreference,
            autoScanLrcFiles = ui.autoScanLrcFiles,
        )
    }
}

/** Everything the Lyrics page can do. */
interface LyricsSettingsActions {
    fun onExternalLyricsEnabledChange(value: Boolean)
    fun onLyricsSourcePreferenceChange(value: LyricsSourcePreference)
    fun onAutoScanLrcFilesChange(value: Boolean)
}

@Composable
fun LyricsSettingsScreen(state: LyricsSettingsState, actions: LyricsSettingsActions) {
    SettingsSubsection(
        title = stringResource(R.string.setcat_lyrics_sources),
        addBottomSpace = false,
    ) {
        SwitchSettingItem(
            title = stringResource(R.string.setcat_external_lyrics_title),
            subtitle = stringResource(R.string.setcat_external_lyrics_subtitle),
            checked = state.externalLyricsEnabled,
            onCheckedChange = { actions.onExternalLyricsEnabledChange(it) },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_lyrics_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
        ThemeSelectorItem(
            label = stringResource(R.string.setcat_lyrics_source_priority_label),
            description = stringResource(R.string.setcat_lyrics_source_priority_desc),
            options = mapOf(
                LyricsSourcePreference.EMBEDDED_FIRST.name to stringResource(R.string.setcat_lyrics_embedded_first),
                LyricsSourcePreference.API_FIRST.name to stringResource(R.string.setcat_lyrics_online_first),
                LyricsSourcePreference.LOCAL_FIRST.name to stringResource(R.string.setcat_lyrics_local_first)
            ),
            selectedKey = state.lyricsSourcePreference.name,
            onSelectionChanged = { key ->
                actions.onLyricsSourcePreferenceChange(
                    LyricsSourcePreference.fromName(key)
                )
            },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_lyrics_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
        SwitchSettingItem(
            title = stringResource(R.string.setcat_auto_scan_lrc_title),
            subtitle = stringResource(R.string.setcat_auto_scan_lrc_subtitle),
            checked = state.autoScanLrcFiles,
            onCheckedChange = { actions.onAutoScanLrcFilesChange(it) },
            leadingIcon = { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.secondary) },
        )
    }
}
