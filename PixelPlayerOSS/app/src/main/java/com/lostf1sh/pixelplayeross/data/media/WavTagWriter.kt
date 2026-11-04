package com.lostf1sh.pixelplayeross.data.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v22Tag
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Tag

/**
 * B57-fix1 5.1. Writes a WAV file's tags **without touching the audio**.
 *
 * Why not JAudioTagger: its WAV writer moves the `data` chunk when it re-lays the file out, and on a file with a `LIST` chunk before `data` and an `id3 ` chunk
 * after it (a common layout) it **changed the audio bytes** (B57-fix1 probe, `analysis/audit-2026-09-27/batches/B57-fix1-gates/`). Its generic WAV tag also cannot
 * delete the lyrics or the disc number, so every WAV edit was refused. TagLib handles WAV on a phone, but it cannot run in a JVM test.
 *
 * This writer is safe by construction: the output is the input chunk by chunk, byte for byte (every chunk but the tags), the tags are the only thing that is
 * rewritten, and the RIFF size is recomputed. A file it cannot read with certainty ([canEdit] false) is never written.
 *
 * What is written: the `id3 ` chunk (ID3v2, the tag every WAV-aware player reads first: all fields, lyrics, disc, ReplayGain and the cover) and, **only if the
 * file already has one**, the `LIST`/`INFO` chunk's title, artist, album, genre, track and date, so the two tags never disagree.
 */
internal object WavTagWriter {
    class UnsupportedWav(message: String) : IOException(message)

    class Chunk(val id: String, val offset: Long, val length: Long) {
        /** Chunks are word aligned: an odd length is followed by one pad byte. */
        val paddedLength: Long get() = length + (length and 1L)
        val end: Long get() = offset + 8 + paddedLength
        val isId3: Boolean get() = id.equals("id3 ", ignoreCase = true)
    }

    /** The chunks of a classic RIFF/WAVE file, in order. Throws [UnsupportedWav] for anything it cannot be certain about. */
    fun chunksOf(file: File): List<Chunk> = RandomAccessFile(file, "r").use { raf ->
        val fileLength = raf.length()
        if (fileLength < 12) throw UnsupportedWav("too short for a WAV file")
        val head = ByteArray(12)
        raf.readFully(head)
        val riff = String(head, 0, 4, Charsets.ISO_8859_1)
        if (riff != "RIFF") throw UnsupportedWav("not a classic RIFF file ($riff)")
        if (String(head, 8, 4, Charsets.ISO_8859_1) != "WAVE") throw UnsupportedWav("not a WAVE file")
        val chunks = ArrayList<Chunk>()
        var at = 12L
        val header = ByteArray(8)
        while (at < fileLength) {
            if (fileLength - at < 8) throw UnsupportedWav("trailing bytes after the last chunk")
            raf.seek(at)
            raf.readFully(header)
            val id = String(header, 0, 4, Charsets.ISO_8859_1)
            val length = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val chunk = Chunk(id, at, length)
            // The pad byte of the very last chunk is sometimes missing; anything else past the end of the file is a truncated or streamed file.
            if (at + 8 + length > fileLength) throw UnsupportedWav("chunk '$id' runs past the end of the file")
            chunks += chunk
            at = minOf(chunk.end, fileLength)
        }
        if (chunks.none { it.id == "fmt " }) throw UnsupportedWav("no fmt chunk")
        if (chunks.none { it.id == "data" }) throw UnsupportedWav("no data chunk")
        chunks
    }

    fun canEdit(file: File): Boolean = try {
        chunksOf(file)
        true
    } catch (e: IOException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    /** INFO sub-chunk ids and the tag fields they carry (the ones every INFO-reading player shows). */
    object InfoId {
        const val TITLE = "INAM"
        const val ARTIST = "IART"
        const val ALBUM = "IPRD"
        const val GENRE = "IGNR"
        const val TRACK = "ITRK"
        const val DATE = "ICRD"
    }

    /**
     * Copies [source] to [target] with its tags changed. [changeId3] edits the ID3v2 tag (the file's own, or a new ID3v2.3 one) in place; [infoUpdates] maps an INFO
     * sub-chunk id to its new text (blank = remove it) and is applied only when the file already has an INFO chunk.
     */
    fun edit(source: File, target: File, infoUpdates: Map<String, String?>, changeId3: (AbstractID3v2Tag) -> Unit) {
        val chunks = chunksOf(source)
        val firstId3 = chunks.firstOrNull { it.isId3 }
        val infoChunk = chunks.firstOrNull { it.id == "LIST" && listFormOf(source, it) == "INFO" }

        val tag: AbstractID3v2Tag = firstId3?.let { readId3(source, it) } ?: ID3v23Tag()
        changeId3(tag)
        val newId3 = id3ChunkBytes(tag)

        RandomAccessFile(source, "r").use { input ->
            FileOutputStream(target).use { out ->
                out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0))
                out.write("WAVE".toByteArray(Charsets.ISO_8859_1))
                var written = 4L // "WAVE"
                for (chunk in chunks) {
                    val replacement: ByteArray? = when {
                        chunk === firstId3 -> newId3
                        chunk === infoChunk && infoUpdates.isNotEmpty() -> infoChunkBytes(source, chunk, infoUpdates)
                        else -> null
                    }
                    if (replacement != null) {
                        out.write(replacement)
                        written += replacement.size
                    } else {
                        written += copyChunk(input, chunk, out)
                    }
                }
                if (firstId3 == null) {
                    out.write(newId3)
                    written += newId3.size
                }
                if (written > 0xFFFFFFFFL) throw UnsupportedWav("the edited file would be larger than a RIFF file can describe")
                out.fd.sync()
                // the RIFF size, patched in place
                RandomAccessFile(target, "rw").use { patch ->
                    patch.seek(4)
                    patch.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(written.toInt()).array())
                }
            }
        }
    }

    private fun copyChunk(input: RandomAccessFile, chunk: Chunk, out: FileOutputStream): Long {
        input.seek(chunk.offset)
        val total = minOf(chunk.end, input.length()) - chunk.offset
        val buffer = ByteArray(64 * 1024)
        var left = total
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw IOException("unexpected end of file while copying chunk '${chunk.id}'")
            out.write(buffer, 0, read)
            left -= read
        }
        if (total < 8 + chunk.paddedLength) {
            // the missing pad byte of the last chunk: add it so the output is well formed
            out.write(0)
            return total + 1
        }
        return total
    }

    private fun readBytes(file: File, offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
        raf.seek(offset)
        ByteArray(length).also { raf.readFully(it) }
    }

    private fun listFormOf(file: File, chunk: Chunk): String? =
        if (chunk.length < 4) null else String(readBytes(file, chunk.offset + 8, 4), Charsets.ISO_8859_1)

    private fun readId3(file: File, chunk: Chunk): AbstractID3v2Tag {
        if (chunk.length > MAX_TAG_BYTES) throw UnsupportedWav("the id3 chunk is larger than ${MAX_TAG_BYTES / (1024 * 1024)} MiB")
        val bytes = readBytes(file, chunk.offset + 8, chunk.length.toInt())
        if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) {
            throw UnsupportedWav("the id3 chunk does not hold an ID3v2 tag")
        }
        return try {
            when (bytes[3].toInt()) {
                2 -> ID3v23Tag(ID3v22Tag(ByteBuffer.wrap(bytes)))
                3 -> ID3v23Tag(ByteBuffer.wrap(bytes))
                4 -> ID3v24Tag(ByteBuffer.wrap(bytes))
                else -> throw UnsupportedWav("unknown ID3v2 version ${bytes[3]}")
            }
        } catch (e: UnsupportedWav) {
            throw e
        } catch (e: Exception) {
            throw UnsupportedWav("the id3 chunk cannot be read: ${e.message}")
        }
    }

    private fun id3ChunkBytes(tag: AbstractID3v2Tag): ByteArray {
        val body = ByteArrayOutputStream().also { tag.write(it, 0) }.toByteArray()
        return chunkBytes("id3 ", body)
    }

    private fun chunkBytes(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 9)
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array())
        out.write(body)
        if (body.size and 1 == 1) out.write(0)
        return out.toByteArray()
    }

    /** The existing INFO list with [updates] applied; every sub-chunk that is not updated is kept as it was, byte for byte. */
    private fun infoChunkBytes(file: File, chunk: Chunk, updates: Map<String, String?>): ByteArray {
        if (chunk.length > MAX_TAG_BYTES) throw UnsupportedWav("the LIST chunk is larger than ${MAX_TAG_BYTES / (1024 * 1024)} MiB")
        val body = readBytes(file, chunk.offset + 8, chunk.length.toInt())
        val out = ByteArrayOutputStream()
        out.write(body, 0, 4) // "INFO"
        val seen = HashSet<String>()
        var at = 4
        while (at + 8 <= body.size) {
            val id = String(body, at, 4, Charsets.ISO_8859_1)
            val length = ByteBuffer.wrap(body, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val end = minOf(body.size.toLong(), at + 8 + length + (length and 1L)).toInt()
            if (id in updates && seen.add(id)) {
                updates[id]?.takeIf { it.isNotBlank() }?.let { out.write(infoSubChunk(id, it)) }
            } else if (id !in updates) {
                out.write(body, at, end - at)
            } // a repeated sub-chunk of an updated id is dropped: one value is left
            at = end
        }
        if (at < body.size) out.write(body, at, body.size - at) // bytes after the last whole sub-chunk, kept
        for ((id, value) in updates) {
            if (id !in seen && !value.isNullOrBlank()) out.write(infoSubChunk(id, value))
        }
        return chunkBytes("LIST", out.toByteArray())
    }

    private fun infoSubChunk(id: String, text: String): ByteArray = chunkBytes(id, text.toByteArray(Charsets.UTF_8) + 0)

    private const val MAX_TAG_BYTES = 32L * 1024 * 1024
}
