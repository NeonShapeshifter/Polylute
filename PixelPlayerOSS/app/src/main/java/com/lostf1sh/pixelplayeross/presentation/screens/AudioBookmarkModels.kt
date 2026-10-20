package com.lostf1sh.pixelplayeross.presentation.screens

import com.lostf1sh.pixelplayeross.data.database.AudioBookmarkEntity
import com.lostf1sh.pixelplayeross.data.model.Song
import java.util.Locale
import kotlin.math.abs

data class AudioBookmarkFolder(
    val song: Song,
    val bookmarks: List<AudioBookmarkEntity>
) {
    val bookmarkCount: Int
        get() = bookmarks.size

    val latestBookmarkCreatedTime: Long
        get() = bookmarks.maxOfOrNull { it.createdTime } ?: 0L
}

data class BookmarkVisibleItemGeometry(
    val key: Any,
    val index: Int,
    val itemCenterPx: Int,
    val itemSizePx: Int = 0,
    val itemEndPx: Int = itemCenterPx + itemSizePx / 2
)

fun bookmarkFocusedItemKey(
    visibleItems: List<BookmarkVisibleItemGeometry>,
    totalItemsCount: Int,
    viewportCenterPx: Int,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    currentFocusedKey: Any? = null,
    switchThresholdPx: Int = 0,
    lastFocusableIndex: Int? = null,
    viewportEndPx: Int? = null,
    endEdgeFocusThresholdPx: Int = 0
): Any? {
    if (visibleItems.isEmpty()) return null

    if (!canScrollBackward) {
        return visibleItems.minByOrNull { it.index }?.key
    }

    if (lastFocusableIndex != null && viewportEndPx != null && endEdgeFocusThresholdPx > 0) {
        visibleItems.firstOrNull { it.index == lastFocusableIndex }?.let { lastFocusableItem ->
            if (viewportEndPx - lastFocusableItem.itemEndPx >= endEdgeFocusThresholdPx) {
                return lastFocusableItem.key
            }
        }
    }

    if (!canScrollForward) {
        val lastItemIndex = totalItemsCount - 1
        visibleItems.firstOrNull { it.index == lastItemIndex }?.let { return it.key }
        return visibleItems.maxByOrNull { it.index }?.key
    }

    val closestItem = visibleItems.minWithOrNull(
        compareBy<BookmarkVisibleItemGeometry> { abs(it.itemCenterPx - viewportCenterPx) }
            .thenBy { it.index }
    ) ?: return null
    val currentItem = visibleItems.firstOrNull { it.key == currentFocusedKey }

    if (currentItem != null && currentItem.key != closestItem.key) {
        val currentDistance = abs(currentItem.itemCenterPx - viewportCenterPx)
        val closestDistance = abs(closestItem.itemCenterPx - viewportCenterPx)
        if (currentDistance <= closestDistance + switchThresholdPx.coerceAtLeast(0)) {
            return currentItem.key
        }
        return adjacentFocusCandidate(
            visibleItems = visibleItems,
            currentItem = currentItem,
            closestItem = closestItem
        ).key
    }

    return closestItem.key
}

private fun adjacentFocusCandidate(
    visibleItems: List<BookmarkVisibleItemGeometry>,
    currentItem: BookmarkVisibleItemGeometry,
    closestItem: BookmarkVisibleItemGeometry
): BookmarkVisibleItemGeometry {
    val direction = (closestItem.index - currentItem.index).coerceIn(-1, 1)
    if (direction == 0) return closestItem

    val targetIndex = currentItem.index + direction
    return visibleItems.firstOrNull { it.index == targetIndex }
        ?: visibleItems
            .filter { (it.index - currentItem.index) * direction > 0 }
            .minByOrNull { abs(it.index - targetIndex) }
        ?: closestItem
}

fun bookmarkStableFocusGeometries(
    visibleItems: List<BookmarkVisibleItemGeometry>,
    collapsedItemSizePx: Int
): List<BookmarkVisibleItemGeometry> {
    if (collapsedItemSizePx <= 0) return visibleItems

    var expandedHeightBeforePx = 0
    return visibleItems
        .sortedBy { it.index }
        .map { item ->
            val expandedHeightPx = (item.itemSizePx - collapsedItemSizePx).coerceAtLeast(0)
            val stableCenterPx = item.itemCenterPx - expandedHeightBeforePx - expandedHeightPx / 2
            expandedHeightBeforePx += expandedHeightPx
            item.copy(itemCenterPx = stableCenterPx)
        }
}

fun buildAudioBookmarkFolders(
    bookmarks: List<AudioBookmarkEntity>,
    songs: List<Song>,
    query: String
): List<AudioBookmarkFolder> {
    if (bookmarks.isEmpty() || songs.isEmpty()) return emptyList()

    val normalizedQuery = query.trim().lowercase(Locale.getDefault())
    val bookmarksBySongId = bookmarks
        .groupBy { it.songId }
        .mapValues { (_, values) -> values.sortedByDescending { it.createdTime } }

    return songs
        .mapNotNull { song ->
            val songBookmarks = bookmarksBySongId[song.id].orEmpty()
            if (songBookmarks.isEmpty()) {
                null
            } else {
                AudioBookmarkFolder(song, songBookmarks)
            }
        }
        .filter { folder ->
            normalizedQuery.isEmpty() || folder.matchesQuery(normalizedQuery)
        }
        .sortedWith(
            compareByDescending<AudioBookmarkFolder> { it.bookmarkCount }
                .thenByDescending { it.latestBookmarkCreatedTime }
                .thenBy { it.song.title.lowercase(Locale.getDefault()) }
        )
}

fun AudioBookmarkFolder.matchesQuery(normalizedQuery: String): Boolean {
    val song = song
    return listOfNotNull(
        song.title,
        song.displayArtist,
        song.album,
        song.genre
    ).any { it.contains(normalizedQuery, ignoreCase = true) } ||
        bookmarks.any { bookmark ->
            bookmark.title.contains(normalizedQuery, ignoreCase = true) ||
                bookmark.songTitle.contains(normalizedQuery, ignoreCase = true) ||
                bookmark.artistName.contains(normalizedQuery, ignoreCase = true) ||
                bookmarkCueSearchLabels(bookmark.timestampMs).any { cueLabel ->
                    cueLabel.contains(normalizedQuery, ignoreCase = true)
                }
        }
}

private fun bookmarkCueSearchLabels(timestampMs: Long): List<String> {
    val cueTime = formatBookmarkCueTime(timestampMs)
    val compactCueTime = cueTime.removePrefix("0")
    return listOf(
        cueTime,
        compactCueTime,
        "cue $cueTime",
        "cue $compactCueTime"
    )
}

private fun formatBookmarkCueTime(timestampMs: Long): String {
    val totalSeconds = timestampMs.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
