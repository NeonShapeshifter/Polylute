package com.lostf1sh.pixelplayeross.data.media

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * B57 2.6: the codec a file's sound is stored in, by its common name, as opposed to the container it is wrapped in. A `.ogg` holds Vorbis **or** Opus, an `.m4a` holds AAC **or**
 * ALAC; the library's MIME type (`audio/ogg`, `audio/mp4`) names only the container, so a label made from it was wrong half the time ("OGG" for an Opus file).
 */
enum class AudioCodec(val label: String, /** The extension a file of this codec usually has; "(.ogg file)" is only said when the file's is another. */ val usualExtension: String) {
    FLAC("FLAC", "flac"),
    ALAC("ALAC", "m4a"),
    WAV("WAV", "wav"),
    MP3("MP3", "mp3"),
    AAC("AAC", "m4a"),
    OPUS("Opus", "opus"),
    VORBIS("Vorbis", "ogg");

    companion object {
        /**
         * What the player says it is playing, when that names a codec (`audio/opus`, `audio/vorbis`, `audio/mp4a-latm`, `audio/flac`, `audio/mpeg`, `audio/alac`). Decoded PCM
         * (`audio/raw`), which is what a FLAC decoded by the app's own decoder reports, is not a codec and gives null.
         */
        fun fromRuntimeMime(mime: String?): AudioCodec? = when (mime?.trim()?.lowercase(Locale.ROOT)?.substringBefore(';')) {
            "audio/opus" -> OPUS
            "audio/vorbis" -> VORBIS
            "audio/mp4a-latm", "audio/aac", "audio/aacp", "audio/x-aac" -> AAC
            "audio/alac" -> ALAC
            "audio/flac", "audio/x-flac" -> FLAC
            "audio/mpeg", "audio/mp3", "audio/x-mp3" -> MP3
            else -> null
        }

        /**
         * A container MIME type that can only hold one codec: MP3, FLAC and WAV files. `audio/ogg`, `audio/mp4` and `audio/x-m4a` are deliberately absent: they may hold two,
         * and guessing one is how an Opus file came to say "OGG".
         */
        fun fromUnambiguousContainerMime(mime: String?): AudioCodec? = when (mime?.trim()?.lowercase(Locale.ROOT)?.substringBefore(';')) {
            "audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3" -> MP3
            "audio/flac", "audio/x-flac" -> FLAC
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> WAV
            else -> null
        }

        /** A tag reader's header token that names a codec by itself ("Flac", "MP3"); "Ogg", "MP4" and the like name containers and give null. */
        fun fromUnambiguousReaderToken(token: String?): AudioCodec? = when (token?.trim()?.lowercase(Locale.ROOT)) {
            "flac" -> FLAC
            "mp3" -> MP3
            "wav", "wave" -> WAV
            else -> null
        }

        /** The player's line: the codec and nothing else ("Opus"). */
        fun playerLabel(codec: AudioCodec?): String? = codec?.label

        /**
         * The song-info page: both, "Opus (.ogg file)". The file part is left out when the file has the extension the codec usually has ("FLAC", not "FLAC (.flac file)"), or none.
         */
        fun describe(codec: AudioCodec?, fileName: String?): String? {
            codec ?: return null
            val extension = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() && it.length <= 5 }
            val same = extension == null || extension == codec.usualExtension || extension == codec.label.lowercase(Locale.ROOT) ||
                (codec == AAC && extension == "aac") || (codec == MP3 && extension == "mp3")
            return if (same) codec.label else "${codec.label} (.$extension file)"
        }
    }
}

/** What reading a file's first bytes says: its codec, and the rate and depth the header carries where it has them. */
data class SniffedAudio(val codec: AudioCodec, val sampleRateHz: Int? = null, val bitDepth: Int? = null)

/**
 * Names a file's codec from its own header, with a handful of bounded reads (the first bytes, and for an MP4 the box path to the sample description): no tag library, no decoding.
 * It runs once, for the song that is playing or whose details are open; never per list row.
 *
 * Opus always decodes at 48 kHz whatever rate it was recorded at, so that is what an Opus file reports; Vorbis reports its own (44.1 kHz stays 44.1).
 */
object CodecSniffer {
    fun sniff(file: File): SniffedAudio? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(64)
            val read = raf.read(head)
            if (read < 12) return@use null
            when {
                head.ascii(0, 4) == "fLaC" -> flac(head, 8)
                head.ascii(0, 4) == "OggS" -> ogg(head)
                head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WAVE" -> wav(raf)
                head.ascii(4, 4) == "ftyp" -> mp4(raf)
                head.ascii(0, 3) == "ID3" || (head[0].toInt() and 0xFF == 0xFF && head[1].toInt() and 0xE0 == 0xE0 && (head[1].toInt() shr 1 and 3) != 0) -> SniffedAudio(AudioCodec.MP3)
                head[0].toInt() and 0xFF == 0xFF && head[1].toInt() and 0xF6 == 0xF0 -> SniffedAudio(AudioCodec.AAC) // ADTS
                else -> null
            }
        }
    }.getOrNull()

    private fun ByteArray.ascii(at: Int, length: Int) = String(this, at, length, Charsets.ISO_8859_1)
    private fun ByteArray.u(at: Int) = this[at].toInt() and 0xFF
    private fun ByteArray.be32(at: Int) = (u(at) shl 24) or (u(at + 1) shl 16) or (u(at + 2) shl 8) or u(at + 3)
    private fun ByteArray.le32(at: Int) = (u(at + 3) shl 24) or (u(at + 2) shl 16) or (u(at + 1) shl 8) or u(at)

    /** STREAMINFO body at [bodyAt]: the rate is 20 bits at body offset 10, the depth 5 bits after the 3 bits of channels. */
    private fun flac(head: ByteArray, bodyAt: Int): SniffedAudio? {
        if (head.size < bodyAt + 14) return SniffedAudio(AudioCodec.FLAC)
        val rate = (head.u(bodyAt + 10) shl 12) or (head.u(bodyAt + 11) shl 4) or (head.u(bodyAt + 12) shr 4)
        val depth = (((head.u(bodyAt + 12) and 1) shl 4) or (head.u(bodyAt + 13) shr 4)) + 1
        return SniffedAudio(AudioCodec.FLAC, rate.takeIf { it > 0 }, depth.takeIf { it in 8..32 })
    }

    private fun ogg(head: ByteArray): SniffedAudio? {
        val segments = head.u(26)
        val start = 27 + segments
        if (head.size < start + 16) return null
        return when {
            head.ascii(start, 7) == "\u0001vorbis" -> SniffedAudio(AudioCodec.VORBIS, head.le32(start + 12).takeIf { it > 0 })
            head.ascii(start, 8) == "OpusHead" -> SniffedAudio(AudioCodec.OPUS, 48_000)
            head.ascii(start, 5) == "\u007fFLAC" -> SniffedAudio(AudioCodec.FLAC)
            else -> null
        }
    }

    private fun wav(raf: RandomAccessFile): SniffedAudio {
        var at = 12L
        val chunk = ByteArray(8)
        repeat(32) {
            raf.seek(at)
            if (raf.read(chunk) < 8) return SniffedAudio(AudioCodec.WAV)
            val size = chunk.le32(4).toLong() and 0xFFFFFFFFL
            if (chunk.ascii(0, 4) == "fmt ") {
                val fmt = ByteArray(16)
                raf.seek(at + 8)
                if (raf.read(fmt) >= 16) return SniffedAudio(AudioCodec.WAV, fmt.le32(4).takeIf { it > 0 }, ((fmt.u(14)) or (fmt.u(15) shl 8)).takeIf { it in 8..64 })
                return SniffedAudio(AudioCodec.WAV)
            }
            at += 8 + size + (size and 1L)
        }
        return SniffedAudio(AudioCodec.WAV)
    }

    /** The first sample entry of `moov/trak/mdia/minf/stbl/stsd`: `mp4a` is AAC, `alac` is ALAC. */
    private fun mp4(raf: RandomAccessFile): SniffedAudio? {
        val path = listOf("moov", "trak", "mdia", "minf", "stbl", "stsd")
        var from = 0L
        var to = raf.length()
        for ((depth, name) in path.withIndex()) {
            val box = findBox(raf, from, to, name) ?: return null
            // stsd is a full box with an entry count: its entries start after 8 (header) + 4 (version/flags) + 4 (count)
            from = box.first + box.third + if (name == "stsd") 8 else 0
            to = box.first + box.second
            if (depth == path.lastIndex) {
                val entry = ByteArray(40)
                raf.seek(from)
                if (raf.read(entry) < 36) return null
                return when (entry.ascii(4, 4)) {
                    "mp4a" -> SniffedAudio(AudioCodec.AAC, (entry.be32(32) ushr 16).takeIf { it > 0 })
                    "alac" -> alacDetails(raf, from, entry)
                    else -> null
                }
            }
        }
        return null
    }

    private fun alacDetails(raf: RandomAccessFile, entryAt: Long, entry: ByteArray): SniffedAudio {
        // inside the audio sample entry (36 bytes), the `alac` box whose payload is version/flags + the magic cookie
        val inner = findBox(raf, entryAt + 36, entryAt + (entry.be32(0).toLong() and 0xFFFFFFFFL), "alac")
        if (inner != null) {
            val cookie = ByteArray(24)
            raf.seek(inner.first + inner.third + 4)
            if (raf.read(cookie) >= 24) return SniffedAudio(AudioCodec.ALAC, cookie.be32(20).takeIf { it > 0 }, cookie.u(5).takeIf { it in 8..32 })
        }
        return SniffedAudio(AudioCodec.ALAC, (entry.be32(32) ushr 16).takeIf { it > 0 })
    }

    /** Where the box called [name] is in `[from, to)`: (start, size, header length), or null. Walks sibling boxes by their sizes, so a large `mdat` is stepped over. */
    private fun findBox(raf: RandomAccessFile, from: Long, to: Long, name: String): Triple<Long, Long, Int>? {
        var at = from
        val header = ByteArray(16)
        var guard = 0
        while (at + 8 <= to && guard++ < 200) {
            raf.seek(at)
            if (raf.read(header, 0, 16).let { it < 8 }) return null
            var size = header.be32(0).toLong() and 0xFFFFFFFFL
            var headerLength = 8
            if (size == 1L) { size = (header.be32(8).toLong() shl 32) or (header.be32(12).toLong() and 0xFFFFFFFFL); headerLength = 16 }
            if (size == 0L) size = to - at
            if (size < headerLength) return null
            if (header.ascii(4, 4) == name) return Triple(at, size, headerLength)
            at += size
        }
        return null
    }
}
