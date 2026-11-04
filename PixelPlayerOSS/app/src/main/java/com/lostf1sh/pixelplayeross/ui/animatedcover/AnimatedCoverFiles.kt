package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.net.Uri
import android.content.Context
import androidx.compose.runtime.Immutable
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.preferences.AnimatedCoverFolderStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * B56 phase 7.2 (#48): the animated file an album folder may hold. **The order of the entries is the priority: mp4 over webp over gif.** (A folder with all three plays the MP4: it
 * is the smallest for the same picture, and the only one a hardware decoder plays.)
 */
enum class AnimatedCoverKind(val fileName: String) {
    VIDEO("cover.mp4"),
    WEBP("cover.webp"),
    GIF("cover.gif"),
}

/**
 * One animated cover found beside an album's songs. [identity] changes when the file does (path, size, time): it is what a failure is remembered by.
 *
 * [file] is where the cover is in the file system (what the album folder is called, what the log says); [uri] is what the engines **read it with**: the same file as `file://`
 * before Android 13, a document inside the folder the listener picked (`content://`) from Android 13 (B57-fix1 4).
 */
@Immutable
data class AnimatedCover(val file: File, val kind: AnimatedCoverKind, val sizeBytes: Long, val modifiedMs: Long, val documentUri: Uri? = null) {
    val identity: String get() = "${file.absolutePath}|$modifiedMs|$sizeBytes"

    /** What the engines read: the document in the chosen folder, or the plain file. (Built when asked, so that nothing here needs Android until an engine does.) */
    val uri: Uri get() = documentUri ?: Uri.fromFile(file)

    /** A plain file can be asked; a document is opened by the engine, and a failure there is reported as any other. */
    fun canRead(): Boolean = documentUri != null || file.canRead()
}

/** Picking the animated file out of a folder's file names: pure, so the priority is a unit test. */
object AnimatedCoverNames {
    /** The name of the file to play and its kind, or null when the folder has none. Names are compared without regard to case (an SD card's FAT is not case sensitive; others may be). */
    fun pick(names: Iterable<String>): Pair<String, AnimatedCoverKind>? {
        val byLower = HashMap<String, String>()
        for (name in names) byLower.putIfAbsent(name.lowercase(Locale.ROOT), name)
        for (kind in AnimatedCoverKind.entries) {
            byLower[kind.fileName]?.let { return it to kind }
        }
        return null
    }
}

/** What the finder reads of a folder: a seam, so the tests list a folder they built without a disk and count the listings. */
interface AnimatedCoverDirectory {
    /** The names of the files directly in [dir], or null when it cannot be listed. */
    fun names(dir: File): List<String>?

    /** A value that changes when files are added to, removed from or renamed in [dir]. */
    fun stamp(dir: File): Long

    fun sizeOf(file: File): Long
    fun modifiedOf(file: File): Long

    /** The address of [file] as a document in the chosen folder (B57-fix1 4), or null for a plain file, which is read by its path. */
    fun documentUriOf(file: File): Uri? = null

    /**
     * True when [dir] is somewhere this directory can never read (outside the folder the listener chose, or no folder chosen at all): "nothing here" is then final and the finder may
     * remember it. A [names] of null for any other folder is a read that **failed** (a provider that errored), and that is not remembered: the next question reads it again.
     */
    fun isUnreachable(dir: File): Boolean = false

    object Disk : AnimatedCoverDirectory {
        override fun names(dir: File): List<String>? = dir.list()?.toList()
        override fun stamp(dir: File): Long = dir.lastModified()
        override fun sizeOf(file: File): Long = file.length()
        override fun modifiedOf(file: File): Long = file.lastModified()
    }

    /** Nothing can be read: Android 13+ with no folder chosen. Every album shows its still cover. */
    object None : AnimatedCoverDirectory {
        override fun names(dir: File): List<String>? = null
        override fun stamp(dir: File): Long = 0L
        override fun sizeOf(file: File): Long = 0L
        override fun modifiedOf(file: File): Long = 0L
        override fun isUnreachable(dir: File): Boolean = true
    }
}

/** A folder that holds [songs] songs of one album, as the library has their paths. */
data class AlbumFolder(val path: String, val songs: Int)

/** Where the folders of an album come from: the library's own song paths (no file walk). A seam, so the tests count the questions. Called off the main thread. */
fun interface AlbumFolders {
    fun foldersOf(albumId: Long): List<AlbumFolder>

    /** No library behind it: every song is looked for in its own folder only. */
    object None : AlbumFolders {
        override fun foldersOf(albumId: Long): List<AlbumFolder> = emptyList()
    }
}

/**
 * Finds the animated cover of a song's **album** (B57.5 2.1): "if your album is this one, you take THAT cover". **Off the main thread**: [find] runs on [io]. No list row ever asks (only the
 * full player does, for the song that plays).
 *
 * - The album's folders are the folders its songs are in, from the library's paths ([AlbumFolders]: one query per album, kept until the library changes). The cover is looked for in them:
 *   `cover.mp4`, then `cover.webp`, then `cover.gif` ([AnimatedCoverNames.pick]). If more than one folder has one, **the folder holding the most songs of the album wins**; on a tie the
 *   song's own folder wins, then the path order. So a song that sits loose in another folder takes the cover of the folder its album is mostly in.
 * - A folder is listed once and the answer (a file or "none") is kept per folder, so an album of twelve songs costs one query and one listing per folder, then nothing. The cache is
 *   forgotten when the library changes ([invalidate], and a sync raises [LibraryChangeSignal]: an answer taken before the change is not believed after it) and when a folder's own stamp
 *   moves (a file put in or taken out, which a sync may not have seen yet). **A folder that could not be read is not remembered as "no cover"** (B57.5 2.2): the next question reads it again.
 * - A song with no album ([find] with `albumId <= 0`) is looked for in its own folder, as before.
 *
 * A folder is trusted by the same rule as the still folder art (`AlbumArtUtils.shouldTrustDirectoryArtwork`): a "Music" or "Downloads" folder mixes albums, and one album's `cover.mp4` must
 * not be played for another's songs. A folder outside the one the listener chose is skipped (the still cover stays when no folder of the album is inside it).
 *
 * One line per answer that took work (never for a cached one) goes to the log under the tag `AnimatedCover`: which folders were asked, what each said, and the winner.
 */
@Singleton
class AnimatedCoverFinder internal constructor(
    private val io: CoroutineDispatcher,
    private val directory: AnimatedCoverDirectory,
    private val trusts: (String) -> Boolean,
    /** The library's change counter ([LibraryChangeSignal.version]): an answer is kept only until it moves. */
    private val libraryVersion: () -> Long,
    /** Where an album's folders come from; without one a song is looked for in its own folder only. */
    private val albumFolders: AlbumFolders,
    private val log: (String) -> Unit,
) {
    /** A finder that looks in the song's own folder only and logs to Timber: what the tests of the folder rules build. */
    internal constructor(io: CoroutineDispatcher, directory: AnimatedCoverDirectory, trusts: (String) -> Boolean, libraryVersion: () -> Long) :
        this(io, directory, trusts, libraryVersion, AlbumFolders.None, DEFAULT_LOG)

    @Inject constructor(@ApplicationContext context: Context, signal: LibraryChangeSignal, store: AnimatedCoverFolderStore, musicDao: MusicDao) :
        this(
            Dispatchers.IO, AnimatedCoverFolder(context, store), AlbumArtUtils::shouldTrustDirectoryArtwork, { signal.version.value },
            AlbumFolders { albumId -> musicDao.getAlbumFolderCounts(albumId).map { AlbumFolder(it.folder, it.songCount) } },
            DEFAULT_LOG,
        )

    /** Why a folder gave the answer it did: what the log says, and what decides whether the answer may be remembered. */
    private enum class Outcome(val say: String, val remember: Boolean) {
        COVER("cover found", true),
        NOT_TRUSTED("not trusted (a folder that mixes albums)", true),
        OUTSIDE("outside the chosen folder", true),
        UNREADABLE("could not be read", false),
        NONE_THERE("no cover.mp4/webp/gif", true),
        EMPTY_FILE("the cover file is empty", true),
    }

    private class Entry(val stamp: Long, val libraryVersion: Long, val cover: AnimatedCover?, val outcome: Outcome)

    private class AlbumEntry(val libraryVersion: Long, val folders: List<AlbumFolder>)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_FOLDERS
    }

    private val albums = object : LinkedHashMap<Long, AlbumEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, AlbumEntry>?): Boolean = size > MAX_ALBUMS
    }

    /** How many times a folder was really listed: what a test counts to show that the answer is cached. */
    @Volatile
    internal var listings = 0
        private set

    /** How many times the library was asked for an album's folders: one per album until the library changes. */
    @Volatile
    internal var albumQueries = 0
        private set

    /**
     * The animated cover for the song at [songPath] of album [albumId] (the library's `album_id`; 0 or less when it has none), or null. Suspends on the IO dispatcher; safe to call from the
     * main thread.
     */
    suspend fun find(songPath: String, albumId: Long = 0L): AnimatedCover? = withContext(io) { findNow(songPath, albumId) }

    /** The same on the calling thread: for the tests, and for a caller that is already off the main thread. */
    internal fun findNow(songPath: String, albumId: Long = 0L): AnimatedCover? {
        // a streamed or content-only song has no folder
        if (!songPath.startsWith("/")) return null
        val own = File(songPath).parentFile ?: return null
        val trace = StringBuilder()
        var worked = false
        val version = libraryVersion()
        val ordered = if (albumId > 0L) {
            val known = foldersOfAlbum(albumId, version)?.also { if (it.second) worked = true }?.first.orEmpty()
            val all = if (known.any { it.path == own.path }) known else known + AlbumFolder(own.path, 0)
            // most songs of the album first; on a tie the song's own folder; then the path order
            all.sortedWith(compareByDescending<AlbumFolder> { it.songs }.thenBy { if (it.path == own.path) 0 else 1 }.thenBy { it.path })
        } else {
            listOf(AlbumFolder(own.path, 0))
        }
        var found: AnimatedCover? = null
        for (folder in ordered) {
            val result = coverIn(File(folder.path))
            if (!result.fromCache) worked = true
            trace.append(if (trace.isEmpty()) "" else "; ").append(folder.path).append(" (").append(folder.songs).append(" songs): ").append(result.outcome.say)
            if (result.cover != null) {
                found = result.cover
                break
            }
        }
        if (worked) {
            log("find album=$albumId song=${File(songPath).name} -> ${found?.file?.path ?: "no animated cover"} | $trace")
        }
        return found
    }

    /** The folders of [albumId] and whether this call asked the library (false: from the cache), or null when the library could not say. */
    private fun foldersOfAlbum(albumId: Long, version: Long): Pair<List<AlbumFolder>, Boolean>? {
        synchronized(albums) { albums[albumId] }?.let { if (it.libraryVersion == version) return it.folders to false }
        albumQueries++
        val folders = try {
            albumFolders.foldersOf(albumId)
        } catch (e: Exception) {
            return null
        }
        synchronized(albums) { albums[albumId] = AlbumEntry(version, folders) }
        return folders to true
    }

    private class FolderResult(val cover: AnimatedCover?, val outcome: Outcome, val fromCache: Boolean)

    private fun coverIn(dir: File): FolderResult {
        val key = dir.path
        val stamp = directory.stamp(dir)
        val version = libraryVersion()
        synchronized(cache) { cache[key] }?.let { if (it.stamp == stamp && it.libraryVersion == version) return FolderResult(it.cover, it.outcome, fromCache = true) }
        val (found, outcome) = list(dir)
        if (outcome.remember) synchronized(cache) { cache[key] = Entry(stamp, version, found, outcome) }
        return FolderResult(found, outcome, fromCache = false)
    }

    private fun list(dir: File): Pair<AnimatedCover?, Outcome> {
        listings++
        if (!trusts(dir.name)) return null to Outcome.NOT_TRUSTED
        val names = directory.names(dir) ?: return null to (if (directory.isUnreachable(dir)) Outcome.OUTSIDE else Outcome.UNREADABLE)
        val (name, kind) = AnimatedCoverNames.pick(names) ?: return null to Outcome.NONE_THERE
        val file = File(dir, name)
        val size = directory.sizeOf(file)
        if (size <= 0L) return null to Outcome.EMPTY_FILE
        return AnimatedCover(file, kind, size, directory.modifiedOf(file), directory.documentUriOf(file)) to Outcome.COVER
    }

    /** Every folder and album is looked at again the next time it is asked for. */
    fun invalidate() {
        synchronized(cache) { cache.clear() }
        synchronized(albums) { albums.clear() }
    }

    internal companion object {
        private val DEFAULT_LOG: (String) -> Unit = { Timber.tag(AnimatedCoverController.TAG).i(it) }

        /** An album folder per entry; a few hundred listings are a few KB. The player asks about one folder at a time, so this is only a bound. */
        const val MAX_FOLDERS = 128

        /** An album's folder list per entry: a handful of short strings each. */
        const val MAX_ALBUMS = 64
    }
}
