package com.lostf1sh.pixelplayeross.utils

import java.io.File
import java.io.RandomAccessFile

/**
 * B59 2 (#80): **does this file carry an embedded picture?**, answered from the file's headers without reading the picture.
 *
 * A Full Rescan clears every song's artwork cache, and the folder-cover step then had to learn, song by song, whether an embedded picture exists (it wins over a folder image). It
 * did that by **extracting** it (multi-MB through TagLib, then caching it again): 296 s of a 9-minute Full Rescan of 4,966 songs on the HONOR, against the scan's own rule that scan-time
 * artwork never extracts embedded art. The question only needs the headers:
 *  - **FLAC**: the metadata blocks are a chain of `type, length` headers; a PICTURE block is type 6. Walked by seeking past each body: a few reads.
 *  - **MP3 / ID3v2** (also an ID3v2 in front of a FLAC): the frames of the tag, `APIC` (v2.3/v2.4) or `PIC` (v2.2), each skipped by its size.
 *  - **MP4 / M4A**: `moov › udta › meta › ilst › covr`, by atom sizes.
 *  - **Ogg (Vorbis, Opus, FLAC-in-Ogg)**: a `METADATA_BLOCK_PICTURE` comment, looked for in the first [OGG_SCAN_BYTES] bytes (the comment header comes right after the stream header).
 * Any other container is answered **false**: it is never read (a WAV or APE with an embedded picture is rare, and a wrong "no" only lets a folder image show where the file's own would).
 * A file that cannot be opened or read is false too: the folder step then does what it does for a file without a picture.
 */
internal object EmbeddedPictureProbe {
    /** How much of an Ogg file is searched for a picture comment. */
    internal const val OGG_SCAN_BYTES = 1 shl 20

    private const val MAX_ATOM_DEPTH = 6

    fun hasPicture(path: String): Boolean = try {
        File(path).takeIf { it.isFile }?.let { file -> RandomAccessFile(file, "r").use { probe(it) } } ?: false
    } catch (_: Exception) {
        false
    }

    /** The probe over an open file; false for anything it does not know. */
    fun probe(file: RandomAccessFile): Boolean {
        val length = file.length()
        if (length < 12) return false
        val head = ByteArray(12).also { file.seek(0); file.readFully(it) }
        return when {
            head.startsWith("fLaC") -> flac(file, 4L, length)
            head.startsWith("ID3") -> id3(file, head, length)
            head.matchesAt(4, "ftyp") -> mp4(file, length)
            head.startsWith("OggS") -> ogg(file, length)
            else -> false
        }
    }

    // ── FLAC ────────────────────────────────────────────────────────────────────────────────────────

    private fun flac(file: RandomAccessFile, start: Long, length: Long): Boolean {
        var at = start
        val header = ByteArray(4)
        // a metadata chain is short; a corrupt one must not loop
        repeat(MAX_FLAC_BLOCKS) {
            if (at + 4 > length) return false
            file.seek(at)
            file.readFully(header)
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val size = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (type == FLAC_PICTURE_BLOCK) return size > 0
            if (last) return false
            at += 4L + size
        }
        return false
    }

    private const val MAX_FLAC_BLOCKS = 128
    private const val FLAC_PICTURE_BLOCK = 6

    // ── ID3v2 ───────────────────────────────────────────────────────────────────────────────────────

    private fun id3(file: RandomAccessFile, head: ByteArray, length: Long): Boolean {
        val version = head[3].toInt() and 0xFF
        val flags = head[5].toInt() and 0xFF
        val tagSize = syncsafe(head, 6)
        val bodyStart = 10L
        val bodyEnd = minOf(length, bodyStart + tagSize)
        // an ID3v2 tag in front of a FLAC stream: the FLAC blocks follow the tag
        if (bodyEnd + 4 <= length) {
            val after = ByteArray(4).also { file.seek(bodyEnd); file.readFully(it) }
            if (after.startsWith("fLaC")) return flac(file, bodyEnd + 4, length)
        }
        var at = bodyStart
        if (version in 3..4 && flags and 0x40 != 0 && at + 4 <= bodyEnd) {
            // extended header: its size comes first (syncsafe in v2.4, plain in v2.3 and not counting its own four bytes)
            val ext = ByteArray(4).also { file.seek(at); file.readFully(it) }
            at += if (version == 4) syncsafe(ext, 0).toLong() else 4L + plain32(ext, 0)
        }
        val frameHeader = if (version == 2) 6 else 10
        val buffer = ByteArray(frameHeader)
        var frames = 0
        while (at + frameHeader <= bodyEnd && frames++ < MAX_ID3_FRAMES) {
            file.seek(at)
            file.readFully(buffer)
            if (buffer[0].toInt() == 0) return false // padding: the frames are over
            val id = String(buffer, 0, if (version == 2) 3 else 4, Charsets.ISO_8859_1)
            val size = when (version) {
                2 -> ((buffer[3].toInt() and 0xFF) shl 16) or ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
                4 -> syncsafe(buffer, 4)
                else -> plain32(buffer, 4).toInt()
            }
            if (size < 0) return false
            if ((version == 2 && id == "PIC") || (version != 2 && id == "APIC")) return size > 0
            at += frameHeader + size.toLong()
        }
        return false
    }

    private const val MAX_ID3_FRAMES = 4096

    private fun syncsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or (bytes[offset + 3].toInt() and 0x7F)

    private fun plain32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)

    // ── MP4 ─────────────────────────────────────────────────────────────────────────────────────────

    private fun mp4(file: RandomAccessFile, length: Long): Boolean = atoms(file, 0L, length, 0, listOf("moov", "udta", "meta", "ilst", "covr"))

    /** Walks the atoms between [start] and [end] looking for the path [path] (container atoms only); true when its last atom is found with a body. */
    private fun atoms(file: RandomAccessFile, start: Long, end: Long, depth: Int, path: List<String>): Boolean {
        if (path.isEmpty() || depth > MAX_ATOM_DEPTH) return false
        var at = start
        val header = ByteArray(8)
        var count = 0
        while (at + 8 <= end && count++ < MAX_ATOMS) {
            file.seek(at)
            file.readFully(header)
            var size = plain32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            if (size == 1L) {
                val big = ByteArray(8)
                file.readFully(big)
                size = 0
                for (b in big) size = (size shl 8) or (b.toLong() and 0xFF)
                headerSize = 16L
            } else if (size == 0L) {
                size = end - at
            }
            if (size < headerSize) return false
            val bodyStart = at + headerSize
            val bodyEnd = minOf(end, at + size)
            if (type == path.first()) {
                if (path.size == 1) return bodyEnd - bodyStart > 0
                // `meta` is a full box: four bytes of version and flags before its children
                val childStart = if (type == "meta") bodyStart + 4 else bodyStart
                if (atoms(file, childStart, bodyEnd, depth + 1, path.drop(1))) return true
            }
            at += size
        }
        return false
    }

    private const val MAX_ATOMS = 512

    // ── Ogg ─────────────────────────────────────────────────────────────────────────────────────────

    private fun ogg(file: RandomAccessFile, length: Long): Boolean {
        val size = minOf(length, OGG_SCAN_BYTES.toLong()).toInt()
        val bytes = ByteArray(size).also { file.seek(0); file.readFully(it) }
        return containsIgnoreCase(bytes, "METADATA_BLOCK_PICTURE=")
    }

    private fun containsIgnoreCase(haystack: ByteArray, needle: String): Boolean {
        val n = needle.length
        outer@ for (i in 0..haystack.size - n) {
            for (j in 0 until n) {
                if (haystack[i + j].toInt().toChar().uppercaseChar() != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun ByteArray.startsWith(text: String): Boolean = matchesAt(0, text)

    private fun ByteArray.matchesAt(offset: Int, text: String): Boolean =
        size >= offset + text.length && text.indices.all { this[offset + it].toInt() == text[it].code }
}
