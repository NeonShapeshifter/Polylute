package com.lostf1sh.pixelplayeross.utils

import com.lostf1sh.pixelplayeross.data.model.Song
import java.util.Locale
import java.util.concurrent.TimeUnit

fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "00:00"

    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatTotalDuration(songs: List<Song>): String {
    val totalMillis = songs.sumOf { it.duration }
    val hours = TimeUnit.MILLISECONDS.toHours(totalMillis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis) % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d h %02d min", hours, minutes)
    } else {
        String.format(Locale.US, "%d min", minutes)
    }
}


fun formatListeningDurationCompact(milliseconds: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60
    return when {
        hours > 0 && minutes > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
        hours > 0 -> String.format(Locale.US, "%dh", hours)
        minutes > 0 -> String.format(Locale.US, "%dm", minutes)
        else -> String.format(Locale.US, "%ds", seconds)
    }
}

fun formatSongCount(count: Int): String {
    return if (count <= 1) "$count Song" else "$count Songs"
}

/**
 * The localized song count. [formatSongCount] is the hardcoded-English variant kept for any
 * non-Compose caller; anything the user reads goes through this one so the count agrees with the
 * rest of the interface's language.
 */
@androidx.compose.runtime.Composable
fun formatSongCountLabel(count: Int): String =
    androidx.compose.ui.res.pluralStringResource(
        com.lostf1sh.pixelplayeross.R.plurals.song_count,
        count,
        count,
    )

/** Home's compact duration uses the active locale and resource-owned units. */
fun formatListeningDurationCompact(milliseconds: Long, resources: android.content.res.Resources): String {
    val seconds=TimeUnit.MILLISECONDS.toSeconds(milliseconds.coerceAtLeast(0))
    val minutes=seconds/60
    val hours=minutes/60
    val id=when {
        hours>0 && minutes%60>0 -> com.lostf1sh.pixelplayeross.R.string.listening_duration_hours_minutes
        hours>0 -> com.lostf1sh.pixelplayeross.R.string.listening_duration_hours
        minutes>0 -> com.lostf1sh.pixelplayeross.R.string.listening_duration_minutes
        else -> com.lostf1sh.pixelplayeross.R.string.listening_duration_seconds
    }
    return when {
        hours>0 && minutes%60>0 -> resources.getString(id,hours,minutes%60)
        hours>0 -> resources.getString(id,hours)
        minutes>0 -> resources.getString(id,minutes)
        else -> resources.getString(id,seconds)
    }
}
