package com.lostf1sh.pixelplayeross.data.worker

import com.lostf1sh.pixelplayeross.data.database.LibraryItemEntity
import com.lostf1sh.pixelplayeross.data.identity.ScannedItem
import java.io.File

/**
 * B41-fix1 (B10): which scanned rows an incremental sync must read again although MediaStore says they did not change, and the record of the
 * ones it has already dealt with.
 *
 * A row is pending when it has no projection or no durable item behind its locator (it needs work), or when the evidence the library holds
 * for it (`stable_key`, `file_signature`) disagrees with what MediaStore says now. B41 kept that second kind pending for as long as the
 * disagreement lasted, and some of them never stop disagreeing after being read (the HONOR logged `MediaStore raw=26, songsToProcess=26,
 * Processing 26 songs for upsert. Hash: -266633018` on every incremental sync, the same hash each time, about half a second each): nothing
 * recorded that they had been seen.
 *
 * Now what MediaStore said when the row was last handled is recorded ([HandledEvidence]), and a row whose evidence disagrees is pending only
 * until it has been handled **for that evidence**. A real change to it (a new fingerprint or a new signature in MediaStore) is a different
 * record, so it is pending again. The identity key is not touched (`stable_key` is not rewritten to make a disagreement go away): this only
 * remembers what was seen.
 */
internal object PendingProjections {
    /**
     * @param pending the MediaStore ids to read although their date says nothing changed.
     * @param disagreeing what the scan said about each row whose evidence disagrees with the library's, for the record: what [HandledEvidence]
     *   keeps once the sync has written its projections.
     */
    class Result(val pending: Set<Long>, val disagreeing: Map<Long, String>)

    fun compute(
        scanned: List<ScannedItem>,
        localSongIds: Set<Long>,
        knownByLocator: Map<out Long?, LibraryItemEntity>,
        handled: Map<Long, String>,
    ): Result {
        val pending = HashSet<Long>()
        val disagreeing = HashMap<Long, String>()
        for (item in scanned) {
            val known = knownByLocator[item.mediaStoreId]
            if (item.mediaStoreId !in localSongIds || known == null) {
                pending += item.mediaStoreId
                continue
            }
            val disagrees = known.stableKey != item.fingerprint ||
                (known.fileSignature.isNotEmpty() && known.fileSignature != item.fileSignature)
            if (!disagrees) continue
            val token = HandledEvidence.token(item)
            disagreeing[item.mediaStoreId] = token
            if (handled[item.mediaStoreId] != token) pending += item.mediaStoreId
        }
        return Result(pending, disagreeing)
    }
}

/**
 * What MediaStore said about a row (its fingerprint and signature) when the sync last handled a disagreement with the library's evidence for
 * it: one line per row, `locator<TAB>token`. A small file in the app's own storage, written whole and moved into place, so a sync that dies
 * in the middle leaves the one before it. Missing or damaged, it is empty: every disagreement is handled once more, which is what B41 did every
 * time.
 */
internal class HandledEvidence(private val file: File) {
    fun load(): Map<Long, String> = runCatching {
        if (!file.exists()) return emptyMap()
        file.readLines().mapNotNull { line ->
            val cut = line.indexOf('\t')
            if (cut <= 0) return@mapNotNull null
            val id = line.substring(0, cut).toLongOrNull() ?: return@mapNotNull null
            id to line.substring(cut + 1)
        }.toMap()
    }.getOrDefault(emptyMap())

    fun save(entries: Map<Long, String>) {
        if (entries.isEmpty()) {
            file.delete()
            return
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(entries.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" })
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        const val FILE_NAME = "sync_handled_evidence.tsv"

        /** What MediaStore said, as one value: a change to either part is a different record. */
        fun token(item: ScannedItem): String = "${item.fingerprint}|${item.fileSignature}"
    }
}
