package com.lostf1sh.pixelplayeross.utils

import android.content.Context
import java.security.MessageDigest

/**
 * B61 (item 1): **what the folder-artwork step decided about a whole folder, remembered between syncs.**
 *
 * The step asks, for every song of a folder whose cover is not cached, "does a folder image apply to it?": it opens the file for its headers (is there an embedded picture? that one wins)
 * and, for a song without one, asks the folder for a still image, and then the chosen animated-cover folder (a `DocumentsContract` listing of ~100 ms on the HONOR). When the answer is
 * "nothing to install" it wrote nothing down, and a song with an embedded picture has no cached file either until it is shown (B59 item 2 stopped extracting during a sync), so the same
 * question was asked again by **every** sync, for every such song and folder. After the 11-07 Full Rescan that was 14-25 s of every "Update library" on the HONOR (it had been 1.4-2.2 s).
 *
 * A folder is **settled** when a pass over it installed nothing, took nothing away, hit no error and had every answer it asked for. It is then remembered under a signature of everything
 * the answer depends on ([signature]): the settings that apply to it, the folder's own modification time (a file put in or taken out of it, `cover.jpg`, `discN.*` or an animated cover,
 * changes it, and it is read from the disk, not from the provider), each song of it (its id, its file's MediaStore modification time, its artwork address and disc) and where the
 * animated covers are read from. If any of it differs, the folder is looked at again, as it always was. Folders where something is installed are never settled: their songs keep being
 * checked (the file's own time and size are part of what is installed), which is what a sync did before.
 *
 * A Full Rescan forgets all of it ([clear]).
 */
internal object FolderArtworkDecisions {
    private const val PREFS = "folder_art_decisions"
    private const val VERSION = "v1"

    /** What the decision for a folder depends on, of one song. [artUri] is the address its row holds: a local (lazy) address stands for "nothing chosen"; any other is kept whole. */
    class Member(val id: Long, val modified: Long, val artUri: String?, val disc: Int)

    /**
     * The signature of a folder's situation: the same text exactly when nothing the decision depends on changed. [directoryStamp] is the folder's own modification time on the disk; 0
     * means it could not be read, and then no decision is remembered ([null]).
     */
    fun signature(
        directory: String, multiDisc: Boolean, directoryStamp: Long, animatedIdentity: String, members: List<Member>,
    ): String? {
        if (directoryStamp <= 0L) return null
        val text = StringBuilder(VERSION).append('|').append(directory).append('|').append(multiDisc).append('|').append(directoryStamp).append('|').append(animatedIdentity)
        for (member in members.sortedBy { it.id }) {
            val kind = when {
                member.artUri == null -> "n"
                LocalArtworkUri.isLocalArtworkUri(member.artUri) -> "l"
                else -> "x:" + member.artUri
            }
            text.append('\n').append(member.id).append(',').append(member.modified).append(',').append(kind).append(',').append(member.disc)
        }
        return MessageDigest.getInstance("SHA-1").digest(text.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** True when [directory] was settled under exactly this [signature] by an earlier pass. A memory read of the preferences. */
    fun isSettled(context: Context, directory: String, signature: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(directory, null) == signature

    /** The settled folders of one sync, written **once** at its end (like the provenance, [AlbumArtUtils.FolderArtworkProvenanceBatch]). */
    class Batch {
        private val settled = LinkedHashMap<String, String>()
        private val unsettled = LinkedHashSet<String>()
        private val seen = LinkedHashSet<String>()

        /** Every folder the sync looked at, settled or not: a folder that is not seen any more is dropped from the memory. */
        fun saw(directory: String) {
            seen += directory
        }

        fun settle(directory: String, signature: String) {
            settled[directory] = signature
        }

        /** Not settled (any more): what was remembered for it goes. */
        fun unsettle(directory: String) {
            settled.remove(directory)
            unsettled += directory
        }

        /** One commit. False when it could not be written (the next sync decides again, which is only slower). */
        @android.annotation.SuppressLint("UseKtx") // commit reports a failed write; KTX discards that result.
        fun flush(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stale = prefs.all.keys.filter { it !in seen }
            if (settled.isEmpty() && unsettled.isEmpty() && stale.isEmpty()) return true
            val written = prefs.edit().apply {
                stale.forEach { remove(it) }
                unsettled.forEach { remove(it) }
                settled.forEach { (directory, signature) -> putString(directory, signature) }
            }.commit()
            commits.incrementAndGet()
            return written
        }
    }

    /** How many times the decisions were committed to disk (read by the tests: one per sync at most). */
    internal val commits = java.util.concurrent.atomic.AtomicInteger()

    /** Forgets every decision: a Full Rescan decides everything again. */
    @android.annotation.SuppressLint("UseKtx")
    fun clear(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
}
