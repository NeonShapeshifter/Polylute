package com.lostf1sh.pixelplayeross.data.media

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * B31 P2.5 / P2.7: **what the Kotlin half of the waveform decode costs on the host**, for a 4:13 stereo 16-bit track at 44.1 kHz (11.1
 * million frames, in the 4,096-frame buffers a FLAC decoder hands over), reading every frame (what it did) and one in
 * [PcmReducer.strideFor] (what it does now). Cold = the first run of the process (the nearest the JVM gets to a debug build that is never
 * compiled ahead of time), warm = the median of the next runs (a release-like steady state).
 *
 * A measurement, not a check: opt-in (`B31_BENCH=1`), writes `$B31_BENCH_OUT`. It says nothing of the codec or of the phone's time; the
 * phone's own split is the log line `WaveformDecoder: decoded ...` (codec+extractor ms against reduce ms).
 */
class WaveformReduceBench {
    private val frames = 253L * 44_100
    private val chunkFrames = 4096
    private val channels = 2

    private fun music(): ByteBuffer {
        val random = Random(7)
        val buffer = ByteBuffer.allocateDirect(chunkFrames * channels * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until chunkFrames * channels) buffer.putShort((random.nextInt(-12_000, 12_000)).toShort())
        buffer.flip()
        return buffer
    }

    private fun reduce(stride: Int, chunk: ByteBuffer): Double {
        val t0 = System.nanoTime()
        val builder = WaveformBuilder(PcmReducer.framesRead(frames, stride), WaveformBuilder.WAVEFORM_BUCKET_COUNT)
        val reducer = PcmReducer(builder, stride).also { it.channelCount = channels }
        var left = frames
        while (left > 0) {
            val n = minOf(chunkFrames.toLong(), left).toInt()
            reducer.accept(chunk, 0, n * channels * 2)
            left -= n
        }
        builder.build()
        return (System.nanoTime() - t0) / 1e6
    }

    @Test
    fun `the reduction of a 4 minute 13 second track, every frame and one in ten, cold and warm`() {
        Assumptions.assumeTrue(System.getenv("B31_BENCH") == "1")
        val chunk = music()
        val stride = PcmReducer.strideFor(frames, WaveformBuilder.WAVEFORM_BUCKET_COUNT)
        val rows = ArrayList<Triple<String, Double, Double>>()
        // The order matters for "cold": every-frame first (the old path), then the strided one, each cold on its own first run.
        for ((label, s) in listOf("every frame (stride 1, what it did)" to 1, "one in $stride (stride $stride, what it does)" to stride)) {
            val cold = reduce(s, chunk)
            repeat(2) { reduce(s, chunk) }
            val warm = DoubleArray(5) { reduce(s, chunk) }.sorted()[2]
            rows += Triple(label, cold, warm)
        }
        val table = buildString {
            appendLine("Waveform reduction on the host, 4:13 stereo 16-bit 44.1 kHz (11.1 M frames, 4096-frame buffers), ms")
            for ((label, cold, warm) in rows) appendLine("%-48s cold %9.1f   warm %9.1f".format(label, cold, warm))
            appendLine("ratio every-frame / strided: cold %.1f x, warm %.1f x".format(rows[0].second / rows[1].second, rows[0].third / rows[1].third))
        }
        println(table)
        val out = File(System.getenv("B31_BENCH_OUT") ?: "../../../analysis/audit-2026-09-27/batches/B31-evidence/logs/waveform-reduce-host.txt")
        out.parentFile?.mkdirs()
        out.writeText(table)
    }
}
