package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqBands
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test

/**
 * Where the Graphic EQ sits relative to the rest of the processing chain, and what that position
 * buys.
 *
 * `DualPlayerEngine` builds `[HiResSampleRateCap, SurroundDownmix, HeadphoneGraphicEq]`. The order is
 * not cosmetic: a five-channel source reaches the chain as five channels, and a stereo graphic EQ
 * applied to five interleaved channels would be filtering a layout it was never designed for and
 * would call "left" and "right" things that are neither. Putting the downmix first means the filters
 * only ever see the two channels the headphones actually receive.
 *
 * The chain is driven here exactly the way `AudioProcessingPipeline` drives it — configure each
 * processor with the previous one's declared output format, then queue each one's output into the
 * next — so these assertions are about the real composition rather than about the processors in
 * isolation.
 */
class HeadphoneEqChainPlacementTest {

    private fun flat10() = FloatArray(GraphicEqBands.TEN_BAND_COUNT)

    private fun settings10(gains: FloatArray = flat10(), enabled: Boolean = true) =
        GraphicEqSettings(enabled, GraphicEqBands.TEN_BAND_HZ, gains)

    /**
     * A minimal stand-in for `AudioProcessingPipeline`'s configure/queue walk.
     *
     * It reproduces the two behaviours that matter for placement. First, a processor that reports
     * itself inactive after `configure` is dropped from the chain and its declared format is
     * ignored — `HiResSampleRateCapAudioProcessor` does exactly that whenever the stream is not
     * above its ceiling, so at 48 kHz the chain really is just downmix-then-EQ. Second, each
     * processor receives the previous one's output buffer.
     *
     * One pass is enough here because the processors under test each consume all of their input and
     * produce all of their output in a single call. The real pipeline loops to handle processors
     * that buffer across calls, which is not the behaviour any of these assertions depend on.
     */
    private class Chain(private val processors: List<AudioProcessor>) {

        /** Simple names of the processors that survived `configure`, in chain order. */
        val placement = mutableListOf<String>()

        var outputFormat: AudioFormat = AudioFormat.NOT_SET
            private set

        fun configure(input: AudioFormat): AudioFormat {
            var format = input
            processors.forEach { processor ->
                val declared = processor.configure(format)
                if (processor.isActive()) {
                    // media3 asserts this too: an active processor must declare a real format.
                    check(declared != AudioFormat.NOT_SET) {
                        "${processor.javaClass.simpleName} is active but declared no format"
                    }
                    format = declared
                    placement += processor.javaClass.simpleName
                }
            }
            outputFormat = format
            return format
        }

        fun process(input: ByteBuffer): ByteBuffer {
            var current = input
            processors.filter { it.isActive() }.forEach { processor ->
                processor.queueInput(current)
                current = processor.getOutput()
            }
            return current
        }
    }

    private fun chainWith(eq: HeadphoneGraphicEqProcessor) = Chain(
        listOf(HiResSampleRateCapAudioProcessor(), SurroundDownmixProcessor(), eq)
    )

    private val eqName = HeadphoneGraphicEqProcessor::class.java.simpleName
    private val downmixName = SurroundDownmixProcessor::class.java.simpleName
    private val capName = HiResSampleRateCapAudioProcessor::class.java.simpleName

    private fun pcm16Buffer(samples: List<Int>): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        samples.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    private fun ByteBuffer.readShorts(): List<Int> {
        val view = duplicate().order(ByteOrder.nativeOrder())
        val out = ArrayList<Int>(view.remaining() / Short.SIZE_BYTES)
        while (view.remaining() >= Short.SIZE_BYTES) out.add(view.getShort().toInt())
        return out
    }

    private val surround = AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT)

    private fun surroundFrames(frames: Int) =
        (0 until frames).flatMap { frame -> (0 until 6).map { channel -> (frame + channel) * 100 } }

    // --- Placement ------------------------------------------------------------------------

    @Test
    fun `the graphic eq is handed stereo because the downmix runs first`() {
        val eq = HeadphoneGraphicEqProcessor { settings10(flat10().also { it[5] = 12f }) }
        val chain = chainWith(eq)

        val output = chain.configure(surround)

        // The downmix collapses the layout and the EQ is configured after it, so it is handed two
        // channels rather than six.
        assertThat(chain.placement).containsExactly(downmixName, eqName).inOrder()
        assertThat(output.channelCount).isEqualTo(2)
    }

    @Test
    fun `the hi-res cap takes itself out of the chain when there is nothing to cap`() {
        val chain = chainWith(HeadphoneGraphicEqProcessor { settings10() })

        chain.configure(AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))

        // The cap declares itself inactive below its ceiling, and media3 drops inactive processors.
        // Nothing downstream is affected: the samples it would have passed through arrive anyway.
        assertThat(chain.placement).doesNotContain(capName)
        assertThat(chain.placement).containsExactly(downmixName, eqName).inOrder()
    }

    @Test
    fun `at an ultra-high rate the eq is still handed stereo, at the rate Android will play`() {
        val chain = chainWith(HeadphoneGraphicEqProcessor { settings10(flat10().also { it[5] = 12f }) })

        val output = chain.configure(AudioFormat(384_000, 6, C.ENCODING_PCM_16BIT))

        // The cap is active here, so it stays; and because the EQ runs last it is tuned for the
        // rate that actually reaches the sink rather than the rate the file was authored at.
        assertThat(chain.placement).containsExactly(capName, downmixName, eqName).inOrder()
        assertThat(output.sampleRate).isEqualTo(192_000)
        assertThat(output.channelCount).isEqualTo(2)
    }

    @Test
    fun `the eq is always last, so nothing downstream can undo it`() {
        listOf(
            AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT),
            AudioFormat(384_000, 6, C.ENCODING_PCM_16BIT),
            AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT),
        ).forEach { format ->
            val chain = chainWith(HeadphoneGraphicEqProcessor { settings10(flat10().also { it[5] = 12f }) })

            chain.configure(format)

            assertThat(chain.placement.last()).isEqualTo(eqName)
        }
    }

    @Test
    fun `moving the eq ahead of the downmix would hand it six channels`() {
        // The negative control for the placement tests: this is what the wrong order looks like, and
        // it is why the order is asserted rather than assumed.
        val misplaced = Chain(
            listOf(
                HeadphoneGraphicEqProcessor { settings10(flat10().also { it[5] = 12f }) },
                SurroundDownmixProcessor(),
            )
        )

        val output = misplaced.configure(surround)

        assertThat(misplaced.placement.first()).isEqualTo(eqName)
        // The downmix still collapses it afterwards, so the mistake would be silent in the output
        // format and audible only in the sound: a stereo EQ applied across a six-channel layout.
        assertThat(output.channelCount).isEqualTo(2)
    }

    @Test
    fun `turning the eq on does not change the shape of the stream`() {
        val gains = flat10().also { it[5] = 12f }
        val frames = 64

        val off = chainWith(HeadphoneGraphicEqProcessor { settings10(gains, enabled = false) })
        val offFormat = off.configure(surround)
        val offOutput = off.process(pcm16Buffer(surroundFrames(frames)))

        val on = chainWith(HeadphoneGraphicEqProcessor { settings10(gains, enabled = true) })
        val onFormat = on.configure(surround)
        val onOutput = on.process(pcm16Buffer(surroundFrames(frames)))

        // Same rate, same channel count, same encoding, same number of frames. Enabling the EQ is a
        // change of filtering, never a change of stream shape.
        assertThat(onFormat).isEqualTo(offFormat)
        assertThat(onOutput.remaining()).isEqualTo(offOutput.remaining())
        assertThat(onOutput.remaining()).isEqualTo(frames * 2 * Short.SIZE_BYTES)
    }

    @Test
    fun `a surround source is filtered, not merely passed through`() {
        val gains = flat10().also { it[5] = 12f }
        val frames = 64

        val off = chainWith(HeadphoneGraphicEqProcessor { settings10(gains, enabled = false) })
        off.configure(surround)
        val downmixed = off.process(pcm16Buffer(surroundFrames(frames))).readShorts()

        val on = chainWith(HeadphoneGraphicEqProcessor { settings10(gains, enabled = true) })
        on.configure(surround)
        val filtered = on.process(pcm16Buffer(surroundFrames(frames))).readShorts()

        assertThat(filtered).hasSize(downmixed.size)
        assertThat(filtered).isNotEqualTo(downmixed)
    }

    @Test
    fun `a flat eq in the real chain reproduces the unprocessed downmix exactly`() {
        // The bypass promise, composed with the real downmix rather than measured in isolation:
        // with the EQ off, the samples leaving the chain are the samples the downmix produced.
        val frames = 64

        val bypassing = chainWith(HeadphoneGraphicEqProcessor { GraphicEqSettings.BYPASS })
        bypassing.configure(surround)
        val eqOff = bypassing.process(pcm16Buffer(surroundFrames(frames))).readShorts()

        val downmixOnly = Chain(listOf(HiResSampleRateCapAudioProcessor(), SurroundDownmixProcessor()))
        downmixOnly.configure(surround)
        val reference = downmixOnly.process(pcm16Buffer(surroundFrames(frames))).readShorts()

        assertThat(eqOff).isEqualTo(reference)
        assertThat(eqOff.any { it != 0 }).isTrue()
    }

    @Test
    fun `the chain leaves the stream's sample rate alone at every supported rate`() {
        listOf(44_100, 48_000, 96_000, 192_000).forEach { sampleRate ->
            val chain = chainWith(HeadphoneGraphicEqProcessor { settings10(flat10().also { it[5] = 12f }) })

            val output = chain.configure(AudioFormat(sampleRate, 6, C.ENCODING_PCM_16BIT))

            assertThat(output.sampleRate).isEqualTo(sampleRate)
            assertThat(output.channelCount).isEqualTo(2)
        }
    }
}
