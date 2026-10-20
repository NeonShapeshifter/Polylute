package com.lostf1sh.pixelplayeross.data.stats

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import com.decent.usbaudio.media3.BitPerfectTruth
import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics
import org.junit.jupiter.api.Test

/** B28a: the audio facts are read from what the app already holds, and a fact it does not hold stays null. */
class ListenAudioFactsAssemblerTest {

    private fun live(rate: Int = 44_100, mime: String? = "audio/flac", encoding: Int = C.ENCODING_INVALID) =
        DualPlayerEngine.AudioFormatSnapshot(mime, rate, 2, encoding, 0)

    @Test
    fun `on Direct the source is the sinks own reading and the transport is what the DAC confirmed`() {
        val facts = ListenAudioFactsAssembler.assemble(
            directRoute(rateHz = 192_000),
            directDiagnostics(rateHz = 192_000, bits = 32),
            androidOutput = AndroidOutput(ListenRoute.SPEAKER, null),
            liveFormat = live(rate = 44_100),
        )
        assertThat(facts.route).isEqualTo(ListenRoute.USB_DIRECT)
        assertThat(facts.sourceRateHz).isEqualTo(192_000)
        assertThat(facts.sourceBits).isEqualTo(32)
        assertThat(facts.transportRateHz).isEqualTo(192_000)
        assertThat(facts.transportBits).isEqualTo(32)
        // The Android output is not consulted for a Direct stream.
        assertThat(facts.deviceName).isEqualTo("SNOWSKY Melody")
    }

    @Test
    fun `on Direct a fact the sink did not publish falls back to the players live format`() {
        val facts = ListenAudioFactsAssembler.assemble(
            directRoute(),
            directDiagnostics(rateHz = null, bits = null, codec = null),
            null,
            live(rate = 88_200, mime = "audio/flac", encoding = C.ENCODING_PCM_24BIT),
        )
        assertThat(facts.sourceRateHz).isEqualTo(88_200)
        assertThat(facts.sourceBits).isEqualTo(24)
        assertThat(facts.sourceCodec).isEqualTo("audio/flac")
    }

    @Test
    fun `off Direct the sinks leftover numbers are not the streams and the live format is`() {
        // The sink publishes nothing for an Android stream, so these are from the last Direct one.
        val facts = ListenAudioFactsAssembler.assemble(
            androidRoute(),
            directDiagnostics(rateHz = 192_000, bits = 32, codec = "audio/flac"),
            AndroidOutput(ListenRoute.WIRED, "Headset"),
            live(rate = 48_000, mime = "audio/mp4a-latm"),
        )
        assertThat(facts.route).isEqualTo(ListenRoute.WIRED)
        assertThat(facts.sourceRateHz).isEqualTo(48_000)
        assertThat(facts.sourceCodec).isEqualTo("audio/mp4a-latm")
        assertThat(facts.sourceBits).isNull()
        assertThat(facts.transportRateHz).isNull()
        assertThat(facts.transportBits).isNull()
        assertThat(facts.signal).isNull()
        // A wired headset's name is not a DAC's.
        assertThat(facts.deviceName).isNull()
    }

    @Test
    fun `an Android USB output is named as a DAC and a bus path is not a name`() {
        val named = ListenAudioFactsAssembler.assemble(
            androidRoute(), UsbDirectAudioDiagnostics(), AndroidOutput(ListenRoute.ANDROID_USB, "Topping D10s"), null,
        )
        val path = ListenAudioFactsAssembler.assemble(
            androidRoute(), UsbDirectAudioDiagnostics(), AndroidOutput(ListenRoute.ANDROID_USB, "/dev/bus/usb/001/004"), null,
        )
        assertThat(named.route).isEqualTo(ListenRoute.ANDROID_USB)
        assertThat(named.deviceName).isEqualTo("Topping D10s")
        assertThat(path.deviceName).isNull()
    }

    @Test
    fun `nothing the model does not hold is invented`() {
        val facts = ListenAudioFactsAssembler.assemble(
            androidRoute(bitPerfect = BitPerfectTruth.PRISTINE_UNVERIFIED), UsbDirectAudioDiagnostics(), null, null,
        )
        assertThat(facts).isEqualTo(ListenAudioFacts(route = ListenRoute.OTHER, bitPerfect = ListenBitPerfect.UNKNOWN))
    }

    @Test
    fun `a failed Direct claim is the speaker, as the Details sheet says`() {
        val route = androidRoute().copy(
            desiredBackend = AudioBackend.USB_DIRECT,
            transition = AudioRouteTransition.DIRECT_FAILED_FALLBACK,
            reason = AudioRouteReason.DIRECT_FAILED_FALLBACK,
            bitPerfect = BitPerfectTruth.NOT_DIRECT,
        )
        val facts = ListenAudioFactsAssembler.assemble(route, UsbDirectAudioDiagnostics(), AndroidOutput(ListenRoute.BLUETOOTH, null), null)
        assertThat(facts.route).isEqualTo(ListenRoute.SPEAKER)
        assertThat(facts.bitPerfect).isEqualTo(ListenBitPerfect.NO)
    }

    @Test
    fun `a route that is only eligible for Direct is not Direct`() {
        val negotiating = directRoute().copy(transition = AudioRouteTransition.DIRECT_NEGOTIATING, actualBackend = AudioBackend.ANDROID_AUDIO)
        val facts = ListenAudioFactsAssembler.assemble(negotiating, directDiagnostics(), AndroidOutput(ListenRoute.SPEAKER, null), null)
        assertThat(facts.route).isEqualTo(ListenRoute.SPEAKER)
        assertThat(facts.signal).isNull()
        assertThat(facts.transportRateHz).isNull()
    }

    @Test
    fun `the Android output follows the same precedence as the Details sheet`() {
        fun classify(vararg types: Int) = AndroidOutputClassifier.classify(types.map { AndroidOutputDevice(it, "n$it") }).route
        assertThat(classify(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).isEqualTo(ListenRoute.SPEAKER)
        assertThat(classify(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)).isEqualTo(ListenRoute.WIRED)
        assertThat(classify(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)).isEqualTo(ListenRoute.BLUETOOTH)
        assertThat(classify(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET)).isEqualTo(ListenRoute.ANDROID_USB)
        assertThat(classify(AudioDeviceInfo.TYPE_HDMI)).isEqualTo(ListenRoute.OTHER)
        assertThat(AndroidOutputClassifier.classify(emptyList()).route).isEqualTo(ListenRoute.OTHER)
    }

    @Test
    fun `the file's own codec is the source and the raw PCM the Direct sink was handed is the transport`() {
        val facts = ListenAudioFactsAssembler.assemble(
            directRoute(),
            directDiagnostics(codec = "audio/raw"),
            null,
            live(mime = "audio/raw"),
            fileMimeType = "audio/flac",
        )
        assertThat(facts.sourceCodec).isEqualTo("audio/flac")
        assertThat(facts.transportCodec).isEqualTo("audio/raw")
    }

    @Test
    fun `raw PCM is never named as the format that was heard`() {
        // No file mime and a sink that only saw decoded samples: the source codec is unknown, not "raw".
        val onDirect = ListenAudioFactsAssembler.assemble(directRoute(), directDiagnostics(codec = "audio/raw"), null, live(mime = "audio/raw"))
        assertThat(onDirect.sourceCodec).isNull()
        assertThat(onDirect.transportCodec).isEqualTo("audio/raw")

        // A file mime that is itself raw (a decoder's output named by mistake) is ignored in favour of what the sink knows.
        val ignoredRaw = ListenAudioFactsAssembler.assemble(
            directRoute(), directDiagnostics(codec = "audio/flac"), null, null, fileMimeType = "audio/raw",
        )
        assertThat(ignoredRaw.sourceCodec).isEqualTo("audio/flac")
    }

    @Test
    fun `off Direct there is no transport codec, whatever the decoder says`() {
        val facts = ListenAudioFactsAssembler.assemble(
            androidRoute(), UsbDirectAudioDiagnostics(), AndroidOutput(ListenRoute.SPEAKER, null), live(mime = "audio/raw"),
            fileMimeType = " audio/mp4a-latm ",
        )
        assertThat(facts.sourceCodec).isEqualTo("audio/mp4a-latm")
        assertThat(facts.transportCodec).isNull()
    }

    @Test
    fun `Purity and Resonance are the signal mode of the stream and nothing else`() {
        val pristine = ListenAudioFactsAssembler.assemble(directRoute(DirectSignalMode.PRISTINE), directDiagnostics(), null, null)
        val processed = ListenAudioFactsAssembler.assemble(directRoute(DirectSignalMode.PROCESSED), directDiagnostics(), null, null)
        assertThat(pristine.signal).isEqualTo(ListenSignal.PURITY)
        assertThat(processed.signal).isEqualTo(ListenSignal.RESONANCE)
    }
}
