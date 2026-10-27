package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.library.LibraryFolderFinder
import com.lostf1sh.pixelplayeross.data.library.LibraryFolderService
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.worker.SyncManager
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.utils.LogUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val syncManager: SyncManager,
    musicRepository: MusicRepository,
    userPreferencesRepository: UserPreferencesRepository,
    private val libraryFolders: LibraryFolderService,
) : ViewModel() {

    private val _folderProposal = kotlinx.coroutines.flow.MutableStateFlow<FolderProposalState?>(null)

    /**
     * B39-A9: a first run that found music: the folders it holds, already chosen, for the listener to confirm in one tap. Null when there is
     * nothing to ask (the library has its rules).
     */
    val folderProposal: StateFlow<FolderProposalState?> = _folderProposal

    val isSetupComplete: StateFlow<Boolean?> = userPreferencesRepository.initialSetupDoneFlow
        .map { it as Boolean? }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val hasCompletedInitialSync: StateFlow<Boolean> = userPreferencesRepository.lastSyncTimestampFlow
        .map { it > 0L }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true
        )

    /**
     * A Flow that emits `true` if the SyncWorker is queued or running.
     * Ideal for showing a loading indicator.
     */
    val isSyncing: StateFlow<Boolean> = syncManager.isSyncing
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    /**
     * Flow that exposes detailed sync progress including file count and phase.
     */
    val syncProgress: StateFlow<SyncProgress> = syncManager.syncProgress
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SyncProgress()
        )

    /**
     * Emits once each time a library sync ends in failure, so the UI can surface a
     * one-shot toast (the progress flow alone silently reverts to idle on failure).
     */
    val syncFailed: Flow<Unit> = syncManager.syncFailed

    /**
     * A Flow that emits `true` if the Room database has no songs.
     * Helps us know whether this is the first time the app is opened.
     */
    val isLibraryEmpty: StateFlow<Boolean> = musicRepository
        .getAudioFiles()
        .map { it.isEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /**
     * Function to start syncing the music library.
     * Should be called after permissions have been granted.
     */
    fun startSync() {
        LogUtils.i(this, "startSync called")
        viewModelScope.launch {
            if (isSetupComplete.value == true) {
                // B39-A9: before the first sync of a run, an install of 1.0 is moved to the new rule (and keeps its library), and a first run
                // that found music asks which folders to include, already chosen, instead of taking everything on the phone.
                when (val first = libraryFolders.finder.firstRun()) {
                    LibraryFolderFinder.FirstRun.Sync -> syncManager.sync()
                    is LibraryFolderFinder.FirstRun.Propose -> if (_folderProposal.value == null) {
                        _folderProposal.value = FolderProposalState(first.folders)
                    }
                }
            }
        }
    }

    fun toggleProposedFolder(path: String) {
        _folderProposal.value = _folderProposal.value?.toggle(path)
    }

    /** "Add": the chosen folders go in the library, and the sync that was waiting for the answer runs. */
    fun addProposedFolders() {
        val proposal = _folderProposal.value ?: return
        _folderProposal.value = null
        viewModelScope.launch {
            when (libraryFolders.finder.add(proposal.chosen)) {
                LibraryFolderFinder.AddResult.ADDED -> syncManager.forceRefresh()
                LibraryFolderFinder.AddResult.NOT_READY -> _folderSearchNotices.tryEmit(FolderSearchNotice.NOT_READY)
                LibraryFolderFinder.AddResult.NOTHING -> Unit
            }
        }
    }

    private val _folderSearchNotices = MutableSharedFlow<FolderSearchNotice>(extraBufferCapacity = 4)

    /** What the search tells the listener outside the dialog (the host shows it as a toast). */
    val folderSearchNotices: SharedFlow<FolderSearchNotice> = _folderSearchNotices.asSharedFlow()

    private var folderSearchJob: Job? = null

    /** The empty library's way back (the same scan as "Find folders with music" in Settings, Library): offers the folders that hold music. */
    fun findFolders() {
        if (folderSearchJob?.isActive == true) return
        folderSearchJob = viewModelScope.launch {
            _folderSearchNotices.tryEmit(FolderSearchNotice.SEARCHING)
            val found = libraryFolders.finder.scan()
            if (found.isEmpty()) _folderSearchNotices.tryEmit(FolderSearchNotice.NONE_FOUND)
            else _folderProposal.value = FolderProposalState(found)
        }
    }

    /** "Not now": nothing is added and no sync runs; it is not asked again by itself: the empty Songs tab and Settings, Library, Find folders with music are the way back. */
    fun dismissProposedFolders() {
        _folderProposal.value = null
    }

    /** Re-runs the library sync after a failure (e.g. from a retry affordance). */
    fun retrySync() {
        viewModelScope.launch { syncManager.sync() }
    }
}
