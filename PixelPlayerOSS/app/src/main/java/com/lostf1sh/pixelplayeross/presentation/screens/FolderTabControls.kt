package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.library.folderHasPlayableSongs
import com.lostf1sh.pixelplayeross.data.library.folderRelativePath
import com.lostf1sh.pixelplayeross.data.model.FolderSource
import com.lostf1sh.pixelplayeross.data.model.MusicFolder
import com.lostf1sh.pixelplayeross.presentation.components.ToggleSegmentButton
import com.lostf1sh.pixelplayeross.presentation.components.GlassModalBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.ui.ds.*

internal fun folderBreadcrumb(tree: List<MusicFolder>, path: String?): List<MusicFolder> {
    if (path == null) return emptyList()
    for (folder in tree) {
        if (folder.path == path) return listOf(folder)
        val child = folderBreadcrumb(folder.subFolders, path)
        if (child.isNotEmpty()) return listOf(folder) + child
    }
    return emptyList()
}

/** Prefer the volume label supplied by Android; mount UUIDs are only the existing last-resort fallback. */
internal fun folderVolumeLabel(context: android.content.Context, root: String, fallback: String): String = runCatching {
    context.getSystemService(android.os.storage.StorageManager::class.java)?.getStorageVolume(java.io.File(root))
        ?.getDescription(context)?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull() ?: fallback

@Composable
internal fun FolderTabControls(
    flatList: Boolean, onFlatListChange: (Boolean) -> Unit,
    showSourceSwitch: Boolean, source: FolderSource, onSourceChange: (FolderSource) -> Unit,
    query: String, onQueryChange: (String) -> Unit,
    volumeRoots: List<String> = emptyList(), selectedVolumeRoot: String = "", internalVolumeRoot: String = "",
    onVolumeChange: (String) -> Unit = {},
    showSearch: Boolean = true
) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val descriptions=androidx.compose.runtime.remember(context,volumeRoots) { volumeRoots.associateWith { folderVolumeLabel(context,it,"") } }
    Column(Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Sm), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showSourceSwitch && volumeRoots.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(volumeRoots.size) { index ->
                    val root = volumeRoots[index]
                    val internal = root == internalVolumeRoot
                    val label = if (!internal && !descriptions[root].isNullOrBlank()) descriptions.getValue(root) else if (internal) stringResource(R.string.presentation_batch_d_storage_internal)
                        else stringResource(R.string.presentation_batch_d_storage_sd_card) +
                            (if (volumeRoots.count { it != internalVolumeRoot } > 1) " · " + java.io.File(root).name else "")
                    val coverScheme = com.lostf1sh.pixelplayeross.ui.ds.LocalDapCoverScheme.current
                    ToggleSegmentButton(
                        modifier = Modifier.width(148.dp).height(48.dp), active = root == selectedVolumeRoot,
                        activeColor = LocalDapAccent.current, inactiveColor = coverScheme?.secondaryContainer ?: Dap.surfaceRaised,
                        activeContentColor = Dap.onFill, inactiveContentColor = coverScheme?.onSecondaryContainer ?: Dap.secondaryText,
                        activeCornerRadius = DapRadius.Surface, onClick = { onVolumeChange(root) }, text = label
                    )
                }
            }
        } else if (showSourceSwitch) FolderSegments(
            stringResource(R.string.presentation_batch_d_storage_internal),
            stringResource(R.string.presentation_batch_d_storage_sd_card), source == FolderSource.SD_CARD
        ) { onSourceChange(if (it) FolderSource.SD_CARD else FolderSource.INTERNAL) }
        FolderSegments(stringResource(R.string.folders_tree), stringResource(R.string.presentation_batch_d_view_list), flatList, onFlatListChange)
        if (showSearch) OutlinedTextField(
            value = query, onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.folders_search)) },
            colors = com.lostf1sh.pixelplayeross.ui.ds.dapOutlinedTextFieldColors(),
            singleLine = true, modifier = Modifier.fillMaxWidth().dapTextField()
        )
    }
}

@Composable
private fun FolderSegments(first: String, second: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    // The DS segmented picker (Settings uses it): one quiet track, the chosen half filled. Not two big blocks.
    DapSegmentedPicker(
        options = listOf(false, true),
        selected = selected,
        onSelect = onChange,
        label = { if (it) second else first },
        fillWidth = true,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun FolderActionsSheet(
    folder: MusicFolder,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    onDismiss: () -> Unit,
    onPlay: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
) {
    val hasSongs = folderHasPlayableSongs(folder)
    GlassModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ModalInputBridge()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DapSpacing.Gutter)
        ) {
            Text(folder.name, style = DapType.Title, color = Dap.primaryText)
            DapNavRow(label = stringResource(R.string.play_playback), enabled = hasSongs, onClick = onPlay)
            DapNavRow(label = stringResource(R.string.shortcut_shuffle_short), enabled = hasSongs, onClick = onShuffle)
            DapNavRow(label = stringResource(R.string.action_queue_next), enabled = hasSongs, onClick = onPlayNext)
            DapNavRow(label = stringResource(R.string.action_add_to_queue), enabled = hasSongs, onClick = onAddToQueue)
            DapNavRow(label = stringResource(R.string.folders_open), onClick = onOpen)
            DapNavRow(label = stringResource(R.string.folders_actions), onClick = onActions)
            Spacer(Modifier.height(DapSpacing.Md))
        }
    }
}
