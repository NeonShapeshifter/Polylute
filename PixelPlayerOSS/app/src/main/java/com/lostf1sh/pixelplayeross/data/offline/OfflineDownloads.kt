package com.lostf1sh.pixelplayeross.data.offline

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.NetworkType
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.DownloadKeys
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lostf1sh.pixelplayeross.data.network.LookupFailures
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Unmetered mobile service is not Wi-Fi. Both constraints are intentional. */
internal fun offlineNetworkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkRequest(NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        .build(), NetworkType.UNMETERED)
    .build()

enum class OfflineKind { LYRICS, ARTIST_PHOTOS, COVERS, DESCRIPTIONS }
data class OfflineTarget(
    val kind: OfflineKind,
    val title: String,
    val songId: String? = null,
    val albumArtist: String? = null,
    val album: Boolean = false,
)

/** Existing artist/album page actions share the new queue's deletion decisions. */
@Singleton
class OfflineDownloads @Inject constructor(
    private val info: MusicInfoRepository,
    private val lyrics: LyricsRepository,
    private val downloads: MusicInfoDownloads,
) {
    val undoActions get() = downloads.dao.undoActions()
    suspend fun finishPendingDelete(token: String) = downloads.retryDelete(token)
    suspend fun deleteArtistInfo(name: String, songs: List<Song>) = withContext(Dispatchers.IO) {
        downloads.open(songs.map { it.id }, artistFilter = name)
        downloads.deleteKeys(DownloadKeys.artist(name) + songs.map(DownloadKeys::lyrics))
    }

    suspend fun deleteAlbumInfo(album: String, artist: String, songs: List<Song>) = withContext(Dispatchers.IO) {
        val keys = DownloadKeys.album(album, artist) + songs.map(DownloadKeys::lyrics)
        downloads.open(songs.map { it.id })
        downloads.deleteKeys(keys)
    }

    suspend fun undo(token: String): Boolean = downloads.undo(token)

    suspend fun refreshArtistInfo(name: String, songs: List<Song>) = withContext(Dispatchers.IO) {
        downloads.allowRequested(DownloadKeys.artist(name) + songs.map(DownloadKeys::lyrics))
        lyrics.allowDownloads(songs)
        val failures = LookupFailures()
        val result = withContext(failures) { info.refreshArtistInfo(name) }
        if (failures.failed) throw IOException("Could not refresh artist information")
        result
    }

    suspend fun refreshAlbumInfo(album: String, artist: String, songs: List<Song>) = withContext(Dispatchers.IO) {
        val artists = songs.map { it.albumArtist?.takeIf(String::isNotBlank) ?: it.artist } + artist
        downloads.allowRequested(artists.flatMap { DownloadKeys.album(album, it) } + songs.map(DownloadKeys::lyrics))
        lyrics.allowDownloads(songs)
        val failures = LookupFailures()
        val result = withContext(failures) { info.service.refreshAlbum(album, artist) }
        if (failures.failed) throw IOException("Could not refresh album information")
        result
    }
}
