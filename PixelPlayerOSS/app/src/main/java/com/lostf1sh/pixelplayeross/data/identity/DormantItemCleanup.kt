package com.lostf1sh.pixelplayeross.data.identity

import com.lostf1sh.pixelplayeross.data.database.LibraryItemDao
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ListeningStatsTracker
import javax.inject.Inject
import timber.log.Timber

/**
 * The manual Maintenance clean-up of "items without a file" (AUD-106, R28-01).
 *
 * An item is removable when the database says it is dormant and carries no user state (`PURGEABLE_DORMANT_WHERE`: favourite, lyric,
 * edit, legacy counter, playlist entry, bookmark) **and** the listening history does not name it. The history is where plays live
 * since B28 (`playback_history.json`), and an event names its track by this item: remove the item, and when the file comes back it is
 * a new one, and the plays it was heard for belong to no track.
 *
 * Three things name an item in the history, and all three are checked:
 *  - the events in the file, read while no listen can be written ([PlaybackStatsRepository.withHistoryIdentities]);
 *  - the listen in progress and the ones that ended and are still being written ([ListeningStatsTracker.identitiesInFlight]), **after**
 *    the identity lookups that are still running have answered: a listen opens with the locator, and for a moment the tracker does not
 *    know which item it is (R28-01-F1). The wait is bounded, suspends off the main thread and holds no lock of the tracker; a lookup
 *    that failed or was cancelled leaves nothing, and one that does not answer in time makes the clean-up remove nothing this time,
 *    because what it was about to name cannot be told apart from an item nobody listened to;
 *  - what the database says again at the moment of the delete, so something saved or brought back since is kept too.
 *
 * The count the listener is shown and the delete take the same selection, and both hold the history's write gate while they do.
 *
 * It holds no state of its own (the repository, the tracker and the database are the singletons), so it has no scope.
 */
class DormantItemCleanup @Inject constructor(
    private val libraryItemDao: LibraryItemDao,
    private val history: PlaybackStatsRepository,
    private val tracker: ListeningStatsTracker,
) {
    /** How many items [remove] would remove now. */
    suspend fun count(): Int = history.withHistoryIdentities { named -> removable(named).size }

    /** Removes them and says how many went. Only ever called from a confirmation. */
    suspend fun remove(): Int = history.withHistoryIdentities { named ->
        removable(named).chunked(DELETE_CHUNK).sumOf { libraryItemDao.deletePurgeableDormant(it) }
    }

    /** How long a lookup that is still running is waited for; a test replaces it. */
    internal var lookupWaitMs = ListeningStatsTracker.IDENTITY_LOOKUP_WAIT_MS

    /** The dormant items without user state that no listen names. Called with the history held. */
    private suspend fun removable(named: Set<String>): List<String> {
        val listening = tracker.identitiesInFlight(lookupWaitMs)
        if (!listening.settled) {
            Timber.w("An identity lookup of a listen is still running after %d ms: nothing is removed this time", lookupWaitMs)
            return emptyList()
        }
        return libraryItemDao.purgeableDormantIds().filter { it !in named && it !in listening.identities }
    }

    private companion object {
        /** Well under SQLite's limit on variables in one statement, on every Android version. */
        const val DELETE_CHUNK = 500
    }
}
