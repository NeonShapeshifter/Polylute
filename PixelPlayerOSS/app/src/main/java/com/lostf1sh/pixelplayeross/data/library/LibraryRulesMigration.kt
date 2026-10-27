package com.lostf1sh.pixelplayeross.data.library

import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * B39-A9: the one-time move of an install of 1.0 from "everything unless excluded" to "nothing unless included", without the library
 * changing: the folders that hold its songs today (read from the database, never from the file system, and **before any sync runs**) become the
 * included set. Favourites, history, stats, lyrics, playlists and the identity key are not touched: no song row is read for anything but its folder
 * and none is written.
 *
 * **It cannot empty a library.** The new rules are checked against every song folder before anything is written (they must answer exactly as
 * the old ones do), the write is one edit that happens only if the stored rules are still the ones it computed from, and anything that fails or
 * does not match leaves the old rules in place, to be tried again at the next start. Nothing is retried in a loop.
 *
 * Guarded by one lock for the process: the sync worker and the app's start both call it, and the second finds it done.
 */
class LibraryRulesMigration(
    private val preferences: UserPreferencesRepository,
    private val songDirectories: suspend () -> List<String>,
    private val storageRoots: () -> List<String>,
) {
    enum class Outcome {
        /** Already include-only (an earlier run, or an install that began that way). */
        ALREADY,

        /** Done now. */
        MIGRATED,

        /** There is no song to build the included set from (a first run, or an empty library): the old rules stay and nothing was lost. */
        NOTHING_TO_BUILD_FROM,

        /** It did not go through (the new rules would not have kept the library, the rules moved meanwhile, or something failed): the old rules stay. */
        KEPT_OLD_RULES,
    }

    suspend fun runIfNeeded(): Outcome = lock.withLock {
        try {
            val current = preferences.currentDirectoryRules()
            if (preferences.directoryRulesIncludeOnlyFlowValue()) return@withLock Outcome.ALREADY
            val directories = songDirectories()
            if (directories.none { it.isNotBlank() }) return@withLock Outcome.NOTHING_TO_BUILD_FROM
            val migrated = LibraryFolders.migrate(current, directories, storageRoots())
                ?: return@withLock Outcome.KEPT_OLD_RULES.also {
                    Timber.tag(TAG).w("The include-only rules would not give every song its answer; the old rules stay")
                }
            if (!preferences.migrateDirectoryRulesToIncludeOnly(expected = current, migrated = migrated)) {
                Timber.tag(TAG).w("The rules changed while the migration was computed; the old rules stay")
                return@withLock Outcome.KEPT_OLD_RULES
            }
            Timber.tag(TAG).i(
                "Library rules are include-only now: %d folders from %d song folders (old exclusions kept: %d)",
                migrated.allowed.size - current.allowed.size, directories.distinct().size, current.blocked.size,
            )
            Outcome.MIGRATED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Library rules migration failed; the old rules stay")
            Outcome.KEPT_OLD_RULES
        }
    }

    companion object {
        private const val TAG = "LibraryRules"
        private val lock = Mutex()

        /** The migration over what the sync worker and the app have: the DAO's folders and the volumes mounted now. */
        fun of(context: android.content.Context, preferences: UserPreferencesRepository, musicDao: com.lostf1sh.pixelplayeross.data.database.MusicDao) =
            LibraryRulesMigration(
                preferences = preferences,
                songDirectories = { musicDao.getDistinctParentDirectories() },
                storageRoots = { storageRootsOf(context) },
            )
    }
}
