package com.lostf1sh.pixelplayeross.presentation.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.backup.model.BackupHistoryEntry
import com.lostf1sh.pixelplayeross.data.backup.model.BackupOperationType
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.RestorePlan
import com.lostf1sh.pixelplayeross.presentation.screens.ActionSettingsItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

/** What the Backup page draws. */
@Immutable
data class BackupSettingsState(
    val infoDismissed: Boolean = false,
    val isDataTransferInProgress: Boolean = false,
    val restorePlan: RestorePlan? = null,
    val backupHistory: ImmutableList<BackupHistoryEntry> = persistentListOf(),
    val isInspectingBackup: Boolean = false,
    val pendingEncryptedBackupUri: String? = null,
    val wrongBackupPassword: Boolean = false,
)

/** Everything the Backup page can do. The file pickers and dialogs are the page's own; what they decide goes through here. */
interface BackupSettingsActions {
    fun onDismissInfo()
    fun onExport(uri: Uri, sections: Set<BackupSection>, passphrase: String?)
    fun onInspectBackup(uri: Uri, passphrase: String?)
    fun onDismissEncryptedBackupPrompt()
    fun onClearRestorePlan()
    fun onUpdateRestorePlanSelection(selected: Set<BackupSection>)
    fun onRestore(uri: Uri)
    fun onRemoveBackupHistoryEntry(entry: BackupHistoryEntry)
}

@Composable
fun BackupSettingsScreen(state: BackupSettingsState, actions: BackupSettingsActions) {
    val context = LocalContext.current
    var showExportDataDialog by remember { mutableStateOf(false) }
    var showImportFlow by remember { mutableStateOf(false) }
    var exportSections by remember { mutableStateOf(BackupSection.defaultSelection) }
    var importFileUri by remember { mutableStateOf<Uri?>(null) }
    var showExportEncryptionDialog by remember { mutableStateOf(false) }
    var exportPassphrase by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            actions.onExport(uri, exportSections, exportPassphrase)
        }
        exportPassphrase = null
    }

    val importFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            importFileUri = uri
            actions.onInspectBackup(uri, null)
        }
    }

    if (!state.infoDismissed) {
        BackupInfoNotice(
            onDismiss = { actions.onDismissInfo() }
        )
        Spacer(modifier = Modifier.height(10.dp))
    }

    SettingsSubsection(title = stringResource(R.string.setcat_create_backup)) {
        ActionSettingsItem(
            title = stringResource(R.string.setcat_export_backup_title),
            subtitle = stringResource(
                R.string.setcat_export_backup_subtitle,
                buildBackupSelectionSummary(context, exportSections)
            ),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.outline_save_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            },
            primaryActionLabel = stringResource(R.string.setcat_select_export),
            onPrimaryAction = { showExportDataDialog = true },
            enabled = !state.isDataTransferInProgress,
        )
    }

    SettingsSubsection(
        title = stringResource(R.string.setcat_restore_backup_section),
        addBottomSpace = false
    ) {
        ActionSettingsItem(
            title = stringResource(R.string.setcat_import_backup_title),
            subtitle = stringResource(R.string.setcat_import_backup_subtitle),
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Restore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            },
            primaryActionLabel = stringResource(R.string.setcat_select_restore),
            onPrimaryAction = { showImportFlow = true },
            enabled = !state.isDataTransferInProgress,
        )
    }

    if (showExportDataDialog) {
        BackupSectionSelectionDialog(
            operation = BackupOperationType.EXPORT,
            title = stringResource(R.string.setcat_export_backup_title),
            supportingText = stringResource(R.string.backup_dialog_supporting_export),
            selectedSections = exportSections,
            confirmLabel = stringResource(R.string.backup_confirm_export_pxpl),
            inProgress = state.isDataTransferInProgress,
            onDismiss = { showExportDataDialog = false },
            onSelectionChanged = { exportSections = it },
            onConfirm = {
                showExportDataDialog = false
                showExportEncryptionDialog = true
            }
        )
    }

    if (showExportEncryptionDialog) {
        BackupEncryptionDialog(
            onDismiss = { showExportEncryptionDialog = false },
            onConfirm = { passphrase ->
                showExportEncryptionDialog = false
                exportPassphrase = passphrase
                val fileName = context.getString(R.string.backup_file_name_format, System.currentTimeMillis())
                exportLauncher.launch(fileName)
            }
        )
    }

    val pendingEncryptedUri = state.pendingEncryptedBackupUri
    if (pendingEncryptedUri != null) {
        BackupPasswordPromptDialog(
            wrongPassword = state.wrongBackupPassword,
            isInspecting = state.isInspectingBackup,
            onDismiss = { actions.onDismissEncryptedBackupPrompt() },
            onConfirm = { password ->
                actions.onInspectBackup(Uri.parse(pendingEncryptedUri), password)
            }
        )
    }

    if (showImportFlow) {
        val restorePlan = state.restorePlan
        if (restorePlan != null && importFileUri != null) {
            ImportModuleSelectionDialog(
                plan = restorePlan,
                inProgress = state.isDataTransferInProgress,
                onDismiss = {
                    showImportFlow = false
                    importFileUri = null
                    actions.onClearRestorePlan()
                },
                onBack = {
                    importFileUri = null
                    actions.onClearRestorePlan()
                },
                onSelectionChanged = { actions.onUpdateRestorePlanSelection(it) },
                onConfirm = {
                    actions.onRestore(importFileUri!!)
                    showImportFlow = false
                    importFileUri = null
                }
            )
        } else {
            ImportFileSelectionDialog(
                backupHistory = state.backupHistory,
                isInspecting = state.isInspectingBackup,
                onDismiss = {
                    showImportFlow = false
                    importFileUri = null
                    actions.onClearRestorePlan()
                },
                onBrowseFile = { importFilePicker.launch("*/*") },
                onHistoryItemSelected = { entry ->
                    val uri = entry.uri.toUri()
                    importFileUri = uri
                    actions.onInspectBackup(uri, null)
                },
                onRemoveHistoryEntry = { actions.onRemoveBackupHistoryEntry(it) }
            )
        }
    }
}
