package com.lostf1sh.pixelplayeross.boundary

import com.decent.usbaudio.media3.BitPerfectReason
import com.decent.usbaudio.media3.BitPerfectVerdict
import com.decent.usbaudio.media3.DirectProcessing
import com.decent.usbaudio.media3.DirectSignalMode
import com.decent.usbaudio.media3.PcmUtils
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqBands
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqSettings
import com.lostf1sh.pixelplayeross.data.service.player.AudioProcessingRequirements
import com.lostf1sh.pixelplayeross.data.service.player.DirectPcmStageImpl
import com.lostf1sh.pixelplayeross.data.service.player.DirectSignalController
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test

/**
 * Boundaries for the Direct path's two signal modes.
 *
 * These replace a set of assertions that enshrined the previous model — that Direct *was* the
 * bit-perfect flag, and that processing must therefore be absent from it. That model is gone, so
 * the assertions are gone with it; what replaces them is narrower and checkable:
 *
 * - **Pristine** means the samples are provably untouched, not "no processor is configured".
 * - **Processed** means the samples are provably *changed*, not "a flag was set".
 * - **Transport and purity are separable**, and neither is evidence of the other.
 *
 * Every test here runs the real DSP, because the failure this guards against is precisely a path
 * that reports processing while leaving the audio alone — which is what merely removing the routing
 * guard would have produced.
 */
class DirectSignalBoundaryTest {

    private val flat = fixedSettings(enabled = true, gainDb = 0f)
    private val boosted = fixedSettings(enabled = true, gainDb = 6f)

    // ── Pristine: untouched by construction ─────────────────────────────

    @Test
    fun `bypassed EQ at unity gain leaves every sample bit-identical`() {
        val stage = stage(eq = { flat }, volume = { 1f }, requirements = { AudioProcessingRequirements() })
        assertThat(stage.configure(SAMPLE_RATE, CHANNELS)).isTrue()
        val original = ramp(SAMPLES)
        val samples = original.copyOf()

        stage.process(samples, FRAMES)

        assertThat(samples).isEqualTo(original)
        assertThat(stage.activeProcessing()).isEmpty()
    }

    @Test
    fun `a disabled EQ is a bypass even with non-zero band gains`() {
        val stage = stage(
            eq = { GraphicEqSettings(enabled = false, frequenciesHz = GraphicEqBands.TEN_BAND_HZ, gainsDb = FloatArray(10) { 6f }) },
            volume = { 1f },
            requirements = { AudioProcessingRequirements() },
        )
        stage.configure(SAMPLE_RATE, CHANNELS)
        val original = ramp(SAMPLES)
        val samples = original.copyOf()

        stage.process(samples, FRAMES)

        assertThat(samples).isEqualTo(original)
    }

    // ── Processed: the samples actually change ──────────────────────────

    @Test
    fun `an engaged EQ alters the samples and says so`() {
        val stage = stage(eq = { boosted }, volume = { 1f }, requirements = { AudioProcessingRequirements(graphicEq = true) })
        assertThat(stage.configure(SAMPLE_RATE, CHANNELS)).isTrue()
        val samples = tone(SAMPLES)

        val before = samples.copyOf()
        stage.process(samples, FRAMES)

        assertThat(samples).isNotEqualTo(before)
        assertThat(stage.activeProcessing()).contains(DirectProcessing.EQUALIZER)
    }

    @Test
    fun `digital volume alters the samples by the requested amount`() {
        val stage = stage(
            eq = { flat },
            volume = { 0.5f },
            requirements = { AudioProcessingRequirements(digitalVolume = true) },
            userGain = { 0.5f },
        )
        stage.configure(SAMPLE_RATE, CHANNELS)
        val samples = tone(SAMPLES)
        val before = samples.copyOf()

        stage.process(samples, FRAMES)

        for (index in samples.indices) {
            assertThat(samples[index]).isWithin(TOLERANCE).of(before[index] * 0.5f)
        }
        assertThat(stage.activeProcessing()).contains(DirectProcessing.DIGITAL_VOLUME)
    }

    @Test
    fun `ReplayGain is named as the cause when it is the requested feature`() {
        // Attribution is owned by the contributors, not inferred from their product. The user's
        // gain is unity here and only ReplayGain is engaged, so exactly one cause may be named —
        // reporting the other would tell a listener their own volume gesture made an album quiet.
        val stage = stage(
            eq = { flat },
            volume = { 0.7f },
            requirements = { AudioProcessingRequirements(replayGain = true) },
            userGain = { 1f },
            rgFactor = { 0.7f },
        )
        stage.configure(SAMPLE_RATE, CHANNELS)

        assertThat(stage.activeProcessing()).containsExactly(DirectProcessing.REPLAY_GAIN)
    }

    // ── The quality gate: no silent precision loss ──────────────────────

    @Test
    fun `24-bit samples survive the float working format exactly`() {
        // The claim the processed path rests on. A float32 significand is 24 bits, so every 24-bit
        // integer is representable exactly and the power-of-two scale cannot round. If this ever
        // fails, the processed path is quantising Hi-Res sources on the way through and the
        // architecture is not the one that was chosen.
        val original = IntArray(BIT_24_PROBES)
        for (index in original.indices) {
            // Spread across the range including the extremes, where a rounding error would show.
            original[index] = when (index) {
                0 -> 0
                1 -> 1
                2 -> -1
                3 -> 8_388_607  // 2^23 - 1, full-scale positive
                4 -> -8_388_608 // -2^23, full-scale negative
                else -> (index * 7919) % 8_388_608 - 4_194_304
            }
        }

        val bytes = ByteBuffer.allocate(original.size * 3).order(ByteOrder.LITTLE_ENDIAN)
        for (value in original) {
            bytes.put((value and 0xFF).toByte())
            bytes.put((value shr 8 and 0xFF).toByte())
            bytes.put((value shr 16 and 0xFF).toByte())
        }
        bytes.flip()

        // Through the float working format and back, exactly as the processed path does it.
        val floats = FloatArray(original.size)
        PcmUtils.readFloats(bytes, PCM_24BIT, floats, original.size)
        for (index in original.indices) {
            val roundTripped = Math.round(floats[index] * 8_388_608f)
            assertThat(roundTripped).isEqualTo(original[index].toLong())
        }
    }

    @Test
    fun `16-bit samples survive the float working format exactly`() {
        val original = ShortArray(512) { (it * 61 - 16_384).toShort() }
        val bytes = ByteBuffer.allocate(original.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(original)

        val floats = FloatArray(original.size)
        PcmUtils.readFloats(bytes, PCM_16BIT, floats, original.size)

        for (index in original.indices) {
            assertThat(Math.round(floats[index] * 32_768f)).isEqualTo(original[index].toLong())
        }
    }

    // ── Channel separation and saturation ───────────────────────────────

    @Test
    fun `filtering one channel does not leak into the other`() {
        val stage = stage(eq = { boosted }, volume = { 1f }, requirements = { AudioProcessingRequirements(graphicEq = true) })
        stage.configure(SAMPLE_RATE, CHANNELS)

        // Left carries a tone, right is digital silence. A filter bank that shared delay lines
        // between channels would pull the silent channel off zero.
        val frames = 4096
        val samples = FloatArray(frames * CHANNELS)
        for (frame in 0 until frames) {
            samples[frame * CHANNELS] = kotlin.math.sin(2.0 * Math.PI * 1000.0 * frame / SAMPLE_RATE).toFloat()
            samples[frame * CHANNELS + 1] = 0f
        }

        stage.process(samples, frames)

        for (frame in 0 until frames) {
            assertThat(samples[frame * CHANNELS + 1]).isEqualTo(0f)
        }
    }

    @Test
    fun `gain that would exceed full scale saturates instead of wrapping`() {
        val stage = stage(eq = { flat }, volume = { 1f }, requirements = { AudioProcessingRequirements() })
        stage.configure(SAMPLE_RATE, CHANNELS)

        // A boosted EQ on a near-full-scale input is the realistic way to overdrive this stage.
        val loud = stage(eq = { boosted }, volume = { 1f }, requirements = { AudioProcessingRequirements(graphicEq = true) })
        loud.configure(SAMPLE_RATE, CHANNELS)
        val samples = FloatArray(2048) { 0.999f }

        loud.process(samples, 1024)

        for (sample in samples) {
            assertThat(sample).isAtMost(1f)
            assertThat(sample).isAtLeast(-1f)
        }
    }

    @Test
    fun `a stage refuses a format it cannot process rather than mangling it`() {
        val stage = stage(eq = { boosted }, volume = { 1f }, requirements = { AudioProcessingRequirements() })
        assertThat(stage.configure(0, CHANNELS)).isFalse()
        assertThat(stage.configure(SAMPLE_RATE, 0)).isFalse()
        assertThat(stage.configure(SAMPLE_RATE, 99)).isFalse()
        assertThat(stage.configure(SAMPLE_RATE, CHANNELS)).isTrue()
    }

    @Test
    fun `reset drops filter history so a seek cannot ring`() {
        val stage = stage(eq = { boosted }, volume = { 1f }, requirements = { AudioProcessingRequirements(graphicEq = true) })
        stage.configure(SAMPLE_RATE, CHANNELS)
        val frames = 256
        val loud = FloatArray(frames * CHANNELS) { 0.9f }
        stage.process(loud, frames)

        stage.reset()
        val silence = FloatArray(frames * CHANNELS)
        stage.process(silence, frames)

        for (sample in silence) {
            assertThat(sample).isEqualTo(0f)
        }
    }

    // ── Transport and purity stay separate ──────────────────────────────

    @Test
    fun `the controller reports pristine exactly when no processing is required`() {
        val controller = controller(requirements = { AudioProcessingRequirements() })
        assertThat(controller.mode()).isEqualTo(DirectSignalMode.PRISTINE)
    }

    @Test
    fun `the controller reports processed for each PCM-domain requirement`() {
        val requirements = listOf(
            AudioProcessingRequirements(graphicEq = true),
            AudioProcessingRequirements(replayGain = true),
            AudioProcessingRequirements(digitalVolume = true),
        )
        for (requirement in requirements) {
            assertThat(controller(requirements = { requirement }).mode())
                .isEqualTo(DirectSignalMode.PROCESSED)
        }
    }

    @Test
    fun `transport-only requirements do not make the signal processed`() {
        // Crossfade and Android session effects are reasons the Direct transport cannot be used at
        // all. They are not PCM work, and a Direct stream must not be reported as processed because
        // of one — that would misdescribe a signal the application is leaving alone.
        val requirements = listOf(
            AudioProcessingRequirements(crossfade = true),
            AudioProcessingRequirements(androidSessionEffect = true),
        )
        for (requirement in requirements) {
            assertThat(requirement.requiresPcmProcessing).isFalse()
        }
    }

    @Test
    fun `pristine is never reported as bit-perfect without a measurement`() {
        // The single most important boundary in this file. Bit-perfectness is a measured property
        // of a payload, not a consequence of choosing a transport, and the model must not be able
        // to express the old `actualBackend == USB_DIRECT` shortcut.
        val controller = controller(requirements = { AudioProcessingRequirements() })
        assertThat(controller.mode()).isEqualTo(DirectSignalMode.PRISTINE)

        val unverified = com.decent.usbaudio.media3.BitPerfectTruth.PRISTINE_UNVERIFIED
        assertThat(unverified.verdict).isEqualTo(BitPerfectVerdict.UNKNOWN)
        assertThat(unverified.verified).isFalse()
        assertThat(unverified.reason).isEqualTo(BitPerfectReason.NOT_MEASURED)
    }

    @Test
    fun `a processed stream names the first active stage and counts the rest`() {
        val truth = com.decent.usbaudio.media3.BitPerfectTruth.processed(
            setOf(DirectProcessing.EQUALIZER, DirectProcessing.REPLAY_GAIN, DirectProcessing.DIGITAL_VOLUME)
        )
        assertThat(truth.verdict).isEqualTo(BitPerfectVerdict.NO)
        assertThat(truth.reason).isEqualTo(BitPerfectReason.EQUALIZER_ACTIVE)
        assertThat(truth.additionalReasons).isEqualTo(2)
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private fun stage(
        eq: () -> GraphicEqSettings,
        volume: () -> Float,
        requirements: () -> AudioProcessingRequirements,
        // Under the shared gain model the *contributors* own attribution, not the composed value.
        // Defaults describe a stream with neither engaged, which is what the pristine cases want.
        userGain: () -> Float = { 1f },
        rgFactor: () -> Float? = { null },
    ) = DirectPcmStageImpl(
        requirements = requirements,
        graphicEq = eq,
        masterVolume = volume,
        userDigitalGain = userGain,
        replayGainFactor = rgFactor,
    )

    private fun controller(requirements: () -> AudioProcessingRequirements) =
        DirectSignalController(
            requirements = requirements,
            graphicEq = { flat },
            masterVolume = { 1f },
        )

    private fun fixedSettings(enabled: Boolean, gainDb: Float) = GraphicEqSettings(
        enabled = enabled,
        frequenciesHz = GraphicEqBands.TEN_BAND_HZ,
        gainsDb = FloatArray(GraphicEqBands.TEN_BAND_COUNT) { gainDb },
    )

    private fun ramp(size: Int) = FloatArray(size) { (it % 101 - 50) / 64f }

    private fun tone(size: Int) = FloatArray(size) {
        kotlin.math.sin(2.0 * Math.PI * 440.0 * it / SAMPLE_RATE).toFloat() * 0.5f
    }

    private companion object {
        const val SAMPLE_RATE = 96_000
        const val CHANNELS = 2
        const val FRAMES = 1024
        /** [DirectPcmStage.process] is sized `frames * channels`; fixtures must match that exactly. */
        const val SAMPLES = FRAMES * CHANNELS
        const val BIT_24_PROBES = 64
        const val TOLERANCE = 1e-7f
        const val PCM_16BIT = 2
        const val PCM_24BIT = 0x15
    }
}
