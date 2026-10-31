package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.*
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import io.mockk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B43StartJobRegressionTest {
    private fun fixture(paused: Boolean, deleteAlbum: Boolean = false) = runBlocking {
        val dir = Files.createTempDirectory("b43-start").toFile()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val context = object : ContextWrapper(app) { override fun getApplicationContext(): Context = this; override fun getFilesDir(): File = dir }
        val songs = (1..3).map { Song.emptySong().copy(id = "$it", artist = "Artist $it", album = "Album $it") }
        val music = mockk<MusicRepository>(relaxed = true)
        coEvery { music.getAllSongsOnce() } returns songs
        every { music.getSongsByIds(any()) } answers { flowOf(songs.filter { it.id in firstArg<List<String>>() }) }
        val ledger = mockk<LedgerProvider>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        mockkObject(WorkManager.Companion)
        val manager = mockk<WorkManager>(relaxed = true)
        every { WorkManager.getInstance(any<Context>()) } returns manager
        try {
            val constructor = LegacyOfflineDownloads::class.java.constructors.single { !it.isSynthetic }
            val downloads = constructor.newInstance(*constructor.parameterTypes.map { type -> when (type.name) {
                Context::class.java.name -> context
                MusicRepository::class.java.name -> music
                CoroutineScope::class.java.name -> scope
                LedgerProvider::class.java.name -> ledger
                MusicInfoRepository::class.java.name -> mockk<MusicInfoRepository>(relaxed = true)
                LyricsRepository::class.java.name -> mockk<LyricsRepository>(relaxed = true)
                else -> error("Unexpected dependency $type")
            } }.toTypedArray()) as LegacyOfflineDownloads
            downloads.start(null, setOf(OfflineKind.ARTIST_PHOTOS))
            val library = downloads.state.value!!
            downloads.record(library.id, 0, OfflineResult("first", 1, 0, 0))
            if (paused) downloads.cancel()
            downloads.start(listOf("2"), setOf(OfflineKind.ARTIST_PHOTOS))
            assertThat(downloads.state.value!!.songIds).containsExactly("2")
            if (deleteAlbum) {
                val album = downloads.state.value!!
                downloads.cancel()
                downloads.deleteJob(album.planId)
                assertThat(downloads.state.value!!.planId).isEqualTo(library.planId)
                assertThat(downloads.state.value!!.active).isEqualTo(!paused)
                verify(exactly = if (paused) 2 else 3) {
                    manager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>())
                }
            }
            val checkpoints = dir.walkTopDown().filter { it.name == "job.json" && it.readText().contains(library.planId) }
                .map { File(it.parentFile, "progress.json").readText() }.toList()
            assertThat(checkpoints.any { it.contains("\"next\":1") }).isTrue()
        } finally { scope.cancel(); unmockkObject(WorkManager.Companion); dir.deleteRecursively() }
    }
    @Test fun `deleting a paused album resumes the queued library that was running`() { fixture(false, true) }
    @Test fun `deleting a paused album leaves an explicitly paused library paused`() { fixture(true, true) }
    @Test fun `starting an album preserves the paused library progress`() { fixture(true) }
    @Test fun `starting an album while running actually queues it ahead of the library`() { fixture(false) }
}
