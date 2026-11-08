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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
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
 * B60 item 3: **what an "Update library" asks the chosen animated-cover folder** (a `DocumentsContract` query of ~100 ms per listing on the HONOR on Android 13+). On the HONOR B59 as
 * delivered took 163 s per sync because every song listed it again (a `getOrPut` that re-ran for a stored null); the reviewer's fix cut it to 4.3 s but still listed once per (folder, disc).
 * Here the folder is a slow, counting stand-in, over 5,000 songs in 150 folders (30 of them two-disc albums, 120 one-disc ones), all with a `cover.jpg`: after the first sync filled the
 * covers, a second one with nothing changed lists **at most once per multi-disc folder and not at all for the others**, and installs nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B60SyncListingCostTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root = Files.createTempDirectory("b60-listing").toFile()
    private val ids = mutableListOf<Long>()

    @After fun cleanup() {
        ids.forEach { AlbumArtUtils.clearCacheForSong(context, it) }
        context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
        unmockkAll()
    }

    /** A stand-in for the chosen folder: every listing costs [costMs] (the HONOR's ~100 ms, scaled down so the test stays short) and is counted by folder. */
    private class SlowCountingDirectory(private val costMs: Long) : AnimatedCoverDirectory {
        val listings = HashMap<String, Int>()
        override fun names(dir: File): List<String>? {
            listings.merge(dir.name, 1, Int::plus)
            Thread.sleep(costMs)
            return dir.list()?.toList()
        }
        override fun stamp(dir: File): Long = dir.lastModified()
        override fun sizeOf(file: File): Long = file.length()
        override fun modifiedOf(file: File): Long = file.lastModified()
    }

    private fun songsIn(name: String, count: Int, firstId: Long, twoDiscs: Boolean): List<SongEntity> {
        val folder = root.resolve(name).apply { mkdirs() }
        folder.resolve("cover.jpg").writeBytes(ByteArray(3000) { 33 })
        return (0 until count).map { i ->
            val id = firstId + i
            ids += id
            val disc = if (twoDiscs) (if (i < count / 2) 1 else 2) else null
            val file = folder.resolve("%d-%02d.flac".format(disc ?: 1, i + 1)).apply { writeBytes(ByteArray(64)) }
            Song.emptySong().copy(id = id.toString(), album = name, albumId = name.hashCode().toLong(), path = file.path).toEntity(file.path, folder.path).copy(discNumber = disc)
        }
    }

    private fun refresher(songs: List<SongEntity>, directory: AnimatedCoverDirectory): FolderArtworkRefresh {
        val dao = mockk<MusicDao>(relaxed = true)
        val prefs = mockk<UserPreferencesRepository>()
        val info = mockk<MusicInfoRepository>()
        val service = mockk<MusicInfoService>()
        every { prefs.folderCoversEnabledFlow } returns flowOf(true)
        coEvery { prefs.currentDirectoryRules() } returns LibraryFolders.Rules(emptySet(), emptySet())
        coEvery { dao.getAllSongsList() } returns songs
        every { info.service } returns service
        coEvery { service.cachedCover(any(), any()) } returns null
        return FolderArtworkRefresh(context, dao, prefs, info, mockk<ColorSchemeProcessor>(relaxed = true)).also { it.sessionFactory = { context -> com.lostf1sh.pixelplayeross.utils.FolderArtworkSession(context, directory) } }
    }

    @Test
    fun `an incremental sync with nothing changed lists the chosen folder at most once per multi-disc folder and never for the others`() = runTest {
        val songs = ArrayList<SongEntity>()
        var nextId = 10_000L
        // 30 two-disc folders of 40 songs and 120 one-disc folders of ~31 songs: 150 folders, 5,000 songs
        repeat(30) { n -> songs += songsIn("MULTI-%03d".format(n), 40, nextId, twoDiscs = true).also { nextId += 40 } }
        repeat(120) { n -> songs += songsIn("SINGLE-%03d".format(n), if (n < 80) 32 else 31, nextId, twoDiscs = false).also { nextId += it.size.toLong() } }
        assertThat(songs).hasSize(5_000)
        assertThat(songs.map { it.parentDirectoryPath }.distinct()).hasSize(150)

        // the first sync fills the covers (it may list); what matters is the next one, with nothing changed
        refresher(songs, SlowCountingDirectory(0)).refresh()
        val slow = SlowCountingDirectory(costMs = 20)
        val started = System.nanoTime()
        refresher(songs, slow).refresh()
        val seconds = (System.nanoTime() - started) / 1e9

        val multi = slow.listings.filterKeys { it.startsWith("MULTI") }
        val single = slow.listings.filterKeys { it.startsWith("SINGLE") }
        println("B60 listing cost: ${slow.listings.values.sum()} listing(s) in ${"%.1f".format(seconds)} s (multi-disc folders: ${multi.size} listed, max ${multi.values.maxOrNull() ?: 0} each; one-disc folders listed: ${single.size})")
        assertThat(multi.values.all { it <= 1 }).isTrue()
        assertThat(single).isEmpty()
        // nothing installed: every song still has the folder cover it got in the first sync
        songs.take(3).forEach { assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, it.id).readBytes()).isEqualTo(ByteArray(3000) { 33 }) }
    }
}
