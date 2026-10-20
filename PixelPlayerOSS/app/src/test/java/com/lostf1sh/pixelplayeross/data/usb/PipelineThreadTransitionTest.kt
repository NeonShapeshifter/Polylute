package com.lostf1sh.pixelplayeross.data.usb

import androidx.media3.common.C
import com.decent.usbaudio.media3.PipelineThread
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/**
 * AUD-143: the ExoPlayer pipeline lost ~1.3 s of audio at every automatic track change (device payload
 * harness: 8 212 480 of 9 216 000 bytes for two 24/96 halves, in PROCESSED and with the debug switch
 * alike; 7 192 424 of 8 841 600 across 96 → 44.1 kHz). The sink stopped its streaming thread — which
 * clears the queue — before deciding that no engine was going to take over, then built another.
 *
 * The sink cannot be driven here (it needs usbfs), so what it decides is in [PipelineThread] and the
 * real [UsbStreamingThread] is run against a fake writer. The sink's use of both is pinned by
 * `PipelineThreadWiringBoundaryTest`.
 */
class PipelineThreadTransitionTest {

    private val encoding = C.ENCODING_PCM_24BIT
    private val channels = 2
    private val framesPerBuffer = 8192
    private val bytesPerBuffer = framesPerBuffer * channels * 3

    /** A DAC: writes are recorded in order, and blocked while [gate] is closed (the ring is full). */
    private class FakeWriter : UsbStreamingThread.PcmWriter {
        val events = CopyOnWriteArrayList<String>()
        val written = CopyOnWriteArrayList<ByteArray>()
        @Volatile var gate = CountDownLatch(0)
        @Volatile var drains = 0
        @Volatile var flushes = 0
        @Volatile private var frames = 0L
        override fun write(pcmBuffer: FloatArray) {
            gate.await(5, TimeUnit.SECONDS)
            frames += pcmBuffer.size / 2
        }
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) {
            gate.await(5, TimeUnit.SECONDS)
            written += pcmBuffer
            events += "W${pcmBuffer[0]}"
            frames += pcmBuffer.size / 6
        }
        override val framesWritten: Long get() = frames
        override fun flush() { flushes++; events += "F" }
        override fun drainEndOfStream(): Boolean { drains++; events += "D"; return true }
    }

    /** One decoded buffer whose bytes all carry [tag], so the order of what reached the DAC is readable. */
    private fun buffer(tag: Int) = ByteArray(bytesPerBuffer) { tag.toByte() }

    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(2)
    }

    private fun newThread(writer: FakeWriter): UsbStreamingThread =
        UsbStreamingThread(writer, initiallyPaused = false, channelCount = channels).also { it.start() }

    // ── the decision ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an engine is wanted only when it is allowed and the file is a local FLAC`() {
        assertThat(PipelineThread.wantsEngine(true, "/music/a.flac")).isTrue()
        assertThat(PipelineThread.wantsEngine(true, "/music/A.FLAC")).isTrue()
        // PROCESSED, or the bit-perfect debug switch: the signal mode or the switch refuse the engine.
        assertThat(PipelineThread.wantsEngine(false, "/music/a.flac")).isFalse()
        assertThat(PipelineThread.wantsEngine(true, "/music/a.m4a")).isFalse()
        assertThat(PipelineThread.wantsEngine(true, null)).isFalse()
    }

    @Test
    fun `what a producer decision does to the thread`() {
        // The pipeline stays the producer: a running thread is left alone, a missing one is started.
        assertThat(PipelineThread.actionFor(wantsEngine = false, threadRunning = true)).isEqualTo(PipelineThread.Action.KEEP)
        assertThat(PipelineThread.actionFor(wantsEngine = false, threadRunning = false)).isEqualTo(PipelineThread.Action.START)
        // An engine takes over: the thread goes, whether or not there is one.
        assertThat(PipelineThread.actionFor(wantsEngine = true, threadRunning = true)).isEqualTo(PipelineThread.Action.REPLACE_BY_ENGINE)
        assertThat(PipelineThread.actionFor(wantsEngine = true, threadRunning = false)).isEqualTo(PipelineThread.Action.REPLACE_BY_ENGINE)
    }

    // ── (a) a transition at the same format, no engine ──────────────────────────────────────────

    @Test
    fun `a transition in PROCESSED at the same format neither stops the thread nor clears its queue`() {
        val writer = FakeWriter()
        writer.gate = CountDownLatch(1)           // the DAC is busy: A's tail is queued
        val thread = newThread(writer)
        try {
            repeat(6) { thread.enqueueRaw(buffer(it + 1), encoding) }          // A: 1..6
            waitUntil { thread.queueSize() == 5 }                                // one is in the DAC's hands

            // The item transition: the producer is chosen again, and it is still the pipeline.
            val after = PipelineThread.keepOrStart(thread) { error("a running thread must not be replaced") }
            assertThat(after).isSameInstanceAs(thread)
            assertThat(thread.queueSize()).isEqualTo(5)                          // nothing was cleared
            assertThat(thread.queuedFrames()).isEqualTo(6L * framesPerBuffer)    // 5 queued + 1 in flight

            repeat(2) { thread.enqueueRaw(buffer(it + 7), encoding) }          // B: 7, 8 follow on
            writer.gate.countDown()
            waitUntil { writer.written.size == 8 }
        } finally {
            writer.gate.countDown()
            thread.stop()
        }
        // A's tail played out and B followed, gapless, in order, without a frame lost.
        assertThat(writer.written.map { it[0].toInt() }).containsExactly(1, 2, 3, 4, 5, 6, 7, 8).inOrder()
        assertThat(writer.flushes).isEqualTo(0)
    }

    @Test
    fun `without a running thread the pipeline starts one`() {
        var created = 0
        val writer = FakeWriter()
        val made = PipelineThread.keepOrStart(null) { created++; newThread(writer) }
        try {
            assertThat(created).isEqualTo(1)
            assertThat(made).isNotNull()
        } finally {
            made.stop()
        }
    }

    // ── (b) an engine takes over ─────────────────────────────────────────────────────────────────

    @Test
    fun `with an engine allowed the thread is replaced and what it held goes with it, as before`() {
        val writer = FakeWriter()
        writer.gate = CountDownLatch(1)
        val thread = newThread(writer)
        repeat(6) { thread.enqueueRaw(buffer(it + 1), encoding) }
        waitUntil { thread.queueSize() == 5 }

        val stopper = Thread { Thread.sleep(50); writer.gate.countDown() }   // the in-flight write ends
        stopper.start()
        val after = PipelineThread.replaceByEngine(thread)
        stopper.join()

        assertThat(after).isNull()
        assertThat(thread.queueSize()).isEqualTo(0)
        // Only the buffer the DAC already had: the queued five were obsolete (the engine plays the item).
        assertThat(writer.written).hasSize(1)
        assertThat(PipelineThread.replaceByEngine(null)).isNull()
    }

    // ── (c) a format change plays the tail out first ─────────────────────────────────────────────

    @Test
    fun `a format change drains the tail before the stream is rebuilt`() {
        val writer = FakeWriter()
        writer.gate = CountDownLatch(1)
        val thread = newThread(writer)
        try {
            repeat(5) { thread.enqueueRaw(buffer(it + 1), encoding) }
            waitUntil { thread.queueSize() == 4 }
            val opener = Thread { Thread.sleep(80); writer.gate.countDown() }   // the DAC consumes at its own pace
            opener.start()

            val outcome = PipelineThread.playOutTail(thread, playing = true, sampleRate = 96_000)
            opener.join()

            assertThat(outcome).isEqualTo(PipelineThread.TailOutcome.PLAYED_OUT)
            // Every queued buffer was written, then the residual drain (the marker's job), in that order.
            assertThat(writer.events).containsExactly("W1", "W2", "W3", "W4", "W5", "D").inOrder()
            assertThat(thread.queuedFrames()).isEqualTo(0L)
        } finally {
            writer.gate.countDown()
            thread.stop()
        }
        // The rebuild (releaseUsbStream) stops the thread: nothing is left to lose.
        assertThat(writer.written).hasSize(5)
        assertThat(writer.drains).isEqualTo(1)
    }

    @Test
    fun `stopping at once, as the rebuild used to, loses the tail - which is what the drain is for`() {
        val writer = FakeWriter()
        writer.gate = CountDownLatch(1)
        val thread = newThread(writer)
        repeat(5) { thread.enqueueRaw(buffer(it + 1), encoding) }
        waitUntil { thread.queueSize() == 4 }
        val opener = Thread { Thread.sleep(50); writer.gate.countDown() }
        opener.start()
        thread.stop()
        opener.join()
        assertThat(writer.written.size).isLessThan(5)
    }

    @Test
    fun `a seek or a skip flushed the thread first, so a rebuild does not wait`() {
        val writer = FakeWriter()
        val thread = newThread(writer)
        try {
            val started = System.nanoTime()
            val outcome = PipelineThread.playOutTail(thread, playing = true, sampleRate = 96_000)
            assertThat(outcome).isEqualTo(PipelineThread.TailOutcome.NOTHING_QUEUED)
            assertThat(System.nanoTime() - started).isLessThan(TimeUnit.MILLISECONDS.toNanos(200))
            assertThat(writer.drains).isEqualTo(0)
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `a paused pipeline has nothing audible to finish, so a rebuild does not wait for it`() {
        val writer = FakeWriter()
        val thread = UsbStreamingThread(writer, initiallyPaused = true, channelCount = channels).also { it.start() }
        try {
            thread.enqueueRaw(buffer(1), encoding)
            assertThat(PipelineThread.playOutTail(thread, playing = false, sampleRate = 96_000))
                .isEqualTo(PipelineThread.TailOutcome.NOT_PLAYING)
            // And the wait itself is bounded for a thread that cannot take the marker.
            assertThat(thread.queueEndOfStream()).isTrue()
            val started = System.nanoTime()
            assertThat(thread.awaitEndOfStream(60)).isFalse()
            assertThat(System.nanoTime() - started).isAtLeast(TimeUnit.MILLISECONDS.toNanos(50))
        } finally {
            thread.stop()
        }
    }

    // ── (d) a seek still empties the queue ───────────────────────────────────────────────────────

    @Test
    fun `a seek still empties the queue`() {
        val writer = FakeWriter()
        writer.gate = CountDownLatch(1)
        val thread = newThread(writer)
        try {
            repeat(6) { thread.enqueueRaw(buffer(it + 1), encoding) }
            waitUntil { thread.queueSize() == 5 }
            thread.flush()                                  // Media3's position reset
            assertThat(thread.queueSize()).isEqualTo(0)
            // B9-2: the stream's packetizer state is no longer reset from here — that dropped the last
            // sub-millisecond of the old audio just before the fade. The stream decides it itself when the
            // writer asks what the seek owes (`beginSeek`), and resets only when nothing audible is queued.
            assertThat(writer.flushes).isEqualTo(0)
            assertThat(thread.isEndOfStreamDrained()).isFalse()
            assertThat(thread.queueEndOfStream()).isTrue()  // a flushed thread takes a new end of stream
            // The old position's queue never plays: only the buffer already in the DAC's hands does. (This writer has
            // no barrier, so there is no continuation to fade either: the old audio is dropped, as before B9.)
            writer.gate.countDown()
            waitUntil { writer.written.isNotEmpty() }
            Thread.sleep(50)
            assertThat(writer.written.map { it[0].toInt() }).containsExactly(1)
        } finally {
            writer.gate.countDown()
            thread.stop()
        }
    }

    @Test
    fun `buffers are counted in frames, floats and raw alike`() {
        val writer = FakeWriter()
        writer.gate = CountDownLatch(1)
        val thread = newThread(writer)
        try {
            thread.enqueue(FloatArray(4096 * channels))                  // 4096 frames
            waitUntil { thread.queueSize() == 0 }                        // taken: in flight
            thread.enqueueRaw(buffer(1), encoding)                       // 8192 frames
            thread.enqueueRaw(ByteArray(4096 * channels * 2), C.ENCODING_PCM_16BIT)   // 4096 frames
            assertThat(thread.queuedFrames()).isEqualTo(4096L + 8192L + 4096L)
        } finally {
            writer.gate.countDown()
            thread.stop()
        }
    }
}
