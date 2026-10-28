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

data class OfflineListeningRank(val plays: Int = 0, val favorite: Boolean = false, val lastHeardMs: Long = 0)

internal object OfflinePlan {
    fun build(songs: List<Song>, kinds: Set<OfflineKind>, ranks: Map<String, OfflineListeningRank> = emptyMap(), hasArtwork: (Song) -> Boolean = { song ->
        runCatching { File(java.net.URI(song.albumArtUriString.orEmpty())).let { it.isFile && it.length() > 0 } }.getOrDefault(false)
    }): List<OfflineTarget> {
        fun key(value: String) = value.trim().lowercase(Locale.ROOT)
        val queues = OfflineKind.entries.filter { it in kinds }.associateWith { linkedMapOf<String, OfflineTarget>() }
        val albumsWithArt = mutableSetOf<String>()
        for (song in songs.sortedWith(compareByDescending<Song> { ranks[it.id]?.plays ?: 0 }
            .thenByDescending { ranks[it.id]?.favorite == true || it.isFavorite }
            .thenByDescending { ranks[it.id]?.lastHeardMs ?: 0L }).distinctBy { it.id }) {
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
                if (covers != null) {
                    val realArt = albumKey !in albumsWithArt && hasArtwork(song)
                    if (albumKey !in covers || realArt) covers[albumKey] = cover
                    if (realArt) albumsWithArt += albumKey
                }
                queues[OfflineKind.DESCRIPTIONS]?.putIfAbsent("album:$albumKey", OfflineTarget(OfflineKind.DESCRIPTIONS, song.album, albumArtist = albumArtist, album = true))
            }
        }
        // Interleave kinds so every selected queue can start in the first bounded worker slice.
        val iterators = queues.values.map { it.values.iterator() }
        return buildList { while (iterators.any { it.hasNext() }) iterators.forEach { if (it.hasNext()) add(it.next()) } }
    }
}

data class OfflineRetry(val attempts: Int, val readyAtMs: Long)

data class OfflineResult(
    val title: String,
    val available: Int,
    val unavailable: Int,
    val failed: Int,
    val cacheKeys: Set<String> = emptySet(),
    val deletedByUser: Boolean = false,
)
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
    val retries: Map<Int, OfflineRetry> = emptyMap(),
    val measuredMs: Long = 0,
    val measuredCount: Int = 0,
) {
    val total: Int get() = targets.size
    val estimatedRemainingMs: Long? get() = if (measuredCount > 0) measuredMs * (total - next) / measuredCount else null
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
                    targets = plan, planned = targets != null, planId = job.optString("plan", job.getString("id")),
                    measuredMs = p?.optLong("measuredMs") ?: 0L, measuredCount = p?.optInt("measuredCount") ?: 0,
                    retries = p?.optJSONObject("retry")?.let { entries -> entries.keys().asSequence().mapNotNull { key ->
                        key.toIntOrNull()?.let { index -> index to OfflineRetry(entries.getJSONObject(key).optInt("attempts"), entries.getJSONObject(key).optLong("at")) }
                    }.toMap() }.orEmpty()).let { it.copy(failed = it.retries.size) }
                // The journal is authoritative: a process can die after appending a result but before updating progress.
                if (targets == null) base else journal(base.planId).filter { it.first in plan.indices }.fold(base) { b, (index, result) ->
                    b.copy(next = b.next + 1, completed = b.completed + index, available = b.available + result.available,
                        unavailable = b.unavailable + result.unavailable, lastTitle = result.title)
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
    @Synchronized fun result(id: String, index: Int, result: OfflineResult, allowInactive: Boolean = false): OfflineBatch? {
        val old = read()?.takeIf { it.id == id && (it.active || allowInactive) && index in it.targets.indices && index !in it.completed } ?: return null
        if (result.failed > 0) {
            val attempts = (old.retries[index]?.attempts ?: 0) + 1
            val delayMs = (30_000L shl (attempts - 1).coerceAtMost(6)).coerceAtMost(30 * 60_000L)
            val pending = old.retries + (index to OfflineRetry(attempts, System.currentTimeMillis() + delayMs))
            return old.copy(retries = pending, failed = pending.size, lastTitle = result.title).also {
                persist(it); current = it; events.value = it
            }
        }
        appendResult(old.planId, index, result)
        return old.copy(next = old.next + 1, completed = old.completed + index, available = old.available + result.available,
            unavailable = old.unavailable + result.unavailable, retries = old.retries - index, failed = (old.retries - index).size, lastTitle = result.title).also {
            persist(it); current = it; events.value = it
        }
    }
    private fun appendResult(planId: String, index: Int, result: OfflineResult) {
        val entry = JSONObject()
            .put("plan", planId)
            .put("index", index)
            .put("title", result.title)
            .put("available", result.available)
            .put("unavailable", result.unavailable)
            .put("failed", result.failed)
            .put("assets", JSONArray(result.cacheKeys))
            .put("deletedByUser", result.deletedByUser)
        File(dir, "results.jsonl").appendText(entry.toString() + "\n")
    }

    @Synchronized fun results(): List<OfflineResult> {
        val batch = read() ?: return emptyList()
        val completed = batch.completed
        return journal(batch.planId).filter { it.first in completed }.map { it.second }
    }
    @Synchronized fun completedResults(): Map<Int, OfflineResult> = read()?.let { batch ->
        journal(batch.planId).filter { it.first in batch.completed }.toMap()
    }.orEmpty()
    @Synchronized fun claim(key: String) {
        val batch = read() ?: return
        File(dir, "results.jsonl").appendText(JSONObject().put("plan", batch.planId).put("asset", key).toString() + "\n")
    }
    @Synchronized fun invalidateTargets(keys: Set<String>) {
        val batch = read() ?: return
        val invalidated = batch.targets.indices.filter { batch.targets[it].sharedKey() in keys }.toSet()
        if (invalidated.isEmpty()) return
        val retries = batch.retries - invalidated
        val keep = completedResults().filterKeys { it !in invalidated }
        val assets = File(dir, "results.jsonl").takeIf { it.isFile }?.readLines().orEmpty().filter { line ->
            runCatching { JSONObject(line).has("asset") }.getOrDefault(false)
        }
        File(dir, "results.jsonl").writeText(assets.joinToString("\n", postfix = if (assets.isEmpty()) "" else "\n"))
        val fresh = batch.copy(
            completed = emptySet(),
            next = 0,
            available = 0,
            unavailable = 0,
            retries = retries,
            failed = retries.size,
            status = if (batch.active) batch.status else OfflineStatus.PAUSED,
        )
        current = fresh
        keep.forEach { (index, result) -> this.result(fresh.id, index, result, allowInactive = true) }
        persist(read()!!)
        events.value = current
    }
    private fun journal(planId: String): List<Pair<Int, OfflineResult>> {
        val file = File(dir, "results.jsonl").takeIf { it.isFile } ?: return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching {
                    val entry = JSONObject(line)
                    require(entry.optString("plan") == planId)
                    entry.getInt("index") to readResult(entry)
                }.getOrNull()
            }.distinctBy { it.first }.toList()
        }
    }

    private fun readResult(entry: JSONObject): OfflineResult {
        val assets = entry.optJSONArray("assets")
        val cacheKeys = if (assets == null) emptySet() else {
            (0 until assets.length()).map { assets.getString(it) }.toSet()
        }
        return OfflineResult(
            title = entry.getString("title"),
            available = entry.optInt("available"),
            unavailable = entry.optInt("unavailable"),
            failed = entry.optInt("failed"),
            cacheKeys = cacheKeys,
            deletedByUser = entry.optBoolean("deletedByUser"),
        )
    }

    private fun persist(batch: OfflineBatch) = atomic("progress.json", JSONObject().put("id", batch.id).put("next", batch.next)
        .put("available", batch.available).put("unavailable", batch.unavailable).put("failed", batch.failed)
        .put("status", batch.status.name).put("last", batch.lastTitle).put("measuredMs", batch.measuredMs).put("measuredCount", batch.measuredCount)
        .put("retry", JSONObject().apply { batch.retries.forEach { (index, retry) ->
            put(index.toString(), JSONObject().put("attempts", retry.attempts).put("at", retry.readyAtMs))
        } }))
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
    private val ledger: com.lostf1sh.pixelplayeross.data.stats.LedgerProvider,
    private val info: com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository,
    private val lyrics: com.lostf1sh.pixelplayeross.data.repository.LyricsRepository,
) {
    private val app = context.applicationContext
    private val assetClaims = com.lostf1sh.pixelplayeross.data.network.DownloadAssetRegistry(File(app.filesDir, "download-claims.jsonl"))
    private val store = OfflineJobStore(File(app.filesDir, "offline-downloads"), assetClaims)
    private val work by lazy { WorkManager.getInstance(app) }
    private val startLock = Mutex()
    val state = store.state
    val jobs = store.jobs
    init { scope.launch(Dispatchers.IO) { store.read() } }

    suspend fun start(songIds: List<String>?, kinds: Set<OfflineKind>) = withContext(Dispatchers.IO) {
        startLock.withLock {
            require(kinds.isNotEmpty())
            val resolved = resolveSongs(songIds)
            val stats = ledger.ledger().itemStats
            val favorites = music.getFavoriteSongIdsOnce()
            val ranks = resolved.associate { song ->
                val heard = stats[song.libraryItemId.ifBlank { com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog.standInIdentity(song) }]
                song.id to OfflineListeningRank(heard?.plays ?: 0, song.id in favorites || song.isFavorite, heard?.lastHeardMs ?: 0L)
            }
            val songs = resolved
            require(songs.isNotEmpty())
            val batch = OfflineBatch(UUID.randomUUID().toString(), songs.map { it.id }, kinds,
                targets = OfflinePlan.build(songs, kinds, ranks) { OfflineArtwork.hasLocalCover(app, it) })
            if (songIds != null) {
                info.allowDownloads(batch.targets)
                if (OfflineKind.LYRICS in kinds) lyrics.allowDownloads(songs)
            }
            store.enqueue(batch)
            enqueue(batch.id, ExistingWorkPolicy.REPLACE)
        }
    }
    suspend fun resume() = withContext(Dispatchers.IO) {
        startLock.withLock {
            val old = store.read()?.takeIf { !it.active && it.next < it.total } ?: return@withLock
            // A resumed task has a new identity: a cancelled worker cannot update it later.
            val batch = old.copy(id = UUID.randomUUID().toString(), status = OfflineStatus.QUEUED)
            store.replaceCurrent(batch, clearResults = false)
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
                val batch = old.copy(id = UUID.randomUUID().toString(), targets = OfflinePlan.build(songs, old.kinds) { OfflineArtwork.hasLocalCover(app, it) },
                    status = if (old.active) OfflineStatus.QUEUED else OfflineStatus.PAUSED, planned = true)
                store.replaceCurrent(batch, clearResults = true)
                if (batch.active) enqueue(batch.id, ExistingWorkPolicy.REPLACE)
            }
        }
        val current = store.read() ?: return@withContext
        val unfinished = work.getWorkInfosForUniqueWork(WORK_NAME).get(10, TimeUnit.SECONDS).any { !it.state.isFinished }
        if (current.active && !unfinished) update(current.id) { if (it.active && it.next < it.total) it.copy(status = OfflineStatus.PAUSED) else it }
    }
    suspend fun results(): List<OfflineResult> = withContext(Dispatchers.IO) { store.results() }
    internal fun load(id: String): OfflineBatch? = store.read()?.takeIf { it.id == id && it.active }
    internal fun update(id: String, change: (OfflineBatch) -> OfflineBatch) {
        val updated = store.update(id, change)
        if (updated?.status == OfflineStatus.COMPLETE) store.read()?.takeIf { it.active }?.let { enqueue(it.id, ExistingWorkPolicy.APPEND_OR_REPLACE) }
    }
    internal fun record(id: String, index: Int, result: OfflineResult): Boolean = store.result(id, index, result) != null
    internal suspend fun continueLater(id: String) = withContext(Dispatchers.IO) {
        startLock.withLock {
            val batch = load(id) ?: return@withLock
            update(id) { it.copy(status = OfflineStatus.QUEUED) }
            val waitMs = batch.targets.indices.filter { it !in batch.completed }.minOfOrNull {
                (batch.retries[it]?.readyAtMs ?: 0L) - System.currentTimeMillis()
            }?.coerceAtLeast(0L) ?: 0L
            enqueue(batch.id, ExistingWorkPolicy.APPEND_OR_REPLACE, waitMs)
        }
    }
    internal fun claim(id: String, key: String) { store.claim(id, key) }

    suspend fun deleteArtistInfo(name: String, songs: List<Song>) = withContext(Dispatchers.IO) {
        startLock.withLock {
            val keys = com.lostf1sh.pixelplayeross.data.musicinfo.DownloadKeys.artist(name) +
                songs.map(com.lostf1sh.pixelplayeross.data.musicinfo.DownloadKeys::lyrics)
            info.deletedDownloads.mark(keys)
            info.deleteArtistInfo(name)
            songs.forEach { lyrics.deleteDownloadedLyrics(it) }
            val targets = listOf(OfflineTarget(OfflineKind.ARTIST_PHOTOS, name), OfflineTarget(OfflineKind.DESCRIPTIONS, name)) +
                songs.map { OfflineTarget(OfflineKind.LYRICS, it.title, it.id) }
            store.invalidate(targets.map { it.sharedKey() }.toSet())
            wakeActiveJobAfterDeletion()
        }
    }
    suspend fun deleteAlbumInfo(album: String, artist: String, songs: List<Song>) = withContext(Dispatchers.IO) {
        startLock.withLock {
            val keys = com.lostf1sh.pixelplayeross.data.musicinfo.DownloadKeys.album(album, artist) +
                songs.map(com.lostf1sh.pixelplayeross.data.musicinfo.DownloadKeys::lyrics)
            info.deletedDownloads.mark(keys)
            info.deleteAlbumInfo(album, artist, songs.map { it.id })
            songs.forEach { lyrics.deleteDownloadedLyrics(it) }
            val targets = listOf(OfflineTarget(OfflineKind.COVERS, album, albumArtist = artist, album = true),
                OfflineTarget(OfflineKind.DESCRIPTIONS, album, albumArtist = artist, album = true)) +
                songs.map { OfflineTarget(OfflineKind.LYRICS, it.title, it.id) }
            store.invalidate(targets.map { it.sharedKey() }.toSet())
            wakeActiveJobAfterDeletion()
        }
    }

    private fun wakeActiveJobAfterDeletion() {
        val current = store.read()?.takeIf { it.active } ?: return
        enqueue(current.id, ExistingWorkPolicy.REPLACE)
    }

    internal suspend fun wasDeleted(target: OfflineTarget, song: Song?): Boolean {
        val key = when (target.kind) {
            OfflineKind.LYRICS -> if (song != null) {
                com.lostf1sh.pixelplayeross.data.musicinfo.DownloadKeys.lyrics(song)
            } else {
                com.lostf1sh.pixelplayeross.data.musicinfo.DownloadKeys.lyrics(target.songId.orEmpty())
            }
            else -> target.sharedKey()
        }
        return info.deletedDownloads.contains(key)
    }

    suspend fun refreshArtistInfo(name: String, songs: List<Song>) = withContext(Dispatchers.IO) {
        lyrics.allowDownloads(songs)
        info.refreshArtistInfo(name)
    }

    suspend fun refreshAlbumInfo(album: String, artist: String, songs: List<Song>) = withContext(Dispatchers.IO) {
        val artists = songs.map { it.albumArtist?.takeIf(String::isNotBlank) ?: it.artist } + artist
        val keys = artists.flatMap { com.lostf1sh.pixelplayeross.data.musicinfo.DownloadKeys.album(album, it) }
        info.deletedDownloads.allow(keys)
        lyrics.allowDownloads(songs)
        info.service.refreshAlbum(album, artist)
    }

    suspend fun deleteJob(plan: String) = withContext(Dispatchers.IO) {
        startLock.withLock {
            val job = store.all().firstOrNull { it.planId == plan && !it.active } ?: return@withLock
            val wasCurrent = store.read()?.planId == plan
            info.deleteJobDownloads(job.planId)
            lyrics.deleteJobDownloads(job.planId)
            store.remove(plan)
            if (wasCurrent) store.read()?.takeIf { it.active }?.let { enqueue(it.id, ExistingWorkPolicy.REPLACE) }
        }
    }
    suspend fun invalidateTargets(keys: Set<String>) = withContext(Dispatchers.IO) { store.invalidate(keys) }

    private suspend fun resolveSongs(ids: List<String>?): List<Song> = if (ids == null) music.getAllSongsOnce()
        else ids.distinct().chunked(400).flatMap { music.getSongsByIds(it).first() }

    private fun enqueue(id: String, policy: ExistingWorkPolicy, waitMs: Long = 0L) {
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(workDataOf("batch" to id))
            .setInitialDelay(waitMs, TimeUnit.MILLISECONDS)
            .setConstraints(offlineNetworkConstraints()).build()
        work.enqueueUniqueWork(WORK_NAME, policy, request)
    }
    companion object { const val WORK_NAME = "offline-music-downloads" }
}
