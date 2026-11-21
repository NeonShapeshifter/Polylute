package com.lostf1sh.pixelplayeross.data.service

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionId
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionRepository
import com.lostf1sh.pixelplayeross.data.database.LocalPlaylistDao
import com.lostf1sh.pixelplayeross.data.model.*
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.provider.SharedArtworkContentProvider
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.presentation.collections.nameRes
import com.lostf1sh.pixelplayeross.presentation.utils.primaryText
import com.lostf1sh.pixelplayeross.presentation.utils.relevanceRank
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class AutoQueue(val items: List<MediaItem>, val startIndex: Int)

/** No restore/player dependency: browsing remains available while B62's restore is running. */
class AutoMediaCatalog @Inject constructor(@param:ApplicationContext private val context: Context,
    private val music: MusicRepository, private val playlists: LocalPlaylistDao,
    private val collections: AutoCollectionRepository, private val preferences: UserPreferencesRepository) {
    companion object {
        const val BROWSABLE_STYLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val PLAYABLE_STYLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
    internal fun root(): MediaItem = folder(AutoMediaId(AutoMediaId.ROOT), context.getString(R.string.app_name))
    private fun folder(id: AutoMediaId, title: String, artSong: Song? = null, artRaw: String? = null, playable: Boolean = false): MediaItem {
        val art = artRaw?.let { MediaItemBuilder.externalControllerArtworkUri(context, it) }
            ?.takeIf { it.scheme == "content" && it.authority == SharedArtworkContentProvider.authority(context.packageName) }
            ?: artSong?.id?.toLongOrNull()?.let { SharedArtworkContentProvider.buildSongUri(context, it) }
        val extras = Bundle().apply { putInt(BROWSABLE_STYLE, 2); putInt(PLAYABLE_STYLE, 1); putBoolean(SEARCH_SUPPORTED, true) }
        return MediaItem.Builder().setMediaId(id.toString()).setMediaMetadata(MediaMetadata.Builder().setTitle(title)
            .setIsBrowsable(true).setIsPlayable(playable).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .setArtworkUri(art).setExtras(extras).build()).build()
    }
    private fun song(parent: AutoMediaId, song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(parent.song(song.id).toString()).setMediaMetadata(MediaMetadata.Builder()
            .setTitle(song.title).setArtist(song.displayArtist).setAlbumTitle(song.album).setIsBrowsable(false).setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(song.id.toLongOrNull()?.let { SharedArtworkContentProvider.buildSongUri(context, it) }).build()).build()

    internal suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem>? = withContext(Dispatchers.IO) {
        val requested = AutoPage.of(page, pageSize) ?: return@withContext null
        val parent = AutoMediaId.parse(parentId)?.takeIf { it.songId == null } ?: return@withContext null
        val offset = (parent.page ?: 0).toLong() * (AutoPage.MAX_SIZE - 1) + requested.offset
        if (offset > Int.MAX_VALUE) return@withContext null
        val bounds = requested.copy(offset = offset.toInt())
        val items = when (parent.kind) {
            AutoMediaId.ROOT -> listOf("home" to R.string.auto_home, "playlists" to R.string.auto_playlists,
                "albums" to R.string.auto_albums, "artists" to R.string.auto_artists)
                .drop(bounds.offset).take(bounds.limit).map { (id, name) -> folder(AutoMediaId(id), context.getString(name)) }
            "home" -> {
                collections.ensureFresh()
                val all = collections.state.first { it.isReady }.visible.map { collection ->
                    folder(AutoMediaId("collection", collection.id.key), context.getString(collection.id.nameRes()), collection.items.firstOrNull()?.song, playable = true)
                } + listOf(folder(AutoMediaId("songs"), context.getString(R.string.auto_songs), playable = true),
                    folder(AutoMediaId("liked"), context.getString(R.string.auto_liked), playable = true),
                    folder(AutoMediaId("mix", "your_mix"), context.getString(R.string.home_your_mix_heading), playable = true))
                all.drop(bounds.offset).take(bounds.limit)
            }
            "albums" -> music.getAlbumsPage(bounds.limit, bounds.offset).map { album ->
                folder(AutoMediaId("album", album.id.toString()), album.title, artRaw = album.albumArtUriString, playable = true)
            }
            "artists" -> music.getArtistsPage(bounds.limit, bounds.offset).map { artist ->
                val representative = music.getAutoSourceSongsPage(null, artist.id, 1, 0).firstOrNull()
                folder(AutoMediaId("artist", artist.id.toString()), artist.name, representative, artist.effectiveImageUrl, true)
            }
            "playlists" -> playlists.getPlaylistsPage(bounds.limit, bounds.offset).map { playlist ->
                val representative = songs(AutoMediaId("playlist", playlist.id), AutoPage(1,0)).firstOrNull()
                folder(AutoMediaId("playlist", playlist.id), playlist.name, representative, playlist.coverImageUri, true)
            }
            "album", "artist", "playlist", "collection", "songs", "liked", "mix", "search" ->
                songs(parent, bounds).map { song(parent, it) }
            else -> null
        } ?: return@withContext null
        // Legacy Auto asks for Int.MAX_VALUE and cannot request API pages. Keep 99 rows plus a
        // stable next-page folder, so a 5,000-song library remains fully browsable in those hosts.
        if (pageSize > AutoPage.MAX_SIZE && page == 0 && items.size == AutoPage.MAX_SIZE) {
            items.take(AutoPage.MAX_SIZE - 1) + folder(parent.copy(page = (parent.page ?: 0) + 1), context.getString(R.string.auto_more))
        } else items
    }
    internal suspend fun item(mediaId: String): MediaItem? = withContext(Dispatchers.IO) {
        val id = AutoMediaId.parse(mediaId) ?: return@withContext null
        if (id.songId != null || id.kind == "song") {
            val row = music.getSong(id.songId ?: id.ref!!).first() ?: return@withContext null
            if (id.kind == "song") song(AutoMediaId("songs"), row).buildUpon().setMediaId(id.toString()).build()
            else song(id.parent(), row).buildUpon().setMediaId(id.toString()).build()
        } else when (id.kind) {
            AutoMediaId.ROOT -> root()
            "home", "albums", "artists", "playlists" -> folder(id, name(id))
            else -> if (source(id) == null) null else folder(id, name(id), songs(id, AutoPage(1,0)).firstOrNull(), playable = id.page == null)
        }
    }
    private suspend fun songs(parent: AutoMediaId, page: AutoPage): List<Song> = when (parent.kind) {
        "album" -> music.getAutoSourceSongsPage(parent.ref!!.toLong(), null, page.limit, page.offset)
        "artist" -> music.getAutoSourceSongsPage(null, parent.ref!!.toLong(), page.limit, page.offset)
        "songs" -> music.getSongsPage(page.limit, page.offset, librarySort(false), StorageFilter.ALL)
        "liked" -> music.getFavoriteSongsPage(page.limit, page.offset, librarySort(true), StorageFilter.ALL)
        "playlist" -> {
            val ids = playlists.getPlaylistLocatorsResolvedPage(parent.ref!!, page.limit, page.offset).map(Long::toString)
            val byId = if (ids.isEmpty()) emptyMap() else music.getSongsByIds(ids).first().associateBy { it.id }
            ids.mapNotNull(byId::get)
        }
        "collection" -> {
            collections.ensureFresh()
            val id = AutoCollectionId.fromKey(parent.ref)
            collections.state.first { it.isReady }.collections.firstOrNull { it.id == id }?.items.orEmpty().asSequence().drop(page.offset).take(page.limit).map { it.song }.toList()
        }
        "mix" -> {
            collections.ensureFresh()
            collections.homeMixes.first { it.isReady }.yours.asSequence().drop(page.offset).take(page.limit).map { it.song }.toList()
        }
        "search" -> searchSongs(parent.ref!!).drop(page.offset).take(page.limit)
        else -> emptyList()
    }
    private suspend fun librarySort(liked: Boolean): SortOption {
        val value = if (liked) preferences.likedSongsSortOptionFlow.first() else preferences.songsSortOptionFlow.first()
        return SortOption.fromStorageKey(value, if (liked) SortOption.LIKED else SortOption.SONGS,
            if (liked) SortOption.LikedSongTitleAZ else SortOption.SongTitleAZ)
    }
    private suspend fun searchSongs(query: String): List<Song> = music.searchAll(query.trim(), SearchFilterType.SONGS).first()
        .filterIsInstance<SearchResultItem.SongItem>()
        .sortedWith(compareBy<SearchResultItem.SongItem> { it.relevanceRank(query.trim()) }.thenBy { it.primaryText.lowercase() })
        .take(AutoPage.MAX_SIZE).map { it.song }
    internal suspend fun search(query: String, page: Int, pageSize: Int): List<MediaItem>? {
        if (query.isBlank()) return emptyList()
        return children(AutoMediaId("search", query.trim()).toString(), page, pageSize)
    }
    internal suspend fun expand(mediaId: String): AutoQueue? = withContext(Dispatchers.IO) {
        val id = AutoMediaId.parse(mediaId) ?: return@withContext null
        val parent = id.parent()
        val origin = if(id.kind == "song") null else PlaybackOrigin.of(source(parent) ?: return@withContext null, name(parent))
        val rows = if (id.kind == "song") listOfNotNull(music.getSong(id.ref!!).first())
        else if (id.kind == "playlist") buildList {
            playlists.getPlaylistLocatorsResolved(id.ref!!).chunked(AutoPage.MAX_SIZE).forEach { locators ->
                val ids = locators.map(Long::toString)
                val byId = music.getSongsByIds(ids).first().associateBy { it.id }
                addAll(ids.mapNotNull(byId::get))
            }
        } else buildList {
            var offset = 0
            while (true) {
                val page = songs(parent, AutoPage(AutoPage.MAX_SIZE, offset))
                addAll(page)
                if (page.size < AutoPage.MAX_SIZE) break
                offset += AutoPage.MAX_SIZE
                kotlinx.coroutines.yield()
            }
        }
        if (rows.isEmpty()) return@withContext null
        val index = if (id.songId == null) 0 else rows.indexOfFirst { it.id == id.songId }.takeIf { it >= 0 } ?: return@withContext null
        AutoQueue(withContext(Dispatchers.Default) { rows.map { PlaybackOrigin.attach(MediaItemBuilder.buildForExternalController(context, it), origin) } }, index)
    }
    private fun source(id: AutoMediaId): ListenSource? = when (id.kind) {
        "album" -> ListenSource(ListenSourceKind.ALBUM, id.ref)
        "artist" -> ListenSource(ListenSourceKind.ARTIST, id.ref)
        "playlist" -> ListenSource(ListenSourceKind.PLAYLIST, id.ref)
        "collection" -> AutoCollectionId.fromKey(id.ref)?.let { ListenSource(ListenSourceKind.COLLECTION, it.key) }
        "songs" -> ListenSource(ListenSourceKind.LIBRARY)
        "liked" -> ListenSource(ListenSourceKind.LIBRARY, "liked")
        "mix" -> id.ref?.takeIf { it == "your_mix" }?.let { ListenSource(ListenSourceKind.HOME_MIX, it) }
        "search" -> ListenSource(ListenSourceKind.SEARCH)
        else -> null
    }
    private suspend fun name(id: AutoMediaId): String = when(id.kind) {
        "album" -> music.getAlbumById(id.ref!!.toLong()).first()?.title.orEmpty()
        "artist" -> music.getArtistById(id.ref!!.toLong()).first()?.name.orEmpty()
        "playlist" -> playlists.getPlaylistById(id.ref!!)?.name.orEmpty()
        "collection" -> AutoCollectionId.fromKey(id.ref)?.let { context.getString(it.nameRes()) }.orEmpty()
        "mix" -> context.getString(R.string.home_your_mix_heading)
        else -> context.getString(when(id.kind) {
            "home" -> R.string.auto_home; "albums" -> R.string.auto_albums; "artists" -> R.string.auto_artists
            "playlists" -> R.string.auto_playlists; "liked" -> R.string.auto_liked; "search" -> R.string.auto_search
            else -> R.string.auto_songs
        })
    }
}
