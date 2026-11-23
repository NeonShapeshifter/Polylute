package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import androidx.core.net.toUri
import com.lostf1sh.pixelplayeross.data.model.GenreNames
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.di.AppScope
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.temporal.IsoFields
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber

internal data class GenrePictures(
    val covers: ImmutableList<String>,
    val featuredCandidates: List<GenreArtist>,
)

private val byAlbumName = compareBy<GenreAlbum> { it.title.lowercase() }
    .thenBy { it.artist.lowercase() }.thenBy { it.albumId }

internal suspend fun topGenreCovers(
    songs: List<Song>,
    listenedMs: (Song) -> Long,
    coverFor: suspend (GenreAlbum) -> String? = { it.artUri },
): ImmutableList<String> {
    val time = songs.groupingBy { it.albumId }.fold(0L) { sum, song -> sum + listenedMs(song) }
    val albums = GenreBrowse.albums(songs)
        .sortedWith(compareByDescending<GenreAlbum> { time.getValue(it.albumId) }
            .thenByDescending { if (time.getValue(it.albumId) == 0L) it.songCount else 0 }
            .then(byAlbumName))
    return buildList {
        for (album in albums) {
            coverFor(album)?.takeIf(String::isNotBlank)?.let(::add)
            if (size == 6) break
        }
    }.toImmutableList()
}

internal fun genreFeaturedCandidates(songs: List<Song>, listenedMs: (Song) -> Long): List<GenreArtist> {
    val time = songs.groupingBy { it.primaryArtist.id }.fold(0L) { sum, song -> sum + listenedMs(song) }
    return GenreBrowse.artists(songs)
        .filter { it.artistId > 0 && it.name.isNotBlank() }
        .sortedWith(compareByDescending<GenreArtist> { time.getValue(it.artistId) }
            .thenByDescending { if (time.getValue(it.artistId) == 0L) it.songCount else 0 }
            .thenBy { it.name.lowercase() }.thenBy { it.artistId })
        .take(5)
}

internal fun weeklyFeaturedArtist(candidates: List<GenreArtist>, date: LocalDate): GenreArtist? =
    if (candidates.isEmpty()) null else candidates[date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) % candidates.size]

/**
 * Shared by the library and genre pages. Library changes invalidate the snapshot immediately.
 * History changes are coalesced into one update per minute. Once requested, the result stays
 * in application memory so returning to a genre never starts a fresh ranking.
 */
@Singleton
@OptIn(FlowPreview::class)
class GenreImagery @Inject constructor(
    @ApplicationContext private val context: Context,
    music: MusicRepository,
    ledgers: LedgerProvider,
    library: LibraryChangeSignal,
    info: MusicInfoRepository,
    @AppScope scope: CoroutineScope,
) {
    private var previousLedger: ListeningLedger? = null
    private var previousCatalog: LibraryCatalog? = null
    private var previousCoverVersion: Int? = null
    private var albumArt = emptyMap<Long, String>()
    private val verifiedCovers = mutableMapOf<Long, String?>()

    internal val pictures = combine(
        library.version,
        ledgers.changes.sample(60_000).onStart { emit(0L) },
        info.coverVersion,
    ) { _, _, covers -> covers }.conflate().mapNotNull { coverVersion ->
        try {
            val ledger = ledgers.ledger()
            if (ledger === previousLedger && coverVersion == previousCoverVersion) return@mapNotNull null
            val catalog = ledger.catalog
            val coroutine = currentCoroutineContext()
            if (catalog !== previousCatalog || coverVersion != previousCoverVersion) {
                albumArt = music.getAllAlbumsOnce(minTracks = 0).mapNotNull { album ->
                    album.albumArtUriString?.let { album.id to it }
                }.toMap()
                verifiedCovers.clear()
                previousCatalog = catalog
                previousCoverVersion = coverVersion
            }
            val songs = GenreBrowse.distinctFiles(catalog.items.map { it.song })
            val byAlbum = songs.groupBy { it.albumId }
            val byGenre = mutableMapOf<String, MutableList<Song>>()
            val genreKeys = mutableMapOf<String?, List<String>>()
            for (song in songs) {
                val keys = genreKeys.getOrPut(song.genre) {
                    GenreNames.parts(song.genre).map(GenreNames::key).filter(String::isNotEmpty).distinct()
                        .ifEmpty { if (song.genre.isNullOrBlank()) listOf(GenreNames.UnknownKey) else emptyList() }
                }
                keys.forEach { byGenre.getOrPut(it, ::mutableListOf).add(song) }
            }
            val listenedMs: (Song) -> Long = { song ->
                ledger.stats(song.libraryItemId.ifBlank { LibraryCatalog.standInIdentity(song) })?.listenedMs ?: 0L
            }
            val result = mutableMapOf<String, GenrePictures>()
            for ((genre, tracks) in byGenre) {
                coroutine.ensureActive()
                result[genre] = GenrePictures(
                    covers = topGenreCovers(tracks, listenedMs) { album ->
                        coroutine.ensureActive()
                        if (album.albumId !in verifiedCovers) {
                            verifiedCovers[album.albumId] = resolveCover(album, byAlbum.getValue(album.albumId), info)
                        }
                        verifiedCovers[album.albumId]
                    },
                    featuredCandidates = genreFeaturedCandidates(tracks, listenedMs),
                )
            }
            previousLedger = ledger
            result.toMap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Could not refresh genre pictures")
            null
        }
    }.stateIn(scope, SharingStarted.Lazily, emptyMap())

    private suspend fun resolveCover(album: GenreAlbum, songs: List<Song>, info: MusicInfoRepository): String? {
        val paths = songs.associate { it.id.toLongOrNull() to it.path }
        val uris = (sequenceOf(albumArt[album.albumId]) + songs.asSequence().map { it.albumArtUriString })
            .filterNotNull().filter(String::isNotBlank).distinct()
        for (uri in uris) {
            if (LocalArtworkUri.isLocalArtworkUri(uri)) {
                val key = LocalArtworkUri.parseSongId(uri) ?: continue
                val sourcePath = paths[LocalArtworkUri.parseAlbumCoverSource(uri) ?: key]
                    ?: AlbumArtUtils.albumCoverSourcePath(context, uri)
                if (AlbumArtUtils.ensureAlbumArtThumbnailFile(context, key, sourcePath) != null) return uri
            } else {
                val parsed = uri.toUri()
                val local = parsed.scheme in listOf("file", "content", "android.resource") || uri.startsWith('/')
                if (local && runCatching { AlbumArtUtils.openArtworkInputStream(context, parsed)?.use { it.read() >= 0 } }.getOrNull() == true) {
                    return uri
                }
            }
        }
        return info.service.cachedCover(album.title, album.artist)?.absolutePath
    }
}
