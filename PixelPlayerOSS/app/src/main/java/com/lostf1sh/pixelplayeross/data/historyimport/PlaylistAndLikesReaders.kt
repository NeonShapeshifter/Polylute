package com.lostf1sh.pixelplayeross.data.historyimport

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

internal data class RawTrackItem(
    val artist: String,
    val title: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val path: String? = null,
)

internal data class SpotifyPlaylistReadStats(
    val playlistsRead: Int,
    val episodesSkipped: Int,
    val localTracksSkipped: Int,
)

/**
 * Streaming readers for Spotify Account Data (`Playlist*.json`, `YourLibrary.json`)
 * and Polylute's own universal export formats (history JSON/CSV, likes CSV, playlists CSV).
 */
internal object SpotifyPlaylistAndLikesReader {

    private const val BUFFER_SIZE = 64 * 1024

    fun readPlaylists(
        input: InputStream,
        fileName: String,
        onPlaylistStart: (name: String) -> Unit,
        onPlaylistTrack: (RawTrackItem) -> Unit,
        onPlaylistEnd: (episodesSkipped: Int, localTracksSkipped: Int) -> Unit,
    ): SpotifyPlaylistReadStats {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8).buffered(BUFFER_SIZE))
        var playlistsRead = 0
        var totalEpisodes = 0
        var totalLocal = 0

        reader.use { r ->
            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                throw UnrecognizedImportFormatException("Expected JSON object in $fileName")
            }
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "playlists" -> {
                        if (r.peek() != JsonToken.BEGIN_ARRAY) {
                            r.skipValue()
                            continue
                        }
                        r.beginArray()
                        while (r.hasNext()) {
                            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                                r.skipValue()
                                continue
                            }
                            playlistsRead++
                            var playlistName = "Spotify Playlist"
                            var episodesSkipped = 0
                            var localTracksSkipped = 0
                            var pendingItems: ArrayList<RawTrackItem>? = null

                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "name" -> {
                                        playlistName = r.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: playlistName
                                    }
                                    "items" -> {
                                        if (r.peek() != JsonToken.BEGIN_ARRAY) {
                                            r.skipValue()
                                            continue
                                        }
                                        r.beginArray()
                                        val pending = ArrayList<RawTrackItem>()
                                        while (r.hasNext()) {
                                            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                                                r.skipValue()
                                                continue
                                            }
                                            when (val item = readPlaylistItemObject(r)) {
                                                PlaylistItemKind.Episode -> episodesSkipped++
                                                PlaylistItemKind.LocalTrack -> localTracksSkipped++
                                                is PlaylistItemKind.Track -> pending += item.track
                                                PlaylistItemKind.Other -> Unit
                                            }
                                        }
                                        r.endArray()
                                        pendingItems = pending
                                    }
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()

                            onPlaylistStart(playlistName)
                            pendingItems?.forEach(onPlaylistTrack)
                            onPlaylistEnd(episodesSkipped, localTracksSkipped)
                            totalEpisodes += episodesSkipped
                            totalLocal += localTracksSkipped
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        return SpotifyPlaylistReadStats(playlistsRead, totalEpisodes, totalLocal)
    }

    private sealed interface PlaylistItemKind {
        data object Episode : PlaylistItemKind
        data object LocalTrack : PlaylistItemKind
        data class Track(val track: RawTrackItem) : PlaylistItemKind
        data object Other : PlaylistItemKind
    }

    private fun readPlaylistItemObject(r: JsonReader): PlaylistItemKind {
        var track: RawTrackItem? = null
        var hasEpisode = false
        var hasLocal = false

        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "track" -> {
                    if (r.peek() == JsonToken.BEGIN_OBJECT) {
                        track = readTrackSubObject(r)
                    } else {
                        r.skipValue()
                    }
                }
                "episode" -> {
                    if (r.peek() != JsonToken.NULL) hasEpisode = true
                    r.skipValue()
                }
                "localTrack" -> {
                    if (r.peek() != JsonToken.NULL) hasLocal = true
                    r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()

        return when {
            hasEpisode -> PlaylistItemKind.Episode
            hasLocal -> PlaylistItemKind.LocalTrack
            track != null -> PlaylistItemKind.Track(track)
            else -> PlaylistItemKind.Other
        }
    }

    private fun readTrackSubObject(r: JsonReader): RawTrackItem? {
        var trackName: String? = null
        var artistName: String? = null
        var albumName: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "trackName" -> trackName = r.stringOrNull()
                "artistName" -> artistName = r.stringOrNull()
                "albumName" -> albumName = r.stringOrNull()
                else -> r.skipValue()
            }
        }
        r.endObject()
        val title = trackName?.trim().orEmpty()
        val artist = artistName?.trim().orEmpty()
        if (title.isEmpty() || artist.isEmpty()) return null
        return RawTrackItem(
            artist = artist,
            title = title,
            album = albumName?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    fun readYourLibraryLikes(
        input: InputStream,
        fileName: String,
        onLike: (RawTrackItem) -> Unit,
    ): Int {
        var count = 0
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8).buffered(BUFFER_SIZE))
        reader.use { r ->
            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                throw UnrecognizedImportFormatException("Expected JSON object in $fileName")
            }
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "tracks" -> {
                        if (r.peek() != JsonToken.BEGIN_ARRAY) {
                            r.skipValue()
                            continue
                        }
                        r.beginArray()
                        while (r.hasNext()) {
                            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                                r.skipValue()
                                continue
                            }
                            var artist: String? = null
                            var track: String? = null
                            var album: String? = null
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "artist" -> artist = r.stringOrNull()
                                    "track" -> track = r.stringOrNull()
                                    "album" -> album = r.stringOrNull()
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                            val cleanTitle = track?.trim().orEmpty()
                            val cleanArtist = artist?.trim().orEmpty()
                            if (cleanTitle.isNotEmpty() && cleanArtist.isNotEmpty()) {
                                count++
                                onLike(
                                    RawTrackItem(
                                        artist = cleanArtist,
                                        title = cleanTitle,
                                        album = album?.trim()?.takeIf { it.isNotEmpty() },
                                    ),
                                )
                            }
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        return count
    }
}

internal object PolyluteExportReaders {

    private const val BUFFER_SIZE = 64 * 1024

    fun readHistoryJson(
        input: InputStream,
        fileName: String,
        onRow: (RawImportedRow) -> Unit,
        onReject: (ImportRejection) -> Unit,
        onProgressRows: (Int) -> Unit = {},
    ): ReaderStats {
        val stats = ReaderStats()
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8).buffered(BUFFER_SIZE))
        reader.use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) {
                throw UnrecognizedImportFormatException("Expected JSON array in $fileName")
            }
            r.beginArray()
            var index = 0
            while (r.hasNext()) {
                index++
                stats.rowsRead++
                val rowRef = "$fileName#$index"
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Not a JSON object"))
                    continue
                }
                var playedAt: String? = null
                var artist: String? = null
                var title: String? = null
                var album: String? = null
                var msPlayed: Long? = null
                var durationMs: Long? = null
                var path: String? = null

                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "played_at" -> playedAt = r.stringOrNull()
                        "artist" -> artist = r.stringOrNull()
                        "title" -> title = r.stringOrNull()
                        "album" -> album = r.stringOrNull()
                        "ms_played" -> msPlayed = r.longOrNull()
                        "duration_ms" -> durationMs = r.longOrNull()
                        "path" -> path = r.stringOrNull()
                        else -> r.skipValue()
                    }
                }
                r.endObject()

                val tsMs = ImportDateParser.parseIsoUtc(playedAt)
                if (tsMs == null) {
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Invalid or missing played_at"))
                    continue
                }
                val cleanArtist = artist?.trim().orEmpty()
                val cleanTitle = title?.trim().orEmpty()
                if (cleanTitle.isEmpty()) {
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Missing title"))
                    continue
                }

                onRow(
                    RawImportedRow(
                        startMs = tsMs,
                        artistRaw = cleanArtist,
                        title = cleanTitle,
                        album = album?.trim()?.takeIf { it.isNotEmpty() },
                        sourceListenedMs = msPlayed?.coerceAtLeast(0L),
                        sourceKind = ImportSourceKind.POLYLUTE_JSON,
                        sourceRowRef = rowRef,
                        trackDurationMs = durationMs?.takeIf { it > 0L },
                        path = path?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                )
                if (index % 2_000 == 0) onProgressRows(index)
            }
            r.endArray()
        }
        return stats
    }

    fun readHistoryCsv(
        input: InputStream,
        fileName: String,
        onRow: (RawImportedRow) -> Unit,
        onReject: (ImportRejection) -> Unit,
        onProgressRows: (Int) -> Unit = {},
    ): ReaderStats {
        val stats = ReaderStats()
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER_SIZE)
        var colIndex: Map<String, Int>? = null
        var rowIndex = 0
        CsvHistoryReader.forEachCsvRow(reader, ',') { cells ->
            if (cells.all { it.isBlank() }) return@forEachCsvRow
            val cols = colIndex
            if (cols == null) {
                colIndex = cells.withIndex().associate { (i, h) -> CsvHistoryReader.foldHeader(h) to i }
                return@forEachCsvRow
            }
            rowIndex++
            stats.rowsRead++
            val rowRef = "$fileName:$rowIndex"
            val playedAtRaw = cols["played_at"]?.let { cells.getOrNull(it)?.trim() }
            val tsMs = ImportDateParser.parseFlexibleUtc(playedAtRaw)
            if (tsMs == null) {
                stats.rejectedCount++
                onReject(ImportRejection(rowRef, "Invalid or missing played_at"))
                return@forEachCsvRow
            }
            val artist = cols["artist"]?.let { cells.getOrNull(it)?.trim() }.orEmpty()
            val title = cols["title"]?.let { cells.getOrNull(it)?.trim() }.orEmpty()
            if (title.isEmpty()) {
                stats.rejectedCount++
                onReject(ImportRejection(rowRef, "Missing title"))
                return@forEachCsvRow
            }
            val album = cols["album"]?.let { cells.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }
            val msPlayed = cols["ms_played"]?.let { cells.getOrNull(it)?.trim()?.toLongOrNull()?.coerceAtLeast(0L) }
            val durationMs = cols["duration_ms"]?.let { cells.getOrNull(it)?.trim()?.toLongOrNull()?.takeIf { d -> d > 0L } }
            val path = cols["path"]?.let { cells.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }

            onRow(
                RawImportedRow(
                    startMs = tsMs,
                    artistRaw = artist,
                    title = title,
                    album = album,
                    sourceListenedMs = msPlayed,
                    sourceKind = ImportSourceKind.POLYLUTE_CSV,
                    sourceRowRef = rowRef,
                    trackDurationMs = durationMs,
                    path = path,
                ),
            )
            if (rowIndex % 2_000 == 0) onProgressRows(rowIndex)
        }
        return stats
    }

    fun readLikesCsv(
        input: InputStream,
        onLike: (RawTrackItem) -> Unit,
    ): Int {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER_SIZE)
        var cols: Map<String, Int>? = null
        var count = 0
        CsvHistoryReader.forEachCsvRow(reader, ',') { cells ->
            if (cells.all { it.isBlank() }) return@forEachCsvRow
            val map = cols
            if (map == null) {
                cols = cells.withIndex().associate { (i, h) -> CsvHistoryReader.foldHeader(h) to i }
                return@forEachCsvRow
            }
            val artist = map["artist"]?.let { cells.getOrNull(it)?.trim() }.orEmpty()
            val title = map["title"]?.let { cells.getOrNull(it)?.trim() }.orEmpty()
            if (artist.isEmpty() || title.isEmpty()) return@forEachCsvRow
            val album = map["album"]?.let { cells.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }
            val durationMs = map["duration_ms"]?.let { cells.getOrNull(it)?.trim()?.toLongOrNull()?.takeIf { d -> d > 0L } }
            val path = map["path"]?.let { cells.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }
            count++
            onLike(RawTrackItem(artist = artist, title = title, album = album, durationMs = durationMs, path = path))
        }
        return count
    }

    fun readPlaylistsCsv(input: InputStream): Map<String, List<RawTrackItem>> {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER_SIZE)
        var cols: Map<String, Int>? = null
        val grouped = LinkedHashMap<String, MutableList<Pair<Int, RawTrackItem>>>()
        var seq = 0
        CsvHistoryReader.forEachCsvRow(reader, ',') { cells ->
            if (cells.all { it.isBlank() }) return@forEachCsvRow
            val map = cols
            if (map == null) {
                cols = cells.withIndex().associate { (i, h) -> CsvHistoryReader.foldHeader(h) to i }
                return@forEachCsvRow
            }
            val playlist = map["playlist"]?.let { cells.getOrNull(it)?.trim() }.orEmpty()
            val artist = map["artist"]?.let { cells.getOrNull(it)?.trim() }.orEmpty()
            val title = map["title"]?.let { cells.getOrNull(it)?.trim() }.orEmpty()
            if (playlist.isEmpty() || artist.isEmpty() || title.isEmpty()) return@forEachCsvRow
            val pos = map["position"]?.let { cells.getOrNull(it)?.trim()?.toIntOrNull() } ?: seq
            seq++
            val album = map["album"]?.let { cells.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }
            val durationMs = map["duration_ms"]?.let { cells.getOrNull(it)?.trim()?.toLongOrNull()?.takeIf { d -> d > 0L } }
            val path = map["path"]?.let { cells.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }
            grouped.getOrPut(playlist) { ArrayList() } += pos to RawTrackItem(
                artist = artist,
                title = title,
                album = album,
                durationMs = durationMs,
                path = path,
            )
        }
        return grouped.mapValues { (_, entries) ->
            entries.sortedBy { it.first }.map { it.second }
        }
    }
}
