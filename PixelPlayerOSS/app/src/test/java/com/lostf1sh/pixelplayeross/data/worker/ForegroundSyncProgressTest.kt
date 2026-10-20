package com.lostf1sh.pixelplayeross.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.coEvery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.work.WorkInfo
import androidx.work.workDataOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ForegroundSyncProgressTest {
    private fun work(state: WorkInfo.State, attempt: Int = 0) = mockk<WorkInfo> {
        every { id } returns UUID.randomUUID()
        every { this@mockk.state } returns state
        every { runAttemptCount } returns attempt
        every { outputData } returns workDataOf(SyncWorker.OUTPUT_TOTAL_SONGS to 4915)
    }
    @Test fun replacement_does_not_inherit_old_success_before_enqueue_or_after_failure() {
        val old = work(WorkInfo.State.SUCCEEDED)
        val next = work(WorkInfo.State.FAILED)
        assertNull(selectForegroundSyncWork(listOf(old), next.id))
        assertFalse(foregroundSyncProgress(selectForegroundSyncWork(listOf(old, next), next.id)).isCompleted)
        val pending = work(WorkInfo.State.ENQUEUED)
        assertTrue(foregroundSyncProgress(selectForegroundSyncWork(listOf(old, pending), pending.id)).isRunning)
    }
    @Test fun keep_observes_existing_work_and_deferral_does_not_announce_completion() {
        val old = work(WorkInfo.State.SUCCEEDED)
        val retry = work(WorkInfo.State.ENQUEUED, 1)
        assertSame(retry, selectForegroundSyncWork(listOf(old, retry), null))
        assertFalse(foregroundSyncProgress(retry).isCompleted)
        assertFalse(foregroundSyncProgress(retry).isRunning)
    }
    @Test fun real_workmanager_coalesces_keep_and_a_failed_replacement_cannot_show_old_success() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var resultGate = CompletableDeferred<ListenableWorker.Result>()
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                val thisGate = resultGate
                return object : CoroutineWorker(appContext, workerParameters) {
                    override suspend fun doWork(): Result { starts.incrementAndGet(); return thisGate.await() }
                }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        val preferences = mockk<UserPreferencesRepository>(relaxed = true)
        // A preferences read that takes a moment, as a real DataStore read can: `sync()` then enqueues its KEEP request well after the first run
        // has started, which is the order the test must hold whatever the timing.
        coEvery { preferences.getLastSyncTimestamp() } coAnswers { delay(300); 0L }
        val workManager = WorkManager.getInstance(context)
        // `sync()` enqueues its KEEP request from a coroutine of its own, so when WorkManager has accepted or rejected it is not something
        // the test can see from outside. The manager is given a WorkManager that hands over the Operation of each KEEP request it is
        // asked for; its `result` completes when WorkManager has decided, which is the point this test is about.
        val keepOperations = java.util.concurrent.LinkedBlockingQueue<Operation>()
        val watched = spyk(workManager)
        every { watched.enqueueUniqueWork(any<String>(), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) } answers {
            callOriginal().also { keepOperations.put(it) }
        }
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns watched
        val manager = SyncManager(context, preferences, mockk(relaxed = true))
        val progress = java.util.concurrent.CopyOnWriteArrayList<SyncProgress>()
        val observer = launch(Dispatchers.IO) { manager.syncProgress.collect { progress += it } }
        suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }
        try {
            manager.forceRefresh()
            await { starts.get() == 1 }
            manager.sync() // KEEP coalesces into the run above...
            // ...once WorkManager has decided on it, and not before: a run that ends first leaves nothing for it to coalesce into.
            val keep = withTimeout(10_000) { while (keepOperations.isEmpty()) delay(10); keepOperations.peek()!! }
            withContext(Dispatchers.IO) { keep.result.get() }
            await { progress.lastOrNull()?.isRunning == true }
            resultGate.complete(ListenableWorker.Result.success(workDataOf(SyncWorker.OUTPUT_TOTAL_SONGS to 4869)))
            await { progress.lastOrNull()?.isCompleted == true }
            assertEquals(4869, progress.last().totalCount)
            assertEquals(1, starts.get())
            resultGate = CompletableDeferred()
            val failure = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { manager.syncFailed.first() }
            manager.forceRefresh()
            await { starts.get() == 2 }
            await { progress.lastOrNull()?.isCompleted == false }
            resultGate.complete(ListenableWorker.Result.failure())
            withTimeout(10_000) { failure.await() }
            assertFalse(progress.last().isCompleted)
        } finally {
            unmockkObject(WorkManager.Companion)
            workManager.cancelAllWork().result.get()
            observer.cancelAndJoin()
        }
    }

    @Test fun completion_is_exact_global_count_and_cancelled_work_is_idle() {
        assertEquals(4915, foregroundSyncProgress(work(WorkInfo.State.SUCCEEDED)).totalCount)
        assertEquals(SyncProgress(), foregroundSyncProgress(work(WorkInfo.State.CANCELLED)))
    }
}
