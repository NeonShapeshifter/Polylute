package com.lostf1sh.pixelplayeross.data.musicinfo

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.lostf1sh.pixelplayeross.data.network.DownloadCommitGuard
import org.json.JSONObject
import org.json.JSONArray
import timber.log.Timber

/** User decisions live beside the cache index, independently of missing results and cache entries.
 * Fixed-width digests and bounded streaming lines reject corrupt input without limiting how many
 * assets a person may delete. The shared lock also orders deletion against a download's commit.
 */
class DeletedDownloads(private val file: File) {
    private data class Stamp(val size: Long, val modified: FileTime, val inode: String?)
    private class FileLock {
        val mutex = Mutex()
        var reportedCorruption: String? = null
    }

    private val lock = locks.computeIfAbsent(file.absoluteFile.normalize().path) { FileLock() }
    private var loaded = false
    private var stamp: Stamp? = null
    private var entries: Set<String> = emptySet()
    private val holdsFile = File(file.parentFile, "${file.name}.undo")

    private fun held(): Map<String, Set<String>> {
        if (!holdsFile.exists()) return emptyMap()
        val json = JSONObject(holdsFile.readText())
        return json.keys().asSequence().associateWith { token ->
            val values = json.getJSONArray(token)
            (0 until values.length()).map { values.getString(it) }.toSet()
        }
    }

    private fun writeHolds(holds: Map<String, Set<String>>) {
        if (holds.isEmpty()) {
            check(!holdsFile.exists() || holdsFile.delete()) { "Could not release pending deletion" }
            return
        }
        holdsFile.parentFile?.mkdirs()
        val json = JSONObject()
        holds.forEach { (token, keys) -> json.put(token, JSONArray(keys)) }
        val target = android.util.AtomicFile(holdsFile)
        val stream = target.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    /** Temporary suppression is separate from durable deletion, and is removed verbatim by Undo. */
    suspend fun hold(token: String, keys: Collection<String>) = lock.mutex.withLock {
        writeHolds(held() + (token to keys.map(::encoded).toSet()))
    }

    suspend fun releaseHold(token: String) = lock.mutex.withLock { writeHolds(held() - token) }

    suspend fun restoreHolds(actions: Map<String, List<String>>) = lock.mutex.withLock {
        val wanted = actions.mapValues { (_, keys) -> keys.map(::encoded).toSet() }
        if (wanted != held()) writeHolds(wanted)
    }

    /** Queue cancellation uses this same short lock as a response's final file/DB commit. */
    suspend fun <T> withCommitLock(action: suspend () -> T): T = lock.mutex.withLock { action() }

    class Snapshot internal constructor(private val entries: Set<String>) {
        fun contains(key: String): Boolean = candidates(key).any { it in entries }
        fun fingerprint(): String = entries.sorted().joinToString("\n")
    }

    internal suspend fun revision(): Any? = lock.mutex.withLock {
        try {
            listOf(fingerprint(), holdsFile.takeIf { it.exists() }?.let { it.length() to it.lastModified() })
        } catch (_: Exception) {
            // Force the normal read path, which reports and ignores unreadable marks.
            Any()
        }
    }

    suspend fun snapshot(): Snapshot = lock.mutex.withLock {
        Snapshot(readLocked() + held().values.flatten())
    }

    suspend fun contains(key: String): Boolean = lock.mutex.withLock {
        val candidates = candidates(key)
        candidates.any { it in readLocked() } || held().values.any { keys -> candidates.any { it in keys } }
    }

    suspend fun mark(keys: Collection<String>) = lock.mutex.withLock {
        val previous = readLocked()
        val updated = previous + keys.map(::encoded)
        if (updated != previous) writeLocked(updated)
    }

    suspend fun allow(keys: Collection<String>): Boolean = lock.mutex.withLock {
        val previous = readLocked()
        val removed = keys.flatMap(::candidates).toSet()
        val updated = previous - removed
        val holds = held()
        val remaining = holds.mapValues { it.value - removed }.filterValues { it.isNotEmpty() }
        if (holds != remaining) writeHolds(remaining)
        if (updated != previous) writeLocked(updated)
        updated != previous || holds != remaining
    }

    /** No network work belongs here, only the short disk/DB commit after a response arrived. */
    suspend fun <T> commitIfAllowed(key: String, commit: suspend () -> T): T? = lock.mutex.withLock {
        currentCoroutineContext().ensureActive()
        val candidates = candidates(key)
        if (candidates.any { it in readLocked() } || held().values.any { keys -> candidates.any { it in keys } }) return@withLock null
        val guard = currentCoroutineContext()[DownloadCommitGuard]
        if (guard?.allowed?.invoke() == false) return@withLock null
        // This is the short disk/DB commit, never a request. Once started it completes atomically
        // with its queue acknowledgement before Cancel can take the same lock.
        withContext(NonCancellable) {
            val result = commit()
            if (result != null && result != false) guard?.committed?.invoke()
            result
        }
    }

    private fun fingerprint(): Stamp? = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey()?.toString())
    } catch (_: NoSuchFileException) {
        null
    }

    private fun readLocked(): Set<String> {
        var current: Stamp? = null
        try {
            current = fingerprint()
            if (loaded && current == stamp) return entries
            entries = if (current == null) emptySet() else readFile()
        } catch (error: Exception) {
            val signature = current?.toString() ?: error.javaClass.name
            if (lock.reportedCorruption != signature) {
                lock.reportedCorruption = signature
                Timber.tag(TAG).w(error, "Ignoring unreadable deleted-download marks")
            }
            entries = emptySet()
        }
        loaded = true
        stamp = current
        return entries
    }

    private fun readFile(): Set<String> = file.bufferedReader(Charsets.UTF_8).use { reader ->
        if (readBoundedLine(reader) != HEADER) throw IOException("Unknown deletion marks format")
        val result = mutableSetOf<String>()
        while (true) {
            val line = readBoundedLine(reader) ?: break
            if (!ENTRY.matches(line)) throw IOException("Invalid deletion mark")
            result += line
        }
        result
    }

    private fun readBoundedLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value == -1) return line.toString().takeIf { it.isNotEmpty() }
            if (value == '\n'.code) return line.toString()
            if (value !in 32..126 || line.length >= MAX_LINE_LENGTH) {
                throw IOException("Invalid deletion marks line")
            }
            line.append(value.toChar())
        }
    }

    private fun writeLocked(updated: Set<String>) {
        if (updated.isEmpty()) {
            check(!file.exists() || file.delete()) { "Could not clear deleted-download marks" }
        } else {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine(HEADER)
                updated.sorted().forEach { writer.appendLine(it) }
            }
            check(temporary.renameTo(file)) { "Could not store deleted-download marks" }
        }
        entries = updated
        stamp = fingerprint()
        loaded = true
    }

    companion object {
        /** Old cache indexes retain a hash rather than a name. Delete all can suppress those too. */
        internal fun cacheIds(key: String): List<String> {
            if ("|cache:" in key) return listOf(key.substringAfter("|cache:"))
            val parts = key.split('|')
            return when (parts.firstOrNull()) {
                "photo" -> listOf(MusicInfoCache.savedEntryId(MusicInfoCache.Kind.ARTIST_PHOTO, parts.drop(1).joinToString("|")))
                "cover" -> listOf(MusicInfoCache.savedEntryId(MusicInfoCache.Kind.COVER, parts.drop(1).joinToString("|")))
                "about" -> if (parts.size >= 4) {
                    val kind = if (parts[1] == "true") AboutKind.ALBUM else AboutKind.ARTIST
                    (listOf<String?>(null, "und", "en", "es", "sv")).map {
                        MusicInfoCache.savedEntryId(MusicInfoCache.Kind.ABOUT, MusicInfoService.aboutKey(kind, parts[2], parts[3], it))
                    }
                } else emptyList()
                else -> emptyList()
            }
        }

        private fun candidates(key: String): List<String> = listOf(encoded(key)) +
            if ("|cache:" in key) emptyList() else cacheIds(key).map { encoded("${key.substringBefore('|')}|cache:$it") }

        private fun encoded(key: String): String {
            val kind = key.substringBefore('|')
            require(kind in KINDS)
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') }
            return "$kind:$hex"
        }


        const val FILE_NAME = "deleted-downloads.txt"
        private const val HEADER = "POLYLUTE-DELETED-DOWNLOADS-1"
        private const val MAX_LINE_LENGTH = 80
        private const val TAG = "DeletedDownloads"
        private val KINDS = setOf("photo", "cover", "about", "lyrics")
        private val ENTRY = Regex("(?:photo|cover|about|lyrics):[0-9a-f]{64}")
        private val locks = ConcurrentHashMap<String, FileLock>()
    }
}
