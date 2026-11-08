package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.MusicDao
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
 * B59 2 (#80): the folder-cover step of a Full Rescan, over real files in a real folder (synthetic: FLAC headers with filler pictures, a filler `cover.jpg`).
 *
 * The HONOR's Full Rescan spent 296 s of 546 s here: the step found every song's cache empty (the rescan clears them) and **extracted the embedded picture of every file** to learn whether
 * a folder image applies, then wrote the provenance with one synchronous commit per song. Now it asks the file's headers, never extracts, and commits the provenance once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B59FolderArtworkSyncTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root = Files.createTempDirectory("b59-folder").toFile()
    private val ids = mutableListOf<Long>()

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
        if (picture) block(6, 300_000)
        block(1, 512, last = true)
        out.write(ByteArray(2000))
        return out.toByteArray()
    }

    /** One album folder of [count] songs: the first [withPicture] have an embedded picture; a `cover.jpg` is in the folder. */
    private fun album(name: String, firstId: Long, count: Int, withPicture: Int): List<com.lostf1sh.pixelplayeross.data.database.SongEntity> {
        val folder = root.resolve(name).apply { mkdirs() }
        folder.resolve("cover.jpg").writeBytes(ByteArray(4096) { 21 })
        return (0 until count).map { i ->
            val id = firstId + i
            ids += id
            val file = folder.resolve("%02d.flac".format(i + 1)).apply { writeBytes(flac(i < withPicture)) }
            Song.emptySong().copy(id = id.toString(), album = name, albumId = firstId / 1000, path = file.path).toEntity(file.path, folder.path)
        }
    }

    private fun refresher(songs: List<com.lostf1sh.pixelplayeross.data.database.SongEntity>): FolderArtworkRefresh {
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

    private val provenance get() = context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE)

    @Test fun `every song has an embedded picture and the folder has a cover - nothing is extracted, nothing is installed, nothing is written`() = runTest {
        val songs = album("Album A", 1_000L, 12, withPicture = 12)
        val commitsBefore = AlbumArtUtils.provenanceCommits.get()
        refresher(songs).refresh()
        // the embedded picture wins, and it is not extracted: no cache file, no provenance, no commit
        songs.forEach { assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, it.id).exists()).isFalse() }
        assertThat(provenance.all).isEmpty()
        assertThat(AlbumArtUtils.provenanceCommits.get() - commitsBefore).isEqualTo(0)
    }

    @Test fun `a folder image is installed for the songs without a picture, not for those with one, and the provenance is committed once`() = runTest {
        val songs = album("Album B", 2_000L, 12, withPicture = 5)
        val commitsBefore = AlbumArtUtils.provenanceCommits.get()
        refresher(songs).refresh()
        songs.forEachIndexed { index, song ->
            val cached = AlbumArtUtils.getCachedAlbumArtFile(context, song.id)
            if (index < 5) assertThat(cached.exists()).isFalse() else assertThat(cached.length()).isGreaterThan(0L)
        }
        // seven songs got a folder cover: seven provenance entries, one commit (before: one commit per song)
        assertThat(provenance.all.keys.filter { key -> key.all { it.isDigit() } }).hasSize(7) // (the other keys are the folder session's own digests)
        assertThat(AlbumArtUtils.provenanceCommits.get() - commitsBefore).isEqualTo(1)
    }

    @Test fun `a second sync over the same folder writes nothing`() = runTest {
        val songs = album("Album C", 3_000L, 12, withPicture = 0)
        val refresher = refresher(songs)
        refresher.refresh()
        val commits = AlbumArtUtils.provenanceCommits.get()
        refresher.refresh()
        assertThat(AlbumArtUtils.provenanceCommits.get()).isEqualTo(commits)
    }

    @Test fun `the step reads no picture bytes - it never calls the extractor`() {
        val source = File("src/main/java/com/lostf1sh/pixelplayeross/utils/AlbumArtUtils.kt").readText()
        val from = source.indexOf("internal fun refreshFolderArtwork(\n        context: Context, songId: Long, songPath: String, existingUri: String?,\n        eligible: Boolean, enabled: Boolean, downloadedCoverExists: Boolean,\n        session")
        val to = source.indexOf("fun getAlbumArtUri(", from)
        assertThat(from).isAtLeast(0)
        val body = source.substring(from, to)
        assertThat(body).doesNotContain("extractEmbeddedAlbumArtBytes")
        assertThat(body).doesNotContain("readEmbedded")
        assertThat(body).contains("hasEmbeddedPicture(songPath)")
        val refresh = File("src/main/java/com/lostf1sh/pixelplayeross/data/repository/FolderArtworkRefresh.kt").readText()
        assertThat(refresh).doesNotContain("extractEmbedded")
        assertThat(refresh).contains("FolderArtworkProvenanceBatch()")
    }
}
