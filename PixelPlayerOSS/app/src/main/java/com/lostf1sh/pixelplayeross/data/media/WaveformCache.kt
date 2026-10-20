package com.lostf1sh.pixelplayeross.data.media

import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Disk cache for [Waveform] summaries, living in a directory the platform may clear at any time.
 *
 * Everything here is derived data, so every failure mode degrades to "generate it again":
 *
 *  - **Identity.** The key is a hash of the media identity (content URI or absolute path) *plus*
 *    the file size *plus* the file's last-modified time. A file edited in place, replaced by a
 *    different rip of the same song, or re-tagged will hash differently and get a fresh waveform.
 *    Keying on the filename alone — which is what the audio cache used to do — would serve a stale
 *    envelope forever.
 *  - **Bounded.** [maxBytes] caps the whole directory. Writes trigger eviction down to 75% of the
 *    cap so a burst of new tracks cannot immediately trip the limit again.
 *  - **Corruption-safe.** Every read validates a magic number, a format version and the declared
 *    length against the actual file size. A truncated or foreign file is deleted and reported as a
 *    miss rather than being decoded into garbage.
 *  - **Restart-safe.** The cache is plain files with no in-memory index, so a process death
 *    mid-write leaves at most one unusable file, which the next read discards. Writes go to a
 *    temporary name and are renamed into place, so a partially written entry is never visible
 *    under its final key.
 */
class WaveformCache(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {

    /** Cache key for a media identity, or null when the caller cannot supply one. */
    fun keyFor(identity: String?, sizeBytes: Long?, lastModifiedMs: Long?): String? {
        if (identity.isNullOrBlank()) return null
        val material = buildString {
            append(identity)
            append('|')
            append(sizeBytes ?: -1L)
            append('|')
            append(lastModifiedMs ?: -1L)
        }
        return sha256(material).take(KEY_LENGTH)
    }

    fun read(key: String): Waveform? {
        val file = File(directory, "$key$EXTENSION")
        if (!file.isFile) return null
        return try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                if (magic != MAGIC || version != VERSION) {
                    Timber.tag(TAG).w("Discarding waveform cache entry ${file.name}: header mismatch")
                    return null.also { file.delete() }
                }
                val bucketCount = input.readInt()
                val sampleRate = input.readInt()
                val durationMs = input.readLong()
                if (bucketCount <= 0 || bucketCount > MAX_BUCKETS) {
                    Timber.tag(TAG).w("Discarding waveform cache entry ${file.name}: bad bucket count")
                    return null.also { file.delete() }
                }
                val expectedBytes = HEADER_BYTES + bucketCount.toLong() * 2L * 4L
                if (file.length() != expectedBytes) {
                    Timber.tag(TAG).w("Discarding truncated waveform cache entry ${file.name}")
                    return null.also { file.delete() }
                }
                val peaks = FloatArray(bucketCount) { input.readFloat() }
                val rms = FloatArray(bucketCount) { input.readFloat() }
                Waveform(bucketCount, peaks, rms, sampleRate, durationMs)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to read waveform cache entry ${file.name}")
            runCatching { file.delete() }
            null
        }
    }

    fun write(key: String, waveform: Waveform) {
        try {
            if (!directory.exists() && !directory.mkdirs()) {
                Timber.tag(TAG).w("Unable to create waveform cache directory")
                return
            }
            val target = File(directory, "$key$EXTENSION")
            val temp = File(directory, "$key$TEMP_SUFFIX")
            DataOutputStream(FileOutputStream(temp).buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(waveform.bucketCount)
                output.writeInt(waveform.sampleRate)
                output.writeLong(waveform.durationMs)
                waveform.peaks.forEach { output.writeFloat(it) }
                waveform.rms.forEach { output.writeFloat(it) }
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return
            }
            evictIfNeeded()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to write waveform cache entry")
        }
    }

    /** Deletes a single entry, e.g. when the source file is known to have changed. */
    fun invalidate(key: String) {
        runCatching { File(directory, "$key$EXTENSION").delete() }
    }

    /**
     * Removes any leftover temp files, then trims the directory to [maxBytes].
     * Called on write; also safe to call once at startup to clear crash residue.
     */
    fun evictIfNeeded() {
        val entries = try {
            directory.listFiles()?.toList().orEmpty()
        } catch (e: Exception) {
            return
        }

        // Orphans from a process death during write. Nothing references them, ever.
        entries.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach { runCatching { it.delete() } }

        val live = entries.filter { it.isFile && it.name.endsWith(EXTENSION) }
        var total = live.sumOf { it.length() }
        if (total <= maxBytes) return

        val target = (maxBytes * EVICT_TO_FRACTION).toLong()
        val oldestFirst = live.sortedBy { it.lastModified() }
        for (file in oldestFirst) {
            if (total <= target) break
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                total -= length
            }
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte -> append(HEX[(byte.toInt() shr 4) and 0x0F]); append(HEX[byte.toInt() and 0x0F]) }
        }
    }

    companion object {
        private const val TAG = "WaveformCache"

        /** "PPWF" — PixelPlayer WaveForm. */
        private const val MAGIC = 0x50505746
        private const val VERSION = 1
        private const val EXTENSION = ".wfm"
        private const val TEMP_SUFFIX = ".tmp"
        private const val HEADER_BYTES = 4 + 4 + 4 + 4 + 8

        /** Sanity bound so a corrupt bucket count cannot make us allocate gigabytes. */
        private const val MAX_BUCKETS = 1 shl 20

        /**
         * 16 MB. Each entry is ~8 KB, so this holds roughly 2000 tracks — far more than a DAP
         * with a large library will ever open Track Details for — while staying small enough to
         * live comfortably in a cache directory the system may reclaim.
         */
        const val DEFAULT_MAX_BYTES = 16L * 1024 * 1024

        private const val EVICT_TO_FRACTION = 0.75
        private const val KEY_LENGTH = 32
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
