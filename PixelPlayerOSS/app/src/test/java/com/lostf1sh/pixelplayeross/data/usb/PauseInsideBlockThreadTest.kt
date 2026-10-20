package com.lostf1sh.pixelplayeross.data.usb

import androidx.media3.common.C
import com.decent.usbaudio.media3.DirectPathTelemetry
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/**
 * B12a-6: a pause that reaches the writer while it is inside a block. The writer's native stream acts on it between
 * URBs (`tools/natural-join-test.cpp`, "pipeline pause inside a block": the fade is queued ~8 ms after the request,
 * not ~70 ms, same bytes); this is what the real [UsbStreamingThread] does around that: it tells the stream
 * about the pause and the resume, and when the stream says it acted on a pause it handles it as a pause even
 * though it never saw the block end — including when the user has already resumed, where the kept rest of the
 * block must still replay.
 */
class PauseInsideBlockThreadTest {

    private val encoding = C.ENCODING_PCM_24BIT
    private val channels = 2
    private val bytesPerBuffer = 8192 * channels * 3

    /**
     * A stream whose write of buffer [slowTag] stays inside the block until a pause is requested, then does what the
     * native stream does: queues the fade from that point (`HEAD`/`OUT`), keeps the rest (`KEEP`) and reports that it acted.
     */
    private class InBlockWriter(private val slowTag: Int) : UsbStreamingThread.PcmWriter {
        val events = CopyOnWriteArrayList<String>()
        val insideBlock = CountDownLatch(1)
        private val pauseSeen = CountDownLatch(1)
        /** Blocks the writer after it acted on the pause, inside the write (so a test can resume "before the loop looks"). */
        @Volatile var afterApplied = CountDownLatch(0)
        @Volatile private var applied = false
        @Volatile private var endsInFade = false
        @Volatile private var keptTag = -1
        @Volatile var pauseRequests = 0
        @Volatile var cancels = 0

        override fun write(pcmBuffer: FloatArray) = Unit

        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) {
            val tag = pcmBuffer[0].toInt()
            if (tag == slowTag) {
                insideBlock.countDown()
                pauseSeen.await(5, TimeUnit.SECONDS)
                events += "HEAD$tag"
                events += "OUT$tag"
                events += "KEEP$tag"
                keptTag = tag
                applied = true
                endsInFade = true
                afterApplied.await(5, TimeUnit.SECONDS)
                return
            }
            events += "W$tag"
        }

        override val framesWritten: Long get() = 0L
        override fun flush() = Unit
        override fun drainEndOfStream(): Boolean = true
        override fun markIdle() { events += "IDLE" }

        override fun requestPause() { pauseRequests++; pauseSeen.countDown() }
        override fun cancelPauseRequest() { cancels++ }
        override fun consumePauseApplied(): Boolean = applied.also { applied = false }

        // The ring already ends in the fade the block queued: a second one would fade nothing.
        override fun beginPauseFade(): Boolean {
            events += "BEGINPAUSE=${!endsInFade}"
            return !endsInFade
        }

        override fun resumeAfterPause() {
            events += "RESUME"
            if (keptTag >= 0) { events += "REPLAY$keptTag"; keptTag = -1 }
            endsInFade = false
        }
    }

    private fun buffer(tag: Int) = ByteArray(bytesPerBuffer) { tag.toByte() }

    private fun waitFor(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(2)
    }

    private fun thread(writer: InBlockWriter) =
        UsbStreamingThread(writer, initiallyPaused = false, channelCount = channels).also { it.start() }

    @Test
    fun `the pause reaches the stream the moment it is made, and the thread handles the pause the stream acted on`() {
        val writer = InBlockWriter(slowTag = 2)
        val thread = thread(writer)
        val fadesBefore = DirectPathTelemetry.pipelinePauseFades.get()
        try {
            repeat(3) { thread.enqueueRaw(buffer(it + 1), encoding) }
            assertThat(writer.insideBlock.await(3, TimeUnit.SECONDS)).isTrue()   // the writer is inside buffer 2
            thread.pauseStreaming()
            assertThat(writer.pauseRequests).isEqualTo(1)                         // told at once, not at the block's end
            waitFor { "IDLE" in writer.events }
            // buffer 1 whole, buffer 2 faded from where the pause reached it, its rest kept, then idle: buffer 3 waits
            assertThat(writer.events.toList()).containsExactly("W1", "HEAD2", "OUT2", "KEEP2", "BEGINPAUSE=false", "IDLE").inOrder()
            assertThat(DirectPathTelemetry.pipelinePauseFades.get() - fadesBefore).isEqualTo(1L)
            thread.resumeStreaming()
            assertThat(writer.cancels).isEqualTo(1)
            waitFor { "W3" in writer.events }
            // the resume replays what the pause kept, then the audio follows: nothing lost
            assertThat(writer.events.toList()).containsExactly(
                "W1", "HEAD2", "OUT2", "KEEP2", "BEGINPAUSE=false", "IDLE", "RESUME", "REPLAY2", "W3",
            ).inOrder()
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `a user who resumed before the writer looked still gets the rest of the block back`() {
        val writer = InBlockWriter(slowTag = 2)
        writer.afterApplied = CountDownLatch(1)               // the writer is held inside the block, after the fade is queued
        val thread = thread(writer)
        try {
            repeat(3) { thread.enqueueRaw(buffer(it + 1), encoding) }
            assertThat(writer.insideBlock.await(3, TimeUnit.SECONDS)).isTrue()
            thread.pauseStreaming()
            waitFor { "KEEP2" in writer.events }
            thread.resumeStreaming()                           // the thread has not left the write yet: `paused` is false again
            writer.afterApplied.countDown()
            waitFor { "W3" in writer.events }
            // it never saw `paused`, yet the pause was handled and the resume replayed the kept rest before buffer 3
            assertThat(writer.events.toList()).containsExactly(
                "W1", "HEAD2", "OUT2", "KEEP2", "BEGINPAUSE=false", "IDLE", "RESUME", "REPLAY2", "W3",
            ).inOrder()
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `a pause the stream did not act on inside a block is handled exactly as before`() {
        // A writer that never reports one: the pause waits for the block to end, then the barrier fades the next buffer.
        val events = CopyOnWriteArrayList<String>()
        val writer = object : UsbStreamingThread.PcmWriter {
            var fading = false
            override fun write(pcmBuffer: FloatArray) = Unit
            override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) { events += (if (fading) "OUT" else "W") + pcmBuffer[0] ; if (fading) fading = false }
            override val framesWritten: Long get() = 0L
            override fun flush() = Unit
            override fun drainEndOfStream() = true
            override fun markIdle() { events += "IDLE" }
            override fun beginPauseFade(): Boolean { fading = true; events += "PAUSEFADE"; return true }
            override val isFadingOut: Boolean get() = fading
            override fun endFadeOut() { fading = false }
        }
        val thread = UsbStreamingThread(writer, initiallyPaused = true, channelCount = channels)
        try {
            repeat(2) { thread.enqueueRaw(buffer(it + 1), encoding) }   // queued before the thread starts: the continuation is there
            thread.start()
            waitFor { "IDLE" in events }
            assertThat(events.toList()).containsExactly("PAUSEFADE", "OUT1", "IDLE").inOrder()
        } finally {
            thread.stop()
        }
    }
}
