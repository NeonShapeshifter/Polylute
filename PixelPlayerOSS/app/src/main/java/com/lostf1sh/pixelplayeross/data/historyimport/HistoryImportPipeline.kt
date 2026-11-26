package com.lostf1sh.pixelplayeross.data.historyimport

import com.lostf1sh.pixelplayeross.data.playlist.M3uManager
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogItem
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.data.stats.PlayRule
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository.PlaybackEvent
import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ImportInputSource(
    val displayName: String,
    val openStream: () -> InputStream,
)

internal data class DedupKey(val hi: Long, val lo: Long)

/**
 * Orchestrates streaming parse -> library matching -> PlayRule -> deduplication -> [ImportPreview].
 * Never touches the network and never writes anything to disk.
 */
internal object HistoryImportPipeline {

    private const val MAX_REJECTION_SAMPLE = 50
    private const val TOP_UNMATCHED_ARTISTS_LIMIT = 5
    internal const val UNKNOWN_LENGTH_PLAY_THRESHOLD_MS = 30_000L

    internal fun isImportedPlay(
        sourceKind: ImportSourceKind,
        listenedMs: Long,
        trackLengthMs: Long,
        skipped: Boolean?,
        reasonEnd: String?,
    ): Boolean {
        if (trackLengthMs > 0L) {
            return PlayRule.isPlay(listenedMs, trackLengthMs)
        }
        if (sourceKind == ImportSourceKind.SPOTIFY_EXTENDED) {
            if (reasonEnd.equals("trackdone", ignoreCase = true)) return true
            if (skipped == true) return false
        }
        return listenedMs >= UNKNOWN_LENGTH_PLAY_THRESHOLD_MS
    }

    fun dedupKey(
        sourceKind: ImportSourceKind,
        timestampMs: Long,
        artistRaw: String,
        title: String,
        sourceListenedMs: Long?,
        occurrence: Int = 0,
    ): DedupKey {
        val canonical = dedupCanonical(sourceKind, timestampMs, artistRaw, title, sourceListenedMs)
        return hashCanonical(if (occurrence == 0) canonical else "$canonical\u0000#$occurrence")
    }

    internal fun dedupCanonical(
        sourceKind: ImportSourceKind,
        timestampMs: Long,
        artistRaw: String,
        title: String,
        sourceListenedMs: Long?,
    ): String =
        "${sourceKind.name}\u0000$timestampMs\u0000${LibraryTrackMatcher.foldBase(artistRaw)}\u0000${LibraryTrackMatcher.foldBase(title)}\u0000${sourceListenedMs ?: -1L}"

    /**
     * Cross-format Spotify overlap key (`SPOTIFY_EXTENDED` <-> `SPOTIFY_ACCOUNT`):
     * Extended stores second-precision `ts`, whereas Account data truncates `endTime` to UTC minutes.
     * Matching on `(endMinute, foldedArtist, foldedTitle, listenedMs)` prevents double counting
     * the overlapping period while keeping a distinct namespace from exact per-format [dedupKey]s.
     */
    fun spotifyMinuteDedupKey(
        endMs: Long,
        artistRaw: String,
        title: String,
        listenedMs: Long,
        occurrence: Int = 0,
    ): DedupKey {
        val canonical = spotifyMinuteDedupCanonical(endMs, artistRaw, title, listenedMs)
        return hashCanonical(if (occurrence == 0) canonical else "$canonical\u0000#$occurrence")
    }

    internal fun spotifyMinuteDedupCanonical(
        endMs: Long,
        artistRaw: String,
        title: String,
        listenedMs: Long,
    ): String {
        val endMinute = endMs / 60_000L
        return "SPOTIFY_OVERLAP\u0000$endMinute\u0000${LibraryTrackMatcher.foldBase(artistRaw)}\u0000${LibraryTrackMatcher.foldBase(title)}\u0000$listenedMs"
    }

    internal fun hashCanonical(canonical: String): DedupKey =
        digestToDedupKey(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))

    private fun digestToDedupKey(digest: ByteArray): DedupKey {
        var hi = 0L
        var lo = 0L
        for (i in 0 until 8) hi = (hi shl 8) or (digest[i].toLong() and 0xffL)
        for (i in 8 until 16) lo = (lo shl 8) or (digest[i].toLong() and 0xffL)
        return DedupKey(hi, lo)
    }

    /**
     * Review 11-25 (B77 B5): an imported playlist's id comes from the picked file's content, the entry inside it and the
     * playlist's place in that entry. A crash during Done loses the in-memory preview; picking the same files again then
     * yields the same ids, so [UnifiedImportApplier] finds the playlist it already created instead of making a " (2)" copy.
     */
    internal fun stableImportPlaylistId(sourceHash: String, entryName: String, ordinal: Int): String {
        val key = hashCanonical("IMPORT_PLAYLIST\u0000$sourceHash\u0000$entryName\u0000$ordinal")
        return "import-%016x%016x".format(key.hi, key.lo)
    }

    private fun sha256Stream(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Inspects picked sources to determine whether CSV column mapping is required
     * or if a Musicolet / HTML error should be surfaced immediately.
     */
    fun inspectInput(sources: List<ImportInputSource>): CsvColumnMapping? {
        require(sources.isNotEmpty()) { "At least one file is required" }
        var firstCsv: ImportInputSource? = null
        var anyRecognizedOrSkippedReason = false
        var firstUnsupportedName: String? = null
        for (src in sources) {
            if (ImportFormatDetector.isMusicoletFileName(src.displayName)) {
                throw MusicoletUnsupportedException(listOf(src.displayName))
            }
            val fmt = src.openStream().use { ImportFormatDetector.detectStreamFormat(src.displayName, it) }
            when (fmt) {
                DetectedFileFormat.MUSICOLET_BACKUP -> throw MusicoletUnsupportedException(listOf(src.displayName))
                DetectedFileFormat.YOUTUBE_HTML -> throw YouTubeHtmlFormatException()
                DetectedFileFormat.ZIP -> {
                    val entries = ImportFormatDetector.scanZipEntries(src.openStream)
                    if (entries.isEmpty()) {
                        throw UnrecognizedImportFormatException("No supported files inside ${src.displayName}")
                    }
                    anyRecognizedOrSkippedReason = true
                    val importable = entries.filter {
                        it.second != DetectedFileFormat.SKIPPED_METADATA && it.second != DetectedFileFormat.UNKNOWN
                    }
                    if (firstCsv == null && importable.size == 1 && importable.first().second == DetectedFileFormat.CSV) {
                        val targetEntry = importable.first().first
                        return inspectZipCsvEntry(src.openStream, targetEntry)
                    }
                }
                DetectedFileFormat.CSV -> {
                    anyRecognizedOrSkippedReason = true
                    if (firstCsv == null) firstCsv = src
                }
                DetectedFileFormat.UNKNOWN -> {
                    if (firstUnsupportedName == null) firstUnsupportedName = src.displayName
                }
                DetectedFileFormat.SPOTIFY_EXTENDED_JSON,
                DetectedFileFormat.SPOTIFY_ACCOUNT_JSON,
                DetectedFileFormat.SPOTIFY_PLAYLISTS_JSON,
                DetectedFileFormat.SPOTIFY_LIBRARY_JSON,
                DetectedFileFormat.YOUTUBE_MUSIC_JSON,
                DetectedFileFormat.YOUTUBE_VIDEO_ID_ONLY,
                DetectedFileFormat.POLYLUTE_HISTORY_JSON,
                DetectedFileFormat.POLYLUTE_HISTORY_CSV,
                DetectedFileFormat.POLYLUTE_LIKES_CSV,
                DetectedFileFormat.POLYLUTE_PLAYLISTS_CSV,
                DetectedFileFormat.M3U,
                DetectedFileFormat.SKIPPED_METADATA -> {
                    anyRecognizedOrSkippedReason = true
                }
            }
        }
        if (!anyRecognizedOrSkippedReason && firstUnsupportedName != null) {
            throw UnrecognizedImportFormatException("Unsupported format: $firstUnsupportedName")
        }
        return firstCsv?.openStream?.invoke()?.use { CsvHistoryReader.inspect(it) }
    }

    private fun inspectZipCsvEntry(openZip: () -> InputStream, entryName: String): CsvColumnMapping {
        ZipInputStream(BufferedInputStream(openZip(), 64 * 1024)).use { zip ->
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                if (entry!!.name == entryName) {
                    return CsvHistoryReader.inspect(NonClosingInputStream(zip))
                }
                zip.closeEntry()
            }
        }
        throw UnrecognizedImportFormatException("CSV entry not found in zip: $entryName")
    }

    suspend fun prepareImport(
        sources: List<ImportInputSource>,
        catalog: LibraryCatalog,
        existingDedupKeys: (ImportSourceKind) -> Set<DedupKey>,
        csvMapping: CsvColumnMapping? = null,
        artistDelimiters: List<String> = UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
        existingFavoriteSongIds: Set<String> = emptySet(),
        onProgress: (ImportProgress) -> Unit = {},
        loadNativeEvents: suspend () -> List<PlaybackEvent> = { emptyList() },
    ): ImportPreview {
        require(sources.isNotEmpty()) { "At least one file is required" }
        val matcher = LibraryTrackMatcher.build(catalog, artistDelimiters)
        val matchCache = HashMap<Triple<String, String, String?>, Pair<TrackMatchResult, CatalogItem?>>()
        val pathIndex = CatalogPathIndex.from(catalog)

        fun matchTrack(
            artistRaw: String,
            titleRaw: String,
            albumRaw: String?,
            durationHintMs: Long?,
            path: String?,
        ): Pair<TrackMatchResult, CatalogItem?> {
            val byPath = pathIndex.find(path)
            if (byPath != null) {
                return TrackMatchResult(byPath.identity, byPath.locator, MatchConfidence.EXACT) to byPath
            }
            val cacheKey = Triple(artistRaw, titleRaw, albumRaw)
            return matchCache.getOrPut(cacheKey) {
                matcher.match(artistRaw, titleRaw, albumRaw, durationHintMs)
            }
        }

        val tracks = ArrayList<ImportedTrackKey>()
        val trackIndexByKey = HashMap<ImportedTrackKey, Int>()
        val dedupOccurrences = HashMap<DedupKey, Int>()
        val extendedOverlapOccurrences = HashMap<DedupKey, Int>()
        val accountOverlapOccurrences = HashMap<DedupKey, Int>()
        val seenExtendedOverlap = HashSet<DedupKey>()
        val seenAccountOverlap = HashSet<DedupKey>()
        val seenFileHashes = HashSet<String>()
        // Review 11-25 (B77): content hash of the source being read; playlist ids derive from it (see stableImportPlaylistId).
        var currentSourceHash = ""
        val readFileNames = ArrayList<String>(sources.size)
        val loadedExistingKinds = HashMap<ImportSourceKind, Set<DedupKey>>()
        val fileSummaries = ArrayList<ImportFileSummary>()

        // Likes accumulation (deduped by songId, preserving order)
        var likesReadCount = 0
        val matchedLikeIds = HashSet<String>()
        val matchedLikes = ArrayList<ImportedTrackRef>()
        val unmatchedLikes = ArrayList<UnmatchedTrackRef>()

        // Playlists accumulation (each keeps source order; 0-match playlists hidden & counted)
        val playlists = ArrayList<ImportPlaylistPreview>()
        var hiddenZeroMatchPlaylists = 0
        var playlistEpisodesSkipped = 0
        var playlistLocalTracksSkipped = 0

        fun handleLike(raw: RawTrackItem) {
            likesReadCount++
            val (_, item) = matchTrack(raw.artist, raw.title, raw.album, raw.durationMs, raw.path)
            if (item != null) {
                if (matchedLikeIds.add(item.locator)) {
                    matchedLikes += ImportedTrackRef(
                        songId = item.locator,
                        title = item.title,
                        artist = item.artistLine,
                        album = item.album,
                        durationMs = item.lengthMs,
                    )
                }
            } else {
                unmatchedLikes += UnmatchedTrackRef(title = raw.title, artist = raw.artist, album = raw.album)
            }
        }

        fun recordPlaylist(
            name: String,
            items: List<RawTrackItem>,
            targetId: String,
            episodesSkipped: Int = 0,
            localTracksSkipped: Int = 0,
        ) {
            playlistEpisodesSkipped += episodesSkipped
            playlistLocalTracksSkipped += localTracksSkipped
            val matched = ArrayList<ImportedTrackRef>(items.size)
            val unmatchedItems = ArrayList<UnmatchedTrackRef>()
            for (raw in items) {
                val (_, item) = matchTrack(raw.artist, raw.title, raw.album, raw.durationMs, raw.path)
                if (item != null) {
                    matched += ImportedTrackRef(
                        songId = item.locator,
                        title = item.title,
                        artist = item.artistLine,
                        album = item.album,
                        durationMs = item.lengthMs,
                    )
                } else {
                    unmatchedItems += UnmatchedTrackRef(title = raw.title, artist = raw.artist, album = raw.album)
                }
            }
            if (matched.isEmpty()) {
                if (items.isNotEmpty() || episodesSkipped > 0 || localTracksSkipped > 0) {
                    hiddenZeroMatchPlaylists++
                }
            } else {
                playlists += ImportPlaylistPreview(
                    targetId = targetId,
                    name = name.trim().ifEmpty { "Imported Playlist" },
                    totalItems = items.size,
                    matchedTracks = matched,
                    unmatchedTracks = unmatchedItems,
                    episodesSkipped = episodesSkipped,
                    localTracksSkipped = localTracksSkipped,
                    included = true,
                )
            }
        }

        // Growable primitive arrays for up to 500k+ plays without per-play object churn
        var capacity = 1024
        var size = 0
        var trackIdxArr = IntArray(capacity)
        var tsArr = LongArray(capacity)
        var listenedArr = LongArray(capacity)
        var estArr = BooleanArray(capacity)
        var srcListenedArr = LongArray(capacity)
        var dedupHiArr = LongArray(capacity)
        var dedupLoArr = LongArray(capacity)

        fun ensureCapacity(needed: Int) {
            if (needed <= capacity) return
            val next = (capacity * 2).coerceAtLeast(needed)
            trackIdxArr = trackIdxArr.copyOf(next)
            tsArr = tsArr.copyOf(next)
            listenedArr = listenedArr.copyOf(next)
            estArr = estArr.copyOf(next)
            srcListenedArr = srcListenedArr.copyOf(next)
            dedupHiArr = dedupHiArr.copyOf(next)
            dedupLoArr = dedupLoArr.copyOf(next)
            capacity = next
        }

        var rowsRead = 0
        var skips = 0
        var podcastsSkipped = 0
        var nonMusicSkipped = 0
        var duplicatesSkipped = 0
        var rejectedCount = 0
        var matchedExact = 0
        var matchedFolded = 0
        var matchedPartial = 0
        var unmatched = 0
        var estimatedPlays = 0
        var estimatedTimeMs = 0L
        var zeroDurationPlays = 0
        var totalListenedMs = 0L
        var minTs = Long.MAX_VALUE
        var maxTs = Long.MIN_VALUE
        var hasDurationData = false
        var nativePlays: NativeHistoryIndex? = null
        var hasHistorySource = false
        var detectedPrimarySource: ImportSourceKind? = null

        val unmatchedArtistCounts = HashMap<String, Int>()
        val unmatchedArtistDisplay = HashMap<String, String>()
        val rejectionsSample = ArrayList<ImportRejection>()

        val recordRejection: (ImportRejection) -> Unit = { r ->
            if (rejectionsSample.size < MAX_REJECTION_SAMPLE) rejectionsSample += r
        }

        val handleRow: (RawImportedRow) -> Unit = rowHandler@{ row ->
            hasHistorySource = true
            if (detectedPrimarySource == null) detectedPrimarySource = row.sourceKind
            val existingSet = loadedExistingKinds.getOrPut(row.sourceKind) { existingDedupKeys(row.sourceKind) }
            val baseCanonical = dedupCanonical(row.sourceKind, row.startMs, row.artistRaw, row.title, row.sourceListenedMs)
            val baseKey = hashCanonical(baseCanonical)
            val occ = dedupOccurrences[baseKey] ?: 0
            dedupOccurrences[baseKey] = occ + 1
            val dk = if (occ == 0) baseKey else hashCanonical("$baseCanonical\u0000#$occ")

            val spotifyOverlapKey = when (row.sourceKind) {
                ImportSourceKind.SPOTIFY_EXTENDED,
                ImportSourceKind.SPOTIFY_ACCOUNT -> {
                    val overlapCanonical = spotifyMinuteDedupCanonical(
                        endMs = row.endMs,
                        artistRaw = row.artistRaw,
                        title = row.title,
                        listenedMs = row.sourceListenedMs ?: 0L,
                    )
                    val overlapBase = hashCanonical(overlapCanonical)
                    val occMap = if (row.sourceKind == ImportSourceKind.SPOTIFY_EXTENDED) {
                        extendedOverlapOccurrences
                    } else {
                        accountOverlapOccurrences
                    }
                    val overlapOcc = occMap[overlapBase] ?: 0
                    occMap[overlapBase] = overlapOcc + 1
                    if (overlapOcc == 0) overlapBase else hashCanonical("$overlapCanonical\u0000#$overlapOcc")
                }
                else -> null
            }
            val alreadySeenExact = dk in existingSet
            val alreadySeenCrossSpotify = when (row.sourceKind) {
                ImportSourceKind.SPOTIFY_ACCOUNT -> spotifyOverlapKey != null &&
                    (spotifyOverlapKey in existingSet || spotifyOverlapKey in seenExtendedOverlap)
                ImportSourceKind.SPOTIFY_EXTENDED -> spotifyOverlapKey != null &&
                    (spotifyOverlapKey in existingSet || spotifyOverlapKey in seenAccountOverlap)
                else -> false
            }
            if (alreadySeenExact || alreadySeenCrossSpotify) {
                duplicatesSkipped++
            } else {
                if (spotifyOverlapKey != null) {
                    when (row.sourceKind) {
                        ImportSourceKind.SPOTIFY_EXTENDED -> seenExtendedOverlap += spotifyOverlapKey
                        ImportSourceKind.SPOTIFY_ACCOUNT -> seenAccountOverlap += spotifyOverlapKey
                        else -> Unit
                    }
                }
                val (matchResult, matchedItem) = matchTrack(
                    artistRaw = row.artistRaw,
                    titleRaw = row.title,
                    albumRaw = row.album,
                    durationHintMs = row.sourceListenedMs ?: row.trackDurationMs,
                    path = row.path,
                )

                if ((row.sourceKind == ImportSourceKind.POLYLUTE_CSV || row.sourceKind == ImportSourceKind.POLYLUTE_JSON) &&
                    nativePlays?.contains(row, matchedItem) == true
                ) {
                    duplicatesSkipped++
                    return@rowHandler
                }

                val trackLengthMs = matchedItem?.lengthMs ?: row.trackDurationMs ?: 0L
                val isEstimated: Boolean
                val finalListenedMs: Long
                val isPlay: Boolean

                if (row.sourceListenedMs != null) {
                    hasDurationData = true
                    isEstimated = false
                    finalListenedMs = row.sourceListenedMs
                    isPlay = isImportedPlay(
                        sourceKind = row.sourceKind,
                        listenedMs = finalListenedMs,
                        trackLengthMs = trackLengthMs,
                        skipped = row.skipped,
                        reasonEnd = row.reasonEnd,
                    )
                } else {
                    isEstimated = true
                    if (matchedItem != null && trackLengthMs > 0L) {
                        finalListenedMs = trackLengthMs
                        isPlay = PlayRule.isPlay(finalListenedMs, trackLengthMs)
                    } else {
                        // Unmatched row from a source without duration (e.g. YouTube Music): counts as a play with 0 listened time.
                        finalListenedMs = 0L
                        isPlay = true
                    }
                }

                if (!isPlay) {
                    skips++
                } else {
                    val splitArtists = LibraryTrackMatcher.splitArtists(row.artistRaw, row.title, artistDelimiters)
                    val trackKey = ImportedTrackKey(
                        artistRaw = row.artistRaw,
                        artists = splitArtists,
                        title = row.title,
                        album = row.album,
                        match = matchResult,
                        matchedDurationMs = matchedItem?.lengthMs,
                    )
                    val tIdx = trackIndexByKey.getOrPut(trackKey) {
                        tracks += trackKey
                        tracks.size - 1
                    }

                    ensureCapacity(size + 1)
                    trackIdxArr[size] = tIdx
                    tsArr[size] = row.startMs
                    listenedArr[size] = finalListenedMs
                    estArr[size] = isEstimated
                    srcListenedArr[size] = row.sourceListenedMs ?: -1L
                    dedupHiArr[size] = dk.hi
                    dedupLoArr[size] = dk.lo
                    size++

                    if (row.startMs < minTs) minTs = row.startMs
                    if (row.startMs > maxTs) maxTs = row.startMs
                    totalListenedMs += finalListenedMs
                    if (isEstimated) {
                        estimatedPlays++
                        estimatedTimeMs += finalListenedMs
                    }
                    if (finalListenedMs == 0L) {
                        zeroDurationPlays++
                    }

                    when (matchResult.confidence) {
                        MatchConfidence.EXACT -> matchedExact++
                        MatchConfidence.FOLDED -> matchedFolded++
                        MatchConfidence.PARTIAL -> matchedPartial++
                        MatchConfidence.NONE -> {
                            unmatched++
                            val primaryArtist = splitArtists.firstOrNull()?.takeIf { it.isNotBlank() } ?: row.artistRaw.trim()
                            if (primaryArtist.isNotEmpty()) {
                                val ak = LibraryTrackMatcher.foldBase(primaryArtist).ifEmpty { primaryArtist.lowercase() }
                                unmatchedArtistCounts[ak] = (unmatchedArtistCounts[ak] ?: 0) + 1
                                unmatchedArtistDisplay.putIfAbsent(ak, primaryArtist)
                            }
                        }
                    }
                }
            }
        }

        suspend fun processSingleStream(
            fmt: DetectedFileFormat,
            name: String,
            stream: InputStream,
            fileIdx: Int,
            filesTotal: Int,
        ) {
            when (fmt) {
                DetectedFileFormat.SPOTIFY_EXTENDED_JSON,
                DetectedFileFormat.SPOTIFY_ACCOUNT_JSON,
                DetectedFileFormat.YOUTUBE_MUSIC_JSON,
                DetectedFileFormat.POLYLUTE_HISTORY_JSON,
                DetectedFileFormat.POLYLUTE_HISTORY_CSV,
                DetectedFileFormat.CSV -> {
                    hasHistorySource = true
                    if (nativePlays == null &&
                        (fmt == DetectedFileFormat.POLYLUTE_HISTORY_CSV || fmt == DetectedFileFormat.POLYLUTE_HISTORY_JSON)
                    ) {
                        nativePlays = NativeHistoryIndex.build(loadNativeEvents(), catalog)
                    }
                    val stats = readSingleHistoryFormat(
                        fmt = fmt,
                        name = name,
                        stream = stream,
                        csvMapping = csvMapping,
                        onRow = handleRow,
                        onReject = recordRejection,
                        onProgressRows = { localRows ->
                            onProgress(ImportProgress(fileIdx, filesTotal, rowsRead + localRows, name))
                        },
                    )
                    rowsRead += stats.rowsRead
                    podcastsSkipped += stats.podcastsSkipped
                    nonMusicSkipped += stats.nonMusicSkipped
                    rejectedCount += stats.rejectedCount
                    fileSummaries += ImportFileSummary(
                        fileName = name,
                        dataKind = when (fmt) {
                            DetectedFileFormat.SPOTIFY_EXTENDED_JSON -> ImportFileDataKind.SPOTIFY_EXTENDED_HISTORY
                            DetectedFileFormat.SPOTIFY_ACCOUNT_JSON -> ImportFileDataKind.SPOTIFY_ACCOUNT_HISTORY
                            DetectedFileFormat.YOUTUBE_MUSIC_JSON -> ImportFileDataKind.YOUTUBE_MUSIC_HISTORY
                            DetectedFileFormat.POLYLUTE_HISTORY_JSON,
                            DetectedFileFormat.POLYLUTE_HISTORY_CSV -> ImportFileDataKind.POLYLUTE_HISTORY
                            else -> ImportFileDataKind.CSV_HISTORY
                        },
                    )
                }
                DetectedFileFormat.SPOTIFY_PLAYLISTS_JSON -> {
                    if (detectedPrimarySource == null) detectedPrimarySource = ImportSourceKind.SPOTIFY_ACCOUNT
                    var currentName = "Spotify Playlist"
                    var playlistOrdinal = 0
                    val currentItems = ArrayList<RawTrackItem>()
                    SpotifyPlaylistAndLikesReader.readPlaylists(
                        input = stream,
                        fileName = name,
                        onPlaylistStart = { plName ->
                            currentName = plName
                            currentItems.clear()
                        },
                        onPlaylistTrack = { item -> currentItems += item },
                        onPlaylistEnd = { epSkipped, locSkipped ->
                            recordPlaylist(
                                name = currentName,
                                items = currentItems.toList(),
                                targetId = stableImportPlaylistId(currentSourceHash, name, playlistOrdinal++),
                                episodesSkipped = epSkipped,
                                localTracksSkipped = locSkipped,
                            )
                            currentItems.clear()
                        },
                    )
                    fileSummaries += ImportFileSummary(name, ImportFileDataKind.SPOTIFY_PLAYLISTS)
                }
                DetectedFileFormat.SPOTIFY_LIBRARY_JSON -> {
                    if (detectedPrimarySource == null) detectedPrimarySource = ImportSourceKind.SPOTIFY_ACCOUNT
                    SpotifyPlaylistAndLikesReader.readYourLibraryLikes(stream, name, ::handleLike)
                    fileSummaries += ImportFileSummary(name, ImportFileDataKind.SPOTIFY_LIKES)
                }
                DetectedFileFormat.M3U -> {
                    val matched = M3uManager.parseM3u(stream, name, catalog, matcher)
                    val matchedItems = matched.items.mapNotNull { m ->
                        m.matched?.let { item ->
                            ImportedTrackRef(
                                songId = item.locator,
                                title = item.title,
                                artist = item.artistLine,
                                album = item.album,
                                durationMs = item.lengthMs,
                            )
                        }
                    }
                    val unmatchedItems = matched.items.mapNotNull { m ->
                        if (m.matched != null) null
                        else {
                            val e = m.entry
                            UnmatchedTrackRef(
                                title = e.title ?: e.rawTitle ?: e.path.substringAfterLast('/'),
                                artist = e.artist.orEmpty(),
                            )
                        }
                    }
                    if (matchedItems.isEmpty()) {
                        if (matched.items.isNotEmpty()) hiddenZeroMatchPlaylists++
                    } else {
                        playlists += ImportPlaylistPreview(
                            targetId = stableImportPlaylistId(currentSourceHash, name, 0),
                            name = matched.name,
                            totalItems = matched.items.size,
                            matchedTracks = matchedItems,
                            unmatchedTracks = unmatchedItems,
                            included = true,
                        )
                    }
                    fileSummaries += ImportFileSummary(name, ImportFileDataKind.M3U_PLAYLIST)
                }
                DetectedFileFormat.POLYLUTE_LIKES_CSV -> {
                    if (detectedPrimarySource == null) detectedPrimarySource = ImportSourceKind.POLYLUTE_CSV
                    PolyluteExportReaders.readLikesCsv(stream, ::handleLike)
                    fileSummaries += ImportFileSummary(name, ImportFileDataKind.POLYLUTE_LIKES)
                }
                DetectedFileFormat.POLYLUTE_PLAYLISTS_CSV -> {
                    if (detectedPrimarySource == null) detectedPrimarySource = ImportSourceKind.POLYLUTE_CSV
                    val grouped = PolyluteExportReaders.readPlaylistsCsv(stream)
                    grouped.entries.forEachIndexed { ordinal, (plName, items) ->
                        recordPlaylist(plName, items, targetId = stableImportPlaylistId(currentSourceHash, name, ordinal))
                    }
                    fileSummaries += ImportFileSummary(name, ImportFileDataKind.POLYLUTE_PLAYLISTS)
                }
                DetectedFileFormat.YOUTUBE_VIDEO_ID_ONLY -> {
                    fileSummaries += ImportFileSummary(
                        fileName = name,
                        dataKind = ImportFileDataKind.SKIPPED,
                        skipReason = ImportFileSkipReason.YOUTUBE_VIDEO_ID_ONLY,
                    )
                }
                DetectedFileFormat.SKIPPED_METADATA -> {
                    fileSummaries += ImportFileSummary(
                        fileName = name,
                        dataKind = ImportFileDataKind.SKIPPED,
                        skipReason = ImportFileSkipReason.NON_MUSIC_METADATA,
                    )
                }
                DetectedFileFormat.UNKNOWN -> {
                    fileSummaries += ImportFileSummary(
                        fileName = name,
                        dataKind = ImportFileDataKind.SKIPPED,
                        skipReason = ImportFileSkipReason.UNSUPPORTED_FORMAT,
                    )
                }
                DetectedFileFormat.YOUTUBE_HTML -> throw YouTubeHtmlFormatException()
                DetectedFileFormat.MUSICOLET_BACKUP -> throw MusicoletUnsupportedException(listOf(name))
                DetectedFileFormat.ZIP -> Unit
            }
        }

        val coroutineCtx = currentCoroutineContext()
        val filesTotal = sources.size
        sources.forEachIndexed { fileIdx, src ->
            coroutineCtx.ensureActive()
            onProgress(ImportProgress(fileIdx, filesTotal, rowsRead, src.displayName))
            if (ImportFormatDetector.isMusicoletFileName(src.displayName)) {
                throw MusicoletUnsupportedException(listOf(src.displayName))
            }
            val fileHash = src.openStream().use { sha256Stream(it) }
            currentSourceHash = fileHash
            if (!seenFileHashes.add(fileHash)) {
                fileSummaries += ImportFileSummary(
                    fileName = src.displayName,
                    dataKind = ImportFileDataKind.SKIPPED,
                    skipReason = ImportFileSkipReason.DUPLICATE_FILE,
                )
                onProgress(ImportProgress(fileIdx + 1, filesTotal, rowsRead, src.displayName))
                return@forEachIndexed
            }
            readFileNames += src.displayName
            val fmt = src.openStream().use { ImportFormatDetector.detectStreamFormat(src.displayName, it) }
            when (fmt) {
                DetectedFileFormat.MUSICOLET_BACKUP -> throw MusicoletUnsupportedException(listOf(src.displayName))
                DetectedFileFormat.YOUTUBE_HTML -> throw YouTubeHtmlFormatException()
                DetectedFileFormat.ZIP -> {
                    val zipEntries = ImportFormatDetector.scanZipEntries(src.openStream)
                    if (zipEntries.isEmpty()) {
                        throw UnrecognizedImportFormatException("No supported files inside ${src.displayName}")
                    }
                    val formatByEntry = zipEntries.toMap()
                    ZipInputStream(BufferedInputStream(src.openStream(), 64 * 1024)).use { zip ->
                        var entry: ZipEntry?
                        while (zip.nextEntry.also { entry = it } != null) {
                            coroutineCtx.ensureActive()
                            val entryName = entry!!.name
                            val innerFmt = formatByEntry[entryName]
                            if (innerFmt != null) {
                                processSingleStream(
                                    fmt = innerFmt,
                                    name = entryName,
                                    stream = NonClosingInputStream(zip),
                                    fileIdx = fileIdx,
                                    filesTotal = filesTotal,
                                )
                            }
                            zip.closeEntry()
                        }
                    }
                }
                else -> {
                    src.openStream().use { stream ->
                        processSingleStream(
                            fmt = fmt,
                            name = src.displayName,
                            stream = stream,
                            fileIdx = fileIdx,
                            filesTotal = filesTotal,
                        )
                    }
                }
            }
            onProgress(ImportProgress(fileIdx + 1, filesTotal, rowsRead, src.displayName))
        }

        val sourceKind = detectedPrimarySource ?: when {
            csvMapping != null -> ImportSourceKind.CSV
            else -> ImportSourceKind.SPOTIFY_EXTENDED
        }

        val topUnmatched = unmatchedArtistCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { unmatchedArtistDisplay[it.key].orEmpty().lowercase() })
            .take(TOP_UNMATCHED_ARTISTS_LIMIT)
            .map { UnmatchedArtistCount(unmatchedArtistDisplay.getValue(it.key), it.value) }

        val counts = ImportCounts(
            rowsRead = rowsRead,
            plays = size,
            skips = skips,
            podcastsSkipped = podcastsSkipped,
            nonMusicSkipped = nonMusicSkipped,
            duplicatesSkipped = duplicatesSkipped,
            rejectedCount = rejectedCount,
            matchedExact = matchedExact,
            matchedFolded = matchedFolded,
            matchedPartial = matchedPartial,
            unmatched = unmatched,
            estimatedPlays = estimatedPlays,
            estimatedTimeMs = estimatedTimeMs,
            zeroDurationPlays = zeroDurationPlays,
            totalListenedMs = totalListenedMs,
        )

        val fileNames = readFileNames.toList()
        val startRange = if (size > 0) minTs else null
        val endRange = if (size > 0) maxTs else null

        val prepared = PreparedImport(
            sourceKind = sourceKind,
            fileNames = fileNames,
            tracks = tracks,
            trackIndices = trackIdxArr.copyOf(size),
            timestampsMs = tsArr.copyOf(size),
            listenedMs = listenedArr.copyOf(size),
            estimated = estArr.copyOf(size),
            sourceListenedMs = srcListenedArr.copyOf(size),
            dedupHi = dedupHiArr.copyOf(size),
            dedupLo = dedupLoArr.copyOf(size),
            dateRangeStartMs = startRange,
            dateRangeEndMs = endRange,
            counts = counts,
            topUnmatchedArtists = topUnmatched,
            rejectionsSample = rejectionsSample,
        )

        val alreadyFavCount = matchedLikes.count { it.songId in existingFavoriteSongIds }

        return ImportPreview(
            sourceKind = sourceKind,
            fileNames = fileNames,
            dateRangeStartMs = startRange,
            dateRangeEndMs = endRange,
            counts = counts,
            topUnmatchedArtists = topUnmatched,
            rejectionsSample = rejectionsSample,
            hasDurationData = hasDurationData,
            csvMapping = csvMapping,
            prepared = prepared,
            hasHistory = hasHistorySource,
            fileSummaries = fileSummaries,
            likes = ImportLikesPreview(
                totalRead = likesReadCount,
                matchedTracks = matchedLikes,
                alreadyFavoriteCount = alreadyFavCount,
                unmatchedTracks = unmatchedLikes,
            ),
            playlists = playlists,
            hiddenZeroMatchPlaylists = hiddenZeroMatchPlaylists,
            playlistEpisodesSkipped = playlistEpisodesSkipped,
            playlistLocalTracksSkipped = playlistLocalTracksSkipped,
        )
    }

    private fun readSingleHistoryFormat(
        fmt: DetectedFileFormat,
        name: String,
        stream: InputStream,
        csvMapping: CsvColumnMapping?,
        onRow: (RawImportedRow) -> Unit,
        onReject: (ImportRejection) -> Unit,
        onProgressRows: (Int) -> Unit,
    ): ReaderStats = when (fmt) {
        DetectedFileFormat.SPOTIFY_EXTENDED_JSON ->
            SpotifyHistoryReader.readExtended(stream, name, onRow, onReject, onProgressRows)
        DetectedFileFormat.SPOTIFY_ACCOUNT_JSON ->
            SpotifyHistoryReader.readAccountData(stream, name, onRow, onReject, onProgressRows)
        DetectedFileFormat.YOUTUBE_MUSIC_JSON ->
            TakeoutHistoryReader.readWatchHistory(stream, name, onRow, onReject, onProgressRows)
        DetectedFileFormat.POLYLUTE_HISTORY_JSON ->
            PolyluteExportReaders.readHistoryJson(stream, name, onRow, onReject, onProgressRows)
        DetectedFileFormat.POLYLUTE_HISTORY_CSV ->
            PolyluteExportReaders.readHistoryCsv(stream, name, onRow, onReject, onProgressRows)
        DetectedFileFormat.YOUTUBE_HTML -> throw YouTubeHtmlFormatException()
        DetectedFileFormat.CSV -> {
            val buffered = if (stream is BufferedInputStream) stream else BufferedInputStream(stream, 64 * 1024)
            val mapping = csvMapping ?: run {
                buffered.mark(64 * 1024)
                val detected = CsvHistoryReader.inspect(NonClosingInputStream(buffered))
                buffered.reset()
                detected
            }
            CsvHistoryReader.read(buffered, name, mapping, onRow, onReject, onProgressRows)
        }
        DetectedFileFormat.MUSICOLET_BACKUP -> throw MusicoletUnsupportedException(listOf(name))
        else -> throw UnrecognizedImportFormatException("Unsupported file format: $name")
    }
}
