package com.lostf1sh.pixelplayeross.data.usb

import androidx.media3.common.C
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/**
 * B48-3: the producer queue's frame count is a running count (O(1) on every buffer), and it always equals the O(n) sum, through every path a buffer enters or leaves the queue:
 * an offer, the writer's poll, the drop of the oldest above the hard limit, a pause fade's continuation, a flush's drain (also while a write is in progress) and the stop.
 */
class B48QueueFramesCountTest {

    private val encoding = C.ENCODING_PCM_16BIT
    private val channels = 2

    private fun buffer(frames: Int) = ByteArray(frames * channels * 2)

    /** A writer whose writes can be held, and that can ramp a continuation of [fadeBuffers] buffers for a pause. */
    private class HoldingWriter : UsbStreamingThread.PcmWriter {
        val writtenFrames = CopyOnWriteArrayList<Int>()
        val events = CopyOnWriteArrayList<String>()
        @Volatile var gate: CountDownLatch? = null
        @Volatile var fadeBuffers = 0
        @Volatile private var fadeLeft = 0
        @Volatile private var frames = 0L
        val entered = CountDownLatch(1)

        override fun write(pcmBuffer: FloatArray) {}
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) {
            entered.countDown()
            gate?.await(10, TimeUnit.SECONDS)
            writtenFrames += pcmBuffer.size / 4
            frames += pcmBuffer.size / 4
            if (fadeLeft > 0) fadeLeft--
        }
        override val framesWritten: Long get() = frames
        override fun flush() {}
        override fun drainEndOfStream(): Boolean = true
        override fun markIdle() { events += "IDLE" }
        override fun beginPauseFade(): Boolean {
            if (fadeBuffers <= 0) return false
            fadeLeft = fadeBuffers
            fadeBuffers = 0
            return true
        }
        override val isFadingOut: Boolean get() = fadeLeft > 0
        override fun endFadeOut() { fadeLeft = 0 }
    }

    private fun thread(writer: HoldingWriter, paused: Boolean, soft: Long = 100_000, hard: Long = 1_000_000) =
        UsbStreamingThread(writer, initiallyPaused = paused, channelCount = channels, queueSoftFrames = soft, queueHardFrames = hard)

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun UsbStreamingThread.assertCountIsTheSum(waiting: Long, inFlight: Long = 0L) {
        assertThat(queuedFramesRecount()).isEqualTo(waiting)
        assertThat(queuedFrames()).isEqualTo(waiting + inFlight)
    }

    @Test
    fun `every offer adds the frames of its buffer and the soft limit follows the count`() {
        val writer = HoldingWriter()
        val thread = thread(writer, paused = true, soft = 2_500)
        thread.start()
        try {
            var expected = 0L
            for (frames in listOf(1_000, 1_000, 700, 300, 1_024)) {
                thread.enqueueRaw(buffer(frames), encoding)
                expected += frames
                thread.assertCountIsTheSum(expected)
                assertThat(thread.atSoftLimit()).isEqualTo(expected >= 2_500)
            }
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `dropping the oldest above the hard limit takes its frames off the count`() {
        val writer = HoldingWriter()
        val thread = thread(writer, paused = true, hard = 3_500)
        thread.start()
        try {
            for (i in 1..12) {
                thread.enqueueRaw(buffer(1_000), encoding)
                assertThat(thread.queuedFrames()).isAtMost(3_500L)
                thread.assertCountIsTheSum(thread.queuedFramesRecount())
            }
            thread.assertCountIsTheSum(3_000)      // three whole buffers fit under 3 500; the other nine were dropped oldest first
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `a flush empties the count and a seek continuation is not counted as waiting`() {
        val writer = HoldingWriter()
        val thread = thread(writer, paused = true)
        thread.start()
        try {
            for (i in 1..4) thread.enqueueRaw(buffer(1_000), encoding)
            thread.assertCountIsTheSum(4_000)
            thread.flush()
            thread.assertCountIsTheSum(0)
            thread.enqueueRaw(buffer(500), encoding)         // the new position
            thread.assertCountIsTheSum(500)
            thread.flush()
            thread.assertCountIsTheSum(0)
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `an end of stream marker has no frames`() {
        val writer = HoldingWriter()
        val thread = thread(writer, paused = true)
        thread.start()
        try {
            thread.enqueueRaw(buffer(800), encoding)
            assertThat(thread.queueEndOfStream()).isTrue()
            thread.assertCountIsTheSum(800)
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `the writer's polls take each buffer off the count and it ends at zero`() {
        val writer = HoldingWriter()
        val thread = thread(writer, paused = false)
        thread.start()
        try {
            repeat(40) { thread.enqueueRaw(buffer(480), encoding) }
            assertThat(waitFor { writer.writtenFrames.size == 40 }).isTrue()
            assertThat(waitFor { thread.queuedFrames() == 0L }).isTrue()
            thread.assertCountIsTheSum(0)
            assertThat(writer.writtenFrames.sum()).isEqualTo(40 * 480)
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `a flush while a write is in progress leaves the count at the sum`() {
        val writer = HoldingWriter()
        writer.gate = CountDownLatch(1)
        val thread = thread(writer, paused = false)
        thread.start()
        try {
            thread.enqueueRaw(buffer(1_000), encoding)
            assertThat(writer.entered.await(5, TimeUnit.SECONDS)).isTrue()      // taken by the writer: in flight, not waiting
            repeat(3) { thread.enqueueRaw(buffer(1_000), encoding) }
            thread.assertCountIsTheSum(3_000, inFlight = 1_000)
            val flushed = CountDownLatch(1)
            Thread({ thread.flush(); flushed.countDown() }, "flusher").start()
            Thread.sleep(100)                                                    // flush() waits for the write in progress
            writer.gate!!.countDown()
            assertThat(flushed.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(waitFor { thread.queuedFramesRecount() == 0L }).isTrue()
            assertThat(waitFor { thread.queuedFrames() == 0L }).isTrue()
        } finally {
            writer.gate?.countDown()
            thread.stop()
        }
    }

    @Test
    fun `a pause fade that takes buffers as its continuation takes them off the count`() {
        val writer = HoldingWriter()
        writer.gate = CountDownLatch(1)
        writer.fadeBuffers = 2
        val thread = thread(writer, paused = false)
        thread.start()
        try {
            thread.enqueueRaw(buffer(1_000), encoding)
            assertThat(writer.entered.await(5, TimeUnit.SECONDS)).isTrue()
            repeat(5) { thread.enqueueRaw(buffer(1_000), encoding) }
            thread.pauseStreaming()
            writer.gate!!.countDown()                                            // the write finishes, the writer sees the pause and ramps two buffers down
            assertThat(waitFor { writer.events.contains("IDLE") }).isTrue()
            thread.assertCountIsTheSum(3_000)                                    // 5 queued − 2 taken by the fade
            assertThat(writer.writtenFrames.sum()).isEqualTo(3 * 1_000)
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `stop drains the queue and the count with it`() {
        val writer = HoldingWriter()
        val thread = thread(writer, paused = true)
        thread.start()
        repeat(6) { thread.enqueueRaw(buffer(700), encoding) }
        thread.assertCountIsTheSum(4_200)
        thread.stop()
        thread.assertCountIsTheSum(0)
    }

    @Test
    fun `heavy producing against a draining writer keeps the count equal to the sum`() {
        val writer = HoldingWriter()
        val thread = thread(writer, paused = false, hard = 60_000)
        thread.start()
        try {
            repeat(400) { i ->
                thread.enqueueRaw(buffer(100 + i % 17), encoding)
                if (i % 50 == 49) thread.flush()
            }
            assertThat(waitFor { thread.queuedFrames() == 0L }).isTrue()
            thread.assertCountIsTheSum(0)
        } finally {
            thread.stop()
        }
    }
}
