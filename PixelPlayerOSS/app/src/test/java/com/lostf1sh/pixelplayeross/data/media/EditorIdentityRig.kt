package com.lostf1sh.pixelplayeross.data.media

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
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
import com.lostf1sh.pixelplayeross.data.database.FavoritesEntity
import com.lostf1sh.pixelplayeross.data.database.LyricsEntity
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.model.MusicBrainzIds
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.worker.SyncMode
import com.lostf1sh.pixelplayeross.data.worker.SyncWorker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import timber.log.Timber

/**
 * B57 1.1. The real [SongMetadataEditor], the real [SyncWorker], the real Room database and real audio files on disk; only Android's MediaStore is a fake
 * ([FakeMediaStore]) and the edit sheet's caller is the test. TagLib's native library cannot load on a JVM, so the editor is built with `tagLibEnabled = false`
 * and writes through JAudioTagger (WAV, Ogg Vorbis, FLAC, MP3, M4A) or vorbis-java (Opus): the same fallback the phone takes when TagLib declines a file.
 *
 * "Rescan" is [rescan]: an incremental sync of whatever MediaStore reports now, the path that decides whether an item keeps its identity.
 */
class EditorIdentityRig {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: PixelPlayerDatabase
    val dao get() = db.musicDao()
    val store = FakeMediaStore()
    val prefs: UserPreferencesRepository
    val musicDir: File = Files.createTempDirectory("b57-music").toFile()

    /** What the fake MediaScanner does when the editor asks for a rescan; tests replace it to model a slow or silent scanner. */
    var rescanBehaviour: suspend (String) -> Unit = { path -> store.reindex(path) }

    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= android.util.Log.WARN) System.err.println("TIMBER $tag: $message ${t ?: ""}")
        }
    }

    init {
        Timber.plant(logTree)
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE)
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).allowMainThreadQueries().build()
        ShadowContentResolver.registerProviderInternal("media", store)
        prefs = UserPreferencesRepository(MemoryPreferences(), Json { ignoreUnknownKeys = true })
        // TagLib's native library cannot load on a JVM; the scan's tag reads (MusicBrainz ids, composer, augmenting metadata) are what it would answer for tags it cannot read.
        mockkObject(AudioMetadataReader)
        every { AudioMetadataReader.readMusicBrainzIds(any()) } returns MusicBrainzIds()
        every { AudioMetadataReader.readComposer(any()) } returns ComposerRead.None
        every { AudioMetadataReader.read(any<File>(), any(), any()) } returns null
        runBlocking {
            prefs.setLastSyncTimestamp(MARKER)
            prefs.includeDirectories(listOf(musicDir.absolutePath))
        }
    }

    fun editor(): SongMetadataEditor = SongMetadataEditor(
        context = context,
        musicDao = dao,
        userPreferencesRepository = prefs,
        tagLibEnabled = false,
        rescanner = MediaRescanner { path -> rescanBehaviour(path) },
    )

    /** Copies a fixture from `src/test/resources/editor/` into the music folder and registers it in the fake MediaStore as [id]. */
    fun addFixture(id: Long, fixture: String, title: String, artist: String = "Gemini Sound", album: String = "Noche", track: Int = 1, disc: Int = 0, fileName: String = fixture): File {
        val bytes = javaClass.classLoader!!.getResourceAsStream("editor/$fixture")!!.use { it.readBytes() }
        val file = File(musicDir, "$id-$fileName").apply { writeBytes(bytes) }
        store.add(id, file, title, artist, album, track + disc * 1000)
        return file
    }

    private fun worker() = TestListenableWorkerBuilder<SyncWorker>(context)
        .setInputData(workDataOf(SyncWorker.INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name, SyncWorker.INPUT_RUN_MAINTENANCE to false))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                SyncWorker(appContext, workerParameters, dao, db.libraryItemOverrideDao(), mockk(relaxed = true), prefs, mockk(relaxed = true))
        }).build()

    /** One incremental scan of the fake MediaStore. Android has indexed the files by the time a scan runs, so their sizes are read from disk first. */
    suspend fun rescan(): ListenableWorker.Result {
        store.reindexAll()
        return worker().doWork().also { prefs.setLastSyncTimestamp(MARKER) }
    }

    /** The first scan of a library: rows are older than the marker only if [oldRows]; the default makes the scan read them all. */
    suspend fun firstScan() {
        store.sql.execSQL("UPDATE audio SET date_modified = ?, date_added = ?", arrayOf<Any>(MARKER / 1000 + 1, MARKER / 1000 + 1))
        check(rescan() is ListenableWorker.Result.Success)
        // From here the rows are "known and unchanged": align timestamps the way the integration test does, so only an edit makes a row look new.
        store.sql.execSQL("UPDATE audio SET date_modified = 20, date_added = 20")
        db.openHelper.writableDatabase.execSQL("UPDATE songs SET media_store_date_modified = 20, media_store_date_added = 20")
    }

    /** Favourite, lyrics, play history, a playlist entry and a bookmark on the item behind [locator]: everything a dormant item would carry away. */
    suspend fun giveUserState(locator: Long): String {
        val item = dao.getSongByLocatorOnce(locator)!!.libraryItemId
        db.favoritesDao().insertAll(listOf(FavoritesEntity(songId = locator, libraryItemId = item)))
        db.lyricsDao().insert(LyricsEntity(songId = locator, libraryItemId = item, content = "user lyrics"))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO song_engagements (song_id, stable_key, library_item_id, play_count, total_play_duration_ms, completion_count, skip_count, first_played_at, last_completed_at, last_skipped_at, last_played_timestamp) VALUES (?, '', ?, 7, 1000, 7, 0, 1, 1, 0, 1)", arrayOf<Any>(locator.toString(), item))
        sql.execSQL("INSERT OR IGNORE INTO playlists (id, name, created_at, last_modified, is_queue_generated, source) VALUES ('p1', 'Mine', 1, 1, 0, 'USER')")
        sql.execSQL("INSERT INTO playlist_songs (playlist_id, song_id, sort_order, library_item_id) VALUES ('p1', ?, 0, ?)", arrayOf<Any>(locator.toString(), item))
        sql.execSQL("INSERT INTO audio_bookmarks (id, song_id, song_title, artist_name, title, timestamp_ms, created_time, library_item_id) VALUES (?, ?, 't', 'a', 'mark', 500, 1, ?)", arrayOf<Any>(locator, locator.toString(), item))
        return item
    }

    /** Everything [giveUserState] set, read back by the item's identity. */
    suspend fun userStateOf(item: String): UserState {
        val sql = db.openHelper.readableDatabase
        fun count(table: String) = sql.query("SELECT COUNT(*) FROM $table WHERE library_item_id = ?", arrayOf(item)).use { it.moveToFirst(); it.getInt(0) }
        return UserState(
            favourite = db.favoritesDao().getFavoriteLibraryItemIds().contains(item),
            lyrics = db.lyricsDao().getLyricsForLibraryItem(item)?.content,
            plays = sql.query("SELECT play_count FROM song_engagements WHERE library_item_id = ?", arrayOf(item)).use { if (it.moveToFirst()) it.getInt(0) else 0 },
            playlistEntries = count("playlist_songs"),
            bookmarks = count("audio_bookmarks"),
        )
    }

    data class UserState(val favourite: Boolean, val lyrics: String?, val plays: Int, val playlistEntries: Int, val bookmarks: Int)

    val expectedUserState = UserState(favourite = true, lyrics = "user lyrics", plays = 7, playlistEntries = 1, bookmarks = 1)

    fun items() = runBlocking { dao.getAllLibraryItems() }

    fun close() {
        Timber.uproot(logTree)
        unmockkObject(AudioMetadataReader)
        db.close()
        store.sql.close()
        musicDir.deleteRecursively()
    }

    class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }

    companion object {
        const val MARKER = 1_792_182_761_485L
    }
}

/**
 * MediaStore as the editor and the scan use it: rows with the columns they read, `update` for the editor's own MediaStore write, and [reindex], which is what
 * Android's scanner does when asked to look at a file again: it reads the file's size from disk.
 */
class FakeMediaStore : ContentProvider() {
    val sql: SQLiteDatabase = SQLiteDatabase.create(null).apply {
        execSQL(
            """CREATE TABLE audio (_id INTEGER PRIMARY KEY, title TEXT, artist TEXT, artist_id INTEGER,
            album TEXT, album_id INTEGER, album_artist TEXT, duration INTEGER, _data TEXT, mime_type TEXT,
            track INTEGER, year INTEGER, date_added INTEGER, date_modified INTEGER, _size INTEGER, genre TEXT)"""
        )
    }

    fun add(id: Long, file: File, title: String, artist: String, album: String, rawTrack: Int, durationMs: Long = 180_000) {
        sql.execSQL(
            "INSERT INTO audio VALUES (?, ?, ?, 1, ?, 1, ?, ?, ?, 'audio/flac', ?, 2024, 10, 10, ?, 'Rock')",
            arrayOf<Any>(id, title, artist, album, artist, durationMs, file.absolutePath, rawTrack, file.length())
        )
    }

    /** The scanner reading the file again: its size (and modification time) as they are on disk now. */
    fun reindex(path: String) {
        val size = File(path).length()
        sql.execSQL("UPDATE audio SET _size = ?, date_modified = date_modified WHERE _data = ?", arrayOf<Any>(size, path))
    }

    fun reindexAll() {
        sql.rawQuery("SELECT _data FROM audio", null).use { c -> while (c.moveToNext()) reindex(c.getString(0)) }
    }

    fun row(id: Long): Map<String, Any?> = sql.rawQuery("SELECT * FROM audio WHERE _id = ?", arrayOf(id.toString())).use { c ->
        check(c.moveToFirst())
        c.columnNames.associateWith { name ->
            val i = c.getColumnIndexOrThrow(name)
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_NULL -> null
                else -> c.getString(i)
            }
        }
    }

    private fun idOf(uri: Uri): Long? = uri.lastPathSegment?.toLongOrNull()

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val id = idOf(uri)
        if (id == null && !uri.path.orEmpty().endsWith("/audio/media")) return android.database.MatrixCursor(arrayOf("_id", "name"))
        val where = if (id != null) "_id = ?" else selection
        val args = if (id != null) arrayOf(id.toString()) else selectionArgs
        return sql.query("audio", projection, where, args, null, null, sortOrder)
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "audio/flac"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("not used")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("not used")
    /** Set to make the next MediaStore `update` fail the way Android 10 does for a file the app does not own: with a recoverable security exception. */
    var denyNextUpdate: Exception? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        denyNextUpdate?.let { denyNextUpdate = null; throw it }
        val id = idOf(uri) ?: return 0
        return sql.update("audio", values, "_id = ?", arrayOf(id.toString()))
    }
}
