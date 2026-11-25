package com.lostf1sh.pixelplayeross.presentation.screens

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.data.historyimport.ImportPlaylistPreview
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.UserPlaylistRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.MainActivity
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.lostf1sh.pixelplayeross.data.historyimport.CsvColumnMapping
import com.lostf1sh.pixelplayeross.data.historyimport.CsvDurationUnit
import com.lostf1sh.pixelplayeross.data.historyimport.ImportFileDataKind
import com.lostf1sh.pixelplayeross.data.historyimport.ImportFileSkipReason
import com.lostf1sh.pixelplayeross.data.historyimport.ImportFileSummary
import com.lostf1sh.pixelplayeross.data.historyimport.ImportPreview
import com.lostf1sh.pixelplayeross.data.historyimport.ImportProgress
import com.lostf1sh.pixelplayeross.data.historyimport.ImportRecord
import com.lostf1sh.pixelplayeross.data.historyimport.ImportSourceKind
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.presentation.components.symmetricContentPadding
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportError
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportStep
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ProductModeViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapNote
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapRowState
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSegmentedPicker
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapState
import com.lostf1sh.pixelplayeross.ui.ds.DapStateKind
import com.lostf1sh.pixelplayeross.ui.ds.DapSwitch
import com.lostf1sh.pixelplayeross.ui.ds.DapSwitchRow
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.DapValue
import com.lostf1sh.pixelplayeross.ui.ds.DapValueRow
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.utils.currentLocale
import com.lostf1sh.pixelplayeross.utils.formatListeningDurationCompact
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private val IMPORT_MIME_TYPES = arrayOf(
    "application/json",
    "application/zip",
    "application/x-zip-compressed",
    "text/csv",
    "text/comma-separated-values",
    "text/plain",
    "audio/x-mpegurl",
    "application/vnd.apple.mpegurl",
    "audio/mpegurl",
    "*/*",
)

@Composable
fun HistoryImportScreen(
    navController: NavController,
    viewModel: HistoryImportViewModel = hiltViewModel(),
    productModeViewModel: ProductModeViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val mode by productModeViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        context.findMainActivity()?.finishTemporarySystemConfirmation()
        if (uris.isNotEmpty()) {
            viewModel.onFilesPicked(uris)
        }
    }

    val isSubStep = ui.step is HistoryImportStep.Reading ||
        ui.step is HistoryImportStep.CsvMapping ||
        ui.step is HistoryImportStep.Preview
    val openPlaylistId = (ui.step as? HistoryImportStep.Preview)?.openPlaylistId
    BackHandler(enabled = isSubStep) {
        if (openPlaylistId != null) viewModel.openPlaylist(null) else viewModel.cancelActiveFlow()
    }

    HistoryImportContent(
        ui = ui,
        effectiveMode = mode.product.effectiveMode,
        onPickFiles = {
            if (mode.product.effectiveMode == EffectiveProductMode.PINNED) return@HistoryImportContent
            val activity = context.findMainActivity()
            if (activity != null) {
                activity.beginTemporarySystemConfirmation { flowReady ->
                    if (flowReady) {
                        runCatching { pickerLauncher.launch(IMPORT_MIME_TYPES) }
                            .onFailure { activity.finishTemporarySystemConfirmation() }
                    }
                }
            } else {
                pickerLauncher.launch(IMPORT_MIME_TYPES)
            }
        },
        onCancel = viewModel::cancelActiveFlow,
        onUpdateCsvMapping = viewModel::updateCsvMapping,
        onUpdateCsvDelimiter = viewModel::updateCsvDelimiter,
        onConfirmCsv = viewModel::confirmCsvMapping,
        onConfirmImport = viewModel::confirmImport,
        onRemoveImport = viewModel::removeImport,
        onTogglePlaylist = viewModel::setPlaylistIncluded,
        onOpenPlaylist = viewModel::openPlaylist,
        observeSongs = viewModel::observeSongs,
    )
}

@Composable
internal fun HistoryImportContent(
    ui: HistoryImportUiState,
    effectiveMode: EffectiveProductMode = EffectiveProductMode.APP,
    onPickFiles: () -> Unit,
    onCancel: () -> Unit,
    onUpdateCsvMapping: ((CsvColumnMapping) -> CsvColumnMapping) -> Unit,
    onUpdateCsvDelimiter: (Char) -> Unit,
    onConfirmCsv: () -> Unit,
    onConfirmImport: () -> Unit,
    onRemoveImport: (String) -> Unit,
    onTogglePlaylist: (String, Boolean) -> Unit = { _, _ -> },
    onOpenPlaylist: (String?) -> Unit = {},
    observeSongs: (List<String>) -> Flow<List<Song>> = { flowOf(emptyList()) },
    initiallyLikesExpanded: Boolean = false,
) {
    val locale = currentLocale()
    val zone = ZoneId.systemDefault()
    val numberFmt = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val previewStep = ui.step as? HistoryImportStep.Preview
    val openPlaylist = previewStep?.openPlaylistId?.let { id -> previewStep.preview.playlists.firstOrNull { it.targetId == id } }

    val reviewListState = rememberLazyListState()
    if (openPlaylist != null) {
        PlaylistPage(
            title = openPlaylist.name,
            caption = stringResource(
                R.string.import_playlist_match_count,
                numberFmt.format(openPlaylist.matchedCount),
                numberFmt.format(openPlaylist.totalItems),
            ),
            onBack = { onOpenPlaylist(null) },
            belowHeader = {
                DapSwitchRow(
                    label = stringResource(R.string.import_playlist_include),
                    checked = previewStep.playlistIncluded[openPlaylist.targetId] ?: openPlaylist.included,
                    onCheckedChange = { checked -> onTogglePlaylist(openPlaylist.targetId, checked) },
                    modifier = Modifier.padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Sm),
                )
            },
        ) {
            ImportPlaylistSongs(playlist = openPlaylist, numberFmt = numberFmt, observeSongs = observeSongs)
        }
        return
    }

    DapBackScaffold(Modifier.fillMaxSize()) {
        LazyColumn(
            state = reviewListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DapSpacing.Gutter,
                end = DapSpacing.Gutter,
                top = DapSpacing.Sm,
                bottom = LocalDapPlayerShelfHeight.current + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(DapSpacing.SectionGap),
        ) {
            item(key = "title") {
                DapScreenTitle(text = stringResource(R.string.import_history_title))
            }

            when (val step = ui.step) {
                is HistoryImportStep.Idle,
                is HistoryImportStep.Done,
                is HistoryImportStep.Error -> {
                    item(key = "pick") {
                        ImportPickSection(
                            step = step,
                            effectiveMode = effectiveMode,
                            zone = zone,
                            numberFmt = numberFmt,
                            onPickFiles = onPickFiles,
                        )
                    }
                    item(key = "past-imports") {
                        ImportedHistoryListSection(
                            records = ui.pastImports,
                            onRemove = onRemoveImport,
                            locale = locale,
                            zone = zone,
                        )
                    }
                }
                is HistoryImportStep.Reading -> {
                    item(key = "reading") {
                        ReadingProgressSection(
                            progress = step.progress,
                            numberFmt = numberFmt,
                            onCancel = onCancel,
                        )
                    }
                }
                is HistoryImportStep.CsvMapping -> {
                    item(key = "csv-mapping") {
                        CsvMappingSection(
                            mapping = step.mapping,
                            onUpdateMapping = onUpdateCsvMapping,
                            onUpdateDelimiter = onUpdateCsvDelimiter,
                            onContinue = onConfirmCsv,
                            onCancel = onCancel,
                        )
                    }
                }
                is HistoryImportStep.Preview -> {
                    item(key = "preview") {
                        ImportPreviewSection(
                            preview = step.preview,
                            playlistIncluded = step.playlistIncluded,
                            canApply = step.canApply,
                            isWriting = ui.isWriting,
                            zone = zone,
                            numberFmt = numberFmt,
                            onTogglePlaylist = onTogglePlaylist,
                            onOpenPlaylist = { onOpenPlaylist(it) },
                            observeSongs = observeSongs,
                            onConfirm = onConfirmImport,
                            onCancel = onCancel,
                            initiallyLikesExpanded = initiallyLikesExpanded,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportPickSection(
    step: HistoryImportStep,
    effectiveMode: EffectiveProductMode,
    zone: ZoneId,
    numberFmt: NumberFormat,
    onPickFiles: () -> Unit,
) {
    val isPinned = effectiveMode == EffectiveProductMode.PINNED
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DapSpacing.Md)) {
        Text(
            text = stringResource(R.string.import_history_formats_note),
            style = DapType.Body,
            color = Dap.secondaryText,
        )

        if (step is HistoryImportStep.Done) {
            val summaryText = stringResource(
                R.string.import_history_done_summary,
                numberFmt.format(step.plays),
                numberFmt.format(step.likesAdded),
                numberFmt.format(step.playlistsCreated),
            )
            DapState(kind = DapStateKind.Confirmed, label = summaryText)
            if (step.plays > 0) {
                val range = formatYearRange(step.dateRangeStartMs, step.dateRangeEndMs, zone)
                val historyLine = if (range != null) {
                    stringResource(
                        R.string.import_history_done_line,
                        numberFmt.format(step.plays),
                        range,
                        step.matchPercent,
                    )
                } else {
                    stringResource(
                        R.string.import_history_done_line_no_dates,
                        numberFmt.format(step.plays),
                        step.matchPercent,
                    )
                }
                Text(text = historyLine, style = DapType.Caption, color = Dap.secondaryText)
            }
        }

        if (step is HistoryImportStep.Error) {
            val msg = when (val err = step.error) {
                is HistoryImportError.Musicolet ->
                    stringResource(R.string.import_history_error_musicolet, err.fileNames.joinToString())
                is HistoryImportError.YouTubeHtml ->
                    stringResource(R.string.import_history_error_ytm_html)
                is HistoryImportError.Generic ->
                    stringResource(R.string.import_history_error_generic, err.message)
            }
            Column(verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs)) {
                DapState(kind = DapStateKind.Attention, label = stringResource(R.string.import_history_error_title))
                Text(text = msg, style = DapType.Caption, color = Dap.secondaryText)
            }
        }

        if (isPinned) {
            Text(
                text = stringResource(R.string.import_history_pinned_note),
                style = DapType.Caption,
                color = Dap.tertiaryText,
            )
        }

        DapPrimaryButton(
            label = stringResource(R.string.import_history_pick_title),
            onClick = onPickFiles,
            enabled = !isPinned,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReadingProgressSection(
    progress: ImportProgress,
    numberFmt: NumberFormat,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DapSpacing.Md)) {
        Text(
            text = stringResource(R.string.import_history_reading_title),
            style = DapType.Heading,
            color = Dap.primaryText,
        )
        Text(
            text = progress.currentFileName,
            style = DapType.Caption,
            color = Dap.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = LocalDapAccent.current,
            trackColor = Dap.trackStroke,
        )
        Text(
            text = stringResource(
                R.string.import_history_reading_progress,
                (progress.filesDone + 1).coerceAtMost(progress.filesTotal.coerceAtLeast(1)),
                progress.filesTotal.coerceAtLeast(1),
                numberFmt.format(progress.rowsRead),
            ),
            style = DapType.ValueSmall,
            color = Dap.tertiaryText,
        )
        DapSecondaryButton(
            label = stringResource(R.string.import_history_cancel),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun CsvMappingSection(
    mapping: CsvColumnMapping,
    onUpdateMapping: ((CsvColumnMapping) -> CsvColumnMapping) -> Unit,
    onUpdateDelimiter: (Char) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    val delimiters = listOf(',', ';', '\t', '|')
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DapSpacing.Md)) {
        DapGroup(title = stringResource(R.string.import_csv_title)) {
            Text(
                text = stringResource(R.string.import_csv_delimiter),
                style = DapType.Note,
                color = Dap.tertiaryText,
                modifier = Modifier.padding(top = DapSpacing.Sm, bottom = DapSpacing.Xs),
            )
            DapSegmentedPicker(
                options = delimiters,
                selected = mapping.delimiter,
                onSelect = onUpdateDelimiter,
                label = { d -> if (d == '\t') "Tab" else d.toString() },
                fillWidth = true,
            )
            Spacer(Modifier.height(DapSpacing.Sm))
            DapSwitchRow(
                label = stringResource(R.string.import_csv_has_header),
                checked = mapping.hasHeader,
                onCheckedChange = { checked -> onUpdateMapping { it.copy(hasHeader = checked) } },
            )
            CsvColumnPickerRow(
                label = stringResource(R.string.import_csv_col_date),
                selectedIndex = mapping.dateColumn,
                headers = mapping.headers,
                allowNone = false,
                onSelect = { col -> onUpdateMapping { it.copy(dateColumn = col ?: 0) } },
            )
            CsvColumnPickerRow(
                label = stringResource(R.string.import_csv_col_artist),
                selectedIndex = mapping.artistColumn,
                headers = mapping.headers,
                allowNone = false,
                onSelect = { col -> onUpdateMapping { it.copy(artistColumn = col ?: 0) } },
            )
            CsvColumnPickerRow(
                label = stringResource(R.string.import_csv_col_title),
                selectedIndex = mapping.titleColumn,
                headers = mapping.headers,
                allowNone = false,
                onSelect = { col -> onUpdateMapping { it.copy(titleColumn = col ?: 0) } },
            )
            CsvColumnPickerRow(
                label = stringResource(R.string.import_csv_col_album),
                selectedIndex = mapping.albumColumn,
                headers = mapping.headers,
                allowNone = true,
                onSelect = { col -> onUpdateMapping { it.copy(albumColumn = col) } },
            )
            CsvColumnPickerRow(
                label = stringResource(R.string.import_csv_col_duration),
                selectedIndex = mapping.durationColumn,
                headers = mapping.headers,
                allowNone = true,
                onSelect = { col -> onUpdateMapping { it.copy(durationColumn = col) } },
                divider = mapping.durationColumn != null,
            )
            if (mapping.durationColumn != null) {
                val msLabel = stringResource(R.string.import_csv_unit_ms)
                val sLabel = stringResource(R.string.import_csv_unit_s)
                Spacer(Modifier.height(DapSpacing.Xs))
                DapSegmentedPicker(
                    options = CsvDurationUnit.entries,
                    selected = mapping.durationUnit,
                    onSelect = { unit -> onUpdateMapping { it.copy(durationUnit = unit) } },
                    label = { if (it == CsvDurationUnit.MILLISECONDS) msLabel else sLabel },
                    fillWidth = true,
                )
            }
        }

        if (mapping.sampleRows.isNotEmpty()) {
            DapGroup(title = stringResource(R.string.import_csv_preview_rows)) {
                mapping.sampleRows.take(3).forEachIndexed { idx, row ->
                    val artist = row.getOrNull(mapping.artistColumn).orEmpty()
                    val title = row.getOrNull(mapping.titleColumn).orEmpty()
                    val date = row.getOrNull(mapping.dateColumn).orEmpty()
                    DapValueRow(
                        label = "$artist — $title".trim(' ', '—').ifEmpty { "—" },
                        divider = idx < mapping.sampleRows.take(3).lastIndex,
                    ) {
                        DapValue(text = date)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md)) {
            DapSecondaryButton(
                label = stringResource(R.string.import_history_cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            DapPrimaryButton(
                label = stringResource(R.string.import_csv_continue),
                onClick = onContinue,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CsvColumnPickerRow(
    label: String,
    selectedIndex: Int?,
    headers: List<String>,
    allowNone: Boolean,
    onSelect: (Int?) -> Unit,
    divider: Boolean = true,
) {
    val noneLabel = stringResource(R.string.import_csv_col_none)
    val valueText = selectedIndex?.let { idx -> headers.getOrNull(idx) } ?: noneLabel
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val maxIdx = headers.lastIndex
                    val next = when {
                        selectedIndex == null -> if (headers.isNotEmpty()) 0 else null
                        selectedIndex < maxIdx -> selectedIndex + 1
                        allowNone -> null
                        else -> 0
                    }
                    onSelect(next)
                }
                .padding(vertical = DapSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = DapType.SettingLabel,
                color = Dap.primaryText,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = DapType.Value,
                color = LocalDapAccent.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (divider) DapDivider()
    }
}

@Composable
internal fun ImportPreviewSection(
    preview: ImportPreview,
    playlistIncluded: Map<String, Boolean>,
    canApply: Boolean,
    isWriting: Boolean,
    zone: ZoneId,
    numberFmt: NumberFormat,
    onTogglePlaylist: (String, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onOpenPlaylist: (String) -> Unit = {},
    observeSongs: (List<String>) -> Flow<List<Song>> = { flowOf(emptyList()) },
    initiallyLikesExpanded: Boolean = false,
) {
    val counts = preview.counts
    var likesExpanded by remember(preview) { mutableStateOf(initiallyLikesExpanded) }
    var likesUnmatchedExpanded by remember(preview) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DapSpacing.SectionGap)) {
        if (preview.fileSummaries.isNotEmpty()) {
            DapGroup(title = stringResource(R.string.import_preview_files_title)) {
                preview.fileSummaries.forEachIndexed { idx, summary ->
                    FileSummaryRow(
                        summary = summary,
                        divider = idx < preview.fileSummaries.lastIndex,
                    )
                }
            }
        }

        if (preview.hasHistory) {
            DapGroup(title = stringResource(R.string.import_preview_title)) {
                DapValueRow(label = stringResource(R.string.import_preview_source)) {
                    DapValue(text = sourceKindLabel(preview.sourceKind))
                }
                formatYearRange(preview.dateRangeStartMs, preview.dateRangeEndMs, zone)?.let { range ->
                    DapValueRow(label = stringResource(R.string.import_preview_dates)) {
                        DapValue(text = range)
                    }
                }
                DapValueRow(label = stringResource(R.string.import_preview_rows_read)) {
                    DapValue(text = numberFmt.format(counts.rowsRead))
                }
                DapValueRow(label = stringResource(R.string.import_preview_plays)) {
                    DapValue(text = numberFmt.format(counts.plays))
                }
                if (counts.skips > 0) {
                    DapValueRow(label = stringResource(R.string.import_preview_skips)) {
                        DapValue(text = numberFmt.format(counts.skips))
                    }
                }
                if (counts.podcastsSkipped > 0) {
                    DapValueRow(label = stringResource(R.string.import_preview_podcasts)) {
                        DapValue(text = numberFmt.format(counts.podcastsSkipped))
                    }
                }
                if (counts.nonMusicSkipped > 0) {
                    DapValueRow(label = stringResource(R.string.import_preview_non_music)) {
                        DapValue(text = numberFmt.format(counts.nonMusicSkipped))
                    }
                }
                if (counts.duplicatesSkipped > 0) {
                    DapValueRow(label = stringResource(R.string.import_preview_duplicates)) {
                        DapValue(text = numberFmt.format(counts.duplicatesSkipped))
                    }
                }
                if (counts.rejectedCount > 0) {
                    DapValueRow(label = stringResource(R.string.import_preview_rejected)) {
                        DapValue(text = numberFmt.format(counts.rejectedCount))
                    }
                }
                DapValueRow(label = stringResource(R.string.import_preview_matched_exact)) {
                    DapValue(text = numberFmt.format(counts.matchedExact))
                }
                DapValueRow(label = stringResource(R.string.import_preview_matched_folded)) {
                    DapValue(text = numberFmt.format(counts.matchedFolded))
                }
                DapValueRow(label = stringResource(R.string.import_preview_matched_partial)) {
                    DapValue(text = numberFmt.format(counts.matchedPartial))
                }
                DapValueRow(
                    label = stringResource(R.string.import_preview_unmatched),
                    divider = counts.estimatedTimeMs > 0L || counts.zeroDurationPlays > 0,
                ) {
                    DapValue(text = numberFmt.format(counts.unmatched))
                }
                if (counts.estimatedTimeMs > 0L) {
                    DapValueRow(
                        label = stringResource(R.string.import_preview_estimated_time),
                        divider = counts.zeroDurationPlays > 0,
                    ) {
                        DapValue(text = formatListeningDurationCompact(counts.estimatedTimeMs))
                    }
                }
                if (counts.zeroDurationPlays > 0) {
                    DapValueRow(
                        label = stringResource(R.string.import_preview_zero_duration),
                        divider = false,
                    ) {
                        DapValue(text = numberFmt.format(counts.zeroDurationPlays))
                    }
                }
            }

            if (preview.sourceKind == ImportSourceKind.YOUTUBE_MUSIC) {
                Text(
                    text = stringResource(R.string.import_preview_ytm_note),
                    style = DapType.Caption,
                    color = Dap.secondaryText,
                )
            }

            if (preview.topUnmatchedArtists.isNotEmpty()) {
                DapGroup(title = stringResource(R.string.import_preview_top_unmatched)) {
                    preview.topUnmatchedArtists.forEachIndexed { idx, entry ->
                        DapValueRow(
                            label = entry.name,
                            divider = idx < preview.topUnmatchedArtists.lastIndex,
                        ) {
                            DapValue(text = numberFmt.format(entry.plays))
                        }
                    }
                }
            }
        }

        if (preview.likes.totalRead > 0) {
            val likes = preview.likes
            DapGroup(title = stringResource(R.string.import_preview_likes_title)) {
                DapNavRow(
                    label = stringResource(
                        R.string.import_likes_summary,
                        numberFmt.format(likes.matchedCount),
                        numberFmt.format(likes.alreadyFavoriteCount),
                    ),
                    onClick = if (likes.matchedTracks.isNotEmpty() || likes.unmatchedTracks.isNotEmpty()) {
                        { likesExpanded = !likesExpanded }
                    } else null,
                    divider = likesExpanded,
                )
                if (likesExpanded) {
                    likes.matchedTracks.forEachIndexed { idx, track ->
                        DapMediaRow(
                            title = track.title,
                            subtitle = track.artist,
                            duration = formatTrackDuration(track.durationMs),
                            divider = idx < likes.matchedTracks.lastIndex || likes.unmatchedTracks.isNotEmpty(),
                        )
                    }
                    if (likes.unmatchedTracks.isNotEmpty()) {
                        DapNavRow(
                            label = pluralStringResource(
                                R.plurals.import_playlist_unmatched_count,
                                likes.unmatchedCount,
                                numberFmt.format(likes.unmatchedCount),
                            ),
                            onClick = { likesUnmatchedExpanded = !likesUnmatchedExpanded },
                            divider = likesUnmatchedExpanded,
                        )
                        if (likesUnmatchedExpanded) {
                            likes.unmatchedTracks.forEachIndexed { uIdx, u ->
                                DapMediaRow(
                                    title = u.title,
                                    subtitle = u.artist.ifBlank { null },
                                    state = DapRowState.Unavailable,
                                    divider = uIdx < likes.unmatchedTracks.lastIndex,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (preview.playlists.isNotEmpty() || preview.hiddenZeroMatchPlaylists > 0) {
            DapGroup(title = stringResource(R.string.import_preview_playlists_title)) {
                // Review 11-25: the same row as the Playlists tab (cover collage, name, count), so a found playlist looks
                // like the playlist it becomes. Tapping it opens its page; the switch decides whether Done creates it.
                preview.playlists.forEach { pl ->
                    val included = playlistIncluded[pl.targetId] ?: pl.included
                    UserPlaylistRow(
                        playlist = remember(pl) { pl.asLibraryPlaylist() },
                        observeSongs = observeSongs,
                        onClick = { onOpenPlaylist(pl.targetId) },
                        subtitle = stringResource(
                            R.string.import_playlist_match_count,
                            numberFmt.format(pl.matchedCount),
                            numberFmt.format(pl.totalItems),
                        ),
                        trailing = {
                            DapSwitch(
                                checked = included,
                                onCheckedChange = { checked -> onTogglePlaylist(pl.targetId, checked) },
                            )
                        },
                    )
                }
                if (preview.hiddenZeroMatchPlaylists > 0) {
                    DapNote(
                        text = pluralStringResource(
                            R.plurals.import_playlists_hidden_zero,
                            preview.hiddenZeroMatchPlaylists,
                            numberFmt.format(preview.hiddenZeroMatchPlaylists),
                        ),
                    )
                }
                if (preview.playlistEpisodesSkipped > 0 || preview.playlistLocalTracksSkipped > 0) {
                    DapNote(
                        text = stringResource(
                            R.string.import_playlists_skipped_items,
                            numberFmt.format(preview.playlistEpisodesSkipped),
                            numberFmt.format(preview.playlistLocalTracksSkipped),
                        ),
                    )
                }
            }
        }

        if (!canApply) {
            Text(
                text = stringResource(R.string.import_preview_nothing_new),
                style = DapType.Caption,
                color = Dap.secondaryText,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md)) {
            DapSecondaryButton(
                label = stringResource(R.string.import_history_cancel),
                onClick = onCancel,
                enabled = !isWriting,
                modifier = Modifier.weight(1f),
            )
            DapPrimaryButton(
                label = stringResource(R.string.import_preview_confirm),
                onClick = onConfirm,
                enabled = !isWriting && canApply,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FileSummaryRow(
    summary: ImportFileSummary,
    divider: Boolean,
) {
    val label = summary.fileName.substringAfterLast('/')
    val detail = when (summary.dataKind) {
        ImportFileDataKind.SPOTIFY_EXTENDED_HISTORY -> stringResource(R.string.import_file_kind_spotify_extended)
        ImportFileDataKind.SPOTIFY_ACCOUNT_HISTORY -> stringResource(R.string.import_file_kind_spotify_short)
        ImportFileDataKind.SPOTIFY_PLAYLISTS -> stringResource(R.string.import_file_kind_spotify_playlists)
        ImportFileDataKind.SPOTIFY_LIKES -> stringResource(R.string.import_file_kind_spotify_likes)
        ImportFileDataKind.YOUTUBE_MUSIC_HISTORY -> stringResource(R.string.import_file_kind_ytm_history)
        ImportFileDataKind.CSV_HISTORY -> stringResource(R.string.import_file_kind_csv_history)
        ImportFileDataKind.M3U_PLAYLIST -> stringResource(R.string.import_file_kind_m3u)
        ImportFileDataKind.POLYLUTE_HISTORY -> stringResource(R.string.import_file_kind_polylute_history)
        ImportFileDataKind.POLYLUTE_LIKES -> stringResource(R.string.import_file_kind_polylute_likes)
        ImportFileDataKind.POLYLUTE_PLAYLISTS -> stringResource(R.string.import_file_kind_polylute_playlists)
        ImportFileDataKind.SKIPPED -> when (summary.skipReason) {
            ImportFileSkipReason.YOUTUBE_VIDEO_ID_ONLY -> stringResource(R.string.import_file_skip_ytm_video_ids)
            ImportFileSkipReason.DUPLICATE_FILE -> stringResource(R.string.import_file_skip_duplicate)
            ImportFileSkipReason.NON_MUSIC_METADATA -> stringResource(R.string.import_file_skip_metadata)
            ImportFileSkipReason.UNSUPPORTED_FORMAT -> stringResource(R.string.import_file_skip_unsupported)
            ImportFileSkipReason.EMPTY_OR_NO_TRACKS, null -> stringResource(R.string.import_file_skip_empty)
        }
    }
    val isSkipped = summary.dataKind == ImportFileDataKind.SKIPPED
    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = DapSpacing.Sm),
        ) {
            Text(
                text = label,
                style = DapType.SettingLabel,
                color = if (isSkipped) Dap.secondaryText else Dap.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                style = DapType.Caption,
                color = if (isSkipped) Dap.warning else Dap.secondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (divider) DapDivider()
    }
}

private fun formatTrackDuration(durationMs: Long): String? {
    if (durationMs <= 0L) return null
    val totalSeconds = durationMs / 1000L
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return "%d:%02d".format(Locale.US, mins, secs)
}

@Composable
internal fun ImportedHistoryListSection(
    records: List<ImportRecord>,
    onRemove: (String) -> Unit,
    locale: Locale = currentLocale(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val numberFmt = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    var pendingDelete by remember { mutableStateOf<ImportRecord?>(null) }

    DapGroup(title = stringResource(R.string.import_history_past_title)) {
        if (records.isEmpty()) {
            Text(
                text = stringResource(R.string.import_history_empty),
                style = DapType.Caption,
                color = Dap.tertiaryText,
                modifier = Modifier.padding(vertical = DapSpacing.Sm),
            )
        } else {
            records.forEachIndexed { idx, rec ->
                val range = formatYearRange(rec.dateRangeStartMs, rec.dateRangeEndMs, zone)
                val title = buildString {
                    append(sourceKindLabel(rec.sourceKind))
                    if (range != null) append(" · ").append(range)
                }
                val summary = stringResource(
                    R.string.import_history_row_summary,
                    numberFmt.format(rec.counts.plays),
                    rec.counts.matchPercent,
                )
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = DapSpacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = DapType.SettingLabel,
                                color = Dap.primaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = rec.fileNames.joinToString(),
                                style = DapType.Note,
                                color = Dap.tertiaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = summary,
                                style = DapType.ValueSmall,
                                color = Dap.secondaryText,
                                maxLines = 1,
                            )
                        }
                        DapTextAction(
                            label = stringResource(R.string.import_history_remove),
                            onClick = { pendingDelete = rec },
                            color = Dap.warning,
                        )
                    }
                    if (idx < records.lastIndex) DapDivider()
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                ModalInputBridge()
                Text(stringResource(R.string.import_history_remove_confirm_title))
            },
            text = {
                Text(
                    stringResource(
                        R.string.import_history_remove_confirm_body,
                        numberFmt.format(target.counts.plays),
                        sourceKindLabel(target.sourceKind),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(target.id)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.import_history_remove), color = Dap.warning)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.import_history_cancel))
                }
            },
        )
    }
}

@Composable
internal fun sourceKindLabel(kind: ImportSourceKind): String = stringResource(
    when (kind) {
        ImportSourceKind.SPOTIFY_EXTENDED -> R.string.import_source_spotify_extended
        ImportSourceKind.SPOTIFY_ACCOUNT -> R.string.import_source_spotify_short
        ImportSourceKind.YOUTUBE_MUSIC -> R.string.import_source_youtube_music
        ImportSourceKind.CSV -> R.string.import_source_csv
        ImportSourceKind.POLYLUTE_JSON, ImportSourceKind.POLYLUTE_CSV -> R.string.import_source_polylute
    },
)

internal fun formatYearRange(startMs: Long?, endMs: Long?, zone: ZoneId): String? {
    if (startMs == null || endMs == null) return null
    val startYear = Instant.ofEpochMilli(startMs).atZone(zone).year
    val endYear = Instant.ofEpochMilli(endMs).atZone(zone).year
    return if (startYear == endYear) "$startYear" else "$startYear–$endYear"
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}

/** A found playlist as the library playlist Done would create (same id), for [UserPlaylistRow] and [PlaylistCover]. */
private fun ImportPlaylistPreview.asLibraryPlaylist(): Playlist =
    Playlist(id = targetId, name = name, songIds = matchedTracks.map { it.songId })

@Composable
private fun ImportPlaylistSongs(
    playlist: ImportPlaylistPreview,
    numberFmt: NumberFormat,
    observeSongs: (List<String>) -> Flow<List<Song>>,
) {
    val ids = remember(playlist) { playlist.matchedTracks.map { it.songId }.distinct() }
    val observed by remember(ids, observeSongs) { observeSongs(ids) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val songsById = remember(observed) { observed.associateBy(Song::id) }
    var unmatchedOpen by remember(playlist.targetId) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = symmetricContentPadding(
            horizontal = PlaylistSongListInset,
            top = DapSpacing.Sm,
            bottom = LocalDapPlayerShelfHeight.current + DapSpacing.Xl,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            playlist.matchedTracks,
            key = { index, track -> "found-$index-${track.songId}" },
            contentType = { _, _ -> "playlist_song" },
        ) { _, track ->
            val song = songsById[track.songId] ?: remember(track) {
                Song.emptySong().copy(
                    id = track.songId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album.orEmpty(),
                    duration = track.durationMs,
                )
            }
            LibraryPlaybackAwareSongItem(
                song = song,
                showDuration = song.duration > 0,
                showMoreOptionsButton = false,
                onMoreOptionsClick = {},
                onClick = null,
            )
        }
        if (playlist.unmatchedTracks.isNotEmpty()) {
            val indent = Modifier.padding(horizontal = DapSpacing.Gutter - PlaylistSongListInset)
            item(key = "unmatched") {
                DapNavRow(
                    label = pluralStringResource(
                        R.plurals.import_playlist_unmatched_count,
                        playlist.unmatchedCount,
                        numberFmt.format(playlist.unmatchedCount),
                    ),
                    modifier = indent,
                    onClick = { unmatchedOpen = !unmatchedOpen },
                    divider = unmatchedOpen,
                )
            }
            if (unmatchedOpen) {
                itemsIndexed(
                    playlist.unmatchedTracks,
                    key = { index, _ -> "unavailable-$index" },
                    contentType = { _, _ -> "unavailable" },
                ) { index, track ->
                    DapMediaRow(
                        title = track.title,
                        subtitle = track.artist.ifBlank { null },
                        modifier = indent,
                        state = DapRowState.Unavailable,
                        divider = index < playlist.unmatchedTracks.lastIndex,
                    )
                }
            }
        }
    }
}
