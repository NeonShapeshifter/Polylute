package com.lostf1sh.pixelplayeross.data.musicinfo

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * B40-F4: what was downloaded, kept on the phone, within a limit the listener sets.
 *
 * One folder (`files/music-info/`) and one index. A picture is a file; a description is text kept in the index; a lookup that found nothing
 * is remembered for [MISSING_TTL_MS] so the app does not ask again at every start. When the files are over the limit the one **used longest
 * ago** goes first. [clear] ("Delete downloaded data") removes the folder and the index. Reading never needs the network: offline the app
 * shows what is here, and nothing breaks when it is not.
 *
 * Thread-safe (every call takes the cache's lock); the index is rewritten after each change.
 */
class MusicInfoCache(
    private val dir: File,
    private val limitBytes: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val prefix: String) { COVER("cover"), ARTIST_PHOTO("photo"), ABOUT("about") }

    /** What a lookup finds: the file (pictures), and the notes kept with it (who made it, where it came from, or the description itself). */
    class Hit(val file: File?, val meta: String?)

    private class Entry(val kind: Kind, val name: String, val file: String?, var bytes: Long, var lastAccessMs: Long, val meta: String?, val missingUntilMs: Long)

    private val lock = Any()
    private val index = LinkedHashMap<String, Entry>()
    private var loaded = false

    private fun id(kind: Kind, key: String): String = kind.prefix + "_" + sha1(key.trim().lowercase())

    private fun indexFile() = File(dir, INDEX)

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val text = indexFile().takeIf { it.isFile }?.readText() ?: return
            val root = JSONObject(text).optJSONObject("entries") ?: return
            root.keys().forEach { name ->
                val e = root.getJSONObject(name)
                val kind = Kind.entries.firstOrNull { it.name == e.optString("kind") } ?: return@forEach
                val file = e.optString("file").takeIf { it.isNotEmpty() }
                // an entry whose file is gone (the system cleared it) is not an entry
                if (file != null && !File(dir, file).isFile) return@forEach
                index[name] = Entry(kind, name, file, e.optLong("bytes"), e.optLong("last"), e.optString("meta").takeIf { it.isNotEmpty() }, e.optLong("missing"))
            }
        }
    }

    private fun save() {
        runCatching {
            dir.mkdirs()
            val entries = JSONObject()
            index.values.forEach { e ->
                entries.put(
                    e.name,
                    JSONObject().put("kind", e.kind.name).put("file", e.file ?: "").put("bytes", e.bytes).put("last", e.lastAccessMs)
                        .put("meta", e.meta ?: "").put("missing", e.missingUntilMs),
                )
            }
            val tmp = File(dir, "$INDEX.tmp")
            tmp.writeText(JSONObject().put("entries", entries).toString())
            if (!tmp.renameTo(indexFile())) { indexFile().writeText(tmp.readText()); tmp.delete() }
        }
    }

    fun get(kind: Kind, key: String): Hit? = synchronized(lock) {
        load()
        val entry = index[id(kind, key)] ?: return null
        if (entry.missingUntilMs > 0) return null
        entry.lastAccessMs = now()
        Hit(entry.file?.let { File(dir, it) }, entry.meta)
    }

    /** The last lookup for this key found nothing, and that is still recent. */
    fun isMissing(kind: Kind, key: String): Boolean = synchronized(lock) {
        load()
        val entry = index[id(kind, key)] ?: return false
        entry.missingUntilMs > now()
    }

    fun putFile(kind: Kind, key: String, data: ByteArray, extension: String, meta: String?): File = synchronized(lock) {
        load()
        dir.mkdirs()
        val name = id(kind, key)
        val file = File(dir, "$name.$extension")
        file.writeBytes(data)
        index[name] = Entry(kind, name, file.name, data.size.toLong(), now(), meta, 0L)
        enforceLimit(keep = name)
        save()
        file
    }

    fun putText(kind: Kind, key: String, meta: String) = synchronized(lock) {
        load()
        val name = id(kind, key)
        index[name] = Entry(kind, name, null, meta.length.toLong(), now(), meta, 0L)
        enforceLimit(keep = name)
        save()
    }

    fun putMissing(kind: Kind, key: String) = synchronized(lock) {
        load()
        val name = id(kind, key)
        index[name] = Entry(kind, name, null, 0L, now(), null, now() + MISSING_TTL_MS)
        while (index.values.count { it.missingUntilMs > 0 } > MAX_MISSING_MARKS) {
            index.values.filter { it.missingUntilMs > 0 }.minByOrNull { it.lastAccessMs }?.let { index.remove(it.name) } ?: break
        }
        save()
    }

    fun usedBytes(): Long = synchronized(lock) {
        load()
        index.values.sumOf { it.bytes }
    }

    /** Whether [path] is a file this cache wrote (the artist's stored picture points at one). */
    fun owns(path: String?): Boolean = path != null && runCatching { File(path).canonicalPath.startsWith(dir.canonicalPath + File.separator) }.getOrDefault(false)

    /** "Delete downloaded data": everything, the index included. Returns the bytes freed. */
    fun clear(): Long = synchronized(lock) {
        load()
        val freed = index.values.sumOf { it.bytes }
        index.clear()
        dir.deleteRecursively()
        freed
    }

    private fun enforceLimit(keep: String) {
        val limit = limitBytes()
        if (limit <= 0L) return // Downloaded content is retained until the listener deletes it.
        var total = index.values.sumOf { it.bytes }
        if (total <= limit) return
        for (entry in index.values.filter { it.name != keep && it.missingUntilMs == 0L }.sortedBy { it.lastAccessMs }) {
            if (total <= limit) break
            entry.file?.let { File(dir, it).delete() }
            index.remove(entry.name)
            total -= entry.bytes
        }
    }

    companion object {
        private const val INDEX = "index.json"

        /** A lookup that found nothing is not repeated for this long. */
        const val MISSING_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_MISSING_MARKS = 3_000

        private fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
