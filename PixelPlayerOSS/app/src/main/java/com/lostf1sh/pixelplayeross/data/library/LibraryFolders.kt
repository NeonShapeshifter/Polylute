package com.lostf1sh.pixelplayeross.data.library

import com.lostf1sh.pixelplayeross.utils.DirectoryRuleResolver

/**
 * B39-A9: the library includes, instead of excluding. Which folders hold the music is the listener's to say; nothing is in the library
 * unless its folder is included.
 *
 * **The model is the one that was already there, with one more rule.** The two persisted sets keep their meaning: `allowed` are folders
 * that are in, `blocked` are folders that are out, and the deepest one that covers a path decides ([DirectoryRuleResolver]). Until now a
 * path with no rule over it was in. [NOTHING_BY_DEFAULT], a blocked root of `/`, makes it out: it covers every path and is shallower than
 * anything the listener adds, so a folder is in only when an included folder (or an exception) is deeper than it. Everything that reads the
 * rules (the sync, every library query, the folders sheet) already asks the resolver and needs no change to understand it, and a library
 * that is not marked include-only reads as it always did.
 *
 * Pure: it knows paths and counts, not a database or a phone.
 */
object LibraryFolders {
    /** The blocked root that turns "everything unless excluded" into "nothing unless included". */
    const val NOTHING_BY_DEFAULT = "/"

    /** The rules as persisted: included roots (and exceptions under excluded ones) and excluded folders. */
    data class Rules(val allowed: Set<String>, val blocked: Set<String>) {
        /** Whether the rules say "nothing unless included". */
        val includeOnly: Boolean get() = NOTHING_BY_DEFAULT in blocked

        fun resolver() = DirectoryRuleResolver(allowed, blocked)

        /** Whether a song in [directory] is in the library under these rules. */
        fun includes(directory: String): Boolean = !resolver().isBlocked(clean(directory))
    }

    /** A folder offered to the listener, with how many songs it holds (the ones under it that are not in yet). */
    data class Proposal(val path: String, val songCount: Int)

    /**
     * The folders to include so that every song in [songDirectories] is in: for each top-level folder of its storage (`Music`, `Download`) the
     * lowest folder that holds all of its songs, never the storage itself (that would be "everything" again) unless a song sits right in it.
     * Songs in `Music/DAP music/Artist/Album/` give `Music/DAP music`, so an album added later under it is in too.
     */
    fun rootsFor(songDirectories: Collection<String>, storageRoots: List<String> = emptyList()): Set<String> {
        val clusters = LinkedHashMap<String, MutableList<List<String>>>()
        val rootOf = HashMap<String, String>()
        for (directory in songDirectories.map(::clean).filter { it.isNotEmpty() }.distinct()) {
            val storage = storageRootOf(directory, storageRoots)
            val below = directory.removePrefix(storage).trim('/').split('/').filter { it.isNotEmpty() }
            if (below.isEmpty()) {
                // a song right in the storage's root: only the whole storage holds it
                clusters.getOrPut(storage) { mutableListOf() }
                rootOf[storage] = storage
                continue
            }
            val key = storage + "/" + below.first()
            rootOf[key] = storage
            clusters.getOrPut(key) { mutableListOf() }.add(below)
        }
        val roots = LinkedHashSet<String>()
        for ((key, below) in clusters) {
            val storage = rootOf.getValue(key)
            if (key == storage) { roots += storage; continue }
            var common = below.first()
            for (other in below.drop(1)) {
                common = common.zip(other).takeWhile { (a, b) -> a == b }.map { it.first }
            }
            roots += storage + "/" + common.joinToString("/")
        }
        return roots
    }

    /**
     * What an install of 1.0 gets: the folders that hold the library's songs today become the included set, and the old exclusions stay
     * (they are deeper than the roots, so they still win). Null when the new rules would not give every song the answer the old rules give it,
     * or there is no song to build from: the old rules then stay as they are. The rules stay `allowed` and `blocked` as they were, plus the roots and
     * [NOTHING_BY_DEFAULT]; nothing is removed.
     */
    fun migrate(old: Rules, songDirectories: Collection<String>, storageRoots: List<String> = emptyList()): Rules? {
        val directories = songDirectories.map(::clean).filter { it.isNotEmpty() }.distinct()
        if (directories.isEmpty()) return null
        if (old.includeOnly) return null
        val roots = rootsFor(directories, storageRoots)
        val migrated = Rules(allowed = old.allowed + roots, blocked = old.blocked + NOTHING_BY_DEFAULT)
        val before = old.resolver()
        val after = migrated.resolver()
        // every song that is in stays in, and every one that was out stays out: the library is the same
        if (directories.any { before.isBlocked(it) != after.isBlocked(it) }) return null
        return migrated
    }

    /** [folders] included: added to the roots, and an exclusion of exactly that folder lifted. Exclusions inside it stay. */
    fun including(rules: Rules, folders: Collection<String>): Rules {
        val added = folders.map(::clean).filter { it.isNotEmpty() }.toSet()
        return Rules(
            allowed = rules.allowed + added,
            blocked = rules.blocked.filterNot { clean(it) in added }.toSet(),
        )
    }

    /**
     * The folders that hold audio and are not in the library, ready to be added: [audioDirectories] maps each folder MediaStore knows with
     * audio in it to its song count. Included ones are left out; the rest are gathered as [rootsFor] gathers them, each with the count under it.
     */
    fun proposals(
        audioDirectories: Map<String, Int>,
        rules: Rules,
        storageRoots: List<String> = emptyList(),
    ): List<Proposal> {
        // "not in the library yet" is judged as include-only: with the old rules everything would count as in already
        val judged = if (rules.includeOnly) rules else rules.copy(blocked = rules.blocked + NOTHING_BY_DEFAULT)
        val outside = audioDirectories.mapKeys { clean(it.key) }.filter { (directory, count) ->
            directory.isNotEmpty() && count > 0 && !judged.includes(directory) && !hidden(directory)
        }
        if (outside.isEmpty()) return emptyList()
        return rootsFor(outside.keys, storageRoots).map { root ->
            Proposal(root, outside.filterKeys { it == root || it.startsWith("$root/") }.values.sum())
        }.sortedBy { it.path.lowercase() }
    }

    private fun hidden(directory: String): Boolean = directory.contains("/.")

    internal fun clean(path: String): String = if (path.length > 1 && path.endsWith("/")) path.dropLast(1) else path

    /** The storage a path is on: the longest known root that holds it, else the usual forms of one. */
    internal fun storageRootOf(path: String, known: List<String>): String {
        known.map(::clean).filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }?.let { return it }
        Regex("^(/storage/emulated/\\d+|/storage/[^/]+|/sdcard|/mnt/[^/]+/[^/]+)").find(path)?.let { return it.value }
        return "/" + path.trim('/').split('/').first()
    }
}
