package com.lostf1sh.pixelplayeross.data.metadata

import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lostf1sh.pixelplayeross.data.database.*
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real preferences, TagLib and Room, isolated from the user's library. */
@RunWith(AndroidJUnit4::class)
class YearRepairMigrationTest {
    @Test
    fun legacyNegativesRecoverYearsWithoutOverwritingValidYears() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefsName = "year_repair_migration_test"
        val prefs = context.getSharedPreferences(prefsName, 0)
        val dir = File(context.cacheDir, prefsName).apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).build()
        try {
            val tagged = File(dir, "tagged.mp3")
            val absent = File(dir, "absent.mp3")
            val failed = File(dir, "unreadable.mp3") // readYearTag returns Failed when its open fails
            val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            for ((name, file) in listOf("art-blue.mp3" to tagged, "art-red.mp3" to absent)) {
                assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            val dao = db.musicDao()
            dao.insertArtists(listOf(ArtistEntity(1, "Artist", 4)))
            dao.insertAlbums(listOf(AlbumEntity(1, "Album", "Artist", 1, null, 4, 1, 0)))
            fun row(id: Long, file: File, year: Int = 0) = SongEntity(id = id, title = "Track $id", artistName = "Artist", artistId = 1,
                albumName = "Album", albumId = 1, contentUriString = "content://test/$id", albumArtUriString = null,
                duration = 100, genre = null, filePath = file.path, parentDirectoryPath = dir.path,
                year = year, mediaStoreDateModified = 100)
            dao.insertSongs(listOf(row(1, tagged), row(2, absent), row(3, failed), row(4, tagged, 1987)))
            prefs.edit(commit = true) {
                clear()
                putStringSet("no_year_tag", setOf("1:100", "2:100", "3:100"))
                putString("unrelated", "keep")
            }
            val reads = mutableListOf<Long>()
            suspend fun scan(): YearRepairRun.Result {
                val cache = YearRepairCache(context.getSharedPreferences(prefsName, 0))
                val state = cache.load()
                return YearRepairRun.run(state.noYear, page = dao::songsWithoutYear,
                    read = { c -> reads += c.id
                        when (val r = AudioMetadataReader.readYearTag(File(c.filePath))) {
                            is AudioMetadataReader.YearTagRead.Found -> YearRepairRun.Read.Year(r.year)
                            AudioMetadataReader.YearTagRead.NoYearTag -> YearRepairRun.Read.NoYear
                            AudioMetadataReader.YearTagRead.Failed -> YearRepairRun.Read.ReadFailed
                        }
                    }, store = dao::applyYearPage, saveAttempts = { cache.save(state) }, readFailures = state.failures)
            }
            assertEquals(1, scan().filled)
            assertEquals(2001, dao.getSongByIdOnce(1)!!.year) // DATE beats ORIGINALDATE=1983
            assertEquals(1987, dao.getSongByIdOnce(4)!!.year)
            assertEquals(0, dao.fillYear(4, 2001))
            repeat(YearRepairRun.MAX_READ_FAILURES - 1) { scan() }
            val afterRetries = reads.size
            scan()
            assertEquals(afterRetries, reads.size)
            assertFalse(reads.contains(4L))
            val current = YearRepairCache(prefs).load()
            assertEquals(setOf("2:100"), current.noYear)
            assertEquals(YearRepairRun.MAX_READ_FAILURES, current.failures["3:100"])
            assertEquals("keep", prefs.getString("unrelated", null))
            assertEquals(YearRepairCache.READER_VERSION, prefs.getInt("reader_version", 0))
        } finally {
            db.close()
            context.deleteSharedPreferences(prefsName)
            dir.deleteRecursively()
        }
    }
}
