package com.lostf1sh.pixelplayeross.data.historyimport

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.Locale

/**
 * Streaming RFC-4180 CSV parser, delimiter/header detector, and column guesser (en / es / sv).
 */
internal object CsvHistoryReader {

    private const val BUFFER_SIZE = 64 * 1024
    private const val SAMPLE_LINES_LIMIT = 25
    private val CANDIDATE_DELIMITERS = listOf(',', ';', '\t', '|')

    private val DATE_HEADER_ALIASES = setOf(
        "date", "timestamp", "timestampms", "played_at", "playedat", "time", "datetime", "utc", "uts",
        "fecha", "hora", "fechahora", "marca_de_tiempo",
        "datum", "tid", "tidpunkt",
    )
    private val ARTIST_HEADER_ALIASES = setOf(
        "artist", "artistname", "artists", "track_artist", "album_artist", "performer",
        "artista", "artistas", "interprete",
        "artister",
    )
    private val TITLE_HEADER_ALIASES = setOf(
        "title", "track", "trackname", "song", "songname", "name",
        "titulo", "cancion", "pista", "tema",
        "titel", "lat", "spar",
    )
    private val ALBUM_HEADER_ALIASES = setOf(
        "album", "albumname", "release", "record",
        "disco",
    )
    private val DURATION_HEADER_ALIASES = setOf(
        "ms_played", "msplayed", "listened_ms", "duration_ms", "played_ms", "ms",
        "seconds", "secs", "duration_s", "duration_sec", "duration", "played_s",
        "duracion", "tiempo", "tiempo_escuchado",
        "tid_spelad", "langd", "sekunder",
    )
    private val SECONDS_HINTS = setOf("s", "sec", "secs", "second", "seconds", "duration_s", "duration_sec", "played_s", "sekunder", "segundos")

    /**
     * Reads only the first sample rows to detect delimiter, header presence, and default column mapping.
     */
    fun inspect(input: InputStream): CsvColumnMapping {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER_SIZE)
        val rawRecords = ArrayList<String>(SAMPLE_LINES_LIMIT)
        reader.use {
            var line: String?
            while (rawRecords.size < SAMPLE_LINES_LIMIT) {
                line = it.readLine() ?: break
                if (rawRecords.isEmpty()) line = line.removePrefix("\uFEFF")
                if (line.isNotBlank()) rawRecords += line
            }
        }
        if (rawRecords.isEmpty()) {
            throw UnrecognizedImportFormatException("CSV file is empty")
        }

        val delimiter = detectDelimiter(rawRecords)
        val parsed = rawRecords.map { parseSingleLine(it, delimiter) }.filter { it.any { cell -> cell.isNotBlank() } }
        if (parsed.isEmpty() || parsed.first().size < 2) {
            throw UnrecognizedImportFormatException("Could not detect CSV columns")
        }

        val firstRow = parsed.first()
        val hasHeader = detectHasHeader(firstRow, parsed.drop(1))
        val colCount = parsed.maxOf { it.size }
        val headers = if (hasHeader) {
            (0 until colCount).map { i -> firstRow.getOrNull(i)?.trim().orEmpty().ifEmpty { "Column ${i + 1}" } }
        } else {
            (0 until colCount).map { i -> "Column ${i + 1}" }
        }
        val dataRows = if (hasHeader) parsed.drop(1) else parsed

        val guessed = guessColumns(headers, dataRows, hasHeader)
        return CsvColumnMapping(
            delimiter = delimiter,
            hasHeader = hasHeader,
            headers = headers,
            sampleRows = dataRows.take(5),
            dateColumn = guessed.dateColumn,
            artistColumn = guessed.artistColumn,
            titleColumn = guessed.titleColumn,
            albumColumn = guessed.albumColumn,
            durationColumn = guessed.durationColumn,
            durationUnit = guessed.durationUnit,
        )
    }

    fun read(
        input: InputStream,
        fileName: String,
        mapping: CsvColumnMapping,
        onRow: (RawImportedRow) -> Unit,
        onReject: (ImportRejection) -> Unit,
        onProgressRows: (Int) -> Unit = {},
    ): ReaderStats {
        val stats = ReaderStats()
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER_SIZE)
        var rowIndex = 0
        var firstSkipped = false
        forEachCsvRow(reader, mapping.delimiter) { cells ->
            if (cells.all { it.isBlank() }) return@forEachCsvRow
            if (mapping.hasHeader && !firstSkipped) {
                firstSkipped = true
                return@forEachCsvRow
            }
            rowIndex++
            stats.rowsRead++
            val rowRef = "$fileName:$rowIndex"

            val dateRaw = cells.getOrNull(mapping.dateColumn)?.trim()
            val timestampMs = ImportDateParser.parseFlexibleUtc(dateRaw)
            if (timestampMs == null) {
                stats.rejectedCount++
                onReject(ImportRejection(rowRef, "Unreadable date: '${dateRaw.orEmpty()}'"))
                return@forEachCsvRow
            }

            val artist = cells.getOrNull(mapping.artistColumn)?.trim().orEmpty()
            val title = cells.getOrNull(mapping.titleColumn)?.trim().orEmpty()
            if (artist.isEmpty() || title.isEmpty()) {
                stats.rejectedCount++
                onReject(ImportRejection(rowRef, "Missing artist or title"))
                return@forEachCsvRow
            }

            val album = mapping.albumColumn?.let { idx -> cells.getOrNull(idx)?.trim()?.takeIf { it.isNotEmpty() } }
            val sourceMs = mapping.durationColumn?.let { idx ->
                val rawDur = cells.getOrNull(idx)?.trim().orEmpty()
                if (rawDur.isEmpty()) {
                    null
                } else {
                    val parsed = parseDuration(rawDur, mapping.durationUnit)
                    if (parsed == null) {
                        stats.rejectedCount++
                        onReject(ImportRejection(rowRef, "Invalid duration: '$rawDur'"))
                        return@forEachCsvRow
                    }
                    parsed
                }
            }

            onRow(
                RawImportedRow(
                    startMs = timestampMs,
                    artistRaw = artist,
                    title = title,
                    album = album,
                    sourceListenedMs = sourceMs,
                    sourceKind = ImportSourceKind.CSV,
                    sourceRowRef = rowRef,
                ),
            )
            if (rowIndex % 2_000 == 0) onProgressRows(rowIndex)
        }
        return stats
    }

    internal fun parseDuration(raw: String, unit: CsvDurationUnit): Long? {
        val normalized = raw.replace(',', '.')
        val num = normalized.toDoubleOrNull() ?: return null
        if (!num.isFinite() || num < 0.0) return null
        return when (unit) {
            CsvDurationUnit.MILLISECONDS -> num.toLong()
            CsvDurationUnit.SECONDS -> (num * 1_000.0).toLong()
        }
    }

    internal fun detectDelimiter(sampleLines: List<String>): Char {
        val sample = sampleLines.take(10)
        var bestDelimiter = ','
        var bestScore = -1
        for (delim in CANDIDATE_DELIMITERS) {
            val counts = sample.map { parseSingleLine(it, delim).size }
            val first = counts.firstOrNull() ?: 0
            if (first <= 1) continue
            val consistent = counts.count { it == first }
            val score = consistent * 100 + first
            if (score > bestScore) {
                bestScore = score
                bestDelimiter = delim
            }
        }
        return bestDelimiter
    }

    private fun detectHasHeader(firstRow: List<String>, dataRows: List<List<String>>): Boolean {
        val headerHits = firstRow.count { cell ->
            val key = foldHeader(cell)
            key in DATE_HEADER_ALIASES ||
                key in ARTIST_HEADER_ALIASES ||
                key in TITLE_HEADER_ALIASES ||
                key in ALBUM_HEADER_ALIASES ||
                key in DURATION_HEADER_ALIASES
        }
        if (headerHits >= 2) return true
        // If any cell in the first row parses as a valid timestamp while data rows also have dates, it's probably headerless.
        val firstRowHasDate = firstRow.any { ImportDateParser.parseFlexibleUtc(it) != null }
        if (firstRowHasDate) return false
        return dataRows.isNotEmpty()
    }

    private data class GuessedColumns(
        val dateColumn: Int,
        val artistColumn: Int,
        val titleColumn: Int,
        val albumColumn: Int?,
        val durationColumn: Int?,
        val durationUnit: CsvDurationUnit,
    )

    private fun guessColumns(headers: List<String>, dataRows: List<List<String>>, hasHeader: Boolean): GuessedColumns {
        val colCount = headers.size
        var dateCol = -1
        var artistCol = -1
        var titleCol = -1
        var albumCol: Int? = null
        var durationCol: Int? = null
        var durationUnit = CsvDurationUnit.MILLISECONDS

        if (hasHeader) {
            headers.forEachIndexed { idx, h ->
                val key = foldHeader(h)
                when {
                    dateCol < 0 && (key in DATE_HEADER_ALIASES || key.contains("date") || key.contains("fecha") || key.contains("datum")) -> dateCol = idx
                    artistCol < 0 && (key in ARTIST_HEADER_ALIASES || key.contains("artist")) -> artistCol = idx
                    titleCol < 0 && (key in TITLE_HEADER_ALIASES || key.contains("title") || key.contains("track") || key.contains("titulo") || key.contains("cancion")) -> titleCol = idx
                    albumCol == null && (key in ALBUM_HEADER_ALIASES || key.contains("album")) -> albumCol = idx
                    durationCol == null && (key in DURATION_HEADER_ALIASES || key.contains("played") || key.contains("duration") || key.contains("duracion")) -> {
                        durationCol = idx
                        val lower = h.lowercase(Locale.ROOT)
                        if (SECONDS_HINTS.any { hint -> Regex("""\b$hint\b""").containsMatchIn(lower) }) {
                            durationUnit = CsvDurationUnit.SECONDS
                        }
                    }
                }
            }
        }

        // Fall back to sample-value inspection for missing columns
        val used = HashSet<Int>().apply {
            if (dateCol >= 0) add(dateCol)
            if (artistCol >= 0) add(artistCol)
            if (titleCol >= 0) add(titleCol)
            albumCol?.let(::add)
            durationCol?.let(::add)
        }

        if (dateCol < 0 && dataRows.isNotEmpty()) {
            dateCol = (0 until colCount)
                .filter { it !in used }
                .maxByOrNull { col -> dataRows.count { row -> ImportDateParser.parseFlexibleUtc(row.getOrNull(col)) != null } }
                ?.takeIf { col -> dataRows.any { row -> ImportDateParser.parseFlexibleUtc(row.getOrNull(col)) != null } }
                ?: 0
            used += dateCol
        } else if (dateCol < 0) {
            dateCol = 0
            used += 0
        }

        if (durationCol == null && dataRows.isNotEmpty()) {
            val numericCol = (0 until colCount)
                .filter { it !in used }
                .firstOrNull { col ->
                    val nonEmpty = dataRows.mapNotNull { it.getOrNull(col)?.trim()?.takeIf(String::isNotEmpty) }
                    nonEmpty.isNotEmpty() && nonEmpty.all { it.toDoubleOrNull() != null }
                }
            if (numericCol != null) {
                durationCol = numericCol
                used += numericCol
                val median = dataRows.mapNotNull { it.getOrNull(numericCol)?.trim()?.toDoubleOrNull() }.sorted().let {
                    if (it.isEmpty()) 0.0 else it[it.size / 2]
                }
                if (median in 1.0..1800.0) durationUnit = CsvDurationUnit.SECONDS
            }
        }

        val remainingTextCols = (0 until colCount).filter { it !in used }
        if (artistCol < 0) {
            artistCol = remainingTextCols.firstOrNull() ?: (1.coerceAtMost(colCount - 1))
            used += artistCol
        }
        if (titleCol < 0) {
            titleCol = (0 until colCount).firstOrNull { it !in used } ?: (2.coerceAtMost(colCount - 1))
            used += titleCol
        }
        if (albumCol == null) {
            val extra = (0 until colCount).firstOrNull { it !in used }
            if (extra != null && hasHeader) {
                // Only auto-pick a 4th text column as album if its header isn't obviously unrelated, or leave null if unclear.
                val h = foldHeader(headers[extra])
                if (h.contains("album") || h.contains("disco") || h.contains("release")) {
                    albumCol = extra
                }
            }
        }

        return GuessedColumns(dateCol, artistCol, titleCol, albumCol, durationCol, durationUnit)
    }

    internal fun foldHeader(raw: String): String {
        val nfd = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return Regex("\\p{M}+").replace(nfd, "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    internal fun parseSingleLine(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == delimiter && !inQuotes -> {
                    out += sb.toString()
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** Streams RFC-4180 CSV rows, including quoted fields with embedded newlines. */
    internal inline fun forEachCsvRow(reader: BufferedReader, delimiter: Char, onRow: (List<String>) -> Unit) {
        reader.use { br ->
            val currentRow = ArrayList<String>()
            val cell = StringBuilder()
            var inQuotes = false
            var firstChar = true
            var sawAnyInRow = false
            val buf = CharArray(8192)
            var len: Int
            while (br.read(buf).also { len = it } != -1) {
                var i = 0
                if (firstChar && len > 0) {
                    firstChar = false
                    if (buf[0] == '\uFEFF') i = 1
                }
                while (i < len) {
                    val ch = buf[i]
                    if (inQuotes) {
                        if (ch == '"') {
                            val next = if (i + 1 < len) buf[i + 1] else {
                                br.mark(1)
                                val peeked = br.read()
                                if (peeked == '"'.code) '"'.code.toChar() else {
                                    if (peeked != -1) br.reset()
                                    '\u0000'
                                }
                            }
                            if (next == '"') {
                                cell.append('"')
                                if (i + 1 < len) i++
                            } else {
                                inQuotes = false
                            }
                        } else {
                            cell.append(ch)
                        }
                    } else {
                        when (ch) {
                            '"' -> {
                                inQuotes = true
                                sawAnyInRow = true
                            }
                            delimiter -> {
                                currentRow += cell.toString()
                                cell.setLength(0)
                                sawAnyInRow = true
                            }
                            '\r' -> {
                                // ignore CR; LF will terminate the row
                            }
                            '\n' -> {
                                if (sawAnyInRow || cell.isNotEmpty() || currentRow.isNotEmpty()) {
                                    currentRow += cell.toString()
                                    onRow(currentRow.toList())
                                }
                                currentRow.clear()
                                cell.setLength(0)
                                sawAnyInRow = false
                            }
                            else -> {
                                cell.append(ch)
                                sawAnyInRow = true
                            }
                        }
                    }
                    i++
                }
            }
            if (sawAnyInRow || cell.isNotEmpty() || currentRow.isNotEmpty()) {
                currentRow += cell.toString()
                onRow(currentRow.toList())
            }
        }
    }
}
