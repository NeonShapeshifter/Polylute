package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.data.database.toEntity
import com.lostf1sh.pixelplayeross.data.library.LibraryFolders
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoService
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemeProcessor
import com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverDirectory
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.FolderArtworkSession
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import com.lostf1sh.pixelplayeross.data.worker.SyncRunContext
import com.lostf1sh.pixelplayeross.utils.FolderArtworkDecisions
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B61 item 1: **"Update library" after a Full Rescan**. On the HONOR every incremental sync with nothing changed took 14-28 s after the 11-07 Full Rescan (`folder-artwork` 14-25 s), against
 * 2.9 s and 8.0 s (`folder-artwork` 1.4 and 2.2 s) before it.
 *
 * What a Full Rescan leaves (B59 item 2 made a sync stop extracting embedded art): no cached art file for any song, and the file's own picture is only extracted when the song is shown. The
 * folder step read "no cached file" as "this song may need a folder image", so **on every sync** each such song was opened for its headers (the embedded-picture probe) and each folder
 * with songs that have none and no still image asked the chosen animated-cover folder (a `DocumentsContract` listing of ~100 ms on the HONOR); the answer, "nothing to install", was
 * never remembered, so the next sync did all of it again.
 *
 * The library here is what such a rescan leaves, scaled to 4,500 songs in 150 folders: 100 folders whose files carry a picture (nothing cached), 30 whose files carry none and have no
 * still image (nothing to install, but the animated-cover folder is asked), and 20 with a `cover.jpg` (installed by the first sync). The animated-cover folder is a slow, counting stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B61FullRescanSyncCostTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root = Files.createTempDirectory("b61-rescan").toFile()
    private val ids = mutableListOf<Long>()

    @After fun cleanup() {
        ids.forEach { AlbumArtUtils.clearCacheForSong(context, it) }
        context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("folder_art_decisions", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
        unmockkAll()
    }

    /** The chosen animated-cover folder: every listing costs [costMs] (the HONOR's ~100 ms, scaled down) and is counted by folder. */
    private class SlowCountingDirectory(private val costMs: Long, private val source: String = "tree-A", private val failFor: String? = null) : AnimatedCoverDirectory {
        val listings = HashMap<String, Int>()
        override fun identity(): String = source
        override fun names(dir: File): List<String>? {
            listings.merge(dir.name, 1, Int::plus)
            if (costMs > 0) Thread.sleep(costMs)
            // a provider that errors on one folder: not "outside the chosen folder" (isUnreachable stays false), so not an answer
            if (dir.name == failFor) return null
            return dir.list()?.toList()
        }
        override fun stamp(dir: File): Long = dir.lastModified()
        override fun sizeOf(file: File): Long = file.length()
        override fun modifiedOf(file: File): Long = file.lastModified()
    }

    /** The embedded-picture probe (a file open and a walk of its headers on the HONOR), counted by folder. Files of the `EMBED-` folders carry a picture. */
    private class CountingProbe {
        val calls = HashMap<String, Int>()
        fun hasPicture(path: String): Boolean {
            val folder = File(path).parentFile!!.name
            calls.merge(folder, 1, Int::plus)
            return folder.startsWith("EMBED-")
        }
    }

    private var nextId = 50_000L
    private var nextAlbum = 700L

    private fun folderOf(name: String, count: Int, withCover: Boolean): List<SongEntity> {
        val folder = root.resolve(name).apply { mkdirs() }
        if (withCover) folder.resolve("cover.jpg").writeBytes(ByteArray(3000) { 61 })
        val album = nextAlbum++
        return (0 until count).map { i ->
            val id = nextId++
            ids += id
            val file = folder.resolve("%02d.flac".format(i + 1)).apply { writeBytes(ByteArray(64)) }
            Song.emptySong().copy(id = id.toString(), album = name, albumId = album, path = file.path).toEntity(file.path, folder.path)
                .copy(albumId = album, albumArtUriString = LocalArtworkUri.buildSongUri(id), mediaStoreDateModified = 1_700_000_000L)
        }
    }

    private fun library(): List<SongEntity> {
        val songs = ArrayList<SongEntity>()
        repeat(100) { songs += folderOf("EMBED-%03d".format(it), 30, withCover = false) }
        repeat(30) { songs += folderOf("PLAIN-%03d".format(it), 30, withCover = false) }
        repeat(20) { songs += folderOf("COVER-%03d".format(it), 30, withCover = true) }
        return songs
    }

    private fun refresher(songs: List<SongEntity>, directory: AnimatedCoverDirectory, probe: CountingProbe): FolderArtworkRefresh {
        val dao = mockk<MusicDao>(relaxed = true)
        val prefs = mockk<UserPreferencesRepository>()
        val info = mockk<MusicInfoRepository>()
        val service = mockk<MusicInfoService>()
        every { prefs.folderCoversEnabledFlow } returns flowOf(true)
        coEvery { prefs.currentDirectoryRules() } returns LibraryFolders.Rules(emptySet(), emptySet())
        coEvery { dao.getAllSongsList() } returns songs
        every { info.service } returns service
        coEvery { service.cachedCover(any(), any()) } returns null
        return FolderArtworkRefresh(context, dao, prefs, info, mockk<ColorSchemeProcessor>(relaxed = true)).also {
            it.sessionFactory = { c -> FolderArtworkSession(c, directory) }
            it.embeddedPictureProbe = probe::hasPicture
        }
    }

    @Test
    fun `after a Full Rescan the second and third incremental sync with nothing changed ask nobody about folders that have nothing to install`() = runTest {
        val songs = library()
        assertThat(songs).hasSize(4_500)
        // the state a Full Rescan leaves: no cached art file for any song
        songs.forEach { assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, it.id).isFile).isFalse() }

        val rows = StringBuilder()
        val perSync = (1..3).map { n ->
            val slow = SlowCountingDirectory(costMs = 20)
            val probe = CountingProbe()
            val started = System.nanoTime()
            refresher(songs, slow, probe).refresh()
            val seconds = (System.nanoTime() - started) / 1e9
            val listed = slow.listings.keys.count { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }
            val probed = probe.calls.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }.values.sum()
            rows.append("sync $n: ${slow.listings.values.sum()} listing(s) of the animated-cover folder (${slow.listings.keys.count { it.startsWith("PLAIN-") }} of the 30 nothing-to-install folders), ")
                .append("$probed embedded-picture probe(s) in the 130 folders with nothing to install, ${"%.2f".format(seconds)} s\n")
            Triple(slow, probe, listed to probed)
        }
        println("B61 sync cost after a Full Rescan (4,500 songs, 150 folders, listing = 20 ms):\n$rows")

        // the first sync learns (it may list and probe); the second and the third must not repeat it
        for (n in 1..2) {
            val (slow, probe, _) = perSync[n]
            assertThat(slow.listings.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }).isEmpty()
            assertThat(probe.calls.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }).isEmpty()
        }
        // what was to be installed was installed by the first sync, in the folders that had a still image
        songs.filter { it.parentDirectoryPath.contains("COVER-") }.take(3).forEach {
            assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, it.id).readBytes()).isEqualTo(ByteArray(3000) { 61 })
        }
    }

    private class Counts(val slow: SlowCountingDirectory, val probe: CountingProbe)

    private suspend fun sync(songs: List<SongEntity>, directory: SlowCountingDirectory = SlowCountingDirectory(0), fullRescan: Boolean = false): Counts {
        val probe = CountingProbe()
        val refresher = refresher(songs, directory, probe)
        if (fullRescan) kotlinx.coroutines.withContext(SyncRunContext(inTransaction = { it() }, onProgress = { _, _ -> }, fullRescan = true)) { refresher.refresh() } else refresher.refresh()
        return Counts(directory, probe)
    }

    /** Two syncs: the first learns, the second finds nothing left to ask. */
    private suspend fun settled(songs: List<SongEntity>) {
        sync(songs)
        val second = sync(songs)
        assertThat(second.slow.listings.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }).isEmpty()
        assertThat(second.probe.calls.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }).isEmpty()
    }

    private fun bump(folder: String) {
        val dir = root.resolve(folder)
        dir.setLastModified(dir.lastModified() + 5_000)
    }

    @Test
    fun `a cover put into a folder that had nothing to install is installed by the next sync and nothing else is asked again`() = runTest {
        val songs = library()
        settled(songs)
        root.resolve("PLAIN-001/cover.jpg").writeBytes(ByteArray(3000) { 7 })
        bump("PLAIN-001")
        val third = sync(songs)
        songs.filter { it.parentDirectoryPath.endsWith("PLAIN-001") }.forEach {
            assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, it.id).readBytes()).isEqualTo(ByteArray(3000) { 7 })
        }
        // only that folder was looked at again
        assertThat(third.probe.calls.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }.keys).containsExactly("PLAIN-001")
        assertThat(third.slow.listings.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }.keys).isEmpty()
    }

    @Test
    fun `a song whose file changed makes its folder be looked at again, and only that folder`() = runTest {
        val songs = library()
        settled(songs)
        val first = songs.first { it.parentDirectoryPath.endsWith("EMBED-007") }
        val edited = songs.map { if (it.id == first.id) it.copy(mediaStoreDateModified = 1_700_000_999L) else it }
        val third = sync(edited)
        assertThat(third.probe.calls.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }.keys).containsExactly("EMBED-007")
        // and it is settled again by that pass
        val fourth = sync(edited)
        assertThat(fourth.probe.calls.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }).isEmpty()
    }

    @Test
    fun `a song whose artwork the listener chose is looked at again`() = runTest {
        val songs = library()
        settled(songs)
        val first = songs.first { it.parentDirectoryPath.endsWith("PLAIN-004") }
        val chosen = songs.map { if (it.id == first.id) it.copy(albumArtUriString = "content://user/picked.jpg") else it }
        val third = sync(chosen)
        assertThat(third.probe.calls.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }.keys).containsExactly("PLAIN-004")
    }

    @Test
    fun `a listing that failed is not remembered as nothing to install`() = runTest {
        val songs = library()
        sync(songs, SlowCountingDirectory(0, failFor = "PLAIN-002"))
        val second = sync(songs)
        // every other folder was settled by the first pass; the one whose question got no answer is asked again, and only it
        assertThat(second.slow.listings.keys).containsExactly("PLAIN-002")
        val third = sync(songs)
        assertThat(third.slow.listings.filterKeys { it.startsWith("PLAIN-") }).isEmpty()
    }

    @Test
    fun `another source of animated covers is asked about every folder once, and then not again`() = runTest {
        val songs = library()
        settled(songs)
        val third = sync(songs, SlowCountingDirectory(0, source = "tree-B"))
        assertThat(third.slow.listings.keys.filter { it.startsWith("PLAIN-") }).hasSize(30)
        val fourth = sync(songs, SlowCountingDirectory(0, source = "tree-B"))
        assertThat(fourth.slow.listings.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }).isEmpty()
    }

    @Test
    fun `a Full Rescan decides every folder again`() = runTest {
        val songs = library()
        settled(songs)
        val rescan = sync(songs, fullRescan = true)
        assertThat(rescan.slow.listings.keys.filter { it.startsWith("PLAIN-") }).hasSize(30)
        assertThat(rescan.probe.calls.filterKeys { it.startsWith("EMBED-") }.values.sum()).isEqualTo(3_000)
        // and the one after it, with nothing changed, asks nobody
        val next = sync(songs)
        assertThat(next.slow.listings.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }).isEmpty()
        assertThat(next.probe.calls.filterKeys { it.startsWith("PLAIN-") || it.startsWith("EMBED-") }).isEmpty()
    }

    @Test
    fun `a folder with an installed cover is never settled, so a cover replaced in place is installed again`() = runTest {
        val songs = library()
        settled(songs)
        // the file is replaced by another one: the folder's own time does not move, the file's does
        val cover = root.resolve("COVER-003/cover.jpg")
        val stamp = root.resolve("COVER-003").lastModified()
        cover.writeBytes(ByteArray(3500) { 9 })
        cover.setLastModified(cover.lastModified() + 7_000)
        root.resolve("COVER-003").setLastModified(stamp)
        sync(songs)
        songs.filter { it.parentDirectoryPath.endsWith("COVER-003") }.forEach {
            assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, it.id).readBytes()).isEqualTo(ByteArray(3500) { 9 })
        }
    }

    @Test
    fun `the decisions are written once per sync and a folder that disappeared is forgotten`() = runTest {
        val songs = library()
        val before = FolderArtworkDecisions.commits.get()
        sync(songs)
        assertThat(FolderArtworkDecisions.commits.get() - before).isEqualTo(1)
        val prefs = context.getSharedPreferences("folder_art_decisions", Context.MODE_PRIVATE)
        assertThat(prefs.all.keys.count { it.contains("PLAIN-") || it.contains("EMBED-") }).isEqualTo(130)
        // a sync that no longer finds the PLAIN folders drops them from the memory
        sync(songs.filterNot { it.parentDirectoryPath.contains("PLAIN-") })
        assertThat(prefs.all.keys.count { it.contains("PLAIN-") }).isEqualTo(0)
        assertThat(prefs.all.keys.count { it.contains("EMBED-") }).isEqualTo(100)
    }
}
