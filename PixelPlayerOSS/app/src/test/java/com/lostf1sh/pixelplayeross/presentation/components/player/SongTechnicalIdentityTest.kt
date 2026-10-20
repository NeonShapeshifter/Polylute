package com.lostf1sh.pixelplayeross.presentation.components.player

import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The identity line under Now Playing.
 *
 * It states what the *file* is, so the interesting cases are all about which authority is allowed
 * to answer: the file and the library describe the track, and the player describes its own output.
 * Mixing those up is what produced a line reading `96.0 kHz` for a 24-bit FLAC.
 */
class SongTechnicalIdentityTest {

    private fun reader(
        format: String? = null,
        sampleRate: Int? = null,
        bitDepth: Int? = null,
    ) = AudioMetadataReader.TechnicalAudioInfo(
        format = format,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
    )

    // ---- C and D: the canonical form --------------------------------------------------------

    @Test
    fun `the canonical line is format then sample rate then bit depth`() {
        val identity = SongTechnicalIdentity(
            format = "FLAC",
            sampleRateHz = 96_000,
            bitDepth = 24,
        )

        assertEquals("FLAC · 96 kHz · 24-bit", identity.canonical)
    }

    @Test
    fun `a known FLAC at 96 kHz and 24 bit reads as the preferred form`() {
        val identity = resolveSongTechnicalIdentity(
            libraryMimeType = "audio/flac",
            runtimeMimeType = null,
            librarySampleRateHz = 96_000,
            runtimeSampleRateHz = null,
            reader = reader(format = "Flac", sampleRate = 96_000, bitDepth = 24),
        )

        assertEquals("FLAC · 96 kHz · 24-bit", identity.canonical)
    }

    @Test
    fun `an ordinary track reads with its own values`() {
        assertEquals(
            "WAV · 48 kHz · 24-bit",
            SongTechnicalIdentity("WAV", 48_000, 24).canonical,
        )
        assertEquals(
            "ALAC · 44.1 kHz · 16-bit",
            SongTechnicalIdentity("ALAC", 44_100, 16).canonical,
        )
    }

    @Test
    fun `an unknown field is left out rather than guessed`() {
        assertEquals("FLAC · 96 kHz", SongTechnicalIdentity("FLAC", 96_000, null).canonical)
        assertEquals("96 kHz · 24-bit", SongTechnicalIdentity(null, 96_000, 24).canonical)
        assertNull(SongTechnicalIdentity().canonical)
    }

    // ---- F: one rate convention for the whole app -------------------------------------------

    @Test
    fun `the rate is formatted by the same rule Track Details uses`() {
        // `formatHz` is the single implementation both surfaces format rates with. The line used to
        // format its own, always with one decimal, so a 96 kHz source read `96.0 kHz` here and
        // `96 kHz` one tap away.
        assertEquals(
            formatHz(96_000),
            SongTechnicalIdentity(sampleRateHz = 96_000).canonical,
        )
        assertEquals("96 kHz", SongTechnicalIdentity(sampleRateHz = 96_000).canonical)
        assertEquals("44.1 kHz", SongTechnicalIdentity(sampleRateHz = 44_100).canonical)
    }

    // ---- E: a decoder's output is not the file's format -------------------------------------

    @Test
    fun `audio raw cannot replace a known source format`() {
        // The player reports `audio/raw` while decoding, which is why the line used to lose its
        // format entirely: the value was taken, then discarded, and the library was never asked.
        val identity = resolveSongTechnicalIdentity(
            libraryMimeType = "audio/flac",
            runtimeMimeType = "audio/raw",
            librarySampleRateHz = 96_000,
            runtimeSampleRateHz = 96_000,
            reader = null,
        )

        assertEquals("FLAC", identity.format)
    }

    @Test
    fun `a decoder output cannot supply a format the library does not have either`() {
        val identity = resolveSongTechnicalIdentity(
            libraryMimeType = null,
            runtimeMimeType = "audio/raw",
            librarySampleRateHz = 96_000,
            runtimeSampleRateHz = 96_000,
            reader = null,
        )

        assertNull(identity.format, "decoded PCM is not a source format")
        assertEquals("96 kHz", identity.canonical, "and the line does not claim one")
    }

    @Test
    fun `the file's own header answers when the library has no type`() {
        val identity = resolveSongTechnicalIdentity(
            libraryMimeType = null,
            runtimeMimeType = "audio/raw",
            librarySampleRateHz = null,
            runtimeSampleRateHz = null,
            reader = reader(format = "Flac", sampleRate = 96_000, bitDepth = 24),
        )

        assertEquals("FLAC", identity.format)
    }

    @Test
    fun `a real codec the player names is used when nothing better is known`() {
        val identity = resolveSongTechnicalIdentity(
            libraryMimeType = null,
            runtimeMimeType = "audio/eac3",
            librarySampleRateHz = null,
            runtimeSampleRateHz = 48_000,
            reader = null,
        )

        assertEquals("EAC3", identity.format)
        assertEquals("EAC3 · 48 kHz", identity.canonical)
    }

    @Test
    fun `bit depth is taken from the file and never from the player`() {
        // Media3's bit depth describes the PCM handed to the sink, not the resolution the file
        // holds, so it is not an input here at all: with no file reading there is no depth, and the
        // line says less rather than saying something wrong.
        val identity = resolveSongTechnicalIdentity(
            libraryMimeType = "audio/flac",
            runtimeMimeType = "audio/raw",
            librarySampleRateHz = 96_000,
            runtimeSampleRateHz = 96_000,
            reader = null,
        )

        assertNull(identity.bitDepth)
    }

    // ---- H: knowledge is not withdrawn ------------------------------------------------------

    @Test
    fun `a reading that says less does not take away what was already known`() {
        val known = SongTechnicalIdentity("FLAC", 96_000, 24)
        val quieter = SongTechnicalIdentity(format = "FLAC")

        assertEquals(known, quieter.keepingWhatWasKnown(known))
    }

    @Test
    fun `a reading that says more is taken in full`() {
        val partial = SongTechnicalIdentity("FLAC", 96_000)
        val fuller = SongTechnicalIdentity("FLAC", 96_000, 24)

        assertEquals(fuller, fuller.keepingWhatWasKnown(partial))
    }

    @Test
    fun `late metadata upgrades the line once and does not oscillate`() {
        // Start of playback: the rate is known, the format is not yet. Then the header arrives.
        var line = SongTechnicalIdentity(sampleRateHz = 96_000)
        val seen = mutableListOf<String?>(line.canonical)

        line = SongTechnicalIdentity("FLAC", 96_000, 24).keepingWhatWasKnown(line)
        seen += line.canonical
        // The player finishes decoding and goes quiet; nothing may be withdrawn.
        line = SongTechnicalIdentity().keepingWhatWasKnown(line)
        seen += line.canonical

        assertEquals(
            listOf<String?>("96 kHz", "FLAC · 96 kHz · 24-bit", "FLAC · 96 kHz · 24-bit"),
            seen,
        )
    }

    // ---- I and J: a new song starts clean ---------------------------------------------------

    @Test
    fun `a different song resolves to its own facts and nothing of the previous one`() {
        val first = resolveSongTechnicalIdentity(
            libraryMimeType = "audio/flac",
            runtimeMimeType = null,
            librarySampleRateHz = 96_000,
            runtimeSampleRateHz = null,
            reader = reader(format = "Flac", sampleRate = 96_000, bitDepth = 24),
        )
        val second = resolveSongTechnicalIdentity(
            libraryMimeType = "audio/wav",
            runtimeMimeType = null,
            librarySampleRateHz = 44_100,
            runtimeSampleRateHz = null,
            reader = reader(format = "WAV", sampleRate = 44_100, bitDepth = 16),
        )

        assertEquals("WAV · 44.1 kHz · 16-bit", second.canonical)
        assertEquals("FLAC · 96 kHz · 24-bit", first.canonical)
        assertNull(
            SongTechnicalIdentity().keepingWhatWasKnown(SongTechnicalIdentity()).canonical,
            "a fresh song starts with nothing carried over",
        )
    }

    // ---- G: two forms, one boolean ----------------------------------------------------------

    @Test
    fun `the canonical line is drawn whenever it fits`() {
        assertEquals(
            "FLAC · 96 kHz · 24-bit",
            chooseTechnicalLine("FLAC · 96 kHz · 24-bit", "FLAC · 96 kHz", canonicalFits = true),
        )
    }

    @Test
    fun `the single fallback drops the bit depth and never the format`() {
        val fallback = SongTechnicalIdentity("FLAC", 96_000, 24).withoutBitDepth

        assertEquals("FLAC · 96 kHz", fallback)
        assertEquals(
            "FLAC · 96 kHz",
            chooseTechnicalLine("FLAC · 96 kHz · 24-bit", fallback, canonicalFits = false),
        )
    }

    @Test
    fun `a line with nothing to fall back to is still drawn`() {
        assertEquals(
            "24-bit",
            chooseTechnicalLine("24-bit", null, canonicalFits = false),
        )
        assertEquals("", chooseTechnicalLine(null, null, canonicalFits = false))
    }
}
