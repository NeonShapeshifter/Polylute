package com.lostf1sh.pixelplayeross.presentation.navigation

import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionId
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind

/** A title goes to the source page; a cover continues to use AlbumDetail directly. */
data class ListeningDestination(val route: String, val pattern: String)

suspend fun listeningDestination(source: ListenSource?, albumId: Long, songId: String,
    exists: suspend (ListenSource) -> Boolean): ListeningDestination {
    fun album() = if (albumId != 0L && albumId != -1L) ListeningDestination(Screen.AlbumDetail.createRoute(albumId), Screen.AlbumDetail.route)
        else ListeningDestination(Screen.LibraryLocate.createRoute(songId), Screen.LibraryLocate.route)
    if (source == null) return album()
    val ref = source.ref
    val page = when (source.kind) {
        ListenSourceKind.ALBUM -> ref?.toLongOrNull()?.let { ListeningDestination(Screen.AlbumDetail.createRoute(it), Screen.AlbumDetail.route) }
        ListenSourceKind.ARTIST -> ref?.toLongOrNull()?.let { ListeningDestination(Screen.ArtistDetail.createRoute(it), Screen.ArtistDetail.route) }
        ListenSourceKind.PLAYLIST -> ref?.takeIf { it.isNotBlank() }?.let { ListeningDestination(Screen.PlaylistDetail.createRoute(android.net.Uri.encode(it)), Screen.PlaylistDetail.route) }
        ListenSourceKind.COLLECTION -> when {
            AutoCollectionId.fromKey(ref) != null -> ListeningDestination(Screen.AutoCollection.createRoute(ref!!), Screen.AutoCollection.route)
            ref?.removePrefix("rule:")?.toLongOrNull() != null && ref.startsWith("rule:") -> ListeningDestination(Screen.SmartCollections.route, Screen.SmartCollections.route)
            else -> null
        }
        ListenSourceKind.GENRE -> ref?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("composer:")) ListeningDestination(Screen.ComposerDetail.createRoute(it.removePrefix("composer:")), Screen.ComposerDetail.route)
            else ListeningDestination(Screen.GenreDetail.createRoute(android.net.Uri.encode(it)), Screen.GenreDetail.route)
        }
        ListenSourceKind.LIBRARY -> ListeningDestination(Screen.LibraryLocate.createRoute(songId, ref == "liked"), Screen.LibraryLocate.route)
        ListenSourceKind.HOME_MIX -> when (ref) {
            "daily_mix" -> ListeningDestination(Screen.AutoCollection.createRoute("daily_mix"), Screen.AutoCollection.route)
            "your_mix" -> ListeningDestination(Screen.Suggestions.route, Screen.Suggestions.route)
            else -> null
        }
        ListenSourceKind.RECENT -> ListeningDestination(Screen.RecentlyPlayed.route, Screen.RecentlyPlayed.route)
        ListenSourceKind.TOP_LIST -> ListeningDestination(Screen.Stats.route, Screen.Stats.route)
        ListenSourceKind.SEARCH, ListenSourceKind.EXTERNAL_VIEW, ListenSourceKind.OTHER -> null
    } ?: return album()
    return if (exists(source)) page else album()
}
