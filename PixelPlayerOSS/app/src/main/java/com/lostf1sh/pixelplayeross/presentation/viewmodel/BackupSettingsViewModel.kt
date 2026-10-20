package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.backup.BackupManager
import com.lostf1sh.pixelplayeross.data.backup.format.BackupEncryptedException
import com.lostf1sh.pixelplayeross.data.backup.format.BackupWrongPassphraseException
import com.lostf1sh.pixelplayeross.data.backup.model.BackupHistoryEntry
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessage
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessageException
import com.lostf1sh.pixelplayeross.data.backup.model.BackupOperationType
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.model.BackupTransferProgressUpdate
import com.lostf1sh.pixelplayeross.data.backup.model.RestoreResult
import com.lostf1sh.pixelplayeross.data.backup.model.resolve
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.worker.SyncManager
import com.lostf1sh.pixelplayeross.presentation.settings.BackupSettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Backup & Restore page's state and the work behind it: export, inspect, restore, history.
 *
 * B21: split out of `SettingsViewModel`, which carried it beside forty unrelated preferences. Nothing about a
 * backup is shared with another category, and nothing outside this page reads it, so it is its own owner.
 * The behaviour of every function is the one `SettingsViewModel` had.
 */
@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val syncManager: SyncManager,
    private val backupManager: BackupManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupSettingsState())
    val uiState: StateFlow<BackupSettingsState> = _uiState.asStateFlow()

    private val _dataTransferEvents = Channel<String>(Channel.BUFFERED)
    val dataTransferEvents: Flow<String> = _dataTransferEvents.receiveAsFlow()

    private val _dataTransferProgress = MutableStateFlow<BackupTransferProgressUpdate?>(null)
    val dataTransferProgress: StateFlow<BackupTransferProgressUpdate?> = _dataTransferProgress.asStateFlow()

    init {
        viewModelScope.launch {
            backupManager.getBackupHistory().collect { history ->
                _uiState.update { it.copy(backupHistory = history.toImmutableList()) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.backupInfoDismissedFlow.collect { dismissed ->
                _uiState.update { it.copy(infoDismissed = dismissed) }
            }
        }
    }

    fun setBackupInfoDismissed(dismissed: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setBackupInfoDismissed(dismissed)
        }
    }


    fun exportAppData(uri: Uri, sections: Set<BackupSection>, passphrase: String? = null) {
        if (sections.isEmpty() || _uiState.value.isDataTransferInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDataTransferInProgress = true) }
            _dataTransferProgress.value = BackupTransferProgressUpdate(
                operation = BackupOperationType.EXPORT,
                step = 0,
                totalSteps = 1,
                title = BackupMessage.of(R.string.backup_progress_preparing_backup),
                detail = BackupMessage.of(R.string.backup_progress_starting_backup_task),
            )
            val result = backupManager.export(uri, sections, passphrase) { progress ->
                _dataTransferProgress.value = progress
            }
            result.fold(
                onSuccess = { _dataTransferEvents.send(context.getString(R.string.data_exported_successfully)) },
                onFailure = {
                    _dataTransferEvents.send(
                        context.getString(
                            R.string.export_failed_format,
                            it.localizedMessage
                                ?: BackupMessageException.messageOf(it)?.resolve(context)
                                ?: context.getString(R.string.error_unknown),
                        ),
                    )
                },
            )
            delay(300)
            _uiState.update { it.copy(isDataTransferInProgress = false) }
            _dataTransferProgress.value = null
        }
    }

    fun inspectBackupFile(uri: Uri, passphrase: String? = null) {
        if (_uiState.value.isInspectingBackup) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isInspectingBackup = true,
                    restorePlan = null,
                    wrongBackupPassword = false
                )
            }
            val result = backupManager.inspectBackup(uri, passphrase)
            result.fold(
                onSuccess = { plan ->
                    _uiState.update {
                        it.copy(
                            restorePlan = plan,
                            isInspectingBackup = false,
                            pendingEncryptedBackupUri = null,
                            wrongBackupPassword = false
                        )
                    }
                },
                onFailure = { error ->
                    when (error) {
                        is BackupEncryptedException -> _uiState.update {
                            it.copy(isInspectingBackup = false, pendingEncryptedBackupUri = uri.toString())
                        }
                        is BackupWrongPassphraseException -> _uiState.update {
                            it.copy(
                                isInspectingBackup = false,
                                pendingEncryptedBackupUri = uri.toString(),
                                wrongBackupPassword = true
                            )
                        }
                        else -> {
                            _dataTransferEvents.send(
                                context.getString(
                                    R.string.backup_invalid_format,
                                    error.localizedMessage ?: context.getString(R.string.error_unknown),
                                ),
                            )
                            _uiState.update { it.copy(isInspectingBackup = false) }
                        }
                    }
                }
            )
        }
    }

    /** Called when the user dismisses the encrypted-backup password prompt. */
    fun dismissEncryptedBackupPrompt() {
        _uiState.update {
            it.copy(
                pendingEncryptedBackupUri = null,
                wrongBackupPassword = false
            )
        }
    }

    fun updateRestorePlanSelection(selectedModules: Set<BackupSection>) {
        _uiState.update { state ->
            state.restorePlan?.let { plan ->
                state.copy(restorePlan = plan.copy(selectedModules = selectedModules))
            } ?: state
        }
    }

    fun restoreFromPlan(uri: Uri) {
        val plan = _uiState.value.restorePlan ?: return
        if (plan.selectedModules.isEmpty() || _uiState.value.isDataTransferInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDataTransferInProgress = true) }
            _dataTransferProgress.value = BackupTransferProgressUpdate(
                operation = BackupOperationType.IMPORT,
                step = 0,
                totalSteps = 1,
                title = BackupMessage.of(R.string.backup_progress_preparing_restore),
                detail = BackupMessage.of(R.string.backup_progress_starting_task),
            )
            var finished = false
            try {
                val result = backupManager.restore(uri, plan) { progress ->
                    _dataTransferProgress.value = progress
                }
                when (result) {
                    is RestoreResult.Success -> {
                        _dataTransferEvents.send(context.getString(R.string.data_restored_successfully))
                        syncManager.sync()
                    }
                    is RestoreResult.PartialFailure -> {
                        val failedNames = result.failed.entries.joinToString { (section, reason) ->
                            "${context.getString(section.labelRes)}: ${reason.resolve(context)}"
                        }
                        _dataTransferEvents.send(
                            context.getString(R.string.restore_partial_unresolved_format, failedNames),
                        )
                        if (result.succeeded.isNotEmpty() || !result.rolledBack) {
                            syncManager.sync()
                        }
                    }
                    is RestoreResult.TotalFailure -> {
                        _dataTransferEvents.send(
                            context.getString(
                                R.string.restore_failed_format,
                                result.error.resolve(context),
                            ),
                        )
                    }
                }
                delay(300)
                finished = true
            } finally {
                // B35 (RT-02): a restore that is cancelled rolls back and then lets the cancellation through; the page must not be
                // left showing a transfer that is no longer running. The plan is consumed only by a restore that ran to its end.
                _uiState.update { it.copy(isDataTransferInProgress = false, restorePlan = if (finished) null else it.restorePlan) }
                _dataTransferProgress.value = null
            }
        }
    }

    fun clearRestorePlan() {
        _uiState.value.restorePlan?.backupUri?.let { planUri ->
            runCatching { backupManager.discardDecryptedBackup(Uri.parse(planUri)) }
        }
        _uiState.update {
            it.copy(
                restorePlan = null,
                pendingEncryptedBackupUri = null,
                wrongBackupPassword = false
            )
        }
    }

    fun removeBackupHistoryEntry(entry: BackupHistoryEntry) {
        viewModelScope.launch {
            backupManager.removeBackupHistoryEntry(entry.uri)
        }
    }
}
