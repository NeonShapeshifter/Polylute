package com.lostf1sh.pixelplayeross.data.backup.model

import android.content.Context
import androidx.annotation.StringRes
import com.lostf1sh.pixelplayeross.R

/**
 * A user-facing backup message that has not been turned into text yet.
 *
 * The backup layer reports progress, warnings and failures as sentences — "Collecting Playlists",
 * "Backup file failed validation: …", "Restore failed at Saved Lyrics: …". Those sentences were
 * English string literals built inside `BackupManager`, `RestoreExecutor` and `RestorePlanner`,
 * which meant the progress dialog and the result toasts rendered English inside an app that is
 * otherwise translated, and no amount of work in the composables could fix it: the text arrived
 * already assembled.
 *
 * The data layer has no locale and no `Context`, so it carries the *shape* of the sentence instead:
 * a resource id and its arguments. The UI resolves it, in the language the user chose.
 *
 * Arguments may be [BackupSection] — a section name is itself product copy, so it is resolved
 * through its own resource rather than interpolated as a literal. Anything else is passed straight
 * to `getString`, which handles the usual `%1$d` / `%1$s` forms.
 */
data class BackupMessage(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList(),
) {
    companion object {
        /** A message with no arguments, for the common case. */
        fun of(@StringRes resId: Int): BackupMessage = BackupMessage(resId)

        fun of(@StringRes resId: Int, vararg args: Any): BackupMessage =
            BackupMessage(resId, args.toList())

        /**
         * Detail that is already text and has no resource: the message of an exception the backup
         * layer did not raise itself.
         *
         * These are passed through verbatim rather than replaced with a generic failure, because
         * discarding them would turn a specific reason into "unknown error" — which is exactly the
         * information a user reports when a restore fails.
         */
        fun raw(text: String): BackupMessage =
            BackupMessage(R.string.backup_detail_raw, listOf(text))
    }
}

/**
 * Resolves [BackupMessage] arguments.
 *
 * Arguments are resolved rather than interpolated raw, so a message that embeds one reads as a
 * single sentence in the user's language instead of a localized word dropped into an English frame:
 *
 * - a [BackupSection] becomes its localized name;
 * - a nested [BackupMessage] becomes its localized text, which is how "…failed at <section>: <why>"
 *   is built when the <section> may itself have to fall back to "an unknown section";
 * - anything else is passed through to `getString` unchanged.
 */
private fun BackupMessage.localizedArgs(context: Context): Array<Any> {
    val resolved = arrayOfNulls<Any>(args.size)
    for (index in args.indices) {
        resolved[index] = when (val arg = args[index]) {
            is BackupSection -> context.getString(arg.labelRes)
            is BackupMessage -> arg.resolve(context)
            else -> arg
        }
    }
    @Suppress("UNCHECKED_CAST")
    return resolved as Array<Any>
}

/** The message as text, in the context's current locale. Safe to call outside composition. */
fun BackupMessage.resolve(context: Context): String =
    context.getString(resId, *localizedArgs(context))

/**
 * A failure whose text has not been resolved yet.
 *
 * The restore path reports failures by throwing, and the thrower is caught several frames up and
 * turned into user-facing text. Throwing a message rather than a rendered string keeps the locale
 * decision at the point where the text is finally shown.
 */
class BackupMessageException(val backupMessage: BackupMessage) : Exception() {
    /** Rendered lazily so nothing holds a translated string in a field. */
    override val message: String
        get() = backupMessage.args.joinToString(", ")

    /** The message as text, in the context's current locale. */
    fun resolve(context: Context): String = backupMessage.resolve(context)

    companion object {
        /** The message carried by [error], or null if it is not one of ours. */
        fun messageOf(error: Throwable): BackupMessage? =
            (error as? BackupMessageException)?.backupMessage
    }
}
