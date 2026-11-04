package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.lostf1sh.pixelplayeross.data.offline.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class InfoAction { OPEN, FIND, CHOOSE, CLEAR, DOWNLOAD, DELETE, PAUSE, CANCEL, UNDO, DONE }
data class InfoActionError(val action: InfoAction)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class OfflineDownloadsViewModel internal constructor(
    private val downloads: MusicInfoDownloads,
    private val background: kotlinx.coroutines.CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(downloads: MusicInfoDownloads) : this(downloads, infoBackground)
    private val scope = MutableStateFlow("library")
    val search = MutableStateFlow("")
    val error = MutableStateFlow(false)
    val errors = MutableStateFlow<Map<InfoAction, InfoActionError>>(emptyMap())
    val busy = MutableStateFlow<Set<InfoAction>>(emptySet())
    val opening = MutableStateFlow(true)
    val findQueued = MutableStateFlow(false)
    val close = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val queue = downloads.dao.queue()
    val downloadProgress = downloads.dao.downloadProgress()
    val completedBytes = downloadProgress.mapLatest { progress ->
        if (progress.total > 0 && progress.done == progress.total && progress.pending == 0 && progress.retry == 0) {
            downloads.downloadedBytes()
        } else null
    }.flowOn(background).catch { failure ->
        if (failure is CancellationException) throw failure
        timber.log.Timber.tag("MusicInfo").w(failure, "Could not measure saved downloads")
        emit(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private var model: InfoScreenModel? = null
    private var loadedScope: String? = null
    private val opens = Mutex()
    private val models = MutableStateFlow<InfoScreenModel?>(null)
    private val revision = MutableStateFlow(0L)
    val types = MutableStateFlow<List<InfoTypeCounts>>(emptyList())
    val selection = MutableStateFlow(InfoSelection(0, 0, null, 0))
    val findProgress = MutableStateFlow(InfoProgress(0, 0, 0))
    val checkedAt = MutableStateFlow(0L)
    val pendingKeys = MutableStateFlow<Set<String>>(emptySet())
    private val delayedChoices = mutableMapOf<Long, List<String>>()
    private val changedKeys = linkedSetOf<String>()
    private val deltaSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val removeChangeListener = downloads.dao.listenChanges { keys ->
        synchronized(changedKeys) { changedKeys.addAll(keys) }
        deltaSignal.trySend(Unit)
    }
    private fun publish() {
        model?.let { m -> types.value = m.types(); selection.value = m.selection()
            findProgress.value = m.progress(); checkedAt.value = m.checkedAt }
        revision.value++
    }
    val network = downloads.network
    val sourceWaits = downloads.sourceWaits
    private val clock = flow { while (true) { emit(System.currentTimeMillis()); delay(1000) } }
    /** One named wait projection combines constraints, live requests and persisted retry deadlines. */
    val waiting = combine(network, sourceWaits, downloads.dao.sourceRetries(), clock) { network, live, persisted, now ->
        val saved = persisted.mapNotNull { retry ->
            runCatching { com.lostf1sh.pixelplayeross.data.network.SourceWait(retry.source,
                com.lostf1sh.pixelplayeross.data.network.SourceWaitKind.valueOf(retry.reason), retry.retryAt) }.getOrNull()
        }
        val sources = (saved + live.values).filter { it.retryAt > now }.groupBy { it.source }
            .map { (_, waits) -> waits.maxBy { it.retryAt } }
        InfoWaitState(network, sources, now)
    }.flowOn(background).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InfoWaitState())
    val undoActions = downloads.dao.undoActions()
    private val choices = Mutex()
    private val retries = mutableMapOf<InfoAction, suspend () -> Unit>()
    private var undoFailureToken: String? = null
    private var findRequested = false
    val artists = combine(models, search) { model, search -> model?.artists(search).orEmpty() }
        .distinctUntilChanged().map { androidx.paging.PagingData.from(it) }.flowOn(background).cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            for (signal in deltaSignal) {
                delay(150) // Coalesce worker notifications; never re-read the full scope.
                // A delta received between rows and members belongs after the complete snapshot.
                opening.first { !it }
                val owner = model ?: continue
                val keys = synchronized(changedKeys) { changedKeys.toList().also { changedKeys.clear() } }
                val rows = kotlinx.coroutines.withContext(background) { keys.chunked(400).flatMap { downloads.dao.changedRows(it) } }
                if (!opening.value && owner === model) {
                    owner.merge(rows)
                    publish()
                } else {
                    synchronized(changedKeys) { changedKeys.addAll(keys) }
                    deltaSignal.trySend(Unit)
                }
            }
        }
        viewModelScope.launch {
            undoActions.collect { actions ->
                val failed = actions.firstOrNull { it.failed }
                if (failed != null) {
                    undoFailureToken = failed.token
                    errors.update { it + (InfoAction.DELETE to InfoActionError(InfoAction.DELETE)) }
                    error.value = true
                    retries[InfoAction.DELETE] = { downloads.retryDelete(failed.token) }
                } else if (undoFailureToken != null) {
                    undoFailureToken = null
                    errors.update { it - InfoAction.DELETE }
                    error.value = errors.value.isNotEmpty()
                }
            }
        }
    }

    private fun run(kind: InfoAction, action: suspend () -> Unit) {
        retries[kind] = action
        viewModelScope.launch {
            errors.update { it - kind }
            error.value = errors.value.isNotEmpty()
            busy.update { it + kind }
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                timber.log.Timber.tag("MusicInfo").w(failure, "Music info %s failed", kind)
                errors.update { it + (kind to InfoActionError(kind)) }
                error.value = true
            } finally {
                busy.update { it - kind }
            }
        }
    }

    fun retry(kind: InfoAction) { retries[kind]?.let { run(kind, it) } }

    fun open(ids: List<String>?, artistFilter: String? = null) = run(InfoAction.OPEN) {
        opens.withLock {
            opening.value = true
            errors.value = emptyMap()
            error.value = false
            try {
                val target = kotlinx.coroutines.withContext(background) { downloads.scopeKey(ids, artistFilter) }
                scope.value = target
                if (loadedScope != target || model == null) {
                    downloads.open(ids, artistFilter)
                    model = kotlinx.coroutines.withContext(background) {
                        InfoScreenModel(downloads.dao.scopeRows(target), downloads.dao.scopeMembers(target), target)
                    }
                    loadedScope = target
                    models.value = model
                    publish()
                }
                if (findRequested) {
                    findRequested = false
                    findQueued.value = false
                    downloads.find(target)
                }
            } finally {
                opening.value = false
            }
        }
    }

    fun choices(artist: String): Flow<List<InfoChoice>> = revision.map { model?.choices(artist).orEmpty() }.distinctUntilChanged()
    fun groupCounts(artist: String, album: String? = null) = revision.map {
        model?.groupCounts(artist, album) ?: InfoGroupCounts()
    }.distinctUntilChanged()
    fun find() = run(InfoAction.FIND) {
        if (opening.value) {
            findRequested = true
            findQueued.value = true
            return@run
        }
        downloads.find(scope.value)
    }
    fun download() = run(InfoAction.DOWNLOAD) { downloads.appendSelected(scope.value) }
    fun delete() = run(InfoAction.DELETE) { downloads.deleteSelected(scope.value) }
    fun pause() = run(InfoAction.PAUSE) { downloads.pause() }
    fun resume() = run(InfoAction.PAUSE) { downloads.resume() }
    fun clearSelection() {
        val target = scope.value
        choose(model?.selectedKeys().orEmpty(), false, clear = true) { downloads.clearSelection(target) }
    }
    fun cancel() = run(InfoAction.CANCEL) { downloads.cancel() }
    fun undo(token: String) = run(InfoAction.UNDO) { check(downloads.undo(token)) { "Undo window has closed" } }
    fun done() = run(InfoAction.DONE) { downloads.done(); close.emit(Unit) }
    /** Update on the tap frame, then serialize database writes in tap order. */
    private fun choose(keys: List<String>, selected: Boolean, clear: Boolean = false, write: suspend () -> Unit) {
        val owner = model
        val intent = owner?.choose(keys, selected, clear)
        publish()
        val delayed = viewModelScope.launch {
            delay(300)
            if (intent != null) { delayedChoices[intent.id] = keys; pendingKeys.value = delayedChoices.values.flatten().toSet() }
        }
        run(if (clear) InfoAction.CLEAR else InfoAction.CHOOSE) {
            choices.withLock {
                try { write() }
                finally {
                    val fresh = try { keys.chunked(400).flatMap { downloads.dao.changedRows(it) } }
                    catch (failure: Exception) { if (failure is CancellationException) throw failure; emptyList() }
                    if (owner === model && intent != null) { owner.merge(fresh, intent.id); publish() }
                    delayed.cancel()
                    intent?.let { delayedChoices.remove(it.id) }
                    pendingKeys.value = delayedChoices.values.flatten().toSet()
                }
            }
        }
    }
    fun select(key: String, selected: Boolean) = choose(listOf(key), selected) { downloads.dao.select(key, selected) }
    fun selectGroup(artist: String, album: String?, selected: Boolean) {
        val target = scope.value
        choose(model?.keys(artist, album).orEmpty(), selected) { downloads.dao.selectGroup(target, artist, album, selected) }
    }
    fun selectKind(kind: InfoKind, selected: Boolean) {
        val target = scope.value
        choose(model?.keys(kind).orEmpty() + "kind:${kind.name}", selected) { downloads.dao.selectAvailableKind(target, kind.name, selected) }
    }
    override fun onCleared() { removeChangeListener(); deltaSignal.close() }
}
