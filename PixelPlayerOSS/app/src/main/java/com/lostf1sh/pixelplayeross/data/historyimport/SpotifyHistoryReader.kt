package com.lostf1sh.pixelplayeross.data.historyimport

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.io.InputStreamReader

internal data class RawImportedRow(
    val startMs: Long,
    val artistRaw: String,
    val title: String,
    val album: String? = null,
    val sourceListenedMs: Long? = null,
    val sourceKind: ImportSourceKind,
    val sourceRowRef: String,
    val externalUri: String? = null,
    val endMs: Long = startMs + (sourceListenedMs ?: 0L),
    val skipped: Boolean? = null,
    val reasonEnd: String? = null,
    val trackDurationMs: Long? = null,
    val path: String? = null,
)

internal data class ReaderStats(
    var rowsRead: Int = 0,
    var podcastsSkipped: Int = 0,
    var nonMusicSkipped: Int = 0,
    var rejectedCount: Int = 0,
)

/**
 * Streaming readers for Spotify exports:
 *  - Extended streaming history (`Streaming_History_Audio_*.json`)
 *  - Account data short history (`StreamingHistory_music_*.json`)
 *
 * Account fields (`ip_addr`, `conn_country`, `platform`, `user_agent_decrypted`, `username`, etc.)
 * are skipped immediately in the stream and never retained in memory or stored.
 */
internal object SpotifyHistoryReader {

    private const val BUFFER_SIZE = 64 * 1024

    fun readExtended(
        input: InputStream,
        fileName: String,
        onRow: (RawImportedRow) -> Unit,
        onReject: (ImportRejection) -> Unit,
        onProgressRows: (Int) -> Unit = {},
    ): ReaderStats {
        val stats = ReaderStats()
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8).buffered(BUFFER_SIZE))
        reader.use {
            if (it.peek() != JsonToken.BEGIN_ARRAY) {
                throw UnrecognizedImportFormatException("Expected JSON array in $fileName")
            }
            it.beginArray()
            var index = 0
            while (it.hasNext()) {
                index++
                stats.rowsRead++
                val rowRef = "$fileName#$index"
                if (it.peek() != JsonToken.BEGIN_OBJECT) {
                    it.skipValue()
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Not a JSON object"))
                    continue
                }
                var ts: String? = null
                var msPlayed: Long? = null
                var trackName: String? = null
                var artistName: String? = null
                var albumName: String? = null
                var trackUri: String? = null
                var episodeName: String? = null
                var audiobookTitle: String? = null
                var skipped: Boolean? = null
                var reasonEnd: String? = null

                it.beginObject()
                while (it.hasNext()) {
                    when (it.nextName()) {
                        "ts" -> ts = it.stringOrNull()
                        "ms_played" -> msPlayed = it.longOrNull()
                        "master_metadata_track_name" -> trackName = it.stringOrNull()
                        "master_metadata_album_artist_name" -> artistName = it.stringOrNull()
                        "master_metadata_album_album_name" -> albumName = it.stringOrNull()
                        "spotify_track_uri" -> trackUri = it.stringOrNull()
                        "episode_name" -> episodeName = it.stringOrNull()
                        "audiobook_title" -> audiobookTitle = it.stringOrNull()
                        "skipped" -> skipped = it.booleanOrNull()
                        "reason_end" -> reasonEnd = it.stringOrNull()
                        // Deliberately ignore ip_addr, conn_country, platform, user_agent_decrypted, etc.
                        else -> it.skipValue()
                    }
                }
                it.endObject()

                if (!episodeName.isNullOrBlank() || !audiobookTitle.isNullOrBlank() || trackName.isNullOrBlank()) {
                    stats.podcastsSkipped++
                    continue
                }

                val endMs = ImportDateParser.parseIsoUtc(ts)
                if (endMs == null) {
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Invalid or missing timestamp"))
                    continue
                }
                val safeMsPlayed = msPlayed?.coerceAtLeast(0L)
                if (safeMsPlayed == null) {
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Missing ms_played"))
                    continue
                }
                val artist = artistName?.trim().orEmpty()
                if (artist.isEmpty()) {
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Missing artist name"))
                    continue
                }

                val startMs = (endMs - safeMsPlayed).coerceAtLeast(0L)
                onRow(
                    RawImportedRow(
                        startMs = startMs,
                        artistRaw = artist,
                        title = trackName.trim(),
                        album = albumName?.trim()?.takeIf { s -> s.isNotEmpty() },
                        sourceListenedMs = safeMsPlayed,
                        sourceKind = ImportSourceKind.SPOTIFY_EXTENDED,
                        sourceRowRef = rowRef,
                        externalUri = trackUri?.trim()?.takeIf { s -> s.isNotEmpty() },
                        endMs = endMs,
                        skipped = skipped,
                        reasonEnd = reasonEnd?.trim()?.takeIf { s -> s.isNotEmpty() },
                    ),
                )
                if (index % 2_000 == 0) onProgressRows(index)
            }
            it.endArray()
        }
        return stats
    }

    /**
     * Reads Spotify "Account data" short history (`StreamingHistory_music_*.json`).
     * `endTime` is UTC with minute precision; start timestamp is derived as `(endUtcMs - msPlayed).coerceAtLeast(0L)`.
     */
    fun readAccountData(
        input: InputStream,
        fileName: String,
        onRow: (RawImportedRow) -> Unit,
        onReject: (ImportRejection) -> Unit,
        onProgressRows: (Int) -> Unit = {},
    ): ReaderStats {
        val stats = ReaderStats()
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8).buffered(BUFFER_SIZE))
        reader.use {
            if (it.peek() != JsonToken.BEGIN_ARRAY) {
                throw UnrecognizedImportFormatException("Expected JSON array in $fileName")
            }
            it.beginArray()
            var index = 0
            while (it.hasNext()) {
                index++
                stats.rowsRead++
                val rowRef = "$fileName#$index"
                if (it.peek() != JsonToken.BEGIN_OBJECT) {
                    it.skipValue()
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Not a JSON object"))
                    continue
                }
                var endTime: String? = null
                var artistName: String? = null
                var trackName: String? = null
                var msPlayed: Long? = null

                it.beginObject()
                while (it.hasNext()) {
                    when (it.nextName()) {
                        "endTime" -> endTime = it.stringOrNull()
                        "artistName" -> artistName = it.stringOrNull()
                        "trackName" -> trackName = it.stringOrNull()
                        "msPlayed" -> msPlayed = it.longOrNull()
                        else -> it.skipValue()
                    }
                }
                it.endObject()

                val endMs = ImportDateParser.parseSpotifyAccountEndTime(endTime)
                if (endMs == null) {
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Invalid or missing endTime"))
                    continue
                }
                val safeMsPlayed = msPlayed?.coerceAtLeast(0L)
                if (safeMsPlayed == null) {
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Missing msPlayed"))
                    continue
                }
                val title = trackName?.trim().orEmpty()
                val artist = artistName?.trim().orEmpty()
                if (title.isEmpty() || artist.isEmpty()) {
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Missing trackName or artistName"))
                    continue
                }

                val startMs = (endMs - safeMsPlayed).coerceAtLeast(0L)
                onRow(
                    RawImportedRow(
                        startMs = startMs,
                        artistRaw = artist,
                        title = title,
                        album = null,
                        sourceListenedMs = safeMsPlayed,
                        sourceKind = ImportSourceKind.SPOTIFY_ACCOUNT,
                        sourceRowRef = rowRef,
                        endMs = endMs,
                    ),
                )
                if (index % 2_000 == 0) onProgressRows(index)
            }
            it.endArray()
        }
        return stats
    }
}

internal fun JsonReader.stringOrNull(): String? = when (peek()) {
    JsonToken.NULL -> {
        nextNull()
        null
    }
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    else -> {
        skipValue()
        null
    }
}

internal fun JsonReader.longOrNull(): Long? = when (peek()) {
    JsonToken.NULL -> {
        nextNull()
        null
    }
    JsonToken.NUMBER -> {
        val raw = nextString()
        raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
    }
    JsonToken.STRING -> nextString().toLongOrNull()
    else -> {
        skipValue()
        null
    }
}

internal fun JsonReader.booleanOrNull(): Boolean? = when (peek()) {
    JsonToken.NULL -> {
        nextNull()
        null
    }
    JsonToken.BOOLEAN -> nextBoolean()
    JsonToken.STRING -> nextString().trim().lowercase().let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
    else -> {
        skipValue()
        null
    }
}
