package com.lostf1sh.pixelplayeross.data.intelligence

import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionRepository
import com.lostf1sh.pixelplayeross.data.database.EngagementDao
import com.lostf1sh.pixelplayeross.data.database.LibraryItemDao
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.metadata.DuplicateCandidate
import com.lostf1sh.pixelplayeross.data.metadata.DuplicateDetector
import com.lostf1sh.pixelplayeross.data.metadata.MetadataDiagnostic
import com.lostf1sh.pixelplayeross.data.metadata.MetadataNormalizer
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ListeningStatsTracker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gathers the Maintenance diagnostic for whatever is currently playing, or for the most recent
 * track if nothing is.
 *
 * Read-only in the strict sense: it issues `SELECT`s and reads a session snapshot. It writes to no
 * table, changes no state, and nothing in playback or recommendation consults it.
 *
 * The listening numbers are the ledger's, so they are the ones Stats and the collections show; the
 * legacy counters (`song_engagements`, no longer written since B28) are beside them, labelled as such,
 * so the two can be compared on the one track.
 *
 * The gathering is separate from the formatting on purpose. A diagnostic that has to be assembled
 * from a live database to be tested is a diagnostic nobody tests, and the parts most worth checking
 * are exactly the parts that involve *missing* data — an item with no projection, a counter that was
 * never recorded, a session that is not running.
 */
@Singleton
class TrackIntelligenceDiagnosticProvider @Inject constructor(
    private val musicDao: MusicDao,
    private val libraryItemDao: LibraryItemDao,
    private val ledgers: LedgerProvider,
    private val engagementDao: EngagementDao,
    private val collections: AutoCollectionRepository,
    private val listeningStatsTracker: ListeningStatsTracker,
    private val duplicateDetector: DuplicateDetector,
) {

    /**
     * The diagnostic for [libraryItemId], or for the track that is playing when it is null.
     *
     * Returns null only when there is nothing to describe at all — no session and no library item.
     */
    suspend fun forCurrentTrack(libraryItemId: String? = null): TrackIntelligenceDiagnostic? {
        val session = listeningStatsTracker.sessionSnapshot()
        val ledger = ledgers.ledger()

        val target = libraryItemId?.takeIf { it.isNotBlank() }
            ?: session?.libraryItemId?.takeIf { it.isNotBlank() }
            ?: mostRecentlyPlayedLibraryItemId(ledger)
            ?: return null

        val item = libraryItemDao.getById(target)
        val stats = ledger.stats(target)
        val catalogItem = ledger.catalog.item(target)

        // An item can legitimately have no projection — that is precisely the state this diagnostic exists to make
        // visible, so it is described rather than skipped.
        val locator = item?.currentLocator
        val projection = locator?.let { musicDao.getSongByLocatorOnce(it) }
        val legacy = engagementDao.getEngagementByLibraryItem(target)
            ?: locator?.let { engagementDao.getEngagement(it.toString()) }
        val membership = collections.state.value.collections.map { collection ->
            TrackIntelligenceDiagnostic.CollectionMembership(
                key = collection.id.key,
                isMember = collection.items.any { it.identity == target },
            )
        }

        return TrackIntelligenceDiagnostic(
            libraryItemId = target,
            currentLocator = locator,
            lifecycle = item?.lifecycle ?: "UNKNOWN",
            stableKey = item?.stableKey.orEmpty(),
            fileSignature = item?.fileSignature.orEmpty(),
            projectionExists = projection != null,
            title = projection?.title ?: catalogItem?.title ?: "—",
            artist = projection?.artistName ?: catalogItem?.artistLine ?: "—",
            album = projection?.albumName ?: catalogItem?.album ?: "—",
            playCount = stats?.plays ?: 0,
            completionCount = stats?.completions ?: 0,
            skipCount = stats?.skips ?: 0,
            totalListenedMs = stats?.listenedMs ?: 0L,
            firstPlayedAt = stats?.firstPlayMs ?: 0L,
            lastPlayedAt = stats?.lastPlayMs ?: 0L,
            lastCompletedAt = stats?.lastCompletionMs ?: 0L,
            lastSkippedAt = stats?.lastSkipMs ?: 0L,
            legacyPlayCount = legacy?.playCount,
            legacySkipCount = legacy?.skipCount,
            collections = membership,
            session = session?.let {
                TrackIntelligenceDiagnostic.SessionView(
                    listenedMs = it.listenedMs,
                    totalDurationMs = it.totalDurationMs,
                    isPlaying = it.isPlaying,
                    endReason = it.endReason,
                    projectedOutcome = it.projectedOutcome,
                )
            },
        )
    }

    /**
     * What the app believes about this track's metadata, and why it grouped it where it did.
     *
     * Read-only, and computed from the stored row plus the same `MetadataNormalizer` the scan used —
     * so the diagnostic cannot disagree with the grouping it is describing.
     */
    suspend fun metadataFor(libraryItemId: String): MetadataDiagnostic? {
        val projection = libraryItemDao.getById(libraryItemId)?.currentLocator
            ?.let { musicDao.getSongByLocatorOnce(it) }
            ?: return null

        val candidates = ledgers.ledger().catalog.items.map {
            DuplicateCandidate(it.identity, it.title, it.artistLine, it.song.albumId, it.lengthMs)
        }
        val duplicateGroup = duplicateDetector.findGroups(candidates)
            .firstOrNull { group -> group.tracks.any { it.libraryItemId == libraryItemId } }

        return MetadataDiagnostic(
            libraryItemId = libraryItemId,
            rawTitle = projection.title,
            rawArtist = projection.artistName,
            rawAlbum = projection.albumName,
            rawAlbumArtist = projection.albumArtist,
            rawGenre = projection.genre,
            rawTrack = projection.rawTrackNumber,
            rawYear = projection.year,
            resolvedTitle = MetadataNormalizer.normalizeForDisplay(projection.title),
            trackArtist = MetadataNormalizer.normalizeForDisplay(projection.artistName),
            albumArtist = MetadataNormalizer.resolveAlbumArtist(projection.albumArtist, projection.artistName),
            albumTitle = MetadataNormalizer.normalizeForDisplay(projection.albumName),
            genres = projection.genresJson?.split('\u0000')?.filter { it.isNotBlank() }
                ?: MetadataNormalizer.parseGenres(projection.genre),
            releaseYear = MetadataNormalizer.parseYear(projection.year),
            albumGroupingKey = projection.albumGroupingKey,
            artistGroupingKey = projection.artistGroupingKey,
            disc = projection.discNumber,
            track = projection.trackNumber.takeIf { it > 0 },
            firstSeenAt = libraryItemDao.getById(libraryItemId)?.firstSeenAt ?: 0L,
            dateAdded = projection.dateAdded,
            isCompilation = projection.isCompilation,
            duplicateGroup = duplicateGroup?.let { group ->
                "${group.size} items · ${group.evidence}"
            },
        )
    }

    /**
     * The fallback when nothing is playing.
     *
     * Deliberately the most recently played item rather than an arbitrary one: a diagnostic that
     * opens on a random track tells you nothing, while one that opens on what you just listened to
     * is usually already answering the question you came with.
     */
    private fun mostRecentlyPlayedLibraryItemId(ledger: com.lostf1sh.pixelplayeross.data.stats.ListeningLedger): String? =
        ledger.occurrences.lastOrNull { it.identity != null }?.identity
}
