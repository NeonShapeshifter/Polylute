package com.lostf1sh.pixelplayeross.data.library

import java.io.File

/**
 * B40-F3: the library finds files Android has not indexed yet.
 *
 * Polylute read only MediaStore, and Android indexes a file that was copied in (from a PC over USB, by a download app) late: sometimes
 * after minutes, sometimes not until a reboot. Another player that walks the folders and asks Android to index what it finds shows the
 * file at once, and after it has run, every other player sees it too. This is that: **walk the included folders**, find the audio files
 * MediaStore does not have (or has stale), and hand them to Android's own scanner ([LibraryFileScanner]), which then makes them rows
 * the usual sync reads.
 *
 * What it will not do, by construction:
 *  - walk a folder that is not included: the roots are the included folders ([LibraryFolders.Rules.includeOnly]); under the old
 *    "everything unless excluded" rules there is no root, so nothing is walked (the walk of a whole storage is not bounded);
 *  - go into an excluded folder (the deepest rule over a path decides, [LibraryFolders.Rules.includes]), a hidden one, or one with a
 *    `.nomedia` file, which Android's scanner would not index either;
 *  - run without a bound: [Limits] stops it by files, by candidates and by time, and says it stopped ([Result.truncated]).
 *
 * Pure over [Fs], so it runs on a real temp folder in the tests; the phone's version of [Fs] is `java.io.File`.
 */
object LibraryFileWalk {
    /** The audio file types Android's scanner indexes, by extension (lower case). */
    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "wma", "ape", "wv", "dsf", "dff", "mka",
        "mp2", "mpc", "amr", "3gp", "mid", "midi",
    )

    /**
     * What MediaStore holds for a file: its size and its modified time in seconds, and whether the row is [complete] for the library
     * (Android read its duration and title; see [com.lostf1sh.pixelplayeross.utils.isMediaRowIncomplete]).
     */
    data class Indexed(val size: Long, val modifiedSeconds: Long, val complete: Boolean = true)

    /** MISSING: MediaStore has no row. STALE: it has one for another size or time. INCOMPLETE: it has the right one, without a duration. */
    enum class Why { MISSING, STALE, INCOMPLETE }

    data class Candidate(val path: String, val size: Long, val modifiedMs: Long, val why: Why)

    /**
     * Why the walk asks about [candidates], for the log: " (1 STALE)", " (2 MISSING, 1 INCOMPLETE)", or nothing when there are none. A file
     * that is asked about on every walk shows up here with its reason (B41-fix1, B11: the one that was reported has not been seen asking twice).
     */
    fun reasons(candidates: List<Candidate>): String =
        if (candidates.isEmpty()) "" else candidates.groupingBy { it.why }.eachCount().entries.sortedBy { it.key }
            .joinToString(", ", prefix = " (", postfix = ")") { "${it.value} ${it.key}" }

    /** Which of the [Limits] stopped a walk. */
    enum class Limit { CANDIDATES, AUDIO_FILES, TIME }

    data class Limits(
        val maxAudioFiles: Int = 60_000,
        val maxCandidates: Int = 1_000,
        val maxMillis: Long = 20_000L,
    )

    data class Result(
        val candidates: List<Candidate>,
        val directories: Int,
        val audioFiles: Int,
        /** Directories not entered: excluded, hidden, `.nomedia`, or unreadable. */
        val skippedDirectories: Int,
        /** A limit stopped the walk before it finished: what is left is found by the next one. */
        val truncated: Boolean,
        val elapsedMs: Long,
        /** Candidates left out because [walk]'s `skip` knew them (declined before): they did not count toward the limit. */
        val skippedKnown: Int = 0,
        /**
         * Where a truncated walk stopped: the directories not walked yet, in order, the interrupted one first, written with the last
         * file it had dealt with (`directory`, [CURSOR], `name`). Handed back to [walk] as `resume`, the next walk continues there
         * instead of starting from the roots again, and inside a folder from the file after that one. Empty when the walk finished.
         */
        val pending: List<String> = emptyList(),
        /** The limit that stopped a truncated walk (null when it finished). */
        val stoppedBy: Limit? = null,
    )

    /** One entry of a directory. */
    data class Entry(val name: String, val isDirectory: Boolean, val size: Long, val modifiedMs: Long)

    interface Fs {
        /** The entries of [directory], or null when it cannot be listed (no permission, gone). */
        fun list(directory: String): List<Entry>?
    }

    /** The phone's file system. A listing that fails is null, never an exception. */
    object JavaFs : Fs {
        override fun list(directory: String): List<Entry>? = runCatching {
            File(directory).listFiles()?.map { f -> Entry(f.name, f.isDirectory, if (f.isFile) f.length() else 0L, f.lastModified()) }
        }.getOrNull()
    }

    /** The folders the walk starts from: the included ones, and only under "nothing unless included". */
    fun roots(rules: LibraryFolders.Rules): List<String> =
        if (!rules.includeOnly) emptyList() else rules.allowed.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct().sorted()

    /**
     * @param skip files already asked about and declined (unchanged since): left out **while walking**, so they never count toward
     *        [Limits.maxCandidates]. Filtered afterwards they used to fill the cap: with 1,000 of them first in walk order the file
     *        behind them was never reached, on any number of updates.
     * @param resume where the previous walk stopped ([Result.pending]); null starts from the included folders.
     */
    fun walk(
        rules: LibraryFolders.Rules,
        index: Map<String, Indexed>,
        fs: Fs = JavaFs,
        limits: Limits = Limits(),
        now: () -> Long = System::currentTimeMillis,
        skip: (Candidate) -> Boolean = { false },
        resume: List<String>? = null,
    ): Result {
        val started = now()
        val byLowerPath = HashMap<String, Indexed>(index.size)
        index.forEach { (path, value) -> byLowerPath[path.lowercase()] = value }
        val candidates = ArrayList<Candidate>()
        var directories = 0
        var audioFiles = 0
        var skipped = 0
        var truncated = false
        var skippedKnown = 0
        var stoppedBy: Limit? = null
        val seen = HashSet<String>()

        /** The limit that is reached, if any. The cap on candidates also counts here, between folders; inside one it is met when a candidate would be added. */
        fun limitHit(): Limit? = when {
            now() - started > limits.maxMillis -> Limit.TIME
            audioFiles >= limits.maxAudioFiles -> Limit.AUDIO_FILES
            candidates.size >= limits.maxCandidates -> Limit.CANDIDATES
            else -> null
        }

        val pending = ArrayDeque<String>()
        // the included roots in order (or where the last walk stopped); a root under another is reached by the outer one (or on its own
        // when the outer is excluded around it)
        (resume?.takeIf { it.isNotEmpty() } ?: roots(rules)).forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            val hit = limitHit()
            if (hit != null) {
                truncated = true
                stoppedBy = hit
                break
            }
            val head = pending.removeFirst()
            val directory = head.substringBefore(CURSOR)
            val after: String? = if (CURSOR in head) head.substringAfter(CURSOR) else null
            if (!seen.add(directory)) continue
            if (!rules.includes(directory)) {
                skipped++
                continue
            }
            val entries = fs.list(directory)
            if (entries == null) {
                skipped++
                continue
            }
            if (entries.any { it.name == ".nomedia" }) {
                skipped++
                continue
            }
            directories++
            // in name order, so that "the file after this one" means the same thing to the next walk; the sub-folders are queued first,
            // so a folder interrupted below can be listed again without losing them
            val ordered = entries.sortedBy { it.name }
            for (entry in ordered) {
                if (!entry.isDirectory) continue
                if (entry.name.startsWith(".")) skipped++ else pending.addLast("$directory/${entry.name}")
            }
            var lastDealtWith: String? = after
            for (entry in ordered) {
                if (entry.isDirectory) continue
                if (after != null && entry.name <= after) continue   // dealt with by the walk that stopped here
                if (entry.name.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) { lastDealtWith = entry.name; continue }
                // The time and file limits are met per file, not only between folders (B40-fix2): a folder of thousands of files that are all
                // indexed or all declined used to be read to its end whatever the budget. At least one file is dealt with per walk, so a
                // walk that resumes here always moves on.
                if (audioFiles > 0) {
                    val over = limitHit()
                    if (over == Limit.TIME || over == Limit.AUDIO_FILES) {
                        truncated = true
                        stoppedBy = over
                        pending.addFirst(if (lastDealtWith == null) directory else "$directory$CURSOR$lastDealtWith")
                        break
                    }
                }
                audioFiles++
                val path = "$directory/${entry.name}"
                val held = index[path] ?: byLowerPath[path.lowercase()]
                val why = when {
                    held == null -> Why.MISSING
                    held.size != entry.size || kotlin.math.abs(held.modifiedSeconds - entry.modifiedMs / 1000L) > MODIFIED_SLACK_SECONDS -> Why.STALE
                    !held.complete -> Why.INCOMPLETE
                    else -> null
                }
                if (why == null) { lastDealtWith = entry.name; continue }
                val candidate = Candidate(path, entry.size, entry.modifiedMs, why)
                if (skip(candidate)) {
                    skippedKnown++
                    lastDealtWith = entry.name
                    continue
                }
                if (candidates.size >= limits.maxCandidates) {
                    // the cap is reached inside this folder: the rest of it is for the next walk, which starts at the next file
                    truncated = true
                    stoppedBy = Limit.CANDIDATES
                    pending.addFirst(if (lastDealtWith == null) directory else "$directory$CURSOR$lastDealtWith")
                    break
                }
                candidates += candidate
                lastDealtWith = entry.name
            }
            if (truncated) break
        }
        return Result(
            candidates, directories, audioFiles, skipped, truncated, now() - started, skippedKnown,
            pending = if (truncated) pending.toList() else emptyList(),
            stoppedBy = stoppedBy,
        )
    }

    /** Separates a folder from the name of the last file dealt with in it, in [Result.pending]. A printable symbol (it is kept in a preferences file, whose XML takes no control characters) that no path has. */
    const val CURSOR = '\u241E'

    /** A file system and MediaStore round modified times differently; this much apart is the same time. */
    const val MODIFIED_SLACK_SECONDS = 2L
}
