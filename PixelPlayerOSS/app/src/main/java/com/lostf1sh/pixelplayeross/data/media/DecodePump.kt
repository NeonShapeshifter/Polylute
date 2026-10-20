package com.lostf1sh.pixelplayeross.data.media

import android.media.MediaCodec
import java.nio.ByteBuffer

/** What the decode loop needs of a codec: `MediaCodec` in the app, a fake with a latency in the tests. */
internal interface DecoderPort {
    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun inputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, size: Int, presentationTimeUs: Long, flags: Int)

    /** Fills [info] and returns the output index, or one of `MediaCodec.INFO_*`. */
    fun dequeueOutputBuffer(info: PumpInfo, timeoutUs: Long): Int
    fun outputBuffer(index: Int): ByteBuffer?
    fun releaseOutputBuffer(index: Int)
}

/** What the decode loop needs of the extractor. */
internal interface SamplePort {
    /** Reads the next sample into [buffer]; negative at the end of the stream. */
    fun readSampleData(buffer: ByteBuffer): Int
    val sampleTime: Long
    fun advance()
}

/** The part of `MediaCodec.BufferInfo` the loop reads. */
internal class PumpInfo {
    var offset = 0
    var size = 0
    var flags = 0
}

/**
 * The extractor → codec → PCM loop of [WaveformDecoder], on its own so it can be run against a fake codec (B31 P2.5).
 *
 * **What it changed.** The loop used to queue *one* input buffer and then wait up to 10 ms for *one* output buffer, every round: one
 * round trip to the codec per container frame (about 2,700 of them in a 4:13 FLAC), with the codec's other input buffers empty the whole
 * time. It now keeps the codec's input full (every input buffer that is free is filled, without waiting), takes every output that is
 * ready, and waits only when nothing could be queued, which is when the next thing to happen is an output. The PCM it hands over, and in
 * what order, is the same.
 *
 * It gives up, as a decode that produced nothing, when the codec has neither taken an input nor given an output for [STALL_ROUNDS]
 * rounds of [timeoutUs] (five seconds), instead of holding its thread for as long as a wedged codec likes.
 */
internal class DecodePump(
    private val codec: DecoderPort,
    private val source: SamplePort,
    private val timeoutUs: Long = DEFAULT_TIMEOUT_US,
) {
    /** What a run did, for the log line: inputs queued, outputs taken, and how many times it had to wait for the codec. */
    class Stats {
        var inputs = 0
        var outputs = 0
        var waits = 0
    }

    val stats = Stats()

    fun run(
        ensureActive: () -> Unit,
        onFormatChanged: () -> Unit,
        onOutput: (buffer: ByteBuffer, offset: Int, size: Int) -> Unit,
    ) {
        val info = PumpInfo()
        var inputDone = false
        var outputDone = false
        var idleRounds = 0

        while (!outputDone) {
            ensureActive()
            var progressed = false

            // Keep the codec's input full: every buffer that is free right now, none waited for.
            var queued = false
            while (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(0L)
                if (inputIndex < 0) break
                val buffer = codec.inputBuffer(inputIndex) ?: ByteBuffer.allocate(0)
                val size = source.readSampleData(buffer)
                if (size < 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inputIndex, size, source.sampleTime, 0)
                    source.advance()
                }
                stats.inputs++
                queued = true
                progressed = true
            }

            // Take every output that is ready. Only the first one is waited for, and only when nothing could be queued above (the codec
            // is full, or the stream is over): then an output is the next thing that will happen.
            var wait = if (queued && !inputDone) 0L else timeoutUs
            if (wait > 0L) stats.waits++
            while (true) {
                val outputIndex = codec.dequeueOutputBuffer(info, wait)
                wait = 0L
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        onFormatChanged()
                        progressed = true
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> progressed = true
                    outputIndex >= 0 -> {
                        progressed = true
                        stats.outputs++
                        val flags = info.flags
                        val isCodecConfig = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val buffer = codec.outputBuffer(outputIndex)
                        if (buffer != null && info.size > 0 && !isCodecConfig) onOutput(buffer, info.offset, info.size)
                        codec.releaseOutputBuffer(outputIndex)
                        if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            break
                        }
                    }
                    else -> break
                }
            }

            idleRounds = if (progressed) 0 else idleRounds + 1
            check(idleRounds < STALL_ROUNDS) { "decoder stalled: no input taken and no output for $STALL_ROUNDS rounds" }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_US = 10_000L

        /** 500 rounds of 10 ms: five seconds with the codec doing nothing at all. */
        const val STALL_ROUNDS = 500
    }
}
