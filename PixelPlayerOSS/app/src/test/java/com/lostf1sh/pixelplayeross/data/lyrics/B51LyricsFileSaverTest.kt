package com.lostf1sh.pixelplayeross.data.lyrics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

class B51LyricsFileSaverTest {
    private class Storage : LyricsStorage {
        var removable = false
        var tree: String? = null
        var pathFailure: Exception? = null
        var treeFailure: Exception? = null
        val writes = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        override fun isRemovable(path: String) = removable
        override fun matchingTree(path: String) = tree
        override fun writePath(path: String, content: String) { pathFailure?.let { throw it }; writes += "path $path $content" }
        override fun writeTree(tree: String, path: String, content: String) { treeFailure?.let { throw it }; writes += "tree $tree $path $content" }
        override fun dropTree(tree: String) { dropped += tree; this.tree = null }
    }
    private val path = "/storage/ABCD/Music/Album/Song.flac"

    @Test fun `Android 10 internal path save remains a UTF8 sidecar without picker or fallback`() = runTest {
        val storage = Storage()
        val result = LyricsFileSaver(storage).save("/storage/emulated/0/Music/Song.flac", "[00:01]sång") { error("no fallback") }
        assertThat(result).isEqualTo(LyricsSaveResult.FileSaved)
        assertThat(storage.writes).containsExactly("path /storage/emulated/0/Music/Song.lrc [00:01]sång")
    }

    @Test fun `Android 10 and Android 11 SD new files request the folder once`() = runTest {
        for (sdk in listOf(29, 30, 35)) {
            val storage = Storage().apply { removable = true }
            val result = LyricsFileSaver(storage).save(path, "lyrics") { error("not yet") }
            assertThat(result).isInstanceOf(LyricsSaveResult.NeedsFolder::class.java)
            assertThat(storage.writes).isEmpty()
            println("SDK $sdk SD uses picker")
        }
    }

    @Test fun `an existing grant writes through the tree and is reusable for another song`() = runTest {
        val storage = Storage().apply { removable = true; tree = "grant" }
        val saver = LyricsFileSaver(storage)
        repeat(2) { assertThat(saver.save(path, "lyrics $it") { error("no fallback") }).isEqualTo(LyricsSaveResult.FileSaved) }
        assertThat(storage.writes).containsExactly("tree grant /storage/ABCD/Music/Album/Song.lrc lyrics 0", "tree grant /storage/ABCD/Music/Album/Song.lrc lyrics 1").inOrder()
    }

    @Test fun `declined and wrong folder results preserve lyrics internally with their reason`() = runTest {
        for (reason in listOf(LyricsWriteReason.PERMISSION, LyricsWriteReason.WRONG_FOLDER)) {
            var saved = false
            val storage = Storage().apply { removable = true }
            val result = LyricsFileSaver(storage).save(path, "lyrics", afterPicker = true, declinedReason = reason) { saved = true }
            assertThat(saved).isTrue()
            assertThat(result).isEqualTo(LyricsSaveResult.SavedInApp(reason))
        }
    }

    @Test fun `revoked grant is dropped and asked again only once then falls back`() = runTest {
        val storage = Storage().apply { removable = true; tree = "stale"; treeFailure = SecurityException() }
        val saver = LyricsFileSaver(storage)
        assertThat(saver.save(path, "lyrics") { error("not yet") }).isInstanceOf(LyricsSaveResult.NeedsFolder::class.java)
        assertThat(storage.dropped).containsExactly("stale")
        var saved = false
        assertThat(saver.save(path, "lyrics", afterPicker = true) { saved = true }).isEqualTo(LyricsSaveResult.SavedInApp(LyricsWriteReason.PERMISSION))
        assertThat(saved).isTrue()
    }

    @Test fun `no space and read only errors keep their actual reason without another picker`() = runTest {
        for (reason in listOf(LyricsWriteReason.NO_SPACE, LyricsWriteReason.FILE_READ_ONLY, LyricsWriteReason.CARD_READ_ONLY)) {
            val storage = Storage().apply { tree = "grant"; treeFailure = LyricsWriteException(reason) }
            assertThat(LyricsFileSaver(storage).save(path, "lyrics") {}).isEqualTo(LyricsSaveResult.SavedInApp(reason))
            assertThat(storage.dropped).isEmpty()
        }
        assertThat(lyricsWriteReason(IOException("open failed ENOSPC"))).isEqualTo(LyricsWriteReason.NO_SPACE)
        assertThat(lyricsWriteReason(IOException("open failed EACCES"))).isEqualTo(LyricsWriteReason.PERMISSION)
    }

    @Test fun `internal fallback failure propagates and never reports a successful save`() = runTest {
        val storage = Storage().apply { removable = true }
        var failed = false
        try { LyricsFileSaver(storage).save(path, "lyrics", declinedReason = LyricsWriteReason.PERMISSION) { throw IOException("full") } }
        catch (_: IOException) { failed = true }
        assertThat(failed).isTrue()
    }
}
