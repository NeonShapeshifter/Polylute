package com.lostf1sh.pixelplayeross.data.lyrics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.mockk.mockk

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B52LyricsWriteAccessTest {
    private class Storage : LyricsStorage {
        var removable = false
        var writable = false
        var requests = 0
        var sender: android.content.IntentSender? = mockk()
        val writes = mutableListOf<String>()
        override fun isRemovable(path: String) = removable
        override fun matchingTree(path: String): String? = null
        override fun writePath(path: String, content: String) {
            if (!writable) throw LyricsWriteException(LyricsWriteReason.FILE_READ_ONLY)
            writes += "$path $content"
        }
        override fun writeTree(tree: String, path: String, content: String) = error("internal path")
        override fun dropTree(tree: String) = Unit
        override fun writeAccessRequest(path: String): android.content.IntentSender? { requests++; return sender }
    }

    @Test fun `Android 11 internal read only sidecar asks for write access before fallback`() = runTest {
        var fallback = false
        val result = LyricsFileSaver(Storage()).save("/storage/emulated/0/Music/Song.flac", "lyrics") { fallback = true }
        assertThat(result.javaClass.simpleName).isEqualTo("NeedsWriteAccess")
        assertThat(fallback).isFalse()
    }

    @Test @Config(sdk = [29])
    fun `Android 10 retains the B51 internal fallback`() = runTest {
        val storage = Storage()
        val result = LyricsFileSaver(storage).save("/storage/emulated/0/Music/Song.flac", "lyrics") {}
        assertThat(result).isEqualTo(LyricsSaveResult.SavedInApp(LyricsWriteReason.FILE_READ_ONLY))
        assertThat(storage.requests).isEqualTo(0)
    }

    @Test fun `permission allowed writes the same sidecar without a second request`() = runTest {
        val storage = Storage()
        val saver = LyricsFileSaver(storage)
        val path = "/storage/emulated/0/Music/Song.flac"
        assertThat(saver.save(path, "lyrics") {}).isInstanceOf(LyricsSaveResult.NeedsWriteAccess::class.java)
        storage.writable = true
        assertThat(saver.save(path, "lyrics", afterWriteAccess = true) { error("must write file") }).isEqualTo(LyricsSaveResult.FileSaved)
        assertThat(storage.requests).isEqualTo(1)
        assertThat(storage.writes).containsExactly("/storage/emulated/0/Music/Song.lrc lyrics")
    }

    @Test fun `denial and failed permission retry save in app with the read only reason`() = runTest {
        for (denied in listOf(true, false)) {
            val storage = Storage()
            val saver = LyricsFileSaver(storage)
            val path = "/storage/emulated/0/Music/Song.flac"
            saver.save(path, "lyrics") {}
            var fallback = 0
            val result = saver.save(path, "lyrics", afterWriteAccess = true,
                declinedReason = if (denied) LyricsWriteReason.FILE_READ_ONLY else null) { fallback++ }
            assertThat(result).isEqualTo(LyricsSaveResult.SavedInApp(LyricsWriteReason.FILE_READ_ONLY))
            assertThat(fallback).isEqualTo(1)
            assertThat(storage.requests).isEqualTo(1)
        }
    }

    @Test fun `an unavailable MediaStore request falls back directly`() = runTest {
        val storage = Storage().apply { sender = null }
        var fallback = false
        val result = LyricsFileSaver(storage).save("/storage/emulated/0/Music/Song.flac", "lyrics") { fallback = true }
        assertThat(result).isEqualTo(LyricsSaveResult.SavedInApp(LyricsWriteReason.FILE_READ_ONLY))
        assertThat(fallback).isTrue()
        assertThat(storage.requests).isEqualTo(1)
    }

    @Test fun `removable storage keeps the folder picker instead of MediaStore write access`() = runTest {
        val storage = Storage().apply { removable = true }
        assertThat(LyricsFileSaver(storage).save("/storage/ABCD/Music/Song.flac", "lyrics") {}).isInstanceOf(LyricsSaveResult.NeedsFolder::class.java)
        assertThat(storage.requests).isEqualTo(0)
    }
}
