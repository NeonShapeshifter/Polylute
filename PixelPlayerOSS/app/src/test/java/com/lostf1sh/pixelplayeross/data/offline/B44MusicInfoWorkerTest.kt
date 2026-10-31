package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B44MusicInfoWorkerTest {
    @Test fun `discovery yields after its time budget and preserves the next target for its continuation`() = runBlocking {
        val dao = mockk<MusicInfoDiscoveryDao>(relaxed = true)
        val downloads = mockk<MusicInfoDownloads>(relaxed = true)
        every { downloads.dao } returns dao
        val first = InfoDiscovery("one", "PHOTO", "Artist one", "one", "Artist one")
        val second = first.copy(key = "two", title = "Artist two")
        var next = first
        coEvery { dao.nextCheck() } answers { next }
        coEvery { downloads.checkOne(first) } coAnswers {
            next = second
            ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        }
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.id } returns UUID.randomUUID()
        val worker = MusicInfoFindWorker(ApplicationProvider.getApplicationContext<Context>(), params, downloads)
        worker.doWork()
        assertThat(next).isEqualTo(second)
        coVerify(exactly = 1) { downloads.checkOne(first) }
        coVerify(exactly = 0) { downloads.checkOne(second) }
        verify(exactly = 1) { downloads.enqueueFind() }
    }

    @Test fun `a worker that restarts while paused neither downloads nor schedules another queue`() = runBlocking {
        val dao = mockk<MusicInfoDiscoveryDao>(relaxed = true)
        val downloads = mockk<MusicInfoDownloads>(relaxed = true)
        every { downloads.dao } returns dao
        coEvery { dao.queueOnce() } returns InfoQueue(paused = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.id } returns UUID.randomUUID()
        MusicInfoQueueWorker(ApplicationProvider.getApplicationContext<Context>(), params, downloads).doWork()
        coVerify(exactly = 0) { downloads.downloadOne(any()) }
        verify(exactly = 0) { downloads.enqueueDownloads(any()) }
        Unit
    }
}
