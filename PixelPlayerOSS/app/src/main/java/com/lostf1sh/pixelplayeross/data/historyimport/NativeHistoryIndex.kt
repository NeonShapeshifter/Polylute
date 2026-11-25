package com.lostf1sh.pixelplayeross.data.historyimport

import com.lostf1sh.pixelplayeross.data.stats.CatalogItem
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.data.stats.ListenTrackMetadata
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository.PlaybackEvent

/** Native play starts indexed once for a Polylute history import. */
internal class NativeHistoryIndex private constructor(
    private val startsByIdentity: Map<String, LongArray>,
    private val startsByMissingTrack: Map<ListenTrackMetadata, LongArray>,
) {
    fun contains(row: RawImportedRow, item: CatalogItem?): Boolean {
        val starts = if (item != null) {
            startsByIdentity[item.identity]
        } else {
            startsByMissingTrack[trackKey(row.title, row.artistRaw, row.album)]
        } ?: return false

        val lowerBound = row.startMs - TOLERANCE_MS
        val found = starts.binarySearch(lowerBound)
        val index = if (found >= 0) found else -found - 1
        return index < starts.size && starts[index] <= row.startMs + TOLERANCE_MS
    }

    companion object {
        private const val TOLERANCE_MS = 2_000L

        fun build(events: List<PlaybackEvent>, catalog: LibraryCatalog): NativeHistoryIndex {
            val byIdentity = HashMap<String, MutableList<Long>>()
            val byMissingTrack = HashMap<ListenTrackMetadata, MutableList<Long>>()
            for (occurrence in ListeningLedger.build(events, catalog).occurrences) {
                if (!occurrence.isPlay) continue
                val item = occurrence.item
                if (item != null) {
                    byIdentity.getOrPut(item.identity, ::ArrayList).add(occurrence.startMs)
                } else {
                    val names = occurrence.recordedTrackMetadata ?: continue
                    val key = trackKey(names.title, names.artist, names.album)
                    byMissingTrack.getOrPut(key, ::ArrayList).add(occurrence.startMs)
                }
            }
            return NativeHistoryIndex(byIdentity.sortedStarts(), byMissingTrack.sortedStarts())
        }

        private fun trackKey(title: String, artist: String?, album: String?) = ListenTrackMetadata(
            title = LibraryTrackMatcher.foldBase(title),
            artist = LibraryTrackMatcher.foldBase(artist.orEmpty()),
            album = LibraryTrackMatcher.foldBase(album.orEmpty()),
        )

        private fun <K> Map<K, List<Long>>.sortedStarts() =
            mapValues { (_, starts) -> starts.toLongArray().apply { sort() } }
    }
}
