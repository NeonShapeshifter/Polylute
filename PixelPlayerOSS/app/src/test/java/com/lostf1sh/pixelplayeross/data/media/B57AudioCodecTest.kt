package com.lostf1sh.pixelplayeross.data.media

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.components.player.resolveSongTechnicalIdentity
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B57 2.6: the player line says the codec, not the container, and the file's rate, not the output's. Fixtures are generated tones (ffmpeg): Vorbis in Ogg at 44.1 kHz, Opus in Ogg (the
 * `.opus` extension is renamed `.ogg` below, which is the file the owner has), AAC in M4A and ALAC in M4A.
 *
 * **Where today's rate comes from** (`SongTechnicalIdentity.kt`): `reader?.sampleRate` (the tag reader's stream header), then `song.sampleRate` (the library), and, before this batch,
 * last of all the player's `runtimeSampleRateHz`: the rate the player reports, which for a Bluetooth or USB sink can be the output's. That last source is gone.
 */
class B57AudioCodecTest {
    private fun fixture(name: String, asName: String = name): File {
        val bytes = javaClass.classLoader!!.getResourceAsStream("editor/$name")!!.use { it.readBytes() }
        return File.createTempFile("codec", "-$asName").apply { writeBytes(bytes); deleteOnExit() }
    }

    @Test
    fun `an ogg that holds Opus is Opus at 48 kHz and an ogg that holds Vorbis is Vorbis at its own rate`() {
        val opusInOgg = fixture("tone-opus.opus", "tone.ogg")
        val vorbis = fixture("tone-vorbis.ogg")
        assertThat(CodecSniffer.sniff(opusInOgg)).isEqualTo(SniffedAudio(AudioCodec.OPUS, 48_000))
        assertThat(CodecSniffer.sniff(vorbis)).isEqualTo(SniffedAudio(AudioCodec.VORBIS, 44_100))
    }

    @Test
    fun `an m4a that holds AAC is AAC and one that holds ALAC is ALAC with its depth`() {
        assertThat(CodecSniffer.sniff(fixture("tone-aac.m4a"))).isEqualTo(SniffedAudio(AudioCodec.AAC, 44_100))
        assertThat(CodecSniffer.sniff(fixture("tone-alac.m4a"))).isEqualTo(SniffedAudio(AudioCodec.ALAC, 44_100, 16))
    }

    @Test
    fun `the other containers name their codec too`() {
        assertThat(CodecSniffer.sniff(fixture("tone.flac"))).isEqualTo(SniffedAudio(AudioCodec.FLAC, 44_100, 16))
        assertThat(CodecSniffer.sniff(fixture("tone-highres.flac"))).isEqualTo(SniffedAudio(AudioCodec.FLAC, 192_000, 24))
        assertThat(CodecSniffer.sniff(fixture("tone.mp3"))!!.codec).isEqualTo(AudioCodec.MP3)
        assertThat(CodecSniffer.sniff(fixture("tone.wav"))).isEqualTo(SniffedAudio(AudioCodec.WAV, 44_100, 16))
    }

    @Test
    fun `a file that is not audio, or is too short to say, is no codec`() {
        assertThat(CodecSniffer.sniff(File.createTempFile("xxx", ".ogg").apply { writeBytes(ByteArray(40) { 7 }); deleteOnExit() })).isNull()
        assertThat(CodecSniffer.sniff(File.createTempFile("yyy", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() })).isNull()
        assertThat(CodecSniffer.sniff(File("/no/such/file.ogg"))).isNull()
    }

    @Test
    fun `the info page says both, the player says only the codec`() {
        assertThat(AudioCodec.describe(AudioCodec.OPUS, "/m/Say What You Will.ogg")).isEqualTo("Opus (.ogg file)")
        assertThat(AudioCodec.describe(AudioCodec.VORBIS, "a.ogg")).isEqualTo("Vorbis")
        assertThat(AudioCodec.describe(AudioCodec.ALAC, "a.m4a")).isEqualTo("ALAC")
        assertThat(AudioCodec.describe(AudioCodec.AAC, "a.mp4")).isEqualTo("AAC (.mp4 file)")
        assertThat(AudioCodec.describe(AudioCodec.FLAC, "a.flac")).isEqualTo("FLAC")
        assertThat(AudioCodec.describe(null, "a.ogg")).isNull()
        assertThat(AudioCodec.playerLabel(AudioCodec.OPUS)).isEqualTo("Opus")
    }

    // ── the player line, end to end through the resolver ────────────────────────────────────────────

    private fun line(file: File, libraryMime: String?, runtimeMime: String? = null, runtimeRate: Int? = null, libraryRate: Int? = null): String? {
        val sniffed = CodecSniffer.sniff(file)
        val reader = AudioMetadataReader.TechnicalAudioInfo(sampleRate = sniffed?.sampleRateHz, bitDepth = sniffed?.bitDepth, codec = sniffed?.codec)
        return resolveSongTechnicalIdentity(libraryMime, runtimeMime, libraryRate, runtimeRate, reader).canonical
    }

    @Test
    fun `the owner's Opus file in an ogg says Opus and 48 kHz and not OGG`() {
        assertThat(line(fixture("tone-opus.opus", "tone.ogg"), libraryMime = "audio/ogg")).isEqualTo("Opus · 48 kHz")
    }

    @Test
    fun `a Vorbis file at 44 point 1 says 44 point 1 even while the output runs at 48`() {
        assertThat(line(fixture("tone-vorbis.ogg"), libraryMime = "audio/ogg", runtimeRate = 48_000)).isEqualTo("Vorbis · 44.1 kHz")
    }

    @Test
    fun `m4a lines name the codec with the depth for ALAC`() {
        assertThat(line(fixture("tone-alac.m4a"), libraryMime = "audio/mp4")).isEqualTo("ALAC · 44.1 kHz · 16-bit")
        assertThat(line(fixture("tone-aac.m4a"), libraryMime = "audio/mp4")).isEqualTo("AAC · 44.1 kHz")
    }

    @Test
    fun `the player's rate is never a source and an unreadable ogg says no codec rather than a wrong one`() {
        val unreadable = AudioMetadataReader.TechnicalAudioInfo()
        val identity = resolveSongTechnicalIdentity("audio/ogg", "audio/raw", null, 48_000, unreadable)
        assertThat(identity.format).isNull()
        assertThat(identity.sampleRateHz).isNull()
        // the library's own rate still counts, and a container that can only hold one codec names it
        assertThat(resolveSongTechnicalIdentity("audio/flac", null, 96_000, 48_000, null).canonical).isEqualTo("FLAC · 96 kHz")
        // while the player is playing a codec it can name, that name is used when the header could not be read
        assertThat(resolveSongTechnicalIdentity("audio/ogg", "audio/opus", null, null, null).format).isEqualTo("Opus")
    }

    @Test
    fun `reading the codec reads a handful of bytes and does no work per list row`() {
        val source = File("src/main/java/com/lostf1sh/pixelplayeross/data/media/AudioCodec.kt").readText()
        // bounded reads only: no whole-file read, no decoder
        assertThat(source).doesNotContain("readBytes()")
        assertThat(source).doesNotContain("MediaCodec")
        val surface = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/player/SongTechnicalIdentity.kt").readText()
        assertThat(surface).contains("LaunchedEffect(song.id)") // once per song, keyed on the song being played
    }
}
