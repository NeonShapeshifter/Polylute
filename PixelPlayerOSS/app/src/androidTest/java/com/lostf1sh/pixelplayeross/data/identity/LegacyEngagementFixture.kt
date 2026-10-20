package com.lostf1sh.pixelplayeross.data.identity

import com.lostf1sh.pixelplayeross.data.database.EngagementDao
import com.lostf1sh.pixelplayeross.data.database.SongEngagementEntity

/**
 * A row of the legacy `song_engagements` table, written the way the app wrote it before B28 stopped writing it.
 *
 * Nothing in the app writes this table any more (the counters are derived from the listening history), but it is still restored from a
 * backup and still follows a track's identity, which is what the tests that use this check. Adds to an existing row like the old
 * statement did.
 */
internal suspend fun EngagementDao.seedLegacy(
    songId: String,
    libraryItemId: String,
    plays: Int,
    completions: Int,
    skips: Int,
    durationMs: Long,
    timestamp: Long,
) {
    val before = getEngagement(songId)
    upsertEngagements(
        listOf(
            SongEngagementEntity(
                songId = songId,
                libraryItemId = libraryItemId.ifEmpty { before?.libraryItemId.orEmpty() },
                playCount = (before?.playCount ?: 0) + plays,
                totalPlayDurationMs = (before?.totalPlayDurationMs ?: 0L) + durationMs,
                completionCount = (before?.completionCount ?: 0) + completions,
                skipCount = (before?.skipCount ?: 0) + skips,
                firstPlayedAt = before?.firstPlayedAt?.takeIf { it != 0L } ?: timestamp,
                lastCompletedAt = if (completions > 0) timestamp else before?.lastCompletedAt ?: 0L,
                lastSkippedAt = if (skips > 0) timestamp else before?.lastSkippedAt ?: 0L,
                lastPlayedTimestamp = timestamp,
            ),
        ),
    )
}
