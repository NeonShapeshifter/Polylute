package com.lostf1sh.pixelplayeross.data.library

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.FavoritesEntity
import com.lostf1sh.pixelplayeross.data.identity.DormantCleanupRig
import com.lostf1sh.pixelplayeross.data.worker.LibraryRefreshIntegrationTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B39-A9, against the real sync: Room in memory, the real worker over a fake MediaStore, the real preferences logic. A library like the
 * owner's (one root with nested artist/album folders, a stray WhatsApp audio folder that was excluded) moves to "the library includes"
 * without losing a song, an identity or a favourite; a new folder stays out until it is included; a migration that fails changes nothing.
 * Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryFolderUpgradeTest {
    private lateinit var rig: DormantCleanupRig
    private val marker = LibraryRefreshIntegrationTest.MARKER

    private val storage = "/storage/emulated/0"
    private val dap = "$storage/Music/DAP music"
    private val whatsapp = "$storage/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"
    private val newFolder = "$storage/Music/A new folder"

    @Before fun setUp() { rig = DormantCleanupRig() }
    @After fun tearDown() { rig.close() }

    private var nextDuration = 180_000L

    private fun song(id: Long, title: String, folder: String) {
        rig.media.add(id, title, marker / 1000 + 1, path = "$folder/$id.flac", duration = nextDuration)
        nextDuration += 20_000L
    }

    private fun migration() = LibraryRulesMigration.of(rig.context, rig.prefs, rig.dao)

    /** What 1.0 left on the owner's phone: a library synced under "everything unless excluded", the WhatsApp audio excluded. */
    private suspend fun libraryOfVersion1(): List<Long> {
        rig.prefs.updateDirectorySelections(emptySet(), setOf(whatsapp))
        rig.prefs.setLastSyncTimestamp(marker)
        song(1, "One", "$dap/Artist A/Album 1")
        song(2, "Two", "$dap/Artist A/Album 2")
        song(3, "Three", "$dap/Artist B/Album 3")
        song(4, "Four", "$dap/Artist C")
        song(90, "Voice note", whatsapp)
        rig.refreshSucceeds()
        return listOf(1L, 2L, 3L, 4L)
    }

    private suspend fun songIds() = rig.dao.getAllSongs().first().map { it.id }.toSet()
    private suspend fun identities() = rig.dao.getAllLibraryItems().map { it.libraryItemId }.toSet()

    @Test fun `an upgrade keeps the owner's songs, identities and favourites and the migration runs once`() = runBlocking<Unit> {
        val library = libraryOfVersion1()
        assertThat(songIds()).isEqualTo(library.toSet())
        val identitiesBefore = identities()
        val favourite = rig.identityOf(2)
        rig.db.favoritesDao().insertAll(listOf(FavoritesEntity(songId = 2, libraryItemId = favourite)))

        assertThat(migration().runIfNeeded()).isEqualTo(LibraryRulesMigration.Outcome.MIGRATED)
        val rules = rig.prefs.currentDirectoryRules()
        assertThat(rules.includeOnly).isTrue()
        assertThat(rules.allowed).containsExactly(dap)
        assertThat(rules.blocked).containsExactly(whatsapp, "/")
        assertThat(rig.prefs.directoryRulesIncludeOnlyFlowValue()).isTrue()

        // the next sync, under the new rule: nothing leaves, nothing is renumbered
        rig.refreshSucceeds()
        assertThat(songIds()).isEqualTo(library.toSet())
        assertThat(identities()).isEqualTo(identitiesBefore)
        for (id in library) assertThat(rig.identityOf(id)).isNotEmpty()
        assertThat(rig.db.favoritesDao().getFavoriteLibraryItemIds()).containsExactly(favourite)
        assertThat(rig.identityOf(2)).isEqualTo(favourite)

        // once: a second run changes nothing, and neither does another sync
        assertThat(migration().runIfNeeded()).isEqualTo(LibraryRulesMigration.Outcome.ALREADY)
        assertThat(rig.prefs.currentDirectoryRules()).isEqualTo(rules)
    }

    @Test fun `the new rule without the migration would empty that library, which is why the migration is written first`() = runBlocking<Unit> {
        val library = libraryOfVersion1()
        assertThat(songIds()).isEqualTo(library.toSet())
        // what "nothing unless included" does to a library nobody migrated: the root block and no included folder
        rig.prefs.updateDirectorySelections(emptySet(), setOf(whatsapp, LibraryFolders.NOTHING_BY_DEFAULT))
        rig.refreshSucceeds()
        assertThat(songIds()).isEmpty()
    }

    @Test fun `the sync itself migrates before it reads the rules, so an upgrade needs no other step`() = runBlocking<Unit> {
        val library = libraryOfVersion1()
        val identitiesBefore = identities()
        // no explicit migration: the worker does it first
        rig.refreshSucceeds()
        assertThat(rig.prefs.currentDirectoryRules().includeOnly).isTrue()
        assertThat(songIds()).isEqualTo(library.toSet())
        assertThat(identities()).isEqualTo(identitiesBefore)
    }

    @Test fun `a folder that appears later stays out until it is included, and excluding it takes its songs out again`() = runBlocking<Unit> {
        val library = libraryOfVersion1()
        assertThat(migration().runIfNeeded()).isEqualTo(LibraryRulesMigration.Outcome.MIGRATED)

        song(100, "Newcomer", newFolder)
        rig.refreshSucceeds()
        assertThat(songIds()).isEqualTo(library.toSet())

        rig.prefs.includeDirectories(listOf(newFolder))
        rig.refreshSucceeds()
        assertThat(songIds()).isEqualTo(library.toSet() + 100L)

        val rules = rig.prefs.currentDirectoryRules()
        rig.prefs.updateDirectorySelections(rules.allowed - newFolder, rules.blocked + newFolder)
        rig.refreshSucceeds()
        assertThat(songIds()).isEqualTo(library.toSet())
        // the songs that were there all along are untouched through it all
        assertThat(rig.dao.getActiveSongByLocator(1)).isNotNull()
    }

    @Test fun `a migration that fails leaves the old rules in place and the library syncs as it did`() = runBlocking<Unit> {
        val library = libraryOfVersion1()
        val before = rig.prefs.currentDirectoryRules()
        val failing = LibraryRulesMigration(rig.prefs, songDirectories = { error("the database is not there") }, storageRoots = { listOf(storage) })
        assertThat(failing.runIfNeeded()).isEqualTo(LibraryRulesMigration.Outcome.KEPT_OLD_RULES)
        assertThat(rig.prefs.currentDirectoryRules()).isEqualTo(before)
        assertThat(rig.prefs.directoryRulesIncludeOnlyFlowValue()).isFalse()
        assertThat(rig.prefs.currentDirectoryRules().includeOnly).isFalse()
        song(100, "Newcomer", newFolder)
        rig.media.sql.execSQL("DELETE FROM audio WHERE _id = 100")
        rig.refreshSucceeds()
        assertThat(songIds()).isEqualTo(library.toSet())
    }

    @Test fun `rules that change while the migration is computed are never overwritten`() = runBlocking<Unit> {
        libraryOfVersion1()
        val moved = LibraryRulesMigration(
            rig.prefs,
            songDirectories = {
                // the listener changed the folders while the migration was reading the database
                rig.prefs.updateDirectorySelections(emptySet(), setOf(whatsapp, "$storage/Music/Podcasts"))
                rig.dao.getDistinctParentDirectories()
            },
            storageRoots = { listOf(storage) },
        )
        assertThat(moved.runIfNeeded()).isEqualTo(LibraryRulesMigration.Outcome.KEPT_OLD_RULES)
        assertThat(rig.prefs.currentDirectoryRules().blocked).containsExactly(whatsapp, "$storage/Music/Podcasts")
        assertThat(rig.prefs.directoryRulesIncludeOnlyFlowValue()).isFalse()
    }

    // ── the first run, and the way back ─────────────────────────────────────────────────────────────

    private fun finder(
        audio: Map<String, Int>,
        empty: Boolean,
        migration: LibraryRulesMigration = LibraryRulesMigration(rig.prefs, songDirectories = { rig.dao.getDistinctParentDirectories() }, storageRoots = { listOf(storage) }),
    ) = LibraryFolderFinder(
        preferences = rig.prefs,
        migration = migration,
        audioDirectories = { audio },
        libraryIsEmpty = { empty },
        storageRoots = { listOf(storage) },
    )

    private val phone = mapOf(
        "$dap/Artist A/Album 1" to 12,
        "$dap/Artist B/Album 3" to 9,
        whatsapp to 40,
    )

    @Test fun `a first run proposes the folders with audio, already included in the sense that one tap adds them all`() = runBlocking<Unit> {
        val found = finder(phone, empty = true)
        val first = found.firstRun()
        assertThat(first).isInstanceOf(LibraryFolderFinder.FirstRun.Propose::class.java)
        val folders = (first as LibraryFolderFinder.FirstRun.Propose).folders
        assertThat(folders.map { it.path }).containsExactly(whatsapp, dap).inOrder()
        assertThat(folders.first { it.path == dap }.songCount).isEqualTo(21)
        // from now on nothing is in until it is chosen: a sync before the answer takes in nothing
        assertThat(rig.prefs.currentDirectoryRules().includeOnly).isTrue()
        song(1, "One", "$dap/Artist A/Album 1")
        rig.refreshSucceeds()
        assertThat(songIds()).isEmpty()

        // one tap: everything proposed
        assertThat(found.add(folders.map { it.path })).isEqualTo(LibraryFolderFinder.AddResult.ADDED)
        rig.refreshSucceeds()
        assertThat(songIds()).containsExactly(1L)
    }

    @Test fun `a first run lets the listener leave a folder out`() = runBlocking<Unit> {
        val found = finder(phone, empty = true)
        val folders = (found.firstRun() as LibraryFolderFinder.FirstRun.Propose).folders
        val chosen = folders.map { it.path }.filterNot { it == whatsapp }
        found.add(chosen)
        song(1, "One", "$dap/Artist A/Album 1")
        song(90, "Voice note", whatsapp)
        rig.refreshSucceeds()
        assertThat(songIds()).containsExactly(1L)
        assertThat(rig.prefs.currentDirectoryRules().allowed).containsExactly(dap)
    }

    @Test fun `a first run with no music anywhere is include-only from the start and syncs`() = runBlocking<Unit> {
        val found = finder(emptyMap(), empty = true)
        assertThat(found.firstRun()).isEqualTo(LibraryFolderFinder.FirstRun.Sync)
        assertThat(rig.prefs.currentDirectoryRules().includeOnly).isTrue()
    }

    @Test fun `an upgrade at app start goes straight to sync because its library is already migrated`() = runBlocking<Unit> {
        libraryOfVersion1()
        val found = finder(phone, empty = false)
        assertThat(found.firstRun()).isEqualTo(LibraryFolderFinder.FirstRun.Sync)
        assertThat(rig.prefs.currentDirectoryRules().includeOnly).isTrue()
    }

    @Test fun `find folders offers what is not in the library yet and is the way back from an empty library`() = runBlocking<Unit> {
        libraryOfVersion1()
        migration().runIfNeeded()
        val found = finder(phone + (newFolder to 3), empty = false)
        val offered = found.scan()
        // the DAP music is in, the excluded WhatsApp audio is offered again (it is not in), and the new folder is new
        assertThat(offered.map { it.path }).containsExactly(whatsapp, newFolder)
        assertThat(found.add(listOf(newFolder))).isEqualTo(LibraryFolderFinder.AddResult.ADDED)
        assertThat(rig.prefs.currentDirectoryRules().allowed).containsAtLeast(dap, newFolder)
        assertThat(found.add(emptyList())).isEqualTo(LibraryFolderFinder.AddResult.NOTHING)
    }

    @Test fun `with the old rules still in place and a library to lose nothing is added`() = runBlocking<Unit> {
        libraryOfVersion1()
        val failing = LibraryRulesMigration(rig.prefs, songDirectories = { error("not now") }, storageRoots = { listOf(storage) })
        val found = finder(phone, empty = false, migration = failing)
        val before = rig.prefs.currentDirectoryRules()
        assertThat(found.add(listOf(newFolder))).isEqualTo(LibraryFolderFinder.AddResult.NOT_READY)
        assertThat(rig.prefs.currentDirectoryRules()).isEqualTo(before)
    }

    // ── the folders sheet is the picker ─────────────────────────────────────────────────────────────

    @Test fun `ticking a folder in the sheet includes it, unticking leaves it out, and the root block stays through both`() = runBlocking<Unit> {
        libraryOfVersion1()
        migration().runIfNeeded()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        try {
            val sheet = com.lostf1sh.pixelplayeross.presentation.viewmodel.FileExplorerStateHolder(rig.prefs, scope, rig.context)
            kotlinx.coroutines.withTimeout(5_000) { sheet.blockedDirectories.first { "/" in it } }
            val folder = java.io.File(newFolder)

            sheet.toggleDirectoryAllowed(folder)
            var rules = rig.prefs.currentDirectoryRules()
            assertThat(rules.includeOnly).isTrue()
            assertThat(rules.includes(newFolder)).isTrue()
            assertThat(rules.includes("$newFolder/Artist/Album")).isTrue()

            sheet.toggleDirectoryAllowed(folder)
            rules = rig.prefs.currentDirectoryRules()
            assertThat(rules.includeOnly).isTrue()
            assertThat(rules.includes(newFolder)).isFalse()
            // the library that was already there is untouched by the picking
            assertThat(rules.includes("$dap/Artist A/Album 1")).isTrue()
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
