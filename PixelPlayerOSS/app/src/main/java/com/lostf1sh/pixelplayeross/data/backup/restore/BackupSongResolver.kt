package com.lostf1sh.pixelplayeross.data.backup.restore

import com.google.gson.annotations.SerializedName
import com.lostf1sh.pixelplayeross.data.database.SongSummary
import com.lostf1sh.pixelplayeross.data.historyimport.LibraryTrackMatcher
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.CatalogItem
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.utils.TextFold
import java.util.Locale

enum class BackupRestoreMode {
    REPLACE,
    MERGE,
}

object EmptyCatalogSource : CatalogSource {
    override suspend fun load(): LibraryCatalog = LibraryCatalog.Empty
}

data class PortableSongKeys(
    @SerializedName("title")
    val title: String? = null,
    @SerializedName("artist")
    val artist: String? = null,
    @SerializedName("album")
    val album: String? = null,
    @SerializedName(value = "duration_ms", alternate = ["durationMs", "duration"])
    val durationMs: Long? = null,
    @SerializedName("path")
    val path: String? = null,
) {
    fun hasAny(): Boolean =
        listOfNotNull(title, artist, album, path).any { it.isNotBlank() } ||
            (durationMs != null && durationMs > 0L)
}

enum class SongResolutionWay {
    BY_ID,
    BY_KEYS,
    UNRESOLVED,
}

data class BackupResolutionCounts(
    val resolvedById: Int = 0,
    val resolvedByKeys: Int = 0,
    val unresolved: Int = 0,
) {
    operator fun plus(other: BackupResolutionCounts): BackupResolutionCounts =
        BackupResolutionCounts(
            resolvedById = resolvedById + other.resolvedById,
            resolvedByKeys = resolvedByKeys + other.resolvedByKeys,
            unresolved = unresolved + other.unresolved,
        )

    val total: Int get() = resolvedById + resolvedByKeys + unresolved
    val resolvedTotal: Int get() = resolvedById + resolvedByKeys
}

data class ResolvedSongRef(
    val songId: String?,
    val libraryItemId: String?,
    val way: SongResolutionWay,
)

internal class BackupSongCatalogResolver(
    private val catalog: LibraryCatalog,
) {
    private val matcher: LibraryTrackMatcher = LibraryTrackMatcher.build(catalog)
    private val byPath: Map<String, CatalogItem>
    private val uniqueByMusicSuffix: Map<String, CatalogItem>

    init {
        val exactPath = HashMap<String, CatalogItem>(catalog.items.size * 2)
        val suffixGroups = HashMap<String, MutableList<CatalogItem>>(catalog.items.size)
        for (item in catalog.items) {
            val normPath = normalizePath(item.song.path)
            if (normPath.isNotEmpty()) {
                exactPath.putIfAbsent(normPath, item)
                musicRelativeSuffix(normPath)?.let { suffix ->
                    suffixGroups.getOrPut(suffix) { ArrayList(1) }.add(item)
                }
            }
        }
        byPath = exactPath
        uniqueByMusicSuffix = suffixGroups
            .filterValues { it.size == 1 }
            .mapValues { (_, list) -> list[0] }
    }

    fun resolve(
        backupSongId: String?,
        backupLibraryItemId: String?,
        keys: PortableSongKeys?,
    ): ResolvedSongRef {
        val id = backupSongId?.trim().orEmpty()
        val libId = backupLibraryItemId?.trim().orEmpty()

        // Step 1: Resolve by songId first (verify against portable keys when present so reused MediaStore IDs don't mismatch).
        if (id.isNotEmpty()) {
            val direct = catalog.itemAtLocator(id)
            if (direct != null) {
                val verified = keys == null || !keys.hasAny() || directMatchesKeys(direct, libId, keys)
                if (verified) {
                    return ResolvedSongRef(
                        songId = direct.locator,
                        libraryItemId = durableLibraryItemId(direct),
                        way = SongResolutionWay.BY_ID,
                    )
                }
            }
        }

        // Step 2: Portable keys matching (exact/normalized path first, then LibraryTrackMatcher).
        if (keys != null && keys.hasAny()) {
            val rawPath = keys.path?.trim().orEmpty()
            if (rawPath.isNotEmpty()) {
                val normPath = normalizePath(rawPath)
                val pathMatch = byPath[normPath] ?: musicRelativeSuffix(normPath)?.let(uniqueByMusicSuffix::get)
                if (pathMatch != null) {
                    return ResolvedSongRef(
                        songId = pathMatch.locator,
                        libraryItemId = durableLibraryItemId(pathMatch),
                        way = SongResolutionWay.BY_KEYS,
                    )
                }
            }

            val artist = keys.artist?.trim().orEmpty()
            val title = keys.title?.trim().orEmpty()
            if (artist.isNotEmpty() && title.isNotEmpty()) {
                val (_, matched) = matcher.match(
                    artistRaw = artist,
                    titleRaw = title,
                    albumRaw = keys.album?.trim()?.takeIf { it.isNotEmpty() },
                    durationHintMs = keys.durationMs?.takeIf { it > 0L },
                )
                if (matched != null) {
                    return ResolvedSongRef(
                        songId = matched.locator,
                        libraryItemId = durableLibraryItemId(matched),
                        way = SongResolutionWay.BY_KEYS,
                    )
                }
            }
        }

        return ResolvedSongRef(
            songId = null,
            libraryItemId = null,
            way = SongResolutionWay.UNRESOLVED,
        )
    }

    private fun directMatchesKeys(
        item: CatalogItem,
        backupLibId: String,
        keys: PortableSongKeys,
    ): Boolean {
        val normBackupPath = keys.path?.let(::normalizePath).orEmpty()
        if (normBackupPath.isNotEmpty() && normBackupPath == normalizePath(item.song.path)) {
            return true
        }
        val keyTitle = keys.title?.let(TextFold::fold).orEmpty()
        val keyArtist = keys.artist?.let(TextFold::fold).orEmpty()
        if (keyTitle.isNotEmpty() && keyArtist.isNotEmpty()) {
            val itemTitle = TextFold.fold(item.title)
            val itemArtist = TextFold.fold(item.artistLine.ifBlank { item.song.artist })
            if (keyTitle == itemTitle && (keyArtist == itemArtist || TextFold.fold(item.song.artist) == keyArtist)) {
                return true
            }
            return false
        }
        if (backupLibId.isNotEmpty() && item.identity == backupLibId) {
            return true
        }
        return false
    }

    companion object {
        fun keysFor(item: CatalogItem?): PortableSongKeys? {
            item ?: return null
            return PortableSongKeys(
                title = item.song.title.ifBlank { item.title },
                artist = item.song.artist.ifBlank { item.artistLine },
                album = item.song.album.ifBlank { item.album },
                durationMs = item.song.duration.takeIf { it > 0L },
                path = item.song.path.takeIf { it.isNotBlank() },
            )
        }

        suspend fun loadIfAvailable(catalogSource: CatalogSource?): BackupSongCatalogResolver? {
            catalogSource ?: return null
            val catalog = runCatching { catalogSource.load() }.getOrNull() ?: return null
            if (catalog.isEmpty) return null
            return BackupSongCatalogResolver(catalog)
        }

        fun catalogFromSummaries(summaries: List<SongSummary>): LibraryCatalog {
            val songs = summaries.map { s ->
                Song(
                    id = s.id.toString(),
                    title = s.title,
                    artist = s.artistName,
                    artistId = 0L,
                    album = s.albumName,
                    albumId = 0L,
                    path = "",
                    contentUriString = "",
                    albumArtUriString = null,
                    duration = s.duration,
                    dateModified = 0L,
                    dateAdded = 0L,
                    mimeType = null,
                    bitrate = null,
                    sampleRate = null,
                    year = 0,
                    trackNumber = 0,
                    libraryItemId = "",
                )
            }
            return LibraryCatalog.of(songs, emptyMap())
        }

        private fun durableLibraryItemId(item: CatalogItem): String? =
            item.identity.takeIf { it.isNotBlank() && !it.startsWith("locator:") }

        private fun normalizePath(raw: String): String =
            raw.trim().replace('\\', '/').lowercase(Locale.ROOT)

        private fun musicRelativeSuffix(normalizedPath: String): String? {
            val idx = normalizedPath.indexOf("/music/")
            return if (idx >= 0) normalizedPath.substring(idx + "/music/".length).takeIf { it.isNotBlank() } else null
        }
    }
}
