package com.lostf1sh.pixelplayeross.data.stats

import com.lostf1sh.pixelplayeross.data.database.LibraryItemDao
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One playable track of the library as Stats and the automatic collections see it: the song, under the durable identity the
 * history is keyed by.
 *
 * [identity] is the library item id. A song row that has none yet (it predates identities and has not been rescanned) is given
 * a stand-in that cannot collide with one, so it still groups with itself; no recorded event can name it by identity, and the
 * locator is the only way a legacy event reaches it.
 */
class CatalogItem(
    val identity: String,
    val song: Song,
    /** When the library first saw this item (durable; not the MediaStore "date added", which a re-copy resets). */
    val firstSeenAtMs: Long,
) {
    /** The MediaStore locator the player queues it under. Not an identity. */
    val locator: String get() = song.id

    val lengthMs: Long get() = song.duration

    /** What the row shows: the title, or the file's name when the tags have none. */
    val title: String = song.title.takeIf { it.isNotBlank() } ?: song.path.substringAfterLast('/')

    /** Every artist credited, primary first. Empty when the song names none (shown as "unknown artist"). */
    val artists: List<String> = run {
        val separated = song.artists
            .sortedByDescending { it.isPrimary }
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
        separated.ifEmpty { listOf(song.displayArtist.trim()).filter { it.isNotBlank() } }
    }

    /** The artists as one line. */
    val artistLine: String get() = artists.joinToString(", ")

    /** Albums are grouped as the Album screen groups them, by the album id. */
    val albumKey: String get() = song.albumId.toString()

    val album: String get() = song.album.trim()

    val genre: String get() = song.genre?.trim().orEmpty()
}

/**
 * The library the history is read against: the tracks that can be played now, by identity and by locator, plus the identities
 * the library knows without being able to play them (a card that is out, a file that is gone).
 *
 * It is a snapshot: it does not change under a reader. When the library changes (a sync, an edit), a new one is built and the
 * ledger over it is built again, which is what keeps Stats from showing the library it saw an hour ago.
 */
class LibraryCatalog(
    val items: List<CatalogItem>,
    private val knownWithoutFile: Set<String> = emptySet(),
) {
    private val byIdentity: Map<String, CatalogItem> = items.associateByTo(HashMap(items.size * 2)) { it.identity }
    private val byLocator: Map<String, CatalogItem> = items.associateByTo(HashMap(items.size * 2)) { it.locator }

    fun item(identity: String): CatalogItem? = byIdentity[identity]

    fun itemAtLocator(locator: String): CatalogItem? = byLocator[locator]

    /** True for an identity the library holds but has no playable file for now. */
    fun isKnownWithoutFile(identity: String): Boolean = identity in knownWithoutFile

    val isEmpty: Boolean get() = items.isEmpty()

    /** The tracks of each album that has a name, in the order the album plays (disc, then track, then title). */
    val tracksByAlbum: Map<String, List<CatalogItem>> by lazy {
        items.filter { it.album.isNotBlank() }
            .groupBy { it.albumKey }
            .mapValues { (_, tracks) ->
                tracks.sortedWith(
                    compareBy<CatalogItem>({ it.song.discNumber ?: 0 }, { it.song.trackNumber }, { it.title.lowercase(Locale.ROOT) }),
                )
            }
    }

    companion object {
        val Empty = LibraryCatalog(emptyList())

        /** The identity of a song row that has none, which no event can carry. */
        fun standInIdentity(song: Song): String = "locator:${song.id}"

        fun of(songs: List<Song>, firstSeenAtMs: Map<String, Long>, knownWithoutFile: Set<String> = emptySet()): LibraryCatalog =
            LibraryCatalog(
                items = songs.map { song ->
                    val identity = song.libraryItemId.takeIf { it.isNotBlank() } ?: standInIdentity(song)
                    CatalogItem(identity, song, firstSeenAtMs[identity] ?: 0L)
                },
                knownWithoutFile = knownWithoutFile,
            )
    }
}

/** Reads the library for Stats. A seam, so the model is tested over a catalog built by hand. */
interface CatalogSource {
    suspend fun load(): LibraryCatalog
}

@Singleton
class RoomCatalogSource @Inject constructor(
    private val musicRepository: MusicRepository,
    private val libraryItemDao: LibraryItemDao,
) : CatalogSource {
    override suspend fun load(): LibraryCatalog {
        val songs = musicRepository.getAllSongsOnce()
        val rows = libraryItemDao.getAll()
        val firstSeen = HashMap<String, Long>(rows.size * 2)
        val withoutFile = HashSet<String>()
        val present = songs.mapTo(HashSet(songs.size * 2)) { it.libraryItemId }
        for (row in rows) {
            firstSeen[row.libraryItemId] = row.firstSeenAt
            if (row.libraryItemId !in present) withoutFile += row.libraryItemId
        }
        return LibraryCatalog.of(songs, firstSeen, withoutFile)
    }
}
