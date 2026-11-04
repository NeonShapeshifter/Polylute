package com.lostf1sh.pixelplayeross.data.lyrics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lostf1sh.pixelplayeross.data.preferences.AnimatedCoverFolderStore
import com.lostf1sh.pixelplayeross.utils.StorageUtils
import com.lostf1sh.pixelplayeross.utils.MediaStorePermissionHelper
import com.lostf1sh.pixelplayeross.utils.TreeGrants
import kotlinx.coroutines.CancellationException
import java.io.File

internal class AndroidLyricsStorage(private val context: Context) : LyricsStorage {
    private val treeGrants by lazy { TreeGrants(context) }

    private fun document(path: String): DocumentFile? {
        val tree = matchingTree(path) ?: return null
        val uri = tree.toUri()
        val root = treePath(uri) ?: return null
        var current = DocumentFile.fromTreeUri(context, uri) ?: return null
        val relative = normalizePath(path).removePrefix(root.trimEnd('/') + "/")
        for (part in relative.split('/')) {
            if (part.isBlank() || part == "." || part == "..") throw LyricsWriteException(LyricsWriteReason.WRONG_FOLDER)
            current = current.findFile(part) ?: return null
        }
        return current
    }

    override fun exists(path: String): Boolean = if (matchingTree(path) != null) document(path)?.isFile == true else File(path).isFile

    override fun contentHash(path: String): String? {
        if (matchingTree(path) == null) return super.contentHash(path)
        val file = document(path) ?: return null
        val input = context.contentResolver.openInputStream(file.uri) ?: throw LyricsWriteException(LyricsWriteReason.PERMISSION)
        return input.use(::lyricsContentHash)
    }

    override fun deletePath(path: String) {
        if (matchingTree(path) == null) return super.deletePath(path)
        val file = document(path) ?: return
        if (!file.delete()) throw LyricsWriteException(LyricsWriteReason.PERMISSION)
    }

    override fun writeAccessRequest(path: String): android.content.IntentSender? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        return try {
            val uri = MediaStorePermissionHelper.getMediaStoreUri(context, path) ?: return null
            MediaStorePermissionHelper.createWriteRequestIntentSender(context, listOf(uri))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null // Unindexed sidecars and rejected requests use the same in-app save path as denial.
        }
    }

    override fun isRemovable(path: String): Boolean {
        val internal = Environment.getExternalStorageDirectory().absolutePath
        return !under(path, internal) && (
            path.startsWith("/storage/") || path.startsWith("/mnt/media_rw/") ||
                StorageUtils.getAvailableStorages(context).any { it.isRemovable && under(path, it.path.absolutePath) }
            )
    }

    override fun writePath(path: String, content: String) {
        val file = File(path)
        if (file.exists() && !file.canWrite()) throw LyricsWriteException(LyricsWriteReason.FILE_READ_ONLY)
        file.writeText(content, Charsets.UTF_8)
    }

    /** The OS's persisted grants are the source of truth, including across restarts. The grant itself is [TreeGrants]'s (shared with the animated covers, B57-fix1 4). */
    fun grants(): List<Pair<String, String>> = treeGrants.grants(requireWrite = true)

    override fun matchingTree(path: String): String? = grants()
        .filter { under(path, it.second) }
        .maxByOrNull { it.second.length }?.first

    fun keepTree(uri: Uri, flags: Int, songPath: String) {
        try {
            // lyrics are written, so write access is required, and the song's folder has to be inside the tree
            treeGrants.keep(uri, flags, required = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, mustContain = File(songPath).parent.orEmpty())
        } catch (refused: TreeGrants.Refused) {
            throw LyricsWriteException(
                when (refused.reason) {
                    TreeGrants.Reason.WRONG_FOLDER -> LyricsWriteReason.WRONG_FOLDER
                    TreeGrants.Reason.PERMISSION -> LyricsWriteReason.PERMISSION
                }
            )
        }
    }

    override fun dropTree(tree: String) {
        // B57-fix1 review: the same folder may be the animated covers' folder; they still need to read it, so only the write goes
        val uri = tree.toUri()
        val coversNeed = if (AnimatedCoverFolderStore.storedTree(context) == uri) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0
        treeGrants.releaseShared(uri, stillNeeded = coversNeed)
    }

    override fun writeTree(tree: String, path: String, content: String) {
        val uri = tree.toUri()
        val root = treePath(uri) ?: throw LyricsWriteException(LyricsWriteReason.UNAVAILABLE)
        if (!under(path, root)) throw LyricsWriteException(LyricsWriteReason.WRONG_FOLDER)
        var directory = DocumentFile.fromTreeUri(context, uri) ?: throw LyricsWriteException(LyricsWriteReason.UNAVAILABLE)
        if (!directory.exists()) throw LyricsWriteException(LyricsWriteReason.UNAVAILABLE)
        val segments = normalizePath(path).removePrefix(root.trimEnd('/') + "/").split('/')
        if (segments.any { it == ".." || it == "." || it.isBlank() }) throw LyricsWriteException(LyricsWriteReason.WRONG_FOLDER)
        for (name in segments.dropLast(1)) {
            directory = directory.findFile(name)?.takeIf { it.isDirectory }
                ?: throw LyricsWriteException(LyricsWriteReason.UNAVAILABLE)
        }
        val existing = directory.findFile(segments.last())
        if (existing != null && !existing.canWrite()) throw LyricsWriteException(LyricsWriteReason.FILE_READ_ONLY)
        if (!directory.canWrite()) throw LyricsWriteException(LyricsWriteReason.CARD_READ_ONLY)
        val target = existing ?: directory.createFile("application/octet-stream", segments.last())
            ?: throw LyricsWriteException(LyricsWriteReason.PERMISSION)
        val stream = context.contentResolver.openOutputStream(target.uri, "wt")
            ?: throw LyricsWriteException(LyricsWriteReason.PERMISSION)
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
    }

    internal fun treePath(uri: Uri): String? = treeGrants.treePath(uri)

    private fun normalizePath(path: String): String = TreeGrants.normalizePath(path)

    private fun under(path: String, root: String): Boolean = TreeGrants.under(path, root)
}
