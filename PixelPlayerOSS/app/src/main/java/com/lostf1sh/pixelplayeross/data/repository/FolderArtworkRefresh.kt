package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemeProcessor
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.FolderArtworkSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import timber.log.Timber

class FolderArtworkRefresh @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
    private val preferences: UserPreferencesRepository,
    private val musicInfo: MusicInfoRepository,
    private val colors: ColorSchemeProcessor
) {
    suspend fun refresh() {
        val enabled = preferences.folderCoversEnabledFlow.first()
        val rules = preferences.currentDirectoryRules()
        val resolver = rules.resolver()
        val songs = musicDao.getAllSongsList().filter { !resolver.isBlocked(it.parentDirectoryPath) }
        val changedAlbums = mutableSetOf<Long>()
        val downloadedCovers = mutableMapOf<Pair<String, String>, Boolean>()
        for ((directory, members) in songs.groupBy { it.parentDirectoryPath }) {
            // Release a folder's bytes before moving to the next one, even in a very large library.
            val session = FolderArtworkSession(context)
            val eligible = AlbumArtUtils.isSingleAlbumFolder(directory, members.map { it.albumId to it.albumName })
            for (song in members) {
                currentCoroutineContext().ensureActive()
                try {
                    if (!AlbumArtUtils.needsFolderArtworkRefresh(context, song.id, song.albumArtUriString, eligible, enabled)) continue
                    val album = song.albumName to (song.albumArtist ?: song.artistName)
                    val downloaded = enabled && downloadedCovers.getOrPut(album) {
                        musicInfo.service.cachedCover(album.first, album.second) != null
                    }
                    val result = AlbumArtUtils.refreshFolderArtwork(
                        context, song.id, song.filePath, song.albumArtUriString, eligible, enabled, downloaded, session
                    )
                    if (result.changed) {
                        // Update the projection even if palette invalidation fails. The next UI read sees the new URI.
                        musicDao.updateSongAlbumArt(song.id, result.uri)
                        changedAlbums.add(song.albumId)
                        song.albumArtUriString?.let { colors.invalidateScheme(it) }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Timber.w(error, "Could not refresh folder artwork in %s for song %s", directory, song.id)
                }
            }
        }
        changedAlbums.chunked(400).forEach {
            try {
                musicDao.refreshAlbumArtwork(it)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Timber.w(error, "Could not refresh album artwork projections for %s", it)
            }
        }
    }
}
