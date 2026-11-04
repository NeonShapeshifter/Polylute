package com.lostf1sh.pixelplayeross.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R

/**
 * B57 4.3 (owner): an album's data line, the third and smallest line of its card and header: **year · N songs · duration** ("2019 · 9 songs · 38 min", "1 h 12 min"). A missing year
 * is left out ("9 songs · 38 min"); a missing duration is left out too. The duration is the sum of the album's songs already in the database: no file is read.
 */
object AlbumInfoLine {
    /** Whole minutes, rounded to the nearest: 38 min 40 s is 39 min; under 30 s of a total still shows 1 min (an album is never "0 min"). */
    fun totalMinutes(durationMs: Long): Int = if (durationMs <= 0L) 0 else ((durationMs + 30_000L) / 60_000L).toInt().coerceAtLeast(1)

    /** A year worth showing: four digits. */
    fun yearText(year: Int): String? = year.takeIf { it in 1000..9999 }?.toString()

    /** The line from its parts, joined with " · "; any null part is left out. */
    fun join(vararg parts: String?): String = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
}

/** The duration as "38 min" or "1 h 12 min"; null when there is none. */
@Composable
fun albumDurationText(durationMs: Long): String? {
    val minutes = AlbumInfoLine.totalMinutes(durationMs)
    if (minutes <= 0) return null
    return if (minutes < 60) stringResource(R.string.album_duration_minutes, minutes)
    else stringResource(R.string.album_duration_hours_minutes, minutes / 60, minutes % 60)
}

/** "year · N songs · duration" for an album: the line under the artist on its card and on its page. */
@Composable
fun albumInfoLine(year: Int, songCount: Int, durationMs: Long): String =
    AlbumInfoLine.join(AlbumInfoLine.yearText(year), pluralStringResource(R.plurals.song_count, songCount, songCount), albumDurationText(durationMs))
