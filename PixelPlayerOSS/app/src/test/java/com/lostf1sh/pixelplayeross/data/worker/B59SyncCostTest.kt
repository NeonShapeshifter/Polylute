package com.lostf1sh.pixelplayeross.data.worker

import android.app.Application
import androidx.work.ListenableWorker
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.media.AudioMetadata
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.media.ComposerBackfill
import com.lostf1sh.pixelplayeross.data.media.ComposerRead
import com.lostf1sh.pixelplayeross.data.model.MusicBrainzIds
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B59 2 (#80): the other two things that made a Full Rescan slower than its tag reading, over the real worker and a synthetic library of 48 songs whose files exist.
 *
 *  - **composers**: the rescan forgets every composer and the background fill read every file again (34 s on the HONOR); the sync now keeps the composer of the tag read it already made,
 *    in the transaction that saves the songs, so there is nothing left for the fill after a Full Rescan (and after an incremental sync of the files it re-read);
 *  - **dormant**: items that are already dormant were marked dormant again on every sync (38 on the HONOR); the second sync writes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B59SyncCostTest {
    private val songs = 48
    private val root = Files.createTempDirectory("b59-sync").toFile()
    private lateinit var rig: B58SyncRig
    private lateinit var watchers: B58Observers

    @Before fun setup() = runBlocking {
        rig = B58SyncRig(songs, root = root.path)
        // the files exist (empty): the sync reads their tags through the reader the tests replace
        val cursor = rig.provider.sql.rawQuery("SELECT _data FROM audio", null)
        while (cursor.moveToNext()) File(cursor.getString(0)).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(8))
        cursor.close()
        rig.includeMusic()
        watchers = B58Observers(rig)
        watchers.start()
        watchers.settle()
    }

    @After fun close() {
        watchers.stop()
        unmockkAll()
        rig.close()
        root.deleteRecursively()
    }

    private fun metadata(composer: String?) = AudioMetadata(
        title = null, artist = null, albumArtist = null, album = null, genre = null, composer = composer, lyrics = null, durationMs = null, trackNumber = null,
        discNumber = null, year = null, bitrate = null, sampleRate = null, artwork = null,
    )

    private fun composerRows(): Map<Long, String> {
        val out = linkedMapOf<Long, String>()
        rig.db.openHelper.readableDatabase.query("SELECT song_id, composer FROM song_composers ORDER BY song_id").use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getString(1)
        }
        return out
    }

    @Test fun `after a Full Rescan every composer is known and the background fill has nothing left to read`() = runBlocking {
        mockkObject(AudioMetadataReader)
        every { AudioMetadataReader.read(any<File>(), any(), any()) } answers { metadata(if (firstArg<File>().name.startsWith("01")) null else "Composer ${firstArg<File>().parentFile!!.name}") }
        every { AudioMetadataReader.readScanTags(any()) } returns (MusicBrainzIds() to ComposerRead.Unreadable)
        assertThat(rig.sync("FULL", deep = true)).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(rig.dao.countSongsNeedingComposer()).isEqualTo(0)
        val rows = composerRows()
        assertThat(rows).hasSize(songs)
        // a file without the tag is recorded as "none" (empty), a file with it carries its composer
        assertThat(rows.values.count { it.isEmpty() }).isEqualTo(songs / 12) // the first track of each album
        assertThat(rows.values.filter { it.isNotEmpty() }.all { it.startsWith("Composer ") }).isTrue()
        // the fill finds nothing: it reads no file
        var reads = 0
        val backfill = ComposerBackfill(rig.dao, read = { reads++; ComposerRead.None })
        assertThat(backfill.runOnce()).isEqualTo(0)
        assertThat(reads).isEqualTo(0)
    }

    @Test fun `an incremental sync keeps the composer of the tag read it makes for the files it re-reads`() = runBlocking {
        mockkObject(AudioMetadataReader)
        every { AudioMetadataReader.read(any<File>(), any(), any()) } returns metadata(null)
        every { AudioMetadataReader.readScanTags(any()) } answers {
            val name = firstArg<File>().name
            MusicBrainzIds() to when {
                name.startsWith("02") -> ComposerRead.None
                name.startsWith("03") -> ComposerRead.Unreadable // not known: left to the fill
                else -> ComposerRead.Found("  Bach  ")
            }
        }
        assertThat(rig.sync("INCREMENTAL", deep = false)).isInstanceOf(ListenableWorker.Result.Success::class.java)
        val rows = composerRows()
        assertThat(rows.values.count { it == "Bach" }).isEqualTo(songs - 2 * (songs / 12))
        assertThat(rows.values.count { it.isEmpty() }).isEqualTo(songs / 12)
        // only the unreadable ones are left for the fill
        assertThat(rig.dao.countSongsNeedingComposer()).isEqualTo(songs / 12)
    }

    @Test fun `a second sync does not mark the already dormant items dormant again`() = runBlocking {
        mockkObject(AudioMetadataReader)
        every { AudioMetadataReader.read(any<File>(), any(), any()) } returns metadata(null)
        every { AudioMetadataReader.readScanTags(any()) } returns (MusicBrainzIds() to ComposerRead.None)
        assertThat(rig.sync("INCREMENTAL", deep = false)).isInstanceOf(ListenableWorker.Result.Success::class.java)
        // the files of one album leave the library (a card taken out): their ids disappear from MediaStore
        rig.provider.sql.execSQL("DELETE FROM audio WHERE _id < ${1000 + 12}")
        watchers.resetCounts()
        assertThat(rig.sync("INCREMENTAL", deep = false)).isInstanceOf(ListenableWorker.Result.Success::class.java)
        watchers.settle()
        assertThat(rig.lines.any { it.startsWith("Marked 12 library items dormant") }).isTrue()
        assertThat(rig.dao.dormantLibraryItemCount()).isEqualTo(12)
        // the same 12 are absent again: nothing is written, nothing is invalidated, nothing is said
        watchers.resetCounts()
        assertThat(rig.sync("INCREMENTAL", deep = false)).isInstanceOf(ListenableWorker.Result.Success::class.java)
        watchers.settle()
        assertThat(rig.lines.none { it.startsWith("Marked ") && it.contains("dormant") }).isTrue()
        assertThat(rig.phaseMs()).containsKey("dormant")
        assertThat(rig.lines.single { it.startsWith("sync phase=dormant") }).contains("items=0")
        assertThat(watchers.invalidations.get()).isEqualTo(0)
        assertThat(rig.dao.dormantLibraryItemCount()).isEqualTo(12)
    }
}
