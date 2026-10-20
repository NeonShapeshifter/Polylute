package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.backup.BackupManager
import com.lostf1sh.pixelplayeross.data.backup.model.BackupManifest
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.RestorePlan
import com.lostf1sh.pixelplayeross.data.backup.model.RestoreResult
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** B35 (RT-02): a restore that is cancelled with its scope leaves no transfer on the page; one that ends normally still consumes its plan. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreProgressTest {

    private val scheduler = TestCoroutineScheduler()
    private val uri: Uri = mockk(relaxed = true)
    private val manager = mockk<BackupManager>(relaxed = true)

    private val plan = RestorePlan(
        manifest = BackupManifest(),
        backupUri = "backup",
        availableModules = setOf(BackupSection.FAVORITES),
        selectedModules = setOf(BackupSection.FAVORITES),
        moduleDetails = emptyMap(),
    )

    @BeforeEach
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @AfterEach
    fun reset() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): BackupSettingsViewModel {
        every { manager.getBackupHistory() } returns flowOf(emptyList())
        coEvery { manager.inspectBackup(any(), any()) } returns Result.success(plan)
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.backupInfoDismissedFlow } returns flowOf(false)
        return BackupSettingsViewModel(prefs, mockk(relaxed = true), manager, mockk(relaxed = true)).also {
            it.inspectBackupFile(uri)
            assertNotNull(it.uiState.value.restorePlan)
        }
    }

    @Test
    fun `a restore cancelled with the scope leaves no transfer showing and keeps its plan`() {
        val running = CompletableDeferred<Unit>()
        coEvery { manager.restore(any(), any(), any()) } coAnswers { running.complete(Unit); awaitCancellation() }
        val vm = viewModel()
        vm.restoreFromPlan(uri)
        assertTrue(running.isCompleted)
        assertTrue(vm.uiState.value.isDataTransferInProgress)
        assertNotNull(vm.dataTransferProgress.value)

        vm.viewModelScope.cancel()                                          // what clearing the ViewModel does

        assertFalse(vm.uiState.value.isDataTransferInProgress)
        assertNull(vm.dataTransferProgress.value)
        assertNotNull(vm.uiState.value.restorePlan)                         // the restore did not run to its end: the plan is not consumed
    }

    @Test
    fun `a restore that ends normally still clears the transfer and consumes its plan`() {
        coEvery { manager.restore(any(), any(), any()) } returns RestoreResult.Success
        val vm = viewModel()

        vm.restoreFromPlan(uri)
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()

        assertFalse(vm.uiState.value.isDataTransferInProgress)
        assertNull(vm.dataTransferProgress.value)
        assertNull(vm.uiState.value.restorePlan)
    }
}
