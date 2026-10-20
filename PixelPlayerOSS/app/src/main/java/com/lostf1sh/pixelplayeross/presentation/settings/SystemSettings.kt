package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow

/** What the System page draws. */
@Immutable
data class SystemSettingsState(
    /** Items whose file is gone and that hold nothing of the listener's; null while they are being counted. */
    val purgeableDormantCount: Int? = null,
    /** A rebuild was asked for and is running: its row waits until the page is left. */
    val isRebuilding: Boolean = false,
    /** Whether this device has a USB preferences screen to open. */
    val usbSettingsAvailable: Boolean = true,
    /**
     * B34 (U6): whether Android's own shortcuts (Android Settings, USB, Wi-Fi, Bluetooth) are offered. Only Lock Task blocks them, so they are
     * offered while the Dedicated level is active or the device is a Device Owner (`ProductModeState.offersAndroidShortcuts`); on a phone at
     * Launcher or Pinned level, or in App Mode, the listener reaches Android normally. Live: the host reads it again as the mode changes.
     */
    val showAndroidShortcuts: Boolean = true,
)

/**
 * Everything the System page can do (B32-Q6). Every action that destroys something or leaves the DAP is asked "are you sure?" by the page
 * before it is called, in a dialog that says what it does: the actions below are what a "yes" runs, and there is no PIN anywhere.
 */
interface SystemSettingsActions {
    // Android's own screens. Each lifts Lock Task first and the app puts it back on return; no question is asked.
    fun onOpenAndroidSettings()
    fun onOpenUsbSettings()
    fun onOpenWifiSettings()
    fun onOpenBluetoothSettings()

    // The library.
    fun onRebuildDatabase()
    fun onDeleteImportedLyrics()
    fun onCleanUpItemsWithoutFile()

    /** The technical page: logs, diagnostics, overrides. */
    fun onOpenMaintenance()
}

/** What the System page asks to confirm, one at a time. */
private enum class SystemConfirmation { REBUILD, LYRICS, CLEANUP }

/**
 * The System page: Android's own settings, repairing the library and Maintenance, in the Settings rows. It is what Maintenance's recovery
 * half was, with the PIN taken away (B32-Q6); Maintenance itself keeps the logs and the technical tools and is one row below. Leaving the
 * DAP (the home picker, "Volver al DAP") moved to "Inicio y modo DAP" (B34): it is the same subject as the levels.
 *
 * [mountInputBridge] is true in the app (a dialog is a window of its own and the volume keys must still reach the Safety authority);
 * a JVM render has no Hilt component to take it from.
 */
@Composable
fun SystemSettingsScreen(
    state: SystemSettingsState,
    actions: SystemSettingsActions,
    mountInputBridge: Boolean = true,
) {
    var confirming by remember { mutableStateOf<SystemConfirmation?>(null) }

    if (state.showAndroidShortcuts) {
        SettingsSubsection(title = stringResource(R.string.maintenance_system_section)) {
            SystemRow(Icons.Rounded.Settings, R.string.maintenance_open_settings, onClick = actions::onOpenAndroidSettings)
            SystemRow(
                Icons.Rounded.Usb,
                R.string.maintenance_open_usb,
                subtitle = if (state.usbSettingsAvailable) null else stringResource(R.string.maintenance_not_available),
                enabled = state.usbSettingsAvailable,
                onClick = actions::onOpenUsbSettings,
            )
            SystemRow(Icons.Rounded.Wifi, R.string.maintenance_open_wifi, onClick = actions::onOpenWifiSettings)
            SystemRow(Icons.Rounded.Bluetooth, R.string.maintenance_open_bluetooth, onClick = actions::onOpenBluetoothSettings)
        }
    }

    SettingsSubsection(title = stringResource(R.string.presentation_batch_f_recovery_section)) {
        SystemRow(
            Icons.Outlined.DeleteForever,
            R.string.presentation_batch_f_rebuild_database,
            subtitle = stringResource(R.string.system_rebuild_database_subtitle),
            enabled = !state.isRebuilding,
        ) { confirming = SystemConfirmation.REBUILD }
        SystemRow(
            Icons.Outlined.ClearAll,
            R.string.setcat_reset_imported_lyrics_title,
            subtitle = stringResource(R.string.setcat_reset_imported_lyrics_subtitle),
        ) { confirming = SystemConfirmation.LYRICS }
        // Manual only (AUD-106): items without a file are never purged automatically, and only those with no user state at all are offered.
        SystemRow(
            Icons.Outlined.DeleteSweep,
            R.string.maintenance_dormant_cleanup_title,
            subtitle = state.purgeableDormantCount
                ?.let { stringResource(R.string.maintenance_dormant_cleanup_subtitle, it) }
                ?: stringResource(R.string.maintenance_dormant_cleanup_counting),
            enabled = (state.purgeableDormantCount ?: 0) > 0,
        ) { confirming = SystemConfirmation.CLEANUP }
    }

    SettingsSubsection(title = stringResource(R.string.maintenance_internal_tools_section), addBottomSpace = false) {
        SystemRow(
            Icons.Rounded.DeveloperBoard,
            R.string.setcat_maintenance,
            subtitle = stringResource(R.string.system_maintenance_subtitle),
            onClick = actions::onOpenMaintenance,
        )
    }

    confirming?.let { which ->
        val dismiss = { confirming = null }
        val run: (() -> Unit) -> Unit = { action ->
            confirming = null
            action()
        }
        when (which) {
            SystemConfirmation.REBUILD -> ConfirmDialog(
                title = R.string.dialog_rebuild_database_title,
                body = stringResource(R.string.dialog_rebuild_database_message),
                confirm = R.string.rebuild,
                destructive = true,
                mountInputBridge = mountInputBridge,
                onDismiss = dismiss,
                onConfirm = { run(actions::onRebuildDatabase) },
            )
            SystemConfirmation.LYRICS -> ConfirmDialog(
                title = R.string.system_confirm_lyrics_title,
                body = stringResource(R.string.setcat_reset_imported_lyrics_subtitle),
                confirm = R.string.confirm,
                destructive = true,
                mountInputBridge = mountInputBridge,
                onDismiss = dismiss,
                onConfirm = { run(actions::onDeleteImportedLyrics) },
            )
            SystemConfirmation.CLEANUP -> ConfirmDialog(
                title = R.string.maintenance_dormant_cleanup_confirm_title,
                body = stringResource(R.string.maintenance_dormant_cleanup_confirm_body, state.purgeableDormantCount ?: 0),
                confirm = R.string.maintenance_dormant_cleanup_action,
                destructive = true,
                mountInputBridge = mountInputBridge,
                onDismiss = dismiss,
                onConfirm = { run(actions::onCleanUpItemsWithoutFile) },
            )
        }
    }
}

@Composable
internal fun SystemRow(
    icon: ImageVector,
    title: Int,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DapNavRow(
        label = stringResource(title),
        description = subtitle,
        enabled = enabled,
        onClick = onClick,
        leading = { Icon(icon, null, tint = MaterialTheme.colorScheme.secondary) },
    )
}

@Composable
internal fun ConfirmDialog(
    title: Int,
    body: String,
    confirm: Int,
    destructive: Boolean,
    mountInputBridge: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        icon = if (destructive) {
            { Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error) }
        } else null,
        title = {
            // An alert is a window of its own, so the activity's volume-key path does not reach it. The bridge routes Volume Up/Down to
            // the same authority the activity uses.
            if (mountInputBridge) {
                ModalInputBridge()
            }
            Text(stringResource(title))
        },
        text = { Text(body) },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(stringResource(confirm), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
    )
}
