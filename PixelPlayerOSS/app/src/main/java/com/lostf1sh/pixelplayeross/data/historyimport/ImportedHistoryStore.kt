package com.lostf1sh.pixelplayeross.data.historyimport

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Persistent on-device store for imported listening history (`files/imported_history/`).
 *
 * Completely separate from `playback_history.json`, which stays Polylute's own playback record.
 * Each import lives in its own gzipped streaming JSON file (`<importId>.json.gz`) plus an
 * atomic `manifest.json` of [ImportRecord] summaries.
 */
@Singleton
class ImportedHistoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogSource: CatalogSource,
    private val librarySignal: LibraryChangeSignal,
    private val preferences: UserPreferencesRepository,
    private val playbackStatsRepository: PlaybackStatsRepository = PlaybackStatsRepository(context),
) : ImportedStatsProvider {

    private val dir = File(context.filesDir, "imported_history")
    private val manifestFile = File(dir, "manifest.json")
    private val gson = Gson()
    private val ioLock = Any()
    private val writeMutex = Mutex()

    private val _records = MutableStateFlow(readManifestLocked())
    override val records: StateFlow<List<ImportRecord>> = _records.asStateFlow()

    private val _refreshVersion = MutableStateFlow(0L)
    override val changes: Flow<Long> = combine(_refreshVersion, librarySignal.version) { r, l ->
        r * 1_000_003L + l
    }

    // Cached raw plays + distinct tracks so 500k plays are loaded from disk once and re-matched cheaply on library edits.
    private var cachedRaw: RawStoreSnapshot? = null
    private var cachedVersion = -1L
    private var cachedStatsCatalog: LibraryCatalog? = null
    private var cachedStatsInput: ImportedStatsInput? = null

    private class RawTrack(
        val artistRaw: String,
        val artists: List<String>,
        val title: String,
        val album: String?,
        val matchedDurationMs: Long?,
    )

    private class RawStoreSnapshot(
        val tracks: Array<RawTrack>,
        val trackIndices: IntArray,
        val timestampsMs: LongArray,
        val listenedMs: LongArray,
        val estimated: BooleanArray,
    )

    fun inspectInput(sources: List<ImportInputSource>): CsvColumnMapping? =
        HistoryImportPipeline.inspectInput(sources)

    suspend fun prepareImport(
        sources: List<ImportInputSource>,
        csvMapping: CsvColumnMapping? = null,
        existingFavoriteSongIds: Set<String> = emptySet(),
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportPreview = withContext(Dispatchers.Default) {
        val catalog = withContext(Dispatchers.IO) { catalogSource.load() }
        val delimiters = runCatching { preferences.artistDelimitersFlow.first() }
            .getOrDefault(UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS)
        HistoryImportPipeline.prepareImport(
            sources = sources,
            catalog = catalog,
            existingDedupKeys = { kind -> loadDedupKeysForKind(kind) },
            csvMapping = csvMapping,
            artistDelimiters = delimiters,
            existingFavoriteSongIds = existingFavoriteSongIds,
            onProgress = onProgress,
            loadNativeEvents = playbackStatsRepository::exportEventsForBackup,
        )
    }

    /**
     * Pure/test-friendly prepare variant using an explicit [catalog] and [delimiters].
     */
    suspend fun prepareImportWithCatalog(
        sources: List<ImportInputSource>,
        catalog: LibraryCatalog,
        csvMapping: CsvColumnMapping? = null,
        artistDelimiters: List<String> = UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
        existingFavoriteSongIds: Set<String> = emptySet(),
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportPreview = withContext(Dispatchers.Default) {
        HistoryImportPipeline.prepareImport(
            sources = sources,
            catalog = catalog,
            existingDedupKeys = { kind -> loadDedupKeysForKind(kind) },
            csvMapping = csvMapping,
            artistDelimiters = artistDelimiters,
            existingFavoriteSongIds = existingFavoriteSongIds,
            onProgress = onProgress,
            loadNativeEvents = playbackStatsRepository::exportEventsForBackup,
        )
    }

    suspend fun commitImport(
        preview: ImportPreview,
        importedAtMs: Long = System.currentTimeMillis(),
    ): ImportRecord = commitImportInternal(preview, importedAtMs).first

    /**
     * Commits [preview] using [PreparedImport.batchId] if not already persisted in `manifest.json`.
     * Returns `true` if a new import record was written, `false` if this exact [PreparedImport.batchId]
     * was already committed (making repeated Done calls idempotent).
     */
    suspend fun commitImportIfAbsent(
        preview: ImportPreview,
        importedAtMs: Long = System.currentTimeMillis(),
    ): Boolean = commitImportInternal(preview, importedAtMs).second

    private suspend fun commitImportInternal(
        preview: ImportPreview,
        importedAtMs: Long,
    ): Pair<ImportRecord, Boolean> = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val id = preview.prepared.batchId.ifBlank { UUID.randomUUID().toString() }
            val prepared = preview.prepared
            var writtenNew = false
            val record = synchronized(ioLock) {
                val current = readManifestLocked()
                val existing = current.firstOrNull { it.id == id }
                if (existing != null) {
                    existing
                } else {
                    val fresh = ImportRecord(
                        id = id,
                        sourceKind = preview.sourceKind,
                        fileNames = preview.fileNames,
                        importedAtMs = importedAtMs,
                        dateRangeStartMs = preview.dateRangeStartMs,
                        dateRangeEndMs = preview.dateRangeEndMs,
                        counts = preview.counts,
                        topUnmatchedArtists = preview.topUnmatchedArtists,
                    )
                    if (!dir.exists()) dir.mkdirs()
                    writeImportPayloadLocked(id, prepared)
                    val updated = listOf(fresh) + current
                    writeManifestLocked(updated)
                    invalidateCacheLocked()
                    _records.value = updated
                    writtenNew = true
                    fresh
                }
            }
            if (writtenNew) bumpRefresh()
            record to writtenNew
        }
    }

    suspend fun removeImport(importId: String): Boolean = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val removed = synchronized(ioLock) {
                val current = readManifestLocked()
                val next = current.filterNot { it.id == importId }
                if (next.size == current.size) {
                    false
                } else {
                    writeManifestLocked(next)
                    payloadFile(importId).delete()
                    invalidateCacheLocked()
                    _records.value = next
                    true
                }
            }
            if (removed) bumpRefresh()
            removed
        }
    }

    override suspend fun statsInput(catalog: LibraryCatalog): ImportedStatsInput = withContext(Dispatchers.Default) {
        val version = _refreshVersion.value
        synchronized(ioLock) {
            if (cachedVersion == version && cachedStatsCatalog === catalog && cachedStatsInput != null) {
                return@withContext cachedStatsInput!!
            }
        }
        val raw = withContext(Dispatchers.IO) {
            synchronized(ioLock) {
                if (cachedVersion == version && cachedRaw != null) {
                    cachedRaw!!
                } else {
                    loadRawSnapshotLocked().also {
                        cachedRaw = it
                        cachedVersion = version
                        cachedStatsInput = null
                    }
                }
            }
        }
        if (raw.timestampsMs.isEmpty()) {
            return@withContext ImportedStatsInput.Empty
        }

        val delimiters = runCatching { preferences.artistDelimitersFlow.first() }
            .getOrDefault(UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS)
        val matcher = LibraryTrackMatcher.build(catalog, delimiters)

        val statsTracks = Array(raw.tracks.size) { idx ->
            val rt = raw.tracks[idx]
            val (matchResult, item) = matcher.match(rt.artistRaw, rt.title, rt.album, rt.matchedDurationMs)
            if (item != null) {
                ImportedStatsTrack(
                    key = item.identity,
                    title = item.title,
                    artistRaw = item.artistLine,
                    artists = item.artists,
                    album = item.album,
                    matchedItem = item,
                    unmatchedAlbumKey = null,
                )
            } else {
                val foldedArtist = LibraryTrackMatcher.foldBase(rt.artistRaw)
                val foldedTitle = LibraryTrackMatcher.foldBase(rt.title)
                val albumTitle = rt.album.orEmpty().trim()
                val albumKey = if (albumTitle.isNotBlank()) {
                    val primary = rt.artists.firstOrNull()?.let(LibraryTrackMatcher::foldBase).orEmpty()
                    "imported:album:${LibraryTrackMatcher.foldBase(albumTitle)}:$primary"
                } else {
                    null
                }
                val unmatchedKey = "imported:track:$foldedArtist:$foldedTitle:${matchResult.confidence.name}"
                ImportedStatsTrack(
                    key = unmatchedKey,
                    title = rt.title,
                    artistRaw = rt.artistRaw,
                    artists = rt.artists.ifEmpty { listOf(rt.artistRaw) },
                    album = albumTitle,
                    matchedItem = null,
                    unmatchedAlbumKey = albumKey,
                )
            }
        }

        val built = ImportedStatsInput(
            tracks = statsTracks,
            trackIndices = raw.trackIndices,
            timestampsMs = raw.timestampsMs,
            listenedMs = raw.listenedMs,
            estimated = raw.estimated,
        )
        synchronized(ioLock) {
            if (cachedVersion == version) {
                cachedStatsCatalog = catalog
                cachedStatsInput = built
            }
        }
        built
    }

    // --- Disk serialization -------------------------------------------------------------------------

    private fun payloadFile(importId: String): File = File(dir, "$importId.json.gz")

    private fun readManifestLocked(): List<ImportRecord> {
        if (!manifestFile.exists()) return emptyList()
        return runCatching {
            FileInputStream(manifestFile).use { stream ->
                val type = object : TypeToken<List<ImportRecord>>() {}.type
                gson.fromJson<List<ImportRecord>>(InputStreamReader(stream, Charsets.UTF_8), type).orEmpty()
            }
        }.onFailure { err ->
            if (err !is FileNotFoundException) Timber.e(err, "Failed reading imported_history manifest")
        }.getOrDefault(emptyList())
    }

    private fun writeManifestLocked(records: List<ImportRecord>) {
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "manifest.json.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                val writer = OutputStreamWriter(out, Charsets.UTF_8)
                gson.toJson(records, writer)
                writer.flush()
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (manifestFile.exists() && !tmp.renameTo(manifestFile)) {
                manifestFile.delete()
                check(tmp.renameTo(manifestFile)) { "Failed to replace ${manifestFile.path}" }
            } else if (!manifestFile.exists()) {
                check(tmp.renameTo(manifestFile)) { "Failed to create ${manifestFile.path}" }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun writeImportPayloadLocked(importId: String, prepared: PreparedImport) {
        val target = payloadFile(importId)
        val tmp = File(dir, "$importId.json.gz.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                val gz = GZIPOutputStream(BufferedOutputStream(fos, 64 * 1024))
                val w = JsonWriter(OutputStreamWriter(gz, Charsets.UTF_8))
                w.beginObject()
                w.name("v").value(1)
                w.name("sourceKind").value(prepared.sourceKind.name)
                w.name("tracks").beginArray()
                for (t in prepared.tracks) {
                    w.beginObject()
                    w.name("artistRaw").value(t.artistRaw)
                    w.name("artists").beginArray()
                    t.artists.forEach { w.value(it) }
                    w.endArray()
                    w.name("title").value(t.title)
                    if (t.album != null) w.name("album").value(t.album)
                    if (t.matchedDurationMs != null) w.name("dur").value(t.matchedDurationMs)
                    w.endObject()
                }
                w.endArray()
                w.name("plays").beginArray()
                val n = prepared.timestampsMs.size
                for (i in 0 until n) {
                    w.beginArray()
                    w.value(prepared.trackIndices[i].toLong())
                    w.value(prepared.timestampsMs[i])
                    w.value(prepared.listenedMs[i])
                    w.value(if (prepared.estimated[i]) 1L else 0L)
                    w.value(prepared.sourceListenedMs[i])
                    w.value(prepared.dedupHi[i])
                    w.value(prepared.dedupLo[i])
                    w.endArray()
                }
                w.endArray()
                w.endObject()
                w.flush()
                gz.finish()
                gz.flush()
                fos.flush()
                runCatching { fos.fd.sync() }
                w.close()
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun loadDedupKeysForKind(kind: ImportSourceKind): Set<DedupKey> {
        val crossSpotifyKind = when (kind) {
            ImportSourceKind.SPOTIFY_ACCOUNT -> ImportSourceKind.SPOTIFY_EXTENDED
            ImportSourceKind.SPOTIFY_EXTENDED -> ImportSourceKind.SPOTIFY_ACCOUNT
            else -> null
        }
        val allRecords = synchronized(ioLock) { readManifestLocked() }
        val sameKindRecords = allRecords.filter { it.sourceKind == kind }
        val crossRecords = if (crossSpotifyKind != null) {
            allRecords.filter { it.sourceKind == crossSpotifyKind }
        } else {
            emptyList()
        }
        if (sameKindRecords.isEmpty() && crossRecords.isEmpty()) return emptySet()

        val keys = HashSet<DedupKey>()
        for (rec in sameKindRecords) {
            val file = payloadFile(rec.id)
            if (!file.exists()) continue
            runCatching {
                GZIPInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { gz ->
                    JsonReader(InputStreamReader(gz, Charsets.UTF_8)).use { r ->
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "plays" -> {
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        r.beginArray()
                                        r.skipValue() // trackIdx
                                        r.skipValue() // ts
                                        r.skipValue() // listenedMs
                                        r.skipValue() // estimated
                                        r.skipValue() // srcListenedMs
                                        val hi = r.nextLong()
                                        val lo = r.nextLong()
                                        while (r.hasNext()) r.skipValue()
                                        r.endArray()
                                        keys += DedupKey(hi, lo)
                                    }
                                    r.endArray()
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    }
                }
            }.onFailure { Timber.w(it, "Failed reading dedup keys from %s", file.name) }
        }

        // Cross-format Spotify dedup: Extended (seconds) <-> Account data (minute endTime)
        val crossOverlapOccurrences = HashMap<DedupKey, Int>()
        for (rec in crossRecords) {
            val file = payloadFile(rec.id)
            if (!file.exists()) continue
            runCatching {
                val trackMeta = ArrayList<Pair<String, String>>() // artistRaw, title
                GZIPInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { gz ->
                    JsonReader(InputStreamReader(gz, Charsets.UTF_8)).use { r ->
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "tracks" -> {
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        val t = readRawTrack(r)
                                        trackMeta += t.artistRaw to t.title
                                    }
                                    r.endArray()
                                }
                                "plays" -> {
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        r.beginArray()
                                        val tIdx = r.nextInt()
                                        val startMs = r.nextLong()
                                        val listenedMs = r.nextLong()
                                        r.skipValue() // estimated
                                        val srcListened = r.nextLong()
                                        while (r.hasNext()) r.skipValue()
                                        r.endArray()
                                        val track = trackMeta.getOrNull(tIdx) ?: continue
                                        val effectiveListened = if (srcListened >= 0L) srcListened else listenedMs
                                        val endMs = startMs + effectiveListened
                                        val overlapCanonical = HistoryImportPipeline.spotifyMinuteDedupCanonical(
                                            endMs = endMs,
                                            artistRaw = track.first,
                                            title = track.second,
                                            listenedMs = effectiveListened,
                                        )
                                        val overlapBase = HistoryImportPipeline.hashCanonical(overlapCanonical)
                                        val occ = crossOverlapOccurrences[overlapBase] ?: 0
                                        crossOverlapOccurrences[overlapBase] = occ + 1
                                        keys += if (occ == 0) {
                                            overlapBase
                                        } else {
                                            HistoryImportPipeline.hashCanonical("$overlapCanonical\u0000#$occ")
                                        }
                                    }
                                    r.endArray()
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    }
                }
            }.onFailure { Timber.w(it, "Failed reading cross-format Spotify keys from %s", file.name) }
        }
        return keys
    }

    private fun loadRawSnapshotLocked(): RawStoreSnapshot {
        val records = readManifestLocked()
        if (records.isEmpty()) {
            return RawStoreSnapshot(emptyArray(), IntArray(0), LongArray(0), LongArray(0), BooleanArray(0))
        }
        val totalPlaysEstimate = records.sumOf { it.counts.plays.coerceAtLeast(0) }.coerceAtLeast(16)
        val tracks = ArrayList<RawTrack>()
        var trackIdxArr = IntArray(totalPlaysEstimate)
        var tsArr = LongArray(totalPlaysEstimate)
        var listenedArr = LongArray(totalPlaysEstimate)
        var estArr = BooleanArray(totalPlaysEstimate)
        var size = 0

        fun ensureCapacity(needed: Int) {
            if (needed <= trackIdxArr.size) return
            val next = (trackIdxArr.size * 2).coerceAtLeast(needed)
            trackIdxArr = trackIdxArr.copyOf(next)
            tsArr = tsArr.copyOf(next)
            listenedArr = listenedArr.copyOf(next)
            estArr = estArr.copyOf(next)
        }

        for (rec in records) {
            val file = payloadFile(rec.id)
            if (!file.exists()) continue
            val trackOffset = tracks.size
            runCatching {
                GZIPInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { gz ->
                    JsonReader(InputStreamReader(gz, Charsets.UTF_8)).use { r ->
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "tracks" -> {
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        tracks += readRawTrack(r)
                                    }
                                    r.endArray()
                                }
                                "plays" -> {
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        r.beginArray()
                                        val localIdx = r.nextInt()
                                        val ts = r.nextLong()
                                        val ms = r.nextLong()
                                        val est = r.nextInt() != 0
                                        while (r.hasNext()) r.skipValue()
                                        r.endArray()
                                        ensureCapacity(size + 1)
                                        trackIdxArr[size] = trackOffset + localIdx
                                        tsArr[size] = ts
                                        listenedArr[size] = ms
                                        estArr[size] = est
                                        size++
                                    }
                                    r.endArray()
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    }
                }
            }.onFailure { Timber.e(it, "Failed reading import payload %s", file.name) }
        }

        if (size == 0) {
            return RawStoreSnapshot(emptyArray(), IntArray(0), LongArray(0), LongArray(0), BooleanArray(0))
        }

        // Sort ascending by timestampMs once so StatsEngine can binary-search any range.
        val isAlreadySorted = (1 until size).all { tsArr[it] >= tsArr[it - 1] }
        if (!isAlreadySorted) {
            val order = (0 until size).sortedWith(compareBy { tsArr[it] })
            val sortedTracks = IntArray(size)
            val sortedTs = LongArray(size)
            val sortedMs = LongArray(size)
            val sortedEst = BooleanArray(size)
            order.forEachIndexed { dest, src ->
                sortedTracks[dest] = trackIdxArr[src]
                sortedTs[dest] = tsArr[src]
                sortedMs[dest] = listenedArr[src]
                sortedEst[dest] = estArr[src]
            }
            return RawStoreSnapshot(tracks.toTypedArray(), sortedTracks, sortedTs, sortedMs, sortedEst)
        }

        return RawStoreSnapshot(
            tracks = tracks.toTypedArray(),
            trackIndices = trackIdxArr.copyOf(size),
            timestampsMs = tsArr.copyOf(size),
            listenedMs = listenedArr.copyOf(size),
            estimated = estArr.copyOf(size),
        )
    }

    private fun readRawTrack(r: JsonReader): RawTrack {
        var artistRaw = ""
        val artists = ArrayList<String>(2)
        var title = ""
        var album: String? = null
        var dur: Long? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "artistRaw" -> artistRaw = r.stringOrNull().orEmpty()
                "artists" -> {
                    if (r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray()
                        while (r.hasNext()) r.stringOrNull()?.let(artists::add)
                        r.endArray()
                    } else {
                        r.skipValue()
                    }
                }
                "title" -> title = r.stringOrNull().orEmpty()
                "album" -> album = r.stringOrNull()
                "dur" -> dur = r.longOrNull()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return RawTrack(artistRaw, artists, title, album, dur)
    }

    private fun invalidateCacheLocked() {
        cachedRaw = null
        cachedStatsCatalog = null
        cachedStatsInput = null
    }

    private fun bumpRefresh() {
        _refreshVersion.update { if (it == Long.MAX_VALUE) 1L else it + 1L }
    }
}
