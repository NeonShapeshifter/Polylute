package com.lostf1sh.pixelplayeross.data.lyrics

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Platform operations are isolated so every save decision can be exercised on the host. */
internal interface LyricsStorage {
    fun isRemovable(path: String): Boolean
    fun writePath(path: String, content: String)
    fun matchingTree(path: String): String?
    fun writeTree(tree: String, path: String, content: String)
    fun dropTree(tree: String)
    fun writeAccessRequest(path: String): android.content.IntentSender? = null
    fun exists(path: String): Boolean = File(path).isFile
    fun contentHash(path: String): String? = File(path).takeIf { it.isFile }?.inputStream()?.use(::lyricsContentHash)
    fun deletePath(path: String) {
        val file = File(path)
        if (file.exists() && !file.delete()) throw LyricsWriteException(LyricsWriteReason.PERMISSION)
    }
}

internal enum class LyricsWriteReason {
    PERMISSION, CARD_READ_ONLY, FILE_READ_ONLY, NO_SPACE, WRONG_FOLDER, UNAVAILABLE, IO, EXISTING_FILE
}

internal class LyricsWriteException(val reason: LyricsWriteReason) : IOException()

internal fun lyricsWriteReason(error: Exception): LyricsWriteReason {
    if (error is LyricsWriteException) return error.reason
    if (error is SecurityException) return LyricsWriteReason.PERMISSION
    val chain = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    if (chain.any { it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC }) return LyricsWriteReason.NO_SPACE
    if (chain.any { it is android.system.ErrnoException && it.errno == android.system.OsConstants.EROFS }) return LyricsWriteReason.CARD_READ_ONLY
    val detail = chain.joinToString(" ") { it.message.orEmpty() }.lowercase(java.util.Locale.ROOT)
    return when {
        "enospc" in detail || "no space" in detail -> LyricsWriteReason.NO_SPACE
        "erofs" in detail || "read-only file system" in detail -> LyricsWriteReason.CARD_READ_ONLY
        "eacces" in detail || "eperm" in detail || "permission denied" in detail -> LyricsWriteReason.PERMISSION
        else -> LyricsWriteReason.IO
    }
}

internal sealed interface LyricsSaveResult {
    data object FileSaved : LyricsSaveResult
    data class NeedsFolder(val reason: LyricsWriteReason) : LyricsSaveResult
    data class NeedsWriteAccess(val sender: android.content.IntentSender) : LyricsSaveResult
    data class SavedInApp(val reason: LyricsWriteReason) : LyricsSaveResult
}

/** One initial attempt and, at most, one picker result. No retry loop can reopen the picker. */
internal class LyricsFileSaver(private val storage: LyricsStorage) {
    suspend fun save(
        songPath: String,
        content: String,
        afterPicker: Boolean = false,
        declinedReason: LyricsWriteReason? = null,
        afterWriteAccess: Boolean = false,
        saveInApp: suspend () -> Unit
    ): LyricsSaveResult {
        val song = File(songPath)
        val target = File(song.parentFile, "${song.nameWithoutExtension}.lrc").absolutePath
        var reason = declinedReason ?: LyricsWriteReason.PERMISSION
        if (declinedReason == null) {
            val tree = storage.matchingTree(target)
            if (tree != null) {
                try {
                    storage.writeTree(tree, target, content)
                    return LyricsSaveResult.FileSaved
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    reason = lyricsWriteReason(error)
                    if (reason == LyricsWriteReason.PERMISSION || reason == LyricsWriteReason.UNAVAILABLE) storage.dropTree(tree)
                    else return fallback(reason, saveInApp)
                }
            } else if (!storage.isRemovable(target)) {
                try {
                    storage.writePath(target, content)
                    return LyricsSaveResult.FileSaved
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    reason = lyricsWriteReason(error)
                    if (reason == LyricsWriteReason.FILE_READ_ONLY && android.os.Build.VERSION.SDK_INT >= 30 && !afterWriteAccess) {
                        storage.writeAccessRequest(target)?.let { return LyricsSaveResult.NeedsWriteAccess(it) }
                    }
                    return fallback(reason, saveInApp)
                }
            }
            if (!afterPicker) return LyricsSaveResult.NeedsFolder(reason)
        }
        return fallback(reason, saveInApp)
    }

    private suspend fun fallback(reason: LyricsWriteReason, saveInApp: suspend () -> Unit): LyricsSaveResult {
        saveInApp() // A failed fallback must propagate; never claim a save that did not happen.
        return LyricsSaveResult.SavedInApp(reason)
    }
}
