package com.lostf1sh.pixelplayeross.presentation.settings

import android.content.Context
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.backup.model.BackupHistoryEntry
import com.lostf1sh.pixelplayeross.data.backup.model.BackupOperationType
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.ModuleRestoreDetail
import com.lostf1sh.pixelplayeross.data.backup.model.RestorePlan
import com.lostf1sh.pixelplayeross.presentation.components.BackupModuleSelectionDialog
import com.lostf1sh.pixelplayeross.presentation.components.DapOverlayBack
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.presentation.components.rememberBackupTimestamp
import com.lostf1sh.pixelplayeross.presentation.model.labelRes
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalBackNavigationMode
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapSwitchRow
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.dapDismissKeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import kotlinx.collections.immutable.ImmutableList

internal fun buildBackupSelectionSummary(context: Context, selected: Set<BackupSection>): String {
    if (selected.isEmpty()) return context.getString(R.string.backup_summary_none)
    val total = BackupSection.entries.size
    return if (selected.size == total) {
        context.getString(R.string.backup_summary_all)
    } else {
        context.getString(R.string.backup_summary_partial, selected.size, total)
    }
}

internal fun backupSectionIconRes(section: BackupSection): Int {
    return section.iconRes
}

/**
 * The one-time explanation of what a backup contains.
 *
 * Was a filled tonal card — a primary-container Surface at 55% alpha with a 20 dp radius, a tinted
 * icon and a Material IconButton. On a page whose every other block is a hairline-separated group,
 * that read as a different application's component. It is now a section header with prose under it,
 * which is how this product states something once.
 */
@Composable
internal fun BackupInfoNotice(onDismiss: () -> Unit) {
    DapGroup(
        title = stringResource(R.string.backup_how_title),
        trailing = {
            DapTextAction(
                label = stringResource(R.string.cd_close_notice),
                onClick = onDismiss,
                color = Dap.tertiaryText,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.backup_how_body),
            style = DapType.Caption,
            color = Dap.secondaryText,
            modifier = Modifier.padding(bottom = DapSpacing.Sm),
        )
        DapDivider()
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BackupSectionSelectionDialog(
    operation: BackupOperationType,
    title: String,
    supportingText: String,
    selectedSections: Set<BackupSection>,
    confirmLabel: String,
    inProgress: Boolean,
    onDismiss: () -> Unit,
    onSelectionChanged: (Set<BackupSection>) -> Unit,
    onConfirm: () -> Unit
) {
    val listState = rememberLazyListState()
    val selectedCount = selectedSections.size
    val totalCount = BackupSection.entries.size
    val transitionState = remember { MutableTransitionState(false) }
    var shouldShowDialog by remember { mutableStateOf(true) }
    var onDialogHiddenAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    transitionState.targetState = shouldShowDialog

    fun closeDialog(afterClose: () -> Unit) {
        if (!shouldShowDialog) return
        onDialogHiddenAction = afterClose
        shouldShowDialog = false
    }

    LaunchedEffect(transitionState.currentState, transitionState.targetState) {
        if (!transitionState.currentState && !transitionState.targetState) {
            onDialogHiddenAction?.let { action ->
                onDialogHiddenAction = null
                action()
            }
        }
    }

    if (transitionState.currentState || transitionState.targetState) {
        Dialog(
            onDismissRequest = { closeDialog(onDismiss) },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            // This window is its own surface, so the activity's volume-key path does not
            // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
            ModalInputBridge()
            AnimatedVisibility(
                visibleState = transitionState,
                enter = slideInVertically(initialOffsetY = { it / 6 }) + fadeIn(animationSpec = tween(220)),
                exit = slideOutVertically(targetOffsetY = { it / 6 }) + fadeOut(animationSpec = tween(200)),
                label = "backup_section_dialog"
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentWindowInsets = WindowInsets.systemBars,
                        topBar = {
                            // Inventory: a title and one navigation Close (`closeDialog(onDismiss)`, cd `cd_close`).
                            // No actions. The callback is re-hosted unchanged.
                            Column {
                                val showsBackControl =
                                    LocalBackNavigationMode.current.showsVisibleBackAffordance
                                DapScreenTitle(
                                    text = title,
                                    modifier = Modifier.padding(
                                        start = if (showsBackControl) DapSizes.MinTouchTarget else 16.dp,
                                        end = 16.dp,
                                    ),
                                )
                            }
                        },
                        bottomBar = {
                            // A DAP action bar: the two selection actions as text actions on the
                            // left, the confirm as the product's primary button on the right. The
                            // Material bar put them in a filled BottomAppBar as two circular icon
                            // buttons and an ExtendedFloatingActionButton — the one shape in this
                            // surface that unmistakably came from another design system.
                            // Two lines rather than one. The selection actions and the confirm
                            // button fit side by side in English and did not in Spanish, where the
                            // confirm label truncated to "Exp…". The action that commits the
                            // operation is the one that must never be abbreviated, so it gets its
                            // own full-width line and the secondary actions sit above it.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Dap.surface)
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .padding(
                                        horizontal = DapSpacing.Gutter,
                                        vertical = DapSpacing.Sm,
                                    ),
                                verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(DapSpacing.Xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    DapTextAction(
                                        label = stringResource(R.string.cd_select_all),
                                        onClick = { onSelectionChanged(BackupSection.entries.toSet()) },
                                        enabled = !inProgress,
                                    )
                                    DapTextAction(
                                        label = stringResource(R.string.cd_clear_selection),
                                        onClick = { onSelectionChanged(emptySet()) },
                                        enabled = !inProgress,
                                    )
                                }

                                if (inProgress) {
                                    DapSecondaryButton(
                                        label = if (operation == BackupOperationType.EXPORT) {
                                            stringResource(R.string.backup_exporting)
                                        } else {
                                            stringResource(R.string.backup_importing)
                                        },
                                        onClick = {},
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = false,
                                    )
                                } else {
                                    DapPrimaryButton(
                                        label = confirmLabel,
                                        onClick = { closeDialog(onConfirm) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .padding(innerPadding)
                                .fillMaxSize()
                                .padding(horizontal = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = DapSpacing.SectionGap)
                            ) {
                                DapGroup(
                                    title = stringResource(R.string.backup_sections_selected_format, selectedCount, totalCount)
                                ) {
                                    Text(
                                        text = supportingText,
                                        style = DapType.Caption,
                                        color = Dap.secondaryText,
                                        modifier = Modifier.padding(bottom = DapSpacing.Sm)
                                    )
                                    if (inProgress) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            LoadingIndicator(modifier = Modifier.height(24.dp))
                                            Text(
                                                text = stringResource(R.string.backup_transfer_in_progress),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 18.dp),
                                // No gap: each row draws its own hairline, as on every other
                                // settings page. The 10 dp gaps were card spacing.
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                items(BackupSection.entries, key = { it.key }) { section ->
                                    val isSelected = section in selectedSections
                                    BackupSectionSelectableRow(
                                        section = section,
                                        selected = isSelected,
                                        enabled = !inProgress,
                                        onToggle = {
                                            onSelectionChanged(
                                                if (isSelected) selectedSections - section else selectedSections + section
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                        DapOverlayBack(
                            onClick = { closeDialog(onDismiss) },
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One restorable section, on a row rather than in a card.
 *
 * This was a Material card: a 22 dp rounded container with a 2.5 dp animated border, tonal
 * elevation when selected, a 48 dp icon tile, a 1.55 sp title in a different typeface, and a
 * Material3 switch with animated check/cross thumb content. Eleven of them made a wall of cards,
 * and a card that grows a border and lifts off the page when you tick it reads as a destination
 * rather than as a checkbox.
 *
 * It is now the same row vocabulary every other Settings surface uses: label, description in
 * Manrope, and a switch. The count of entries that would be replaced moves into the description,
 * where it is prose, instead of a third line in a third colour.
 */
@Composable
internal fun BackupSectionSelectableRow(
    section: BackupSection,
    selected: Boolean,
    enabled: Boolean,
    detail: ModuleRestoreDetail? = null,
    onToggle: () -> Unit
) {
    val description = buildString {
        append(stringResource(section.descriptionRes))
        if (detail != null && detail.entryCount > 0) {
            append(' ')
            append(stringResource(R.string.backup_entries_will_replace, detail.entryCount))
        }
    }

    DapSwitchRow(
        label = stringResource(section.labelRes),
        description = description,
        checked = selected,
        onCheckedChange = { onToggle() },
        enabled = enabled,
        leading = {
            Icon(
                painter = painterResource(backupSectionIconRes(section)),
                contentDescription = null,
                tint = if (selected) LocalDapAccent.current else Dap.tertiaryText,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
internal fun ImportFileSelectionDialog(
    backupHistory: ImmutableList<BackupHistoryEntry>,
    isInspecting: Boolean,
    onDismiss: () -> Unit,
    onBrowseFile: () -> Unit,
    onHistoryItemSelected: (BackupHistoryEntry) -> Unit,
    onRemoveHistoryEntry: (BackupHistoryEntry) -> Unit
) {
    val context = LocalContext.current
    val transitionState = remember { MutableTransitionState(false) }
    var shouldShowDialog by remember { mutableStateOf(true) }
    var onDialogHiddenAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    transitionState.targetState = shouldShowDialog

    fun closeDialog(afterClose: () -> Unit) {
        if (!shouldShowDialog) return
        onDialogHiddenAction = afterClose
        shouldShowDialog = false
    }

    LaunchedEffect(transitionState.currentState, transitionState.targetState) {
        if (!transitionState.currentState && !transitionState.targetState) {
            onDialogHiddenAction?.let { action ->
                onDialogHiddenAction = null
                action()
            }
        }
    }

    if (transitionState.currentState || transitionState.targetState) {
        Dialog(
            onDismissRequest = { closeDialog(onDismiss) },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            // This window is its own surface, so the activity's volume-key path does not
            // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
            ModalInputBridge()
            AnimatedVisibility(
                visibleState = transitionState,
                enter = slideInVertically(initialOffsetY = { it / 6 }) + fadeIn(animationSpec = tween(220)),
                exit = slideOutVertically(targetOffsetY = { it / 6 }) + fadeOut(animationSpec = tween(200)),
                label = "import_file_selection_dialog"
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentWindowInsets = WindowInsets.systemBars,
                        topBar = {
                            // Inventory: a title and one navigation Close (`closeDialog(onDismiss)`, cd `cd_close`).
                            // No actions. The callback is re-hosted unchanged.
                            Column {
                                val showsBackControl =
                                    LocalBackNavigationMode.current.showsVisibleBackAffordance
                                DapScreenTitle(
                                    text = stringResource(R.string.import_backup_toolbar_title),
                                    modifier = Modifier.padding(
                                        start = if (showsBackControl) DapSizes.MinTouchTarget else 16.dp,
                                        end = 16.dp,
                                    ),
                                )
                            }
                        },
                        bottomBar = {
                            // Same DAP action bar as the export picker: the browse action is the
                            // primary decision on this screen, so it is the product's primary
                            // button and not an ExtendedFloatingActionButton.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Dap.surface)
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .padding(
                                        horizontal = DapSpacing.Gutter,
                                        vertical = DapSpacing.Sm,
                                    ),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isInspecting) {
                                    DapSecondaryButton(
                                        label = stringResource(R.string.import_inspecting),
                                        onClick = {},
                                        enabled = false,
                                    )
                                } else {
                                    DapPrimaryButton(
                                        label = stringResource(R.string.import_browse_file),
                                        onClick = onBrowseFile,
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .padding(innerPadding)
                                .fillMaxSize()
                                .padding(horizontal = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DapGroup(
                                title = stringResource(R.string.import_recent_backups),
                                modifier = Modifier.padding(top = DapSpacing.SectionGap),
                            ) {
                                Text(
                                    text = stringResource(R.string.import_backup_hint),
                                    style = DapType.Caption,
                                    color = Dap.secondaryText,
                                    modifier = Modifier.padding(bottom = DapSpacing.Sm),
                                )
                            }

                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                if (backupHistory.isEmpty()) {
                                    item {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = DapSpacing.SectionGap),
                                            verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.import_no_recent_title),
                                                style = DapType.SettingLabel,
                                                color = Dap.secondaryText,
                                            )
                                            Text(
                                                text = stringResource(R.string.import_no_recent_subtitle),
                                                style = DapType.Caption,
                                                color = Dap.tertiaryText,
                                            )
                                        }
                                    }
                                } else {
                                    items(backupHistory, key = { it.uri }) { entry ->
                                        BackupHistoryRow(
                                            entry = entry,
                                            context = context,
                                            onSelect = { onHistoryItemSelected(entry) },
                                            onRemove = { onRemoveHistoryEntry(entry) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                        DapOverlayBack(
                            onClick = { closeDialog(onDismiss) },
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BackupHistoryRow(
    entry: BackupHistoryEntry,
    context: Context,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    val unknownShort = stringResource(R.string.presentation_batch_f_unknown_short)
    val dateText = rememberBackupTimestamp(entry.createdAt)
    val sizeText = remember(entry.sizeBytes) {
        if (entry.sizeBytes > 0) Formatter.formatShortFileSize(context, entry.sizeBytes) else ""
    }
    val moduleCount = entry.modules.size

    // A compact row rather than a card, and the whole row is the picker. The version/schema footer
    // is a technical reading about the file, so it stays mono and stays a value.
    DapNavRow(
        label = entry.displayName,
        description = dateText +
            if (sizeText.isNotEmpty()) {
                stringResource(R.string.presentation_batch_f_dot_space_suffix, sizeText)
            } else {
                ""
            },
        value = stringResource(
            R.string.backup_history_modules_line,
            moduleCount,
            entry.appVersion.ifEmpty { unknownShort },
            entry.schemaVersion
        ),
        onClick = onSelect,
        trailing = {
            DapTextAction(
                label = stringResource(R.string.cd_remove_from_history),
                onClick = onRemove,
                color = Dap.tertiaryText,
            )
            Spacer(Modifier.width(DapSpacing.Sm))
            DapIcon(
                kind = DapIconKind.ChevronRight,
                tint = Dap.disabledText,
                size = 14.dp,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ImportModuleSelectionDialog(
    plan: RestorePlan,
    inProgress: Boolean,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onSelectionChanged: (Set<BackupSection>) -> Unit,
    onConfirm: () -> Unit
) {
    BackupModuleSelectionDialog(
        plan = plan,
        inProgress = inProgress,
        onDismiss = onDismiss,
        onBack = onBack,
        onSelectionChanged = onSelectionChanged,
        onConfirm = onConfirm
    )
}

/**
 * Optional-encryption step shown between section selection and the actual
 * export. Confirms with `null` for a plain backup or the passphrase when the
 * user enables encryption.
 */
@Composable
internal fun BackupEncryptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var encryptEnabled by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    val passwordTooShort = encryptEnabled && password.isNotEmpty() && password.length < 4
    val passwordsMismatch = encryptEnabled && confirmPassword.isNotEmpty() && password != confirmPassword
    val canConfirm = !encryptEnabled || (password.length >= 4 && password == confirmPassword)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_encrypt_title)) },
        text = {
            // An alert is a window of its own, so the activity's volume-key path does not reach
            // it. The bridge routes Volume Up/Down to the same authority the activity uses.
            ModalInputBridge()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.backup_encrypt_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = encryptEnabled,
                        onCheckedChange = { encryptEnabled = it }
                    )
                }
                if (encryptEnabled) {
                    OutlinedTextField(
                        value = password,
                        keyboardActions = dapDismissKeyboardActions(),
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.backup_password_hint)) },
                        singleLine = true,
                        isError = passwordTooShort,
                        supportingText = if (passwordTooShort) {
                            { Text(stringResource(R.string.backup_password_too_short)) }
                        } else null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().dapTextField()
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        keyboardActions = dapDismissKeyboardActions(),
                        onValueChange = { confirmPassword = it },
                        label = { Text(stringResource(R.string.backup_password_confirm_hint)) },
                        singleLine = true,
                        isError = passwordsMismatch,
                        supportingText = if (passwordsMismatch) {
                            { Text(stringResource(R.string.backup_password_mismatch)) }
                        } else null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().dapTextField()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(if (encryptEnabled) password else null) }
            ) { Text(stringResource(R.string.backup_confirm_export_pxpl), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    )
}

/** Password prompt for opening an encrypted backup. */
@Composable
internal fun BackupPasswordPromptDialog(
    wrongPassword: Boolean,
    isInspecting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isInspecting) onDismiss() },
        title = { Text(stringResource(R.string.backup_encrypted_prompt_title)) },
        text = {
            // An alert is a window of its own, so the activity's volume-key path does not reach
            // it. The bridge routes Volume Up/Down to the same authority the activity uses.
            ModalInputBridge()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.backup_encrypted_prompt_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = password,
                    keyboardActions = dapDismissKeyboardActions(),
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.backup_password_hint)) },
                    singleLine = true,
                    enabled = !isInspecting,
                    isError = wrongPassword,
                    supportingText = if (wrongPassword) {
                        { Text(stringResource(R.string.backup_wrong_password)) }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().dapTextField()
                )
                if (isInspecting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && !isInspecting,
                onClick = { onConfirm(password) }
            ) { Text(stringResource(R.string.backup_unlock), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        dismissButton = {
            TextButton(enabled = !isInspecting, onClick = onDismiss) {
                Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    )
}
