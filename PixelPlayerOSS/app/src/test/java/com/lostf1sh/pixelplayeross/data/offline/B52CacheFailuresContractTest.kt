package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoCache
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B52CacheFailuresContractTest {
    @Test fun `a failed physical cache deletion is reported and retained for Retry`() {
        val root = Files.createTempDirectory("b52-cache-delete").toFile()
        val cache = MusicInfoCache(root, { 0L })
        try {
            val file = cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, "artist", byteArrayOf(1, 2, 3), "img", null)
            check(file.delete()); check(file.mkdir()); file.resolve("blocked").writeText("fixture")
            var failed = false
            try { cache.remove(MusicInfoCache.Kind.ARTIST_PHOTO, "artist") } catch (_: Exception) { failed = true }
            assertThat(failed).isTrue()
            check(file.resolve("blocked").delete())
            cache.remove(MusicInfoCache.Kind.ARTIST_PHOTO, "artist")
            assertThat(file.exists()).isFalse()
        } finally { root.deleteRecursively() }
    }

    @Test fun `a failed index write does not claim that a cache mutation succeeded`() {
        val root = Files.createTempDirectory("b52-cache-index").toFile()
        try {
            val blocked = root.resolve("index.json")
            check(blocked.mkdir())
            blocked.resolve("keep-directory-nonempty").writeText("fixture")
            assertThat(blocked.isDirectory).isTrue()
            val cache = MusicInfoCache(root, { 0L })
            var failed = false
            try { cache.putFile(MusicInfoCache.Kind.COVER, "album|artist", byteArrayOf(1, 2, 3), "img", null) }
            catch (_: Exception) { failed = true }
            assertThat(failed).isTrue()
        } finally { root.deleteRecursively() }
    }
}
