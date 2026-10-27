package com.lostf1sh.pixelplayeross.data.library

import android.content.Context
import android.media.MediaScannerConnection
import android.provider.MediaStore
import androidx.core.content.edit
import com.lostf1sh.pixelplayeross.utils.DirectoryRuleResolver
import com.lostf1sh.pixelplayeross.utils.isMediaRowIncomplete
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * B40-F3: the phone's side of [LibraryFileWalk]: reads what MediaStore holds, walks the included folders, asks Android's media scanner to
 * index what it is missing, and waits for it, so that the usual sync that follows reads the new rows.
 *
 * Runs inside the sync worker (off the main thread), for "Update library" and the pull to refresh always, and for the periodic
 * maintenance at most every [PERIODIC_MIN_INTERVAL_MS]. **A failure here never stops the sync**: it is logged and the sync goes on with
 * what MediaStore has. Nothing leaves the phone.
 *
 * A file the scanner declines (a corrupt one, one it does not know) is remembered by path, size and time, so it is not asked for again
 * on every run, and is asked again when the file changes ([Memory]). So is a file Android indexed **without a duration** (it read it
 * before the copy was finished) once it has been asked to read it again: if it still has none, it is a file Android cannot read, and
 * asking on every walk would not change that.
 *
 * Remembered files are skipped **while walking**, so they never use up the walk's limit, and a walk that a limit stopped continues
 * where it stopped ([LibraryFileWalk.Result.pending], kept in [Memory]); a file behind a thousand that Android declines is reached.
 */
class LibraryFileScanner(
    private val context: Context,
    private val memory: Memory = Memory.persistent(context),
    private val fs: LibraryFileWalk.Fs = LibraryFileWalk.JavaFs,
    private val limits: LibraryFileWalk.Limits = LibraryFileWalk.Limits(),
    private val now: () -> Long = System::currentTimeMillis,
    /** Asks Android to index [paths] and returns the paths it did not add, within the time it is given. Replaceable for the tests. */
    private val scan: suspend (paths: List<String>, timeoutMs: Long) -> Set<String> = { paths, timeoutMs -> scanThroughAndroid(context, paths, timeoutMs) },
    /** The files MediaStore holds under [roots]. Replaceable for the tests. */
    private val readIndex: (roots: List<String>) -> Map<String, LibraryFileWalk.Indexed> = { roots -> readMediaStoreIndex(context, roots) },
) {
    enum class Outcome { NOT_DUE, NO_FOLDERS, NOTHING_NEW, ASKED }

    data class Report(
        val outcome: Outcome,
        val walk: LibraryFileWalk.Result? = null,
        val asked: Int = 0,
        val declined: Int = 0,
        val millis: Long = 0L,
    )

    /**
     * @param dueOnly true for a sync that is not the listener's own ask: the walk runs only if the last one is [PERIODIC_MIN_INTERVAL_MS] old.
     */
    suspend fun run(rules: LibraryFolders.Rules, dueOnly: Boolean = false): Report {
        val started = now()
        val roots = LibraryFileWalk.roots(rules)
        if (roots.isEmpty()) return Report(Outcome.NO_FOLDERS)
        if (dueOnly && started - memory.lastWalkAtMs() < PERIODIC_MIN_INTERVAL_MS) return Report(Outcome.NOT_DUE)
        // a walk a limit stopped goes on from there, if the folders are the ones it was walking
        val signature = roots.joinToString("|")
        val resume = memory.resumePoint(signature)
        val walk = LibraryFileWalk.walk(rules, readIndex(roots), fs, limits, now, skip = memory::declined, resume = resume)
        memory.noteWalk(started)
        memory.saveResumePoint(signature, if (walk.truncated) walk.pending else null)
        val toAsk = walk.candidates
        Timber.tag(TAG).i(
            "Folder walk: %d folders, %d audio files, %d to ask for%s (%d already declined, skipped), %d ms%s",
            walk.directories, walk.audioFiles, toAsk.size, LibraryFileWalk.reasons(toAsk), walk.skippedKnown, walk.elapsedMs,
            when (walk.stoppedBy) {
                LibraryFileWalk.Limit.CANDIDATES -> ", stopped at the limit on files to ask for, the next walk goes on from there"
                LibraryFileWalk.Limit.AUDIO_FILES -> ", stopped at the limit on files looked at, the next walk goes on from there"
                LibraryFileWalk.Limit.TIME -> ", stopped at the time limit, the next walk goes on from there"
                null -> if (walk.truncated) ", stopped at a limit, the next walk goes on from there" else ""
            },
        )
        if (toAsk.isEmpty()) return Report(Outcome.NOTHING_NEW, walk, declined = walk.skippedKnown, millis = now() - started)
        val timeoutMs = (SCAN_BASE_MS + SCAN_PER_FILE_MS * toAsk.size).coerceAtMost(SCAN_MAX_MS)
        val declined = scan(toAsk.map { it.path }, timeoutMs)
        // declined by Android, or a row it had without a duration: asked once, and remembered until the file changes
        toAsk.filter { it.path in declined || it.why == LibraryFileWalk.Why.INCOMPLETE }.forEach(memory::decline)
        Timber.tag(TAG).i("Asked Android to index %d file(s); it declined %d", toAsk.size, declined.size)
        return Report(Outcome.ASKED, walk, asked = toAsk.size, declined = declined.size, millis = now() - started)
    }

    /** What was declined, and when the walk last ran. */
    interface Memory {
        fun lastWalkAtMs(): Long
        fun noteWalk(atMs: Long)
        fun declined(candidate: LibraryFileWalk.Candidate): Boolean
        fun decline(candidate: LibraryFileWalk.Candidate)

        /** Where a walk stopped at a limit (for the folders named by [signature]), or null to start from the folders. */
        fun resumePoint(signature: String): List<String>? = null

        /** Keeps (or, with null, forgets) where a walk stopped. */
        fun saveResumePoint(signature: String, pending: List<String>?) = Unit

        companion object {
            fun persistent(context: Context): Memory {
                val prefs = context.getSharedPreferences("library_file_walk", Context.MODE_PRIVATE)
                return object : Memory {
                    override fun lastWalkAtMs(): Long = prefs.getLong(LAST_WALK, 0L)
                    override fun noteWalk(atMs: Long) = prefs.edit { putLong(LAST_WALK, atMs) }
                    override fun resumePoint(signature: String): List<String>? =
                        prefs.getString(RESUME_FOR, null)?.takeIf { it == signature }
                            ?.let { prefs.getString(RESUME_PENDING, null)?.split('\n')?.filter { it.isNotEmpty() } }
                    override fun saveResumePoint(signature: String, pending: List<String>?) = prefs.edit {
                        if (pending.isNullOrEmpty()) {
                            remove(RESUME_FOR)
                            remove(RESUME_PENDING)
                        } else {
                            putString(RESUME_FOR, signature)
                            // bounded: a very large tree keeps the first folders; the walk after the last one starts from the roots again
                            putString(RESUME_PENDING, pending.take(MAX_RESUME_FOLDERS).joinToString("\n"))
                        }
                    }
                    private fun key(c: LibraryFileWalk.Candidate) = "d:${c.path}"
                    private fun value(c: LibraryFileWalk.Candidate) = "${c.size}|${c.modifiedMs}"
                    override fun declined(candidate: LibraryFileWalk.Candidate): Boolean = prefs.getString(key(candidate), null) == value(candidate)
                    override fun decline(candidate: LibraryFileWalk.Candidate) {
                        // bounded: a phone full of files the scanner declines does not grow this without end
                        if (prefs.all.size > MAX_DECLINED) prefs.edit { prefs.all.keys.filter { it.startsWith("d:") }.take(MAX_DECLINED / 2).forEach(::remove) }
                        prefs.edit { putString(key(candidate), value(candidate)) }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "LibraryFileScanner"
        private const val LAST_WALK = "last_walk_ms"
        private const val RESUME_FOR = "resume_for"
        private const val RESUME_PENDING = "resume_pending"
        private const val MAX_RESUME_FOLDERS = 3_000
        private const val MAX_DECLINED = 500

        /** The periodic maintenance walks the folders at most this often (a walk that finds nothing new costs a listing per folder). */
        const val PERIODIC_MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

        private const val SCAN_BASE_MS = 5_000L
        private const val SCAN_PER_FILE_MS = 40L
        private const val SCAN_MAX_MS = 60_000L

        /** MediaStore's view of the files under [roots]: path to size and modified time. One query; no row is read for anything else. */
        fun readMediaStoreIndex(context: Context, roots: List<String>): Map<String, LibraryFileWalk.Indexed> {
            val index = HashMap<String, LibraryFileWalk.Indexed>()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.MIME_TYPE,
            )
            runCatching {
                context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { cursor ->
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataCol) ?: continue
                        if (roots.none { root -> path.startsWith("$root/", ignoreCase = true) }) continue
                        // a row the library's sync would leave out for want of metadata (Android had not read the file yet) is incomplete
                        val incomplete = isMediaRowIncomplete(
                            durationMs = if (cursor.isNull(durationCol)) null else cursor.getLong(durationCol),
                            title = cursor.getString(titleCol),
                            mimeType = cursor.getString(mimeCol),
                            data = path,
                        )
                        index[path] = LibraryFileWalk.Indexed(cursor.getLong(sizeCol), cursor.getLong(dateCol), complete = !incomplete)
                    }
                }
            }.onFailure { Timber.tag(TAG).w(it, "Could not read MediaStore's view of the folders") }
            return index
        }

        /** Asks Android's media scanner to index [paths]; returns the ones it did not add. Waits at most [timeoutMs]; the ones it never answered for count as declined. */
        suspend fun scanThroughAndroid(context: Context, paths: List<String>, timeoutMs: Long): Set<String> {
            val declined = LinkedHashSet<String>()
            val answered = HashSet<String>()
            val finished = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Unit> { cont ->
                    var remaining = paths.size
                    MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { path, uri ->
                        synchronized(answered) {
                            answered += path
                            if (uri == null) declined += path
                            if (--remaining <= 0 && cont.isActive) cont.resume(Unit)
                        }
                    }
                }
            }
            if (finished == null) Timber.tag(TAG).w("Android's scanner answered for %d of %d file(s) in %d ms", answered.size, paths.size, timeoutMs)
            return synchronized(answered) { declined + paths.filter { it !in answered } }
        }
    }
}
