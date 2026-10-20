package com.lostf1sh.pixelplayeross.data.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Decodes a track's PCM and reduces it to a [Waveform], without ever holding the whole track.
 *
 * The decode is a straight `MediaExtractor` -> `MediaCodec` loop ([DecodePump]). Output buffers are reduced to
 * per-bucket peak and RMS as they arrive and then dropped, so peak memory is one decoder output
 * buffer plus the (fixed-size) bucket arrays — a 4-minute 96 kHz FLAC produces roughly 23 million
 * sample frames and this never allocates room for more than a few thousand at once.
 *
 * Runs on [Dispatchers.IO] and is cooperatively cancellable between buffers, so closing the view
 * stops the work promptly instead of pinning a core.
 *
 * This is intentionally a *separate* decode from playback. It does not touch the player, the
 * audio sink, the queue or the route, and it cannot seek or otherwise disturb what is playing.
 *
 * **B31 P2.5, what it costs while music plays.** It used to take 7.4 s for a 4:13 FLAC on the debug build, at the pool thread's normal
 * priority, with the USB writer running beside it. Now: (1) the thread runs at `THREAD_PRIORITY_BACKGROUND` for the decode (the
 * scheduler keeps such a thread off the foreground cores and behind everything else, the USB writer included) and gets its priority back
 * after; (2) the PCM is read one frame in [PcmReducer.strideFor] instead of every frame; (3) the codec's input is kept full instead of
 * one buffer per round trip ([DecodePump]). The log line `WaveformDecoder: ...` says where the time went, on the device.
 */
object WaveformDecoder {

    private const val TAG = "WaveformDecoder"

    /**
     * Returns a waveform for [uri]/[file], or null if the source cannot be decoded.
     *
     * Null is a normal outcome, not an error: an unsupported codec, a file with no duration in its
     * container, or a corrupt file all land here and the UI simply shows no waveform.
     */
    suspend fun decode(
        context: Context,
        uri: Uri?,
        file: File?,
        bucketCount: Int = WaveformBuilder.WAVEFORM_BUCKET_COUNT
    ): Waveform? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        // Background for the length of the decode only: the pool's threads are shared, and this one is handed back as it was.
        val tid = Process.myTid()
        val previousPriority = runCatching { Process.getThreadPriority(tid) }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        val startedAtNs = System.nanoTime()
        try {
            when {
                uri != null -> extractor.setDataSource(context, uri, null)
                file != null -> extractor.setDataSource(file.absolutePath)
                else -> return@withContext null
            }

            val trackIndex = selectAudioTrack(extractor) ?: run {
                Timber.tag(TAG).d("No audio track to analyse")
                return@withContext null
            }
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)

            val sampleRate = inputFormat.optInt(MediaFormat.KEY_SAMPLE_RATE)
            val durationUs = inputFormat.optLong(MediaFormat.KEY_DURATION)
            if (sampleRate <= 0 || durationUs <= 0) {
                // Without both we cannot map buckets onto time, and guessing would produce a
                // waveform whose seek mapping is a lie. Report "no waveform" instead.
                Timber.tag(TAG).d("Source lacks sample rate or duration; skipping waveform")
                return@withContext null
            }
            val totalFrames = durationUs * sampleRate / 1_000_000L
            if (totalFrames <= 0) return@withContext null

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val stride = PcmReducer.strideFor(totalFrames, bucketCount)
            val builder = WaveformBuilder(totalFrames = PcmReducer.framesRead(totalFrames, stride), bucketCount = bucketCount)
            val reducer = PcmReducer(builder, stride).also {
                it.channelCount = inputFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                it.pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            }
            val pump = DecodePump(MediaCodecPort(decoder), ExtractorPort(extractor))
            var reduceNs = 0L

            pump.run(
                ensureActive = { coroutineContext.ensureActive() },
                onFormatChanged = {
                    val outputFormat = decoder.outputFormat
                    reducer.channelCount = outputFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    reducer.pcmEncoding = outputFormat.optInt(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                },
                onOutput = { buffer, offset, size ->
                    val t0 = System.nanoTime()
                    reducer.accept(buffer, offset, size)
                    reduceNs += System.nanoTime() - t0
                },
            )

            val totalMs = (System.nanoTime() - startedAtNs) / 1_000_000L
            val reduceMs = reduceNs / 1_000_000L
            // Where the time went, for the device: the codec and the extractor (everything but the reduction), and the reduction.
            Timber.tag(TAG).i(
                "decoded %s: %d frames, stride %d, total %d ms (codec+extractor %d ms, reduce %d ms), %d inputs, %d outputs, %d waits, priority bg",
                mime, totalFrames, stride, totalMs, totalMs - reduceMs, reduceMs, pump.stats.inputs, pump.stats.outputs, pump.stats.waits,
            )

            // A decode that produced nothing usable is a miss, not an empty waveform: an empty
            // waveform would render as a flat line that looks like a real silent track.
            if (builder.framesAccepted <= 0) return@withContext null

            builder.build(sampleRate = sampleRate, durationMs = durationUs / 1000L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Waveform decode failed for ${uri ?: file?.absolutePath}")
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { Process.setThreadPriority(previousPriority) }
        }
    }

    private class MediaCodecPort(private val codec: MediaCodec) : DecoderPort {
        private val info = MediaCodec.BufferInfo()
        override fun dequeueInputBuffer(timeoutUs: Long) = codec.dequeueInputBuffer(timeoutUs)
        override fun inputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)
        override fun queueInputBuffer(index: Int, size: Int, presentationTimeUs: Long, flags: Int) =
            codec.queueInputBuffer(index, 0, size, presentationTimeUs, flags)

        override fun dequeueOutputBuffer(info: PumpInfo, timeoutUs: Long): Int {
            val index = codec.dequeueOutputBuffer(this.info, timeoutUs)
            if (index >= 0) {
                info.offset = this.info.offset
                info.size = this.info.size
                info.flags = this.info.flags
            }
            return index
        }

        override fun outputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)
        override fun releaseOutputBuffer(index: Int) = codec.releaseOutputBuffer(index, false)
    }

    private class ExtractorPort(private val extractor: MediaExtractor) : SamplePort {
        override fun readSampleData(buffer: ByteBuffer): Int = extractor.readSampleData(buffer, 0)
        override val sampleTime: Long get() = extractor.sampleTime
        override fun advance() {
            extractor.advance()
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return index
        }
        return null
    }

    private fun MediaFormat.optInt(key: String, default: Int = -1): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default

    private fun MediaFormat.optLong(key: String, default: Long = -1L): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(default) else default
}
