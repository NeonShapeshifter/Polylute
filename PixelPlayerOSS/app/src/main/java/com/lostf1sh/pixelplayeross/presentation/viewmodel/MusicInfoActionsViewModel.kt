package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.offline.InfoUndo
import com.lostf1sh.pixelplayeross.data.offline.OfflineDownloads
import com.lostf1sh.pixelplayeross.data.repository.ArtistImageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

enum class MusicInfoPageAction { REFRESH, DELETE, UNDO }
enum class MusicInfoActionStatus { BUSY, SUCCESS, FAILURE }
data class MusicInfoActionResult(val action: MusicInfoPageAction, val status: MusicInfoActionStatus, val name: String, val undo: InfoUndo? = null)

@HiltViewModel
class MusicInfoActionsViewModel @Inject constructor(
    private val info: MusicInfoRepository,
    private val downloads: OfflineDownloads,
    private val artists: ArtistImageRepository,
) : ViewModel() {
    private val _result = MutableStateFlow<MusicInfoActionResult?>(null)
    val result = _result.asStateFlow()
    private var retryAction: (() -> Unit)? = null
    private var pendingDelete: MusicInfoActionResult? = null

    init {
        viewModelScope.launch {
            downloads.undoActions.collect { actions ->
                val pending = pendingDelete ?: return@collect
                val action = actions.firstOrNull { it.token == pending.undo?.token }
                if (action == null) pendingDelete = null
                else if (action.failed) {
                    _result.value = pending.copy(status = MusicInfoActionStatus.FAILURE)
                    retryAction = { run(MusicInfoPageAction.DELETE, pending.name, {}) { downloads.finishPendingDelete(action.token); null } }
                }
            }
        }
    }

    private fun run(kind: MusicInfoPageAction, name: String, done: () -> Unit, action: suspend () -> InfoUndo?) {
        retryAction = { run(kind, name, done, action) }
        viewModelScope.launch {
            _result.value = MusicInfoActionResult(kind, MusicInfoActionStatus.BUSY, name)
            try {
                val undo = action()
                done()
                _result.value = MusicInfoActionResult(kind, MusicInfoActionStatus.SUCCESS, name, undo)
                if (undo != null) pendingDelete = _result.value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Timber.tag("MusicInfo").w(failure, "Manual music info %s failed", kind)
                _result.value = MusicInfoActionResult(kind, MusicInfoActionStatus.FAILURE, name)
            }
        }
    }

    fun retry() { retryAction?.invoke() }
    fun clearResult() { _result.value = null }
    fun undo(token: String, name: String, done: () -> Unit) = run(MusicInfoPageAction.UNDO, name, done) {
        check(downloads.undo(token)) { "Undo window has closed" }; null
    }
    fun refreshArtist(name: String, songs: List<Song>, done: () -> Unit) = run(MusicInfoPageAction.REFRESH, name, done) {
        downloads.refreshArtistInfo(name, songs)
        artists.invalidateDownloaded(name)
        null
    }
    fun refreshAlbum(album: String, artist: String, songs: List<Song>, done: () -> Unit) = run(MusicInfoPageAction.REFRESH, album, done) {
        downloads.refreshAlbumInfo(album, artist, songs)
        songs.mapNotNull { it.id.toLongOrNull() }.forEach { info.downloadedCoverArrived(it) }
        null
    }
    fun deleteArtist(name: String, songs: List<Song>, done: () -> Unit) = run(MusicInfoPageAction.DELETE, name, done) {
        downloads.deleteArtistInfo(name, songs).also { artists.invalidateDownloaded(name) }
    }
    fun deleteAlbum(album: String, artist: String, songs: List<Song>, done: () -> Unit) = run(MusicInfoPageAction.DELETE, album, done) {
        downloads.deleteAlbumInfo(album, artist, songs)
    }
}
