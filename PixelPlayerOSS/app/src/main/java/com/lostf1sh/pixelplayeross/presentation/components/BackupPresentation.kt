package com.lostf1sh.pixelplayeross.presentation.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.lostf1sh.pixelplayeross.data.backup.model.BackupMessage
import com.lostf1sh.pixelplayeross.data.backup.model.resolve

/**
 * Presentation helpers for the backup surface.
 *
 * Both of these exist because the backup layer hands the UI a value that is not yet fit to display:
 * a sentence with unresolved arguments, or a timestamp that needs the user's own date and time
 * conventions.
 */

/**
 * A [BackupMessage] as text, in the language the user chose.
 *
 * Resolution goes through the context rather than `stringResource` because a message's arguments
 * can themselves be messages or section names, and those have to be resolved before substitution —
 * `stringResource` formats its arguments verbatim. The result is remembered per message and
 * context, and the context changes when the locale does, so a language change re-renders.
 */
@Composable
fun backupMessageText(message: BackupMessage): String {
    val context = LocalContext.current
    return remember(message, context) { message.resolve(context) }
}

/**
 * A backup timestamp, formatted for the user's locale.
 *
 * This used to be `SimpleDateFormat("MMM d, yyyy 'at' h:mm a")`, which hardcoded both the word
 * "at" and the US ordering and 12-hour clock. `getBestDateTimePattern` with the `j` skeleton takes
 * the date order, the separator and the 12- or 24-hour preference from the locale, so the timestamp
 * in the backup history reads the way every other date on the device reads.
 */
@Composable
fun rememberBackupTimestamp(epochMs: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(epochMs, locale) {
        val pattern = DateFormat.getBestDateTimePattern(locale, "yMMMdjm")
        DateFormat.format(pattern, epochMs).toString()
    }
}
