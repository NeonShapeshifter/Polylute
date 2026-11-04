package com.lostf1sh.pixelplayeross.ui.animatedcover

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.AnimatedCovers
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** B56 phase 7.2 (#48): which animated file wins, where it is looked for, how often, and the default per mode. */
class B56AnimatedCoverFinderTest {
    /** Folders as name lists, with a stamp that moves when the test changes one; counts the listings and records the thread that did them. */
    private class FakeDirectory : AnimatedCoverDirectory {
        val folders = HashMap<String, List<String>>()
        val stamps = HashMap<String, Long>()
        var listings = 0
        val threads = mutableListOf<String>()
        var size = 5_000L

        override fun names(dir: File): List<String>? {
            listings++
            threads += Thread.currentThread().name
            return folders[dir.path]
        }

        override fun stamp(dir: File): Long = stamps[dir.path] ?: 1L
        override fun sizeOf(file: File): Long = size
        override fun modifiedOf(file: File): Long = 42L
    }

    private fun finder(dir: FakeDirectory, version: () -> Long = { 0L }, io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined) =
        AnimatedCoverFinder(io, dir, AlbumArtUtils::shouldTrustDirectoryArtwork, version)

    // ── the order of the files: mp4 > webp > gif ─────────────────────────────────────────────────────

    @Test
    fun `with several animated files the order is mp4 then webp then gif`() {
        assertThat(AnimatedCoverNames.pick(listOf("cover.gif", "cover.webp", "cover.mp4", "01.flac"))).isEqualTo("cover.mp4" to AnimatedCoverKind.VIDEO)
        assertThat(AnimatedCoverNames.pick(listOf("cover.gif", "cover.webp", "01.flac"))).isEqualTo("cover.webp" to AnimatedCoverKind.WEBP)
        assertThat(AnimatedCoverNames.pick(listOf("cover.gif", "01.flac"))).isEqualTo("cover.gif" to AnimatedCoverKind.GIF)
        assertThat(AnimatedCoverNames.pick(listOf("cover.webp", "cover.mp4"))).isEqualTo("cover.mp4" to AnimatedCoverKind.VIDEO)
        assertThat(AnimatedCoverKind.entries.map { it.fileName }).containsExactly("cover.mp4", "cover.webp", "cover.gif").inOrder()
    }

    @Test
    fun `names are compared without regard to case and only cover dot those three counts`() {
        assertThat(AnimatedCoverNames.pick(listOf("Cover.MP4"))).isEqualTo("Cover.MP4" to AnimatedCoverKind.VIDEO)
        assertThat(AnimatedCoverNames.pick(listOf("cover.jpg", "cover.mp4.bak", "front.gif", "mycover.webp", "cover.png", "cover"))).isNull()
        assertThat(AnimatedCoverNames.pick(emptyList())).isNull()
    }

    // ── the find step: off the main thread, once per folder, forgotten when the library changes ──────

    @Test
    fun `a folder of twelve songs is listed once`() = runBlocking {
        val dir = FakeDirectory().apply { folders["/music/Gaia"] = listOf("cover.mp4") }
        val f = finder(dir)
        repeat(12) { n -> assertThat(f.find("/music/Gaia/${n + 1}.flac")?.file?.name).isEqualTo("cover.mp4") }
        assertThat(dir.listings).isEqualTo(1)
    }

    @Test
    fun `a folder with no animated file is remembered too`() = runBlocking {
        val dir = FakeDirectory().apply { folders["/music/Gaia"] = listOf("01.flac", "cover.jpg") }
        val f = finder(dir)
        repeat(5) { assertThat(f.find("/music/Gaia/01.flac")).isNull() }
        assertThat(dir.listings).isEqualTo(1)
    }

    @Test
    fun `the lookup runs on the finder's own dispatcher and never on the caller's thread`() {
        val dir = FakeDirectory().apply { folders["/music/Gaia"] = listOf("cover.gif") }
        val pool = Executors.newSingleThreadExecutor { Thread(it, "fake-io") }
        try {
            val f = finder(dir, io = pool.asCoroutineDispatcher())
            val callerThread = Thread.currentThread().name
            runBlocking { f.find("/music/Gaia/01.flac") }
            assertThat(dir.threads).hasSize(1)
            assertThat(dir.threads.single()).startsWith("fake-io")
            assertThat(callerThread).doesNotContain("fake-io")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a library sync makes the next find look again`() = runBlocking {
        val signal = LibraryChangeSignal()
        val dir = FakeDirectory().apply { folders["/music/Gaia"] = listOf("cover.gif") }
        val f = AnimatedCoverFinder(Dispatchers.Unconfined, dir, AlbumArtUtils::shouldTrustDirectoryArtwork) { signal.version.value }
        assertThat(f.find("/music/Gaia/01.flac")?.kind).isEqualTo(AnimatedCoverKind.GIF)
        assertThat(f.find("/music/Gaia/02.flac")?.kind).isEqualTo(AnimatedCoverKind.GIF)
        assertThat(dir.listings).isEqualTo(1)

        // the owner adds the better file and the library syncs
        dir.folders["/music/Gaia"] = listOf("cover.gif", "cover.mp4")
        signal.changed("test sync")
        assertThat(f.find("/music/Gaia/01.flac")?.kind).isEqualTo(AnimatedCoverKind.VIDEO)
        assertThat(dir.listings).isEqualTo(2)
    }

    @Test
    fun `a folder that changed on its own is looked at again even before a sync`() = runBlocking {
        val dir = FakeDirectory().apply { folders["/music/Gaia"] = listOf("01.flac") }
        val f = finder(dir)
        assertThat(f.find("/music/Gaia/01.flac")).isNull()
        dir.folders["/music/Gaia"] = listOf("01.flac", "cover.webp")
        dir.stamps["/music/Gaia"] = 2L
        assertThat(f.find("/music/Gaia/01.flac")?.kind).isEqualTo(AnimatedCoverKind.WEBP)
    }

    @Test
    fun `invalidate forgets every folder`() = runBlocking {
        val dir = FakeDirectory().apply { folders["/music/A"] = listOf("cover.gif"); folders["/music/B"] = listOf("cover.gif") }
        val f = finder(dir)
        f.find("/music/A/1.flac"); f.find("/music/B/1.flac")
        f.invalidate()
        f.find("/music/A/1.flac"); f.find("/music/B/1.flac")
        assertThat(dir.listings).isEqualTo(4)
    }

    @Test
    fun `a mixed folder like Music or Downloads is not trusted, and neither is a streamed song or an empty file`() = runBlocking {
        val dir = FakeDirectory().apply {
            folders["/storage/emulated/0/Music"] = listOf("cover.mp4")
            folders["/music/Gaia"] = listOf("cover.mp4")
        }
        val f = finder(dir)
        assertThat(f.find("/storage/emulated/0/Music/a.flac")).isNull()
        assertThat(f.find("https://example.invalid/stream/a.flac")).isNull()
        assertThat(f.find("content://media/external/audio/media/5")).isNull()
        dir.size = 0L
        assertThat(f.find("/music/Gaia/1.flac")).isNull()
    }

    @Test
    fun `the answer says which file, its kind and an identity that changes with the file`() = runBlocking {
        val dir = FakeDirectory().apply { folders["/music/Gaia"] = listOf("cover.MP4") }
        val cover = finder(dir).find("/music/Gaia/1.flac")!!
        assertThat(cover.file.path).isEqualTo("/music/Gaia/cover.MP4")
        assertThat(cover.kind).isEqualTo(AnimatedCoverKind.VIDEO)
        assertThat(cover.copy(sizeBytes = cover.sizeBytes + 1).identity).isNotEqualTo(cover.identity)
        assertThat(cover.copy(modifiedMs = cover.modifiedMs + 1).identity).isNotEqualTo(cover.identity)
    }

    // ── the setting's default per mode ───────────────────────────────────────────────────────────────

    @Test
    fun `animated covers default to off in both modes, and the listener's choice wins in both`() {
        // B57 5 changes this on purpose: off in both modes
        assertThat(AnimatedCovers.defaultEnabled(dapMode = false)).isFalse()
        assertThat(AnimatedCovers.defaultEnabled(dapMode = true)).isFalse()
        assertThat(AnimatedCovers.effective(stored = null, dapMode = false)).isFalse()
        assertThat(AnimatedCovers.effective(stored = null, dapMode = true)).isFalse()
        assertThat(AnimatedCovers.effective(stored = true, dapMode = true)).isTrue()
        assertThat(AnimatedCovers.effective(stored = false, dapMode = false)).isFalse()
    }
}
