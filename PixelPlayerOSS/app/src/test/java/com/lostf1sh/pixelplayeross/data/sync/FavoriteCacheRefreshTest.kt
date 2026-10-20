package com.lostf1sh.pixelplayeross.data.sync

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * R3-1 / AUD-109: `songs.is_favorite` is a cache of the `favorites` table. The scanner always writes
 * `isFavorite = false`, and the favourites triggers only fire on writes to `favorites`, so a rebuild
 * (R3-1) or any incremental sync that rewrites an existing song (AUD-109) cleared the cache while the
 * favourite itself survived. Both transactions must re-derive the cache before they commit.
 *
 * Source-level because Room does not run on this JVM; `RebuildPreservesUserStateRoomTest`
 * (androidTest) proves the behaviour against real SQLite.
 */
class FavoriteCacheRefreshTest {

    private val dao = File("src/main/java/com/lostf1sh/pixelplayeross/data/database/MusicDao.kt").readText()

    private fun body(signature: String): String {
        val start = dao.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val open = dao.indexOf('{', start)
        var depth = 0
        for (i in open until dao.length) {
            when (dao[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return dao.substring(open, i + 1)
            }
        }
        error("unbalanced $signature")
    }

    @Test
    fun `a rebuild re-derives the favourite cache after inserting the scanned songs`() {
        val rebuild = body("suspend fun rebuildLocalMusicDataWithCrossRefs(")
        assertThat(rebuild).contains("refreshLocalFavoriteCache()")
        assertThat(rebuild.indexOf("refreshLocalFavoriteCache()")).isGreaterThan(rebuild.indexOf("insertSongs(songs)"))
    }

    @Test
    fun `an incremental sync re-derives the favourite cache after rewriting songs`() {
        val sync = body("suspend fun incrementalSyncMusicData(")
        assertThat(sync).contains("refreshLocalFavoriteCache()")
        assertThat(sync.indexOf("refreshLocalFavoriteCache()")).isGreaterThan(sync.indexOf("insertSongs(chunk)"))
    }

    @Test
    fun `the cache is derived from the favourites table, by locator or by identity`() {
        val query = dao.substringBefore("suspend fun refreshLocalFavoriteCache()").substringAfterLast("@Query(")
        assertThat(query).contains("FROM favorites")
        assertThat(query).contains("isFavorite = 1")
        assertThat(query).contains("library_item_id")
    }
}
