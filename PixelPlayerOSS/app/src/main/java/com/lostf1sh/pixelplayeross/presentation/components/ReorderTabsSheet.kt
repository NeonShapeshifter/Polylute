@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapSwitch
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.LibraryTabId
import com.lostf1sh.pixelplayeross.data.model.toLibraryTabIdOrNull
import com.lostf1sh.pixelplayeross.presentation.utils.LocalAppHapticsConfig
import com.lostf1sh.pixelplayeross.presentation.utils.performAppCompatHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderTabsSheet(
    orderedTabs: ImmutableList<String>,
    hiddenTabs: Set<String>,
    onSave: (order: List<String>, hidden: Set<String>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var showResetDialog by remember { mutableStateOf(false) }
    var localTabs by remember { mutableStateOf(orderedTabs) }
    var localHidden by remember { mutableStateOf(hiddenTabs) }

    LaunchedEffect(orderedTabs, hiddenTabs) {
        localTabs = orderedTabs
        localHidden = hiddenTabs
    }

    // Hiding is a filter, never a removal, and the Library must never be empty: the last visible
    // tab cannot be hidden.
    val canHideMore = localTabs.count { it !in localHidden } > 1

    val persist: () -> Unit = { onSave(localTabs.toList(), localHidden) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                // An alert is a window of its own, so the activity's volume-key path does not reach
                // it. The bridge routes Volume Up/Down to the same authority the activity uses.
                ModalInputBridge()
                Text(stringResource(R.string.reorder_tabs_reset_dialog_title))
            },
            text = { Text(stringResource(R.string.reorder_tabs_reset_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onReset()
                    localHidden = emptySet()
                    showResetDialog = false
                    onDismiss()
                }) {
                    Text(
                        stringResource(R.string.action_reset),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(
                        stringResource(R.string.cancel),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        )
    }

    val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val view = LocalView.current
    val appHapticsConfig = LocalAppHapticsConfig.current

    val reorderableState = rememberReorderableLazyListState(
        onMove = { from, to ->
            localTabs = localTabs.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }.toImmutableList()
            performAppCompatHapticFeedback(
                view,
                appHapticsConfig,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        },
        lazyListState = listState
    )

    ModalBottomSheet(
        onDismissRequest = {
            persist()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = Dap.surface
    ) {
        // This window is its own surface, so the activity's volume-key path does not
        // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
        ModalInputBridge()
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = DapSpacing.Gutter,
                        end = DapSpacing.Gutter,
                        bottom = DapSpacing.Md
                    ),
                verticalArrangement = Arrangement.spacedBy(DapSpacing.Md)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.library_edit_tabs_title),
                        style = DapType.Heading,
                        color = Dap.primaryText
                    )
                    Spacer(Modifier.height(DapSpacing.Xs))
                    Text(
                        text = stringResource(R.string.library_edit_tabs_hint),
                        style = DapType.Note,
                        color = Dap.tertiaryText
                    )
                }
                DapTextAction(
                    label = stringResource(R.string.action_reset),
                    onClick = { showResetDialog = true },
                    modifier = Modifier.align(Alignment.End),
                )
            }

            DapDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 420.dp)
            ) {
                items(localTabs, key = { it }) { rawId ->
                    // Falls back to the raw stored key rather than to a default tab, so an
                    // unrecognised id is visible in the editor instead of masquerading as Songs.
                    val tabId = rawId.toLibraryTabIdOrNull()
                    val label = tabId?.let { stringResource(it.titleRes) } ?: rawId
                    val isVisible = rawId !in localHidden

                    ReorderableItem(reorderableState, key = rawId) { isDragging ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isDragging) Dap.surfaceRaised else Color.Transparent)
                                .padding(
                                    start = DapSpacing.Gutter,
                                    end = DapSpacing.Gutter,
                                    top = DapSpacing.Md,
                                    bottom = DapSpacing.Md
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Everything left of the switch is the drag surface, so a drag never
                            // fights the control.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .longPressDraggableHandle(
                                        onDragStopped = {
                                            performAppCompatHapticFeedback(
                                                view,
                                                appHapticsConfig,
                                                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                                            )
                                        },
                                        enabled = true
                                    ),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    DapIcon(
                                        kind = DapIconKind.DragHandle,
                                        tint = Dap.disabledText,
                                        size = 14.dp
                                    )
                                    Spacer(Modifier.width(DapSpacing.Lg))
                                    Text(
                                        text = label,
                                        style = DapType.SettingLabel,
                                        color = if (isVisible) Dap.primaryText else Dap.tertiaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            DapSwitch(
                                checked = isVisible,
                                onCheckedChange = { wantVisible ->
                                    when {
                                        wantVisible -> localHidden = localHidden - rawId
                                        canHideMore -> localHidden = localHidden + rawId
                                    }
                                },
                                enabled = isVisible || canHideMore
                            )
                        }
                    }
                    DapDivider()
                }
            }

            Spacer(Modifier.height(DapSpacing.Lg))

            DapPrimaryButton(
                label = stringResource(R.string.action_done),
                onClick = {
                    persist()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DapSpacing.Gutter)
            )

            Spacer(Modifier.height(DapSpacing.SectionGap))
        }
    }
}
