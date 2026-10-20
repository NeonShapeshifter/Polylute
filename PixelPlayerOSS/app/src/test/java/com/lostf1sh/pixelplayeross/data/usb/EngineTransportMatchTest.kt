package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.EngineTransportMatch
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-141 (latent: the owner's 4 899 FLACs are all stereo). On an automatic advance the engine for
 * the next item is created before Media3 configures its format, against the previous item's stereo
 * transport. The guard compared only the rate, so a mono FLAC at the same rate played at 2× (an
 * octave up) and a 5.1 one at 3×, interleaved — and `configure` then kept the engine alive for the
 * whole track. The engine writes its source frames as-is: its channel count must be the transport's.
 */
class EngineTransportMatchTest {
    @Test
    fun `stereo at the transport's rate matches`() {
        assertThat(EngineTransportMatch.mismatch(44_100, 2, transportRate = 44_100, transportChannels = 2)).isNull()
    }

    @Test
    fun `a mono FLAC at the same rate is refused, not played an octave up`() {
        assertThat(EngineTransportMatch.mismatch(44_100, 1, transportRate = 44_100, transportChannels = 2))
            .isEqualTo("Channel mismatch: FLAC=1 USB=2")
    }

    @Test
    fun `a 5_1 FLAC at the same rate is refused, not played at 3x`() {
        assertThat(EngineTransportMatch.mismatch(48_000, 6, transportRate = 48_000, transportChannels = 2))
            .isEqualTo("Channel mismatch: FLAC=6 USB=2")
    }

    @Test
    fun `the rate check is kept`() {
        assertThat(EngineTransportMatch.mismatch(48_000, 2, transportRate = 192_000, transportChannels = 2))
            .isEqualTo("Rate mismatch: FLAC=48000 USB=192000")
    }

    @Test
    fun `wiring - the engine start uses the guard`() {
        val sink = File(
            "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt"
        ).readText().replace("\r\n", "\n")
        val start = sink.substringAfter("private fun startNativeEngineIfFlac(").substringBefore("\n    }\n")
        assertThat(start).contains("EngineTransportMatch.mismatch(")
        assertThat(start).contains("engine.getChannels()")
    }
}
