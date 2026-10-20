package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.rounded.Abc
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.preferences.ANIMATED_LYRICS_BLUR_STRENGTH_MAX
import com.lostf1sh.pixelplayeross.data.preferences.ANIMATED_LYRICS_BLUR_STRENGTH_MIN
import com.lostf1sh.pixelplayeross.data.preferences.ImmersiveLyrics
import com.lostf1sh.pixelplayeross.presentation.screens.SliderSettingsItem
import com.lostf1sh.pixelplayeross.presentation.screens.SwitchSettingItem
import com.lostf1sh.pixelplayeross.presentation.screens.ThemeSelectorItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import java.util.Locale

/** What the Lyrics page draws. */
@Immutable
data class LyricsSettingsState(
    val externalLyricsEnabled: Boolean,
    val lyricsSourcePreference: LyricsSourcePreference,
    val autoScanLrcFiles: Boolean,
    val immersiveLyricsEnabled: Boolean,
    val immersiveLyricsTimeout: Long,
    val lyricsAlignment: String,
    val showLyricsTranslation: Boolean,
    val showLyricsRomanization: Boolean,
    val useAnimatedLyrics: Boolean,
    val animatedLyricsBlurEnabled: Boolean,
    val animatedLyricsBlurStrength: Float,
) {
    companion object {
        fun from(ui: SettingsUiState) = LyricsSettingsState(
            externalLyricsEnabled = ui.externalLyricsEnabled,
            lyricsSourcePreference = ui.lyricsSourcePreference,
            autoScanLrcFiles = ui.autoScanLrcFiles,
            immersiveLyricsEnabled = ui.immersiveLyricsEnabled,
            immersiveLyricsTimeout = ui.immersiveLyricsTimeout,
            lyricsAlignment = ui.lyricsAlignment,
            showLyricsTranslation = ui.showLyricsTranslation,
            showLyricsRomanization = ui.showLyricsRomanization,
            useAnimatedLyrics = ui.useAnimatedLyrics,
            animatedLyricsBlurEnabled = ui.animatedLyricsBlurEnabled,
            animatedLyricsBlurStrength = ui.animatedLyricsBlurStrength,
        )
    }
}

/** Everything the Lyrics page can do. */
interface LyricsSettingsActions {
    fun onExternalLyricsEnabledChange(value: Boolean)
    fun onLyricsSourcePreferenceChange(value: LyricsSourcePreference)
    fun onAutoScanLrcFilesChange(value: Boolean)
    fun onImmersiveLyricsEnabledChange(value: Boolean)
    fun onImmersiveLyricsTimeoutChange(value: Long)
    fun onLyricsAlignmentChange(value: String)
    fun onShowLyricsTranslationChange(value: Boolean)
    fun onShowLyricsRomanizationChange(value: Boolean)
    fun onUseAnimatedLyricsChange(value: Boolean)
    fun onAnimatedLyricsBlurEnabledChange(value: Boolean)
    fun onAnimatedLyricsBlurStrengthChange(value: Float)
}

@Composable
fun LyricsSettingsScreen(state: LyricsSettingsState, actions: LyricsSettingsActions) {
    // A drag is drafted locally and written once, when it ends: the slider would otherwise write the
    // preference on every frame of the gesture.
    var animatedLyricsBlurStrengthDraft by remember(state.animatedLyricsBlurStrength) {
        mutableStateOf(
            state.animatedLyricsBlurStrength.coerceIn(
                ANIMATED_LYRICS_BLUR_STRENGTH_MIN,
                ANIMATED_LYRICS_BLUR_STRENGTH_MAX,
            )
        )
    }

    SettingsSubsection(title = stringResource(R.string.setcat_lyrics_sources)) {
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

    SettingsSubsection(title = stringResource(R.string.setcat_lyrics_reading)) {
        // B32-Q7: immersive lyrics are hidden in 1.0 (the switch and its delay), and kept for 1.1.
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
    }

    // Rendering effects. These shipped on by default and were still only
    // reachable from a debug-only screen, so a release build had no way to
    // turn off behaviour it was already applying.
    SettingsSubsection(
        title = stringResource(R.string.setcat_lyrics_effects),
        addBottomSpace = false
    ) {
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
                value = animatedLyricsBlurStrengthDraft,
                valueRange = ANIMATED_LYRICS_BLUR_STRENGTH_MIN..ANIMATED_LYRICS_BLUR_STRENGTH_MAX,
                steps = 28,
                onValueChange = { animatedLyricsBlurStrengthDraft = it },
                onValueChangeFinished = {
                    actions.onAnimatedLyricsBlurStrengthChange(
                        animatedLyricsBlurStrengthDraft
                    )
                },
                valueText = { value -> String.format(Locale.US, "%.1f×", value) },
            )
        }
    }
}
