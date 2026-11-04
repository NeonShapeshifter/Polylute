package com.lostf1sh.pixelplayeross.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * B57-fix1 4. The one implementation of "the listener picked a folder with the system picker, and the app keeps the right to it" (`ACTION_OPEN_DOCUMENT_TREE` plus a persisted
 * permission). The lyrics saver ([com.lostf1sh.pixelplayeross.data.lyrics.AndroidLyricsStorage]) keeps a folder it can **write** to, one that contains the song; the animated covers
 * keep a folder they only **read** from, which need not contain any particular song. The two differ only in the arguments of [keep]: no second copy.
 *
 * The OS's persisted grants are the source of truth, including across restarts.
 */
internal class TreeGrants(private val context: Context) {
    enum class Reason { WRONG_FOLDER, PERMISSION }

    class Refused(val reason: Reason) : Exception(reason.name)

    /**
     * Persists the right to [uri].
     * - [required]: the access flags the picker must have granted (read, or read + write); fewer is [Reason.PERMISSION].
     * - [mustContain]: a folder (a song's folder) that has to be inside the tree, or null for "any folder": the lyrics saver needs the song's folder inside it, the animated covers do not.
     *
     * A volume's root is refused on Android 11 and later (the system itself refuses to grant it), and so is any tree that is not on the shared-storage provider.
     */
    fun keep(uri: Uri, flags: Int, required: Int, mustContain: String? = null, persistOnly: Int? = null) {
        val root = treePath(uri) ?: throw Refused(Reason.WRONG_FOLDER)
        if (android.os.Build.VERSION.SDK_INT >= 30 && DocumentsContract.getTreeDocumentId(uri).substringAfter(':').isBlank()) {
            throw Refused(Reason.WRONG_FOLDER)
        }
        if (mustContain != null && !under(mustContain, root)) throw Refused(Reason.WRONG_FOLDER)
        val access = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (access and required != required) throw Refused(Reason.PERMISSION)
        // [persistOnly]: keep no more than the caller needs. The picker hands out read + write for any tree, and a reader that persisted write would look like a writer
        // to [releaseShared] (B57-fix1 review: one folder for lyrics and covers).
        context.contentResolver.takePersistableUriPermission(uri, persistOnly?.let { access and it } ?: access)
    }

    /** Lets go of the right to [tree], read and write (releasing one that was not held is harmless). */
    fun release(tree: Uri) = release(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    /** Lets go of [modes] of the right to [tree] and keeps the rest. */
    fun release(tree: Uri, modes: Int) {
        if (modes == 0) return
        runCatching { context.contentResolver.releasePersistableUriPermission(tree, modes) }
    }

    /**
     * B57-fix1 review. The lyrics saver and the animated covers may hold **the same folder** (one OS grant per tree: releasing it for one releases it for both).
     * - [stillNeeded]: the modes another feature still needs on [tree]; they are kept, and only the rest is let go.
     */
    fun releaseShared(tree: Uri, stillNeeded: Int) {
        val all = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // write without read is no use to anyone: a feature that still needs write keeps both
        val keep = if (stillNeeded and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) all else stillNeeded and all
        release(tree, all and keep.inv())
    }

    /** True while the OS lets this app write [tree] (the lyrics saver's grant: the animated covers persist read only). */
    fun canWrite(tree: Uri): Boolean = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** The persisted grants that carry at least [requireWrite]'s access, as (tree uri, file system path of its root). */
    fun grants(requireWrite: Boolean): List<Pair<String, String>> = context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && (!requireWrite || it.isWritePermission) }
        .mapNotNull { permission -> treePath(permission.uri)?.let { permission.uri.toString() to it } }

    /** True while the OS still lets this app read [tree]. */
    fun canRead(tree: Uri): Boolean = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

    /** The file system path of a tree's root folder (`/storage/emulated/0/Music`), or null for a provider or a document id it cannot map. */
    fun treePath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val volume = id.substringBefore(':')
        if (':' !in id || volume.isBlank()) return null
        val relative = id.substringAfter(':').trim('/')
        if (relative.split('/').any { it == ".." || it == "." }) return null
        val root = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().absolutePath
            else "/storage/$volume"
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    fun under(path: String, root: String): Boolean = Companion.under(path, root)

    companion object {
        fun normalizePath(path: String): String = if (path.startsWith("/mnt/media_rw/")) "/storage/" + path.removePrefix("/mnt/media_rw/") else path

        fun under(path: String, root: String): Boolean {
            val normalized = normalizePath(path)
            return normalized == root || normalized.startsWith(root.trimEnd('/') + "/")
        }

        /** The name the listener sees for a tree: its last folder (`Music`), or the volume for a root. */
        fun displayName(uri: Uri): String {
            val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.lastPathSegment.orEmpty()
            val relative = id.substringAfter(':', "").trim('/')
            return if (relative.isEmpty()) id.substringBefore(':') else File(relative).name
        }
    }
}
