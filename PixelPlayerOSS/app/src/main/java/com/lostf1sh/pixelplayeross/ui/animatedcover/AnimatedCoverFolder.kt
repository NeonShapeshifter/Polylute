package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.lostf1sh.pixelplayeross.data.preferences.AnimatedCoverFolderStore
import com.lostf1sh.pixelplayeross.data.preferences.AnimatedCovers
import java.io.File

/**
 * B57-fix1 4. What the finder and the frame maker read the covers with: the disk before Android 13, the folder the listener chose ([AnimatedCoverFolderStore]) after it, and nothing at all
 * while there is none (every album then shows its still cover). It asks the store every time, so a folder chosen, changed or removed in Settings is the one in use on the next question.
 */
class AnimatedCoverFolder(
    private val context: Context,
    private val store: AnimatedCoverFolderStore,
    private val sdk: Int = android.os.Build.VERSION.SDK_INT,
) : AnimatedCoverDirectory {
    @Volatile private var cached: Pair<Uri, TreeAnimatedCoverDirectory>? = null

    private fun current(): AnimatedCoverDirectory {
        if (!AnimatedCovers.needsFolderGrant(sdk)) return AnimatedCoverDirectory.Disk
        val tree = store.current() ?: return AnimatedCoverDirectory.None
        cached?.takeIf { it.first == tree }?.let { return it.second }
        val root = store.rootPathOf(tree) ?: return AnimatedCoverDirectory.None
        return TreeAnimatedCoverDirectory(context.contentResolver, tree, root).also { cached = tree to it }
    }

    override fun names(dir: File) = current().names(dir)
    override fun stamp(dir: File) = current().stamp(dir)
    override fun sizeOf(file: File) = current().sizeOf(file)
    override fun modifiedOf(file: File) = current().modifiedOf(file)
    override fun documentUriOf(file: File) = current().documentUriOf(file)
    override fun isUnreachable(dir: File) = current().isUnreachable(dir)

    companion object {
        /** The directory to read covers with from a place that has no injected one (the library scan's worker): the same answer, from the same stored folder. */
        fun directoryFor(context: Context): AnimatedCoverDirectory {
            val app = context.applicationContext ?: context
            return AnimatedCoverFolder(app, AnimatedCoverFolderStore(app))
        }
    }
}

/**
 * The chosen folder, read as [AnimatedCoverDirectory] through `DocumentsContract`: one query per folder lists its names, sizes and times, one more reads the folder's own time. Document ids on the
 * shared-storage provider are `volume:path`, so an album folder maps to its document with no walk down the tree ([AnimatedCovers.segmentsInTree] says where it is below the root; a folder
 * outside the tree answers "nothing here" and the still cover stays).
 */
internal class TreeAnimatedCoverDirectory(
    private val resolver: ContentResolver,
    private val tree: Uri,
    private val rootPath: String,
) : AnimatedCoverDirectory {
    private class Entry(val documentId: String, val size: Long, val modified: Long)

    private val listings = object : LinkedHashMap<String, Map<String, Entry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Entry>>?): Boolean = size > 16
    }

    private fun documentIdOf(dir: File): String? {
        val below = AnimatedCovers.segmentsInTree(dir.path, rootPath) ?: return null
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        if (below.isEmpty()) return rootId
        return rootId + (if (rootId.endsWith(":")) "" else "/") + below.joinToString("/")
    }

    override fun names(dir: File): List<String>? {
        val id = documentIdOf(dir) ?: return null
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val found = LinkedHashMap<String, Entry>()
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    found[cursor.getString(0)] = Entry(cursor.getString(1), cursor.getLong(2), cursor.getLong(3))
                }
            } ?: return null
            synchronized(listings) { listings[dir.path] = found }
            found.keys.toList()
        } catch (e: Exception) {
            null
        }
    }

    override fun stamp(dir: File): Long {
        val id = documentIdOf(dir) ?: return 0L
        return try {
            resolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, id),
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** Outside the chosen folder: no document to ask for, so "nothing here" is final. (Inside it, a failed query is a failed read.) */
    override fun isUnreachable(dir: File): Boolean = documentIdOf(dir) == null

    private fun entryOf(file: File): Entry? = synchronized(listings) { listings[file.parent.orEmpty()]?.get(file.name) }

    override fun sizeOf(file: File): Long = entryOf(file)?.size ?: 0L
    override fun modifiedOf(file: File): Long = entryOf(file)?.modified ?: 0L
    override fun documentUriOf(file: File): Uri? = entryOf(file)?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.documentId) }
}
