package com.lostf1sh.pixelplayeross.data.historyimport

import com.lostf1sh.pixelplayeross.data.stats.CatalogItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Compact, pre-sorted, read-only snapshot of all imported listening history,
 * matched against the current `LibraryCatalog`.
 *
 * Stored as parallel primitive arrays so 500,000+ plays occupy ~10 MB in memory
 * and binary-search / aggregate in tens of milliseconds inside [StatsEngine].
 */
class ImportedStatsTrack(
    val key: String,
    val title: String,
    val artistRaw: String,
    val artists: List<String>,
    val album: String,
    val matchedItem: CatalogItem?,
    /** Non-blank normalized album key when unmatched and album is known; prevents collision with numeric MediaStore albumIds. */
    val unmatchedAlbumKey: String?,
)

class ImportedStatsInput(
    val tracks: Array<ImportedStatsTrack>,
    val trackIndices: IntArray,
    /** UTC epoch millis, sorted ascending. */
    val timestampsMs: LongArray,
    val listenedMs: LongArray,
    val estimated: BooleanArray,
) {
    val size: Int get() = timestampsMs.size
    val isEmpty: Boolean get() = size == 0
    val firstMs: Long? get() = if (size == 0) null else timestampsMs[0]
    val lastMs: Long? get() = if (size == 0) null else timestampsMs[size - 1] + listenedMs[size - 1]

    companion object {
        val Empty = ImportedStatsInput(
            tracks = emptyArray(),
            trackIndices = IntArray(0),
            timestampsMs = LongArray(0),
            listenedMs = LongArray(0),
            estimated = BooleanArray(0),
        )
    }
}

/** Seam for Stats to observe and read imported history without coupling to storage. */
interface ImportedStatsProvider {
    val records: StateFlow<List<ImportRecord>>
    val changes: Flow<Long>
    suspend fun statsInput(catalog: com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog): ImportedStatsInput
}
