package com.lostf1sh.pixelplayeross.data.historyimport

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Parses timestamps from Spotify, Google Takeout, Last.fm, and generic CSV exports into UTC epoch millis.
 * Never invents or guesses a date: unparseable inputs return null so the caller rejects the row.
 */
internal object ImportDateParser {

    private val spotifyAccountFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        .withZone(ZoneOffset.UTC)

    private val localDateTimeFormatters: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.US),
    )

    private val lastFmFormatters: List<DateTimeFormatter> = listOf(Locale.ENGLISH, Locale.forLanguageTag("es"), Locale.forLanguageTag("sv"))
        .flatMap { locale ->
            listOf(
                DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM yyyy HH:mm").toFormatter(locale),
                DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM yyyy, HH:mm").toFormatter(locale),
                DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM yyyy HH:mm:ss").toFormatter(locale),
                DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM yyyy, HH:mm:ss").toFormatter(locale),
                DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMMM yyyy HH:mm").toFormatter(locale),
            )
        }

    /** Parses Spotify Extended / YouTube Takeout ISO-8601 UTC timestamp, or null. */
    fun parseIsoUtc(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(text).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        .toInstant(ZoneOffset.UTC)
                        .toEpochMilli()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }

    /** Parses Spotify Account Data `endTime` ("yyyy-MM-dd HH:mm" in UTC), or null. */
    fun parseSpotifyAccountEndTime(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDateTime.parse(text, spotifyAccountFormatter).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            parseIsoUtc(text)
        }
    }

    /**
     * Parses CSV/generic dates: ISO 8601, Unix seconds or millis, Last.fm ("24 Nov 2026 13:05"),
     * `yyyy-MM-dd HH:mm[:ss]`, or `yyyy-MM-dd`. Returns null if unreadable.
     */
    fun parseFlexibleUtc(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // Numeric Unix timestamp (seconds or milliseconds)
        text.toLongOrNull()?.let { numeric ->
            return unixNumericToMillis(numeric)
        }
        text.toDoubleOrNull()?.let { decimal ->
            if (decimal.isFinite() && decimal > 0.0 && text.all { it.isDigit() || it == '.' }) {
                return unixNumericToMillis(decimal.toLong())
            }
        }

        // ISO 8601 with zone or local time
        parseIsoUtc(text)?.let { return it }

        // Common SQL / spreadsheet date-times in UTC
        for (fmt in localDateTimeFormatters) {
            try {
                return LocalDateTime.parse(text, fmt).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }

        // Last.fm style ("24 Nov 2026 13:05", etc.)
        for (fmt in lastFmFormatters) {
            try {
                return LocalDateTime.parse(text, fmt).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }

        // ISO calendar date without time ("2024-05-01")
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
        }

        return null
    }

    private fun unixNumericToMillis(value: Long): Long? {
        if (value <= 0L) return null
        // Anything below 100_000_000_000 (~year 1973 in ms, or year 5138 in seconds) is Unix seconds.
        return if (value < 100_000_000_000L) value * 1_000L else value
    }
}
