package com.lostf1sh.pixelplayeross.data.offline

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.room.withTransaction
import androidx.work.*
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.*
import com.lostf1sh.pixelplayeross.data.network.DownloadOwner
import com.lostf1sh.pixelplayeross.data.network.RemoteOutcome
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.utils.splitArtistsByDelimiters
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Dedicated background threads keep file inspection and discovery away from UI and playback. */
internal val infoBackground = Executors.newFixedThreadPool(2) { task ->
    Thread({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        task.run()
    }, "music-info-background").apply { isDaemon = true }
}.asCoroutineDispatcher()

internal fun infoSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()

@Singleton
class MusicInfoDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: PixelPlayerDatabase,
    private val music: MusicRepository,
    private val lyrics: LyricsRepository,
    private val info: MusicInfoRepository,
) {
    val dao get() = database.musicInfoDiscoveryDao()
    private val control = Mutex()
    internal val lastOpenSteps = linkedMapOf<String, Double>()

    private inline fun <T> catalogStep(name: String, block: () -> T): T {
        val started = System.nanoTime()
        return try {
            block()
        } finally {
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            lastOpenSteps[name] = elapsed
            timber.log.Timber.tag("InfoCatalog").d("%s %.3f ms", name, elapsed)
        }
    }

    private val refresh = Mutex()
    private val catalogGeneration = java.util.concurrent.atomic.AtomicLong()
    private val catalogObserver = object : androidx.room.InvalidationTracker.Observer(
        "songs", "albums", "artists", "song_artist_cross_ref", "lyrics"
    ) {
        override fun onInvalidated(tables: Set<String>) {
            catalogGeneration.incrementAndGet()
        }
    }
    private data class OpenRevision(
        val generation: Long,
        val databaseFiles: Any,
        val folders: String,
        val saved: Any,
        val deleted: Any?,
    )
    private val completedRevisions = mutableMapOf<String, OpenRevision>()
    private val emptyScopes = mutableSetOf<String>()

    init {
        if (database.openHelper.databaseName != null) {
            database.invalidationTracker.addObserver(catalogObserver)
        }
    }

    private fun databaseFilesRevision(): Any {
        val name = database.openHelper.databaseName
        // An in-memory database has no durable change token, so it always takes the full path.
        if (name == null) return Any()
        val file = context.getDatabasePath(name)
        return listOf(file, java.io.File(file.path + "-wal")).map { path ->
            try {
                val attributes = java.nio.file.Files.readAttributes(
                    path.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java
                )
                listOf(attributes.fileKey()?.toString(), attributes.size(), attributes.lastModifiedTime())
            } catch (_: java.nio.file.NoSuchFileException) {
                null
            }
        }
    }

    private suspend fun openRevision(): OpenRevision = OpenRevision(
        catalogGeneration.get(), databaseFilesRevision(), music.catalogFilterKey(),
        info.service.savedRevision(), info.deletedDownloads.revision(),
    )

    private var initialized = false
    private val work get() = WorkManager.getInstance(context)

    suspend fun initialize() = withContext(infoBackground) {
        control.withLock {
            if (initialized) return@withLock
            var queue = dao.queueOnce()
            if (queue == null) {
                dao.initQueue(InfoQueue())
                queue = dao.queueOnce()
            }
            if (queue?.legacyDropped != true) {
                // Only WorkManager is cancelled. Old job files and finished assets remain unread.
                work.cancelUniqueWork("offline-music-downloads").result.get()
                dao.legacyDropped()
            }
            initialized = true
        }
    }

    /** Known before refresh, so existing Room pages remain usable while the catalog catches up. */
    fun scopeKey(ids: List<String>?, artistFilter: String?): String {
        if (ids == null) return "library"
        val source = ids.sorted().joinToString("\u0000") + "|" + artistFilter.orEmpty()
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }

    /** Refresh only changed membership and saved flags. No network or media-file inspection. */
    suspend fun open(ids: List<String>?, artistFilter: String? = null): String = withContext(infoBackground) {
        refresh.withLock {
            lastOpenSteps.clear()
            catalogStep("initialize") { initialize() }
            val scope = scopeKey(ids, artistFilter)
            val revision = catalogStep("revisionChecks") { openRevision() }
            if (completedRevisions[scope] == revision && (scope in emptyScopes || dao.hasScope(scope))) return@withLock scope

            val songs = catalogStep("songsFromRoom") {
                if (ids == null) music.getAllSongsOnce()
                else ids.distinct().chunked(400).flatMap { music.getSongsByIds(it).first() }
            }
            val libraryFingerprint = catalogStep("libraryFingerprint") { libraryFingerprint(songs) }
            val savedInfo = catalogStep("savedInfoSnapshot") { info.service.savedSnapshot() }
            val savedLyrics = catalogStep("savedLyrics") { database.lyricsDao().downloadedLyricsIdentities().toSet() }
            val deleted = catalogStep("deletedMarks") { info.deletedDownloads.snapshot() }
            val savedFingerprint = catalogStep("savedFingerprint") { digest(listOf(
                savedInfo.fingerprint(),
                savedLyrics.sorted().joinToString("\n"),
                deleted.fingerprint(),
            )) }
            val stampFile = java.io.File(context.filesDir, "music-info-catalog/$scope.stamp")
            val previous = catalogStep("catalogStamp") { runCatching { stampFile.readLines() }.getOrDefault(emptyList()) }
            val rebuild = previous.firstOrNull() != libraryFingerprint ||
                (songs.isNotEmpty() && !dao.hasScope(scope))
            if (!rebuild && previous.getOrNull(1) == savedFingerprint) {
                if (openRevision() == revision) completedRevisions[scope] = revision
                if (songs.isEmpty()) emptyScopes.add(scope) else emptyScopes.remove(scope)
                return@withLock scope
            }

            if (rebuild) {
                refreshMembership(scope, songs, artistFilter)
            }
            val songsById = songs.associateBy { it.id }
            val changed = dao.scopeRows(scope).mapNotNull { row ->
                val saved = !deleted.contains(row.key) &&
                    isSaved(row, songsById[row.songId], savedInfo, savedLyrics)
                if (saved == row.saved) null else row to saved
            }
            if (changed.isNotEmpty()) {
                database.withTransaction {
                    for ((row, saved) in changed) {
                        // A download completed after the snapshot must win over this import.
                        dao.saved(row.key, saved, row.downloadState)
                    }
                }
            }
            stampFile.parentFile?.mkdirs()
            val stamp = android.util.AtomicFile(stampFile)
            val output = stamp.startWrite()
            try {
                output.write("$libraryFingerprint\n$savedFingerprint\n".toByteArray())
                stamp.finishWrite(output)
            } catch (error: Exception) {
                stamp.failWrite(output)
                throw error
            }
            val finished = openRevision()
            if (finished.copy(databaseFiles = revision.databaseFiles) == revision) {
                completedRevisions[scope] = finished
            }
            if (songs.isEmpty()) emptyScopes.add(scope) else emptyScopes.remove(scope)
            scope
        }
    }

    private suspend fun refreshMembership(scope: String, songs: List<Song>, artistFilter: String?) {
        val rows = plan(songs)
        val members = artistMembers(scope, songs, rows.map { it.key }.toSet(), artistFilter)
        val previous = dao.scopeRows(scope).associate { it.key to it.catalogMetadata() }
        val changedMetadata = rows.map { it.catalogMetadata() }.filter { row ->
            previous[row.key]?.let { it != row } == true
        }
        database.withTransaction {
            dao.insert(rows)
            if (changedMetadata.isNotEmpty()) dao.updateCatalog(changedMetadata)
            dao.clearScopeAssets(scope)
            dao.clearScopeArtists(scope)
            dao.addScope(members.map { InfoScope(scope, it.itemKey) }.distinct())
            dao.addArtistMembers(members)
            dao.prioritize(scope)
        }
    }

    private fun libraryFingerprint(songs: List<Song>): String = digest(songs.sortedBy { it.id }.flatMap { song ->
        listOf(song.id, song.libraryItemId, song.title, song.artist, song.album, song.albumArtist.orEmpty(),
            song.albumId.toString(), song.artists.joinToString("\u0000") { it.name })
    })

    private fun digest(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray()
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    private fun artistMembers(
        scope: String,
        songs: List<Song>,
        keys: Set<String>,
        artistFilter: String?,
    ): List<InfoArtistMember> {
        val members = linkedMapOf<Pair<String, String>, InfoArtistMember>()
        for (song in songs) {
            val names = song.artists.map { it.name }.ifEmpty {
                song.artist.splitArtistsByDelimiters(UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS)
            }
            val credit = song.albumArtist?.takeIf(String::isNotBlank) ?: song.artist
            val shared = listOf(
                DownloadKeys.cover(song.album, credit),
                DownloadKeys.about(AboutKind.ALBUM, song.album, credit),
                DownloadKeys.lyrics(song),
            )
            val visibleNames = names.filter { name ->
                name.isNotBlank() && (artistFilter == null || name.equals(artistFilter, ignoreCase = true))
            }
            for (name in visibleNames) {
                val artistKey = name.trim().lowercase(Locale.ROOT)
                val targets = shared + DownloadKeys.photo(name) + DownloadKeys.about(AboutKind.ARTIST, name)
                for (key in targets.filter { it in keys }) {
                    val search = if (key.startsWith("photo|") || key.startsWith("about|false|")) {
                        infoSearch(name)
                    } else {
                        infoSearch("$name ${song.album}")
                    }
                    members[artistKey to key] = InfoArtistMember(scope, artistKey, name, key, search)
                }
            }
        }
        return members.values.toList()
    }

    internal fun plan(songs: List<Song>): List<InfoDiscovery> {
        val rows = linkedMapOf<String, InfoDiscovery>()
        for (song in songs) {
            val names = song.artists.map { it.name }.ifEmpty {
                song.artist.splitArtistsByDelimiters(
                    UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS
                )
            }
            for (name in names.filter(String::isNotBlank)) {
                val artistKey = name.trim().lowercase(Locale.ROOT)
                rows.putIfAbsent(DownloadKeys.photo(name), InfoDiscovery(
                    DownloadKeys.photo(name), InfoKind.PHOTO.name, name,
                    artistKey, name, searchText = infoSearch(name),
                ))
                val bioKey = DownloadKeys.about(AboutKind.ARTIST, name)
                rows.putIfAbsent(bioKey, InfoDiscovery(
                    bioKey, InfoKind.BIO.name, name,
                    artistKey, name, searchText = infoSearch(name),
                ))
            }
            val credit = song.albumArtist?.takeIf(String::isNotBlank) ?: song.artist
            val artist = MusicBrainzClient.firstCreditedArtist(credit)
            val artistKey = artist.trim().lowercase(Locale.ROOT)
            val albumKey = DownloadKeys.cover(song.album, artist)
            val search = infoSearch("$artist ${song.album}")
            if (song.album.isNotBlank()) {
                rows.putIfAbsent(albumKey, InfoDiscovery(
                    albumKey, InfoKind.COVER.name, song.album,
                    artistKey, artist, albumKey,
                    song.album, song.id, search,
                    albumArtist = credit,
                ))
                val descriptionKey = DownloadKeys.about(AboutKind.ALBUM, song.album, artist)
                rows.putIfAbsent(descriptionKey, InfoDiscovery(
                    descriptionKey, InfoKind.DESCRIPTION.name, song.album,
                    artistKey, artist, albumKey,
                    song.album, song.id, search,
                    albumArtist = credit,
                ))
            }
            val lyricsKey = DownloadKeys.lyrics(song)
            rows.putIfAbsent(lyricsKey, InfoDiscovery(
                lyricsKey, InfoKind.LYRICS.name, song.title,
                artistKey, artist, albumKey,
                song.album, song.id, search,
            ))
        }
        return rows.values.toList()
    }

    fun artists(scope: String, search: String) = Pager(
        PagingConfig(pageSize = 24, initialLoadSize = 24, enablePlaceholders = false)
    ) {
        dao.artists(scope, infoSearch(search))
    }.flow

    suspend fun find(scope: String) = withContext(infoBackground) {
        dao.find(scope, System.currentTimeMillis() - FRESH_MS, if (scope == "library") 1 else 0)
        enqueueFind()
    }

    suspend fun appendSelected(scope: String) = withContext(infoBackground) {
        control.withLock {
            for (row in dao.scopeRows(scope).filter { it.selected && !it.saved && it.availability == "AVAILABLE" }) {
                info.deletedDownloads.allow(listOf(row.key))
            }
            dao.appendSelected(scope)
            if (dao.queueOnce()?.paused != true) enqueueDownloads()
        }
    }

    suspend fun pause() = withContext(infoBackground) {
        control.withLock {
            dao.pause(true)
            work.cancelUniqueWork(DOWNLOAD_WORK).result.get()
        }
    }

    suspend fun resume() = withContext(infoBackground) {
        control.withLock {
            dao.pause(false)
            enqueueDownloads()
        }
    }

    suspend fun deleteSelected(scope: String) = withContext(infoBackground) {
        control.withLock {
            for (row in dao.selectedSaved(scope)) {
                // The mark serializes with the save commit, including a request already in flight.
                info.deletedDownloads.mark(listOf(row.key))
                if (row.kind == InfoKind.LYRICS.name) song(row)?.let { lyrics.deleteDownloadedLyrics(it) }
                else info.deleteDiscovered(InfoKind.valueOf(row.kind), row.title, row.albumArtist.ifBlank { row.artistName }, row.songId)
                dao.deleted(row.key)
            }
        }
    }

    internal suspend fun checkOne(row: InfoDiscovery) {
        val started = android.os.SystemClock.elapsedRealtime()
        val local = when (InfoKind.valueOf(row.kind)) {
            InfoKind.COVER -> {
                val representative = song(row)
                val albumSongs = if (representative == null) emptyList() else music.getSongsForAlbum(representative.albumId).first()
                albumSongs.any { OfflineArtwork.hasLocalCover(context, it) }
            }
            InfoKind.LYRICS -> song(row)?.let { lyrics.hasLocalLyrics(it) } == true
            else -> false
        }
        if (local) {
            dao.checked(row.key, "LOCAL", null, null, System.currentTimeMillis())
        } else {
            val result = RemoteOutcome.lookup {
                if (row.kind == InfoKind.LYRICS.name) song(row)?.let { lyrics.discoverLyrics(it) }?.let { ResolvedInfo(it) }
                else info.service.discover(InfoKind.valueOf(row.kind), row.title, row.albumArtist.ifBlank { row.artistName })
            }
            currentCoroutineContext().ensureActive()
            when (result) {
                is RemoteOutcome.Found -> dao.checked(row.key, "AVAILABLE", result.value.payload, result.value.sourceBytes, System.currentTimeMillis())
                RemoteOutcome.NotFound -> dao.checked(row.key, "MISSING", null, null, System.currentTimeMillis())
                RemoteOutcome.Failed -> dao.checked(row.key, "FAILED", null, null, System.currentTimeMillis())
            }
        }
        dao.measured(android.os.SystemClock.elapsedRealtime() - started)
    }

    internal suspend fun downloadOne(target: InfoDiscovery) {
        var row = target
        if (row.payload == null || row.checkedAt < System.currentTimeMillis() - FRESH_MS) {
            checkOne(row)
            row = dao.get(row.key) ?: return
        }
        if (dao.queueOnce()?.paused == true) return
        dao.currentArtist(row.artistName)
        val result = RemoteOutcome.lookup {
            withContext(DownloadOwner("music-info-queue") {}) {
                val payload = row.payload ?: return@withContext null
                val saved = if (row.kind == InfoKind.LYRICS.name) {
                    song(row)?.let { lyrics.saveDiscoveredLyrics(it, payload) } == true
                } else {
                    info.service.saveDiscovered(InfoKind.valueOf(row.kind), row.title, row.albumArtist.ifBlank { row.artistName }, payload)
                }
                saved.takeIf { it }
            }
        }
        currentCoroutineContext().ensureActive()
        if (dao.queueOnce()?.paused == true) return
        if (result is RemoteOutcome.Found) {
            if (row.kind == InfoKind.PHOTO.name) info.updateDiscoveredPhoto(row.title)
            if (row.kind == InfoKind.COVER.name) row.songId.toLongOrNull()?.let { info.downloadedCoverArrived(it) }
            dao.downloaded(row.key)
        } else {
            val attempts = row.attempts + 1
            val delay = (30_000L shl (attempts - 1).coerceAtMost(6)).coerceAtMost(30 * 60_000L)
            dao.retry(row.key, attempts, System.currentTimeMillis() + delay)
        }
    }

    private suspend fun song(row: InfoDiscovery): Song? = music.getSongsByIds(listOf(row.songId)).first().firstOrNull()

    private fun isSaved(
        row: InfoDiscovery,
        song: Song?,
        snapshot: SavedMusicInfo,
        lyrics: Set<String>,
    ): Boolean = when (InfoKind.valueOf(row.kind)) {
        InfoKind.PHOTO -> snapshot.photo(row.title)
        InfoKind.BIO -> snapshot.about(AboutKind.ARTIST, row.title, null)
        InfoKind.COVER -> snapshot.cover(row.title, row.albumArtist) || snapshot.cover(row.title, row.artistName)
        InfoKind.DESCRIPTION -> snapshot.about(AboutKind.ALBUM, row.title, row.albumArtist) ||
            snapshot.about(AboutKind.ALBUM, row.title, row.artistName)
        InfoKind.LYRICS -> song?.libraryItemId in lyrics
    }

    internal fun enqueueFind() {
        val request = OneTimeWorkRequestBuilder<MusicInfoFindWorker>().setConstraints(offlineNetworkConstraints()).build()
        work.enqueueUniqueWork(FIND_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    internal fun enqueueDownloads(delayMs: Long = 0) {
        val request = OneTimeWorkRequestBuilder<MusicInfoQueueWorker>().setConstraints(offlineNetworkConstraints())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS).build()
        work.enqueueUniqueWork(DOWNLOAD_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val FIND_WORK = "music-info-find"
        const val DOWNLOAD_WORK = "music-info-queue"
        const val FRESH_MS = 30L * 24 * 60 * 60 * 1000
    }
}
