package com.lostf1sh.pixelplayeross.data.worker

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.presentation.lockscreen.CoverSignal
import com.lostf1sh.pixelplayeross.presentation.lockscreen.FreezeWhileCovered
import com.lostf1sh.pixelplayeross.presentation.screens.RefreshLibraryItem
import com.lostf1sh.pixelplayeross.presentation.settings.libraryStateFlow
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B39-A1 (Roderick's bug): the Library settings page kept "Saving to the database 0% (0/26)", with "Update library" and "Full rescan"
 * greyed out, long after the sync had succeeded (HONOR, 2026-10-22 23:25: the sync ran 41.06 to 43.85 and the page stayed on its
 * running snapshot for more than a minute, until another sync ran).
 *
 * **What is confirmed.** The sync streams were shared with `replay = 1` and `WhileSubscribed(5 s)`, whose replay cache is never emptied: when
 * nobody listens for five seconds the upstream stops *and the last value stays*. The screen had been off for five minutes, so every
 * collector of the page was stopped (the app is held at CREATED while the lock screen covers it), and a sync that was still running when
 * the page stopped listening had its running snapshot kept. The page that listened again was handed it first (the first test, which is the
 * same sequence against the real `SyncManager` and the real WorkManager: **fails before**, the first reading is the old running one), and
 * everything that guards on it (`SettingsViewModel.refreshLibrary` reads it as "a sync is running" and returns) acts on it until the
 * pipeline's own first reading replaces it. The streams now forget what they last saw when they stop, and the two halves (running, how
 * far) are one reading.
 *
 * **What is not.** Why the replacement did not arrive for a minute on the HONOR was not reproduced on the host (WorkManager's real
 * scheduler, the debug build); every sequence run here (page covered, sheet open, KEEP after an earlier sync) converges. It is said in the
 * result document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LibrarySyncStateTest {
    @get:Rule val compose = createComposeRule()
    private val showSheet = mutableStateOf(false)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var gate = CompletableDeferred<ListenableWorker.Result>()

    private val cleanup = mutableListOf<() -> Unit>()

    @After
    fun finish() {
        cleanup.reversed().forEach { runCatching { it() } }
    }

    /** A WorkManager whose sync worker reports "Saving 0/26" and then waits for [gate], and a real [SyncManager] over it. */
    private fun syncManager(): SyncManager {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                val thisGate = gate
                return object : CoroutineWorker(appContext, workerParameters) {
                    override suspend fun doWork(): Result {
                        setProgress(
                            workDataOf(
                                SyncWorker.PROGRESS_PHASE to SyncProgress.SyncPhase.SAVING_TO_DATABASE.ordinal,
                                SyncWorker.PROGRESS_CURRENT to 0,
                                SyncWorker.PROGRESS_TOTAL to 26,
                            ),
                        )
                        return thisGate.await()
                    }
                }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
        cleanup += { WorkManager.getInstance(context).cancelAllWork().result.get() }
        return SyncManager(context, mockk<UserPreferencesRepository>(relaxed = true), mockk(relaxed = true))
    }

    private fun finishTheSync() {
        gate.complete(ListenableWorker.Result.success(workDataOf(SyncWorker.OUTPUT_TOTAL_SONGS to 4934)))
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(20) }

    @Test
    fun a_page_that_listens_again_after_the_sync_ended_is_never_handed_the_running_snapshot_it_last_saw() = runBlocking {
        val manager = syncManager()
        val seenBefore = CopyOnWriteArrayList<SyncState>()
        val covered = launch(Dispatchers.IO) { manager.syncState.collect { seenBefore += it } }
        manager.forceRefresh()
        await { seenBefore.lastOrNull()?.let { it.isSyncing && it.progress.totalCount == 26 } == true }

        // The page stops listening (screen off, another page on top) and the sync is still running; the streams stop after their timeout.
        covered.cancelAndJoin()
        delay(SyncManager.SYNC_STOP_TIMEOUT_MS + 1_500)
        finishTheSync()
        delay(500)

        // The page listens again: what it is handed must be a reading taken since, never the snapshot the streams kept.
        val seenAfter = CopyOnWriteArrayList<SyncState>()
        val back = launch(Dispatchers.IO) { manager.syncState.collect { seenAfter += it } }
        await { seenAfter.isNotEmpty() }
        delay(300)
        back.cancelAndJoin()

        assertFalse("handed the running snapshot of a sync that had ended: $seenAfter", seenAfter.any { it.isSyncing })
        assertTrue(seenAfter.last().progress.isCompleted)
        assertEquals(4934, seenAfter.last().progress.totalCount)
    }

    @Test
    fun running_and_how_far_are_one_reading_through_a_whole_sync() = runBlocking {
        val manager = syncManager()
        val seen = CopyOnWriteArrayList<SyncState>()
        val listener = launch(Dispatchers.IO) { manager.syncState.collect { seen += it } }
        manager.forceRefresh()
        await { seen.lastOrNull()?.let { it.isSyncing && it.progress.totalCount == 26 } == true }
        finishTheSync()
        await { seen.lastOrNull()?.progress?.isCompleted == true }
        listener.cancelAndJoin()

        for (state in seen) assertEquals("a reading that disagrees with itself: $state", state.isSyncing, state.progress.isRunning)
        assertFalse(seen.last().isSyncing)
    }

    @Test
    fun the_page_enables_its_actions_when_the_sync_ends_behind_an_open_sheet_and_a_covered_screen() {
        val manager = syncManager()
        // The view model's scope is Main.immediate; Robolectric's paused main looper does not run a 5 s delay in real time, so the streams'
        // own timeouts would never elapse here. Default runs them on the wall clock, which is what the sleeps below wait for.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        cleanup += { scope.cancel() }
        val libraryState = libraryStateFlow(MutableStateFlow(SettingsUiState()), manager.syncState, scope, SettingsUiState())
        val cover = CoverSignal()
        val update = context.getString(R.string.setcat_sync_update_library)
        val rescan = context.getString(R.string.presentation_batch_f_full_rescan)

        compose.setContent {
            FreezeWhileCovered(frozen = false, hidden = false, cover = cover) {
                val state by libraryState.collectAsStateWithLifecycle()
                Column {
                    RefreshLibraryItem(
                        isSyncing = state.isSyncing,
                        syncProgress = state.syncProgress,
                        onUpdateLibrary = {},
                        onFullRescan = {},
                    )
                    Text(if (state.isSyncing) "page: syncing" else "page: idle")
                }
                // The "Excluded folders" sheet is a Dialog of its own over the page.
                if (showSheet.value) Dialog(onDismissRequest = { showSheet.value = false }) { Text("sheet") }
            }
        }
        compose.onNodeWithText(update).assertHasClickAction()

        manager.forceRefresh()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("page: syncing")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(update).assertHasNoClickAction()
        compose.onNodeWithText(rescan).assertHasNoClickAction()

        // The sheet opens and the screen goes off; the page stops listening and the streams stop. The sync ends in the meantime.
        showSheet.value = true
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("sheet")).fetchSemanticsNodes().isNotEmpty() }
        cover.covered = true
        compose.waitForIdle()
        Thread.sleep(SyncManager.SYNC_STOP_TIMEOUT_MS + 1_500)
        finishTheSync()
        Thread.sleep(500)
        // What the page's state holds while nobody listens must not claim a sync is running (it is what `refreshLibrary` guards on).
        assertFalse("held while nobody listens: ${libraryState.value}", libraryState.value.isSyncing)

        // The screen is on again.
        cover.covered = false
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("page: idle")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(update).assertHasClickAction()
        compose.onNodeWithText(rescan).assertHasClickAction()
        assertFalse(libraryState.value.isSyncing)
        assertTrue(libraryState.value.syncProgress.isCompleted)
    }
}
