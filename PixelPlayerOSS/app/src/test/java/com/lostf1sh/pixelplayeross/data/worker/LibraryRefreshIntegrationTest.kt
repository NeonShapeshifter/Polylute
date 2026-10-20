package com.lostf1sh.pixelplayeross.data.worker

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.lostf1sh.pixelplayeross.data.database.*
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.every
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Runs the real worker, SQL MediaStore selection, identity reconciliation and Room writes/flows.
 * Only unrelated maintenance collaborators are mocked. No phone, native decoder or real music IO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryRefreshIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: PixelPlayerDatabase
    private lateinit var media: AudioProvider
    private lateinit var prefs: UserPreferencesRepository
    private val dao get() = db.musicDao()

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).allowMainThreadQueries().build()
        media = AudioProvider()
        ShadowContentResolver.registerProviderInternal("media", media)
        prefs = UserPreferencesRepository(MemoryPreferences(), Json { ignoreUnknownKeys = true })
        prefs.setLastSyncTimestamp(MARKER)
    }

    @After fun close() { db.close(); media.sql.close() }

    private suspend fun refresh(): ListenableWorker.Result = TestListenableWorkerBuilder<SyncWorker>(context)
        .setInputData(workDataOf(SyncWorker.INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name,
            SyncWorker.INPUT_RUN_MAINTENANCE to false))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                SyncWorker(appContext, workerParameters, dao, db.libraryItemOverrideDao(),
                    mockk(relaxed = true), prefs, mockk(relaxed = true))
        }).build().doWork()

    private suspend fun seedKnown() {
        media.add(1, "Known", MARKER / 1000 + 1)
        assertTrue(refresh() is ListenableWorker.Result.Success)
        prefs.setLastSyncTimestamp(MARKER)
        media.sql.execSQL("UPDATE audio SET date_modified=20, date_added=20")
        // Keep the projection's timestamps aligned so the known song is unchanged.
        db.openHelper.writableDatabase.execSQL("UPDATE songs SET media_store_date_modified=20, media_store_date_added=20")
        media.metadataIds.clear()
    }

    @Test fun old_missing_and_new_tracks_reach_an_already_subscribed_list_and_repeat_is_cheap() = runBlocking {
        seedKnown()
        val paging = dao.getSongsPaginated(emptyList(), false, "title_asc", 0)
        val load = androidx.paging.PagingSource.LoadParams.Refresh<Int>(null, 50, false)
        assertEquals(1, (paging.load(load) as androidx.paging.PagingSource.LoadResult.Page).data.size)
        val invalidated = CompletableDeferred<Unit>()
        paging.registerInvalidatedCallback { invalidated.complete(Unit) }
        val emissions = java.util.concurrent.CopyOnWriteArrayList<List<SongEntity>>()
        val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            dao.getAllSongs().collect { emissions += it }
        }
        withTimeout(5_000) { while (emissions.isEmpty()) delay(10) }
        media.add(2, "Old but indexed", 10)
        media.add(3, "New", MARKER / 1000 + 5, path = "/other/new.flac")
        val result = refresh()
        assertTrue(result is ListenableWorker.Result.Success)
        withTimeout(5_000) { while (emissions.none { it.size == 3 }) delay(10) }
        assertEquals(setOf(1L, 2L, 3L), dao.getAllSongs().first().map { it.id }.toSet())
        assertEquals(3, dao.getSongCount().first())
        assertEquals(3, dao.countActiveProjections())
        assertEquals(2, dao.getAllSongs(listOf("/music"), true).first().size)
        withTimeout(5_000) { invalidated.await() }
        val refreshedPage = dao.getSongsPaginated(emptyList(), false, "title_asc", 0).load(load)
        assertEquals(3, (refreshedPage as androidx.paging.PagingSource.LoadResult.Page).data.size)
        assertEquals(setOf(2L, 3L), media.metadataIds.toSet())
        val identities = dao.getAllLibraryItems().map { it.libraryItemId }.toSet()
        media.metadataIds.clear()
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertTrue("known old songs must not enter metadata query", media.metadataIds.isEmpty())
        assertEquals(identities, dao.getAllLibraryItems().map { it.libraryItemId }.toSet())
        job.cancelAndJoin()
    }

    @Test fun missing_projection_and_return_at_an_old_renumbered_locator_keep_identity_and_user_state() = runBlocking {
        seedKnown()
        val identity = dao.getSongByLocatorOnce(1)!!.libraryItemId
        db.favoritesDao().insertAll(listOf(FavoritesEntity(songId = 1, libraryItemId = identity)))
        db.lyricsDao().insert(LyricsEntity(songId = 1, libraryItemId = identity, content = "user lyrics"))
        db.libraryItemOverrideDao().insertIfAbsent(LibraryItemOverrideEntity(libraryItemId = identity,
            title = "User title", titleUserEdited = true))
        dao.deleteSongsByIds(listOf(1))
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertEquals(identity, dao.getActiveSongByLocator(1)!!.libraryItemId)
        media.sql.execSQL("DELETE FROM audio")
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertNull(dao.getActiveSongByLocator(1))
        media.add(77, "Known", 20)
        assertTrue(refresh() is ListenableWorker.Result.Success)
        val returned = dao.getActiveSongByLocator(77)!!
        assertEquals(identity, returned.libraryItemId)
        assertEquals("User title", returned.title)
        assertEquals(listOf(identity), db.favoritesDao().getFavoriteLibraryItemIds())
        assertEquals("user lyrics", db.lyricsDao().getLyricsForLibraryItem(identity)?.content)
        assertEquals(1, dao.getAllLibraryItems().size)
    }

    @Test fun directory_and_duration_filters_apply_to_inventory_and_metadata_and_changes_converge() = runBlocking {
        seedKnown()
        prefs.updateDirectorySelections(emptySet(), setOf("/blocked"))
        media.add(2, "Too short", 10, duration = 100)
        media.add(3, "Blocked", 10, path = "/blocked/3.flac")
        media.add(4, "Allowed", 10)
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertEquals(setOf(1L, 4L), dao.getAllSongs().first().map { it.id }.toSet())
        media.sql.execSQL("UPDATE audio SET title='Changed', date_modified=${System.currentTimeMillis()/1000} WHERE _id=4")
        media.sql.execSQL("DELETE FROM audio WHERE _id=1")
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertEquals(listOf("Changed"), dao.getAllSongs().first().map { it.title })
    }

    @Test fun an_orphan_projection_with_empty_identity_is_repaired_on_a_normal_update() = runBlocking {
        seedKnown()
        media.add(2, "Orphan", System.currentTimeMillis() / 1000 + 1)
        assertTrue(refresh() is ListenableWorker.Result.Success)
        val oldIdentity = dao.getActiveSongByLocator(2)!!.libraryItemId
        media.sql.execSQL("UPDATE audio SET date_modified=20, date_added=20 WHERE _id=2")
        db.openHelper.writableDatabase.execSQL("UPDATE songs SET media_store_date_modified=20, media_store_date_added=20 WHERE id=2")
        db.openHelper.writableDatabase.execSQL("DELETE FROM library_items WHERE library_item_id=?", arrayOf<Any>(oldIdentity))
        dao.assignLibraryItemId(2, "")
        assertTrue(refresh() is ListenableWorker.Result.Success)
        val repaired = dao.getActiveSongByLocator(2)!!
        assertTrue(repaired.libraryItemId.isNotEmpty())
        assertEquals(2, dao.countActiveProjections())
        media.metadataIds.clear()
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertTrue(media.metadataIds.isEmpty())
    }

    @Test fun old_dated_retag_keeps_user_identity_even_when_legacy_seed_has_no_file_signature() = runBlocking {
        seedKnown()
        val identity = dao.getActiveSongByLocator(1)!!.libraryItemId
        db.favoritesDao().insertAll(listOf(FavoritesEntity(songId = 1, libraryItemId = identity)))
        db.openHelper.writableDatabase.execSQL("UPDATE library_items SET file_signature=''")
        media.sql.execSQL("UPDATE audio SET title='Retagged', date_modified=20")
        assertTrue(refresh() is ListenableWorker.Result.Success)
        val updated = dao.getActiveSongByLocator(1)!!
        assertEquals("Retagged", updated.title)
        assertEquals(identity, updated.libraryItemId)
        assertTrue(updated.isFavorite)
        media.metadataIds.clear()
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertTrue(media.metadataIds.isEmpty())
    }

    @Test fun failed_or_cancelled_metadata_query_does_not_advance_marker_and_old_rows_recover() = runBlocking {
        seedKnown()
        media.add(2, "Old retry", 10)
        media.failure = IllegalStateException("transient metadata query")
        assertTrue(refresh() is ListenableWorker.Result.Failure)
        assertEquals(MARKER, prefs.getLastSyncTimestamp())
        assertEquals(1, dao.getSongCount().first())
        media.failure = CancellationException("cancelled")
        try { refresh(); fail("cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(MARKER, prefs.getLastSyncTimestamp())
        media.failure = null
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertNotNull(dao.getActiveSongByLocator(2))
    }

    @Test fun a_per_file_processing_error_keeps_the_marker_and_recovers_on_next_update() = runBlocking {
        seedKnown()
        media.add(2, "Retry one file", 10)
        mockkObject(AlbumArtUtils)
        try {
            every { AlbumArtUtils.getAlbumArtUriForLibraryScan(any(), 2, any()) } throws java.io.IOException("transient per-file read")
            assertTrue(refresh() is ListenableWorker.Result.Failure)
            assertEquals(MARKER, prefs.getLastSyncTimestamp())
            assertNull(dao.getActiveSongByLocator(2))
        } finally { unmockkObject(AlbumArtUtils) }
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertNotNull(dao.getActiveSongByLocator(2))
    }

    @Test fun null_inventory_does_not_mark_the_library_absent() = runBlocking {
        seedKnown()
        media.nullInventory = true
        assertTrue(refresh() is ListenableWorker.Result.Failure)
        assertNotNull(dao.getActiveSongByLocator(1))
        assertEquals(MARKER, prefs.getLastSyncTimestamp())
    }

    @Test fun second_granularity_and_late_indexing_are_recovered() = runBlocking {
        seedKnown()
        // A date exactly at the cutoff fails strict > but an unknown locator still must be read.
        media.add(2, "At cutoff", incrementalFetchTimestampSeconds(MARKER))
        assertTrue(refresh() is ListenableWorker.Result.Success)
        media.add(3, "Indexed much later", 1)
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertEquals(3, dao.getAllSongs().first().size)
    }

    @Test fun unknown_artist_does_not_roll_back_other_new_songs() = runBlocking {
        checkArtistBatch(listOf("<unknown>" to listOf("Unknown Artist")))
    }

    @Test fun missing_sentinel_mixed_and_whitespace_artists_have_coherent_local_references() = runBlocking {
        checkArtistBatch(listOf(
            "<UnKnOwN>" to listOf("Unknown Artist"),
            "  <UNKNOWN>  " to listOf("Unknown Artist"),
            null to listOf("Unknown Artist"),
            "" to listOf("Unknown Artist"),
            "  \t  " to listOf("Unknown Artist"),
            "<unknown>; Guest" to listOf("Guest"),
            "  Guest   Name ; Guest Name ; <UNKNOWN> ; Other   Guest  " to listOf("Guest Name", "Other Guest")
        ))
    }

    private suspend fun checkArtistBatch(cases: List<Pair<String?, List<String>>>) {
        seedKnown()
        val knownIdentity = dao.getActiveSongByLocator(1)!!.libraryItemId
        media.add(2, "New valid", 10)
        cases.forEachIndexed { index, (raw, _) ->
            val id = index + 3L
            media.add(id, "New untagged $id", 10)
            // IDs from MediaStore are locators for its artist table, not our local artist IDs.
            media.sql.execSQL("UPDATE audio SET artist=?, artist_id=900001, album_artist=NULL WHERE _id=?",
                arrayOf<Any?>(raw, id))
        }
        assertTrue("the complete batch must be inserted", refresh() is ListenableWorker.Result.Success)
        assertEquals(cases.size + 2, dao.getSongCount().first())
        assertEquals(knownIdentity, dao.getActiveSongByLocator(1)!!.libraryItemId)
        cases.forEachIndexed { index, (_, names) -> assertArtistReferences(index + 3L, names) }
        assertForeignKeys()
        val identities = dao.getAllSongs().first().associate { it.id to it.libraryItemId }
        val fingerprints = dao.getAllSongs().first().associate { it.id to it.stableKey }
        val artists = artistSnapshot()
        // Make metadata selection run again and exercise grouping/upsert, rather than just a no-op.
        prefs.setLastSyncTimestamp(1)
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertEquals(identities, dao.getAllSongs().first().associate { it.id to it.libraryItemId })
        assertEquals(fingerprints, dao.getAllSongs().first().associate { it.id to it.stableKey })
        assertEquals(artists, artistSnapshot())
        cases.forEachIndexed { index, (_, names) -> assertArtistReferences(index + 3L, names) }
        assertForeignKeys()
    }

    private suspend fun assertArtistReferences(locator: Long, names: List<String>) {
        val song = dao.getActiveSongByLocator(locator)!!
        val json = JSONArray(song.artistsJson)
        assertEquals(names.size, json.length())
        val refs = (0 until json.length()).map { json.getJSONObject(it) }
        assertEquals(names, refs.map { it.getString("name") })
        val sql = db.openHelper.readableDatabase
        refs.forEachIndexed { index, ref ->
            val artistId = ref.getLong("id")
            assertEquals(index == 0, ref.getBoolean("primary"))
            sql.query("SELECT name FROM artists WHERE id=?", arrayOf<Any>(artistId)).use {
                assertTrue(it.moveToFirst()); assertEquals(names[index], it.getString(0))
            }
            sql.query("SELECT is_primary FROM song_artist_cross_ref WHERE song_id=? AND artist_id=?",
                arrayOf<Any>(locator, artistId)).use {
                assertTrue(it.moveToFirst()); assertEquals(if (index == 0) 1 else 0, it.getInt(0))
            }
        }
        assertEquals(refs.first().getLong("id"), song.artistId)
        sql.query("SELECT COUNT(*) FROM song_artist_cross_ref WHERE song_id=?", arrayOf<Any>(locator)).use {
            assertTrue(it.moveToFirst()); assertEquals(names.size, it.getInt(0))
        }
        sql.query("SELECT artist_id FROM albums WHERE id=?", arrayOf<Any>(song.albumId)).use {
            assertTrue("album must exist", it.moveToFirst())
            assertTrue("album artist must exist", artistSnapshot().containsKey(it.getLong(0)))
        }
        assertTrue(artistSnapshot().containsKey(song.albumArtistId))
    }

    private fun artistSnapshot(): Map<Long, String> = db.openHelper.readableDatabase.query(
        "SELECT id, name FROM artists ORDER BY id"
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1)) } }

    private fun assertForeignKeys() {
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
            assertFalse("all song, album and artist references must exist", it.moveToFirst())
        }
    }

    @Test fun migrated_unknown_size_keeps_identity_lyrics_and_identity_owned_favorite() = runBlocking {
        checkMigratedMissingEvidence(fileSize = 0, duration = 180_000)
    }

    @Test fun migrated_unknown_duration_does_not_invent_historical_signature() = runBlocking {
        checkMigratedMissingEvidence(fileSize = 8_000_000, duration = 0)
    }

    private suspend fun checkMigratedMissingEvidence(fileSize: Long, duration: Long) {
        seedKnown()
        val identity = dao.getActiveSongByLocator(1)!!.libraryItemId
        db.favoritesDao().setFavoriteForLibraryItem(identity, true, 123, 1)
        db.lyricsDao().insert(LyricsEntity(songId = 1, libraryItemId = identity, content = "saved lyrics"))
        // Reachable migration state: zero means unknown, not a measurement of the old file.
        db.openHelper.writableDatabase.execSQL("UPDATE songs SET stable_key='', file_size=?, duration=? WHERE id=1",
            arrayOf<Any>(fileSize, duration))
        db.openHelper.writableDatabase.execSQL("UPDATE library_items SET stable_key='', file_signature=''")
        assertTrue(refresh() is ListenableWorker.Result.Success)
        val updated = dao.getActiveSongByLocator(1)!!
        assertEquals(identity, updated.libraryItemId)
        assertEquals(true, db.favoritesDao().isFavoriteForLibraryItem(updated.libraryItemId))
        assertEquals("saved lyrics", db.lyricsDao().getLyricsForLibraryItem(updated.libraryItemId)?.content)
        assertEquals(8_000_000L, updated.fileSize)
        val item = dao.getAllLibraryItems().single()
        assertEquals(updated.stableKey, item.stableKey)
        assertEquals(com.lostf1sh.pixelplayeross.data.identity.LibraryIdentity.computeFileSignature(180_000, 8_000_000), item.fileSignature)
        media.metadataIds.clear()
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertTrue(media.metadataIds.isEmpty())
        assertEquals(identity, dao.getActiveSongByLocator(1)!!.libraryItemId)
        assertEquals(listOf(identity), db.favoritesDao().getFavoriteLibraryItemIds())
        assertEquals("saved lyrics", db.lyricsDao().getLyricsForLibraryItem(identity)?.content)
        assertEquals(1, dao.getAllLibraryItems().size)
        assertForeignKeys()
    }

    @Test fun reused_locator_with_conflicting_stored_evidence_does_not_inherit_identity_state() = runBlocking {
        seedKnown()
        val identity = dao.getActiveSongByLocator(1)!!.libraryItemId
        db.favoritesDao().setFavoriteForLibraryItem(identity, true, 123, 1)
        db.lyricsDao().insert(LyricsEntity(songId = 1, libraryItemId = identity, content = "old file lyrics"))
        // Blank legacy keys but known stored size/duration: the fallback must still defend reuse.
        db.openHelper.writableDatabase.execSQL("UPDATE songs SET stable_key='' WHERE id=1")
        db.openHelper.writableDatabase.execSQL("UPDATE library_items SET stable_key='', file_signature=''")
        media.sql.execSQL("UPDATE audio SET title='Different recording', duration=240000, _size=12000000 WHERE _id=1")
        assertTrue(refresh() is ListenableWorker.Result.Success)
        val replacement = dao.getActiveSongByLocator(1)!!
        assertNotEquals(identity, replacement.libraryItemId)
        assertEquals("Different recording", replacement.title)
        assertNotEquals(true, db.favoritesDao().isFavoriteForLibraryItem(replacement.libraryItemId))
        assertNull(db.lyricsDao().getLyricsForLibraryItem(replacement.libraryItemId))
        assertEquals(true, db.favoritesDao().isFavoriteForLibraryItem(identity))
        assertEquals("old file lyrics", db.lyricsDao().getLyricsForLibraryItem(identity)?.content)
        assertTrue(refresh() is ListenableWorker.Result.Success)
        assertEquals(replacement.libraryItemId, dao.getActiveSongByLocator(1)!!.libraryItemId)
        assertEquals(listOf(identity), db.favoritesDao().getFavoriteLibraryItemIds())
        assertEquals(2, dao.getAllLibraryItems().size)
        assertForeignKeys()
    }

    class AudioProvider : ContentProvider() {
        val sql = SQLiteDatabase.create(null).apply {
            execSQL("""CREATE TABLE audio (_id INTEGER PRIMARY KEY, title TEXT, artist TEXT, artist_id INTEGER,
                album TEXT, album_id INTEGER, album_artist TEXT, duration INTEGER, _data TEXT, mime_type TEXT,
                track INTEGER, year INTEGER, date_added INTEGER, date_modified INTEGER, _size INTEGER, genre TEXT)""")
        }
        val metadataIds = mutableListOf<Long>()
        var failure: Exception? = null
        var nullInventory = false
        fun add(id: Long, title: String, date: Long, path: String = "/music/$id.flac", duration: Long = 180_000) {
            sql.execSQL("INSERT INTO audio VALUES (?, ?, 'Artist', 1, 'Album', 1, 'Artist', ?, ?, 'audio/flac', 1, 2024, ?, ?, 8000000, 'Rock')",
                arrayOf<Any>(id, title, duration, path, date, date))
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (!uri.path.orEmpty().endsWith("/audio/media")) return android.database.MatrixCursor(arrayOf("_id", "name"))
            val metadata = projection?.contains("date_modified") == true
            if (metadata) failure?.let { throw it }
            if (!metadata && nullInventory) return null
            val cursor = sql.query("audio", projection, selection, selectionArgs, null, null, sortOrder)
            if (metadata) {
                while (cursor.moveToNext()) metadataIds += cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                cursor.moveToPosition(-1)
            }
            return cursor
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/flac"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("read only")
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }
    companion object { const val MARKER = 1_792_182_761_485L }
}
