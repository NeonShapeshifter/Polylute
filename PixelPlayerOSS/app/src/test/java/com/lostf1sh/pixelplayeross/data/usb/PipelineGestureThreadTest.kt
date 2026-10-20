package com.lostf1sh.pixelplayeross.data.usb

import androidx.media3.common.C
import com.decent.usbaudio.UsbAudioStream
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/**
 * B9-1/2/3: what the real [UsbStreamingThread] does with the stream's pause / seek / skip barrier.
 *
 * The thread is run against a model of the native barrier's contract (`usb-audio-output.cpp`,
 * `pipelineBeginPause` / `pipelineBeginSeek` / `submitPipelinePcm`, proven byte for byte against
 * `pcmApplyGainRamp` by `tools/natural-join-test.cpp`): this file is about *which buffers* the thread hands
 * the stream, in what order, and when it stops feeding. The events read like the ring's content: `W3` is
 * buffer 3 written as it is, `OUT2` the first 8 ms of buffer 2 ramped to zero (the rest of it dropped, or
 * `KEEP2`'d for the resume), `IN4` a buffer written faded in, `SIL` a step of silence.
 */
class PipelineGestureThreadTest {

    private val encoding = C.ENCODING_PCM_24BIT
    private val channels = 2
    private val framesPerBuffer = 8192
    private val bytesPerBuffer = framesPerBuffer * channels * 3

    /**
     * The native barrier, as the thread sees it. It is only ever called by the thread, under the stream lock,
     * like the real one; the test reads [events], which is copy-on-write.
     */
    private class BarrierWriter(private val rampFrames: Int = 768) : UsbStreamingThread.PcmWriter {
        val events = CopyOnWriteArrayList<String>()
        /** Blocks a write inside the stream lock (a DAC that is slow to take it). */
        @Volatile var gate = CountDownLatch(0)
        /** Holds the buffer in the writer's hands before it takes the lock (see [busyWriterWithSixQueued]). */
        @Volatile var release = CountDownLatch(0)
        /** What `usbQueuedFrames` would say: audio submitted and not yet played. */
        @Volatile var ringUnplayed = 4_000L
        @Volatile var streamOk = true
        @Volatile private var hasAudio = false
        @Volatile private var endsInFade = false
        @Volatile private var fadeArmed = false
        private var keep = false
        private var done = 0
        private var pendingTag = -1
        @Volatile private var pendingFrames = 0
        private var fadeInOwed = false
        @Volatile private var frames = 0L
        /** Called by the writer, inside the write, with the event it just recorded (to land a flush mid-ramp). */
        @Volatile var onWrite: ((String) -> Unit)? = null

        override fun write(pcmBuffer: FloatArray) = submit(pcmBuffer[0].toInt(), pcmBuffer.size / 2)

        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) = submit(pcmBuffer[0].toInt(), pcmBuffer.size / 6)

        private fun submit(tag: Int, bufferFrames: Int) {
            gate.await(5, TimeUnit.SECONDS)
            if (fadeArmed) {
                val ramped = minOf(bufferFrames, rampFrames - done)
                events += "OUT$tag"
                done += ramped
                if (keep) frames += ramped
                if (done >= rampFrames) {
                    if (keep && ramped < bufferFrames) {
                        pendingTag = tag
                        pendingFrames = bufferFrames - ramped
                        events += "KEEP$tag"
                    }
                    fadeArmed = false
                    endsInFade = true
                }
                onWrite?.invoke("OUT$tag")
                return
            }
            events += if (fadeInOwed) "IN$tag" else "W$tag"
            fadeInOwed = false
            hasAudio = true
            endsInFade = false
            frames += bufferFrames
            onWrite?.invoke("W$tag")
        }

        override val framesWritten: Long get() = frames
        override fun flush() { events += "STREAM-FLUSH" }
        override fun drainEndOfStream(): Boolean { events += "D"; return true }
        override fun markIdle() { events += "IDLE" }

        override fun beginPauseFade(): Boolean {
            if (fadeArmed) return true
            if (endsInFade || !hasAudio || ringUnplayed <= 0L) return false
            fadeArmed = true
            keep = true
            done = 0
            events += "PAUSEFADE"
            return true
        }

        override fun beginSeek(): Int {
            events += "SEEK"
            pendingFrames = 0
            fadeInOwed = hasAudio
            if (fadeArmed) {
                keep = false
                return UsbAudioStream.SEEK_FADE
            }
            if (hasAudio && ringUnplayed > 0L) {
                if (endsInFade) return UsbAudioStream.SEEK_SILENCE
                fadeArmed = true
                keep = false
                done = 0
                return UsbAudioStream.SEEK_FADE
            }
            return UsbAudioStream.SEEK_NONE
        }

        override val isFadingOut: Boolean get() = fadeArmed

        override fun endFadeOut() {
            if (fadeArmed) {
                fadeArmed = false
                endsInFade = true
                events += "ENDRAMP"
            }
        }

        override fun resumeAfterPause() {
            events += "RESUME"
            if (hasAudio) fadeInOwed = true
            if (pendingFrames > 0) {
                events += "REPLAY$pendingTag"
                frames += pendingFrames
                pendingFrames = 0
                fadeInOwed = false
                endsInFade = false
            }
        }

        override fun feedSilenceStep(): Boolean {
            events += "SIL"
            Thread.sleep(1)
            return streamOk
        }

        fun count(prefix: String) = events.count { it.startsWith(prefix) }

        /** Lets a held writer go on, whichever way it is held. */
        fun letGo() {
            release.countDown()
            gate.countDown()
        }
    }

    private fun buffer(tag: Int) = ByteArray(bytesPerBuffer) { tag.toByte() }

    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(2)
    }

    private fun newThread(writer: BarrierWriter, paused: Boolean = false): UsbStreamingThread =
        UsbStreamingThread(writer, initiallyPaused = paused, channelCount = channels).also { it.start() }

    /**
     * Buffer 1 in the writer's hands, held (in its stage, before it takes the stream lock, so a flush is not
     * made to wait for it) while buffers 2..6 queue behind it: a ring that is full and a decoder that is ahead.
     * Counting the returned latch down lets the writer go on.
     */
    private fun busyWriterWithSixQueued(writer: BarrierWriter, thread: UsbStreamingThread): CountDownLatch {
        val release = CountDownLatch(1)
        writer.release = release
        thread.enqueue(FloatArray(framesPerBuffer * channels).also { it[0] = 1f }) { release.await(5, TimeUnit.SECONDS) }
        repeat(5) { thread.enqueueRaw(buffer(it + 2), encoding) }
        waitUntil { thread.queueSize() == 5 }
        return release
    }

    private fun events(writer: BarrierWriter) = writer.events.toList()

    // ── B9-1: pause and resume ──────────────────────────────────────────────────────────────────

    @Test
    fun `a pause ends the ring in a fade of the next queued buffer and keeps the rest of it for the resume`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            busyWriterWithSixQueued(writer, thread)
            thread.pauseStreaming()
            writer.letGo()
            waitUntil { "IDLE" in writer.events }

            // The buffer in hand plays out; the continuation is buffer 2, ramped; its rest is kept; idle.
            assertThat(events(writer)).containsExactly("W1", "PAUSEFADE", "OUT2", "KEEP2", "IDLE").inOrder()
            // Nothing else was consumed: 3..6 wait for the resume, in order.
            assertThat(thread.queueSize()).isEqualTo(4)

            thread.resumeStreaming()
            waitUntil { writer.count("W") >= 5 }
            assertThat(events(writer)).containsExactly(
                "W1", "PAUSEFADE", "OUT2", "KEEP2", "IDLE",
                "RESUME", "REPLAY2", "W3", "W4", "W5", "W6",
            ).inOrder()
            // The rest of buffer 2 came back: every frame of the six buffers was written once, none skipped.
            assertThat(writer.framesWritten).isEqualTo(6L * framesPerBuffer)
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    @Test
    fun `a pause with nothing queued behind the ring idles, and what follows the resume fades in`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            thread.enqueueRaw(buffer(1), encoding)
            waitUntil { "W1" in writer.events }
            thread.pauseStreaming()
            waitUntil { "IDLE" in writer.events }
            assertThat(events(writer)).containsExactly("W1", "PAUSEFADE", "ENDRAMP", "IDLE").inOrder()

            thread.resumeStreaming()
            waitUntil { "RESUME" in writer.events }
            thread.enqueueRaw(buffer(2), encoding)
            waitUntil { "IN2" in writer.events }
            assertThat(events(writer).takeLast(2)).containsExactly("RESUME", "IN2").inOrder()
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `a ring with nothing audible left is not faded at a pause`() {
        val writer = BarrierWriter()
        writer.ringUnplayed = 0L
        val thread = newThread(writer)
        try {
            thread.enqueueRaw(buffer(1), encoding)
            waitUntil { "W1" in writer.events }
            thread.pauseStreaming()
            waitUntil { "IDLE" in writer.events }
            assertThat(events(writer)).containsExactly("W1", "IDLE").inOrder()
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `a stop that is undone before the thread acts on it changes nothing - the join hold relies on it`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            busyWriterWithSixQueued(writer, thread)
            thread.pauseStreaming()
            thread.resumeStreaming()
            writer.letGo()
            waitUntil { writer.count("W") == 6 }
            assertThat(events(writer)).containsExactly("W1", "W2", "W3", "W4", "W5", "W6").inOrder()
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    @Test
    fun `the end of the queue is not a continuation for a pause - the marker stays for the drain`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            writer.gate = CountDownLatch(1)
            thread.enqueueRaw(buffer(1), encoding)
            waitUntil { thread.queueSize() == 0 }
            assertThat(thread.queueEndOfStream()).isTrue()
            thread.pauseStreaming()
            writer.letGo()
            waitUntil { "IDLE" in writer.events }
            assertThat(events(writer)).containsExactly("W1", "PAUSEFADE", "ENDRAMP", "IDLE").inOrder()
            assertThat(thread.queueSize()).isEqualTo(1)   // the marker, untouched
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    // ── B9-2: seek and skip ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a seek ends the ring in a fade of the old continuation, feeds silence, and fades the new position in`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            busyWriterWithSixQueued(writer, thread)
            thread.flush()                                      // the position reset
            assertThat(thread.queueSize()).isEqualTo(0)         // emptied at once, as it always was
            writer.letGo()

            waitUntil { writer.count("SIL") >= 3 }              // the decoder is still seeking: the DAC is fed
            // The new position's first buffers arrive.
            thread.enqueueRaw(buffer(11), encoding)
            thread.enqueueRaw(buffer(12), encoding)
            waitUntil { "W12" in writer.events }

            val log = events(writer)
            // The buffer in hand finished; then the continuation: buffer 2 ramped, buffers 3..6 never written.
            assertThat(log.take(3)).containsExactly("W1", "SEEK", "OUT2").inOrder()
            assertThat(log.filter { it == "SIL" }).isNotEmpty()
            // The first buffer of the new position fades in, and it is the first audio after the silence.
            val firstNew = log.indexOf("IN11")
            assertThat(firstNew).isGreaterThan(log.lastIndexOf("SIL"))
            assertThat(log.drop(firstNew)).containsExactly("IN11", "W12").inOrder()
            // The old position never plays again, and nothing is kept for a resume.
            for (old in 3..6) assertThat(log).doesNotContain("W$old")
            assertThat(log.none { it.startsWith("KEEP") || it == "REPLAY2" }).isTrue()
            // The clock counts media: buffer 1, and the new position's two. The ramp of the position that was
            // left is not part of any item's time.
            assertThat(writer.framesWritten).isEqualTo(3L * framesPerBuffer)
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    @Test
    fun `the flush does not reset the stream underneath the fade - the stream decides that itself`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            busyWriterWithSixQueued(writer, thread)
            thread.flush()
            writer.letGo()
            waitUntil { "OUT2" in writer.events }
            // A reset of the packetizer's partial frame here would drop the last sub-millisecond of the old
            // audio just before the ramp (a tick). The stream resets it when nothing audible is queued.
            assertThat(writer.events).doesNotContain("STREAM-FLUSH")
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    @Test
    fun `a burst of flushes before the thread looks is one seek with the first continuation`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            busyWriterWithSixQueued(writer, thread)
            repeat(5) {
                thread.flush()                                  // five drags of the seek bar
                thread.enqueueRaw(buffer(20 + it), encoding)    // each position's first buffer, superseded by the next
            }
            writer.letGo()
            waitUntil { writer.count("SIL") >= 1 || writer.count("W24") + writer.count("IN24") >= 1 }
            waitUntil { writer.count("IN24") + writer.count("W24") >= 1 }

            val log = events(writer)
            assertThat(log.count { it == "SEEK" }).isEqualTo(1)
            // The ramp is the *old* position's continuation (2), not the first drag's buffer (20).
            assertThat(log.filter { it.startsWith("OUT") }).containsExactly("OUT2")
            // Only the last position's buffer is heard; the intermediate ones never were.
            for (superseded in 20..23) assertThat(log).doesNotContain("W$superseded")
            for (superseded in 20..23) assertThat(log).doesNotContain("IN$superseded")
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    @Test
    fun `five seeks spaced out pile up one fade-out and one stretch of silence, not five`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            busyWriterWithSixQueued(writer, thread)
            thread.flush()                                      // the first drag, while the writer still holds buffer 1
            writer.letGo()
            repeat(5) { round ->
                // The writer takes each one and goes on feeding silence; the next drag comes before any audio does.
                if (round > 0) thread.flush()
                waitUntil { writer.count("SEEK") == round + 1 }
                waitUntil { writer.count("SIL") >= 2 * (round + 1) }
            }
            val silenceBeforeAudio = writer.count("SIL")
            thread.enqueueRaw(buffer(30), encoding)
            waitUntil { "IN30" in writer.events }

            val log = events(writer)
            assertThat(log.count { it == "SEEK" }).isEqualTo(5)
            assertThat(log.filter { it.startsWith("OUT") }).containsExactly("OUT2")    // one continuation
            assertThat(log.count { it == "ENDRAMP" }).isEqualTo(0)
            // Nothing between the seeks but silence and the seeks themselves: no second ramp, no audio.
            val afterFirst = log.drop(log.indexOf("OUT2") + 1)
            assertThat(afterFirst.filter { it != "SIL" && it != "SEEK" && it != "IN30" }).isEmpty()
            assertThat(silenceBeforeAudio).isLessThan(2000)   // paced by the feed (1 ms a step), not multiplied by the seeks
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    @Test
    fun `a seek with nothing queued still ends the ring and keeps the DAC fed`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            thread.enqueueRaw(buffer(1), encoding)
            waitUntil { "W1" in writer.events }
            thread.flush()
            waitUntil { writer.count("SIL") >= 2 }
            assertThat(events(writer).take(3)).containsExactly("W1", "SEEK", "ENDRAMP").inOrder()
            thread.enqueueRaw(buffer(9), encoding)
            waitUntil { "IN9" in writer.events }
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `a seek over a ring with nothing audible left feeds nothing and fades the new position in`() {
        val writer = BarrierWriter()
        writer.ringUnplayed = 0L
        val thread = newThread(writer)
        try {
            thread.enqueueRaw(buffer(1), encoding)
            waitUntil { "W1" in writer.events }
            thread.flush()
            waitUntil { "SEEK" in writer.events }
            Thread.sleep(30)
            assertThat(writer.count("SIL")).isEqualTo(0)
            thread.enqueueRaw(buffer(2), encoding)
            waitUntil { "IN2" in writer.events }
            assertThat(events(writer)).containsExactly("W1", "SEEK", "IN2").inOrder()
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `the silence stops when the stream fails, and the thread carries on`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            busyWriterWithSixQueued(writer, thread)
            writer.streamOk = false
            thread.flush()
            writer.letGo()
            waitUntil { "OUT2" in writer.events }
            Thread.sleep(40)
            // One failed step and no more: it does not spin on a dead stream.
            assertThat(writer.count("SIL")).isEqualTo(1)
            writer.streamOk = true
            thread.enqueueRaw(buffer(7), encoding)
            waitUntil { "IN7" in writer.events }
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    // ── AUD-140: a seek while paused ────────────────────────────────────────────────────────────

    @Test
    fun `a seek while paused drops the kept block, and feeds silence only until the new position's first buffer is queued`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            busyWriterWithSixQueued(writer, thread)
            thread.pauseStreaming()
            writer.letGo()
            waitUntil { "IDLE" in writer.events }
            assertThat(writer.events).contains("KEEP2")

            thread.flush()                                     // the player seeks while paused
            waitUntil { "SEEK" in writer.events }
            // The ring already ends in the pause's fade, so no second ramp: only silence, which the DAC is
            // kept fed with while the decoder works on the new position — even though Media3 has paused.
            waitUntil { writer.count("SIL") >= 3 }
            assertThat(writer.count("SIL")).isAtLeast(3)

            thread.enqueueRaw(buffer(15), encoding)            // the decoder delivered: the new position buffers while paused
            waitUntil { writer.events.last() == "IDLE" && writer.count("IDLE") >= 2 }
            val silence = writer.count("SIL")
            Thread.sleep(40)
            assertThat(writer.count("SIL")).isEqualTo(silence)  // no more once the new audio is queued
            thread.resumeStreaming()
            waitUntil { "IN15" in writer.events }
            val log = events(writer)
            // The old position's kept block never plays: resume replays nothing, the new audio fades in.
            assertThat(log).doesNotContain("REPLAY2")
            for (old in 3..6) assertThat(log).doesNotContain("W$old")
            assertThat(log.indexOf("IN15")).isGreaterThan(log.indexOf("SEEK"))
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    @Test
    fun `the silence fed into a pause is bounded, so a long pause is not a wake-up every millisecond`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            val release = busyWriterWithSixQueued(writer, thread)
            thread.pauseStreaming()
            release.countDown()
            waitUntil { "IDLE" in writer.events }
            thread.flush()                                     // a seek while paused, and the decoder never answers
            waitUntil { writer.count("SIL") >= 3 }
            // After the cap the thread idles again, and the silence stops for good.
            waitUntil(timeoutMs = 2_500) { writer.count("IDLE") >= 2 }
            val silence = writer.count("SIL")
            assertThat(silence).isAtLeast(3)
            assertThat(silence).isLessThan(1_000)               // ~1 ms a step for ≤ 600 ms
            Thread.sleep(80)
            assertThat(writer.count("SIL")).isEqualTo(silence)
            assertThat(writer.events.last()).isEqualTo("IDLE")
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    @Test
    fun `a seek that lands in the middle of a pause's ramp carries that ramp on with the old position's buffers`() {
        // The ramp takes three buffers here. The flush arrives after the second: the ramp is one ramp, finished
        // from the old position's buffers, and what the pause would have kept is dropped, not replayed.
        val writer = BarrierWriter(rampFrames = 3 * framesPerBuffer)
        val thread = newThread(writer)
        try {
            writer.onWrite = { event -> if (event == "OUT3") { writer.onWrite = null; thread.flush() } }
            busyWriterWithSixQueued(writer, thread)
            thread.pauseStreaming()
            writer.letGo()
            waitUntil { writer.count("SEEK") == 1 && writer.count("OUT") == 3 }
            waitUntil { writer.events.last() == "IDLE" }
            val log = events(writer)
            assertThat(log.filter { it.startsWith("OUT") }).containsExactly("OUT2", "OUT3", "OUT4").inOrder()
            assertThat(log.none { it.startsWith("KEEP") || it.startsWith("REPLAY") }).isTrue()
            for (old in 5..6) assertThat(log).doesNotContain("W$old")
            assertThat(log.indexOf("SEEK")).isGreaterThan(log.indexOf("OUT3"))
            assertThat(log.indexOf("OUT4")).isGreaterThan(log.indexOf("SEEK"))
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    @Test
    fun `a seek and a pause that reach the thread together fade the old continuation once`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            busyWriterWithSixQueued(writer, thread)
            thread.pauseStreaming()
            thread.flush()
            writer.letGo()
            waitUntil(timeoutMs = 2_500) { "IDLE" in writer.events }
            val log = events(writer)
            // The seek is looked at first, as the engine does: its ramp ends the ring; the pause finds the ring
            // already ending in a fade and only idles. (Between them, the silence of the seek.)
            assertThat(log.filter { it != "SIL" }).containsExactly("W1", "SEEK", "OUT2", "IDLE").inOrder()
            assertThat(log.indexOf("SIL")).isGreaterThan(log.indexOf("OUT2"))
            assertThat(log.indexOf("SIL")).isLessThan(log.indexOf("IDLE"))
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    // ── B9-3: a manual skip to another stream shape ─────────────────────────────────────────────

    @Test
    fun `a rebuild can wait for the fade-out a skip left to write`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            busyWriterWithSixQueued(writer, thread)
            thread.flush()
            assertThat(thread.fadeOutPending()).isTrue()        // the ring still ends in the old audio at full level
            val opener = Thread { Thread.sleep(40); writer.letGo() }
            opener.start()
            val finished = thread.awaitFadeOut(2_000)
            opener.join()
            assertThat(finished).isTrue()
            assertThat(writer.events).contains("OUT2")
            assertThat(thread.fadeOutPending()).isFalse()
            // The wait does not depend on the silence feed, which runs until the new audio arrives.
            waitUntil { writer.count("SIL") >= 1 }
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    @Test
    fun `the wait for the fade-out is bounded when the thread is stuck`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            busyWriterWithSixQueued(writer, thread)
            thread.flush()
            val started = System.nanoTime()
            assertThat(thread.awaitFadeOut(60)).isFalse()
            assertThat(System.nanoTime() - started).isAtLeast(TimeUnit.MILLISECONDS.toNanos(50))
        } finally {
            writer.letGo()
            thread.stop()
        }
    }

    // ── what must not change ────────────────────────────────────────────────────────────────────

    @Test
    fun `a natural join touches no barrier - the buffers and the end of stream go through in order`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        try {
            repeat(4) { thread.enqueueRaw(buffer(it + 1), encoding) }       // item A
            repeat(3) { thread.enqueueRaw(buffer(it + 5), encoding) }       // item B follows on, no flush
            assertThat(thread.queueEndOfStream()).isTrue()
            waitUntil { thread.isEndOfStreamDrained() }
            assertThat(events(writer)).containsExactly("W1", "W2", "W3", "W4", "W5", "W6", "W7", "D").inOrder()
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `a thread started paused fades nothing and starts clean at the first resume`() {
        val writer = BarrierWriter()
        val thread = newThread(writer, paused = true)
        try {
            waitUntil { "IDLE" in writer.events }
            thread.enqueueRaw(buffer(1), encoding)
            thread.resumeStreaming()
            waitUntil { writer.count("W") + writer.count("IN") >= 1 }
            // No audio has played yet: the stream's own rule (`hasAudio`) keeps the very first buffer bit-exact.
            assertThat(events(writer).take(3)).containsExactly("IDLE", "RESUME", "W1").inOrder()
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `stopping while silence is being fed ends the thread`() {
        val writer = BarrierWriter()
        val thread = newThread(writer)
        busyWriterWithSixQueued(writer, thread)
        thread.flush()
        writer.letGo()
        waitUntil { writer.count("SIL") >= 2 }
        val started = System.nanoTime()
        thread.stop()
        assertThat(System.nanoTime() - started).isLessThan(TimeUnit.MILLISECONDS.toNanos(1_000))
    }
}
