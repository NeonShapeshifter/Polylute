package com.lostf1sh.pixelplayeross.data.collections.auto

import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.CatalogItem
import java.util.Locale

internal data class SuggestedAlbumResult(val albums: List<Album>, val sourceCounts: Map<String, Int>)

/** Daily selection from the local catalog. All play counts keep the ledger's Stats rule. */
internal object SuggestedAlbums {
    private const val NINETY_DAYS = 90L * 24 * 60 * 60 * 1000
    fun build(ledger: ListeningLedger, now: Long, epochDay: Long, onRepeat: Set<String>): SuggestedAlbumResult {
        val albumTracks = ledger.catalog.tracksByAlbum
        val cutoff = now - NINETY_DAYS
        val recent = ledger.occurrences.filter { it.isPlay && it.endMs.coerceAtMost(now) >= cutoff }
        val recentAlbums = recent.mapNotNullTo(hashSetOf()) { it.item?.albumKey }
        val artists = recent.flatMap { it.item?.artists.orEmpty() }.groupingBy { it.lowercase(Locale.ROOT) }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(20).mapTo(hashSetOf()) { it.key }
        val albums = albumTracks.values.map(::suggestedAlbum).filter { album ->
            albumTracks[album.id.toString()].orEmpty().count { it.identity in onRepeat } * 2 <= album.songCount
        }
        val chosen = linkedMapOf<Long, Pair<Album, String>>()
        val fallback = ledger.isEmpty
        fun source(album: Album): String? {
            val tracks = albumTracks[album.id.toString()].orEmpty()
            if (fallback) return "newest"
            val mostlyUnheard = tracks.count { (ledger.stats(it.identity)?.plays ?: 0) <= 1 } * 2 > tracks.size
            if (mostlyUnheard && tracks.any { track -> track.artists.any { it.lowercase(Locale.ROOT) in artists } }) return "familiar_artist"
            if (tracks.all { (ledger.stats(it.identity)?.plays ?: 0) == 0 }) return "never_played"
            if (album.id.toString() !in recentAlbums && tracks.any { (ledger.stats(it.identity)?.plays ?: 0) > 0 }) return "rediscover"
            return null
        }
        // First fill all three sources with albums of at least three tracks; short releases come last.
        for (short in listOf(false, true)) {
            for (kind in if(fallback) listOf("newest") else listOf("familiar_artist", "never_played", "rediscover")) {
                val candidates = albums.filter { (it.songCount < 3) == short && it.id !in chosen && source(it) == kind }
                val ordered = if(fallback) candidates.sortedWith(compareByDescending<Album> { it.dateAdded }.thenBy { it.id })
                    else candidates.sortedWith(compareBy<Album> { suggestionSeed("albums:$epochDay:${it.id}") }.thenBy { it.id })
                ordered.take((10-chosen.size).coerceAtLeast(0)).forEach { chosen[it.id] = it to kind }
            }
        }
        return SuggestedAlbumResult(chosen.values.map { it.first },chosen.values.groupingBy { it.second }.eachCount())
    }
}

/** Stable FNV seed, independent of process, locale, platform hash maps and wall-clock time. */
internal fun suggestionSeed(value: String): Long {
    var hash=0xcbf29ce484222325uL.toLong()
    value.forEach { hash=(hash xor it.code.toLong()) * 0x100000001b3L }
    return hash and Long.MAX_VALUE
}

/** Sample only the existing list. Sorting the sample preserves its order relative to the Suggestions page. */
internal fun <T> suggestionPreview(items: List<T>, seed: Long, previous: List<T> = emptyList()): List<T> {
    if(items.size <= 3) return items
    val indices=items.indices.shuffled(kotlin.random.Random(seed)).take(3).sorted().toMutableList()
    if(indices.map { items[it] } == previous) {
        indices[indices.lastIndex]=items.indices.first { it !in indices }
        indices.sort()
    }
    return indices.map { items[it] }
}

internal fun suggestedAlbum(tracks: List<CatalogItem>): Album {
    val song=tracks.first().song
    return Album(song.albumId,tracks.first().album,song.albumArtist?.takeIf { it.isNotBlank() } ?: tracks.first().artistLine,
        song.year,tracks.maxOf { it.song.dateAdded },tracks.firstNotNullOfOrNull { it.song.albumArtUriString },tracks.size,song.albumArtist)
}
