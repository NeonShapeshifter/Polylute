package com.lostf1sh.pixelplayeross.data.historyimport

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Streaming reader for Google Takeout YouTube Music history (`watch-history.json`).
 *
 * - Rejects HTML Takeout exports cleanly ([YouTubeHtmlFormatException]).
 * - Keeps only entries whose `header` is `"YouTube Music"`.
 * - Strips localised title prefixes for en, es, sv, de, fr, pt.
 * - Extracts artist from `subtitles[0].name`, stripping trailing `" - Topic"`.
 * - Emits plays with `sourceListenedMs = null` (duration is estimated from matched library tracks, or 0 ms when unmatched).
 */
internal object TakeoutHistoryReader {

    private const val BUFFER_SIZE = 64 * 1024
    private const val HEADER_YOUTUBE_MUSIC = "YouTube Music"

    /** Known Google Takeout watched-video prefixes across en, es, sv, de, fr, pt. */
    val KNOWN_TITLE_PREFIXES: List<String> = listOf(
        // English
        "Watched ",
        // Spanish
        "Has visto ",
        "Viste ",
        // Swedish
        "Tittade på ",
        "Har tittat på ",
        // German
        "Angesehen: ",
        "Video angesehen: ",
        "Du hast dir ",
        // French
        "Vous avez regardé ",
        // Portuguese
        "Você assistiu ",
        "Assistiu a ",
        "Assististe a ",
    )

    private val TOPIC_SUFFIX = Regex("""\s+[-–—]\s+Topic$""", RegexOption.IGNORE_CASE)

    fun stripTitlePrefix(rawTitle: String): String {
        val trimmed = rawTitle.trim()
        for (prefix in KNOWN_TITLE_PREFIXES) {
            if (trimmed.startsWith(prefix, ignoreCase = false)) {
                return trimmed.removePrefix(prefix).trim()
            }
        }
        for (prefix in KNOWN_TITLE_PREFIXES) {
            if (trimmed.startsWith(prefix, ignoreCase = true)) {
                return trimmed.substring(prefix.length).trim()
            }
        }
        return trimmed
    }

    fun stripTopicSuffix(rawArtist: String): String =
        TOPIC_SUFFIX.replace(rawArtist.trim(), "").trim()

    fun isHtmlContent(buffered: BufferedInputStream): Boolean {
        buffered.mark(256)
        val probe = ByteArray(256)
        val read = buffered.read(probe)
        buffered.reset()
        if (read <= 0) return false
        val text = String(probe, 0, read, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return text.startsWith("<")
    }

    fun readWatchHistory(
        input: InputStream,
        fileName: String,
        onRow: (RawImportedRow) -> Unit,
        onReject: (ImportRejection) -> Unit,
        onProgressRows: (Int) -> Unit = {},
    ): ReaderStats {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, BUFFER_SIZE)
        if (isHtmlContent(buffered)) {
            throw YouTubeHtmlFormatException()
        }

        val stats = ReaderStats()
        val reader = JsonReader(InputStreamReader(buffered, Charsets.UTF_8).buffered(BUFFER_SIZE))
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
                var header: String? = null
                var title: String? = null
                var titleUrl: String? = null
                var time: String? = null
                var firstSubtitleName: String? = null

                it.beginObject()
                while (it.hasNext()) {
                    when (it.nextName()) {
                        "header" -> header = it.stringOrNull()
                        "title" -> title = it.stringOrNull()
                        "titleUrl" -> titleUrl = it.stringOrNull()
                        "time" -> time = it.stringOrNull()
                        "subtitles" -> firstSubtitleName = readFirstSubtitleName(it)
                        else -> it.skipValue()
                    }
                }
                it.endObject()

                if (!header.equals(HEADER_YOUTUBE_MUSIC, ignoreCase = true)) {
                    stats.nonMusicSkipped++
                    continue
                }

                val timestampMs = ImportDateParser.parseIsoUtc(time)
                if (timestampMs == null) {
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Invalid or missing timestamp"))
                    continue
                }

                val cleanedTitle = title?.let(::stripTitlePrefix).orEmpty()
                if (cleanedTitle.isEmpty()) {
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Missing track title"))
                    continue
                }

                val artist = firstSubtitleName?.let(::stripTopicSuffix).orEmpty()
                if (artist.isEmpty()) {
                    stats.rejectedCount++
                    onReject(ImportRejection(rowRef, "Missing artist (no subtitles)"))
                    continue
                }

                onRow(
                    RawImportedRow(
                        startMs = timestampMs,
                        artistRaw = artist,
                        title = cleanedTitle,
                        album = null,
                        sourceListenedMs = null,
                        sourceKind = ImportSourceKind.YOUTUBE_MUSIC,
                        sourceRowRef = rowRef,
                        externalUri = titleUrl?.trim()?.takeIf { s -> s.isNotEmpty() },
                    ),
                )
                if (index % 2_000 == 0) onProgressRows(index)
            }
            it.endArray()
        }
        return stats
    }

    private fun readFirstSubtitleName(reader: JsonReader): String? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return null
        }
        var found: String? = null
        reader.beginArray()
        while (reader.hasNext()) {
            if (found == null && reader.peek() == JsonToken.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "name" -> found = reader.stringOrNull()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            } else {
                reader.skipValue()
            }
        }
        reader.endArray()
        return found
    }
}
