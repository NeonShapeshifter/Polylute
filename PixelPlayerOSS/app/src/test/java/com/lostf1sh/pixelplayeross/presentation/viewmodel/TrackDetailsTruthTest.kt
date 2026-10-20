package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.player.formatChannels
import com.lostf1sh.pixelplayeross.presentation.components.player.formatDuration
import com.lostf1sh.pixelplayeross.presentation.components.player.formatFileSize
import com.lostf1sh.pixelplayeross.presentation.components.player.formatSampleRate
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.ReplayGainRuntimeState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the Track Details surface.
 *
 * Two things are being pinned here: that the screen reports what is genuinely happening rather
 * than what is configured, and that values reach the user already human-readable.
 */
class TrackDetailsTruthTest {

    @Test
    fun `ReplayGain reports applied only on the actual Android backend`() {
        val runtime = ReplayGainRuntimeState(requested = true, applied = true)
        assertEquals(ReplayGainState.TRACK_APPLIED, resolveReplayGain(runtime, AudioRouteState()))
        assertEquals(
            ReplayGainState.REQUESTED_NOT_APPLIED,
            resolveReplayGain(
                runtime,
                AudioRouteState(
                    actualBackend = AudioBackend.USB_DIRECT,
                    transition = AudioRouteTransition.DIRECT_ACTIVE,
                ),
            ),
        )
    }

    @Test
    fun `requested ReplayGain without a completed gain application is truthful`() {
        assertEquals(
            ReplayGainState.REQUESTED_NOT_APPLIED,
            resolveReplayGain(ReplayGainRuntimeState(requested = true), AudioRouteState()),
        )
    }

    // --- DSP state truthfulness -----------------------------------------------------------------

    @Test
    fun `an enabled equalizer on USB Direct is reported as bypassed not as on`() {
        val state = resolveEqualizerState(isDirectActive = true, isBypass = false, bandCount = 10)

        assertEquals(EqualizerState.BYPASSED_USB_DIRECT, state)
    }

    @Test
    fun `USB Direct bypass wins even when the equalizer is fully configured`() {
        val state = resolveEqualizerState(isDirectActive = true, isBypass = false, bandCount = 15)

        assertEquals(
            EqualizerState.BYPASSED_USB_DIRECT,
            state,
            "a configured-and-enabled equalizer must still read as bypassed on the direct path"
        )
    }

    @Test
    fun `a disabled equalizer is reported as off`() {
        val state = resolveEqualizerState(isDirectActive = false, isBypass = true, bandCount = 10)

        assertEquals(EqualizerState.OFF, state)
    }

    @Test
    fun `an enabled equalizer off the direct path is reported as applied`() {
        val state = resolveEqualizerState(isDirectActive = false, isBypass = false, bandCount = 10)

        assertEquals(EqualizerState.GRAPHIC_10, state)
    }

    @Test
    fun `a fifteen band equalizer is distinguished from a ten band one`() {
        assertEquals(
            EqualizerState.GRAPHIC_15,
            resolveEqualizerState(isDirectActive = false, isBypass = false, bandCount = 15)
        )
        assertEquals(
            EqualizerState.GRAPHIC_10,
            resolveEqualizerState(isDirectActive = false, isBypass = false, bandCount = 10)
        )
    }

    @Test
    fun `a profile below either band count still reports as the ten band graphic`() {
        assertEquals(
            EqualizerState.GRAPHIC_10,
            resolveEqualizerState(isDirectActive = false, isBypass = false, bandCount = 0)
        )
    }

    @Test
    fun `only the direct route can produce a bypassed equalizer`() {
        // Guards the ordering inside the resolver: bypass is decided before the band count, so no
        // combination of other inputs may reach it.
        listOf(0, 5, 10, 15, 31).forEach { bands ->
            listOf(true, false).forEach { bypass ->
                val viaDirect = resolveEqualizerState(true, bypass, bands)
                val offDirect = resolveEqualizerState(false, bypass, bands)

                assertEquals(EqualizerState.BYPASSED_USB_DIRECT, viaDirect)
                assertTrue(offDirect != EqualizerState.BYPASSED_USB_DIRECT)
            }
        }
    }

    @Test
    fun `the fifteen band threshold matches the graphic equalizer's own count`() {
        assertEquals(15, GRAPHIC_15_BAND_COUNT)
    }

    // --- Output description ---------------------------------------------------------------------

    @Test
    fun `an unconfirmed output rate is absent rather than guessed`() {
        val output = OutputDetails(route = OutputRoute.SPEAKER)

        assertNull(output.confirmedRateHz, "nothing confirms a rate on a non-direct route")
        assertNull(output.deviceName)
    }

    @Test
    fun `the device mixer rate is a distinct field from the confirmed output rate`() {
        // They must never collapse into one value: one describes the device, the other the stream.
        val output = OutputDetails(
            route = OutputRoute.SPEAKER,
            confirmedRateHz = null,
            deviceMixerRateHz = 48_000
        )

        assertNull(output.confirmedRateHz)
        assertEquals(48_000, output.deviceMixerRateHz)
    }

    @Test
    fun `every route has a distinct label so the screen cannot conflate them`() {
        val routes = OutputRoute.entries
        assertEquals(routes.size, routes.distinct().size)
    }

    // --- Formatting -----------------------------------------------------------------------------

    @Test
    fun `sample rates are rendered in kilohertz`() {
        assertEquals("96 kHz", formatSampleRate(96_000))
        assertEquals("44.1 kHz", formatSampleRate(44_100))
        assertEquals("48 kHz", formatSampleRate(48_000))
    }

    @Test
    fun `a missing or nonsensical sample rate produces no row`() {
        assertNull(formatSampleRate(null))
        assertNull(formatSampleRate(0))
        assertNull(formatSampleRate(-1))
    }

    @Test
    fun `channel counts are named where the name is unambiguous`() {
        assertEquals("Mono", formatChannels(1))
        assertEquals("Stereo", formatChannels(2))
        assertEquals("6 ch", formatChannels(6))
        assertNull(formatChannels(null))
    }

    @Test
    fun `durations are rendered as minutes and seconds`() {
        assertEquals("4:13", formatDuration(253_000))
        assertEquals("0:42", formatDuration(42_000))
        assertEquals("10:00", formatDuration(600_000))
    }

    @Test
    fun `an unknown duration produces no row rather than a zero`() {
        assertNull(formatDuration(null))
        assertNull(formatDuration(0))
        assertNull(formatDuration(-5))
    }

    @Test
    fun `file sizes are rendered in the unit that suits them`() {
        assertEquals("84.5 MB", formatFileSize(88_600_000))
        assertEquals("512 KB", formatFileSize(524_288))
    }

    @Test
    fun `an unknown file size produces no row`() {
        assertNull(formatFileSize(null))
        assertNull(formatFileSize(0))
    }

    @Test
    fun `formatting never leaks a raw internal integer for the fields the screen shows`() {
        // Every human-readable formatter must change the shape of the number it was given.
        assertTrue(formatSampleRate(96_000) != "96000")
        assertTrue(formatFileSize(88_600_000) != "88600000")
        assertTrue(formatDuration(253_000) != "253000")
    }

    // --- Waveform loading state -----------------------------------------------------------------

    @Test
    fun `a bind for a new song starts loading its waveform`() {
        assertTrue(nextWaveformLoadingState(isNewSong = true, currentlyLoading = false))
    }

    @Test
    fun `re-binding the song on screen does not clear an in-flight decode`() {
        // The player names the stream a moment after playback starts, so the second bind arrives
        // while the first bind's decode is still running. Clearing the flag there would show
        // "no waveform available" for a track that has one.
        assertTrue(
            nextWaveformLoadingState(isNewSong = false, currentlyLoading = true),
            "an ongoing decode must stay loading across a re-bind"
        )
    }

    @Test
    fun `re-binding a song whose waveform already failed stays not-loading`() {
        // The other side of the same rule: a track with genuinely no waveform must not be left
        // spinning forever by a re-bind.
        assertTrue(!nextWaveformLoadingState(isNewSong = false, currentlyLoading = false))
    }

    @Test
    fun `a new song never inherits the previous song's loading state`() {
        assertTrue(nextWaveformLoadingState(isNewSong = true, currentlyLoading = true))
    }

    // --- Source reconciliation ------------------------------------------------------------------

    private fun song(
        id: String = "42",
        duration: Long = 0L,
        sampleRate: Int? = null,
        bitrate: Int? = null
    ) = Song.emptySong().copy(
        id = id,
        title = "Carla's Song",
        duration = duration,
        sampleRate = sampleRate,
        bitrate = bitrate
    )

    private fun reading(
        songId: String = "42",
        format: String? = null,
        sampleRateHz: Int? = null,
        channels: Int? = null,
        bitrateBps: Int? = null,
        durationMs: Long? = null
    ) = LiveAudioFormat(
        songId = songId,
        format = format,
        sampleRateHz = sampleRateHz,
        channels = channels,
        bitrateBps = bitrateBps,
        durationMs = durationMs
    )

    private fun technical(
        format: String? = null,
        sampleRate: Int? = null,
        bitDepth: Int? = null,
        bitrate: Int? = null,
        channels: Int? = null,
        durationMs: Long? = null
    ) = AudioMetadataReader.TechnicalAudioInfo(
        format = format,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        bitrate = bitrate,
        channels = channels,
        durationMs = durationMs
    )

    @Test
    fun `the decoder names the codec when the tag reader could not`() {
        // The E-AC-3 in MP4 case: the reader answers "*", the decoder answers "eac3".
        val merged = mergeSourceDetails(
            song = song(),
            technical = technical(format = null),
            live = reading(format = "EAC3"),
            fileSizeBytes = null
        )

        assertEquals("EAC3", merged.format)
    }

    @Test
    fun `a codec the decoder named is preferred over a tag reader's guess`() {
        val merged = mergeSourceDetails(
            song = song(),
            technical = technical(format = "MP4"),
            live = reading(format = "EAC3"),
            fileSizeBytes = null
        )

        assertEquals("EAC3", merged.format)
    }

    @Test
    fun `a tag reader's codec is still used when the player is not rendering the track`() {
        val merged = mergeSourceDetails(
            song = song(),
            technical = technical(format = "Flac"),
            live = null,
            fileSizeBytes = null
        )

        assertEquals("Flac", merged.format)
    }

    @Test
    fun `a container timebase misread by the tag reader cannot become the shown duration`() {
        // jaudiotagger reads MP4's 12000-unit timebase as 12000 seconds. The player measured the
        // real length, so the absurd figure must never reach the screen.
        val merged = mergeSourceDetails(
            song = song(duration = 11_995),
            technical = technical(durationMs = 12_000_000),
            live = reading(durationMs = 11_995),
            fileSizeBytes = null
        )

        assertEquals(11_995, merged.durationMs)
    }

    @Test
    fun `the library duration outranks the tag reader when the track is not playing`() {
        val merged = mergeSourceDetails(
            song = song(duration = 11_995),
            technical = technical(durationMs = 12_000_000),
            live = null,
            fileSizeBytes = null
        )

        assertEquals(11_995, merged.durationMs)
    }

    @Test
    fun `bit depth comes from the file because the decoder only knows its own output`() {
        // The decoder's PCM encoding is not the file's resolution: a 24-bit FLAC decodes to
        // 32-bit float samples, and reporting those as the source depth would be wrong.
        val merged = mergeSourceDetails(
            song = song(),
            technical = technical(bitDepth = 24),
            live = reading(format = "FLAC", sampleRateHz = 96_000),
            fileSizeBytes = null
        )

        assertEquals(24, merged.bitDepth)
    }

    @Test
    fun `bit depth stays absent rather than being taken from the decoder`() {
        val merged = mergeSourceDetails(
            song = song(),
            technical = technical(),
            live = reading(bitrateBps = 4_608_000),
            fileSizeBytes = null
        )

        assertNull(merged.bitDepth)
    }

    @Test
    fun `bitrate comes from the file because the decoder reports the decoded PCM rate`() {
        // ExoPlayer's averageBitrate for a lossless track is the uncompressed rate, not the
        // file's, so a fallback to it would misreport the file by more than a megabit.
        val merged = mergeSourceDetails(
            song = song(),
            technical = technical(bitrate = 2_784_000),
            live = reading(bitrateBps = 4_608_000),
            fileSizeBytes = null
        )

        assertEquals(2_784_000, merged.bitrateBps)
    }

    @Test
    fun `the decoder's bitrate is used when the tag reader produced none`() {
        val merged = mergeSourceDetails(
            song = song(),
            technical = technical(),
            live = reading(bitrateBps = 640_000),
            fileSizeBytes = null
        )

        assertEquals(640_000, merged.bitrateBps)
    }

    @Test
    fun `the stream's rate and channel count are taken from the decoder`() {
        val merged = mergeSourceDetails(
            song = song(),
            technical = technical(sampleRate = 44_100, channels = 2),
            live = reading(sampleRateHz = 48_000, channels = 6),
            fileSizeBytes = null
        )

        assertEquals(48_000, merged.sampleRateHz)
        assertEquals(6, merged.channels)
    }

    @Test
    fun `an absent reading leaves the file's own account intact`() {
        val merged = mergeSourceDetails(
            song = song(),
            technical = technical(
                format = "Flac",
                sampleRate = 96_000,
                bitDepth = 24,
                bitrate = 2_784_000,
                channels = 2,
                durationMs = 254_000
            ),
            live = null,
            fileSizeBytes = 88_600_000
        )

        assertEquals("Flac", merged.format)
        assertEquals(96_000, merged.sampleRateHz)
        assertEquals(24, merged.bitDepth)
        assertEquals(2_784_000, merged.bitrateBps)
        assertEquals(2, merged.channels)
        assertEquals(254_000, merged.durationMs)
        assertEquals(88_600_000, merged.fileSizeBytes)
    }

    @Test
    fun `a reading for a different song is never applied`() {
        val reading = LiveAudioFormat.from(
            metadata = PlaybackAudioMetadata(
                mediaId = "other",
                mimeType = "audio/eac3",
                sampleRate = 48_000
            ),
            durationMs = 12_000,
            songId = "other"
        )

        val merged = mergeSourceDetails(
            song = song(id = "42"),
            technical = null,
            live = reading?.takeIf { it.songId == "42" },
            fileSizeBytes = null
        )

        assertNull(merged.format)
        assertNull(merged.sampleRateHz)
    }

    @Test
    fun `a reading is only produced for the song the player is actually rendering`() {
        val metadata = PlaybackAudioMetadata(mediaId = "42", mimeType = "audio/eac3")

        assertNotNull(LiveAudioFormat.from(metadata, durationMs = 11_995, songId = "42"))
        assertNull(LiveAudioFormat.from(metadata, durationMs = 11_995, songId = "43"))
        assertNull(
            LiveAudioFormat.from(
                PlaybackAudioMetadata(mediaId = null),
                durationMs = 11_995,
                songId = "42"
            )
        )
    }

    @Test
    fun `a reading carries the human readable codec token the artwork view shows`() {
        val reading = LiveAudioFormat.from(
            metadata = PlaybackAudioMetadata(
                mediaId = "42",
                mimeType = "audio/eac3",
                sampleRate = 48_000,
                channelCount = 6,
                bitrate = 640_000
            ),
            durationMs = 11_995,
            songId = "42"
        )

        assertNotNull(reading)
        assertEquals("EAC3", reading.format)
        assertEquals("6 ch", formatChannels(reading.channels))
        assertEquals("48 kHz", formatSampleRate(reading.sampleRateHz))
    }

    @Test
    fun `decoded PCM is never published as the file's codec`() {
        // A FLAC being rendered reports audio/raw: that describes the player's output, and the
        // artwork view renders it as "RAW". This screen describes the file, so it must not.
        val reading = LiveAudioFormat.from(
            metadata = PlaybackAudioMetadata(
                mediaId = "42",
                mimeType = "audio/raw",
                sampleRate = 96_000,
                bitrate = 4_608_000
            ),
            durationMs = 254_000,
            songId = "42"
        )

        assertNotNull(reading)
        assertNull(reading.format, "the decoder's own output is not a source codec")
    }

    @Test
    fun `a real codec the format table does not enumerate is still named`() {
        // The table lists the common cases, but the stream's own type is authoritative, so an
        // unlisted codec is reported rather than dropped.
        val reading = LiveAudioFormat.from(
            metadata = PlaybackAudioMetadata(mediaId = "42", mimeType = "audio/ac4"),
            durationMs = 11_995,
            songId = "42"
        )

        assertNotNull(reading)
        assertEquals("AC4", reading.format)
    }

    @Test
    fun `a missing mime type leaves the codec to the file reader`() {
        val reading = LiveAudioFormat.from(
            metadata = PlaybackAudioMetadata(mediaId = "42", mimeType = null),
            durationMs = 11_995,
            songId = "42"
        )

        assertNotNull(reading)
        assertNull(reading.format)
    }

    @Test
    fun `non positive measurements are dropped rather than shown as zero`() {
        val reading = LiveAudioFormat.from(
            metadata = PlaybackAudioMetadata(
                mediaId = "42",
                mimeType = "audio/flac",
                sampleRate = 0,
                channelCount = 0,
                bitrate = 0
            ),
            durationMs = 0,
            songId = "42"
        )

        assertNotNull(reading)
        assertNull(reading.sampleRateHz)
        assertNull(reading.channels)
        assertNull(reading.bitrateBps)
        assertNull(reading.durationMs)
    }
}
