package com.lostf1sh.pixelplayeross.data.historyimport

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal enum class DetectedFileFormat {
    SPOTIFY_EXTENDED_JSON,
    SPOTIFY_ACCOUNT_JSON,
    SPOTIFY_PLAYLISTS_JSON,
    SPOTIFY_LIBRARY_JSON,
    YOUTUBE_MUSIC_JSON,
    YOUTUBE_HTML,
    YOUTUBE_VIDEO_ID_ONLY,
    POLYLUTE_HISTORY_JSON,
    POLYLUTE_HISTORY_CSV,
    POLYLUTE_LIKES_CSV,
    POLYLUTE_PLAYLISTS_CSV,
    M3U,
    CSV,
    ZIP,
    MUSICOLET_BACKUP,
    SKIPPED_METADATA,
    UNKNOWN,
}

/**
 * Pure format & Musicolet detector for picked files and ZIP archives.
 */
internal object ImportFormatDetector {

    private val MUSICOLET_NAME_PATTERNS = listOf(
        Regex("""(^|/)0\.musicolet\.backup$""", RegexOption.IGNORE_CASE),
        Regex("""(^|/)DB_SONGS_LOG$""", RegexOption.IGNORE_CASE),
        Regex("""(^|/)PCs_.*""", RegexOption.IGNORE_CASE),
        Regex("""\.musicolet\.backup$""", RegexOption.IGNORE_CASE),
    )

    fun isMusicoletFileName(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        return MUSICOLET_NAME_PATTERNS.any { it.containsMatchIn(normalized) }
    }

    fun detectStreamFormat(fileName: String, input: InputStream): DetectedFileFormat {
        if (isMusicoletFileName(fileName)) return DetectedFileFormat.MUSICOLET_BACKUP

        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 16 * 1024)
        buffered.mark(16 * 1024)
        val headerBytes = ByteArray(8192)
        val n = buffered.read(headerBytes)
        buffered.reset()

        if (n <= 0) return DetectedFileFormat.UNKNOWN

        // ZIP magic: PK\x03\x04 or PK\x05\x06
        if (n >= 4 && headerBytes[0] == 0x50.toByte() && headerBytes[1] == 0x4B.toByte() &&
            (headerBytes[2] == 0x03.toByte() || headerBytes[2] == 0x05.toByte())
        ) {
            return DetectedFileFormat.ZIP
        }

        val prefix = String(headerBytes, 0, n, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (prefix.startsWith("<")) {
            return DetectedFileFormat.YOUTUBE_HTML
        }

        val lowerName = fileName.lowercase(Locale.ROOT)
        if (lowerName.endsWith(".m3u") || lowerName.endsWith(".m3u8") ||
            prefix.startsWith("#EXTM3U", ignoreCase = true) ||
            prefix.startsWith("#EXTINF:", ignoreCase = true)
        ) {
            return DetectedFileFormat.M3U
        }

        if (prefix.startsWith("[")) {
            // Inspect keys of the first JSON object without loading the stream
            buffered.mark(16 * 1024)
            val jsonKind = runCatching { detectJsonArrayKind(buffered) }.getOrNull()
            buffered.reset()
            if (jsonKind != null) return jsonKind
        }

        if (prefix.startsWith("{")) {
            buffered.mark(16 * 1024)
            val objKind = runCatching { detectJsonObjectKind(buffered) }.getOrNull()
            buffered.reset()
            if (objKind != null) return objKind
        }

        if (lowerName.endsWith(".csv") || lowerName.endsWith(".tsv") || lowerName.endsWith(".txt")) {
            return detectCsvSubtype(prefix)
        }
        if (prefix.contains(',') || prefix.contains(';') || prefix.contains('\t')) {
            return detectCsvSubtype(prefix)
        }
        if (lowerName.endsWith(".json")) {
            return DetectedFileFormat.SKIPPED_METADATA
        }
        return DetectedFileFormat.UNKNOWN
    }

    private fun detectJsonArrayKind(input: InputStream): DetectedFileFormat? {
        // Do not close underlying stream here — caller resets BufferedInputStream.
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        if (reader.peek() != JsonToken.BEGIN_ARRAY) return null
        reader.beginArray()
        if (!reader.hasNext()) return DetectedFileFormat.SKIPPED_METADATA
        if (reader.peek() != JsonToken.BEGIN_OBJECT) return null
        val keys = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext() && keys.size < 20) {
            keys += reader.nextName()
            reader.skipValue()
        }
        return when {
            "master_metadata_track_name" in keys || ("ts" in keys && "ms_played" in keys) ->
                DetectedFileFormat.SPOTIFY_EXTENDED_JSON
            "endTime" in keys && "msPlayed" in keys ->
                DetectedFileFormat.SPOTIFY_ACCOUNT_JSON
            "played_at" in keys && "artist" in keys && "title" in keys ->
                DetectedFileFormat.POLYLUTE_HISTORY_JSON
            "header" in keys && "title" in keys ->
                DetectedFileFormat.YOUTUBE_MUSIC_JSON
            ("videoId" in keys || "video_id" in keys) && "title" !in keys && "artist" !in keys ->
                DetectedFileFormat.YOUTUBE_VIDEO_ID_ONLY
            else -> DetectedFileFormat.SKIPPED_METADATA
        }
    }

    private fun detectJsonObjectKind(input: InputStream): DetectedFileFormat? {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        if (reader.peek() != JsonToken.BEGIN_OBJECT) return null
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            val token = reader.peek()
            if (name == "playlists" && token == JsonToken.BEGIN_ARRAY) {
                return DetectedFileFormat.SPOTIFY_PLAYLISTS_JSON
            }
            if (name == "tracks" && token == JsonToken.BEGIN_ARRAY) {
                return DetectedFileFormat.SPOTIFY_LIBRARY_JSON
            }
            reader.skipValue()
        }
        return DetectedFileFormat.SKIPPED_METADATA
    }

    private fun detectCsvSubtype(prefix: String): DetectedFileFormat {
        val lines = prefix.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(5)
            .toList()
        if (lines.isEmpty()) return DetectedFileFormat.CSV
        val delimiter = CsvHistoryReader.detectDelimiter(lines)
        val firstCells = CsvHistoryReader.parseSingleLine(lines.first(), delimiter)
        val headers = firstCells.mapTo(HashSet()) { foldHeader(it) }

        val hasArtist = "artist" in headers
        val hasTitle = "title" in headers
        val hasPlayedAt = "played_at" in headers
        val hasPlaylist = "playlist" in headers

        if (hasPlaylist && hasArtist && hasTitle) {
            return DetectedFileFormat.POLYLUTE_PLAYLISTS_CSV
        }
        if (hasPlayedAt && hasArtist && hasTitle) {
            return DetectedFileFormat.POLYLUTE_HISTORY_CSV
        }
        if (hasArtist && hasTitle && ("duration_ms" in headers || "path" in headers) &&
            !hasPlayedAt && "date" !in headers && "timestamp" !in headers && !hasPlaylist
        ) {
            return DetectedFileFormat.POLYLUTE_LIKES_CSV
        }

        val hasVideoId = "video_id" in headers || "playlist_video_id" in headers
        val hasTakeoutPlaylistIndex = "playlist_id" in headers &&
            ("playlist_create_timestamp" in headers || "playlist_update_timestamp" in headers)
        if ((hasVideoId || hasTakeoutPlaylistIndex) && !hasTitle && !hasArtist &&
            "track" !in headers && "song_title" !in headers && "artist_names" !in headers
        ) {
            return DetectedFileFormat.YOUTUBE_VIDEO_ID_ONLY
        }
        return DetectedFileFormat.CSV
    }

    private fun foldHeader(raw: String): String {
        val nfd = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return Regex("\\p{M}+").replace(nfd, "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    /**
     * Scans a ZIP archive without extracting to disk. Detects Musicolet archives immediately,
     * and yields every recognized or explicitly skipped inner entry.
     */
    fun scanZipEntries(openZipStream: () -> InputStream): List<Pair<String, DetectedFileFormat>> {
        val detected = ArrayList<Pair<String, DetectedFileFormat>>()
        val musicoletHits = ArrayList<String>()
        ZipInputStream(BufferedInputStream(openZipStream(), 64 * 1024)).use { zip ->
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                val current = entry!!
                if (current.isDirectory) continue
                val entryName = current.name
                if (isMusicoletFileName(entryName)) {
                    musicoletHits += entryName
                    continue
                }
                val baseName = entryName.substringAfterLast('/')
                if (baseName.startsWith(".") || entryName.startsWith("__MACOSX") || entryName.contains("/__MACOSX/")) {
                    zip.closeEntry()
                    continue
                }
                val fmt = detectStreamFormat(entryName, NonClosingInputStream(zip))
                if (fmt == DetectedFileFormat.MUSICOLET_BACKUP) {
                    musicoletHits += entryName
                } else if (fmt != DetectedFileFormat.ZIP) {
                    detected += entryName to fmt
                }
                zip.closeEntry()
            }
        }
        if (musicoletHits.isNotEmpty()) {
            throw MusicoletUnsupportedException(musicoletHits)
        }
        return detected
    }
}

internal class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray): Int = delegate.read(b)
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun skip(n: Long): Long = delegate.skip(n)
    override fun available(): Int = delegate.available()
    override fun markSupported(): Boolean = false
    override fun close() {
        // Leave underlying ZipInputStream open across entry boundaries.
    }
}
