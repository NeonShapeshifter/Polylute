package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.offline.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OfflineDownloadsViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.glass.frostedPanelOr
import kotlinx.coroutines.flow.Flow

@Composable
fun MusicInfoDownloadsScreen(songIds: List<String>?, onDismiss: () -> Unit, artistFilter: String? = null, viewModel: OfflineDownloadsViewModel = hiltViewModel()) {
    LaunchedEffect(songIds, artistFilter) { viewModel.open(songIds, artistFilter) }
    val artists = viewModel.artists.collectAsLazyPagingItems()
    val query by viewModel.search.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle(null)
    val find by viewModel.findProgress.collectAsStateWithLifecycle(InfoProgress(0, 0, 0))
    val download by viewModel.downloadProgress.collectAsStateWithLifecycle(InfoProgress(0, 0, 0))
    val selection by viewModel.selection.collectAsStateWithLifecycle(InfoSelection(0, 0, null, 0))
    val opening by viewModel.opening.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ModalInputBridge()
        com.lostf1sh.pixelplayeross.ui.glass.GlassWindowBackdrop(com.lostf1sh.pixelplayeross.ui.glass.LocalCoverGlass.current)
        val glass = com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette.current.glass != null
        Surface(Modifier.fillMaxSize(), color = if (glass) androidx.compose.ui.graphics.Color.Transparent else Dap.background) {
            MusicInfoDownloadsContent(artists, query, { viewModel.search.value = it }, queue, find, download, selection,
                opening, error, viewModel::choices, viewModel::find, viewModel::download, viewModel::delete,
                viewModel::pause, viewModel::resume, viewModel::select, viewModel::selectKind, onDismiss, viewModel::selectGroup)
        }
    }
}

@Composable
internal fun MusicInfoDownloadsContent(
    artists: LazyPagingItems<InfoArtist>,
    search: String,
    onSearch: (String) -> Unit,
    queue: InfoQueue?,
    find: InfoProgress,
    download: InfoProgress,
    selection: InfoSelection,
    opening: Boolean,
    error: Boolean,
    choices: (String) -> Flow<List<InfoChoice>>,
    onFind: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSelect: (String, Boolean) -> Unit,
    onSelectKind: (InfoKind, Boolean) -> Unit,
    onClose: () -> Unit,
    onSelectGroup: (String, String?, Boolean) -> Unit = { _, _, _ -> },
) {
    val selected = selection.downloads + selection.deletes
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        LazyColumn(
            Modifier.weight(1f).testTag("info-artists"),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "heading") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.info_internet_title), Modifier.weight(1f), style = DapType.Title)
                    TextButton(onClick = onClose) { Text(stringResource(R.string.offline_download_close)) }
                }
            }
            item(key = "find") { InfoFindCard(find, queue, onFind) }
            item(key = "search") {
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.info_search)) },
                )
            }
            item(key = "kinds") {
                Column {
                    Text(stringResource(R.string.info_select_all), style = DapType.Caption)
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(InfoKind.entries.size) { index ->
                            val kind = InfoKind.entries[index]
                            var checked by rememberSaveable(kind) { mutableStateOf(false) }
                            FilterChip(
                                selected = checked,
                                onClick = {
                                    checked = !checked
                                    onSelectKind(kind, checked)
                                },
                                label = { Text(infoKindLabel(kind)) },
                            )
                        }
                    }
                }
            }
            if (selected == 0) {
                item(key = "empty-selection") {
                    Text(pluralStringResource(R.plurals.info_selected, 0, 0), style = DapType.Caption)
                }
            }
            if (opening) {
                item(key = "refresh") {
                    // Progress is a measured list item, never an overlay above the first artist.
                    Text(stringResource(R.string.info_refreshing_catalog), style = DapType.Caption)
                }
            }
            if (error) {
                item(key = "error") { Text(stringResource(R.string.offline_download_failed)) }
            }
            items(artists.itemCount, key = artists.itemKey { it.artistKey }) { index ->
                artists[index]?.let { artist ->
                    InfoArtistRow(artist, choices, onSelect, onSelectGroup)
                }
            }
        }
        if (selected > 0) {
            InfoSelectionBar(selection, onDownload, onDelete)
        }
        if (download.total > 0 || queue?.paused == true) {
            InfoQueueBar(queue, download, onPause, onResume)
        }
    }
}

@Composable
private fun InfoFindCard(progress: InfoProgress, queue: InfoQueue?, onFind: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = frostedPanelOr(MaterialTheme.colorScheme.surfaceContainer))) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.info_find_title), Modifier.weight(1f), style = DapType.Body)
                FilledTonalButton(onClick = onFind) { Text(stringResource(R.string.info_find)) }
            }
            Text(stringResource(R.string.info_find_note), style = DapType.Caption)
            if (progress.pending > 0) {
                InfoProgressRow(progress)
            } else if (progress.done > 0) {
                Text(stringResource(R.string.info_checked_result, progress.done, progress.total), style = DapType.Caption)
            }
            if (progress.pending > 0 && (queue?.findMeasuredCount ?: 0) > 0) {
                val remaining = (progress.total - progress.done) * queue!!.findMeasuredMs / queue.findMeasuredCount
                Text(
                    stringResource(R.string.offline_download_estimate, (remaining / 60_000L).coerceAtLeast(1)),
                    style = DapType.Caption,
                )
            }
            if (progress.retry > 0) {
                Text(pluralStringResource(R.plurals.info_could_not_check, progress.retry, progress.retry), style = DapType.Caption)
            }
        }
    }
}

@Composable
private fun InfoSelectionBar(selection: InfoSelection, onDownload: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val count = selection.downloads + selection.deletes
        Text(pluralStringResource(R.plurals.info_selected, count, count), style = DapType.Caption)
        selection.knownBytes?.let { bytes ->
            Text(
                stringResource(R.string.info_reported_size, android.text.format.Formatter.formatShortFileSize(
                    androidx.compose.ui.platform.LocalContext.current, bytes)),
                style = DapType.Caption,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selection.downloads > 0) {
                FilledTonalButton(onClick = onDownload) { Text(stringResource(R.string.info_download_selected)) }
            }
            if (selection.deletes > 0) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.info_delete_selected)) }
            }
        }
    }
}

@Composable
private fun InfoQueueBar(queue: InfoQueue?, progress: InfoProgress, onPause: () -> Unit, onResume: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            InfoProgressRow(progress)
            Text(queue?.currentArtist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = DapType.Caption)
            if (progress.retry > 0) {
                Text(stringResource(R.string.offline_download_retry, progress.retry), style = DapType.Caption)
            }
        }
        if (queue?.paused == true) {
            TextButton(onClick = onResume) { Text(stringResource(R.string.offline_download_resume)) }
        } else {
            TextButton(onClick = onPause) { Text(stringResource(R.string.offline_download_pause)) }
        }
    }
}

@Composable
private fun InfoProgressRow(progress: InfoProgress) {
    if (progress.total == 0) return
    LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.offline_download_progress_items, progress.done, progress.total), style = DapType.Caption)
}

@Composable
private fun InfoArtistRow(artist: InfoArtist, choices: (String) -> Flow<List<InfoChoice>>, onSelect: (String, Boolean) -> Unit, onSelectGroup: (String, String?, Boolean) -> Unit) {
    var groupSelected by rememberSaveable(artist.artistKey) { mutableStateOf(false) }
    var expanded by rememberSaveable(artist.artistKey) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = frostedPanelOr(MaterialTheme.colorScheme.surfaceContainer))) {
        Column {
            com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow(
                title = artist.artistName,
                modifier = Modifier.padding(horizontal = 12.dp),
                leading = { Text(if (expanded) "▾" else "▸") },
                trailing = {
                    InfoGroupCheckbox(groupSelected) { selected ->
                        groupSelected = selected
                        onSelectGroup(artist.artistKey, null, selected)
                    }
                },
                divider = false,
                onClick = { expanded = !expanded },
            )
            if (expanded) {
                val flow = remember(artist.artistKey) { choices(artist.artistKey) }
                val rows by flow.collectAsStateWithLifecycle(emptyList())
                for (row in rows.filter { it.albumKey.isEmpty() }) InfoChoiceRow(row, onSelect)
                for ((album, albumRows) in rows.filter { it.albumKey.isNotEmpty() }.groupBy { it.albumKey }) {
                    var albumSelected by rememberSaveable(album) { mutableStateOf(false) }
                    var albumExpanded by rememberSaveable(album) { mutableStateOf(false) }
                    com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow(
                        title = albumRows.first().albumName,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp),
                        leading = { Text(if (albumExpanded) "▾" else "▸") },
                        trailing = {
                            InfoGroupCheckbox(albumSelected) { selected ->
                                albumSelected = selected
                                onSelectGroup(artist.artistKey, album, selected)
                            }
                        },
                        divider = false,
                        onClick = { albumExpanded = !albumExpanded },
                    )
                    if (albumExpanded) for (row in albumRows) InfoChoiceRow(row, onSelect)
                }
            }
        }
    }
}

@Composable
private fun InfoGroupCheckbox(selected: Boolean, onSelected: (Boolean) -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
        Checkbox(selected, onSelected, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun InfoChoiceRow(row: InfoChoice, onSelect: (String, Boolean) -> Unit) {
    val enabled = row.saved || row.availability == "AVAILABLE"
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(row.selected, { onSelect(row.key, it) }, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(if (row.kind == InfoKind.LYRICS.name) row.title else infoKindLabel(InfoKind.valueOf(row.kind)), maxLines = 1, overflow = TextOverflow.Ellipsis, style = DapType.Body)
            Text(stringResource(when {
                row.saved -> R.string.info_saved
                row.availability == "MISSING" -> R.string.info_missing
                row.availability == "LOCAL" -> R.string.info_local
                row.availability == "FAILED" -> R.string.info_failed_check
                row.availability == "AVAILABLE" -> R.string.info_available
                else -> R.string.info_unchecked
            }), style = DapType.Caption)
        }
    }
}

@Composable
private fun infoKindLabel(kind: InfoKind): String = stringResource(when (kind) {
    InfoKind.PHOTO -> R.string.offline_download_photos
    InfoKind.BIO -> R.string.info_bios
    InfoKind.COVER -> R.string.offline_download_covers
    InfoKind.DESCRIPTION -> R.string.offline_download_descriptions
    InfoKind.LYRICS -> R.string.offline_download_lyrics
})
