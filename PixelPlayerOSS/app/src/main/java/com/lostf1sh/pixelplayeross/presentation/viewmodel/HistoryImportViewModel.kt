package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.historyimport.CsvColumnMapping
import com.lostf1sh.pixelplayeross.data.historyimport.CsvHistoryReader
import com.lostf1sh.pixelplayeross.data.historyimport.ImportInputSource
import com.lostf1sh.pixelplayeross.data.historyimport.ImportPreview
import com.lostf1sh.pixelplayeross.data.historyimport.ImportProgress
import com.lostf1sh.pixelplayeross.data.historyimport.ImportRecord
import com.lostf1sh.pixelplayeross.data.historyimport.ImportSourceKind
import com.lostf1sh.pixelplayeross.data.historyimport.ImportedHistoryStore
import com.lostf1sh.pixelplayeross.data.historyimport.MusicoletUnsupportedException
import com.lostf1sh.pixelplayeross.data.historyimport.UnifiedImportApplier
import com.lostf1sh.pixelplayeross.data.historyimport.YouTubeHtmlFormatException
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

sealed interface HistoryImportStep {
    data object Idle : HistoryImportStep
    data class Reading(val progress: ImportProgress) : HistoryImportStep
    data class CsvMapping(
        val sources: List<ImportInputSource>,
        val mapping: CsvColumnMapping,
    ) : HistoryImportStep
    data class Preview(
        val preview: ImportPreview,
        val playlistIncluded: Map<String, Boolean> = preview.playlists.associate { it.targetId to it.included },
        /** Review 11-25: the found playlist shown as its own page (like a Polylute playlist), or null for the review. */
        val openPlaylistId: String? = null,
    ) : HistoryImportStep {
        val canApply: Boolean
            get() = preview.counts.plays > 0 ||
                preview.likes.newCount > 0 ||
                preview.playlists.any { (playlistIncluded[it.targetId] ?: it.included) && it.matchedTracks.isNotEmpty() }
    }
    data class Done(
        val plays: Int,
        val likesAdded: Int = 0,
        val playlistsCreated: Int = 0,
        val dateRangeStartMs: Long?,
        val dateRangeEndMs: Long?,
        val matchPercent: Int,
        val sourceKind: ImportSourceKind,
    ) : HistoryImportStep
    data class Error(val error: HistoryImportError) : HistoryImportStep
}

sealed interface HistoryImportError {
    data class Musicolet(val fileNames: List<String>) : HistoryImportError
    data object YouTubeHtml : HistoryImportError
    data class Generic(val message: String) : HistoryImportError
}

data class HistoryImportUiState(
    val step: HistoryImportStep = HistoryImportStep.Idle,
    val pastImports: List<ImportRecord> = emptyList(),
    val isWriting: Boolean = false,
)

@HiltViewModel
class HistoryImportViewModel @Inject constructor(
    private val store: ImportedHistoryStore,
    private val dispatchers: DispatcherProvider,
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository? = null,
    private val unifiedImportApplier: UnifiedImportApplier? = null,
) : ViewModel() {

    constructor(
        store: ImportedHistoryStore,
        dispatchers: DispatcherProvider,
        context: Context,
    ) : this(store, dispatchers, context, null, null)

    private val _uiState = MutableStateFlow(HistoryImportUiState(pastImports = store.records.value))
    val uiState: StateFlow<HistoryImportUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var pendingSources: List<ImportInputSource> = emptyList()

    init {
        viewModelScope.launch {
            store.records.collect { records ->
                _uiState.update { it.copy(pastImports = records) }
            }
        }
    }

    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val sources = uris.map { uri ->
            val name = resolveDisplayName(uri)
            ImportInputSource(displayName = name) {
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open $name")
            }
        }
        onSourcesPicked(sources)
    }

    internal fun onSourcesPicked(sources: List<ImportInputSource>) {
        if (sources.isEmpty()) return
        activeJob?.cancel()
        pendingSources = sources
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    step = HistoryImportStep.Reading(
                        ImportProgress(0, sources.size, 0, sources.first().displayName),
                    ),
                )
            }
            try {
                val csvMapping = withContext(dispatchers.io) {
                    store.inspectInput(sources)
                }
                if (csvMapping != null) {
                    _uiState.update { it.copy(step = HistoryImportStep.CsvMapping(sources, csvMapping)) }
                } else {
                    runPrepare(sources, csvMapping = null)
                }
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(step = HistoryImportStep.Idle) }
                throw cancelled
            } catch (t: Throwable) {
                handleImportError(t)
            }
        }
    }

    fun updateCsvMapping(transform: (CsvColumnMapping) -> CsvColumnMapping) {
        val current = _uiState.value.step as? HistoryImportStep.CsvMapping ?: return
        val updated = transform(current.mapping)
        _uiState.update { it.copy(step = current.copy(mapping = updated)) }
    }

    fun updateCsvDelimiter(delimiter: Char) {
        val current = _uiState.value.step as? HistoryImportStep.CsvMapping ?: return
        viewModelScope.launch {
            val reInspected = runCatching {
                withContext(dispatchers.io) {
                    current.sources.first().openStream().use { stream ->
                        reparseCsvSample(stream, delimiter, current.mapping.hasHeader)
                    }
                }
            }.getOrNull()
            if (reInspected != null) {
                _uiState.update { it.copy(step = current.copy(mapping = reInspected)) }
            }
        }
    }

    fun confirmCsvMapping() {
        val current = _uiState.value.step as? HistoryImportStep.CsvMapping ?: return
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    step = HistoryImportStep.Reading(
                        ImportProgress(0, current.sources.size, 0, current.sources.first().displayName),
                    ),
                )
            }
            try {
                runPrepare(current.sources, current.mapping)
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(step = HistoryImportStep.Idle) }
                throw cancelled
            } catch (t: Throwable) {
                handleImportError(t)
            }
        }
    }

    private suspend fun runPrepare(sources: List<ImportInputSource>, csvMapping: CsvColumnMapping?) {
        val existingFavorites = withContext(dispatchers.io) {
            runCatching { musicRepository?.getFavoriteSongIdsOnce().orEmpty() }.getOrDefault(emptySet())
        }
        val preview = store.prepareImport(
            sources = sources,
            csvMapping = csvMapping,
            existingFavoriteSongIds = existingFavorites,
        ) { progress ->
            _uiState.update { state ->
                if (state.step is HistoryImportStep.Reading) {
                    state.copy(step = HistoryImportStep.Reading(progress))
                } else {
                    state
                }
            }
        }
        _uiState.update { it.copy(step = HistoryImportStep.Preview(preview)) }
    }

    fun openPlaylist(targetId: String?) {
        val previewStep = _uiState.value.step as? HistoryImportStep.Preview ?: return
        _uiState.update { it.copy(step = previewStep.copy(openPlaylistId = targetId)) }
    }

    /** The library songs behind a found playlist, in its order, for the same covers and rows a Polylute playlist uses. */
    fun observeSongs(songIds: List<String>): Flow<List<Song>> =
        musicRepository?.getSongsByIds(songIds) ?: flowOf(emptyList())

    fun setPlaylistIncluded(targetId: String, included: Boolean) {
        val previewStep = _uiState.value.step as? HistoryImportStep.Preview ?: return
        val updated = previewStep.playlistIncluded.toMutableMap().apply {
            this[targetId] = included
        }
        _uiState.update { it.copy(step = previewStep.copy(playlistIncluded = updated)) }
    }

    fun confirmImport() {
        val previewStep = _uiState.value.step as? HistoryImportStep.Preview ?: return
        if (!previewStep.canApply) return
        if (_uiState.value.isWriting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isWriting = true) }
            try {
                val includedIds = previewStep.playlistIncluded
                    .filterValues { it }
                    .keys
                val applier = unifiedImportApplier
                if (applier != null) {
                    val result = withContext(dispatchers.io) {
                        applier.apply(previewStep.preview, includedPlaylistIds = includedIds)
                    }
                    _uiState.update {
                        it.copy(
                            isWriting = false,
                            step = HistoryImportStep.Done(
                                plays = result.playsAdded,
                                likesAdded = result.likesAdded,
                                playlistsCreated = result.playlistsCreated,
                                dateRangeStartMs = result.dateRangeStartMs,
                                dateRangeEndMs = result.dateRangeEndMs,
                                matchPercent = result.matchPercent,
                                sourceKind = result.sourceKind,
                            ),
                        )
                    }
                } else {
                    val record = store.commitImport(previewStep.preview)
                    _uiState.update {
                        it.copy(
                            isWriting = false,
                            step = HistoryImportStep.Done(
                                plays = record.counts.plays,
                                likesAdded = 0,
                                playlistsCreated = 0,
                                dateRangeStartMs = record.dateRangeStartMs,
                                dateRangeEndMs = record.dateRangeEndMs,
                                matchPercent = record.counts.matchPercent,
                                sourceKind = record.sourceKind,
                            ),
                        )
                    }
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isWriting = false) }
                handleImportError(t)
            }
        }
    }

    fun cancelActiveFlow() {
        activeJob?.cancel()
        activeJob = null
        pendingSources = emptyList()
        _uiState.update { it.copy(step = HistoryImportStep.Idle, isWriting = false) }
    }

    fun removeImport(importId: String) {
        viewModelScope.launch {
            store.removeImport(importId)
        }
    }

    private fun handleImportError(t: Throwable) {
        Timber.w(t, "Listening history import failed")
        val err = when (t) {
            is MusicoletUnsupportedException -> HistoryImportError.Musicolet(t.detectedFiles)
            is YouTubeHtmlFormatException -> HistoryImportError.YouTubeHtml
            else -> HistoryImportError.Generic(t.localizedMessage ?: t.javaClass.simpleName)
        }
        _uiState.update { it.copy(step = HistoryImportStep.Error(err), isWriting = false) }
    }

    private fun reparseCsvSample(stream: InputStream, delimiter: Char, hasHeader: Boolean): CsvColumnMapping {
        val base = CsvHistoryReader.inspect(stream)
        val lines = streamToLines(pendingSources.first().openStream(), 25)
        val parsed = lines.map { CsvHistoryReader.parseSingleLine(it, delimiter) }.filter { it.any(String::isNotBlank) }
        val colCount = parsed.maxOfOrNull { it.size } ?: base.headers.size
        val headers = if (hasHeader && parsed.isNotEmpty()) {
            (0 until colCount).map { i -> parsed.first().getOrNull(i)?.trim().orEmpty().ifEmpty { "Column ${i + 1}" } }
        } else {
            (0 until colCount).map { i -> "Column ${i + 1}" }
        }
        val samples = if (hasHeader) parsed.drop(1).take(5) else parsed.take(5)
        return base.copy(
            delimiter = delimiter,
            hasHeader = hasHeader,
            headers = headers,
            sampleRows = samples,
            dateColumn = base.dateColumn.coerceIn(0, (colCount - 1).coerceAtLeast(0)),
            artistColumn = base.artistColumn.coerceIn(0, (colCount - 1).coerceAtLeast(0)),
            titleColumn = base.titleColumn.coerceIn(0, (colCount - 1).coerceAtLeast(0)),
            albumColumn = base.albumColumn?.takeIf { it < colCount },
            durationColumn = base.durationColumn?.takeIf { it < colCount },
        )
    }

    private fun streamToLines(stream: InputStream, limit: Int): List<String> =
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val out = ArrayList<String>(limit)
            while (out.size < limit) {
                val line = reader.readLine() ?: break
                val clean = if (out.isEmpty()) line.removePrefix("\uFEFF") else line
                if (clean.isNotBlank()) out += clean
            }
            out
        }

    private fun resolveDisplayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "import"
    }
}
