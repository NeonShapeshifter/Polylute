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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class OfflineDownloadsViewModel @Inject constructor(private val downloads: MusicInfoDownloads) : ViewModel() {
    private val scope = MutableStateFlow("library")
    val search = MutableStateFlow("")
    val error = MutableStateFlow(false)
    val opening = MutableStateFlow(true)
    val queue = downloads.dao.queue()
    val downloadProgress = downloads.dao.downloadProgress()
    val findProgress = scope.flatMapLatest { downloads.dao.findProgress(it) }
    val selection = scope.flatMapLatest { downloads.dao.selection(it) }
    private var findRequested = false
    val artists = combine(scope, search) { scope, search -> scope to search }
        .flatMapLatest { (scope, search) -> downloads.artists(scope, search) }.cachedIn(viewModelScope)

    private fun run(action: suspend () -> Unit) {
        viewModelScope.launch {
            error.value = false
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                timber.log.Timber.tag("MusicInfo").w(error, "Music info action failed")
                this@OfflineDownloadsViewModel.error.value = true
            }
        }
    }

    fun open(ids: List<String>?, artistFilter: String? = null) = run {
        opening.value = true
        try {
            scope.value = kotlinx.coroutines.withContext(infoBackground) {
                downloads.scopeKey(ids, artistFilter)
            }
            downloads.open(ids, artistFilter)
            if (findRequested) {
                findRequested = false
                downloads.find(scope.value)
            }
        } finally {
            opening.value = false
        }
    }

    fun choices(artist: String) = downloads.dao.choices(scope.value, artist)
    fun find() = run {
        if (opening.value) findRequested = true
        downloads.find(scope.value)
    }
    fun download() = run { downloads.appendSelected(scope.value) }
    fun delete() = run { downloads.deleteSelected(scope.value) }
    fun pause() = run { downloads.pause() }
    fun resume() = run { downloads.resume() }
    fun select(key: String, selected: Boolean) = run { downloads.dao.select(key, selected) }
    fun selectGroup(artist: String, album: String?, selected: Boolean) = run { downloads.dao.selectGroup(scope.value, artist, album, selected) }
    fun selectKind(kind: InfoKind, selected: Boolean) = run { downloads.dao.selectKind(scope.value, kind.name, selected) }
}
