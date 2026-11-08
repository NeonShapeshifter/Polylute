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
import androidx.paging.PagingSource
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.Data
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import timber.log.Timber

/**
 * B58 2 (#68): the real [SyncWorker] over a synthetic library, a fake MediaStore and a real Room database, with the app's list queries observed the way the library screens observe them.
 * It measures the database work of a sync on the JVM (no tag reads: the files do not exist) and counts how many times the observed tables are invalidated and how many times each
 * list query is run again. Not a phone: the HONOR is several times slower per statement, and the numbers here are for comparing before and after on one host.
 */
internal class B58SyncRig(private val songs: Int, albumSize: Int = 12, albumsPerArtist: Int = 3, private val root: String = "/music") {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: PixelPlayerDatabase
    val provider = SyntheticMediaProvider()
    val prefs: UserPreferencesRepository
    val dao get() = db.musicDao()

    /** The log lines the worker wrote, with their tag. */
    val lines = CopyOnWriteArrayList<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (tag == SyncWorker.LOG_TAG) lines += message
        }
    }

    /** The folder-artwork step the worker runs at the end of a sync; null (as in most tests) leaves it out. */
    var folderArtwork: com.lostf1sh.pixelplayeross.data.repository.FolderArtworkRefresh? = null

    /** What the worker reported through `setProgress`, in order: (current, total, phase ordinal). */
    val progress = CopyOnWriteArrayList<Triple<Int, Int, Int>>()

    init {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).allowMainThreadQueries().build()
        ShadowContentResolver.registerProviderInternal("media", provider)
        prefs = UserPreferencesRepository(MemoryPreferences(), Json { ignoreUnknownKeys = true })
        Timber.plant(tree)
        provider.fill(songs, albumSize, albumsPerArtist, root = root)
    }

    fun close() {
        Timber.uproot(tree)
        db.close()
        provider.sql.close()
    }

    suspend fun includeMusic() {
        prefs.includeDirectories(listOf(root))
    }

    /** Runs the worker once. [mode] is the name of a [SyncMode]; a Full Rescan is `FULL` with `deep = true`, "Update library" is `INCREMENTAL`. */
    suspend fun sync(mode: String, deep: Boolean = false, listener: Boolean = true, maintenance: Boolean = mode != "INCREMENTAL"): ListenableWorker.Result {
        lines.clear()
        progress.clear()
        val data: Data = workDataOf(
            SyncWorker.INPUT_SYNC_MODE to mode,
            SyncWorker.INPUT_FORCE_METADATA to deep,
            SyncWorker.INPUT_RUN_MAINTENANCE to maintenance,
            SyncWorker.INPUT_REQUESTED_BY_LISTENER to listener,
        )
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(data)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                    val real = SyncWorker(appContext, workerParameters, dao, db.libraryItemOverrideDao(), mockk(relaxed = true), prefs, mockk(relaxed = true), folderArtwork)
                    // a spy, so what the worker hands to setProgress is what the Settings row would have shown
                    val spy = spyk(real)
                    coEvery { spy.setProgress(any()) } coAnswers {
                        val d = firstArg<Data>()
                        progress += Triple(d.getInt(SyncWorker.PROGRESS_CURRENT, 0), d.getInt(SyncWorker.PROGRESS_TOTAL, 0), d.getInt(SyncWorker.PROGRESS_PHASE, 0))
                        callOriginal()
                    }
                    return spy
                }
            }).build()
        return worker.doWork()
    }

    /** The `sync phase=` lines of the last run as name -> milliseconds. */
    fun phaseMs(): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        lines.filter { it.startsWith("sync phase=") }.forEach { line ->
            val name = line.substringAfter("phase=").substringBefore(' ')
            out[name] = line.substringAfter("ms=").substringBefore(' ').toLong()
        }
        return out
    }

    class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }

    /** A MediaStore with [fill]ed rows shaped like a real library: albums of a dozen tracks, a few albums per artist, one folder per album. */
    class SyntheticMediaProvider : ContentProvider() {
        val sql: SQLiteDatabase = SQLiteDatabase.create(null).apply {
            execSQL(
                """CREATE TABLE audio (_id INTEGER PRIMARY KEY, title TEXT, artist TEXT, artist_id INTEGER,
                album TEXT, album_id INTEGER, album_artist TEXT, duration INTEGER, _data TEXT, mime_type TEXT,
                track INTEGER, year INTEGER, date_added INTEGER, date_modified INTEGER, _size INTEGER, genre TEXT)"""
            )
        }
        val metadataIds = CopyOnWriteArrayList<Long>()

        fun fill(songs: Int, albumSize: Int, albumsPerArtist: Int, dateSeconds: Long = 1_700_000_000L, root: String = "/music") {
            val genres = listOf("Rock", "Pop", "Jazz", "Classical", "Hip-Hop", "Electronic", "Folk", "Metal", "Latin", "Soul", "Blues", "Reggae")
            sql.beginTransaction()
            try {
                for (i in 0 until songs) {
                    val album = i / albumSize
                    val artist = album / albumsPerArtist
                    val id = 1000L + i
                    sql.execSQL(
                        "INSERT INTO audio VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'audio/flac', ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any>(
                            id, "Track ${i % albumSize + 1} of album $album", "Artist $artist", 500L + artist, "Album $album", 700L + album, "Artist $artist",
                            180_000L + (i % 90) * 1000L, "$root/Artist $artist/[${2000 + album % 24}] Album $album/${"%02d".format(i % albumSize + 1)}.flac",
                            i % albumSize + 1, 2000 + album % 24, dateSeconds, dateSeconds, 24_000_000L + i, genres[album % genres.size],
                        ),
                    )
                }
                sql.setTransactionSuccessful()
            } finally {
                sql.endTransaction()
            }
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (!uri.path.orEmpty().endsWith("/audio/media")) return android.database.MatrixCursor(arrayOf("_id", "name"))
            val metadata = projection?.contains("date_modified") == true
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
}

/**
 * The library screens' queries, observed: every flow is collected and counted, every paging source is loaded once and loaded again when it is invalidated. [invalidations] counts the
 * times Room told an observer of the library tables that they changed (what every list query is re-run for).
 */
internal class B58Observers(private val rig: B58SyncRig) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val emissions = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    val pagingLoads = AtomicInteger()
    val invalidations = AtomicInteger()
    private val jobs = ArrayList<Job>()
    private val tableObserver = object : InvalidationTracker.Observer(arrayOf("songs", "albums", "artists", "song_artist_cross_ref", "library_items")) {
        override fun onInvalidated(tables: Set<String>) {
            invalidations.incrementAndGet()
        }
    }

    private fun <T> watch(name: String, flow: Flow<T>) {
        emissions[name] = AtomicInteger()
        jobs += scope.launch { flow.collect { emissions.getValue(name).incrementAndGet() } }
    }

    private fun page(name: String, source: () -> PagingSource<Int, *>) {
        emissions[name] = AtomicInteger()
        jobs += scope.launch {
            while (true) {
                val src = source()
                val changed = kotlinx.coroutines.CompletableDeferred<Unit>()
                src.registerInvalidatedCallback { changed.complete(Unit) }
                src.load(PagingSource.LoadParams.Refresh(null, 60, false))
                pagingLoads.incrementAndGet()
                emissions.getValue(name).incrementAndGet()
                changed.await()
            }
        }
    }

    fun start() {
        val dao = rig.dao
        rig.db.invalidationTracker.addObserver(tableObserver)
        watch("songs", dao.getAllSongs())
        watch("songCount", dao.getSongCount())
        watch("albums", dao.getAlbums(emptyList(), false, 0, 1))
        watch("albumCount", dao.getAlbumCount())
        watch("artists", dao.getArtists(emptyList(), false))
        watch("artistCount", dao.getArtistCount())
        watch("artistsRaw", dao.getAllArtistsRaw())
        watch("genreCounts", dao.getGenreCounts(emptyList(), false))
        watch("genres", dao.getUniqueGenres())
        watch("folders", dao.getDistinctParentDirectoriesFlow())
        watch("albumDurations", dao.albumDurations())
        page("pagedSongs") { dao.getSongsPaginated(emptyList(), false, "title_asc", 0) }
        page("pagedAlbums") { dao.getAlbumsPaginated(emptyList(), false, 0, "album_title_asc", 1) }
        page("pagedArtists") { dao.getArtistsPaginated(emptyList(), false, 0, "artist_name_asc") }
    }

    /** Total times any observed list query ran (each flow emission and each paging load). */
    fun queriesRun(): Int = emissions.values.sumOf { it.get() }

    fun resetCounts() {
        invalidations.set(0)
        emissions.values.forEach { it.set(0) }
        pagingLoads.set(0)
    }

    suspend fun settle() {
        // Room refreshes on its own executor: wait until the counters stop moving
        var last = -1
        var stable = 0
        while (stable < 5) {
            kotlinx.coroutines.delay(60)
            val now = queriesRun() * 1000 + invalidations.get()
            if (now == last) stable++ else { stable = 0; last = now }
        }
    }

    fun stop() {
        rig.db.invalidationTracker.removeObserver(tableObserver)
        scope.cancel()
    }
}
