package com.lostf1sh.pixelplayeross.data.preferences

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import com.lostf1sh.pixelplayeross.utils.TreeGrants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * B57-fix1 4 (#52). On Android 13+ the audio permission does not cover a `cover.mp4` / `cover.webp` / `cover.gif`, so the listener picks **one folder** with the system picker
 * (`ACTION_OPEN_DOCUMENT_TREE`) and the covers are read through it. Never `READ_MEDIA_VIDEO` / `READ_MEDIA_IMAGES`, never "selected photos": the app asks Android for nothing but the folder.
 *
 * This is the folder the listener chose and the right to it. The grant is [TreeGrants]'s (the lyrics saver's code, a read-only variant with no "the song must be inside the tree"). The folder is
 * remembered in a plain preferences file (read synchronously, from the library scan's worker as well as the UI) and the OS's persisted permission is the truth: a folder whose permission
 * was revoked behind the app's back is no folder. Android 10 to 12: no picker, no folder; the covers are read from the disk as before ([AnimatedCovers.needsFolderGrant]).
 */
@Singleton
class AnimatedCoverFolderStore internal constructor(private val context: Context, private val sdk: Int) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, android.os.Build.VERSION.SDK_INT)

    private val grants = TreeGrants(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _folder = MutableStateFlow(current())

    /** The folder the covers are read through (null: none, or not needed before Android 13). */
    val folder: StateFlow<Uri?> = _folder.asStateFlow()

    /** What the listener sees for a folder: its name. */
    fun nameOf(tree: Uri): String = TreeGrants.displayName(tree)

    /** The file system path of the folder's root, which the album folders are placed under. */
    fun rootPathOf(tree: Uri): String? = grants.treePath(tree)

    /** The stored folder, if the OS still lets the app read it. Read fresh every time: the scan's worker has no other way to learn that the listener changed it. */
    fun current(): Uri? {
        if (!AnimatedCovers.needsFolderGrant(sdk)) return null
        val tree = prefs.getString(KEY_TREE, null)?.toUri() ?: return null
        return tree.takeIf { grants.canRead(it) }
    }

    /**
     * Keeps the right to a folder the listener just picked. Returns true when it is kept (and is now the folder): the previous one, if any, is let go. Anything refused leaves what
     * was there as it was, so cancelling or picking something unusable never costs the folder the listener already had.
     */
    fun choose(tree: Uri, flags: Int): Boolean {
        try {
            grants.keep(tree, flags, required = Intent.FLAG_GRANT_READ_URI_PERMISSION, persistOnly = Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (refused: TreeGrants.Refused) {
            return false
        } catch (e: SecurityException) {
            return false
        }
        val previous = _folder.value
        prefs.edit { putString(KEY_TREE, tree.toString()) }
        _folder.value = tree
        if (previous != null && previous != tree) letGo(previous)
        return true
    }

    /** Lets go of the folder: the permission is released and nothing is read through it any more. */
    fun remove() {
        val previous = _folder.value
        prefs.edit { remove(KEY_TREE) }
        _folder.value = null
        if (previous != null) letGo(previous)
    }

    /**
     * B57-fix1 review: the folder may also be the lyrics saver's (the only grant this app persists with write; the covers persist read only). Then the lyrics still need
     * read + write on it and nothing is released; otherwise the read the covers took goes.
     */
    private fun letGo(tree: Uri) {
        val lyricsNeed = if (grants.canWrite(tree)) Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        grants.releaseShared(tree, stillNeeded = lyricsNeed)
    }

    companion object {
        private const val PREFS = "animated_cover_folder"
        private const val KEY_TREE = "tree"

        /** The folder the covers were given, as stored (the lyrics saver asks before it lets a folder go: B57-fix1 review). */
        fun storedTree(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.toUri()
    }
}
