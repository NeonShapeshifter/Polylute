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

    class Snapshot internal constructor(private val entries: Set<String>) {
        fun contains(key: String): Boolean = encoded(key) in entries
        fun fingerprint(): String = entries.sorted().joinToString("\n")
    }

    internal suspend fun revision(): Any? = lock.mutex.withLock {
        try {
            fingerprint()
        } catch (_: Exception) {
            // Force the normal read path, which reports and ignores unreadable marks.
            Any()
        }
    }

    suspend fun snapshot(): Snapshot = lock.mutex.withLock {
        Snapshot(readLocked().toSet())
    }

    suspend fun contains(key: String): Boolean = lock.mutex.withLock {
        encoded(key) in readLocked()
    }

    suspend fun mark(keys: Collection<String>) = lock.mutex.withLock {
        val previous = readLocked()
        val updated = previous + keys.map(::encoded)
        if (updated != previous) writeLocked(updated)
    }

    suspend fun allow(keys: Collection<String>): Boolean = lock.mutex.withLock {
        val previous = readLocked()
        val updated = previous - keys.map(::encoded).toSet()
        if (updated == previous) return@withLock false
        writeLocked(updated)
        true
    }

    /** No network work belongs here, only the short disk/DB commit after a response arrived. */
    suspend fun <T> commitIfAllowed(key: String, commit: suspend () -> T): T? = lock.mutex.withLock {
        if (encoded(key) in readLocked()) return@withLock null
        commit()
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
