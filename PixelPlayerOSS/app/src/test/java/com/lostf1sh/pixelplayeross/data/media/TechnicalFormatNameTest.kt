package com.lostf1sh.pixelplayeross.data.media

import com.lostf1sh.pixelplayeross.utils.AudioMetaUtils
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the codec name a tag reader is allowed to publish.
 *
 * The reader labels containers it understands and returns punctuation for the ones it does not —
 * an MP4 carrying E-AC-3 comes back as `*`. Trusting that would put a punctuation mark where the
 * codec belongs, so anything that is not a name is discarded and the player's own decoder gets to
 * answer instead.
 */
class TechnicalFormatNameTest {

    @Test
    fun `a genuine format name is kept`() {
        assertEquals("Flac", AudioMetadataReader.plausibleFormatName("Flac"))
        assertEquals("MP3", AudioMetadataReader.plausibleFormatName("MP3"))
        assertEquals("MP4", AudioMetadataReader.plausibleFormatName("MP4"))
    }

    @Test
    fun `the reader's punctuation fallback is rejected`() {
        assertNull(AudioMetadataReader.plausibleFormatName("*"))
        assertNull(AudioMetadataReader.plausibleFormatName("?"))
        assertNull(AudioMetadataReader.plausibleFormatName("-"))
        assertNull(AudioMetadataReader.plausibleFormatName("."))
        assertNull(AudioMetadataReader.plausibleFormatName("***"))
    }

    @Test
    fun `a missing or blank name is rejected`() {
        assertNull(AudioMetadataReader.plausibleFormatName(null))
        assertNull(AudioMetadataReader.plausibleFormatName(""))
        assertNull(AudioMetadataReader.plausibleFormatName("   "))
    }

    @Test
    fun `surrounding whitespace is trimmed rather than shown`() {
        assertEquals("Flac", AudioMetadataReader.plausibleFormatName("  Flac  "))
    }

    @Test
    fun `a length from a container the reader cannot name is not published`() {
        // The E-AC-3 in MP4 case: 12000 is the container's timebase, and multiplying it out would
        // advertise a twelve-second file as three hours and twenty minutes long.
        assertNull(AudioMetadataReader.plausibleDurationMs(trackLength = 12_000, format = "*"))
        assertNull(AudioMetadataReader.plausibleDurationMs(trackLength = 12_000, format = null))
    }

    @Test
    fun `a length from a named container is converted to milliseconds`() {
        assertEquals(254_000L, AudioMetadataReader.plausibleDurationMs(trackLength = 254, format = "Flac"))
        assertEquals(12_000L, AudioMetadataReader.plausibleDurationMs(trackLength = 12, format = "MP4"))
    }

    @Test
    fun `a missing length stays missing`() {
        assertNull(AudioMetadataReader.plausibleDurationMs(trackLength = null, format = "Flac"))
        assertNull(AudioMetadataReader.plausibleDurationMs(trackLength = 0, format = "Flac"))
        assertNull(AudioMetadataReader.plausibleDurationMs(trackLength = -3, format = "Flac"))
    }

    @Test
    fun `a decoder's own output is not a codec stored in the file`() {
        // A playing FLAC reports audio/raw. Only callers describing a file need ask this.
        assertTrue(AudioMetaUtils.isDecoderOutputFormat("audio/raw"))
        assertTrue(AudioMetaUtils.isDecoderOutputFormat("audio/pcm"))
    }

    @Test
    fun `a stored codec is not mistaken for decoder output`() {
        listOf("audio/flac", "audio/eac3", "audio/mpeg", "audio/mp4", "audio/ac4").forEach { type ->
            assertTrue(!AudioMetaUtils.isDecoderOutputFormat(type), "expected $type to be a codec")
        }
        assertTrue(!AudioMetaUtils.isDecoderOutputFormat(null))
        assertTrue(!AudioMetaUtils.isDecoderOutputFormat(""))
    }
}
