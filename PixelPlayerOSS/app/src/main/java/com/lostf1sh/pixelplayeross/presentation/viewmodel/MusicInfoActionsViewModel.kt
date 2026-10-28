package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.offline.OfflineDownloads
import com.lostf1sh.pixelplayeross.data.repository.ArtistImageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class MusicInfoActionsViewModel @Inject constructor(
    private val info: MusicInfoRepository,
    private val downloads: OfflineDownloads,
    private val artists: ArtistImageRepository,
) : ViewModel() {
    private fun run(done: () -> Unit, action: suspend () -> Unit) { viewModelScope.launch {
        try { action(); done() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Timber.tag("MusicInfo").w(error, "Manual music info request failed") }
    } }
    fun refreshArtist(name: String, songs: List<Song>, done: () -> Unit) = run(done) {
        downloads.refreshArtistInfo(name, songs)
        artists.invalidateDownloaded(name)
    }
    fun refreshAlbum(album: String, artist: String, songs: List<Song>, done: () -> Unit) = run(done) {
        downloads.refreshAlbumInfo(album, artist, songs)
        songs.mapNotNull { it.id.toLongOrNull() }.forEach { info.downloadedCoverArrived(it) }
    }
    fun deleteArtist(name: String, songs: List<Song>, done: () -> Unit) = run(done) {
        downloads.deleteArtistInfo(name, songs); artists.invalidateDownloaded(name)
    }
    fun deleteAlbum(album: String, artist: String, songs: List<Song>, done: () -> Unit) = run(done) {
        downloads.deleteAlbumInfo(album, artist, songs)
    }
}
