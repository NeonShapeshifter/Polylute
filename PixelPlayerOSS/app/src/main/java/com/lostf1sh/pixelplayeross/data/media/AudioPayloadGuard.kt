package com.lostf1sh.pixelplayeross.data.media

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import timber.log.Timber

/**
 * B57-fix1 5.1 / 5.3. The last check before an edited file replaces the original: **the audio in the edited copy is the audio in the original, byte for byte.**
 *
 * The editor always writes on a copy and swaps it in only when the copy is good. This is what "good" means for the two formats whose payload can be located with a
 * few reads and that the owner's library is made of:
 * - **FLAC**: the STREAMINFO block (rate, depth, length, the audio MD5) and every byte after the last metadata block.
 * - **WAV**: every chunk that is not a tag (`fmt `, `data`, anything else), with its header, in the same order.
 *
 * Every other format passes without a runtime check: its payload cannot be told from its tags in a few reads, and its writers are covered by the unit tests that
 * compare the audio bytes of real files before and after (`B57EditorDateAndAudioTest`).
 *
 * A mismatch is not an error to fix up: the copy is thrown away, the original stays as it was, and the caller tries the next writer (or reports the failure).
 */
internal object AudioPayloadGuard {
    private const val TAG = "AudioPayloadGuard"

    /** True when [edited] carries exactly the audio [original] does, or when the format has no runtime check. */
    fun sameAudio(original: File, edited: File, container: String): Boolean = try {
        when (container) {
            "flac" -> sameFlacAudio(original, edited)
            "wav" -> sameWavAudio(original, edited)
            else -> true
        }
    } catch (e: IOException) {
        Timber.tag(TAG).e(e, "could not compare the audio of ${original.name} before and after the edit")
        false
    } catch (e: RuntimeException) {
        Timber.tag(TAG).e(e, "could not compare the audio of ${original.name} before and after the edit")
        false
    }

    // ── FLAC ───────────────────────────────────────────────────────────────────────────────────────

    private class FlacLayout(val streamInfo: ByteArray, val audioStart: Long)

    private fun flacLayoutOf(file: File): FlacLayout = RandomAccessFile(file, "r").use { raf ->
        val magic = ByteArray(4)
        raf.readFully(magic)
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") throw IOException("not a FLAC file")
        var streamInfo: ByteArray? = null
        var at = 4L
        while (true) {
            raf.seek(at)
            val header = ByteArray(4)
            raf.readFully(header)
            val isLast = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (type == 0 && streamInfo == null) {
                streamInfo = ByteArray(length).also { raf.readFully(it) }
            }
            at += 4L + length
            if (isLast) break
        }
        FlacLayout(streamInfo ?: throw IOException("no STREAMINFO block"), at)
    }

    private fun sameFlacAudio(original: File, edited: File): Boolean {
        val a = flacLayoutOf(original)
        val b = flacLayoutOf(edited)
        if (!a.streamInfo.contentEquals(b.streamInfo)) {
            Timber.tag(TAG).e("FLAC STREAMINFO changed by the edit of ${original.name}")
            return false
        }
        val same = sameRange(original, a.audioStart, original.length() - a.audioStart, edited, b.audioStart, edited.length() - b.audioStart)
        if (!same) Timber.tag(TAG).e("FLAC audio frames changed by the edit of ${original.name}")
        return same
    }

    // ── WAV ────────────────────────────────────────────────────────────────────────────────────────

    private fun sameWavAudio(original: File, edited: File): Boolean {
        val a = WavTagWriter.chunksOf(original).filterNot(::isTagChunk)
        val b = WavTagWriter.chunksOf(edited).filterNot(::isTagChunk)
        if (a.size != b.size) {
            Timber.tag(TAG).e("WAV chunk list changed by the edit of ${original.name}: ${a.map { it.id }} -> ${b.map { it.id }}")
            return false
        }
        for (i in a.indices) {
            if (a[i].id != b[i].id || a[i].length != b[i].length ||
                !sameRange(original, a[i].offset, 8 + a[i].paddedLength, edited, b[i].offset, 8 + b[i].paddedLength)
            ) {
                Timber.tag(TAG).e("WAV chunk '${a[i].id}' changed by the edit of ${original.name}")
                return false
            }
        }
        return true
    }

    /** The tag chunks: the ID3v2 chunk and the INFO list. Everything else is audio or the description of the audio. */
    private fun isTagChunk(chunk: WavTagWriter.Chunk): Boolean = chunk.isId3 || chunk.id == "LIST"

    // ── shared ─────────────────────────────────────────────────────────────────────────────────────

    private fun sameRange(a: File, aStart: Long, aLength: Long, b: File, bStart: Long, bLength: Long): Boolean {
        if (aLength != bLength) return false
        RandomAccessFile(a, "r").use { ra ->
            RandomAccessFile(b, "r").use { rb ->
                ra.seek(aStart)
                rb.seek(bStart)
                val bufferA = ByteArray(64 * 1024)
                val bufferB = ByteArray(64 * 1024)
                var left = aLength
                while (left > 0) {
                    val n = minOf(bufferA.size.toLong(), left).toInt()
                    ra.readFully(bufferA, 0, n)
                    rb.readFully(bufferB, 0, n)
                    for (i in 0 until n) if (bufferA[i] != bufferB[i]) return false
                    left -= n
                }
            }
        }
        return true
    }
}
