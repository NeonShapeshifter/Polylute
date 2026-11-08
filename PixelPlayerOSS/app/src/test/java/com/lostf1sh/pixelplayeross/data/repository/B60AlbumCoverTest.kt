package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverFrames
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
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
 * B60 item 2 (#82 follow-up): **the album's own cover is never a `discN` file.** On the HONOR the album page of SAYONARA (`disc1.mp4` only) showed disc 1's cover as the album's, because the
 * album took the lowest-id song's artwork and a disc cover is installed into exactly that. Here, over real files (FLAC headers with and without a picture, filler images): with `disc1.jpg` +
 * `disc2.jpg` + embedded pictures and no `cover.jpg` the album is the embedded picture (named, **not extracted by the sync**) and each song its disc's; with `cover.jpg` the album is
 * `cover.jpg`; with `disc1.mp4` only the album is not its frame; an album with no disc files is untouched; a sync that changes nothing writes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B60AlbumCoverTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root = Files.createTempDirectory("b60-album").toFile()
    private val ids = mutableListOf<Long>()
    private val albumIds = mutableListOf<Long>()
    private val disc1 = ByteArray(3000) { 11 }
    private val disc2 = ByteArray(3000) { 22 }
    private val cover = ByteArray(3000) { 33 }
    private val albumId = 7L

    /** What the folder step told the database about albums: album id → the address written (null: none). */
    private val albumWrites = mutableListOf<Pair<Long, String?>>()
    private val refreshed = mutableListOf<Long>()

    @After fun cleanup() {
        ids.forEach { AlbumArtUtils.clearCacheForSong(context, it) }
        albumIds.forEach { AlbumArtUtils.clearCacheForSong(context, LocalArtworkUri.albumCoverKey(it)) }
        context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("album_cover_sources", Context.MODE_PRIVATE).edit().clear().commit()
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

    private fun folder(name: String, discs: List<Int>, picture: Boolean, firstId: Long, album: Long = albumId): Pair<java.io.File, List<SongEntity>> {
        albumIds += album
        val folder = root.resolve(name).apply { mkdirs() }
        val songs = discs.mapIndexed { i, disc ->
            val id = firstId + i
            ids += id
            val file = folder.resolve("%d-%02d.flac".format(disc, i + 1)).apply { writeBytes(flac(picture)) }
            Song.emptySong().copy(id = id.toString(), album = "SAYONARA", albumId = album, path = file.path).toEntity(file.path, folder.path).copy(discNumber = disc)
        }
        return folder to songs
    }

    private fun refresher(songs: List<SongEntity>, animated: Boolean = false): FolderArtworkRefresh {
        val dao = mockk<MusicDao>(relaxed = true)
        val prefs = mockk<UserPreferencesRepository>()
        val info = mockk<MusicInfoRepository>()
        val service = mockk<MusicInfoService>()
        every { prefs.folderCoversEnabledFlow } returns flowOf(true)
        coEvery { prefs.currentDirectoryRules() } returns LibraryFolders.Rules(emptySet(), emptySet())
        coEvery { dao.getAllSongsList() } returns songs
        coEvery { dao.setAlbumArtworkIfDifferent(any(), any()) } answers { albumWrites += firstArg<Long>() to secondArg<String?>() }
        coEvery { dao.refreshAlbumArtwork(any()) } answers { refreshed += firstArg<List<Long>>() }
        every { info.service } returns service
        coEvery { service.cachedCover(any(), any()) } returns null
        // the animated covers are read from the disk (API 33+ would read the folder the listener chose, and there is none here)
        return FolderArtworkRefresh(context, dao, prefs, info, mockk<ColorSchemeProcessor>(relaxed = true)).also {
            if (animated) it.sessionFactory = { c -> com.lostf1sh.pixelplayeross.utils.FolderArtworkSession(c, com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverDirectory.Disk) }
        }
    }

    private fun cached(id: Long): ByteArray? = AlbumArtUtils.getCachedAlbumArtFile(context, id).takeIf { it.isFile && it.length() > 0 }?.readBytes()
    private val discs = listOf(1, 1, 1, 2, 2, 2)

    @Test fun `disc1 and disc2 and embedded art with no cover dot jpg - the album is the embedded picture, named and not extracted, and each song is its disc's`() = runTest {
        val (folder, songs) = folder("SAYONARA", discs, picture = true, firstId = 100)
        folder.resolve("disc1.jpg").writeBytes(disc1); folder.resolve("disc2.jpg").writeBytes(disc2)
        refresher(songs).refresh()
        // the songs keep their disc's cover
        songs.forEach { assertThat(cached(it.id)).isEqualTo(if (it.discNumber == 1) disc1 else disc2) }
        // the album: one write, an address of the album's own key that names the lowest-id song's file for its picture
        val (id, uri) = albumWrites.single()
        assertThat(id).isEqualTo(albumId)
        assertThat(LocalArtworkUri.isAlbumCoverKey(LocalArtworkUri.parseSongId(uri!!)!!)).isTrue()
        assertThat(LocalArtworkUri.parseAlbumCoverSource(uri)).isEqualTo(songs.minOf { it.id })
        // it is not a song's address, so it cannot be a disc's picture
        assertThat(songs.map { LocalArtworkUri.buildSongUri(it.id) }.none { uri.startsWith(it) }).isTrue()
        // and the sync extracted nothing for it: the cache of the album's key is empty until the cover is first shown
        assertThat(cached(LocalArtworkUri.albumCoverKey(albumId))).isNull()
        assertThat(AlbumArtUtils.recordedAlbumCover(context, albumId)!!.uri).isEqualTo(uri)
    }

    @Test fun `with a cover dot jpg the album is cover dot jpg and the songs are still their disc's`() = runTest {
        val (folder, songs) = folder("SAYONARA", discs, picture = true, firstId = 200)
        folder.resolve("disc1.jpg").writeBytes(disc1); folder.resolve("disc2.jpg").writeBytes(disc2); folder.resolve("cover.jpg").writeBytes(cover)
        refresher(songs).refresh()
        songs.forEach { assertThat(cached(it.id)).isEqualTo(if (it.discNumber == 1) disc1 else disc2) }
        val uri = albumWrites.single().second!!
        assertThat(LocalArtworkUri.parseAlbumCoverSource(uri)).isNull() // a file's cover: nothing to extract
        assertThat(cached(LocalArtworkUri.albumCoverKey(albumId))).isEqualTo(cover)
        // the cover file itself is untouched, and not any song's cover
        assertThat(folder.resolve("cover.jpg").readBytes()).isEqualTo(cover)
    }

    /** B60 review: the folder step installs the album's `cover.*` copy and the end-of-sync clean deleted it again (an album-cover key is never a song id), every sync. */
    @Test fun `the end-of-sync cache clean keeps the album's own cover while it is recorded, and drops it once forgotten`() = runTest {
        val (folder, songs) = folder("SAYONARA", discs, picture = true, firstId = 250)
        folder.resolve("disc1.jpg").writeBytes(disc1); folder.resolve("disc2.jpg").writeBytes(disc2); folder.resolve("cover.jpg").writeBytes(cover)
        refresher(songs).refresh()
        val key = LocalArtworkUri.albumCoverKey(albumId)
        assertThat(LocalArtworkUri.albumIdOfCoverKey(key)).isEqualTo(albumId)
        assertThat(cached(key)).isEqualTo(cover)
        val songIds = songs.map { it.id }.toSet()
        com.lostf1sh.pixelplayeross.utils.AlbumArtCacheManager.cleanOrphanedCacheFiles(context, songIds)
        assertThat(cached(key)).isEqualTo(cover)
        songs.forEach { assertThat(cached(it.id)).isEqualTo(if (it.discNumber == 1) disc1 else disc2) }
        // an album cover no album records any more is an orphan like any other
        context.getSharedPreferences("album_cover_sources", Context.MODE_PRIVATE).edit().clear().commit()
        com.lostf1sh.pixelplayeross.utils.AlbumArtCacheManager.cleanOrphanedCacheFiles(context, songIds)
        assertThat(cached(key)).isNull()
    }

    /**
     * B61 review of the reviewer's clean: it found the album behind a cover key by **inverting the key** (`albumIdOfCoverKey`), which is exact only for ids below 2^40. MediaStore album ids
     * on the HONOR (Android 15) are 19-digit hashes (`album=2160361001860445455` in its log), far above it: the inverse gave another number, the album was "not recorded", and the clean
     * deleted the album's `cover.*` copy again at the end of every Full Rescan, exactly what the reviewer's test was written to stop (it used album id 7).
     */
    @Test fun `the end-of-sync cache clean keeps the album's own cover for a real MediaStore album id too (19 digits, above 2 to the 40)`() = runTest {
        val bigAlbum = 2160361001860445455L
        val (folder, songs) = folder("SAYONARA", discs, picture = true, firstId = 270, album = bigAlbum)
        folder.resolve("disc1.jpg").writeBytes(disc1); folder.resolve("disc2.jpg").writeBytes(disc2); folder.resolve("cover.jpg").writeBytes(cover)
        refresher(songs).refresh()
        val key = LocalArtworkUri.albumCoverKey(bigAlbum)
        assertThat(cached(key)).isEqualTo(cover)
        val songIds = songs.map { it.id }.toSet()
        com.lostf1sh.pixelplayeross.utils.AlbumArtCacheManager.cleanOrphanedCacheFiles(context, songIds)
        assertThat(cached(key)).isEqualTo(cover)
        assertThat(AlbumArtUtils.isRecordedAlbumCoverKey(context, key)).isTrue()
        // an album that is not recorded is still an orphan: another album's key, with no decision behind it
        assertThat(AlbumArtUtils.isRecordedAlbumCoverKey(context, LocalArtworkUri.albumCoverKey(bigAlbum + 1))).isFalse()
        context.getSharedPreferences("album_cover_sources", Context.MODE_PRIVATE).edit().clear().commit()
        com.lostf1sh.pixelplayeross.utils.AlbumArtCacheManager.cleanOrphanedCacheFiles(context, songIds)
        assertThat(cached(key)).isNull()
    }

    /** B61: the LRU size clean evicts the oldest quarter of the cache; the album's own cover (written at sync time, never "used" since) is no longer among them. */
    @Test fun `the size clean evicts the oldest files but never an album's recorded own cover`() = runTest {
        val bigAlbum = 2160361001860445455L
        val (folder, songs) = folder("SAYONARA", discs, picture = true, firstId = 290, album = bigAlbum)
        folder.resolve("disc1.jpg").writeBytes(disc1); folder.resolve("disc2.jpg").writeBytes(disc2); folder.resolve("cover.jpg").writeBytes(cover)
        refresher(songs).refresh()
        val key = LocalArtworkUri.albumCoverKey(bigAlbum)
        assertThat(cached(key)).isEqualTo(cover)
        // the album's copy is the oldest file of the cache, the songs' copies are newer: a size clean with a limit of 0 evicts a quarter of the 7 files, oldest first
        AlbumArtUtils.getCachedAlbumArtFile(context, key).setLastModified(1_000L)
        songs.forEachIndexed { i, song -> AlbumArtUtils.getCachedAlbumArtFile(context, song.id).setLastModified(2_000L + i) }
        com.lostf1sh.pixelplayeross.utils.AlbumArtCacheManager.forgetLastCleanup()
        val deleted = com.lostf1sh.pixelplayeross.utils.AlbumArtCacheManager.cleanCacheIfNeeded(context, maxCacheSizeMb = 0)
        assertThat(deleted).isEqualTo(1)
        assertThat(cached(key)).isEqualTo(cover)
        // what went was the oldest of the others
        assertThat(cached(songs[0].id)).isNull()
        songs.drop(1).forEach { assertThat(cached(it.id)).isNotNull() }
    }

    @Test fun `with disc1 dot mp4 only the album is not its frame - without a picture or a cover file it has none, with a picture it is that`() = runTest {
        val frame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 30, 30)) }
        val original = AnimatedCoverFrames.decoder
        AnimatedCoverFrames.decoder = AnimatedCoverFrames.FirstFrameDecoder { _, _ -> frame }
        try {
            // no picture in the files, no cover file: disc 1's songs show the frame of disc1.mp4, the album shows nothing of its own
            val (folder, songs) = folder("SAYONARA", discs, picture = false, firstId = 300)
            folder.resolve("disc1.mp4").writeBytes(ByteArray(4096) { 5 })
            refresher(songs, animated = true).refresh()
            assertThat(cached(songs[0].id)).isNotNull()
            assertThat(AlbumArtUtils.hasDiscCover(context, songs[0].id)).isTrue()
            assertThat(albumWrites.single().second).isNull()
            assertThat(cached(LocalArtworkUri.albumCoverKey(albumId))).isNull()
            // the same album with pictures in its files: the album is the picture of the files (disc 2's songs, which have no disc file, keep it as theirs)
            albumWrites.clear()
            val (folder2, songs2) = folder("SAYONARA 2", discs, picture = true, firstId = 400, album = 8L)
            folder2.resolve("disc1.mp4").writeBytes(ByteArray(4096) { 5 })
            refresher(songs2, animated = true).refresh()
            val uri = albumWrites.single().second!!
            assertThat(LocalArtworkUri.parseAlbumCoverSource(uri)).isEqualTo(songs2.minOf { it.id })
        } finally {
            AnimatedCoverFrames.decoder = original
        }
    }

    @Test fun `an album with no disc files behaves exactly as before - no album cover is decided and nothing is written for it`() = runTest {
        val (folder, songs) = folder("PLAIN", discs, picture = false, firstId = 500)
        folder.resolve("cover.jpg").writeBytes(cover)
        refresher(songs).refresh()
        assertThat(albumWrites).isEmpty()
        assertThat(AlbumArtUtils.recordedAlbumCover(context, albumId)).isNull()
        songs.forEach { assertThat(cached(it.id)).isEqualTo(cover) }
    }

    @Test fun `a sync that changes nothing gives the same address and writes no cover file`() = runTest {
        val (folder, songs) = folder("SAYONARA", discs, picture = true, firstId = 600)
        folder.resolve("disc1.jpg").writeBytes(disc1); folder.resolve("disc2.jpg").writeBytes(disc2); folder.resolve("cover.jpg").writeBytes(cover)
        val step = refresher(songs)
        step.refresh()
        val coverFile = AlbumArtUtils.getCachedAlbumArtFile(context, LocalArtworkUri.albumCoverKey(albumId))
        val modified = coverFile.lastModified().also { coverFile.setLastModified(1_000L) }
        assertThat(modified).isGreaterThan(1_000L)
        albumWrites.clear()
        step.refresh()
        assertThat(albumWrites.map { it.second }.distinct()).hasSize(1)
        assertThat(coverFile.lastModified()).isEqualTo(1_000L) // not rewritten
        assertThat(coverFile.readBytes()).isEqualTo(cover)
    }

    @Test fun `taking the disc files away gives the album back to the ordinary rule and forgets its own cover`() = runTest {
        val (folder, songs) = folder("SAYONARA", discs, picture = false, firstId = 700)
        folder.resolve("disc1.jpg").writeBytes(disc1); folder.resolve("disc2.jpg").writeBytes(disc2); folder.resolve("cover.jpg").writeBytes(cover)
        val step = refresher(songs)
        step.refresh()
        assertThat(AlbumArtUtils.recordedAlbumCover(context, albumId)).isNotNull()
        folder.resolve("disc1.jpg").delete(); folder.resolve("disc2.jpg").delete()
        refreshed.clear(); albumWrites.clear()
        step.refresh()
        assertThat(AlbumArtUtils.recordedAlbumCover(context, albumId)).isNull()
        assertThat(cached(LocalArtworkUri.albumCoverKey(albumId))).isNull()
        assertThat(refreshed).contains(albumId) // the album's art is taken from its songs again
        assertThat(albumWrites).isEmpty()
    }

    @Test fun `the album cover address is a song address of a reserved key, and the helpers read it back`() {
        val key = LocalArtworkUri.albumCoverKey(5L)
        assertThat(key).isLessThan(0L)
        assertThat(LocalArtworkUri.albumCoverKey(5L)).isNotEqualTo(LocalArtworkUri.albumCoverKey(6L))
        assertThat(LocalArtworkUri.albumCoverKey(-5L)).isLessThan(0L)
        val uri = LocalArtworkUri.buildAlbumCoverUri(5L, "abc", sourceSongId = 99L)
        assertThat(LocalArtworkUri.isLocalArtworkUri(uri)).isTrue()
        assertThat(LocalArtworkUri.parseSongId(uri)).isEqualTo(key)
        assertThat(LocalArtworkUri.parseAlbumCoverSource(uri)).isEqualTo(99L)
        assertThat(LocalArtworkUri.extractCacheBustToken(uri)).isEqualTo("abc")
        assertThat(LocalArtworkUri.parseAlbumCoverSource(LocalArtworkUri.buildAlbumCoverUri(5L, "abc"))).isNull()
        // an ordinary song address is never an album cover
        assertThat(LocalArtworkUri.parseAlbumCoverSource(LocalArtworkUri.buildSongUriWithTimestamp(99L))).isNull()
        assertThat(AlbumArtUtils.albumCoverSourcePath(context, LocalArtworkUri.buildSongUri(99L))).isNull()
    }
}
