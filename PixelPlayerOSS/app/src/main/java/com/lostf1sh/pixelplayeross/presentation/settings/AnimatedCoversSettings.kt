package com.lostf1sh.pixelplayeross.presentation.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.AnimatedCovers
import com.lostf1sh.pixelplayeross.presentation.LocalDapMode
import com.lostf1sh.pixelplayeross.presentation.screens.SettingsItem
import com.lostf1sh.pixelplayeross.presentation.screens.SwitchSettingItem
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction

/**
 * B56-7.2 / B57-fix1 4 (#52): "Animated covers", `cover.mp4` / `cover.webp` / `cover.gif` from the album folder, in the full player only. Off by default in both modes.
 *
 * Android 13 and later reads the covers only through **one folder the listener picks** (the system folder picker, a persisted read permission; never the photo or video permissions). That
 * picker opens **only** when the listener turns the switch on and no folder is kept; cancelling leaves the switch off. With a folder, two rows show its name and offer Change and Remove
 * (Remove releases the permission and turns the feature off). Before Android 13 there is no picker and no folder row: the files are read as they always were.
 */
@Composable
internal fun AnimatedCoversRows(state: AppearanceSettingsState, actions: AppearanceSettingsActions) {
    val hasFolder = state.animatedCoversFolder != null
    val needsFolder = state.animatedCoversNeedsFolder
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        actions.onAnimatedCoverFolderPicked(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null, result.data?.flags ?: 0)
    }
    val openPicker = {
        try {
            picker.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            )
        } catch (_: ActivityNotFoundException) {
            actions.onAnimatedCoverFolderPicked(null, 0)
        }
    }
    val stored = AnimatedCovers.effective(state.animatedCoversStored, LocalDapMode.current)
    SwitchSettingItem(
        title = stringResource(R.string.setcat_animated_covers_title),
        subtitle = stringResource(R.string.setcat_animated_covers_subtitle),
        // on only when it can play: Android 13+ needs the folder as well
        checked = stored && (!needsFolder || hasFolder),
        onCheckedChange = { want ->
            when {
                !want -> actions.onAnimatedCoversChange(false)
                AnimatedCovers.turnOn(needsFolder, hasFolder) == AnimatedCovers.TurnOn.ASK_FOR_FOLDER -> openPicker()
                else -> actions.onAnimatedCoversChange(true)
            }
        },
        leadingIcon = { Icon(Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.secondary) },
    )
    if (needsFolder && hasFolder) {
        SettingsItem(
            title = stringResource(R.string.setcat_animated_covers_folder_title),
            subtitle = state.animatedCoversFolder.orEmpty(),
            leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.secondary) },
            trailingIcon = { DapTextAction(label = stringResource(R.string.setcat_animated_covers_folder_change), onClick = { openPicker() }) },
            onClick = { openPicker() },
        )
        SettingsItem(
            title = stringResource(R.string.setcat_animated_covers_folder_remove_title),
            subtitle = stringResource(R.string.setcat_animated_covers_folder_remove_desc),
            leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.secondary) },
            onClick = { actions.onAnimatedCoverFolderRemove() },
        )
    }
}
