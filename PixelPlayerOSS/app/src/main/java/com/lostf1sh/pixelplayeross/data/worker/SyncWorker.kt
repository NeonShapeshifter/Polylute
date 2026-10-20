package com.lostf1sh.pixelplayeross.data.worker

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.LibraryItemOverrideDao
import com.lostf1sh.pixelplayeross.data.database.LibraryItemEntity
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.withOverrides
import com.lostf1sh.pixelplayeross.data.database.SongArtistCrossRef
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.data.database.SourceType
import com.lostf1sh.pixelplayeross.data.database.serializeArtistRefs
import com.lostf1sh.pixelplayeross.data.diagnostics.AdvancedPerformanceDiagnostics
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.identity.LibraryIdentity
import com.lostf1sh.pixelplayeross.data.identity.IdentityResolution
import com.lostf1sh.pixelplayeross.data.metadata.LibraryRepairPolicy
import com.lostf1sh.pixelplayeross.data.metadata.MetadataNormalizer
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.data.identity.SyncIdentityPlan
import com.lostf1sh.pixelplayeross.data.identity.ScannedItem
import com.lostf1sh.pixelplayeross.data.identity.LibraryItemId
import com.lostf1sh.pixelplayeross.data.identity.KnownItem
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepository
import com.lostf1sh.pixelplayeross.data.service.PlaybackActivityTracker
import com.lostf1sh.pixelplayeross.utils.AlbumArtCacheManager
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.AudioMetaUtils.getAudioMetadata
import com.lostf1sh.pixelplayeross.utils.DirectoryRuleResolver
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import com.lostf1sh.pixelplayeross.utils.buildLocalAudioSelection
import com.lostf1sh.pixelplayeross.utils.normalizeMetadataTextOrEmpty
import com.lostf1sh.pixelplayeross.utils.splitArtistsByDelimiters
import com.lostf1sh.pixelplayeross.utils.traceAsyncSection
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class SyncMode {
    INCREMENTAL,
    FULL,
    REBUILD
}

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val musicDao: MusicDao,
        private val overrideDao: LibraryItemOverrideDao,
        private val libraryChangeSignal: LibraryChangeSignal,
        private val userPreferencesRepository: UserPreferencesRepository,
        private val lyricsRepository: LyricsRepository
) : CoroutineWorker(appContext, workerParams) {

    private val contentResolver: ContentResolver = appContext.contentResolver
    private var minSongDurationMs: Int = 10000
    private var minTracksPerAlbum: Int = 1

    private fun hasMediaReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(
            applicationContext,
            permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override suspend fun doWork(): Result = traceAsyncSection("SyncWorker.doWork") {
            withContext(Dispatchers.IO) {
                try {
                    val syncModeName =
                            inputData.getString(INPUT_SYNC_MODE) ?: SyncMode.INCREMENTAL.name
                    val syncMode = SyncMode.valueOf(syncModeName)
                    val requestedForceMetadata = inputData.getBoolean(INPUT_FORCE_METADATA, false)
                    val requestedRunMaintenance = inputData.getBoolean(INPUT_RUN_MAINTENANCE, true)

                    if (!hasMediaReadPermission()) {
                        Timber.tag(TAG).w(
                            "Skipping sync: media read permission not granted (setup not finished?)"
                        )
                        return@withContext Result.failure()
                    }

                    if (
                        syncMode == SyncMode.INCREMENTAL &&
                        PlaybackActivityTracker.isPlaybackActive &&
                        runAttemptCount < MAX_PLAYBACK_DEFERRALS
                    ) {
                        Timber.tag(TAG).d(
                            "SyncWorker deferring INCREMENTAL sync (playback active, attempt=$runAttemptCount)"
                        )
                        return@withContext Result.retry()
                    }

                    // The reattachments this scan decided on, executed once the new projections
                    // exist. Reattachment is the one transition the scan cannot work out on its own:
                    // the row has never existed at the new locator, so nothing there carries the
                    // identity forward, and without this every renumber yields a second logical item
                    // for one file.
                    var pendingReattachments: List<IdentityResolution> = emptyList()
                    var pendingProjectionIds: Set<Long> = emptySet()
                    var pendingEvidenceUpdates: List<LibraryItemEntity> = emptyList()

                    Timber.tag(TAG)
                        .i("Starting MediaStore synchronization (Mode: $syncMode, ForceMetadata: $requestedForceMetadata)...")
                    val startTime = System.currentTimeMillis()
                    AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                        type = AdvancedPerformanceDiagnostics.EventTypes.WORKER,
                        name = "sync_worker_start"
                    ) {
                        mapOf(
                            "mode" to syncMode.name,
                            "forceMetadata" to requestedForceMetadata.toString(),
                            "attempt" to runAttemptCount.toString()
                        )
                    }

                    val artistDelimiters = userPreferencesRepository.artistDelimitersFlow.first()
                    val artistWordDelimiters = userPreferencesRepository.artistWordDelimitersFlow.first()
                    val extractArtistsFromTitle = userPreferencesRepository.extractArtistsFromTitleFlow.first()
                    val groupByAlbumArtist =
                            userPreferencesRepository.groupByAlbumArtistFlow.first()
                    val rescanRequired =
                            userPreferencesRepository.artistSettingsRescanRequiredFlow.first()
                    val directoryRulesVersion =
                        userPreferencesRepository.getDirectoryRulesVersion()
                    val lastAppliedDirectoryRulesVersion =
                        userPreferencesRepository.getLastAppliedDirectoryRulesVersion()
                    val directoryRulesChanged =
                        directoryRulesVersion != lastAppliedDirectoryRulesVersion

                    val allowedDirs = userPreferencesRepository.allowedDirectoriesFlow.first()
                    val blockedDirs = userPreferencesRepository.blockedDirectoriesFlow.first()
                    val directoryResolver = DirectoryRuleResolver(allowedDirs, blockedDirs)
                    
                    val lastSyncTimestamp = userPreferencesRepository.getLastSyncTimestamp()

                    minSongDurationMs = userPreferencesRepository.getMinSongDuration()
                    minTracksPerAlbum = userPreferencesRepository.minTracksPerAlbumFlow.first()

                    Timber.tag(TAG)
                        .d(
                            "Artist delimiters=$artistDelimiters, groupByAlbumArtist=$groupByAlbumArtist, " +
                                "rescanRequired=$rescanRequired, directoryRulesChanged=$directoryRulesChanged " +
                                "(current=$directoryRulesVersion, applied=$lastAppliedDirectoryRulesVersion)"
                        )

                    if (syncMode != SyncMode.REBUILD) {
                        val localProjections = musicDao.getMediaStoreIdentityProjections().associateBy { it.id }
                        val localSongIds = localProjections.keys
                        val scannedItems = fetchScannedIdentityItems(directoryResolver)
                        // Reuse the inventory: old MediaStore dates cannot hide an unbound
                        // locator, a missing projection or changed identity evidence.
                        val libraryItems = musicDao.getAllLibraryItems()
                        val knownByLocator = libraryItems.associateBy { it.currentLocator }
                        pendingProjectionIds = scannedItems.filter { scanned ->
                            val known = knownByLocator[scanned.mediaStoreId]
                            scanned.mediaStoreId !in localSongIds || known == null ||
                                known.stableKey != scanned.fingerprint ||
                                (known.fileSignature.isNotEmpty() && known.fileSignature != scanned.fileSignature)
                        }.mapTo(HashSet()) { it.mediaStoreId }

                        // Identity is resolved BEFORE anything is destroyed. The previous version
                        // deleted the set difference here, which meant a renumbered file was
                        // removed — along with its favourites and lyrics — before anything could
                        // recognise it under its new locator.
                        //
                        // The known items come from `library_items`, **not** from `songs`. That
                        // distinction is the whole point of the durable table: an item that is
                        // dormant has no projection at all, so reading the known set from the
                        // projections made every dormant item invisible to the resolver — and a
                        // returning card would hand its tracks brand-new identities with the user's
                        // favourites, lyrics and history still attached to the old ones.
                        //
                        // The evidence a dormant item keeps (`stable_key`, `file_signature`) is
                        // exactly what makes it recognisable on return. Discarding it on absence
                        // would have turned every temporary loss into a permanent one.
                        val knownItems = libraryItems
                            .filter { it.libraryItemId.isNotEmpty() }
                            .map { item ->
                                KnownItem(
                                    libraryItemId = LibraryItemId(item.libraryItemId),
                                    // An item with no projection has no locator. A sentinel is
                                    // correct rather than merely convenient: MediaStore ids are
                                    // positive, so it can never collide with a scanned row, and it
                                    // makes the item "missing from this scan" — which is precisely
                                    // what a dormant item is.
                                    mediaStoreId = item.currentLocator ?: NO_LOCATOR_SENTINEL,
                                    fingerprint = item.stableKey,
                                    // Older seeds left this field blank. Use only the stored,
                                    // bound projection's evidence, never the incoming file's, so
                                    // the existing resolver can distinguish retagging from reuse.
                                    fileSignature = item.fileSignature.ifEmpty {
                                        item.currentLocator?.let(localProjections::get)
                                            // Zero in a migrated projection means unknown. Only
                                            // complete historical measurements can contradict a scan.
                                            ?.takeIf { it.duration > 0L && it.fileSize > 0L }
                                            ?.let { LibraryIdentity.computeFileSignature(it.duration, it.fileSize) }
                                            .orEmpty()
                                    }
                                )
                            }
                        val identityPlan = SyncIdentityPlan.build(
                            known = knownItems,
                            scanned = scannedItems,
                            generateId = { java.util.UUID.randomUUID().toString() }
                        )

                        val scannedByLocator = scannedItems.associateBy { it.mediaStoreId }
                        pendingEvidenceUpdates = identityPlan.unchanged.mapNotNull { resolution ->
                            val known = knownByLocator[resolution.mediaStoreId] ?: return@mapNotNull null
                            if (!resolution.fingerprintUpdated && known.fileSignature.isNotEmpty()) return@mapNotNull null
                            val scanned = scannedByLocator.getValue(resolution.mediaStoreId)
                            known.copy(stableKey = scanned.fingerprint, fileSignature = scanned.fileSignature)
                        }

                        Timber.tag(TAG).i(
                            "Identity plan: ${identityPlan.reattachmentCount} reattached, " +
                                "${identityPlan.newIdentityCount} new, " +
                                "${identityPlan.ambiguousCount} ambiguous, " +
                                "${identityPlan.absent.size} absent"
                        )

                        // ABSENT IS NOT DELETED.
                        //
                        // The previous version fed this list straight into the destructive path. But
                        // an item with no projection is indistinguishable between an ejected card,
                        // a revoked permission, a scanner rebuild, and a track awaiting
                        // re-identification — and destroying a listener's favourites and lyrics on
                        // that evidence loses real user state every time a card comes out.
                        //
                        // So absent items become DORMANT: they keep their identity, their matching
                        // evidence and every piece of user state that hangs off them. Permanent
                        // deletion would need an explicit product retention rule, and none exists
                        // yet, so nothing is passed to the destructive path.
                        //
                        // Dormancy also **removes the stale projection**. Retaining it looked safer,
                        // but with the user's metadata overrides now owned by the identity there is
                        // nothing left on the row worth keeping — and a retained row is a phantom
                        // track: still listed, still searchable, still playable, for a file the
                        // device cannot see.
                        pendingReattachments = identityPlan.reattached + identityPlan.unchanged.filter {
                            it.mediaStoreId !in localSongIds
                        }
                        pendingProjectionIds = pendingProjectionIds + pendingReattachments.map { it.mediaStoreId }
                        currentCoroutineContext().ensureActive()
                        musicDao.markLibraryItemsDormantAndDropProjections(
                            identityPlan.absent.map { it.libraryItemId.value }
                        )
                        if (identityPlan.absent.isNotEmpty()) {
                            Timber.tag(TAG).i(
                                "Marked ${identityPlan.absent.size} library items dormant and dropped " +
                                    "their stale projections; no user state deleted"
                            )
                        }

                        val deletedIds = emptyList<Long>().toSet()

                        if (deletedIds.isNotEmpty()) {
                            Timber.tag(TAG)
                                .i("Found ${deletedIds.size} deleted songs. Removing from database...")
                            setProgress(
                                workDataOf(
                                    PROGRESS_CURRENT to 0,
                                    PROGRESS_TOTAL to deletedIds.size,
                                    PROGRESS_PHASE to SyncProgress.SyncPhase.SAVING_TO_DATABASE.ordinal
                                )
                            )
                            musicDao.deleteSongsAndRelatedData(deletedIds.toList())
                        } else {
                            Timber.tag(TAG).d("No deleted songs found.")
                        }
                    }

                    val isFreshInstall = musicDao.getSongCount().first() == 0
                    val syncPlan = buildSyncExecutionPlan(
                        requestedMode = syncMode,
                        requestedForceMetadata = requestedForceMetadata,
                        requestedRunMaintenance = requestedRunMaintenance,
                        rescanRequired = rescanRequired,
                        directoryRulesChanged = directoryRulesChanged,
                        isFreshInstall = isFreshInstall
                    )

                    val fetchTimestamp = if (!syncPlan.forceProcessAll) {
                        incrementalFetchTimestampSeconds(lastSyncTimestamp)
                    } else {
                        0L
                    }

                    Timber.tag(TAG)
                        .i("Fetching music from MediaStore (plan=${syncPlan.localScanMode}, since=$fetchTimestamp seconds)...")

                    val progressBatchSize = 50

                    val songsToInsert = fetchMusicFromMediaStore(
                        fetchTimestamp,
                        syncPlan.forceMetadata,
                        directoryResolver,
                        syncPlan.forceProcessAll,
                        syncPlan.resetExistingLocalData,
                        progressBatchSize,
                        pendingProjectionIds
                    ) { current, total, phaseOrdinal ->
                        setProgress(
                            workDataOf(
                                PROGRESS_CURRENT to current,
                                PROGRESS_TOTAL to total,
                                PROGRESS_PHASE to phaseOrdinal
                            )
                        )
                    }

                    Timber.tag(TAG)
                        .i("Fetched ${songsToInsert.size} new/modified songs from MediaStore.")

                    currentCoroutineContext().ensureActive()
                    val anySongsFetched = songsToInsert.isNotEmpty()
                    if (anySongsFetched) {

                        val allExistingArtists =
                                if (syncMode == SyncMode.REBUILD) {
                                    emptyList()
                                } else {
                                    musicDao.getAllArtistsListRaw()
                                }
                        val allExistingAlbums =
                                if (syncMode == SyncMode.REBUILD) {
                                    emptyList()
                                } else {
                                    musicDao.getAllAlbumsList(emptyList(), false, 0)
                                }

                        val existingArtistMetadata =
                                allExistingArtists.associate { it.id to (it.imageUrl to it.customImageUri) }
                        
                        val existingArtistIdMap = allExistingArtists.associate { it.name to it.id }.toMutableMap()
                        val maxArtistId = (musicDao.getMaxArtistId() ?: 0L).coerceAtLeast(0L)

                        Timber.tag(TAG)
                            .i("Processing ${songsToInsert.size} songs for upsert. Hash: ${songsToInsert.hashCode()}")

                        val (correctedSongs, albums, artists, crossRefs) =
                                preProcessAndDeduplicateWithMultiArtist(
                                        songs = songsToInsert,
                                        artistDelimiters = artistDelimiters,
                                        wordDelimiters = artistWordDelimiters,
                                        extractFromTitle = extractArtistsFromTitle,
                                        groupByAlbumArtist = groupByAlbumArtist,
                                        existingArtistMetadata = existingArtistMetadata,
                                        existingAlbums = allExistingAlbums,
                                        existingArtistIdMap = existingArtistIdMap,
                                        initialMaxArtistId = maxArtistId
                                )

                        if (syncMode == SyncMode.REBUILD) {
                            setProgress(
                                workDataOf(
                                    PROGRESS_CURRENT to 0,
                                    PROGRESS_TOTAL to correctedSongs.size,
                                    PROGRESS_PHASE to SyncProgress.SyncPhase.SAVING_TO_DATABASE.ordinal
                                )
                            )
                            musicDao.rebuildLocalMusicDataWithCrossRefs(
                                correctedSongs,
                                albums,
                                artists,
                                crossRefs
                            )
                        } else {
                            setProgress(
                                workDataOf(
                                    PROGRESS_CURRENT to 0,
                                    PROGRESS_TOTAL to correctedSongs.size,
                                    PROGRESS_PHASE to SyncProgress.SyncPhase.SAVING_TO_DATABASE.ordinal
                                )
                            )
                            musicDao.incrementalSyncMusicData(
                                    songs = correctedSongs,
                                    albums = albums,
                                    artists = artists,
                                    crossRefs = crossRefs,
                                    deletedSongIds = emptyList()
                            )
                        }
                    } else if (syncMode == SyncMode.REBUILD) {
                        setProgress(
                            workDataOf(
                                PROGRESS_CURRENT to 0,
                                PROGRESS_TOTAL to 0,
                                PROGRESS_PHASE to SyncProgress.SyncPhase.SAVING_TO_DATABASE.ordinal
                            )
                        )
                        musicDao.rebuildLocalMusicDataWithCrossRefs(
                            songs = emptyList(),
                            albums = emptyList(),
                            artists = emptyList(),
                            crossRefs = emptyList()
                        )
                    }

                    // --- identity binding, after the projections exist ---------------------------------
                    //
                    // The order here is not incidental. Reattachment must run *before* the library
                    // items are seeded, because seeding claims each projection's locator for the
                    // identity the scan minted — and the reattached row is about to be re-owned by a
                    // different identity. Seeding first would leave the old identity holding a
                    // locator, and the unique index on `current_locator` would then refuse the
                    // reattachment outright.

                    if (pendingReattachments.isNotEmpty()) {
                        var executed = 0
                        pendingReattachments.forEach { reattached ->
                            val incoming = musicDao.getSongByLocatorOnce(reattached.mediaStoreId)
                            if (incoming == null) return@forEach

                            val override = overrideDao.getForLibraryItem(reattached.libraryItemId.value)
                            val previousLocator = musicDao.currentLocatorFor(reattached.libraryItemId.value)
                            val moved = musicDao.reattachProjection(
                                libraryItemId = reattached.libraryItemId.value,
                                oldLocator = previousLocator,
                                incoming = incoming.withOverrides(override),
                                stableKey = incoming.stableKey,
                                fileSignature = LibraryIdentity.computeFileSignature(
                                    incoming.duration,
                                    incoming.fileSize
                                ),
                            )
                            if (moved) executed++
                        }
                        Timber.tag(TAG).i(
                            "Reattached $executed of ${pendingReattachments.size} renumbered items " +
                                "onto their existing identities; no duplicate logical items created"
                        )
                    }

                    // The user's edits are authoritative in `library_item_overrides`; the columns on
                    // the projection are a cache of them, recomputed here so a projection written by
                    // this scan shows the user's title rather than the file's.
                    musicDao.reapplyLibraryItemOverridesToProjections()

                    // Every projection belongs to a durable item, including the ones this scan just
                    // created. Without this a fresh install would have songs that no
                    // active-projection query can see, because the predicate requires the join.
                    musicDao.seedLibraryItemsFromProjections()
                    // Commit fresh matching evidence only after successful projection writes.
                    musicDao.updateLibraryItemEvidenceBatch(pendingEvidenceUpdates + songsToInsert.map {
                        LibraryItemEntity(
                            libraryItemId = it.libraryItemId,
                            stableKey = it.stableKey,
                            fileSignature = LibraryIdentity.computeFileSignature(it.duration, it.fileSize),
                        )
                    })

                    // The set of playable items, their identities and their metadata all just changed, which is
                    // every input the history is read against: Stats and the collections read the library again.
                    libraryChangeSignal.changed("library sync changed the active identities")

                    if (rescanRequired) {
                        userPreferencesRepository.clearArtistSettingsRescanRequired()
                    }

                    if (directoryRulesChanged) {
                        userPreferencesRepository.markDirectoryRulesVersionApplied(
                            directoryRulesVersion
                        )
                    }

                    currentCoroutineContext().ensureActive()
                    userPreferencesRepository.setLastSyncTimestamp(startTime)

                    val endTime = System.currentTimeMillis()
                    Timber.tag(TAG)
                        .i("Synchronization finished successfully in ${endTime - startTime}ms.")

                    val totalSongs = musicDao.getSongCount().first()
                    if (!syncPlan.runMaintenance) {
                        Timber.tag(TAG).d("Skipping library maintenance phases for local-only sync.")
                        AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                            type = AdvancedPerformanceDiagnostics.EventTypes.WORKER,
                            name = "sync_worker_success"
                        ) {
                            mapOf(
                                "mode" to syncMode.name,
                                "durationMs" to (System.currentTimeMillis() - startTime).toString(),
                                "totalSongs" to totalSongs.toString()
                            )
                        }
                        return@withContext Result.success(
                            workDataOf(OUTPUT_TOTAL_SONGS to totalSongs)
                        )
                    }

                    val autoScanLrc = userPreferencesRepository.autoScanLrcFilesFlow.first()
                    if (autoScanLrc) {
                        Timber.tag(TAG)
                            .i("Auto-scan LRC files enabled. Starting scan phase in chunks...")

                        val mediaStoreSongIds = musicDao.getAllMediaStoreSongIds()
                        val totalToScan = mediaStoreSongIds.size
                        var totalScannedCount = 0

                        mediaStoreSongIds.chunked(1000).forEach { idBatch ->
                            val batchEntities = musicDao.getSongsByIdsListSimple(idBatch)
                            val batchSongs =
                                    batchEntities.map { entity ->
                                        Song(
                                                id = entity.id.toString(),
                                                title = entity.title,
                                                artist = entity.artistName,
                                                artistId = entity.artistId,
                                                album = entity.albumName,
                                                albumId = entity.albumId,
                                                path = entity.filePath,
                                                contentUriString = entity.contentUriString,
                                                albumArtUriString = entity.albumArtUriString,
                                                duration = entity.duration,
                                                lyrics = entity.lyrics,
                                                dateAdded = entity.dateAdded,
                                                trackNumber = entity.trackNumber,
                                                year = entity.year,
                                                mimeType = entity.mimeType,
                                                bitrate = entity.bitrate,
                                                sampleRate = entity.sampleRate
                                        )
                                    }

                            val batchScannedCount =
                                    lyricsRepository.scanAndAssignLocalLrcFiles(batchSongs) {
                                            current,
                                            total ->
                                        val overallCurrent = totalScannedCount + current
                                        setProgress(
                                                workDataOf(
                                                        PROGRESS_CURRENT to overallCurrent,
                                                        PROGRESS_TOTAL to totalToScan,
                                                        PROGRESS_PHASE to
                                                                SyncProgress.SyncPhase.SCANNING_LRC
                                                                        .ordinal
                                                )
                                        )
                                    }
                            totalScannedCount += idBatch.size
                            Timber.tag(TAG).d(
                                    "LRC Scan: Processed batch of ${idBatch.size}, total assigned so far: $batchScannedCount"
                            )
                        }

                        Timber.tag(TAG).i("LRC Scan finished for $totalToScan songs.")
                    }

                    setProgress(
                        workDataOf(
                            PROGRESS_PHASE to SyncProgress.SyncPhase.CLEANING_CACHE.ordinal
                        )
                    )
                    val allSongIds = musicDao.getAllSongIds().toSet()
                    AlbumArtCacheManager.cleanOrphanedCacheFiles(applicationContext, allSongIds)

                    val finalTotalSongs = musicDao.getSongCount().first()

                    AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                        type = AdvancedPerformanceDiagnostics.EventTypes.WORKER,
                        name = "sync_worker_success"
                    ) {
                        mapOf(
                            "mode" to syncMode.name,
                            "durationMs" to (System.currentTimeMillis() - startTime).toString(),
                            "totalSongs" to finalTotalSongs.toString()
                        )
                    }
                    Result.success(workDataOf(OUTPUT_TOTAL_SONGS to finalTotalSongs))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error during MediaStore synchronization")
                    AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                        type = AdvancedPerformanceDiagnostics.EventTypes.WORKER,
                        name = "sync_worker_failure"
                    ) {
                        mapOf("error" to (e.message ?: e.javaClass.simpleName))
                    }
                    Result.failure()
                }
            }
    }

    /** Data class to hold the result of multi-artist preprocessing. */
    private data class MultiArtistProcessResult(
        val songs: List<SongEntity>,
        val albums: List<AlbumEntity>,
        val artists: List<ArtistEntity>,
        val crossRefs: List<SongArtistCrossRef>
    )

    /**
     * Process songs with multi-artist support. Splits artist names by delimiters and creates proper
     * cross-references.
     */
    private fun preProcessAndDeduplicateWithMultiArtist(
            songs: List<SongEntity>,
            artistDelimiters: List<String>,
            wordDelimiters: List<String> = emptyList(),
            extractFromTitle: Boolean = true,
            groupByAlbumArtist: Boolean,
            existingArtistMetadata: Map<Long, Pair<String?, String?>>,
            existingAlbums: List<AlbumEntity>,
            existingArtistIdMap: MutableMap<String, Long>,
            initialMaxArtistId: Long
    ): MultiArtistProcessResult {
        
        val nextArtistId = AtomicLong(initialMaxArtistId + 1)
        val artistNameToId = existingArtistIdMap
        
        val allCrossRefs = mutableListOf<SongArtistCrossRef>()
        val artistTrackCounts = mutableMapOf<Long, Int>()
        val albumMap = mutableMapOf<AlbumGroupingKey, Long>()
        val artistSplitCache = mutableMapOf<String, List<String>>()
        val correctedSongs = ArrayList<SongEntity>(songs.size)

        existingAlbums
            .sortedBy { it.id }
            .forEach { album ->
                buildAlbumGroupingKeys(album).forEach { key ->
                    albumMap.putIfAbsent(key, album.id)
                }
            }

        songs.forEach { song ->
            val rawArtistName = song.artistName

            val allArtistsForSong =
                    artistSplitCache.getOrPut("$rawArtistName\u0000${song.title}\u0000$extractFromTitle") {
                        collectArtistNames(
                            rawArtistName = rawArtistName,
                            title = song.title,
                            artistDelimiters = artistDelimiters,
                            wordDelimiters = wordDelimiters,
                            extractFromTitle = extractFromTitle
                        ).mapNotNull(MetadataNormalizer::normalizeForDisplay)
                            .distinct()
                            // A fixed local name, independent of the current UI language.
                            .ifEmpty { listOf(MetadataNormalizer.DISPLAY_UNKNOWN_ARTIST) }
                    }

            allArtistsForSong.forEach { artistName ->
                if (!artistNameToId.containsKey(artistName)) {
                     artistNameToId[artistName] = nextArtistId.getAndIncrement()
                }
            }

            val primaryArtistName = allArtistsForSong.first()
            val primaryArtistId = artistNameToId.getValue(primaryArtistName)

            val effectiveAlbumArtistName = song.albumArtist
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
                    ?: primaryArtistName
            if (effectiveAlbumArtistName.isNotEmpty() && !artistNameToId.containsKey(effectiveAlbumArtistName)) {
                artistNameToId[effectiveAlbumArtistName] = nextArtistId.getAndIncrement()
            }
            val albumArtistId = artistNameToId[effectiveAlbumArtistName] ?: primaryArtistId

            allArtistsForSong.forEachIndexed { index, artistName ->
                val artistId = artistNameToId.getValue(artistName)
                allCrossRefs.add(
                        SongArtistCrossRef(
                                songId = song.id,
                                artistId = artistId,
                                isPrimary = index == 0
                        )
                )
                artistTrackCounts[artistId] = (artistTrackCounts[artistId] ?: 0) + 1
            }

            // The grouping key is the normalized album artist plus title, computed by
            // `MetadataNormalizer` — the same function the diagnostic uses, so what the app groups
            // by and what it *says* it grouped by cannot diverge. It no longer consults the MediaStore
            // album id or the parent directory unless there is genuinely nothing else to go on.
            val albumKey = buildAlbumGroupingKey(song)
            val finalAlbumId = albumMap.getOrPut(albumKey) { song.albumId }

            val artistRefsForJson = allArtistsForSong.mapIndexed { index, name ->
                ArtistRef(id = artistNameToId.getValue(name), name = name, isPrimary = index == 0)
            }

            correctedSongs.add(
                    song.copy(
                            artistId = primaryArtistId,
                            artistName = rawArtistName,
                            albumArtistId = albumArtistId,
                            albumId = finalAlbumId,
                            artistsJson = serializeArtistRefs(artistRefsForJson)
                    )
            )
        }

        val artistEntities = artistNameToId.map { (name, id) ->
            val count = artistTrackCounts[id] ?: 0
            val metadata = existingArtistMetadata[id]
            ArtistEntity(
                id = id,
                name = name,
                trackCount = count,
                imageUrl = metadata?.first,
                customImageUri = metadata?.second
            )
        }
        
        val albumEntities = correctedSongs.groupBy { it.albumId }.map { (catAlbumId, songsInAlbum) ->
             val firstSong = songsInAlbum.first()
             val representativeAlbumArt = songsInAlbum.firstNotNullOfOrNull { it.albumArtUriString }
             val determinedAlbumArtist = chooseAlbumDisplayArtist(
                 songs = songsInAlbum,
                 preferAlbumArtist = groupByAlbumArtist
             )
             val determinedAlbumArtistId = resolveAlbumDisplayArtistId(
                 displayArtist = determinedAlbumArtist,
                 songs = songsInAlbum,
                 artistNameToId = artistNameToId,
                 artistDelimiters = artistDelimiters,
                 wordDelimiters = wordDelimiters
             )
             val metadataAlbumArtist = songsInAlbum
                 .mapNotNull { song ->
                     song.albumArtist?.takeIf { it.isNotBlank() }
                 }
                 .groupingBy { it }
                 .eachCount()
                 .maxByOrNull { it.value }
                 ?.key

             AlbumEntity(
                 id = catAlbumId,
                 title = firstSong.albumName,
                 artistName = determinedAlbumArtist,
                 artistId = determinedAlbumArtistId,
                 albumArtUriString = representativeAlbumArt,
                 songCount = songsInAlbum.size,
                 dateAdded = firstSong.dateAdded,
                 // The album's most common track year, not the first track's: one untagged track
                 // (year 0) used to zero the whole album's (B11-fix FIX-3).
                 year = MetadataNormalizer.albumYear(songsInAlbum.map { it.year }),
                 albumArtist = metadataAlbumArtist,
                 // The key every track in this album was grouped under. Stored so grouping is
                 // queryable, and so the diagnostic can show what it grouped by rather than
                 // recomputing a possibly-different answer.
                 groupingKey = firstSong.albumGroupingKey,
                 isCompilation = songsInAlbum.any { it.isCompilation }
             )
        }

        return MultiArtistProcessResult(
                songs = correctedSongs,
                albums = albumEntities,
                artists = artistEntities,
                crossRefs = allCrossRefs
        )
    }

    /**
     * Fetches a map of Song ID -> Genre Name using the MediaStore.Audio.Genres table. This is
     * necessary because the GENRE column in MediaStore.Audio.Media is not reliably available or
     * populated on all Android versions (especially pre-API 30).
     * 
     * Optimized: 
     * 1. Caches results for 1 hour to avoid refetching on incremental syncs
     * 2. Fetches all genres first, then queries members in parallel with controlled concurrency
     */
    private suspend fun fetchGenreMap(forceRefresh: Boolean = false): Map<Long, String> = coroutineScope {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return@coroutineScope emptyMap()
        }

        val now = System.currentTimeMillis()
        val cacheAge = now - genreMapCacheTimestamp
        if (!forceRefresh && genreMapCache.isNotEmpty() && cacheAge < GENRE_CACHE_TTL_MS) {
            Timber.tag(TAG).d("Using cached genre map (${genreMapCache.size} entries, age: ${cacheAge/1000}s)")
            return@coroutineScope genreMapCache
        }
        
        val genreMap = ConcurrentHashMap<Long, String>()
        val genreProjection = arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME)
        
        val querySemaphore = Semaphore(4)

        try {
            val genres = mutableListOf<Pair<Long, String>>()
            
            contentResolver.query(
                            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                            genreProjection,
                            null,
                            null,
                            null
                    )
                    ?.use { cursor ->
                        val idCol = cursor.getColumnIndex(MediaStore.Audio.Genres._ID)
                        val nameCol = cursor.getColumnIndex(MediaStore.Audio.Genres.NAME)

                        if (idCol >= 0 && nameCol >= 0) {
                            while (cursor.moveToNext()) {
                                val genreId = cursor.getLong(idCol)
                                val genreName = cursor.getString(nameCol)

                                if (!genreName.isNullOrBlank() &&
                                                !genreName.equals("unknown", ignoreCase = true)
                                ) {
                                    genres.add(genreId to genreName)
                                }
                            }
                        }
                    }
            
            genres.map { (genreId, genreName) ->
                async(Dispatchers.IO) {
                    querySemaphore.withPermit {
                        val membersUri =
                                MediaStore.Audio.Genres.Members.getContentUri(
                                        "external",
                                        genreId
                                )
                        val membersProjection =
                                arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID)

                        contentResolver.query(
                                        membersUri,
                                        membersProjection,
                                        null,
                                        null,
                                        null
                                )
                                ?.use { membersCursor ->
                                    val audioIdCol =
                                            membersCursor.getColumnIndex(
                                                    MediaStore.Audio.Genres.Members.AUDIO_ID
                                            )
                                    if (audioIdCol >= 0) {
                                        while (membersCursor.moveToNext()) {
                                            val audioId = membersCursor.getLong(audioIdCol)
                                            genreMap[audioId] = genreName
                                        }
                                    }
                                }
                    }
                }
            }.awaitAll()
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error fetching genre map")
        }
        
        if (genreMap.isNotEmpty()) {
            genreMapCache = genreMap.toMap()
            genreMapCacheTimestamp = System.currentTimeMillis()
            Timber.tag(TAG).d("Genre map cache updated with ${genreMap.size} entries")
        }
        
        genreMap
    }

    /** Raw data extracted from cursor - lightweight class for fast iteration */
    private data class RawSongData(
            val id: Long,
            val albumId: Long,
            val artistId: Long,
            val filePath: String,
            val mimeType: String?,
            val title: String,
            val artist: String,
            val album: String,
            val albumArtist: String?,
            val duration: Long,
            /**
             * MediaStore's packed disc/track integer, exactly as the cursor returned it.
             *
             * Kept raw rather than pre-decoded because `decodePackedTrack` is the single decoder —
             * the scan's identity pass used `% 1000` / `/ 1000` and this pass used the same
             * arithmetic written out separately, so the two could disagree about one file.
             */
            val rawTrack: Int,
            val year: Int,
            val dateAdded: Long,
            val dateModified: Long,
            val genre: String?,
            /** Bytes as MediaStore reports them; 0 when the column is unavailable. */
            val size: Long = 0L
    )

    private fun Int?.orZero(): Int = this ?: 0

    private fun userEdited(song: SongEntity): Boolean = LibraryRepairPolicy.isUserEdited(
        titleEdited = song.titleUserEdited,
        artistEdited = song.artistUserEdited,
        albumEdited = song.albumUserEdited,
        genreEdited = song.genreUserEdited,
    )

    private fun isSongUnchanged(raw: RawSongData, existing: SongEntity?): Boolean {
        if (existing == null || existing.libraryItemId.isEmpty()) return false

        val position = MetadataNormalizer.decodePackedTrack(raw.rawTrack)
        val lastSlash = raw.filePath.lastIndexOf('/')
        val parentDir = if (lastSlash > 0) raw.filePath.substring(0, lastSlash) else ""
        val existingDateModifiedSeconds = existing.mediaStoreDateModified.takeIf { it > 0 }
            ?: TimeUnit.MILLISECONDS.toSeconds(existing.dateAdded)
        val existingDateAddedSeconds = existing.mediaStoreDateAdded.takeIf { it > 0 }
            ?: existingDateModifiedSeconds

        return existing.filePath == raw.filePath &&
            existing.parentDirectoryPath == parentDir &&
            (existing.titleUserEdited || existing.title == raw.title) &&
            (existing.artistUserEdited || existing.artistName == raw.artist) &&
            (existing.albumUserEdited || existing.albumName == raw.album) &&
            (existing.albumUserEdited || existing.albumId == raw.albumId) &&
            (existing.artistUserEdited || existing.artistId == raw.artistId) &&
            existing.duration == raw.duration &&
            // Also fill legacy unknown sizes when MediaStore supplies a real measurement.
            existing.fileSize == raw.size &&
            // Compared against the *interpreted* position, not the packed value: a tagger that
            // rewrites `TRACK` from 3 to 3/12 changes the raw integer without changing where the
            // track sits, and re-scanning every such file would be pointless work. A row the user
            // edited holds the user's position, which the scan is not allowed to replace, so it is
            // not "changed" merely because the file says something else (B11-fix FIX-3).
            (userEdited(existing) ||
                (existing.trackNumber == position.track.orZero() && existing.discNumber == position.disc)) &&
            // MediaStore has no year for many files; a year read from the tags is not a change.
            LibraryRepairPolicy.isYearUnchanged(existing.year, raw.year) &&
            existingDateAddedSeconds == raw.dateAdded &&
            existingDateModifiedSeconds == raw.dateModified
    }

    private suspend fun fetchMusicFromMediaStore(
            sinceTimestamp: Long,
            forceMetadata: Boolean,
            directoryResolver: DirectoryRuleResolver,
            forceProcessAll: Boolean,
            resetExistingLocalData: Boolean,
            progressBatchSize: Int,
            pendingProjectionIds: Set<Long>,
            onProgress: suspend (current: Int, total: Int, phaseOrdinal: Int) -> Unit
    ): List<SongEntity> = traceAsyncSection("SyncWorker.fetchMusicFromMediaStore") {
        val deepScan = forceMetadata
        val genreMap = fetchGenreMap()

        val projectionList = mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ALBUM_ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED,
                // Part of the durable identity: cheap here, and identity must be recomputable
                // from this cursor alone rather than needing a second pass over the library.
                MediaStore.Audio.Media.SIZE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projectionList.add(MediaStore.Audio.Media.GENRE)
        }

        val projection = projectionList.toTypedArray()

        val (baseSelection, baseArgs) = buildLocalAudioSelection(minSongDurationMs)
        val selectionBuilder = StringBuilder(baseSelection)
        val selectionArgsList = baseArgs.toMutableList()

        if (sinceTimestamp > 0) {
            selectionBuilder.append(
                    " AND (${MediaStore.Audio.Media.DATE_MODIFIED} > ? OR ${MediaStore.Audio.Media.DATE_ADDED} > ?"
            )
            selectionArgsList.add(sinceTimestamp.toString())
            selectionArgsList.add(sinceTimestamp.toString())
            // Only numeric ids from the inventory, never paths or untrusted SQL text.
            // Literals avoid SQLite's bind-variable limit for a returning large card.
            if (pendingProjectionIds.isNotEmpty()) {
                selectionBuilder.append(" OR ${MediaStore.Audio.Media._ID} IN (${pendingProjectionIds.joinToString(",")})")
            }
            selectionBuilder.append(")")
        }

        val selection = selectionBuilder.toString()
        val selectionArgs = selectionArgsList.toTypedArray()

        val rawDataList = mutableListOf<RawSongData>()

        requireNotNull(contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        null
                )) { "MediaStore metadata query returned no cursor" }
                .use { cursor ->
                    val totalCount = cursor.count
                    onProgress(0, totalCount, SyncProgress.SyncPhase.FETCHING_MEDIASTORE.ordinal)

                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val artistIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val albumArtistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val mimeTypeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                    val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                    val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val dateModifiedCol =
                            cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
                    } else -1

                    while (cursor.moveToNext()) {
                        try {
                            val data = cursor.getString(dataCol) ?: continue
                            val lastSlash = data.lastIndexOf('/')
                            if (lastSlash > 0) {
                                val normalizedParent = data.substring(0, lastSlash)
                                if (directoryResolver.isBlocked(normalizedParent)) {
                                    continue
                                }
                            }
                        } catch (e: Exception) {
                        }

                        rawDataList.add(
                                RawSongData(
                                        id = cursor.getLong(idCol),
                                        albumId = cursor.getLong(albumIdCol),
                                        artistId = cursor.getLong(artistIdCol),
                                        filePath = cursor.getString(dataCol) ?: "",
                                        mimeType = if (mimeTypeCol >= 0) cursor.getString(mimeTypeCol) else null,
                                        size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                                        title =
                                                cursor.getString(titleCol)
                                                        .normalizeMetadataTextOrEmpty()
                                                        .ifEmpty { "Unknown Title" },
                                        artist =
                                                cursor.getString(artistCol)
                                                        .normalizeMetadataTextOrEmpty()
                                                        .ifEmpty { "Unknown Artist" },
                                        album =
                                                cursor.getString(albumCol)
                                                        .normalizeMetadataTextOrEmpty()
                                                        .ifEmpty { "Unknown Album" },
                                        albumArtist =
                                                if (albumArtistCol >= 0)
                                                        cursor.getString(albumArtistCol)
                                                                ?.normalizeMetadataTextOrEmpty()
                                                                ?.takeIf { it.isNotBlank() }
                                                else null,
                                        duration = cursor.getLong(durationCol),
                                        rawTrack = cursor.getInt(trackCol),
                                        year = cursor.getInt(yearCol),
                                        dateAdded = cursor.getLong(dateAddedCol),
                                        dateModified = cursor.getLong(dateModifiedCol),
                                        genre = if (genreCol >= 0) cursor.getString(genreCol) else null
                                )
                        )
                    }
                }

        if (rawDataList.isEmpty()) {
            Timber.tag(TAG).i("MediaStore cursor produced 0 raw songs after directory filtering")
            return@traceAsyncSection emptyList()
        }

        val rawSongCount = rawDataList.size
        val songsToProcess = if (forceProcessAll) {
              rawDataList.toList()
        } else {
            val results = mutableListOf<RawSongData>()
            
            rawDataList.chunked(500).forEach { batch ->
                val ids = batch.map { it.id }
                val existingMap = musicDao.getSongsByIdsListSimple(ids).associateBy { it.id }
                
                batch.forEach { raw ->
                    val existing = existingMap[raw.id]
                    if (!isSongUnchanged(raw, existing)) {
                        results.add(raw)
                    }
                }
            }
            results
        }

        rawDataList.clear()

        val totalCount = songsToProcess.size
        Timber.tag(TAG).i(
            "MediaStore raw=$rawSongCount, songsToProcess=$totalCount, forceProcessAll=$forceProcessAll, resetExistingLocalData=$resetExistingLocalData"
        )
        if (totalCount == 0) {
            return@traceAsyncSection emptyList()
        }

        onProgress(0, totalCount, SyncProgress.SyncPhase.PROCESSING_FILES.ordinal)
        val processedCount = AtomicInteger(0)
        val concurrencyLimit = 4
        val semaphore = Semaphore(concurrencyLimit)

        val songs = mutableListOf<SongEntity>()
        for (batch in songsToProcess.chunked(200)) {
            val ids = batch.map { it.id }
            val existingMap = if (resetExistingLocalData) emptyMap() else musicDao.getSongsByIdsListSimple(ids).associateBy { it.id }
            val batchResults = coroutineScope {
                batch.map { raw ->
                    async {
                        semaphore.withPermit {
                            val localSong = existingMap[raw.id]
                            val mediaStoreSong =
                                processSongData(
                                    raw = raw,
                                    genreMap = genreMap,
                                    deepScan = deepScan,
                                    forceAlbumArtRefresh = deepScan || localSong != null
                                )

                            val song = if (localSong != null) {
                                // B11-fix FIX-3: the track and disc of a row the user did not edit
                                // are what this scan decoded, not whatever an earlier scan stored
                                // (the old "keep any nonzero local value" is how a packed 1012
                                // survived every sync); the year is kept when the scan has none.
                                val position = LibraryRepairPolicy.mergePosition(
                                    storedTrack = localSong.trackNumber,
                                    storedDisc = localSong.discNumber,
                                    userEdited = userEdited(localSong),
                                    scannedTrack = mediaStoreSong.trackNumber,
                                    scannedDisc = mediaStoreSong.discNumber,
                                )
                                mediaStoreSong.copy(
                                    dateAdded = if (
                                        localSong.mediaStoreDateAdded > 0 &&
                                        localSong.mediaStoreDateAdded == mediaStoreSong.mediaStoreDateAdded
                                    ) {
                                        localSong.dateAdded
                                    } else {
                                        mediaStoreSong.dateAdded
                                    },
                                    lyrics = localSong.lyrics,
                                    title = if (localSong.titleUserEdited) localSong.title else mediaStoreSong.title,
                                    artistName = if (localSong.artistUserEdited) localSong.artistName else mediaStoreSong.artistName,
                                    albumName = if (localSong.albumUserEdited) localSong.albumName else mediaStoreSong.albumName,
                                    genre = if (localSong.genreUserEdited) localSong.genre else mediaStoreSong.genre,
                                    trackNumber = position.track,
                                    discNumber = position.disc,
                                    year = LibraryRepairPolicy.mergeYear(localSong.year, mediaStoreSong.year),
                                    albumArtUriString = mediaStoreSong.albumArtUriString,
                                    // Identity is carried over, never re-derived. The fingerprint
                                    // above deliberately refreshes from the fresh scan, because
                                    // matching evidence is supposed to track the file.
                                    libraryItemId = localSong.libraryItemId.ifEmpty { mediaStoreSong.libraryItemId },
                                    titleUserEdited = localSong.titleUserEdited,
                                    artistUserEdited = localSong.artistUserEdited,
                                    albumUserEdited = localSong.albumUserEdited,
                                    genreUserEdited = localSong.genreUserEdited
                                )
                            } else {
                                mediaStoreSong
                            }

                            val count = processedCount.incrementAndGet()
                            if (count % progressBatchSize == 0 || count == totalCount) {
                                onProgress(count, totalCount, SyncProgress.SyncPhase.PROCESSING_FILES.ordinal)
                            }
                            song
                        }
                    }
                }.awaitAll()
            }
            songs.addAll(batchResults)
        }

        songs
    }

    /**
     * Checks if a metadata field from MediaStore is a default/unknown placeholder.
     * MediaStore uses `<unknown>` for unreadable fields, and our normalization
     * may fall back to `"Unknown Artist"` / `"Unknown Album"` etc.
     */
    private fun isDefaultMetadata(value: String): Boolean {
        val lower = value.trim().lowercase()
        return lower.isEmpty() ||
            lower == "<unknown>" ||
            lower == "unknown" ||
            lower == "unknown artist" ||
            lower == "unknown album"
    }

    /**
     * Process a single song's raw data into a SongEntity. This is the CPU/IO intensive work that
     * benefits from parallelization.
     */
    private suspend fun processSongData(
            raw: RawSongData,
            genreMap: Map<Long, String>,
            deepScan: Boolean,
            forceAlbumArtRefresh: Boolean
    ): SongEntity {
        val parentDir = java.io.File(raw.filePath).parent ?: ""
        val contentUriString =
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, raw.id)
                        .toString()

        // Scan-time artwork resolution is intentionally lightweight: it never extracts
        // embedded art (that can allocate a multi-MB ByteArray per song). The stable lazy
        // URI is stored and the image-loading path extracts/caches artwork on demand.
        var albumArtUriString =
                AlbumArtUtils.getAlbumArtUriForLibraryScan(
                        applicationContext,
                        raw.id,
                        forceAlbumArtRefresh
                )
        val audioMetadata =
                if (deepScan) getAudioMetadata(musicDao, raw.id, raw.filePath, true) else null

        var title = raw.title
        var artist = raw.artist
        var album = raw.album
        var albumArtist = resolveAlbumArtist(
            rawAlbumArtist = raw.albumArtist,
            metadataAlbumArtist = null
        )
        // The interpreted position comes from the packed value; a tag read may refine it below.
        val decoded = MetadataNormalizer.decodePackedTrack(raw.rawTrack)
        var trackNumber = decoded.track ?: 0
        var discNumber = decoded.disc
        var year = MetadataNormalizer.parseYear(raw.year) ?: 0
        var genre: String? = genreMap[raw.id] ?: raw.genre

        val shouldAugmentMetadata =
                deepScan ||
                        raw.filePath.endsWith(".wav", true) ||
                        raw.filePath.endsWith(".opus", true) ||
                        raw.filePath.endsWith(".ogg", true) ||
                        raw.filePath.endsWith(".oga", true) ||
                        raw.filePath.endsWith(".aiff", true) ||
                        isDefaultMetadata(raw.artist) ||
                        isDefaultMetadata(raw.album)

        if (shouldAugmentMetadata) {
            val file = java.io.File(raw.filePath)
            if (file.exists()) {
                try {
                    AudioMetadataReader.read(file, readArtwork = false)?.let { meta ->
                        if (!meta.title.isNullOrBlank()) title = meta.title
                        if (!meta.artist.isNullOrBlank()) artist = meta.artist
                        if (!meta.album.isNullOrBlank()) album = meta.album
                        albumArtist = resolveAlbumArtist(
                            rawAlbumArtist = albumArtist,
                            metadataAlbumArtist = meta.albumArtist
                        )
                        if (!meta.genre.isNullOrBlank()) genre = meta.genre
                        if (meta.trackNumber != null) trackNumber = meta.trackNumber
                        if (meta.discNumber != null) discNumber = meta.discNumber
                        MetadataNormalizer.parseYear(meta.year)?.let { year = it }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to read metadata via TagLib for ${raw.filePath}")
                }
            }
        }

        // The fingerprint's track and disc come from the one frozen function the identity pass uses
        // too, NOT from [MetadataNormalizer.decodePackedTrack]: the stored keys were made with the
        // frozen arithmetic, and following the display decoder would change every one of them and
        // orphan the library's per-song state (see `LibraryIdentity.fingerprintPosition`).
        val (keyTrack, keyDisc) = LibraryIdentity.fingerprintPosition(raw.rawTrack)
        val stableKey = LibraryIdentity.computeKey(
                durationMs = raw.duration,
                fileSize = raw.size,
                title = raw.title,
                artist = raw.artist,
                album = raw.album,
                trackNumber = keyTrack,
                discNumber = keyDisc
        )

        // The interpreted position, plus the raw values it came from. `decodePackedTrack` is the
        // one decoder for MediaStore's packed disc/track integer — the scan's identity pass and its
        // metadata pass previously decoded it independently, which is how two views of the same file
        // can disagree.
        val position = MetadataNormalizer.decodePackedTrack(raw.rawTrack)
        val releaseYear = MetadataNormalizer.parseYear(raw.year)
        val genres = MetadataNormalizer.parseGenres(genre)
        // MediaStore exposes no compilation column, so the explicit ID3 `TCMP` frame is not
        // available here — and reading it would mean a tag read per file on every scan, which the
        // performance constraint rules out. The evidence the cursor *does* carry is the album
        // artist, and that is enough: see `MetadataNormalizer.isCompilation` for why several track
        // artists is deliberately not treated as evidence.
        val compilation = MetadataNormalizer.isCompilation(
            explicitFlag = null,
            albumArtist = albumArtist,
            distinctTrackArtists = 0,
        )
        val albumKey = MetadataNormalizer.albumGroupingKey(
            albumTitle = album,
            albumArtist = albumArtist,
            parentDirectoryPath = parentDir,
            compilation = compilation,
        )

        return SongEntity(
                id = raw.id,
                // Minted once, opaque, and deliberately not derived from `raw.id`: the locator is
                // how we find the row, never what it is.
                libraryItemId = java.util.UUID.randomUUID().toString(),
                stableKey = stableKey,
                fileSize = raw.size,
                albumGroupingKey = albumKey.value,
                artistGroupingKey = MetadataNormalizer.artistGroupingKey(artist).value,
                genresJson = if (genres.isEmpty()) null else genres.joinToString("\u0000"),
                rawTrackNumber = raw.rawTrack.toString(),
                rawDiscNumber = null,
                isCompilation = compilation,
                title = title,
                artistName = artist,
                artistId = raw.artistId,
                albumArtist = albumArtist,
                albumName = album,
                albumId = raw.albumId,
                contentUriString = contentUriString,
                albumArtUriString = albumArtUriString,
                duration = raw.duration,
                genre = genre,
                filePath = raw.filePath,
                parentDirectoryPath = parentDir,
                trackNumber = position.track ?: trackNumber,
                discNumber = position.disc ?: discNumber,
                year = releaseYear ?: year,
                dateAdded =
                        raw.dateAdded.let { seconds ->
                            if (seconds > 0) TimeUnit.SECONDS.toMillis(seconds)
                            else System.currentTimeMillis()
                        },
                mimeType = audioMetadata?.mimeType ?: raw.mimeType,
                sampleRate = audioMetadata?.sampleRate,
                bitrate = audioMetadata?.bitrate,
                sourceType = SourceType.LOCAL,
                mediaStoreDateAdded = raw.dateAdded,
                mediaStoreDateModified = raw.dateModified
        )
    }

    /**
     * Fetches all IDs currently available in MediaStore to identify deleted songs.
     */

    /**
     * Reads just enough of MediaStore to compute matching evidence for every visible row.
     *
     * Cursor-only: no file reads, no hashing. This runs *before* cleanup because identity has to be
     * decided while the disappeared rows are still present to be matched against.
     */
    private fun fetchScannedIdentityItems(
        directoryResolver: DirectoryRuleResolver
    ): List<ScannedItem> {
        val items = mutableListOf<ScannedItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TRACK
        )
        val (selection, selectionArgs) = buildLocalAudioSelection(minSongDurationMs)

        requireNotNull(contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )) { "MediaStore inventory query returned no cursor" }.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)

            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol) ?: continue
                if (File(path).parent?.let(directoryResolver::isBlocked) == true) continue
                val duration = cursor.getLong(durationCol)
                val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                val title = cursor.getString(titleCol)
                val artist = cursor.getString(artistCol)
                val album = cursor.getString(albumCol)
                val track = cursor.getInt(trackCol)
                // Frozen on purpose: the same arithmetic the stored keys were made with.
                val (keyTrack, keyDisc) = LibraryIdentity.fingerprintPosition(track)
                items += ScannedItem(
                    mediaStoreId = cursor.getLong(idCol),
                    fingerprint = LibraryIdentity.computeKey(
                        durationMs = duration,
                        fileSize = size,
                        title = title,
                        artist = artist,
                        album = album,
                        trackNumber = keyTrack,
                        discNumber = keyDisc
                    ),
                    fileSignature = LibraryIdentity.computeFileSignature(duration, size)
                )
            }
        }
        return items
    }

    private fun fetchMediaStoreIds(directoryResolver: DirectoryRuleResolver): Set<Long> {
        val ids = mutableSetOf<Long>()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA)
        val (selection, selectionArgs) = buildLocalAudioSelection(minSongDurationMs)

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val data = cursor.getString(dataCol)
                if (data != null) {
                    val parentPath = File(data).parent
                    if (parentPath != null && directoryResolver.isBlocked(File(parentPath).absolutePath)) {
                        continue
                    }
                }
                ids.add(cursor.getLong(idCol))
            }
        }
        return ids
    }

    companion object {
        const val WORK_NAME = "com.lostf1sh.pixelplayeross.data.worker.SyncWorker"
        const val PERIODIC_MAINTENANCE_WORK_NAME =
            "com.lostf1sh.pixelplayeross.data.worker.SyncWorker.PeriodicMaintenance"
        private const val TAG = "SyncWorker"
        const val INPUT_FORCE_METADATA = "input_force_metadata"
        const val INPUT_RUN_MAINTENANCE = "input_run_maintenance"
        const val INPUT_SYNC_MODE = "input_sync_mode"
        private const val MAX_PLAYBACK_DEFERRALS = 5

        /**
         * Stands in for "this item has no projection".
         *
         * MediaStore ids are positive, so a negative value can never collide with a real scanned
         * row — which is what lets a dormant item be recognised as absent from the scan without a
         * nullable locator in a type that never needed one.
         */
        private const val NO_LOCATOR_SENTINEL = -1L

        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_TOTAL = "progress_total"
        const val PROGRESS_PHASE = "progress_phase"
        const val OUTPUT_TOTAL_SONGS = "output_total_songs"

        private const val GENRE_CACHE_TTL_MS = 60 * 60 * 1000L
        @Volatile private var genreMapCache: Map<Long, String> = emptyMap()
        @Volatile private var genreMapCacheTimestamp: Long = 0L
        
        fun invalidateGenreCache() {
            genreMapCache = emptyMap()
            genreMapCacheTimestamp = 0L
            Timber.tag(TAG).d("Genre cache invalidated")
        }

        fun startUpSyncWork(deepScan: Boolean = false) =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_FORCE_METADATA to deepScan,
                                        INPUT_RUN_MAINTENANCE to true,
                                        INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name
                                )
                        )
                        .build()

        fun incrementalSyncWork(
            runMaintenance: Boolean = true
        ) =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name,
                                        INPUT_RUN_MAINTENANCE to runMaintenance
                                )
                        )
                        .build()

        private val heavySyncConstraints: Constraints =
                Constraints.Builder()
                        .setRequiresStorageNotLow(true)
                        .build()

        fun fullSyncWork(
            deepScan: Boolean = false
        ) =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_SYNC_MODE to SyncMode.FULL.name,
                                        INPUT_FORCE_METADATA to deepScan,
                                        INPUT_RUN_MAINTENANCE to true
                                )
                        )
                        .setConstraints(heavySyncConstraints)
                        .build()

        fun rebuildDatabaseWork() =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_SYNC_MODE to SyncMode.REBUILD.name,
                                        INPUT_RUN_MAINTENANCE to true
                                )
                        )
                        .setConstraints(heavySyncConstraints)
                        .build()

        fun periodicMaintenanceWork(): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresStorageNotLow(true)
                .build()
            return PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
                .setInputData(
                    workDataOf(
                        INPUT_SYNC_MODE to SyncMode.FULL.name,
                        INPUT_RUN_MAINTENANCE to true
                    )
                )
                .setConstraints(constraints)
                .build()
        }
    }
    
}
