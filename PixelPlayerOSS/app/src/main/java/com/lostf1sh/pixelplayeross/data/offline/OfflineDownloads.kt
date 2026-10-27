package com.lostf1sh.pixelplayeross.data.offline

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.model.Song
import java.util.Locale
import kotlinx.coroutines.flow.first
import com.lostf1sh.pixelplayeross.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Explicit transport and billing constraints: an unmetered mobile plan is still not Wi-Fi. */
internal fun offlineNetworkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkRequest(NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        .build(), NetworkType.UNMETERED)
    .build()

enum class OfflineKind { LYRICS, ARTIST_PHOTOS, COVERS, DESCRIPTIONS }
enum class OfflineStatus { QUEUED, RUNNING, PAUSED, COMPLETE }
/** One remote asset, rather than one song that happens to mention the same artist. */
data class OfflineTarget(
    val kind: OfflineKind,
    val title: String,
    val songId: String? = null,
    val albumArtist: String? = null,
    val album: Boolean = false,
)

internal object OfflinePlan {
    fun build(songs: List<Song>, kinds: Set<OfflineKind>): List<OfflineTarget> {
        fun key(value: String) = value.trim().lowercase(Locale.ROOT)
        val queues = OfflineKind.entries.filter { it in kinds }.associateWith { linkedMapOf<String, OfflineTarget>() }
        for (song in songs.distinctBy { it.id }) {
            queues[OfflineKind.LYRICS]?.putIfAbsent(song.id, OfflineTarget(OfflineKind.LYRICS, song.title, song.id))
            val artists = song.artists.map { it.name }.ifEmpty { listOf(song.artist) }.filter { it.isNotBlank() }
            for (artist in artists) {
                queues[OfflineKind.ARTIST_PHOTOS]?.putIfAbsent(key(artist), OfflineTarget(OfflineKind.ARTIST_PHOTOS, artist))
                queues[OfflineKind.DESCRIPTIONS]?.putIfAbsent("artist:${key(artist)}", OfflineTarget(OfflineKind.DESCRIPTIONS, artist))
            }
            val albumArtist = song.albumArtist?.takeIf { it.isNotBlank() } ?: song.artist
            if (song.album.isNotBlank()) {
                val albumKey = "${key(albumArtist)}\u0000${key(song.album)}"
                // A cover already present on any song of an album also serves the other songs.
                val cover = OfflineTarget(OfflineKind.COVERS, song.album, song.id, albumArtist, album = true)
                val covers = queues[OfflineKind.COVERS]
                if (covers != null && (albumKey !in covers || !song.albumArtUriString.isNullOrBlank())) covers[albumKey] = cover
                queues[OfflineKind.DESCRIPTIONS]?.putIfAbsent("album:$albumKey", OfflineTarget(OfflineKind.DESCRIPTIONS, song.album, albumArtist = albumArtist, album = true))
            }
        }
        // Interleave kinds so every selected queue can start in the first bounded worker slice.
        val iterators = queues.values.map { it.values.iterator() }
        return buildList { while (iterators.any { it.hasNext() }) iterators.forEach { if (it.hasNext()) add(it.next()) } }
    }
}

data class OfflineResult(val title: String, val available: Int, val unavailable: Int, val failed: Int)
data class OfflineBatch(
    val id: String,
    val songIds: List<String>,
    val kinds: Set<OfflineKind>,
    val next: Int = 0,
    val available: Int = 0,
    val unavailable: Int = 0,
    val failed: Int = 0,
    val status: OfflineStatus = OfflineStatus.QUEUED,
    val lastTitle: String = "",
    val targets: List<OfflineTarget> = emptyList(),
    val completed: Set<Int> = emptySet(),
    val planned: Boolean = true,
    val planId: String = id,
) {
    val total: Int get() = targets.size
    val active: Boolean get() = status == OfflineStatus.QUEUED || status == OfflineStatus.RUNNING
}

/** Persistent cursor and append-only results: no request queue in WorkManager's size-limited input Data. */
internal class OfflineBatchStore(private val dir: File) {
    private var loaded = false
    private var current: OfflineBatch? = null
    private val events = MutableStateFlow<OfflineBatch?>(null)
    val state = events.asStateFlow()
    @Synchronized fun read(): OfflineBatch? {
        if (!loaded) {
            loaded = true
            current = runCatching {
                val job = JSONObject(File(dir, "job.json").readText())
                val p = runCatching { JSONObject(File(dir, "progress.json").readText()) }.getOrNull()?.takeIf { it.optString("id") == job.getString("id") }
                val ids = job.getJSONArray("songs")
                val kinds = job.getJSONArray("kinds")
                val targets = job.optJSONArray("targets")
                val plan = (0 until (targets?.length() ?: 0)).map { i ->
                    val t = targets!!.getJSONObject(i)
                    OfflineTarget(OfflineKind.valueOf(t.getString("kind")), t.getString("title"),
                        t.optString("song").takeIf { it.isNotBlank() }, t.optString("artist").takeIf { it.isNotBlank() }, t.optBoolean("album"))
                }
                val base = OfflineBatch(job.getString("id"), (0 until ids.length()).map { ids.getString(it) },
                    (0 until kinds.length()).mapNotNull { i -> OfflineKind.entries.find { it.name == kinds.optString(i) } }.toSet(),
                    status = p?.optString("status")?.let { name -> OfflineStatus.entries.find { it.name == name } } ?: OfflineStatus.QUEUED,
                    targets = plan, planned = targets != null, planId = job.optString("plan", job.getString("id")))
                // The journal is authoritative: a process can die after appending a result but before updating progress.
                if (targets == null) base else journal(base.planId).filter { it.first in plan.indices }.fold(base) { b, (index, result) ->
                    b.copy(next = b.next + 1, completed = b.completed + index, available = b.available + result.available,
                        unavailable = b.unavailable + result.unavailable, failed = b.failed + result.failed, lastTitle = result.title)
                }
            }.getOrNull()
            events.value = current
        }
        return current
    }
    @Synchronized fun start(batch: OfflineBatch, clearResults: Boolean) {
        dir.mkdirs()
        atomic("job.json", JSONObject().put("id", batch.id).put("plan", batch.planId).put("songs", JSONArray(batch.songIds)).put("kinds", JSONArray(batch.kinds.map { it.name }))
            .put("targets", JSONArray(batch.targets.map { t -> JSONObject().put("kind", t.kind.name).put("title", t.title)
                .put("song", t.songId).put("artist", t.albumArtist).put("album", t.album) })))
        if (clearResults) File(dir, "results.jsonl").writeText("")
        loaded = true
        current = batch
        persist(batch)
        events.value = current
    }
    @Synchronized fun update(id: String, change: (OfflineBatch) -> OfflineBatch): OfflineBatch? {
        val old = read()?.takeIf { it.id == id } ?: return null
        return change(old).also { persist(it); current = it; events.value = it }
    }
    @Synchronized fun result(id: String, index: Int, result: OfflineResult): OfflineBatch? {
        val old = read()?.takeIf { it.id == id && it.active && index in it.targets.indices && index !in it.completed } ?: return null
        File(dir, "results.jsonl").appendText(JSONObject().put("plan", old.planId).put("index", index).put("title", result.title).put("available", result.available)
            .put("unavailable", result.unavailable).put("failed", result.failed).toString() + "\n")
        return old.copy(next = old.next + 1, completed = old.completed + index, available = old.available + result.available,
            unavailable = old.unavailable + result.unavailable, failed = old.failed + result.failed, lastTitle = result.title).also {
            persist(it); current = it; events.value = it
        }
    }
    @Synchronized fun results(): List<OfflineResult> {
        val batch = read() ?: return emptyList()
        val completed = batch.completed
        return journal(batch.planId).filter { it.first in completed }.map { it.second }
    }
    private fun journal(planId: String): List<Pair<Int, OfflineResult>> = File(dir, "results.jsonl").takeIf { it.isFile }?.useLines { lines ->
        lines.mapNotNull { line -> runCatching {
            val j = JSONObject(line)
            require(j.optString("plan") == planId)
            j.getInt("index") to OfflineResult(j.getString("title"), j.optInt("available"), j.optInt("unavailable"), j.optInt("failed"))
        }.getOrNull() }.distinctBy { it.first }.toList()
    }.orEmpty()
    private fun persist(batch: OfflineBatch) = atomic("progress.json", JSONObject().put("id", batch.id).put("next", batch.next)
        .put("available", batch.available).put("unavailable", batch.unavailable).put("failed", batch.failed)
        .put("status", batch.status.name).put("last", batch.lastTitle))
    private fun atomic(name: String, json: JSONObject) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.toString())
        check(tmp.renameTo(File(dir, name))) { "Could not persist offline download" }
    }
}

@Singleton
class OfflineDownloads @Inject constructor(
    @ApplicationContext context: Context,
    private val music: MusicRepository,
    @AppScope scope: CoroutineScope,
) {
    private val app = context.applicationContext
    private val store = OfflineBatchStore(File(app.filesDir, "offline-downloads"))
    private val work by lazy { WorkManager.getInstance(app) }
    private val startLock = Mutex()
    val state = store.state
    init { scope.launch(Dispatchers.IO) { store.read() } }

    suspend fun start(songIds: List<String>?, kinds: Set<OfflineKind>) = withContext(Dispatchers.IO) {
        startLock.withLock {
            if (store.read()?.active == true) return@withLock
            require(kinds.isNotEmpty())
            val songs = resolveSongs(songIds)
            require(songs.isNotEmpty())
            val batch = OfflineBatch(UUID.randomUUID().toString(), songs.map { it.id }, kinds, targets = OfflinePlan.build(songs, kinds))
            store.start(batch, clearResults = true)
            enqueue(batch.id, ExistingWorkPolicy.REPLACE)
        }
    }
    suspend fun resume() = withContext(Dispatchers.IO) {
        startLock.withLock {
            val old = store.read()?.takeIf { !it.active && it.next < it.total } ?: return@withLock
            // A resumed task has a new identity: a cancelled worker cannot update it later.
            val batch = old.copy(id = UUID.randomUUID().toString(), status = OfflineStatus.QUEUED)
            store.start(batch, clearResults = false)
            enqueue(batch.id, ExistingWorkPolicy.REPLACE)
        }
    }
    suspend fun cancel() = withContext(Dispatchers.IO) {
        startLock.withLock {
            store.read()?.let { update(it.id) { b -> b.copy(status = OfflineStatus.PAUSED) } }
            work.cancelUniqueWork(WORK_NAME)
        }
        Unit
    }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        // Migrate the owner's early preview queue without removing any downloaded assets.
        startLock.withLock {
            val old = store.read()
            if (old != null && !old.planned) {
                val songs = resolveSongs(old.songIds)
                val batch = old.copy(id = UUID.randomUUID().toString(), targets = OfflinePlan.build(songs, old.kinds),
                    status = if (old.active) OfflineStatus.QUEUED else OfflineStatus.PAUSED, planned = true)
                store.start(batch, clearResults = true)
                if (batch.active) enqueue(batch.id, ExistingWorkPolicy.REPLACE)
            }
        }
        val current = store.read() ?: return@withContext
        val unfinished = work.getWorkInfosForUniqueWork(WORK_NAME).get(10, TimeUnit.SECONDS).any { !it.state.isFinished }
        if (current.active && !unfinished) update(current.id) { if (it.active && it.next < it.total) it.copy(status = OfflineStatus.PAUSED) else it }
    }
    suspend fun results(): List<OfflineResult> = withContext(Dispatchers.IO) { store.results() }
    internal fun load(id: String): OfflineBatch? = store.read()?.takeIf { it.id == id && it.active }
    internal fun update(id: String, change: (OfflineBatch) -> OfflineBatch) { store.update(id, change) }
    internal fun record(id: String, index: Int, result: OfflineResult): Boolean = store.result(id, index, result) != null
    internal suspend fun continueLater(id: String) = withContext(Dispatchers.IO) {
        startLock.withLock {
            val batch = load(id) ?: return@withLock
            update(id) { it.copy(status = OfflineStatus.QUEUED) }
            enqueue(batch.id, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }
    private suspend fun resolveSongs(ids: List<String>?): List<Song> = if (ids == null) music.getAllSongsOnce()
        else ids.distinct().chunked(400).flatMap { music.getSongsByIds(it).first() }

    private fun enqueue(id: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(workDataOf("batch" to id))
            .setConstraints(offlineNetworkConstraints()).build()
        work.enqueueUniqueWork(WORK_NAME, policy, request)
    }
    companion object { const val WORK_NAME = "offline-music-downloads" }
}
