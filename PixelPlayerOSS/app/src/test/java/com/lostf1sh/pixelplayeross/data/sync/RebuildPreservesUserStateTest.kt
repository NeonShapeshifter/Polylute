package com.lostf1sh.pixelplayeross.data.sync

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.BenchmarkLaunchPolicy
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-009: `SyncMode.REBUILD` deleted every local favourite and all lyrics, and any app could trigger
 * it through the exported `MainActivity` with two boolean extras.
 *
 * Favourites and lyrics are owned by the durable `library_item_id` (no foreign key to `songs`), so a
 * rebuild of the song projections has no reason to touch them — "absent is not deleted" applies to a
 * rebuild as much as to an incremental scan. The benchmark hook is honoured only in the `benchmark`
 * build.
 *
 * The DAO half is asserted on source here because Room does not run on this JVM (no Robolectric);
 * `RebuildPreservesUserStateRoomTest` (androidTest) proves it against real SQLite on a device.
 */
class RebuildPreservesUserStateTest {

    private val dao = File("src/main/java/com/lostf1sh/pixelplayeross/data/database/MusicDao.kt").readText()
    private val activity = File("src/main/java/com/lostf1sh/pixelplayeross/MainActivity.kt").readText()

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced $signature")
    }

    @Test
    fun `the rebuild transaction does not delete favourites or lyrics`() {
        val rebuild = body(dao, "suspend fun rebuildLocalMusicDataWithCrossRefs(")
        assertThat(rebuild).doesNotContain("deleteLocalFavorites()")
        assertThat(rebuild).doesNotContain("deleteLocalLyrics()")
    }

    @Test
    fun `benchmark extras rebuild only in the benchmark build`() {
        assertThat(BenchmarkLaunchPolicy.shouldRebuildDatabase("benchmark", isBenchmark = true, rebuildRequested = true)).isTrue()
        assertThat(BenchmarkLaunchPolicy.shouldRebuildDatabase("debug", isBenchmark = true, rebuildRequested = true)).isFalse()
        assertThat(BenchmarkLaunchPolicy.shouldRebuildDatabase("release", isBenchmark = true, rebuildRequested = true)).isFalse()
        assertThat(BenchmarkLaunchPolicy.shouldRebuildDatabase("benchmark", isBenchmark = true, rebuildRequested = false)).isFalse()
    }

    @Test
    fun `MainActivity decides the benchmark rebuild through the policy and the build type`() {
        assertThat(activity).containsMatch(
            """BenchmarkLaunchPolicy\.shouldRebuildDatabase\(\s*BuildConfig\.BUILD_TYPE"""
        )
    }
}
