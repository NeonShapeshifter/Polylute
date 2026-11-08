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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B59 4 (#82): the still per-disc covers a sync installs, over real files (synthetic: FLAC headers, filler images). A two-disc folder with `disc1.jpg` and `disc2.jpg`: each song gets its
 * disc's picture, over the folder's `cover.jpg` and over the file's own embedded picture; only `disc2.jpg`: disc 1 keeps today's choice; a folder that mixes albums ignores `discN`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B59DiscCoverSyncTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root = Files.createTempDirectory("b59-disc").toFile()
    private val ids = mutableListOf<Long>()
    private val disc1 = ByteArray(3000) { 11 }
    private val disc2 = ByteArray(3000) { 22 }
    private val cover = ByteArray(3000) { 33 }

    @After fun cleanup() {
        ids.forEach { AlbumArtUtils.clearCacheForSong(context, it) }
        context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
        unmockkAll()
    }

    private fun flac(picture: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        fun block(type: Int, size: Int, last: Boolean = false) {
            out.write(byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()))
            out.write(ByteArray(size) { 3 })
        }
        block(0, 34); block(4, 40)
        if (picture) block(6, 50_000)
        block(1, 256, last = true)
        out.write(ByteArray(500))
        return out.toByteArray()
    }

    /** [layout]: for each song, its disc and whether its file carries an embedded picture. [albums]: the album id of each song (one id: a single-album folder). */
    private fun folder(name: String, layout: List<Pair<Int?, Boolean>>, firstId: Long, albums: (Int) -> Long = { 7L }): Pair<java.io.File, List<SongEntity>> {
        val folder = root.resolve(name).apply { mkdirs() }
        val songs = layout.mapIndexed { i, (disc, picture) ->
            val id = firstId + i
            ids += id
            val file = folder.resolve("%d-%02d.flac".format(disc ?: 1, i + 1)).apply { writeBytes(flac(picture)) }
            Song.emptySong().copy(id = id.toString(), album = "SAYONARA", albumId = albums(i), path = file.path).toEntity(file.path, folder.path).copy(discNumber = disc)
        }
        return folder to songs
    }

    private fun refresher(songs: List<SongEntity>): FolderArtworkRefresh {
        val dao = mockk<MusicDao>(relaxed = true)
        val prefs = mockk<UserPreferencesRepository>()
        val info = mockk<MusicInfoRepository>()
        val service = mockk<MusicInfoService>()
        every { prefs.folderCoversEnabledFlow } returns flowOf(true)
        coEvery { prefs.currentDirectoryRules() } returns LibraryFolders.Rules(emptySet(), emptySet())
        coEvery { dao.getAllSongsList() } returns songs
        every { info.service } returns service
        coEvery { service.cachedCover(any(), any()) } returns null
        return FolderArtworkRefresh(context, dao, prefs, info, mockk<ColorSchemeProcessor>(relaxed = true))
    }

    private fun cached(song: SongEntity): ByteArray? = AlbumArtUtils.getCachedAlbumArtFile(context, song.id).takeIf { it.isFile && it.length() > 0 }?.readBytes()

    private val layout = listOf(1 to false, 1 to true, 1 to false, 2 to false, 2 to true, 2 to false) // some with an embedded picture, some without, on both discs

    @Test fun `disc1 and disc2 give each song its disc's cover over the folder cover and over the embedded picture, and the album keeps cover dot jpg`() = runTest {
        val (folder, songs) = folder("SAYONARA", layout, 100)
        folder.resolve("disc1.jpg").writeBytes(disc1); folder.resolve("disc2.jpg").writeBytes(disc2); folder.resolve("cover.jpg").writeBytes(cover)
        refresher(songs).refresh()
        songs.forEach { song -> assertThat(cached(song)).isEqualTo(if (song.discNumber == 1) disc1 else disc2) }
        // recorded as a disc cover (the album page's headers read it)
        songs.forEach { assertThat(AlbumArtUtils.hasDiscCover(context, it.id)).isTrue() }
        // the album's own cover file is untouched and no song of the album was given it
        assertThat(folder.resolve("cover.jpg").readBytes()).isEqualTo(cover)
        assertThat(songs.map { cached(it) }.none { it.contentEquals(cover) }).isTrue()
    }

    @Test fun `only disc2 dot jpg - disc 1 keeps today's choice (the folder cover where there is no picture, the file's own where there is one)`() = runTest {
        val (folder, songs) = folder("SAYONARA", layout, 200)
        folder.resolve("disc2.jpg").writeBytes(disc2); folder.resolve("cover.jpg").writeBytes(cover)
        refresher(songs).refresh()
        songs.filter { it.discNumber == 2 }.forEach { assertThat(cached(it)).isEqualTo(disc2) }
        // disc 1: songs without a picture get cover.jpg (as today), the one with a picture is left to be extracted lazily (nothing cached here)
        assertThat(cached(songs[0])).isEqualTo(cover)
        assertThat(cached(songs[2])).isEqualTo(cover)
        assertThat(cached(songs[1])).isNull()
        songs.filter { it.discNumber == 1 }.forEach { assertThat(AlbumArtUtils.hasDiscCover(context, it.id)).isFalse() }
    }

    @Test fun `a folder that mixes albums ignores the disc files`() = runTest {
        val (folder, songs) = folder("MIXED", layout, 300) { i -> 7L + i % 2 }
        folder.resolve("disc1.jpg").writeBytes(disc1); folder.resolve("disc2.jpg").writeBytes(disc2)
        refresher(songs).refresh()
        songs.forEach {
            assertThat(cached(it)).isNull()
            assertThat(AlbumArtUtils.hasDiscCover(context, it.id)).isFalse()
        }
    }

    @Test fun `a song without a disc tag is on disc 1, a disc file added later reaches songs whose cache is full, and taking it away gives today's choice back`() = runTest {
        val (folder, songs) = folder("SAYONARA", listOf(null to false, null to true, 2 to false), 400)
        folder.resolve("cover.jpg").writeBytes(cover)
        val step = refresher(songs)
        step.refresh()
        assertThat(cached(songs[0])).isEqualTo(cover)
        // disc1.jpg appears (an "Update library" later): the song that was on cover.jpg and the one with the picture now have disc 1's
        folder.resolve("disc1.jpg").writeBytes(disc1)
        step.refresh()
        assertThat(cached(songs[0])).isEqualTo(disc1)
        assertThat(cached(songs[1])).isEqualTo(disc1)
        assertThat(cached(songs[2])).isEqualTo(cover) // disc 2 has no file: today's choice
        // and when it is taken away the disc's songs go back to what they were: the folder cover, or the file's own picture lazily
        folder.resolve("disc1.jpg").delete()
        step.refresh()
        assertThat(cached(songs[0])).isEqualTo(cover)
        assertThat(cached(songs[1])).isNull()
        assertThat(AlbumArtUtils.hasDiscCover(context, songs[0].id)).isFalse()
    }

    @Test fun `a one-disc folder is not searched for disc files - cover dot jpg as today (B59 review, the HONOR's 17 s per sync)`() = runTest {
        val (folder, songs) = folder("ONE DISC", listOf(1 to false, 1 to false, null to false), 600)
        folder.resolve("disc1.jpg").writeBytes(disc1); folder.resolve("cover.jpg").writeBytes(cover)
        refresher(songs).refresh()
        songs.forEach {
            assertThat(cached(it)).isEqualTo(cover)
            assertThat(AlbumArtUtils.hasDiscCover(context, it.id)).isFalse()
        }
    }

    @Test fun `a second sync over the same disc files writes nothing, and the sync's provenance is one commit`() = runTest {
        val (folder, songs) = folder("SAYONARA", layout, 500)
        folder.resolve("disc1.jpg").writeBytes(disc1); folder.resolve("disc2.jpg").writeBytes(disc2)
        val step = refresher(songs)
        val before = AlbumArtUtils.provenanceCommits.get()
        step.refresh()
        val first = AlbumArtUtils.provenanceCommits.get() - before
        step.refresh()
        assertThat(first).isEqualTo(1)
        assertThat(AlbumArtUtils.provenanceCommits.get() - before).isEqualTo(1)
    }
}
