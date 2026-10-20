package com.lostf1sh.pixelplayeross.data.media

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the on-disk waveform cache: a stable identity that changes with the source,
 * a bounded footprint, and recovery from a corrupted entry.
 */
class WaveformCacheTest {

    @TempDir
    lateinit var directory: File

    private lateinit var cache: WaveformCache

    @BeforeEach
    fun setUp() {
        cache = WaveformCache(directory)
    }

    private fun waveform(bucketCount: Int = 8, fill: Float = 0.5f): Waveform =
        Waveform(
            bucketCount = bucketCount,
            peaks = FloatArray(bucketCount) { fill },
            rms = FloatArray(bucketCount) { fill / 2f },
            sampleRate = 44_100,
            durationMs = 1_000L
        )

    @Test
    fun `a stored waveform round trips intact`() {
        val key = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)!!
        val original = waveform()

        cache.write(key, original)

        val loaded = assertNotNull(cache.read(key))
        assertEquals(original.bucketCount, loaded.bucketCount)
        assertContentEquals(original.peaks, loaded.peaks)
        assertContentEquals(original.rms, loaded.rms)
        assertEquals(original.sampleRate, loaded.sampleRate)
        assertEquals(original.durationMs, loaded.durationMs)
    }

    @Test
    fun `the same source identity yields the same key`() {
        val first = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)
        val second = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)

        assertEquals(first, second)
    }

    @Test
    fun `a different source identity yields a different key`() {
        val first = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)
        val second = cache.keyFor("content://media/audio/2", 1_000L, 2_000L)

        assertTrue(first != second)
    }

    @Test
    fun `a changed file size invalidates the key`() {
        val before = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)
        val after = cache.keyFor("content://media/audio/1", 2_000L, 2_000L)

        assertTrue(before != after, "size is part of the identity")
    }

    @Test
    fun `a changed modified time invalidates the key`() {
        val before = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)
        val after = cache.keyFor("content://media/audio/1", 1_000L, 9_000L)

        assertTrue(before != after, "modified time is part of the identity")
    }

    @Test
    fun `an unknown source identity produces no key rather than a colliding one`() {
        assertNull(cache.keyFor(null, 1_000L, 2_000L))
        assertNull(cache.keyFor("", 1_000L, 2_000L))
    }

    @Test
    fun `a missing entry reads as absent`() {
        val key = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)!!

        assertNull(cache.read(key))
    }

    @Test
    fun `a corrupted entry is discarded rather than surfaced`() {
        val key = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)!!
        cache.write(key, waveform())

        // Overwrite the payload with bytes that are not a valid cache record.
        directory.listFiles()!!.filter { it.name.endsWith(".wfm") }.forEach {
            it.writeBytes(ByteArray(64) { 0x7F })
        }

        assertNull(cache.read(key), "a corrupt file must not be decoded as a waveform")
    }

    @Test
    fun `invalidating an entry removes it`() {
        val key = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)!!
        cache.write(key, waveform())

        cache.invalidate(key)

        assertNull(cache.read(key))
    }

    @Test
    fun `the cache evicts down to its cap instead of growing without bound`() {
        val bounded = WaveformCache(directory, maxBytes = 64L * 1024)

        repeat(200) { index ->
            val key = bounded.keyFor("content://media/audio/$index", 1_000L, index.toLong())!!
            bounded.write(key, waveform(bucketCount = 512))
            // Space the writes out so the LRU ordering is unambiguous.
            directory.listFiles()?.forEach { it.setLastModified(1_000L * index) }
        }

        val total = directory.listFiles()!!.filter { it.name.endsWith(".wfm") }.sumOf { it.length() }

        assertTrue(total <= 64L * 1024, "cache grew to $total bytes past its 64 KiB cap")
    }

    @Test
    fun `a partial write is never left behind as a readable entry`() {
        val key = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)!!
        cache.write(key, waveform())

        val leftovers = directory.listFiles()!!.filter { it.name.endsWith(".tmp") }

        assertTrue(leftovers.isEmpty(), "temporary files must not survive a completed write")
    }

    @Test
    fun `eviction clears orphaned temporary files`() {
        File(directory, "orphan.wfm.tmp").writeBytes(ByteArray(32))

        cache.evictIfNeeded()

        assertTrue(
            directory.listFiles()!!.none { it.name.endsWith(".tmp") },
            "orphaned temporaries must be swept"
        )
    }

    @Test
    fun `an entry survives a fresh cache instance over the same directory`() {
        val key = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)!!
        cache.write(key, waveform(fill = 0.25f))

        // A new instance models a process restart over the same cache directory.
        val reopened = assertNotNull(WaveformCache(directory).read(key))

        assertEquals(0.25f, reopened.peaks[0], 1e-6f)
    }

    @Test
    fun `rewriting the same key replaces rather than duplicates`() {
        val key = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)!!

        cache.write(key, waveform(fill = 0.2f))
        cache.write(key, waveform(fill = 0.8f))

        assertEquals(1, directory.listFiles()!!.count { it.name.endsWith(".wfm") })
        assertEquals(0.8f, cache.read(key)!!.peaks[0], 1e-6f)
    }

    @Test
    fun `the default cap is bounded to a modest share of the cache directory`() {
        assertTrue(
            WaveformCache.DEFAULT_MAX_BYTES in (1L..64L * 1024 * 1024),
            "unexpected default cap ${WaveformCache.DEFAULT_MAX_BYTES}"
        )
    }

    @Test
    fun `a record with a nonsensical bucket count is rejected and removed`() {
        val key = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)!!
        cache.write(key, waveform(bucketCount = 4))

        // Rewrite the bucket-count header to zero, leaving the payload the wrong length too.
        val file = directory.listFiles()!!.first { it.name.endsWith(".wfm") }
        val bytes = file.readBytes()
        bytes[8] = 0
        bytes[9] = 0
        bytes[10] = 0
        bytes[11] = 0
        file.writeBytes(bytes)

        assertNull(cache.read(key), "a malformed header must not decode into a waveform")
        assertTrue(
            directory.listFiles()!!.none { it.name.endsWith(".wfm") },
            "a rejected entry must be removed so it cannot be retried forever"
        )
    }

    @Test
    fun `a truncated payload is rejected rather than read as a short waveform`() {
        val key = cache.keyFor("content://media/audio/1", 1_000L, 2_000L)!!
        cache.write(key, waveform(bucketCount = 16))

        val file = directory.listFiles()!!.first { it.name.endsWith(".wfm") }
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 16))

        assertNull(cache.read(key))
    }
}
