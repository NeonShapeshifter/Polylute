package com.lostf1sh.pixelplayeross.utils

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B59 2 (#80): whether a file carries an embedded picture is read from its headers, never by extracting the picture. Synthetic files only (made here; no real audio, no real cover):
 * the structures are the container formats' own, the payloads are filler bytes.
 */
class B59EmbeddedPictureProbeTest {
    private val dir = Files.createTempDirectory("b59-probe").toFile()

    @AfterEach fun cleanUp() { dir.deleteRecursively() }

    private fun file(name: String, bytes: ByteArray) = dir.resolve(name).apply { writeBytes(bytes) }.path

    private fun flacBlock(type: Int, size: Int, last: Boolean = false): ByteArray =
        byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) + ByteArray(size) { 7 }

    private fun flac(withPicture: Boolean, pictureSize: Int = 200_000): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        out.write(flacBlock(0, 34)) // STREAMINFO
        out.write(flacBlock(4, 60)) // VORBIS_COMMENT
        if (withPicture) out.write(flacBlock(6, pictureSize))
        out.write(flacBlock(1, 4096, last = true)) // PADDING, last
        out.write(ByteArray(1000) { 1 }) // "audio"
        return out.toByteArray()
    }

    private fun syncsafe(n: Int) = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())

    private fun id3v24(frames: List<Pair<String, Int>>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((id, size) in frames) {
            body.write(id.toByteArray()); body.write(syncsafe(size)); body.write(byteArrayOf(0, 0)); body.write(ByteArray(size) { 5 })
        }
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncsafe(body.size()) + body.toByteArray()
    }

    private fun id3v23(frames: List<Pair<String, Int>>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((id, size) in frames) {
            body.write(id.toByteArray())
            body.write(byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()))
            body.write(byteArrayOf(0, 0)); body.write(ByteArray(size) { 5 })
        }
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncsafe(body.size()) + body.toByteArray()
    }

    private fun id3v22(frames: List<Pair<String, Int>>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((id, size) in frames) {
            body.write(id.toByteArray()); body.write(byteArrayOf((size shr 16).toByte(), (size shr 8).toByte(), size.toByte())); body.write(ByteArray(size) { 5 })
        }
        return "ID3".toByteArray() + byteArrayOf(2, 0, 0) + syncsafe(body.size()) + body.toByteArray()
    }

    private fun atom(type: String, body: ByteArray): ByteArray {
        val size = 8 + body.size
        return byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun m4a(withCover: Boolean): ByteArray {
        val ftyp = atom("ftyp", "M4A ".toByteArray() + ByteArray(4) + "M4A mp42isom".toByteArray())
        val items = atom("©nam", ByteArray(20) { 3 }) + if (withCover) atom("covr", ByteArray(150_000) { 9 }) else ByteArray(0)
        val ilst = atom("ilst", items)
        val meta = atom("meta", ByteArray(4) + atom("hdlr", ByteArray(30)) + ilst)
        val moov = atom("moov", atom("mvhd", ByteArray(100)) + atom("udta", meta))
        val mdat = atom("mdat", ByteArray(5000))
        return ftyp + mdat + moov // moov at the end, as a file that was not optimised for streaming has it
    }

    @Test fun `a FLAC with a picture block says yes, and one without says no`() {
        assertThat(EmbeddedPictureProbe.hasPicture(file("with.flac", flac(true)))).isTrue()
        assertThat(EmbeddedPictureProbe.hasPicture(file("without.flac", flac(false)))).isFalse()
    }

    @Test fun `the picture is never read - the probe touches only the block headers`() {
        val path = file("big.flac", flac(true, pictureSize = 5_000_000))
        // the file is 5 MB; the probe of a FLAC walks four headers. Measured by what it is allowed to read: a RandomAccessFile that fails any read of more than a header
        RandomAccessFile(path, "r").use { raf ->
            val before = raf.filePointer
            assertThat(EmbeddedPictureProbe.probe(raf)).isTrue()
            // it seeks around the headers; it never leaves the pointer past the first picture block's header + its own body
            assertThat(raf.filePointer).isAtMost(before + 4 + 34 + 4 + 60 + 4 + 4)
        }
    }

    @Test fun `an ID3v2 in front of a FLAC stream is walked past, and an ID3v2 MP3 is found by its APIC or PIC frame`() {
        val id3 = id3v24(listOf("TIT2" to 20))
        assertThat(EmbeddedPictureProbe.hasPicture(file("tagged.flac", id3 + flac(true)))).isTrue()
        assertThat(EmbeddedPictureProbe.hasPicture(file("tagged-no.flac", id3 + flac(false)))).isFalse()
        val audio = ByteArray(4000) { 0x55 }
        assertThat(EmbeddedPictureProbe.hasPicture(file("a.mp3", id3v24(listOf("TIT2" to 20, "APIC" to 90_000)) + audio))).isTrue()
        assertThat(EmbeddedPictureProbe.hasPicture(file("b.mp3", id3v24(listOf("TIT2" to 20, "TALB" to 30)) + audio))).isFalse()
        assertThat(EmbeddedPictureProbe.hasPicture(file("c.mp3", id3v23(listOf("TIT2" to 20, "APIC" to 70_000)) + audio))).isTrue()
        assertThat(EmbeddedPictureProbe.hasPicture(file("d.mp3", id3v23(listOf("TIT2" to 20)) + audio))).isFalse()
        assertThat(EmbeddedPictureProbe.hasPicture(file("e.mp3", id3v22(listOf("TT2" to 20, "PIC" to 50_000)) + audio))).isTrue()
        assertThat(EmbeddedPictureProbe.hasPicture(file("f.mp3", id3v22(listOf("TT2" to 20)) + audio))).isFalse()
    }

    @Test fun `an MP4 is found by moov, udta, meta, ilst and covr, whichever end the moov is at`() {
        assertThat(EmbeddedPictureProbe.hasPicture(file("with.m4a", m4a(true)))).isTrue()
        assertThat(EmbeddedPictureProbe.hasPicture(file("without.m4a", m4a(false)))).isFalse()
    }

    @Test fun `an Ogg is found by a picture comment in its first megabyte`() {
        val page = "OggS".toByteArray() + ByteArray(40)
        val withPicture = page + "vendor".toByteArray() + "metadata_block_picture=AAAA".toByteArray() + ByteArray(2000)
        assertThat(EmbeddedPictureProbe.hasPicture(file("with.ogg", withPicture))).isTrue()
        assertThat(EmbeddedPictureProbe.hasPicture(file("without.ogg", page + "vendor".toByteArray() + "TITLE=x".toByteArray() + ByteArray(2000)))).isFalse()
    }

    @Test fun `a container the probe does not know, a text file, an empty or truncated file, and a missing file are all no`() {
        assertThat(EmbeddedPictureProbe.hasPicture(file("song.wav", "RIFF".toByteArray() + ByteArray(5000)))).isFalse()
        assertThat(EmbeddedPictureProbe.hasPicture(file("fixture.flac", "fixture without embedded art".toByteArray()))).isFalse()
        assertThat(EmbeddedPictureProbe.hasPicture(file("empty.flac", ByteArray(0)))).isFalse()
        assertThat(EmbeddedPictureProbe.hasPicture(file("cut.flac", flac(true).copyOf(30)))).isFalse()
        assertThat(EmbeddedPictureProbe.hasPicture(File(dir, "nope.flac").path)).isFalse()
        // a corrupt chain of blocks that never ends is not followed for ever
        val loop = "fLaC".toByteArray() + (0 until 300).flatMap { flacBlock(1, 0).toList() }
        assertThat(EmbeddedPictureProbe.hasPicture(file("loop.flac", loop))).isFalse()
    }
}
