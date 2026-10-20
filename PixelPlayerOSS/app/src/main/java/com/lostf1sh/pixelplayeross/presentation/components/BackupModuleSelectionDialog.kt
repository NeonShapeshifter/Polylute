package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.windowInsetsPadding
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapSwitchRow
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapTopBar
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.ModuleRestoreDetail
import com.lostf1sh.pixelplayeross.data.backup.model.RestorePlan
import com.lostf1sh.pixelplayeross.ui.theme.RoundedSans
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.presentation.components.DapOverlayBack
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalBackNavigationMode
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes

@OptIn(
    ExperimentalAnimationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun BackupModuleSelectionDialog(
    plan: RestorePlan,
    inProgress: Boolean,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onSelectionChanged: (Set<BackupSection>) -> Unit,
    onConfirm: () -> Unit
) {
    val listState = rememberLazyListState()
    val selectedCount = plan.selectedModules.size
    val availableCount = plan.availableModules.size
    val dateText = rememberBackupTimestamp(plan.manifest.createdAt)
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
                label = "import_module_selection_dialog"
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
                            // The bar held the title and one Back control, and nothing else. The
                            // title is page content; the control is now the overlay below, which
                            // — unlike the bar's own glyph — obeys the Back mode.
                            Column {
                                val showsBackControl =
                                    LocalBackNavigationMode.current.showsVisibleBackAffordance
                                DapScreenTitle(
                                    text = stringResource(R.string.presentation_batch_g_backup_restore_modules),
                                    modifier = Modifier.padding(
                                        start = if (showsBackControl) DapSizes.MinTouchTarget else 16.dp,
                                        end = 16.dp,
                                    ),
                                )
                            }
                        },
                        bottomBar = {
                            // The same DAP action bar as the export picker: secondary selection
                            // actions above, the committing action on its own full-width line.
                            // This bar was the last Material one in the backup flow — a
                            // BottomAppBar holding two FilledIconButtons and an
                            // ExtendedFloatingActionButton.
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
                                        label = stringResource(R.string.presentation_batch_g_backup_cd_select_all),
                                        onClick = { onSelectionChanged(plan.availableModules) },
                                        enabled = !inProgress,
                                    )
                                    DapTextAction(
                                        label = stringResource(R.string.presentation_batch_g_backup_cd_clear_selection),
                                        onClick = { onSelectionChanged(emptySet()) },
                                        enabled = !inProgress,
                                    )
                                }

                                if (inProgress) {
                                    DapSecondaryButton(
                                        label = stringResource(R.string.presentation_batch_g_backup_restoring),
                                        onClick = {},
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = false,
                                    )
                                } else {
                                    DapPrimaryButton(
                                        label = stringResource(R.string.presentation_batch_g_backup_restore_selected),
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
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.62f),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.presentation_batch_g_backup_details),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = stringResource(R.string.presentation_batch_g_backup_created),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = dateText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = stringResource(R.string.presentation_batch_g_backup_app_version),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = plan.manifest.appVersion.ifEmpty { stringResource(R.string.presentation_batch_g_backup_unknown) },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = stringResource(R.string.presentation_batch_g_backup_schema),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = stringResource(
                                                    R.string.backup_manifest_schema_v,
                                                    plan.manifest.schemaVersion
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        if (plan.manifest.deviceInfo.model.isNotBlank()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = stringResource(R.string.presentation_batch_g_backup_device),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = stringResource(
                                                        R.string.backup_manifest_device_line,
                                                        plan.manifest.deviceInfo.manufacturer,
                                                        plan.manifest.deviceInfo.model
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = stringResource(R.string.presentation_batch_g_backup_modules_selected, selectedCount, availableCount),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    if (inProgress) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            LoadingIndicator(modifier = Modifier.height(24.dp))
                                            Text(
                                                text = stringResource(R.string.presentation_batch_g_backup_transfer_progress),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            if (plan.warnings.isNotEmpty()) {
                                plan.warnings.forEach { warning ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = backupMessageText(warning),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(plan.availableModules.toList(), key = { it.key }) { section ->
                                    val isSelected = section in plan.selectedModules
                                    val detail = plan.moduleDetails[section]
                                    BackupSectionSelectableRowShared(
                                        section = section,
                                        selected = isSelected,
                                        enabled = !inProgress,
                                        detail = detail,
                                        onToggle = {
                                            onSelectionChanged(
                                                if (isSelected) {
                                                    plan.selectedModules - section
                                                } else {
                                                    plan.selectedModules + section
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                    // The Back affordance, floating over the dialog's content rather than
                    // reserving a bar row. Mode-aware, unlike the glyph it replaces.
                    DapOverlayBack(
                        onClick = { closeDialog(onBack) },
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                  }
                }
            }
        }
    }
}

/**
 * One restorable section, as a compact row.
 *
 * This was a second, private copy of the section card in `SettingsCategoryScreen`, differing only
 * in that it read `section.iconRes` directly. Both copies are now the same DAP row: label,
 * description in Manrope, a switch, and a hairline.
 */
@Composable
private fun BackupSectionSelectableRowShared(
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
                painter = painterResource(section.iconRes),
                contentDescription = null,
                tint = if (selected) LocalDapAccent.current else Dap.tertiaryText,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}
