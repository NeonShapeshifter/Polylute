package com.lostf1sh.pixelplayeross.data.library

import android.content.Context
import android.provider.MediaStore
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.utils.StorageUtils
import com.lostf1sh.pixelplayeross.utils.buildLocalAudioSelection
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * B39-A9: finds the folders that hold music, and puts the ones the listener chooses in the library.
 *
 * Three moments use it, with the same scan: a **first run** (nothing in the library: the folders with audio are proposed already included, one tap
 * from done), the **empty library** (the way back), and the permanent "Find folders with music" action in Settings, Library. The scan asks
 * MediaStore which folders have audio and keeps the ones that are not in the library yet.
 */
class LibraryFolderFinder(
    private val preferences: UserPreferencesRepository,
    private val migration: LibraryRulesMigration,
    private val audioDirectories: suspend (minDurationMs: Int) -> Map<String, Int>,
    private val libraryIsEmpty: suspend () -> Boolean,
    private val storageRoots: () -> List<String>,
) {
    /** What the app does at the start of a sync. */
    sealed interface FirstRun {
        /** Go on and sync: the library has its rules (an install of 1.0 was migrated, or this one began include-only). */
        data object Sync : FirstRun

        /** A first run that found music: propose these, already included, and sync once the listener has answered. */
        data class Propose(val folders: List<LibraryFolders.Proposal>) : FirstRun
    }

    enum class AddResult {
        /** In the library now; a sync follows. */
        ADDED,

        /** The rules are still the old ones and the library is not empty (the migration did not go through): nothing is changed rather than risk it. */
        NOT_READY,

        /** Nothing was chosen. */
        NOTHING,
    }

    /** The folders with audio that are not in the library, ready to add. Empty when everything MediaStore knows is already in. */
    suspend fun scan(): List<LibraryFolders.Proposal> {
        migration.runIfNeeded()
        val rules = preferences.currentDirectoryRules()
        val minDuration = preferences.getMinSongDuration()
        return LibraryFolders.proposals(audioDirectories(minDuration), rules, storageRoots())
    }

    /** What to do before the first sync of this run (B39-A9). */
    suspend fun firstRun(): FirstRun = when (migration.runIfNeeded()) {
        LibraryRulesMigration.Outcome.ALREADY,
        LibraryRulesMigration.Outcome.MIGRATED,
        // the old rules stay, and the app behaves as 1.0 until the next start tries again
        LibraryRulesMigration.Outcome.KEPT_OLD_RULES -> FirstRun.Sync

        LibraryRulesMigration.Outcome.NOTHING_TO_BUILD_FROM -> {
            // The library is include-only from here, before anything can scan it: until the listener has answered (or when there is no music
            // yet) a sync, whatever starts it, takes in nothing, and music that appears later waits to be included.
            preferences.includeDirectories(emptyList())
            val found = scan()
            if (found.isEmpty()) FirstRun.Sync else FirstRun.Propose(found)
        }
    }

    /** Puts [folders] in the library. */
    suspend fun add(folders: Collection<String>): AddResult {
        if (folders.isEmpty()) return AddResult.NOTHING
        migration.runIfNeeded()
        val rules = preferences.currentDirectoryRules()
        if (!rules.includeOnly && !libraryIsEmpty()) return AddResult.NOT_READY
        preferences.includeDirectories(folders)
        return AddResult.ADDED
    }
}

/** The finder and the migration, built from what the app has (B39-A9). */
@Singleton
class LibraryFolderService @Inject constructor(
    @ApplicationContext private val context: Context,
    preferences: UserPreferencesRepository,
    musicDao: MusicDao,
) {
    private fun storageRoots(): List<String> = storageRootsOf(context)

    val migration = LibraryRulesMigration.of(context, preferences, musicDao)

    val finder = LibraryFolderFinder(
        preferences = preferences,
        migration = migration,
        audioDirectories = { minDurationMs -> MediaStoreAudioDirectories.query(context, minDurationMs) },
        libraryIsEmpty = { musicDao.getDistinctParentDirectories().none { it.isNotBlank() } },
        storageRoots = ::storageRoots,
    )
}

/** The volumes mounted now, by path (an unreadable list is an empty one: the usual forms of a storage root are recognised from the path itself). */
internal fun storageRootsOf(context: Context): List<String> =
    runCatching { StorageUtils.getAvailableStorages(context).map { it.path.absolutePath } }.getOrDefault(emptyList())

/** The folders MediaStore has audio in, with how many songs each holds directly (the selection the sync itself uses, minus hidden folders). */
object MediaStoreAudioDirectories {
    suspend fun query(context: Context, minDurationMs: Int): Map<String, Int> = withContext(Dispatchers.IO) {
        val (selection, args) = buildLocalAudioSelection(minDurationMs)
        val counts = LinkedHashMap<String, Int>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DATA),
            selection,
            args,
            null,
        )?.use { cursor ->
            val data = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val parent = cursor.getString(data)?.let { File(it).parentFile?.absolutePath } ?: continue
                if (parent.contains("/.")) continue
                counts[parent] = (counts[parent] ?: 0) + 1
            }
        }
        counts
    }
}
