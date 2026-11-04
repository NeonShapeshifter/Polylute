package com.lostf1sh.pixelplayeross.presentation.model

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@Parcelize
data class RecentlyPlayedSongUiModel(
    val song: Song,
    val lastPlayedTimestamp: Long
) : Parcelable

fun mapRecentlyPlayedSongs(
    playbackHistory: List<PlaybackStatsRepository.PlaybackHistoryEntry>,
    songs: List<Song>,
    range: StatsTimeRange? = null,
    nowMillis: Long = System.currentTimeMillis(),
    maxItems: Int = Int.MAX_VALUE,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<RecentlyPlayedSongUiModel> {
    if (maxItems <= 0 || playbackHistory.isEmpty() || songs.isEmpty()) return emptyList()

    val songById = songs.associateBy { it.id }
    val recentSongIds = collectRecentlyPlayedSongIds(
        playbackHistory = playbackHistory,
        range = range,
        nowMillis = nowMillis,
        maxItems = maxItems,
        zoneId = zoneId
    )

    if (recentSongIds.isEmpty()) return emptyList()

    val recentSongIdSet = recentSongIds.toHashSet()
    val (startBound, _) = range.resolveBounds(
        nowMillis = nowMillis.coerceAtLeast(0L),
        zoneId = zoneId
    )

    val seenSongIds = HashSet<String>()
    val deduped = ArrayList<RecentlyPlayedSongUiModel>(maxItems.coerceAtMost(playbackHistory.size))

    val sortedHistory = playbackHistory.sortedWith(
        compareByDescending<PlaybackStatsRepository.PlaybackHistoryEntry> { it.timestamp }
            .thenBy { it.songId }
    )

    for (entry in sortedHistory) {
        if (deduped.size >= maxItems) break
        val safeTimestamp = entry.timestamp.coerceIn(0L, nowMillis.coerceAtLeast(0L))
        if (startBound != null && safeTimestamp < startBound) continue
        if (!seenSongIds.add(entry.songId)) continue
        if (entry.songId !in recentSongIdSet) continue

        val song = songById[entry.songId] ?: continue
        deduped += RecentlyPlayedSongUiModel(
            song = song,
            lastPlayedTimestamp = safeTimestamp
        )
    }

    return deduped
}

fun collectRecentlyPlayedSongIds(
    playbackHistory: List<PlaybackStatsRepository.PlaybackHistoryEntry>,
    range: StatsTimeRange? = null,
    nowMillis: Long = System.currentTimeMillis(),
    maxItems: Int = Int.MAX_VALUE,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<String> {
    if (maxItems <= 0 || playbackHistory.isEmpty()) return emptyList()

    val (startBound, _) = range.resolveBounds(
        nowMillis = nowMillis.coerceAtLeast(0L),
        zoneId = zoneId
    )

    val seenSongIds = LinkedHashSet<String>()
    val sortedHistory = playbackHistory.sortedWith(
        compareByDescending<PlaybackStatsRepository.PlaybackHistoryEntry> { it.timestamp }
            .thenBy { it.songId }
    )

    for (entry in sortedHistory) {
        if (seenSongIds.size >= maxItems) break
        val safeTimestamp = entry.timestamp.coerceIn(0L, nowMillis.coerceAtLeast(0L))
        if (startBound != null && safeTimestamp < startBound) continue
        seenSongIds.add(entry.songId)
    }

    return seenSongIds.toList()
}

private fun StatsTimeRange?.resolveBounds(
    nowMillis: Long,
    zoneId: ZoneId
): Pair<Long?, Long> {
    val safeNow = nowMillis.coerceAtLeast(0L)
    val zonedNow = java.time.Instant.ofEpochMilli(safeNow).atZone(zoneId)

    return when (this) {
        StatsTimeRange.DAY -> {
            val start = zonedNow.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
            start to safeNow
        }
        StatsTimeRange.WEEK -> {
            val startOfWeek = zonedNow.toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            val start = startOfWeek.atStartOfDay(zoneId).toInstant().toEpochMilli()
            start to safeNow
        }
        StatsTimeRange.MONTH -> {
            val startOfMonth = zonedNow.toLocalDate().withDayOfMonth(1)
            val start = startOfMonth.atStartOfDay(zoneId).toInstant().toEpochMilli()
            start to safeNow
        }
        StatsTimeRange.YEAR -> {
            val startOfYear = zonedNow.toLocalDate().withDayOfYear(1)
            val start = startOfYear.atStartOfDay(zoneId).toInstant().toEpochMilli()
            start to safeNow
        }
        StatsTimeRange.ALL, null -> {
            null to safeNow
        }
    }
}

/** Same range boundaries for the full-ledger projection; no list-length cap. */
fun filterRecentListening(items: List<RecentlyPlayedSongUiModel>, range: StatsTimeRange, nowMillis: Long, zoneId: ZoneId): List<RecentlyPlayedSongUiModel> {
    val (start, _) = range.resolveBounds(nowMillis.coerceAtLeast(0), zoneId)
    return items.filter { start == null || it.lastPlayedTimestamp.coerceAtMost(nowMillis) >= start }
}
