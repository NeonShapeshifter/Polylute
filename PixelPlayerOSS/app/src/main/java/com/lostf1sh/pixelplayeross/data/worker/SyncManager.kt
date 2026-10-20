package com.lostf1sh.pixelplayeross.data.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import com.lostf1sh.pixelplayeross.data.observer.MediaStoreObserver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data class representing the progress of the sync operation.
 */
data class SyncProgress(
    val isRunning: Boolean = false,
    val currentCount: Int = 0,
    val totalCount: Int = 0,
    val isCompleted: Boolean = false,
    val phase: SyncPhase = SyncPhase.IDLE
) {
    enum class SyncPhase {
        IDLE,
        FETCHING_MEDIASTORE,
        PROCESSING_FILES,
        SAVING_TO_DATABASE,
        SCANNING_LRC,
        CLEANING_CACHE,
        COMPLETING
    }

    val progress: Float
        get() = if (totalCount > 0) currentCount.toFloat() / totalCount else 0f

    val hasProgress: Boolean
        get() = totalCount > 0
    }

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val mediaStoreObserver: MediaStoreObserver
) {
    private val workManager = WorkManager.getInstance(context)
    private val sharingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mediaStoreAutoSyncJob: Job? = null
    private val autoSyncLock = Any()
    private val currentSyncWorkId = MutableStateFlow<UUID?>(null)
    private val syncRequestGeneration = AtomicLong(0L)

    // B23-P1: the catch-up waits out the burst of a wake instead of starting inside it.
    private val foregroundCatchUp = ForegroundCatchUp(
        scope = sharingScope,
        cooldownMs = FOREGROUND_SYNC_COOLDOWN_MS,
        now = System::currentTimeMillis,
        initialSetupDone = { userPreferencesRepository.initialSetupDoneFlow.first() },
        enqueueIncrementalSync = {
            enqueueSyncWork(
                request = SyncWorker.incrementalSyncWork(runMaintenance = false),
                policy = ExistingWorkPolicy.KEEP,
                notifyObserver = false
            )
        },
        log = { message -> Timber.tag(TAG).i(message) },
    )

    val isSyncing: Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
            .map { workInfos ->
                val isRunning = workInfos.any { it.state == WorkInfo.State.RUNNING }
                val isFreshlyEnqueued = workInfos.any {
                    it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0
                }
                isRunning || isFreshlyEnqueued
            }
            .distinctUntilChanged()
            .shareIn(
                scope = sharingScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                replay = 1
            )

    /**
     * Emits once each time the library sync finishes in a FAILED state with no other
     * run still active. Used to surface a one-shot "sync failed" message to the user,
     * which the [syncProgress] flow alone cannot do (it silently reverts to idle).
     */
    val syncFailed: Flow<Unit> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
            .combine(currentSyncWorkId) { workInfos, requestedId ->
                trackForegroundSyncWork(workInfos, requestedId)?.state == WorkInfo.State.FAILED
            }
            .distinctUntilChanged()
            .filter { it }
            .map { }
            .shareIn(sharingScope, SharingStarted.WhileSubscribed(5_000), replay = 0)

    private val started = AtomicBoolean(false)

    /**
     * Starts background observation (storage-change auto-sync, app-foreground catch-up)
     * and schedules periodic maintenance. Called once from PixelPlayerApplication.onCreate
     * so the launch site is explicit and greppable instead of hidden in DI construction.
     * Idempotent — extra calls are no-ops.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        observeStorageChanges()
        observeAppForeground()
        schedulePeriodicMaintenance()
    }

    /**
     * Schedules the once-a-day heavy maintenance: a full local rescan plus LRC scanning and
     * artwork-cache cleaning, constrained to charging. Uses a dedicated unique name distinct
     * from [SyncWorker.WORK_NAME], so it never drives the foreground sync indicator, and it
     * asks for no network. KEEP preserves the existing schedule across launches.
     */
    private fun schedulePeriodicMaintenance() {
        workManager.enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_MAINTENANCE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            SyncWorker.periodicMaintenanceWork()
        )
    }

    /**
     * Flow that exposes the detailed sync progress including song count.
     */
    val syncProgress: Flow<SyncProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
            .combine(currentSyncWorkId) { workInfos, requestedId ->
                foregroundSyncProgress(trackForegroundSyncWork(workInfos, requestedId))
            }
            .distinctUntilChanged()
            .shareIn(
                scope = sharingScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                replay = 1
            )

    /**
     * Emits `true` while the worker is in the early "library changes" phases —
     * scanning MediaStore for added/removed/modified files and writing them to the
     * unified DB. This is what powers the pull-to-refresh indicator: the UI only
     * needs to confirm that local additions/deletions have landed.
     */
    val isFetchingChanges: Flow<Boolean> = syncProgress
        .map { progress ->
            progress.isRunning && progress.phase in CHANGE_PHASES
        }
        .distinctUntilChanged()
        .shareIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            replay = 1
        )

    /**
     * Emits `true` while the worker is performing background maintenance that does
     * not gate the user's pull-to-refresh gesture: LRC scanning, album-art cache
     * cleanup, and local library reconciliation. This drives the slim linear
     * indicator under [LibraryActionRow].
     */
    val isPerformingMaintenance: Flow<Boolean> = syncProgress
        .map { progress ->
            progress.isRunning && progress.phase in MAINTENANCE_PHASES
        }
        .distinctUntilChanged()
        .shareIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            replay = 1
        )

    fun sync() {
        sharingScope.launch {
            val now = System.currentTimeMillis()
            val lastSyncTimestamp = userPreferencesRepository.getLastSyncTimestamp()
            val shouldRunSync =
                lastSyncTimestamp <= 0L || (now - lastSyncTimestamp) >= MIN_SYNC_INTERVAL_MS

            if (!shouldRunSync) {
                val ageSeconds = (now - lastSyncTimestamp) / 1000
                Timber.tag(TAG).d("Skipping startup sync (last sync ${ageSeconds}s ago)")
                return@launch
            }

            Timber.tag(TAG).i("Startup sync requested - Scheduling Incremental Sync")
            enqueueSyncWork(
                request = SyncWorker.incrementalSyncWork(),
                policy = ExistingWorkPolicy.KEEP,
                notifyObserver = false
            )
        }
    }

    /**
     * Performs an incremental sync, only processing files that have changed
     * since the last sync. Much faster for large libraries with few changes.
     * This is the recommended sync method for pull-to-refresh actions.
     */
    fun incrementalSync() {
        Timber.tag(TAG).i("Incremental sync requested - Scheduling incremental worker")
        enqueueSyncWork(
            request = SyncWorker.incrementalSyncWork(runMaintenance = false),
            policy = ExistingWorkPolicy.REPLACE
        )
    }

    /**
     * Performs a full library rescan, ignoring the last sync timestamp.
     * Use this when the user explicitly wants to force a complete rescan.
     */
    fun fullSync(deepScan: Boolean = true) {
        Timber.tag(TAG).i("Full sync requested - Scheduling full sync worker")
        enqueueSyncWork(
            request = SyncWorker.fullSyncWork(deepScan = deepScan),
            policy = ExistingWorkPolicy.REPLACE
        )
    }

    /**
     * Rebuilds local MediaStore-backed songs from scratch.
     * Local imported lyrics, favorites, and user metadata edits are removed for the rebuilt songs.
     * Use when local library data is corrupted or songs are missing.
     */
    fun rebuildDatabase() {
        Timber.tag(TAG).i("Rebuild database requested - Scheduling rebuild worker")
        enqueueSyncWork(
            request = SyncWorker.rebuildDatabaseWork(),
            policy = ExistingWorkPolicy.REPLACE
        )
    }

    /**
     * Schedules a fresh local incremental sync, replacing any pending foreground sync work.
     * The worker detects directory-rule version changes, so this still becomes a full local pass
     * when the caller just changed the selected folders.
     */
    fun forceRefresh() {
        Timber.tag(TAG).i("Manual local refresh requested - Scheduling incremental worker")
        enqueueSyncWork(
            request = SyncWorker.incrementalSyncWork(runMaintenance = false),
            policy = ExistingWorkPolicy.REPLACE
        )
    }

    private fun trackForegroundSyncWork(workInfos: List<WorkInfo>, requestedId: UUID?): WorkInfo? {
        val work = selectForegroundSyncWork(workInfos, requestedId)
        if (work != null && !work.state.isFinished) currentSyncWorkId.value = work.id
        return work
    }

    private fun observeStorageChanges() {
        sharingScope.launch {
            mediaStoreObserver.externalMediaStoreChanges.collect {
                scheduleLocalAutoSync()
            }
        }
    }

    private fun scheduleLocalAutoSync() {
        synchronized(autoSyncLock) {
            mediaStoreAutoSyncJob?.cancel()
            mediaStoreAutoSyncJob = sharingScope.launch {
                runLocalAutoSyncAfterDebounce()
            }
        }
    }

    private suspend fun runLocalAutoSyncAfterDebounce() {
        delay(MEDIASTORE_CHANGE_DEBOUNCE_MS)
        if (!userPreferencesRepository.initialSetupDoneFlow.first()) {
            Timber.tag(TAG).d("Skipping storage-change sync: initial setup not finished")
            return
        }
        Timber.tag(TAG).i("Storage change detected - scheduling local incremental sync")
        enqueueSyncWork(
            request = SyncWorker.incrementalSyncWork(runMaintenance = false),
            policy = ExistingWorkPolicy.KEEP,
            notifyObserver = false
        )
    }

    private fun observeAppForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Fast, maintenance-free incremental sync when the app returns to the foreground. Catches files
                // MediaStore indexed while we were backgrounded (the ContentObserver is only registered in the
                // foreground). [ForegroundCatchUp] holds it back for the settle time (B23-P1) and keeps the
                // in-memory cooldown so quick minimize/restore cycles don't pile up redundant work.
                foregroundCatchUp.onForeground()
            }
        })
    }

    private fun enqueueSyncWork(
        request: OneTimeWorkRequest,
        policy: ExistingWorkPolicy,
        notifyObserver: Boolean = true
    ) {
        val generation = syncRequestGeneration.incrementAndGet()
        // A KEEP request may be rejected in favour of an existing run. Clear any terminal
        // result until the accepted id is known; REPLACE hides old snapshots immediately.
        currentSyncWorkId.value = if (policy == ExistingWorkPolicy.KEEP) null else request.id
        val operation = workManager.enqueueUniqueWork(SyncWorker.WORK_NAME, policy, request)
        sharingScope.launch {
            try {
                // The wait is for WorkManager's local DB transaction, off main. It does not
                // await execution of the worker and does not create another scan.
                withContext(Dispatchers.IO) { operation.result.get() }
                val accepted = workManager.getWorkInfoByIdFlow(request.id).first()
                if (generation == syncRequestGeneration.get() && accepted != null) {
                    currentSyncWorkId.value = request.id
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "Unable to observe accepted library sync request")
            }
        }
        if (notifyObserver) {
            mediaStoreObserver.forceRescan()
        }
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val MIN_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val MEDIASTORE_CHANGE_DEBOUNCE_MS = 1_500L
        private const val FOREGROUND_SYNC_COOLDOWN_MS = 60_000L

        private val CHANGE_PHASES = setOf(
            SyncProgress.SyncPhase.IDLE,
            SyncProgress.SyncPhase.FETCHING_MEDIASTORE,
            SyncProgress.SyncPhase.PROCESSING_FILES,
            SyncProgress.SyncPhase.SAVING_TO_DATABASE
        )

        private val MAINTENANCE_PHASES = setOf(
            SyncProgress.SyncPhase.SCANNING_LRC,
            SyncProgress.SyncPhase.CLEANING_CACHE,
            SyncProgress.SyncPhase.COMPLETING
        )
    }
}

/** A pending replacement cannot inherit a previous run's success or error. */
internal fun selectForegroundSyncWork(workInfos: List<WorkInfo>, requestedId: UUID?): WorkInfo? {
    val requested = requestedId?.let { id -> workInfos.firstOrNull { it.id == id } }
    if (requestedId != null && requested == null) return null
    if (requested != null && !requested.state.isFinished) return requested
    return workInfos.firstOrNull { !it.state.isFinished } ?: requested
}

internal fun foregroundSyncProgress(work: WorkInfo?): SyncProgress = when (work?.state) {
    WorkInfo.State.RUNNING -> SyncProgress(
        isRunning = true,
        currentCount = work.progress.getInt(SyncWorker.PROGRESS_CURRENT, 0),
        totalCount = work.progress.getInt(SyncWorker.PROGRESS_TOTAL, 0),
        phase = SyncProgress.SyncPhase.entries.getOrNull(
            work.progress.getInt(SyncWorker.PROGRESS_PHASE, 0)
        ) ?: SyncProgress.SyncPhase.IDLE,
    )
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> SyncProgress(isRunning = work.runAttemptCount == 0)
    WorkInfo.State.SUCCEEDED -> {
        val intCount = work.outputData.getInt(SyncWorker.OUTPUT_TOTAL_SONGS, -1)
        val total = if (intCount >= 0) intCount else work.outputData
            .getLong(SyncWorker.OUTPUT_TOTAL_SONGS, 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        SyncProgress(currentCount = total, totalCount = total, isCompleted = true,
            phase = SyncProgress.SyncPhase.COMPLETING)
    }
    else -> SyncProgress()
}
