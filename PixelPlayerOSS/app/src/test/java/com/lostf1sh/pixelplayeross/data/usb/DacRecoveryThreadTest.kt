package com.lostf1sh.pixelplayeross.data.usb

import androidx.media3.common.C
import com.decent.usbaudio.DacLockState
import com.decent.usbaudio.RestartOutcome
import com.decent.usbaudio.UsbAudioStream
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test

/**
 * B47-A5: what the real [UsbStreamingThread] does when the native policy asks for a recovery step of a locked-up DAC.
 *
 * The native half (the detector, the escalation, the drain and the re-arm) is proven in `tools/natural-join-test.cpp` (`dac-lock`) and `tools/usb-glitch-test.cpp`; here the thread runs
 * against a model of that contract and the question is the order and the gating: a step is a pause-style fade, the ring drained, the platform's work, the stream armed again, and a
 * resume at the same position; it is never asked for while paused, in a seek, or while a fade is being written; the frame count (Media3's position is a function of it) only grows.
 */
class DacRecoveryThreadTest {

    private val encoding = C.ENCODING_PCM_24BIT
    private val bufferFrames = 4096
    private val bytesPerBuffer = bufferFrames * 2 * 3
    private val rampFrames = 768

    /** A model of the stream's barrier and of the recovery halves; the events read like the ring's content. */
    private class RecoveryWriter(private val rampFrames: Int) : UsbStreamingThread.PcmWriter {
        val events = CopyOnWriteArrayList<String>()
        val frameHistory = CopyOnWriteArrayList<Long>()
        /** The step the policy answers with at the next look (consumed). */
        val nextStep = AtomicInteger(0)
        val looks = AtomicInteger(0)
        @Volatile var lookedWhileFading = false
        @Volatile private var hasAudio = false
        @Volatile private var endsInFade = false
        @Volatile private var fadeArmed = false
        private var done = 0
        @Volatile private var pendingFrames = 0
        @Volatile private var fadeInOwed = false
        @Volatile private var frames = 0L

        override fun write(pcmBuffer: FloatArray) = submit(pcmBuffer[0].toInt(), pcmBuffer.size / 2)
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) = submit(pcmBuffer[0].toInt(), pcmBuffer.size / 6)

        private fun submit(tag: Int, bufferFrames: Int) {
            if (fadeArmed) {
                val ramped = minOf(bufferFrames, rampFrames - done)
                events += "OUT$tag"
                done += ramped
                frames += ramped
                if (done >= rampFrames) {
                    if (ramped < bufferFrames) {
                        pendingFrames = bufferFrames - ramped
                        events += "KEEP$tag"
                    }
                    fadeArmed = false
                    endsInFade = true
                }
            } else {
                events += if (fadeInOwed) "IN$tag" else "W$tag"
                fadeInOwed = false
                hasAudio = true
                endsInFade = false
                frames += bufferFrames
            }
            frameHistory += frames
        }

        override val framesWritten: Long get() = frames
        override fun flush() { events += "STREAM-FLUSH" }
        override fun drainEndOfStream(): Boolean = true
        override fun markIdle() { events += "IDLE" }

        override fun beginPauseFade(): Boolean {
            if (fadeArmed) return true
            if (endsInFade || !hasAudio) return false
            fadeArmed = true
            done = 0
            events += "PAUSEFADE"
            return true
        }

        override fun beginSeek(): Int {
            events += "SEEK"
            pendingFrames = 0
            fadeInOwed = hasAudio
            if (fadeArmed) return UsbAudioStream.SEEK_FADE
            if (hasAudio) {
                if (endsInFade) return UsbAudioStream.SEEK_SILENCE
                fadeArmed = true
                done = 0
                return UsbAudioStream.SEEK_FADE
            }
            return UsbAudioStream.SEEK_NONE
        }

        override val isFadingOut: Boolean get() = fadeArmed
        override fun endFadeOut() { if (fadeArmed) { fadeArmed = false; endsInFade = true } }

        override fun resumeAfterPause() {
            events += "RESUME"
            if (!hasAudio) return
            fadeInOwed = true
            frames += pendingFrames   // the kept rest of the faded block plays, faded in: counted when it is replayed
            pendingFrames = 0
            frameHistory += frames
        }

        override fun feedSilenceStep(): Boolean { Thread.sleep(1); return true }

        override fun dacLockStep(): Int {
            looks.incrementAndGet()
            if (fadeArmed) lookedWhileFading = true
            return nextStep.getAndSet(0)
        }

        override fun dacRecoveryBegin(): Boolean { events += "BEGIN"; return true }
        override fun dacRecoveryEnd(): Boolean { events += "END"; return true }
        override fun noteDacRecovery(step: Int, ran: Boolean, elapsedMs: Long) { events += "NOTE$step:$ran" }
    }

    private class Host(private val outcome: Boolean, private val events: CopyOnWriteArrayList<String>) : UsbStreamingThread.DacRecoveryHost {
        val steps = CopyOnWriteArrayList<Int>()
        override fun begin(step: Int): Boolean = true
        override fun restartInterface(step: Int): RestartOutcome {
            steps += step
            events += "HOST$step"
            return if (outcome) RestartOutcome.RAN else RestartOutcome.FAILED
        }
        override fun end(step: Int) {}
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun raw(tag: Int) = ByteArray(bytesPerBuffer).also { it[0] = tag.toByte() }

    private fun thread(writer: RecoveryWriter, host: UsbStreamingThread.DacRecoveryHost?, paused: Boolean = false) =
        UsbStreamingThread(writer, initiallyPaused = paused, channelCount = 2, dacRecovery = host)

    @Test
    fun `a step fades out like a pause, restarts the interface, re arms the stream and resumes`() {
        val writer = RecoveryWriter(rampFrames)
        val host = Host(true, writer.events)
        val thread = thread(writer, host)
        thread.start()
        try {
            thread.enqueueRaw(raw(0), encoding)
            assertThat(waitFor { writer.events.contains("W0") }).isTrue()
            writer.nextStep.set(DacLockState.STEP_INTERFACE)   // the policy: the DAC is locked up, take step 1
            for (tag in 1..4) thread.enqueueRaw(raw(tag), encoding)
            assertThat(waitFor { writer.events.contains("RESUME") }).isTrue()
            assertThat(waitFor { writer.events.contains("IN2") || writer.events.contains("IN3") || writer.events.contains("IN4") || writer.events.any { it.startsWith("IN") } }).isTrue()

            val e = writer.events.toList()
            // the order of one step: the pause's fade (the continuation ramped, the rest of its block kept), the announced idle, the drain, the platform's work, the re-arm, the report, the resume
            val order = listOf("PAUSEFADE", "IDLE", "BEGIN", "HOST1", "END", "NOTE1:true", "RESUME")
            var at = -1
            for (event in order) {
                val next = e.indexOf(event)
                assertThat(next).isGreaterThan(at)
                at = next
            }
            assertThat(e.count { it == "HOST1" }).isEqualTo(1)
            // the buffer after the resume fades in (the stream's resume owes it): the thread did not write it as it was
            assertThat(e.subList(e.indexOf("RESUME"), e.size).any { it.startsWith("IN") }).isTrue()
        } finally {
            thread.stop()
        }
        // Media3's position is a function of the frame count: it only ever grows, and every frame of the source is counted once
        // (4 buffers written whole or ramped + the kept rest replayed at the resume + the 5th buffer = 5 buffers)
        val history = writer.frameHistory.toList()
        assertThat(history).isInOrder()
        assertThat(history.last()).isEqualTo(5L * bufferFrames)
    }

    @Test
    fun `a step the platform could not do is reported as failed and the stream is armed again all the same`() {
        val writer = RecoveryWriter(rampFrames)
        val host = Host(false, writer.events)
        val thread = thread(writer, host)
        thread.start()
        try {
            thread.enqueueRaw(raw(0), encoding)
            assertThat(waitFor { writer.events.contains("W0") }).isTrue()
            writer.nextStep.set(DacLockState.STEP_BUS_RESET)
            for (tag in 1..3) thread.enqueueRaw(raw(tag), encoding)
            assertThat(waitFor { writer.events.contains("NOTE2:false") }).isTrue()
            val e = writer.events.toList()
            assertThat(host.steps).containsExactly(2)
            // the stream is never left half restarted: the drain was followed by the re-arm whatever the platform said
            assertThat(e.indexOf("END")).isGreaterThan(e.indexOf("HOST2"))
            assertThat(e.indexOf("BEGIN")).isLessThan(e.indexOf("HOST2"))
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `nothing is asked of the policy while paused, and a request waits for the writer to play`() {
        val writer = RecoveryWriter(rampFrames)
        val host = Host(true, writer.events)
        val thread = thread(writer, host, paused = true)
        thread.start()
        try {
            writer.nextStep.set(DacLockState.STEP_INTERFACE)   // e.g. RECOVER_DAC_NOW from the shell while paused
            Thread.sleep(400)
            assertThat(writer.looks.get()).isEqualTo(0)         // the policy did not look: a manual request is not consumed either
            assertThat(host.steps).isEmpty()
            thread.enqueueRaw(raw(0), encoding)
            thread.resumeStreaming()
            assertThat(waitFor { writer.events.contains("W0") }).isTrue()
            thread.enqueueRaw(raw(1), encoding)
            thread.enqueueRaw(raw(2), encoding)
            assertThat(waitFor { host.steps.isNotEmpty() }).isTrue()
            assertThat(host.steps).containsExactly(1)
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `the policy is not asked during a skip or while a fade is being written`() {
        val writer = RecoveryWriter(rampFrames)
        val host = Host(true, writer.events)
        val thread = thread(writer, host)
        thread.start()
        try {
            for (tag in 0..2) thread.enqueueRaw(raw(tag), encoding)
            assertThat(waitFor { writer.events.contains("W2") }).isTrue()
            // a skip: flush, then the new position's audio
            thread.flush()
            for (tag in 10..13) thread.enqueueRaw(raw(tag), encoding)
            assertThat(waitFor { writer.events.contains("W13") || writer.events.contains("IN13") }).isTrue()
            // a pause with its fade
            thread.pauseStreaming()
            assertThat(waitFor { writer.events.contains("IDLE") }).isTrue()
        } finally {
            thread.stop()
        }
        assertThat(writer.lookedWhileFading).isFalse()
        assertThat(host.steps).isEmpty()
    }

    @Test
    fun `without a host the thread never looks`() {
        val writer = RecoveryWriter(rampFrames)
        val thread = thread(writer, host = null)
        thread.start()
        try {
            writer.nextStep.set(DacLockState.STEP_INTERFACE)
            for (tag in 0..3) thread.enqueueRaw(raw(tag), encoding)
            assertThat(waitFor { writer.events.contains("W3") }).isTrue()
            assertThat(writer.looks.get()).isEqualTo(0)
            assertThat(writer.events).doesNotContain("BEGIN")
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `the steps used up and no step at all are not steps`() {
        val writer = RecoveryWriter(rampFrames)
        val host = Host(true, writer.events)
        val thread = thread(writer, host)
        thread.start()
        try {
            thread.enqueueRaw(raw(0), encoding)
            assertThat(waitFor { writer.events.contains("W0") }).isTrue()
            writer.nextStep.set(DacLockState.STEP_GAVE_UP)   // the policy has already logged it: the writer has nothing to do
            for (tag in 1..3) thread.enqueueRaw(raw(tag), encoding)
            assertThat(waitFor { writer.events.contains("W3") }).isTrue()
            assertThat(host.steps).isEmpty()
            assertThat(writer.events).doesNotContain("BEGIN")
        } finally {
            thread.stop()
        }
    }
}
