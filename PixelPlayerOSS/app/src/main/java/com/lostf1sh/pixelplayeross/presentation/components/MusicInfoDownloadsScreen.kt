package com.lostf1sh.pixelplayeross.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.network.SourceWaitKind
import com.lostf1sh.pixelplayeross.data.offline.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.InfoAction
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OfflineDownloadsViewModel
import com.lostf1sh.pixelplayeross.ui.ds.*
import kotlinx.coroutines.flow.Flow

@Composable
fun MusicInfoDownloadsScreen(
    songIds: List<String>?, onDismiss: () -> Unit, artistFilter: String? = null,
    viewModel: OfflineDownloadsViewModel = hiltViewModel(), albumName: String? = null,
) {
    LaunchedEffect(songIds, artistFilter) { viewModel.open(songIds, artistFilter) }
    LaunchedEffect(viewModel) { viewModel.close.collect { onDismiss() } }
    val artists = viewModel.artists.collectAsLazyPagingItems()
    val query by viewModel.search.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle(null)
    val find by viewModel.findProgress.collectAsStateWithLifecycle(InfoProgress(0, 0, 0))
    val download by viewModel.downloadProgress.collectAsStateWithLifecycle(InfoProgress(0, 0, 0))
    val completedBytes by viewModel.completedBytes.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle(InfoSelection(0, 0, null, 0))
    val opening by viewModel.opening.collectAsStateWithLifecycle()
    val findQueued by viewModel.findQueued.collectAsStateWithLifecycle()
    val checkedAt by viewModel.checkedAt.collectAsStateWithLifecycle()
    val pendingKeys by viewModel.pendingKeys.collectAsStateWithLifecycle()
    val types by viewModel.types.collectAsStateWithLifecycle(emptyList())
    val waits by viewModel.waiting.collectAsStateWithLifecycle()
    val errors by viewModel.errors.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val undos by viewModel.undoActions.collectAsStateWithLifecycle(emptyList())
    val scope = when {
        artistFilter != null -> stringResource(R.string.info_scope_artist, artistFilter)
        albumName != null -> stringResource(R.string.info_scope_album, albumName)
        songIds != null -> pluralStringResource(R.plurals.info_scope_selection, songIds.size, songIds.size)
        else -> infoNumberText(pluralStringResource(R.plurals.info_scope_library, (types.firstOrNull { it.kind == InfoKind.LYRICS }?.total ?: 0), (types.firstOrNull { it.kind == InfoKind.LYRICS }?.total ?: 0)))
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ModalInputBridge()
        com.lostf1sh.pixelplayeross.ui.glass.GlassWindowBackdrop(com.lostf1sh.pixelplayeross.ui.glass.LocalCoverGlass.current)
        Surface(Modifier.fillMaxSize(), color = Dap.page) {
            MusicInfoDownloadsContent(artists, query, { viewModel.search.value = it }, queue, find, download,
                selection, opening, false, viewModel::choices, viewModel::find, viewModel::download,
                viewModel::delete, viewModel::pause, viewModel::resume, viewModel::select, viewModel::selectKind,
                onDismiss, viewModel::selectGroup, types, scope, waits, errors.keys, busy,
                undos.lastOrNull { !it.failed && it.untilAt > waits.nowMillis }, viewModel::undo,
                viewModel::cancel, viewModel::done, viewModel::retry,
                emptyLibrary = !opening && types.sumOf { it.total } == 0 && errors.isEmpty(), checkedAt = checkedAt, findQueued = findQueued, albumScopeName = albumName, artistScoped = artistFilter != null, completedBytes = completedBytes, onClearSelection = viewModel::clearSelection, pendingKeys = pendingKeys, groupCounts = viewModel::groupCounts)
        }
    }
}

/** Only navigation/expansion live locally. Every choice and aggregate checkbox comes from Room. */
@Composable
internal fun MusicInfoDownloadsContent(
    artists: LazyPagingItems<InfoArtist>, search: String, onSearch: (String) -> Unit,
    queue: InfoQueue?, find: InfoProgress, download: InfoProgress, selection: InfoSelection,
    opening: Boolean, error: Boolean, choices: (String) -> Flow<List<InfoChoice>>,
    onFind: () -> Unit, onDownload: () -> Unit, onDelete: () -> Unit, onPause: () -> Unit,
    onResume: () -> Unit, onSelect: (String, Boolean) -> Unit, onSelectKind: (InfoKind, Boolean) -> Unit,
    onClose: () -> Unit, onSelectGroup: (String, String?, Boolean) -> Unit = { _, _, _ -> },
    types: List<InfoTypeCounts> = emptyList(), scope: String = "", waits: InfoWaitState = InfoWaitState(),
    errors: Set<InfoAction> = emptySet(), busy: Set<InfoAction> = emptySet(), undo: InfoUndo? = null,
    onUndo: (String) -> Unit = {}, onCancel: () -> Unit = {}, onDone: () -> Unit = {},
    onRetry: (InfoAction) -> Unit = {}, emptyLibrary: Boolean = false, checkedAt: Long = 0,
    initiallyAdvanced: Boolean = false, findQueued: Boolean = false, albumScopeName: String? = null, artistScoped: Boolean = false, completedBytes: Long? = null, onClearSelection: () -> Unit = {}, pendingKeys: Set<String> = emptySet(), groupCounts: ((String, String?) -> Flow<InfoGroupCounts>)? = null,
) {
    CompositionLocalProvider(LocalInfoPending provides pendingKeys, LocalInfoGroups provides groupCounts) {
    MusicInfoDownloadsBody(artists, search, onSearch, queue, find, download, selection, opening, error, choices,
        onFind, onDownload, onDelete, onPause, onResume, onSelect, onSelectKind, onClose, onSelectGroup,
        types, scope, waits, errors, busy, undo, onUndo, onCancel, onDone, onRetry, emptyLibrary,
        checkedAt, initiallyAdvanced, findQueued, albumScopeName, artistScoped, completedBytes, onClearSelection)
    }
}
private val LocalInfoGroups = staticCompositionLocalOf<((String, String?) -> Flow<InfoGroupCounts>)?> { null }
private val LocalInfoPending = staticCompositionLocalOf<Set<String>> { emptySet() }

@Composable
private fun MusicInfoDownloadsBody(
    artists: LazyPagingItems<InfoArtist>, search: String, onSearch: (String) -> Unit,
    queue: InfoQueue?, find: InfoProgress, download: InfoProgress, selection: InfoSelection,
    opening: Boolean, error: Boolean, choices: (String) -> Flow<List<InfoChoice>>,
    onFind: () -> Unit, onDownload: () -> Unit, onDelete: () -> Unit, onPause: () -> Unit,
    onResume: () -> Unit, onSelect: (String, Boolean) -> Unit, onSelectKind: (InfoKind, Boolean) -> Unit,
    onClose: () -> Unit, onSelectGroup: (String, String?, Boolean) -> Unit,
    types: List<InfoTypeCounts>, scope: String, waits: InfoWaitState,
    errors: Set<InfoAction>, busy: Set<InfoAction>, undo: InfoUndo?,
    onUndo: (String) -> Unit, onCancel: () -> Unit, onDone: () -> Unit,
    onRetry: (InfoAction) -> Unit, emptyLibrary: Boolean, checkedAt: Long,
    initiallyAdvanced: Boolean, findQueued: Boolean, albumScopeName: String?, artistScoped: Boolean, completedBytes: Long?, onClearSelection: () -> Unit,
) {
    var deletingCount by rememberSaveable { mutableIntStateOf(selection.deletes) }
    val deleteAction = { deletingCount = selection.deletes; onDelete() }
    var advanced by rememberSaveable { mutableStateOf(initiallyAdvanced) }
    var findExpanded by rememberSaveable { mutableStateOf(false) }
    val selected = selection.downloads + selection.deletes
    val queueVisible = download.total > 0
    val completed = queueVisible && download.done == download.total && download.retry == 0 && download.pending == 0
    val step = if (queueVisible) 3 else if (find.done > 0 && find.pending == 0) 2 else 1
    BackHandler(advanced) { advanced = false; onSearch("") }
    val topReservation = (28.dp - WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()).coerceAtLeast(0.dp)
    Box(Modifier.fillMaxSize().background(Dap.page).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, end = 8.dp, top = topReservation, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (advanced) { advanced = false; onSearch("") } else onClose() }) {
                    InfoGlyph(if (advanced) "back" else "close", description = stringResource(if (advanced) R.string.info_back_to_summary else R.string.offline_download_close), modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(stringResource(if (advanced) R.string.info_by_group_title else R.string.info_internet_title), style = DapType.Heading, color = Dap.primaryText)
                    if (scope.isNotEmpty()) Text(scope, style = DapType.Caption.copy(lineHeight = 18.sp), color = Dap.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!emptyLibrary) InfoSteps(step, completed)
            if (emptyLibrary) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(start = 40.dp, end = 40.dp, bottom = 120.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    InfoGlyph("music", modifier = Modifier.size(48.dp), tint = Dap.secondaryText)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.info_empty_title), style = DapType.Row, color = Dap.primaryText)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.info_empty_body), style = DapType.Body, color = Dap.secondaryText, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("info-artists"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(if (advanced) 0.dp else 14.dp)) {
                    for (action in errors + if (error) setOf(InfoAction.DOWNLOAD) else emptySet()) {
                        item("error-$action") { InfoError(action, queue?.paused == true) { onRetry(action) } }
                    }
                    if (advanced) {
                        if (albumScopeName == null && !artistScoped) item("search") {
                            OutlinedTextField(search, onSearch, Modifier.fillMaxWidth().padding(bottom = 14.dp).heightIn(min = 48.dp), singleLine = true,
                                placeholder = { Text(stringResource(R.string.info_search), style = DapType.RowSmall) },
                                leadingIcon = { InfoGlyph("search", modifier = Modifier.size(18.dp), tint = Dap.secondaryText) },
                                trailingIcon = if (search.isNotEmpty()) ({ IconButton({ onSearch("") }) { InfoGlyph("close", description = stringResource(R.string.info_clear_search), modifier = Modifier.size(18.dp)) } }) else null,
                                shape = RoundedCornerShape(12.dp), textStyle = DapType.RowSmall,
                                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Dap.border, focusedBorderColor = Dap.secondaryText))
                        }
                        if (artists.itemCount == 0 && search.isNotEmpty()) {
                            item("no-match") {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 58.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    InfoGlyph("search", modifier = Modifier.size(40.dp), tint = Dap.secondaryText)
                                    Text(stringResource(R.string.info_no_match, search), style = DapType.Row, color = Dap.primaryText)
                                    Text(stringResource(R.string.info_no_match_hint), style = DapType.Body, color = Dap.secondaryText, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    InfoTextAction(stringResource(R.string.info_clear_search), { onSearch("") })
                                }
                            }
                        } else {
                            if (albumScopeName == null && !artistScoped) item("artist-count") { InfoSection(pluralStringResource(R.plurals.info_artists_count, artists.itemCount, artists.itemCount), infoChosen(selected)) }
                            items(artists.itemCount, key = artists.itemKey { it.artistKey }) { index ->
                                artists[index]?.let { InfoArtistRow(it, choices, onSelect, onSelectGroup, waits.nowMillis, albumScopeName,
                                    RoundedCornerShape(topStart = if(index == 0) 16.dp else 0.dp, topEnd = if(index == 0) 16.dp else 0.dp,
                                        bottomStart = if(index == artists.itemCount - 1) 16.dp else 0.dp, bottomEnd = if(index == artists.itemCount - 1) 16.dp else 0.dp)) }
                            }
                        }
                    } else {
                        item("find") {
                            InfoFindPanel(find, queue, opening, waits, types, checkedAt, findExpanded,
                                { findExpanded = !findExpanded }, onFind, InfoAction.FIND in busy || findQueued)
                        }
                        item("kinds") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                InfoSection(stringResource(R.string.info_select_all), pluralStringResource(R.plurals.info_kinds_selected, 5, types.count { it.group.chosen > 0 }, 5))
                                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Dap.panel)) {
                                    val ordered = listOf(InfoKind.LYRICS, InfoKind.PHOTO, InfoKind.BIO, InfoKind.COVER, InfoKind.DESCRIPTION)
                                    ordered.forEachIndexed { i, kind ->
                                        if (i > 0) HorizontalDivider(color = Dap.divider)
                                        InfoTypeRow(kind, types.firstOrNull { it.kind == kind }, find.pending > 0, onSelectKind, queueVisible)
                                    }
                                }
                            }
                        }
                        if (!queueVisible && !opening && find.pending == 0 && find.done > 0) {
                            item("size-note") { Text(stringResource(R.string.info_no_size_note), Modifier.padding(horizontal = 4.dp), style = DapType.Note.copy(lineHeight = 17.sp), color = Dap.secondaryText) }
                        }
                        if (!opening && find.pending == 0 && find.done > 0) {
                            item("advanced") { InfoFold(stringResource(R.string.info_choose_by_group), "list", false, { advanced = true }) }
                        }
                    }
                }
                if (selected > 0 || queueVisible || InfoAction.DELETE in busy) {
                    Column(Modifier.fillMaxWidth().background(Dap.panel).testTag("info-bottom-panel")) {
                        HorizontalDivider(color = Dap.divider)
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (queueVisible) InfoDownloadPanel(queue, download, waits, completedBytes ?: types.sumOf { it.savedBytes }, onPause, onResume, onCancel, onDone, busy)
                            if (selected > 0 || InfoAction.DELETE in busy) InfoSelectionPanel(
                                if (InfoAction.DELETE in busy) selection.copy(deletes = maxOf(selection.deletes, deletingCount)) else selection,
                                onDownload, deleteAction, onClearSelection, busy)
                        }
                    }
                }
            }
        }
        if (undo != null) {
            Surface(Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 20.dp), shape = RoundedCornerShape(10.dp), color = Dap.surfaceRaised, shadowElevation = 8.dp) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(when (undo.action) { "CANCEL" -> R.plurals.info_cancelled_undo; "CLEAR" -> R.plurals.info_cleared_undo; else -> R.plurals.info_deleted_undo }, undo.count, undo.count), Modifier.weight(1f), style = DapType.Body, color = Dap.primaryText)
                    InfoTextAction(stringResource(R.string.info_undo), { onUndo(undo.token) })
                }
            }
        }
    }
}

@Composable
private fun InfoSteps(current: Int, complete: Boolean) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp).testTag("info-steps"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(R.string.info_step_find, R.string.info_step_choose, R.string.info_step_download).forEachIndexed { i, label ->
            val done = i + 1 < current || complete
            val active = i + 1 == current && !complete
            val text = stringResource(label)
            val status = stringResource(if (done) R.string.info_step_done else if (active) R.string.info_step_current else R.string.info_step_pending)
            Row(Modifier.clearAndSetSemantics { contentDescription = "$text, $status" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val ink = if (done) LocalDapAccent.current else if (active) Dap.background else Dap.secondaryText
                Box(Modifier.size(22.dp).clip(CircleShape).background(if (active) Dap.primaryText else Color.Transparent).border(1.5.dp, if (done) LocalDapAccent.current else if (active) Dap.primaryText else Dap.secondaryText, CircleShape), contentAlignment = Alignment.Center) {
                    if (done) InfoGlyph("check", modifier = Modifier.size(13.dp), tint = ink)
                    else Text("${i + 1}", style = DapType.ValueSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = ink)
                }
                Text(text, style = DapType.Caption.copy(lineHeight = 16.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium), color = if (active) Dap.primaryText else Dap.secondaryText)
            }
            if (i < 2) HorizontalDivider(Modifier.weight(1f), color = Dap.border)
        }
    }
}

@Composable
private fun InfoFindPanel(
    p: InfoProgress, queue: InfoQueue?, opening: Boolean, waits: InfoWaitState, types: List<InfoTypeCounts>,
    checkedAt: Long, expanded: Boolean, onExpand: () -> Unit, onFind: () -> Unit, requested: Boolean,
) {
    val searching = p.pending > 0
    val checked = p.done > 0 || checkedAt > 0
    val failed = types.sumOf { it.failed }.coerceAtLeast(p.retry)
    val days = ((waits.nowMillis - checkedAt) / 86_400_000).coerceAtLeast(0).toInt()
    val checkedLabel = if (days == 0 || checkedAt == 0L) stringResource(R.string.info_checked_today) else pluralStringResource(R.plurals.info_checked_days_ago, days, days)
    if (checked && !searching && !opening && !expanded && failed == 0) {
        InfoFold("$checkedLabel · ${stringResource(R.string.info_up_to_date)}", "saved", false, onExpand)
        return
    }
    InfoCard {
        if (opening) {
            InfoStatusTitle(stringResource(R.string.info_preparing), "loading")
            InfoBody(stringResource(R.string.info_preparing_note))
            InfoButton(stringResource(if (requested) R.string.info_find_queued else R.string.info_find), onFind, primary = !requested, enabled = !requested)
        } else if (searching) {
            val waiting = waits.network != InfoNetworkState.READY
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoStatusTitle(stringResource(when (waits.network) { InfoNetworkState.WIFI -> R.string.info_waiting_wifi; InfoNetworkState.INTERNET -> R.string.info_offline; else -> R.string.info_searching }), when(waits.network) { InfoNetworkState.WIFI -> "wifi"; InfoNetworkState.INTERNET -> "offline"; else -> "loading" }, Modifier.weight(1f), warning = waiting)
                InfoValue("${p.done} / ${p.total}")
            }
            InfoProgressBar(p, waiting)
            if (!waiting) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if ((queue?.findMeasuredCount ?: 0) > 0) {
                        val mins = ((p.total - p.done) * queue!!.findMeasuredMs / queue.findMeasuredCount / 60_000).coerceAtLeast(1)
                        InfoValue(stringResource(R.string.offline_download_estimate, mins), secondary = true)
                    }
                    InfoValue(stringResource(R.string.info_wifi_only), secondary = true)
                }
            }
            InfoBody(stringResource(when(waits.network) { InfoNetworkState.WIFI -> R.string.info_find_waiting_wifi_note; InfoNetworkState.INTERNET -> R.string.info_find_offline_note; else -> R.string.info_choose_while_searching }), small = !waiting)
            InfoSourceWaits(waits, p.retry)
        } else if (checked) {
            InfoStatusTitle(checkedLabel, "saved")
            val resultLabels = listOf(R.string.info_result_available, R.string.info_result_local, R.string.info_result_missing, R.string.info_result_failed)
            val values = listOf(types.sumOf { it.available }, types.sumOf { it.local }, types.sumOf { it.missing }, failed)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(2) { r -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(2) { c -> val n = r * 2 + c
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(values[n].toString(), style = DapType.Value.copy(fontSize = 20.sp, lineHeight = 24.sp), color = Dap.primaryText)
                            InfoBody(stringResource(resultLabels[n]), small = true)
                        }
                    }
                } }
            }
            if (failed > 0) {
                InfoNotice(pluralStringResource(R.plurals.info_could_not_check, failed, failed), stringResource(R.string.info_could_not_check_hint), "warning") {
                    InfoTextAction(stringResource(R.string.info_retry_n, failed), onFind)
                }
            } else InfoTextAction(stringResource(R.string.info_find_again), onFind)
        } else {
            Text(stringResource(R.string.info_find_title), style = DapType.Row, color = Dap.primaryText)
            InfoBody(stringResource(R.string.info_find_note))
            InfoValue(infoScopeCounts(types.firstOrNull { it.kind == InfoKind.PHOTO }?.total ?: 0, types.firstOrNull { it.kind == InfoKind.DESCRIPTION }?.total ?: 0, types.firstOrNull { it.kind == InfoKind.LYRICS }?.total ?: 0), secondary = true)
            InfoButton(stringResource(R.string.info_find), onFind, icon = "search", modifier = Modifier.fillMaxWidth())
            InfoBody("${stringResource(R.string.info_wifi_only)} · ${stringResource(R.string.info_runs_in_background)}", small = true)
        }
    }
}

@Composable
private fun InfoTypeRow(kind: InfoKind, counts: InfoTypeCounts?, searching: Boolean, onSelect: (InfoKind, Boolean) -> Unit, downloading: Boolean) {
    val c = counts
    val status = when {
        c == null || c.total == c.unchecked -> stringResource(R.string.info_unchecked) + (c?.let { " · ${it.total}" } ?: "")
        else -> buildList {
            add(stringResource(R.string.info_kind_to_save, c.available))
            if (c.saved > 0 || (c.local + c.localPlain == 0 && c.queued + c.retrying == 0)) add(pluralStringResource(R.plurals.info_kind_saved, c.saved, c.saved))
            if (c.local > 0) add(pluralStringResource(R.plurals.info_on_phone, c.local, c.local))
            if (c.localPlain > 0) add(pluralStringResource(R.plurals.info_plain_on_phone, c.localPlain, c.localPlain))
            if (c.queued > 0) add(pluralStringResource(R.plurals.info_kind_queued, c.queued, c.queued))
            if (c.retrying > 0) add(pluralStringResource(R.plurals.info_retrying_count, c.retrying, c.retrying))
            if (c.saved > 0 && c.saved + c.local + c.localPlain == c.total) add(stringResource(R.string.info_all_saved))
            if (c.available == 0 && c.saved == 0 && c.queued + c.retrying == 0 && c.local + c.localPlain == 0) {
                add(stringResource(if (c.failed > 0) R.string.info_failed_check else R.string.info_missing))
            }
        }.joinToString(" · ")
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoGlyph(kind.name, modifier = Modifier.size(20.dp), tint = Dap.secondaryText)
        Column(Modifier.weight(1f).padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(infoKindLabel(kind), style = DapType.RowSmall, color = Dap.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val statusIcon = if (downloading) null else when {
                c == null || c.total == c.unchecked -> "unchecked"
                c.available > 0 && searching -> "available"
                c.available > 0 -> null
                c.saved > 0 -> "saved"
                c.local + c.localPlain > 0 -> "folder"
                c.failed > 0 -> "warning"
                else -> "missing"
            }
            val tint = if ((c?.retrying ?: 0) > 0 || statusIcon == "warning") Dap.warning else if ((c?.saved ?: 0) > 0 && (c?.available ?: 0) == 0 && (c?.queued ?: 0) == 0) LocalDapAccent.current else Dap.secondaryText
            if (statusIcon != null && statusIcon != "saved") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoGlyph(statusIcon, modifier = Modifier.size(14.dp), tint = tint)
                InfoTypeStatus(status, c, tint, Modifier.weight(1f))
            } else InfoTypeStatus(status, c, tint)
            if ((c?.chosenSaved ?: 0) > 0) Text(infoNumberText(pluralStringResource(R.plurals.info_saved_chosen, c!!.chosenSaved, c.chosenSaved)),
                style = DapType.ValueSmall, color = LocalDapAccent.current)
            if (c != null && c.group.chosen == 0 && c.queued + c.retrying > 0) {
                InfoTextAction(stringResource(R.string.info_remove_from_queue), { onSelect(kind, false) })
            }
        }
        val label = infoKindLabel(kind)
        if ("kind:${kind.name}" in LocalInfoPending.current) CircularProgressIndicator(Modifier.size(14.dp), color = LocalDapAccent.current, strokeWidth = 2.dp)
        if (c != null && c.group.chosen == 0 && c.available == 0 && c.saved + c.queued + c.retrying > 0) {
            val pending = c.queued + c.retrying > 0
            val stateLabel = if (pending) status else if (c.saved + c.local + c.localPlain == c.total) stringResource(R.string.info_all_saved) else pluralStringResource(R.plurals.info_kind_saved, c.saved, c.saved)
            Box(Modifier.size(48.dp).semantics { contentDescription = "$label, $stateLabel" }, contentAlignment = Alignment.Center) {
                InfoGlyph(if (pending) "clock" else "saved", modifier = Modifier.size(20.dp), tint = LocalDapAccent.current)
            }
        } else InfoCheckbox(c?.group ?: InfoGroupCounts(), stringResource(R.string.info_group_select_all, label),
            { onSelect(kind, (c?.chosenNew ?: 0) < (c?.newItems ?: 0)) }, enabledOverride = c != null && c.newItems > 0)
    }
}

@Composable
private fun InfoTypeStatus(status: String, counts: InfoTypeCounts?, tint: Color, modifier: Modifier = Modifier) {
    val text = infoNumberText(status)
    val saved = counts?.saved?.takeIf { it > 0 }?.let { infoNumberText(pluralStringResource(R.plurals.info_kind_saved, it, it)) }
    val retry = counts?.retrying?.takeIf { it > 0 }?.let { infoNumberText(pluralStringResource(R.plurals.info_retrying_count, it, it)) }
    val accent = LocalDapAccent.current
    val warning = Dap.warning
    val styled = androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        for ((token, color) in listOf(saved to accent, retry to warning)) {
            val start = token?.let(text::indexOf) ?: -1
            if (start >= 0) addStyle(androidx.compose.ui.text.SpanStyle(color = color), start, start + token!!.length)
        }
    }
    Text(styled, modifier, style = DapType.ValueSmall.copy(fontSize = 11.5.sp, lineHeight = 15.sp), color = tint,
        maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun InfoSelectionPanel(s: InfoSelection, onDownload: () -> Unit, onDelete: () -> Unit, onClear: () -> Unit, busy: Set<InfoAction>) {
    val ready = s.downloads - s.pending
    val deleting = InfoAction.DELETE in busy
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (deleting) CircularProgressIndicator(Modifier.size(16.dp), color = LocalDapAccent.current, strokeWidth = 2.dp)
                    Text(if (deleting) stringResource(R.string.info_deleting, s.deletes) else infoChosen(s.downloads + s.deletes),
                        style = DapType.RowSmall.copy(fontWeight = FontWeight.SemiBold), color = Dap.primaryText)
                }
                val detail = when {
                    (s.knownBytes ?: 0) > 0 -> "${stringResource(R.string.info_at_least, infoSize(s.knownBytes!!))} · ${stringResource(R.string.info_wifi_only)}"
                    s.downloads > 0 && s.deletes > 0 -> infoNewAndSaved(s.downloads, s.deletes)
                    else -> stringResource(R.string.info_size_not_given)
                }
                Text(infoNumberText(detail), style = DapType.ValueSmall, color = Dap.secondaryText)
            }
            if (ready > 0) InfoButton(stringResource(R.string.info_download_n, ready), onDownload, icon = "download", enabled = InfoAction.DOWNLOAD !in busy)
            else if (s.deletes > 0) InfoButton(stringResource(R.string.info_delete_selected, s.deletes), onDelete, primary = false, icon = "delete", enabled = !deleting)
        }
        Row {
            InfoTextAction(stringResource(R.string.info_clear_selection), onClear, enabled = InfoAction.CLEAR !in busy)
            if (ready > 0 && s.deletes > 0) InfoTextAction(stringResource(R.string.info_delete_selected, s.deletes), onDelete, enabled = !deleting, tint = Dap.error)
        }
    }
}

@Composable
private fun InfoDownloadPanel(q: InfoQueue?, p: InfoProgress, waits: InfoWaitState, savedBytes: Long, pause: () -> Unit, resume: () -> Unit, cancel: () -> Unit, done: () -> Unit, busy: Set<InfoAction>) {
    val complete = p.done == p.total && p.retry == 0 && p.pending == 0
    val paused = q?.paused == true && !complete
    val network = if (complete || paused) InfoNetworkState.READY else waits.network
    val hold = paused || network != InfoNetworkState.READY
    val title = when { complete -> R.string.info_done; paused -> R.string.info_paused; network == InfoNetworkState.WIFI -> R.string.info_waiting_wifi; network == InfoNetworkState.INTERNET -> R.string.info_offline; else -> R.string.info_downloading }
    Row(verticalAlignment = Alignment.CenterVertically) {
        InfoStatusTitle(stringResource(title), when { complete -> "saved"; paused -> "pause"; network == InfoNetworkState.WIFI -> "wifi"; network == InfoNetworkState.INTERNET -> "offline"; else -> "download" }, Modifier.weight(1f), warning = network != InfoNetworkState.READY)
        InfoValue("${p.done} / ${p.total}")
    }
    InfoProgressBar(p, hold)
    if (!complete && !paused && network == InfoNetworkState.READY) {
        if (waits.sources.isEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(q?.currentArtist.orEmpty(), Modifier.weight(1f), style = DapType.ValueSmall, color = Dap.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            InfoValue(stringResource(R.string.info_wifi_only), secondary = true)
        } else InfoSourceWaits(waits, p.retry)
    }
    val note = when { complete -> pluralStringResource(R.plurals.info_done_note, p.done, p.done, infoSize(savedBytes)); paused -> stringResource(R.string.info_paused_note); network == InfoNetworkState.WIFI -> stringResource(R.string.info_waiting_wifi_note); network == InfoNetworkState.INTERNET -> stringResource(R.string.info_offline_note); waits.sources.isNotEmpty() -> ""; else -> stringResource(R.string.info_queue_global) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (note.isNotEmpty()) Text(infoNumberText(note), Modifier.weight(1f), style = DapType.Note.copy(lineHeight = 17.sp), color = Dap.secondaryText)
        if (complete) InfoButton(stringResource(R.string.info_ok), done, enabled = InfoAction.DONE !in busy)
    }
    if (!complete) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            InfoTextAction(stringResource(R.string.info_cancel), cancel, enabled = InfoAction.CANCEL !in busy)
            InfoButton(stringResource(if (paused) R.string.offline_download_resume else R.string.offline_download_pause), if (paused) resume else pause, primary = paused, icon = if (paused) "play" else "pause", enabled = InfoAction.PAUSE !in busy)
        }
        InfoBody(stringResource(R.string.info_cancel_note), small = true)
    }
}

@Composable
private fun InfoSourceWaits(waits: InfoWaitState, retries: Int) {
    waits.sources.forEach { wait ->
        val seconds = ((wait.retryAt - waits.nowMillis + 999) / 1000).coerceAtLeast(0).toInt()
        if (wait.kind == SourceWaitKind.RATE_LIMIT) {
            InfoStatusLine(stringResource(R.string.info_source_wait, wait.source, infoKindLabel(if (wait.source == "LRCLIB") InfoKind.LYRICS else if (wait.source == "Wikimedia Commons") InfoKind.PHOTO else InfoKind.BIO), seconds), "clock", Dap.warning)
            InfoBody(stringResource(R.string.info_source_wait_note), small = true)
        } else {
            InfoStatusLine(pluralStringResource(R.plurals.offline_download_retry, retries, retries, infoTime(seconds.toLong() * 1000)), "retry", Dap.warning)
            InfoBody(stringResource(R.string.info_source_down, wait.source), small = true)
        }
    }
}

@Composable
private fun InfoArtistRow(a: InfoArtist, choices: (String) -> Flow<List<InfoChoice>>, onSelect: (String, Boolean) -> Unit, onGroup: (String, String?, Boolean) -> Unit, now: Long, albumScopeName: String? = null, shape: RoundedCornerShape = RoundedCornerShape(16.dp)) {
    var expanded by rememberSaveable(a.artistKey) { mutableStateOf(false) }
    val flow = remember(a.artistKey, choices) { choices(a.artistKey) }
    val rows by flow.collectAsStateWithLifecycle(emptyList())
    if (albumScopeName != null) {
        val albumRows = rows.filter { it.albumKey.isNotEmpty() }
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Dap.panel)) {
            InfoGroupRow(albumScopeName, albumRows, expanded, { expanded = !expanded }, { selected ->
                albumRows.map { it.albumKey }.distinct().forEach { onGroup(a.artistKey, it, selected) }
            })
            if (expanded) albumRows.forEach { InfoChoiceRow(it, onSelect, 44, now) }
        }
        val artistRows = rows.filter { it.albumKey.isEmpty() }
        if (artistRows.isNotEmpty()) {
            var artistExpanded by rememberSaveable(a.artistKey, "artist-info") { mutableStateOf(false) }
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Dap.panel)) {
                InfoGroupRow(a.artistName, artistRows, artistExpanded, { artistExpanded = !artistExpanded }, { selected ->
                    artistRows.forEach { onSelect(it.key, selected) }
                })
                if (artistExpanded) artistRows.forEach { InfoChoiceRow(it, onSelect, 44, now) }
            }
        }
        return
    }
    Column(Modifier.clip(shape).background(Dap.panel)) {
        InfoGroupRow(a.artistName, rows, expanded, { expanded = !expanded }, { onGroup(a.artistKey, null, it) })
        if (expanded) {
            rows.filter { it.albumKey.isEmpty() }.forEach { InfoChoiceRow(it, onSelect, 44, now) }
            rows.filter { it.albumKey.isNotEmpty() }.groupBy { it.albumKey }.forEach { (album, albumRows) ->
                var albumExpanded by rememberSaveable(a.artistKey, album) { mutableStateOf(false) }
                InfoGroupRow(albumRows.first().albumName, albumRows, albumExpanded, { albumExpanded = !albumExpanded }, { onGroup(a.artistKey, album, it) }, 44)
                if (albumExpanded) albumRows.forEach { InfoChoiceRow(it, onSelect, 72, now) }
            }
        }
    }
}

@Composable
private fun InfoGroupRow(name: String, rows: List<InfoChoice>, expanded: Boolean, toggle: () -> Unit, choose: (Boolean) -> Unit, inset: Int = 16) {
    val provider = LocalInfoGroups.current
    val artist = rows.firstOrNull()?.artistKey.orEmpty()
    val album = rows.map { it.albumKey }.distinct().singleOrNull()
    val counts = if (provider == null) infoGroupCounts(rows) else {
        val flow = remember(provider, artist, album) { provider(artist, album) }
        flow.collectAsStateWithLifecycle(InfoGroupCounts()).value
    }
    val state = stringResource(if (expanded) R.string.info_expanded else R.string.info_collapsed)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = inset.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 56.dp).clickable(role = Role.Button, onClick = toggle).semantics { stateDescription = state }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoGlyph(if(expanded) "down" else "right", modifier = Modifier.size(18.dp), tint = Dap.secondaryText)
            Column(Modifier.weight(1f).padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = DapType.RowSmall.copy(fontWeight = FontWeight.SemiBold), color = Dap.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val shared = rows.count { it.sharedWithOthers > 0 }
                if (shared > 0) InfoValue(pluralStringResource(R.plurals.info_shared_items, shared, shared), secondary = true, small = true)
                InfoValue(if(counts.eligible == 0) stringResource(R.string.info_nothing_available) else pluralStringResource(R.plurals.info_group_chosen, counts.chosen, counts.chosen, counts.eligible), secondary = true, small = true)
                if (counts.chosen == 0 && rows.any { it.downloadState in listOf("QUEUED", "RETRY") }) {
                    InfoTextAction(stringResource(R.string.info_remove_from_queue), { choose(false) })
                }
            }
        }
        InfoCheckbox(counts, stringResource(R.string.info_group_select_all, name), choose)
    }
}

@Composable
private fun InfoChoiceRow(row: InfoChoice, select: (String, Boolean) -> Unit, inset: Int, now: Long) {
    val enabled = row.selected || row.saved || row.availability == "AVAILABLE" || row.downloadState in listOf("QUEUED", "DOWNLOADING", "RETRY")
    val statusId = when { row.saved -> R.string.info_saved; row.downloadState == "DOWNLOADING" -> R.string.info_downloading_item; row.downloadState == "QUEUED" -> R.string.info_queued; row.downloadState == "RETRY" -> R.string.info_will_retry; row.deletedByUser -> R.string.info_deleted_by_you; row.availability == "AVAILABLE" -> R.string.info_available; row.availability == "LOCAL" -> R.string.info_local; row.availability == "LOCAL_PLAIN" -> R.string.info_local_plain; row.availability == "FAILED" -> R.string.info_failed_check; row.availability == "MISSING" -> R.string.info_missing; else -> R.string.info_unchecked }
    val status = if(statusId == R.string.info_will_retry) stringResource(statusId, infoTime(row.retryAt - now)) else stringResource(statusId)
    val glyph = when(statusId) { R.string.info_saved -> "saved"; R.string.info_downloading_item -> "loading"; R.string.info_queued -> "clock"; R.string.info_will_retry -> "retry"; R.string.info_deleted_by_you -> "delete"; R.string.info_local -> "folder"; R.string.info_local_plain -> "folder"; R.string.info_failed_check -> "warning"; R.string.info_missing -> "missing"; R.string.info_unchecked -> "unchecked"; else -> "available" }
    val title = stringResource(when(InfoKind.valueOf(row.kind)) { InfoKind.PHOTO -> R.string.info_item_photo; InfoKind.BIO -> R.string.info_item_bio; InfoKind.COVER -> R.string.info_item_cover; InfoKind.DESCRIPTION -> R.string.info_item_album_desc; InfoKind.LYRICS -> R.string.offline_download_lyrics })
    HorizontalDivider(color = Dap.divider)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = inset.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (row.kind == InfoKind.LYRICS.name) row.title else title, style = DapType.RowSmall, color = if(enabled || row.downloadState != "IDLE") Dap.primaryText else Dap.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val label = if(row.kind == InfoKind.LYRICS.name) stringResource(R.string.info_item_lyrics, status) else if (statusId == R.string.info_available && row.sourceBytes != null) "$status · ${infoSize(row.sourceBytes)}" else status
            InfoStatusLine(label, glyph, if(row.saved) LocalDapAccent.current else if (statusId in listOf(R.string.info_failed_check, R.string.info_will_retry)) Dap.warning else Dap.secondaryText)
            if (row.downloadState == "DOWNLOADING") InfoBody(stringResource(R.string.info_downloading_finishes), small = true)
            if (!row.selected && row.downloadState in listOf("QUEUED", "RETRY")) {
                InfoTextAction(stringResource(R.string.info_remove_from_queue), { select(row.key, false) })
            }
        }
        if (row.key in LocalInfoPending.current) CircularProgressIndicator(Modifier.size(14.dp), color = LocalDapAccent.current, strokeWidth = 2.dp)
        InfoCheckbox(InfoGroupCounts(if(enabled) 1 else 0, if(row.selected) 1 else 0),
            "${if(row.kind == InfoKind.LYRICS.name) row.title else title}, $status", { select(row.key, it) })
    }
}

@Composable
private fun InfoCheckbox(counts: InfoGroupCounts, label: String, choose: (Boolean) -> Unit, allowEmpty: Boolean = false, enabledOverride: Boolean? = null) {
    val state = when(counts.checkState) { InfoCheckState.CHECKED -> ToggleableState.On; InfoCheckState.PARTIAL -> ToggleableState.Indeterminate; InfoCheckState.EMPTY -> ToggleableState.Off }
    val enabled = enabledOverride ?: (counts.eligible > 0 || allowEmpty)
    val accent = LocalDapAccent.current
    val ink = Dap.onFill
    val outline = if(enabled) Dap.secondaryText else Dap.disabledText
    Box(Modifier.size(48.dp).triStateToggleable(state, enabled = enabled, role = Role.Checkbox,
        onClick = { choose(counts.checkState != InfoCheckState.CHECKED) }).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = 2.dp.toPx()
            drawRoundRect(if(state == ToggleableState.Off) outline else accent,
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                style = if(state == ToggleableState.Off) androidx.compose.ui.graphics.drawscope.Stroke(stroke,
                    pathEffect = if(enabled) null else androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),2.dp.toPx()))) else androidx.compose.ui.graphics.drawscope.Fill)
            if(state != ToggleableState.Off) {
                val p = androidx.compose.ui.graphics.Path()
                if(state == ToggleableState.Indeterminate) { p.moveTo(size.width * .25f,size.height * .5f); p.lineTo(size.width * .75f,size.height * .5f) }
                else { p.moveTo(size.width * .25f,size.height * .5f); p.lineTo(size.width * .44f,size.height * .7f); p.lineTo(size.width * .78f,size.height * .3f) }
                drawPath(p, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx(),cap = androidx.compose.ui.graphics.StrokeCap.Round,join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }
}

@Composable internal fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dap.panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable private fun InfoFold(text: String, icon: String, expanded: Boolean, click: () -> Unit) {
    val state = stringResource(if(expanded) R.string.info_expanded else R.string.info_collapsed)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dap.panel).clickable(role = Role.Button, onClick = click).semantics { stateDescription = state }.heightIn(min = 52.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoGlyph(icon, modifier = Modifier.size(18.dp), tint = if(icon == "saved") LocalDapAccent.current else Dap.secondaryText)
        Text(text, Modifier.weight(1f), style = DapType.MediaRow, color = Dap.primaryText)
        InfoGlyph(if(icon == "list") "right" else "down", modifier = Modifier.size(18.dp), tint = Dap.secondaryText)
    }
}
@Composable private fun InfoSection(title: String, value: String) {
    Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = DapType.SectionHeader, color = Dap.secondaryText)
        InfoValue(value, secondary = true)
    }
}
@Composable internal fun InfoBody(text: String, small: Boolean = false) { Text(text, style = if(small) DapType.Note.copy(lineHeight = 17.sp) else DapType.Body, color = Dap.secondaryText) }
@Composable private fun InfoValue(text: String, modifier: Modifier = Modifier, secondary: Boolean = false, small: Boolean = false, tint: Color? = null) {
    Text(infoNumberText(text), modifier, style = if(small) DapType.ValueSmall.copy(fontSize = 11.5.sp, lineHeight = 15.sp) else if(secondary) DapType.ValueSmall else DapType.Value, color = tint ?: if(secondary) Dap.secondaryText else Dap.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
@Composable private fun InfoStatusTitle(text: String, icon: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    Row(modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoGlyph(icon, modifier = Modifier.size(20.dp), tint = if(warning) Dap.warning else if(icon in listOf("saved", "download", "loading")) LocalDapAccent.current else Dap.secondaryText)
        Text(text, style = DapType.RowSmall.copy(fontWeight = FontWeight.SemiBold), color = Dap.primaryText)
    }
}
@Composable private fun InfoStatusLine(text: String, icon: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoGlyph(icon, modifier = Modifier.size(14.dp), tint = tint)
        InfoValue(text, small = true, tint = tint)
    }
}
@Composable private fun InfoProgressBar(p: InfoProgress, hold: Boolean) {
    LinearProgressIndicator(progress = { if(p.total == 0) 0f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = if(hold) Dap.secondaryText else LocalDapAccent.current, trackColor = Dap.trackStroke, gapSize = 0.dp, drawStopIndicator = {})
}
@Composable internal fun InfoButton(text: String, click: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, icon: String? = null, enabled: Boolean = true) {
    Button(click, modifier.heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = if(primary) LocalDapAccent.current else Dap.panelRaised, contentColor = if(primary) Dap.onFill else Dap.primaryText), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        if(icon != null) { InfoGlyph(icon, modifier = Modifier.size(18.dp), tint = LocalContentColor.current); Spacer(Modifier.width(8.dp)) }
        Text(text, style = DapType.Button)
    }
}
@Composable internal fun InfoTextAction(text: String, click: () -> Unit, enabled: Boolean = true, tint: Color = LocalDapAccent.current) {
    TextButton(click, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = tint)) { Text(text, style = DapType.Action) }
}
@Composable private fun InfoNotice(title: String, note: String, icon: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Dap.panelRaised).padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoGlyph(icon, modifier = Modifier.size(20.dp), tint = if(icon == "warning") Dap.warning else Dap.error)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(title, style = DapType.Body, color = Dap.primaryText); InfoBody(note, true); action() }
    }
}
@Composable private fun InfoError(action: InfoAction, paused: Boolean, retry: () -> Unit) {
    val id = when(action) { InfoAction.OPEN, InfoAction.FIND -> R.string.info_error_find; InfoAction.CHOOSE, InfoAction.CLEAR -> R.string.info_error_select; InfoAction.DOWNLOAD -> R.string.offline_download_failed; InfoAction.DELETE -> R.string.info_error_delete; InfoAction.PAUSE -> if(paused) R.string.info_error_resume else R.string.info_error_pause; InfoAction.CANCEL -> R.string.info_error_cancel; InfoAction.UNDO -> R.string.info_error_undo; InfoAction.DONE -> R.string.offline_download_failed }
    InfoNotice(stringResource(id), stringResource(R.string.info_choice_kept), "error") { InfoTextAction(stringResource(R.string.info_retry), retry) }
}
@Composable internal fun infoSize(bytes: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(bytes, locale) { formatInfoSize(bytes, locale) }
}

internal fun formatInfoSize(bytes: Long, locale: java.util.Locale): String {
    val units = listOf("B", "kB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while(value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    val format = java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    return "${format.format(value)} ${units[unit]}"
}
@Composable private fun infoTime(ms: Long): String { val seconds = ((ms + 999) / 1000).coerceAtLeast(0); return stringResource(if(seconds >= 60) R.string.info_minutes else R.string.info_seconds, if(seconds >= 60) (seconds + 59) / 60 else seconds) }
@Composable private fun infoKindLabel(kind: InfoKind): String = stringResource(when(kind) { InfoKind.PHOTO -> R.string.offline_download_photos; InfoKind.BIO -> R.string.info_bios; InfoKind.COVER -> R.string.offline_download_covers; InfoKind.DESCRIPTION -> R.string.offline_download_descriptions; InfoKind.LYRICS -> R.string.offline_download_lyrics })

@Composable private fun infoChosen(count: Int): String = infoNumberText(pluralStringResource(R.plurals.info_selected, count, count))
@Composable private fun infoNumberText(text: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(text, locale) { Regex("(?<![\\w.,])\\d{4,}(?![\\w.,])").replace(text) { java.text.NumberFormat.getIntegerInstance(locale).format(it.value.toLong()) } }
}

@Composable private fun infoAvailablePending(available: Int, pending: Int): String = stringResource(R.string.info_available_pending,
    pluralStringResource(R.plurals.info_available_count, available, available),
    pluralStringResource(R.plurals.info_remaining_count, pending, pending))

@Composable private fun infoNewAndSaved(new: Int, saved: Int): String = stringResource(R.string.info_new_and_saved,
    pluralStringResource(R.plurals.info_new_count, new, new),
    pluralStringResource(R.plurals.info_saved_count, saved, saved))

@Composable private fun infoScopeCounts(artists: Int, albums: Int, songs: Int): String = stringResource(R.string.info_scope_counts,
    pluralStringResource(R.plurals.info_artists_count, artists, artists),
    pluralStringResource(R.plurals.info_albums_count, albums, albums),
    pluralStringResource(R.plurals.info_songs_count, songs, songs))
