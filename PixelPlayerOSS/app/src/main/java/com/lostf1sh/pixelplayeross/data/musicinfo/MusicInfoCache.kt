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

    val deletedDownloads = DeletedDownloads(File(dir, DeletedDownloads.FILE_NAME))

    private val lock = Any()
    private val index = LinkedHashMap<String, Entry>()
    private var loaded = false
    private var revision = 0L

    private fun id(kind: Kind, key: String): String = savedEntryId(kind, key)

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
        revision++
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
        }.getOrThrow()
    }

    fun get(kind: Kind, key: String): Hit? = synchronized(lock) {
        load()
        val entry = index[id(kind, key)] ?: return null
        if (entry.missingUntilMs > 0) return null
        entry.lastAccessMs = now()
        Hit(entry.file?.let { File(dir, it) }, entry.meta)
    }

    data class SavedEntry(val id: String, val kind: Kind, val meta: String?, val bytes: Long = 0)

    /** A single read-only pass. Catalog refresh must not touch LRU times or register claims. */
    fun savedEntries(): List<SavedEntry> = synchronized(lock) {
        load()
        index.values.filter { entry ->
            entry.missingUntilMs == 0L &&
                (entry.kind == Kind.ABOUT || entry.file?.let { File(dir, it).isFile } == true)
        }.map { SavedEntry(it.name, it.kind, it.meta, it.bytes) }
    }

    /** Removes one already identified legacy entry while retaining durable suppression marks. */
    fun removeSavedEntry(id: String): File? = synchronized(lock) {
        load()
        val entry = index[id] ?: return@synchronized null
        val file = entry.file?.let { File(dir, it) }
        if (file != null) check(!file.exists() || file.delete()) { "Could not delete downloaded image" }
        index.remove(id)
        try { save() } catch (error: Exception) { index[id] = entry; throw error }
        file
    }

    /** Constant-size checks also notice removed files or a replaced cache directory. */
    internal fun savedRevision(): List<Any?> = synchronized(lock) {
        listOf(revision, fileStamp(dir), fileStamp(indexFile()))
    }

    private fun fileStamp(file: File): Any? = try {
        val attributes = java.nio.file.Files.readAttributes(
            file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java
        )
        listOf(attributes.fileKey()?.toString(), attributes.lastModifiedTime(), attributes.size())
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    } catch (_: java.io.IOException) {
        Any() // An unreadable stamp cannot authorize a cached catalog result.
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
        val revision = sha1(data.contentHashCode().toString()).take(12)
        val file = File(dir, "$name-$revision.$extension")
        val temporary = File(dir, "$name.tmp")
        val retainedBytes = index[name]?.bytes ?: 0L
        val addedBytes = if (file.isFile && index[name] != null) 0L else data.size.toLong()
        temporary.writeBytes(data)
        check(temporary.renameTo(file)) { "Could not save downloaded image" }
        index[name] = Entry(kind, name, file.name, retainedBytes + addedBytes, now(), meta, 0L)
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

    /** Old failures and real absences were indistinguishable. Drop those marks once at startup. */
    fun dropMissingMarks() = synchronized(lock) {
        load()
        val missing = index.values.filter { it.missingUntilMs > 0L }.map { it.name }
        for (name in missing) index.remove(name)
        if (missing.isNotEmpty()) save()
    }

    /** Repair only unreadable images; never age out a working downloaded picture. */
    fun brokenImages(): List<File> = synchronized(lock) {
        load()
        val indexed = index.values.filter { it.kind != Kind.ABOUT }.mapNotNull { it.file?.let { name -> File(dir, name) } }
        val orphaned = dir.listFiles().orEmpty().filter { file ->
            file.isFile && file.extension != "tmp" && listOf(Kind.ARTIST_PHOTO, Kind.COVER).any { file.name.startsWith(it.prefix + "_") }
        }
        (indexed + orphaned).distinctBy { it.absolutePath }.filterNot(DownloadedImage::decodes)
    }

    fun removeBrokenImage(file: File) = synchronized(lock) {
        load()
        val entries = index.values.filter { it.file == file.name && it.kind != Kind.ABOUT }
        if (!DownloadedImage.decodes(file)) {
            entries.forEach { index.remove(it.name) }
            file.delete()
            save() // No missing mark: a later lookup must ask again.
        }
    }

    fun remove(kind: Kind, key: String): File? = synchronized(lock) {
        load()
        val name = id(kind, key)
        val entry = index[name]
        val file = entry?.file?.let { File(dir, it) }
        // Includes superseded revisions kept until a replacement was on screen.
        dir.listFiles()?.filter { it.name.startsWith("$name-") || it.name.startsWith("$name.") }?.forEach {
            check(!it.exists() || it.delete()) { "Could not delete downloaded image ${it.name}" }
        }
        index.remove(name)
        try { save() } catch (error: Exception) { if (entry != null) index[name] = entry; throw error }
        file
    }

    fun descriptionLanguages(): Set<String> = synchronized(lock) {
        load()
        index.values.filter { it.kind == Kind.ABOUT }.mapNotNull { entry ->
            entry.meta?.let { metadata ->
                runCatching { JSONObject(metadata).optString("language").takeIf(String::isNotBlank) }.getOrNull()
            }
        }.toSet()
    }

    fun usedBytes(): Long = synchronized(lock) {
        load()
        index.values.sumOf { it.bytes }
    }

    /** Whether [path] is a file this cache wrote (the artist's stored picture points at one). */
    fun owns(path: String?): Boolean = path != null && runCatching { File(path).canonicalPath.startsWith(dir.canonicalPath + File.separator) }.getOrDefault(false)

    /** "Delete downloaded data": everything, the index included. Returns the bytes freed. */
    fun clear(): Long = synchronized(lock) {
        revision++
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
            dir.listFiles()?.filter { it.name.startsWith("${entry.name}-") || it.name.startsWith("${entry.name}.") }?.forEach { it.delete() }
            index.remove(entry.name)
            total -= entry.bytes
        }
    }

    companion object {
        private const val INDEX = "index.json"

        /** A lookup that found nothing is not repeated for this long. */
        const val MISSING_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_MISSING_MARKS = 3_000

        internal fun savedEntryId(kind: Kind, key: String): String =
            kind.prefix + "_" + sha1(key.trim().lowercase())

        private fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") {
                (it.toInt() and 255).toString(16).padStart(2, '0')
            }
    }
}
