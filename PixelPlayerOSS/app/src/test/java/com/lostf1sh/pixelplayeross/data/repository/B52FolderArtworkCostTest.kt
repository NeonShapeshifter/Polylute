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
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import io.mockk.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B52FolderArtworkCostTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val root = Files.createTempDirectory("b52-covers").toFile()
    private val dao = mockk<MusicDao>(relaxed = true)
    private val prefs = mockk<UserPreferencesRepository>()
    private val service = mockk<MusicInfoService>()
    private val info = mockk<MusicInfoRepository>()
    private val colors = mockk<ColorSchemeProcessor>(relaxed = true)
    private var enabled = true
    private var songs = emptyList<SongEntity>()
    private val reads = mutableMapOf<String, Long>()
    private var coverCalls = 0

    @Before fun setup() {
        every { prefs.folderCoversEnabledFlow } answers { flowOf(enabled) }
        coEvery { prefs.currentDirectoryRules() } returns LibraryFolders.Rules(emptySet(), emptySet())
        coEvery { dao.getAllSongsList() } answers { songs }
        coEvery { dao.updateSongAlbumArt(any(), any()) } answers {
            val id = firstArg<Long>()
            val uri = secondArg<String?>()
            songs = songs.map { if (it.id == id) it.copy(albumArtUriString = uri) else it }
        }
        every { info.service } returns service
        coEvery { service.cachedCover(any(), any()) } answers { coverCalls++; null }
        mockkStatic("kotlin.io.FilesKt__FileReadWriteKt")
        every { any<File>().readBytes() } answers {
            val file = firstArg<File>()
            if (file.name == "cover.jpg" && file.path.startsWith(root.path)) {
                reads[file.path] = reads.getOrDefault(file.path, 0L) + file.length()
            }
            callOriginal()
        }
    }

    @After fun cleanup() {
        songs.forEach { AlbumArtUtils.clearCacheForSong(context, it.id) }
        context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
        unmockkAll()
    }

    private fun library(folders: Int = 40, tracks: Int = 12, imageBytes: Int = 4096) {
        songs = (0 until folders).flatMap { album ->
            val dir = root.resolve("Album$album").apply { mkdirs() }
            dir.resolve("cover.jpg").writeBytes(ByteArray(imageBytes) { 37 })
            (0 until tracks).map { track ->
                val path = dir.resolve("$track.flac").apply { writeText("fake audio without art") }
                Song.emptySong().copy(id = (520000L + album * tracks + track).toString(),
                    album = "Album$album", albumId = album + 1L, artist = "Artist", path = path.path)
                    .toEntity(path.path, dir.path)
            }
        }
    }

    private suspend fun refresh() = FolderArtworkRefresh(context, dao, prefs, info, colors).refresh()
    private fun resetCounters() { reads.clear(); coverCalls = 0 }

    @Test fun `settled 480 song library reads no cover bytes on the next sync`() = runTest {
        library()
        refresh()
        resetCounters()
        refresh()
        assertThat(reads.values.sum()).isEqualTo(0L)
    }

    @Test fun `disabled settled library does not consult downloaded covers`() = runTest {
        library()
        enabled = false
        refresh()
        resetCounters()
        refresh()
        assertThat(reads.values.sum()).isEqualTo(0L)
        assertThat(coverCalls).isEqualTo(0)
    }

    @Test fun `only one changed folder is read and it is read once`() = runTest {
        library()
        refresh()
        val cover = root.resolve("Album7/cover.jpg")
        val stamp = cover.lastModified()
        cover.writeBytes(ByteArray(4096) { 51 })
        check(cover.setLastModified(stamp + 2000))
        resetCounters()
        refresh()
        assertThat(reads).containsExactly(cover.path, 4096L)
    }

    @Test fun `downloaded covers are checked once per album and artist per sync`() = runTest {
        library()
        refresh()
        assertThat(coverCalls).isEqualTo(40)
    }

    @Test fun `losing several cached song covers reads their folder image once`() = runTest {
        library(folders = 1)
        refresh()
        songs.take(4).forEach { AlbumArtUtils.getCachedAlbumArtFile(context, it.id).delete() }
        resetCounters()
        refresh()
        assertThat(reads).containsExactly(root.resolve("Album0/cover.jpg").path, 4096L)
    }

    @Test fun `switching off installed folder art removes it without reading images or querying covers`() = runTest {
        library(folders = 1)
        refresh()
        enabled = false
        resetCounters()
        refresh()
        assertThat(reads).isEmpty()
        assertThat(coverCalls).isEqualTo(0)
        assertThat(songs.map { it.albumArtUriString }).containsExactlyElementsIn(List(12) { null })
    }

    @Test fun `cancellation from a folder operation propagates`() = runTest {
        library(folders = 1, tracks = 1)
        coEvery { service.cachedCover(any(), any()) } throws CancellationException("cancel sync")
        var cancelled = false
        try { refresh() } catch (_: CancellationException) { cancelled = true }
        assertThat(cancelled).isTrue()
    }

    @Test fun `same size replacement with restored modification time keeps the settled cache`() = runTest {
        library(folders = 1)
        refresh()
        val cover = root.resolve("Album0/cover.jpg")
        val stamp = cover.lastModified()
        val before = AlbumArtUtils.getCachedAlbumArtFile(context, songs.first().id).readBytes()
        cover.writeBytes(ByteArray(4096) { 51 })
        check(cover.setLastModified(stamp))
        resetCounters()
        refresh()
        assertThat(reads).isEmpty()
        assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, songs.first().id).readBytes()).isEqualTo(before)
    }

    @Test fun `an error invalidating one folder does not skip another folder`() = runTest {
        library(folders = 2, tracks = 1)
        refresh()
        root.resolve("Album0/cover.jpg").delete()
        root.resolve("Album1/cover.jpg").delete()
        val badUri = songs.first().albumArtUriString!!
        coEvery { colors.invalidateScheme(badUri) } throws IOException("bad palette")
        refresh()
        assertThat(songs.last().albumArtUriString).isNull()
    }

    @Test fun `host benchmark measures the settled 480 song refresh`() = runTest {
        // Below the real cache's 200 MB limit, so eviction cannot turn a settled benchmark into repair work.
        library(imageBytes = 256 * 1024)
        refresh()
        resetCounters()
        val start = System.nanoTime()
        refresh()
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        println("B52_BENCHMARK songs=${songs.size} folders=40 wall_ms=$elapsed bytes=${reads.values.sum()} cachedCover=$coverCalls")
        assertThat(reads.values.sum()).isEqualTo(0L)
    }
}
