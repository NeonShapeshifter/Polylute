package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B59 2 (#80): the measurement, not a check (opt-in: `B59_FOLDER_BENCH=1`; `B59_BENCH_SONGS`, 5,000 by default; `B59_BENCH_PICTURE_KB`, the size of every song's embedded picture, 1,024 by
 * default; `B59_BENCH_OUT`, the file written). The folder-cover step of a Full Rescan on a library where **every song has an embedded picture and every album folder has a `cover.jpg`**,
 * over real files (FLAC headers with a filler picture block; a filler cover), the way the HONOR's library is. Everything the rescan clears is clear (no cache, no provenance).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B59FolderArtworkBench {
    @Test fun measure() = runBlocking {
        Assume.assumeTrue("opt-in: B59_FOLDER_BENCH=1", System.getenv("B59_FOLDER_BENCH") == "1")
        val songs = System.getenv("B59_BENCH_SONGS")?.toInt() ?: 5000
        val pictureKb = System.getenv("B59_BENCH_PICTURE_KB")?.toInt() ?: 1024
        val label = System.getenv("B59_BENCH_LABEL") ?: "run"
        val out = File(System.getenv("B59_BENCH_OUT") ?: "../../../analysis/audit-2026-09-27/batches/B59-gates/folder-artwork-$label.txt")
        out.parentFile?.mkdirs()
        val context: Context = ApplicationProvider.getApplicationContext()
        val root = Files.createTempDirectory("b59-bench").toFile()
        val entities = ArrayList<com.lostf1sh.pixelplayeross.data.database.SongEntity>(songs)
        val bytes = ByteArrayOutputStream().also { o ->
            o.write("fLaC".toByteArray())
            fun block(type: Int, size: Int, last: Boolean = false) {
                o.write(byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()))
                o.write(ByteArray(size) { 3 })
            }
            block(0, 34); block(4, 40); block(6, pictureKb * 1024); block(1, 512, last = true)
            o.write(ByteArray(2000))
        }.toByteArray()
        for (i in 0 until songs) {
            val album = i / 12
            val folder = root.resolve("Artist ${album / 3}/[${2000 + album % 24}] Album $album").apply { mkdirs() }
            if (i % 12 == 0) folder.resolve("cover.jpg").writeBytes(ByteArray(40_000) { 21 })
            val file = folder.resolve("%02d.flac".format(i % 12 + 1)).apply { writeBytes(bytes) }
            entities += Song.emptySong().copy(id = (10_000L + i).toString(), album = "Album $album", albumId = 700L + album, path = file.path).toEntity(file.path, folder.path)
        }
        val dao = mockk<MusicDao>(relaxed = true)
        val prefs = mockk<UserPreferencesRepository>()
        val info = mockk<MusicInfoRepository>()
        val service = mockk<MusicInfoService>()
        every { prefs.folderCoversEnabledFlow } returns flowOf(true)
        coEvery { prefs.currentDirectoryRules() } returns LibraryFolders.Rules(emptySet(), emptySet())
        coEvery { dao.getAllSongsList() } returns entities
        every { info.service } returns service
        coEvery { service.cachedCover(any(), any()) } returns null
        context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE).edit().clear().commit()
        entities.forEach { AlbumArtUtils.clearCacheForSong(context, it.id) }
        val step = FolderArtworkRefresh(context, dao, prefs, info, mockk<ColorSchemeProcessor>(relaxed = true))
        val t0 = System.nanoTime()
        step.refresh()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val cached = entities.count { AlbumArtUtils.getCachedAlbumArtFile(context, it.id).length() > 0 }
        val provenanceEntries = context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE).all.keys.count { k -> k.all { it.isDigit() } }
        out.writeText(
            "B59 folder-artwork step - $label - $songs songs, every song with a ${pictureKb} KB embedded picture, a cover.jpg in every album folder\n" +
                "wall=${ms}ms  per song=${"%.3f".format(ms.toDouble() / songs)}ms\n" +
                "songs with a cached cover afterwards: $cached; provenance entries: $provenanceEntries\n" +
                "provenance commits: ${AlbumArtUtils.provenanceCommits.get()}\n",
        )
        root.deleteRecursively()
        Unit
    }
}
