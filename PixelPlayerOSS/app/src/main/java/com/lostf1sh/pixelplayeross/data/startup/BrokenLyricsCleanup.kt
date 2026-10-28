package com.lostf1sh.pixelplayeross.data.startup

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.LyricsDao
import com.lostf1sh.pixelplayeross.data.database.LyricsRepairRow
import com.lostf1sh.pixelplayeross.data.lyrics.LyricsTextPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class BrokenLyricsCleanup @Inject constructor(@ApplicationContext context: Context, private val songs: MusicDao, private val lyrics: LyricsDao) {
    private val marker = File(context.filesDir, "lyrics-repair-1.1.1.done")
    private val gate = Mutex()
    suspend fun runOnce() = withContext(Dispatchers.IO) { gate.withLock {
        if (marker.exists()) return@withLock
        suspend fun repair(page: suspend (Long, Int) -> List<LyricsRepairRow>, clear: suspend (Long) -> Unit) {
            var after = Long.MIN_VALUE
            while (true) {
                val rows = page(after, 64)
                if (rows.isEmpty()) break
                for (row in rows) {
                    val reason = when {
                        row.characters > LyricsTextPolicy.MAX_CHARACTERS || row.bytes > LyricsTextPolicy.MAX_BYTES -> "too long"
                        row.hasNul -> "binary NUL"
                        else -> LyricsTextPolicy.rejection(row.text.orEmpty())
                    }
                    if (reason != null) { clear(row.songId); LyricsTextPolicy.logDrop(row.songId, reason) }
                }
                after = rows.last().songId
            }
        }
        repair(songs::lyricsRepairPage, songs::resetLyrics)
        repair(lyrics::lyricsRepairPage, lyrics::deleteLyrics)
        marker.writeText("done")
    } }
}
