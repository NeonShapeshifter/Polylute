package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.presentation.model.hintRes
import com.lostf1sh.pixelplayeross.presentation.model.labelRes
import com.lostf1sh.pixelplayeross.presentation.screens.SwitchSettingItem
import com.lostf1sh.pixelplayeross.presentation.screens.ThemeSelectorItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState

/** What the Behavior page draws. */
@Immutable
data class BehaviorSettingsState(
    val backNavigationMode: BackNavigationMode,
    val keepPlayingInBackground: Boolean,
    val resumeOnHeadsetReconnect: Boolean,
    val dapLockScreenEnabled: Boolean,
    val showQueueHistory: Boolean,
    val folderBackGestureNavigation: Boolean,
    val tapBackgroundClosesPlayer: Boolean,
    val hapticsEnabled: Boolean,
) {
    companion object {
        fun from(ui: SettingsUiState) = BehaviorSettingsState(
            backNavigationMode = ui.backNavigationMode,
            keepPlayingInBackground = ui.keepPlayingInBackground,
            resumeOnHeadsetReconnect = ui.resumeOnHeadsetReconnect,
            dapLockScreenEnabled = ui.dapLockScreenEnabled,
            showQueueHistory = ui.showQueueHistory,
            folderBackGestureNavigation = ui.folderBackGestureNavigation,
            tapBackgroundClosesPlayer = ui.tapBackgroundClosesPlayer,
            hapticsEnabled = ui.hapticsEnabled,
        )
    }
}

/** Everything the Behavior page can do. */
interface BehaviorSettingsActions {
    fun onBackNavigationModeChange(value: BackNavigationMode)
    fun onKeepPlayingInBackgroundChange(value: Boolean)
    fun onResumeOnHeadsetReconnectChange(value: Boolean)
    fun onDapLockScreenEnabledChange(value: Boolean)
    fun onShowQueueHistoryChange(value: Boolean)
    fun onFolderBackGestureNavigationChange(value: Boolean)
    fun onTapBackgroundClosesPlayerChange(value: Boolean)
    fun onHapticsEnabledChange(value: Boolean)
}

@Composable
fun BehaviorSettingsScreen(state: BehaviorSettingsState, actions: BehaviorSettingsActions) {
    // How the app goes back. Its own subsection rather than a row inside
    // another one: the three modes are one decision, and the description
    // under the row changes with the selection so the choice explains
    // itself without a second label.
    SettingsSubsection(title = stringResource(R.string.setcat_back_navigation)) {
        ThemeSelectorItem(
            label = stringResource(R.string.setcat_back_navigation_title),
            description = stringResource(state.backNavigationMode.hintRes),
            options = BackNavigationMode.entries.associate { mode ->
                mode.id to stringResource(mode.labelRes)
            },
            selectedKey = state.backNavigationMode.id,
            onSelectionChanged = { actions.onBackNavigationModeChange(BackNavigationMode.fromId(it)) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.rounded_touch_app_24),
                    null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            },
        )
    }

    SettingsSubsection(title = stringResource(R.string.setcat_background_playback)) {
        ThemeSelectorItem(
            label = stringResource(R.string.setcat_keep_playing_label),
            description = stringResource(R.string.setcat_keep_playing_desc),
            options = mapOf("true" to stringResource(R.string.label_on), "false" to stringResource(R.string.label_off)),
            selectedKey = if (state.keepPlayingInBackground) "true" else "false",
            onSelectionChanged = { actions.onKeepPlayingInBackgroundChange(it.toBoolean()) },
            leadingIcon = { Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        SwitchSettingItem(
            title = stringResource(R.string.setcat_headphones_resume_title),
            subtitle = stringResource(R.string.setcat_headphones_resume_subtitle),
            checked = state.resumeOnHeadsetReconnect,
            onCheckedChange = { actions.onResumeOnHeadsetReconnectChange(it) },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_headphones_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
        SwitchSettingItem(
            title = stringResource(R.string.setcat_dap_lock_screen_title),
            subtitle = stringResource(R.string.setcat_dap_lock_screen_subtitle),
            checked = state.dapLockScreenEnabled,
            onCheckedChange = { actions.onDapLockScreenEnabledChange(it) },
            leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.secondary) },
        )
    }

    SettingsSubsection(title = stringResource(R.string.setcat_queue_behaviour)) {
        SwitchSettingItem(
            title = stringResource(R.string.setcat_show_queue_history_title),
            subtitle = stringResource(R.string.setcat_show_queue_history_subtitle),
            checked = state.showQueueHistory,
            onCheckedChange = { actions.onShowQueueHistoryChange(it) },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_queue_music_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
    }

    SettingsSubsection(
        title = stringResource(R.string.setcat_folders)
    ) {
        SwitchSettingItem(
            title = stringResource(R.string.setcat_folder_back_gesture_title),
            subtitle = stringResource(R.string.setcat_folder_back_gesture_subtitle),
            checked = state.folderBackGestureNavigation,
            onCheckedChange = { actions.onFolderBackGestureNavigationChange(it) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.rounded_touch_app_24),
                    null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            },
        )
    }
    SettingsSubsection(
        title = stringResource(R.string.setcat_player_gestures)
    ) {
        SwitchSettingItem(
            title = stringResource(R.string.setcat_tap_bg_closes_title),
            subtitle = stringResource(R.string.setcat_tap_bg_closes_subtitle),
            checked = state.tapBackgroundClosesPlayer,
            onCheckedChange = { actions.onTapBackgroundClosesPlayerChange(it) },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_touch_app_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
    }
    SettingsSubsection(
        title = stringResource(R.string.setcat_haptics),
        addBottomSpace = false
    ) {
        SwitchSettingItem(
            title = stringResource(R.string.setcat_haptic_feedback_title),
            subtitle = stringResource(R.string.setcat_haptic_feedback_subtitle),
            checked = state.hapticsEnabled,
            onCheckedChange = { actions.onHapticsEnabledChange(it) },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_touch_app_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
    }
}
