package com.lostf1sh.pixelplayeross.data.worker

import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.metadata.MetadataNormalizer
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import com.lostf1sh.pixelplayeross.utils.normalizeMetadataText
import com.lostf1sh.pixelplayeross.utils.normalizeMetadataTextOrEmpty
import com.lostf1sh.pixelplayeross.utils.splitArtistsByDelimiters

internal data class AlbumGroupingKey(
    val normalizedTitle: String,
    val identity: String
)

internal fun resolveAlbumArtist(
    rawAlbumArtist: String?,
    metadataAlbumArtist: String?
): String? {
    return sequenceOf(metadataAlbumArtist, rawAlbumArtist)
        .mapNotNull { candidate ->
            candidate.normalizeMetadataText()
                ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
        }
        .firstOrNull()
}

/**
 * The grouping key for one song, from the shared normalizer.
 *
 * This file used to compute its own key: title plus an "identity" that fell back through album
 * artist, parent directory, artwork URI and finally the MediaStore album id. Three things were
 * wrong with that, and all three are the failures this phase exists to fix:
 *
 * * **The directory was a grouping input.** Every track in a flat `Music/` folder therefore shared
 *   one identity, so a whole library could collapse into a single album — and, in the opposite
 *   direction, a release stored as `Album/CD 1` and `Album/CD 2` split into two.
 * * **`<unknown>` was treated as a title.** `ifBlank { "Unknown Album" }` does not fire on it,
 *   because it is not blank, so untagged files grouped by whatever the directory test produced.
 * * **It could not see compilations at all.** The album artist is the only thing that keeps a
 *   compilation together, and it was one of several fallbacks rather than the primary input.
 *
 * The rules now live in `MetadataNormalizer`, where they are pure and testable, and this is a thin
 * adapter over them.
 */
internal fun buildAlbumGroupingKey(song: SongEntity): AlbumGroupingKey {
    val key = MetadataNormalizer.albumGroupingKey(
        albumTitle = song.albumName,
        albumArtist = song.albumArtist,
        parentDirectoryPath = song.parentDirectoryPath,
        compilation = song.isCompilation,
    )
    return AlbumGroupingKey(
        normalizedTitle = MetadataNormalizer.normalizeForMatching(song.albumName).orEmpty(),
        identity = key.value,
    )
}

internal fun buildAlbumGroupingKeys(album: AlbumEntity): List<AlbumGroupingKey> {
    val albumKeys = mutableListOf<AlbumGroupingKey>()
    if (album.groupingKey.isNotBlank()) albumKeys += AlbumGroupingKey(
        MetadataNormalizer.normalizeForMatching(album.title).orEmpty(), album.groupingKey
    )

    albumKeys += buildAlbumGroupingKey(
        albumName = album.title,
        albumArtist = album.artistName,
        albumArtUriString = album.albumArtUriString,
        parentDirectoryPath = null,
        fallbackAlbumId = album.id
    )

    albumKeys += AlbumGroupingKey(
        normalizedTitle = album.title.normalizeMetadataTextOrEmpty()
            .ifBlank { "Unknown Album" }
            .lowercase(),
        identity = "media:${album.id}"
    )

    if (!album.albumArtUriString.isNullOrBlank()) {
        albumKeys += AlbumGroupingKey(
            normalizedTitle = album.title.normalizeMetadataTextOrEmpty()
                .ifBlank { "Unknown Album" }
                .lowercase(),
            identity = "art:${album.albumArtUriString.trim()}"
        )
    }

    return albumKeys.distinct()
}

internal fun chooseAlbumDisplayArtist(
    songs: List<SongEntity>,
    preferAlbumArtist: Boolean
): String {
    if (songs.isEmpty()) return "Unknown Artist"

    val albumArtist = mostCommonValue(
        songs.mapNotNull { song ->
            song.albumArtist.normalizeMetadataText()?.takeIf { it.isNotBlank() }
        }
    )
    val trackArtist = mostCommonValue(
        songs.map { song ->
            song.artistName.normalizeMetadataTextOrEmpty()
        }
    )

    return when {
        preferAlbumArtist && albumArtist != null -> albumArtist
        trackArtist != null -> trackArtist
        albumArtist != null -> albumArtist
        else -> "Unknown Artist"
    }
}

internal fun resolveAlbumDisplayArtistId(
    displayArtist: String,
    songs: List<SongEntity>,
    artistNameToId: Map<String, Long>,
    artistDelimiters: List<String>,
    wordDelimiters: List<String> = emptyList()
): Long {
    artistNameToId[displayArtist.trim()]?.let { return it }

    val primaryArtistName = displayArtist
        .splitArtistsByDelimiters(artistDelimiters, wordDelimiters)
        .firstOrNull()
        ?.trim()
    if (!primaryArtistName.isNullOrEmpty()) {
        artistNameToId[primaryArtistName]?.let { return it }
    }

    return songs.firstOrNull()?.artistId ?: 0L
}

private fun mostCommonValue(values: List<String>): String? {
    return values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()
        .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
        ?.key
}

private fun buildAlbumGroupingKey(
    albumName: String,
    albumArtist: String?,
    albumArtUriString: String?,
    parentDirectoryPath: String?,
    fallbackAlbumId: Long,
    preferStableLocalIdentity: Boolean = false
): AlbumGroupingKey {
    val normalizedTitle = albumName.normalizeMetadataTextOrEmpty()
        .ifBlank { "Unknown Album" }
        .lowercase()
    val stableLocalIdentity = when {
        !parentDirectoryPath.isNullOrBlank() -> {
            "dir:${parentDirectoryPath.trim().lowercase()}"
        }
        else -> {
            "media:$fallbackAlbumId"
        }
    }
    val identity = when {
        !albumArtist.isNullOrBlank() -> {
            "artist:${albumArtist.normalizeMetadataTextOrEmpty().lowercase()}"
        }
        preferStableLocalIdentity -> {
            stableLocalIdentity
        }
        !albumArtUriString.isNullOrBlank() -> {
            "art:${albumArtUriString.trim()}"
        }
        !parentDirectoryPath.isNullOrBlank() -> {
            "dir:${parentDirectoryPath.trim().lowercase()}"
        }
        else -> {
            "media:$fallbackAlbumId"
        }
    }

    return AlbumGroupingKey(
        normalizedTitle = normalizedTitle,
        identity = identity
    )
}

/** Shared scan/editor projection. The identity fingerprint is deliberately outside this function. */
internal fun SongEntity.withAlbumGrouping(): SongEntity {
    val owner = resolveAlbumArtist(albumArtist, null)
    val compilation = MetadataNormalizer.isCompilation(null, owner, 0)
    val normalized = copy(albumArtist = owner, isCompilation = compilation)
    return normalized.copy(albumGroupingKey = buildAlbumGroupingKey(normalized).identity)
}

/** Keeps an existing group's id, and never reuses the id of a different occupied group. */
internal class AlbumIdAllocator(existing: List<AlbumEntity>) {
    private val ids = mutableMapOf<AlbumGroupingKey, Long>()
    private val occupied = existing.mapTo(mutableSetOf()) { it.id }
    init {
        existing.sortedBy { it.id }.forEach { album ->
            buildAlbumGroupingKeys(album).forEach { ids.putIfAbsent(it, album.id) }
        }
    }
    fun idFor(song: SongEntity): Long {
        val key = buildAlbumGroupingKey(song)
        return ids.getOrPut(key) {
            var id = song.albumId
            if (id in occupied) {
                id = MetadataNormalizer.stableHash(key.identity).toULong(16).and(Long.MAX_VALUE.toULong()).toLong()
                while (id in occupied) id = if (id == Long.MAX_VALUE) 1 else id + 1
            }
            occupied += id
            id
        }
    }
}

/** The album row produced by both a scan and a successful edit; no tag or artwork reads. */
internal fun albumProjection(
    songs: List<SongEntity>,
    preferAlbumArtist: Boolean,
    artistNameToId: Map<String, Long>,
    artistDelimiters: List<String>,
    wordDelimiters: List<String>,
    artwork: String?,
    year: Int = MetadataNormalizer.albumYear(songs.map { it.year }),
): AlbumEntity {
    require(songs.isNotEmpty())
    val first = songs.first()
    val displayArtist = chooseAlbumDisplayArtist(songs, preferAlbumArtist)
    return AlbumEntity(
        id = first.albumId, title = first.albumName,
        artistName = displayArtist,
        artistId = resolveAlbumDisplayArtistId(displayArtist, songs, artistNameToId, artistDelimiters, wordDelimiters),
        albumArtUriString = artwork, songCount = songs.size, dateAdded = first.dateAdded,
        year = year,
        albumArtist = songs.mapNotNull { it.albumArtist?.takeIf(String::isNotBlank) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
        groupingKey = first.albumGroupingKey, isCompilation = songs.any { it.isCompilation },
    )
}

/** MediaStore is a candidate hint; only discrepant past edits get a fresh tag read on sync. */
internal fun needsEditedAlbumRepair(song: SongEntity, mediaStoreAlbumArtist: String?): Boolean {
    if (!song.albumUserEdited) return false
    val reportedOwner = resolveAlbumArtist(mediaStoreAlbumArtist, null)
    return (reportedOwner != null && reportedOwner != resolveAlbumArtist(song.albumArtist, null)) ||
        song.albumGroupingKey != song.withAlbumGrouping().albumGroupingKey
}
