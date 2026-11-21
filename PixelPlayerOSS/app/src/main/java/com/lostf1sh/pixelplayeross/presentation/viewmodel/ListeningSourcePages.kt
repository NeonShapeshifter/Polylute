package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionRepository
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionId
import com.lostf1sh.pixelplayeross.data.database.LocalPlaylistDao
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import javax.inject.Inject
import kotlinx.coroutines.flow.first

class ListeningSourcePages @Inject constructor(private val music: MusicRepository,
    private val playlists: LocalPlaylistDao, private val rules: SmartCollectionRepository) {
    private fun hasFolder(folders: List<com.lostf1sh.pixelplayeross.data.model.MusicFolder>, path: String): Boolean =
        folders.any { it.path == path || hasFolder(it.subFolders, path) }

    suspend fun exists(source: ListenSource): Boolean = when (source.kind) {
        ListenSourceKind.ALBUM -> source.ref?.toLongOrNull()?.let { music.getAlbumById(it).first() != null } ?: false
        ListenSourceKind.ARTIST -> source.ref?.toLongOrNull()?.let { music.getArtistById(it).first() != null } ?: false
        ListenSourceKind.PLAYLIST -> source.ref?.let { ref ->
            if (ref.startsWith(PlaylistViewModel.FOLDER_PLAYLIST_PREFIX)) {
                val path = android.net.Uri.decode(ref.removePrefix(PlaylistViewModel.FOLDER_PLAYLIST_PREFIX))
                hasFolder(music.getMusicFolders().first(), path)
            } else playlists.getPlaylistById(ref) != null
        } ?: false
        ListenSourceKind.COLLECTION -> AutoCollectionId.fromKey(source.ref) != null ||
            (source.ref?.takeIf { it.startsWith("rule:") }?.removePrefix("rule:")?.toLongOrNull()?.let { rules.getById(it) != null } ?: false)
        ListenSourceKind.GENRE -> source.ref?.startsWith("composer:") == true || source.ref?.let { com.lostf1sh.pixelplayeross.data.model.Genre.resolve(music.getGenres().first(), java.net.URLDecoder.decode(it, "UTF-8")) != null } == true
        else -> true
    }
}
