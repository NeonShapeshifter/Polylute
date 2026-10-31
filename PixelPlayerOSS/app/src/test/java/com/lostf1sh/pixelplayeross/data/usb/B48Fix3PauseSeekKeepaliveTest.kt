package com.lostf1sh.pixelplayeross.data.usb

import android.util.Log
import androidx.media3.common.C
import com.decent.usbaudio.OpenLevelCheck
import com.decent.usbaudio.StreamingDeviceOps
import com.decent.usbaudio.StreamingInterfaceSequence
import com.decent.usbaudio.UsbAudioStream
import com.decent.usbaudio.UsbDeviceOwner
import com.decent.usbaudio.media3.OwnedRecoveryHost
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class B48Fix3PauseSeekKeepaliveTest {
    private val logs = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun captureLogs() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } answers {
            logs += secondArg<String>()
            0
        }
    }

    @AfterEach
    fun releaseLogs() {
        unmockkStatic(Log::class)
    }

    /** B48-fix2's 128 ms DAC model, with the same 110 ms replenished lead and one ms per silence step. */
    private class Dac(val seeks: List<Long>, val resumeAtMs: Long? = null) : UsbStreamingThread.PcmWriter {
        lateinit var thread: UsbStreamingThread
        val done = CountDownLatch(1)
        val oldSource = ByteArray(128 * 6) { (it % 127 + 1).toByte() }
        val newSource = ByteArray(128 * 6) { (127 - it % 127).toByte() }
        val audible = ArrayList<Byte>()
        val seekTimes = ArrayList<Long>()
        val idleTimes = ArrayList<Long>()
        var nowMs = 0L
        var leadUntilMs = 128L
        var dryMs = 0L
        var keepaliveSteps = 0
        var seekSteps = 0
        var resumedAtMs = -1L
        var resumeLeadMs = -1L
        var onFeed: (() -> Unit)? = null
        var cancelled = false
        var failFeed = false
        private var pendingSeek = false
        private var seeking = false
        private var firstBufferAtMs = -1L
        private var cancellationProbed = false
        private var frames = 0L
        override val framesWritten: Long get() = frames
        override val pauseKeepaliveMs: Long get() = 2000L

        private fun advance(toMs: Long) {
            check(toMs >= nowMs)
            dryMs += (toMs - maxOf(nowMs, leadUntilMs)).coerceAtLeast(0)
            nowMs = toMs
        }

        override fun write(pcmBuffer: FloatArray) = error("raw test")

        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) {
            audible.addAll(pcmBuffer.toList())
            frames += pcmBuffer.size / 6
            if (frames == 128L) thread.pauseStreaming() else done.countDown()
        }

        private fun requestSeek() {
            pendingSeek = true
            thread.flush()
        }

        override fun beginSeek(): Int {
            pendingSeek = false
            seekTimes += nowMs
            // A cancellation probe already has a buffer queued, so it feeds no seek silence.
            seeking = !cancelled
            firstBufferAtMs = nowMs + 150
            return UsbAudioStream.SEEK_SILENCE
        }

        override fun feedSilenceStep(): Boolean {
            advance(nowMs + 1)
            leadUntilMs = maxOf(leadUntilMs, nowMs + 110)
            if (seeking) seekSteps++ else keepaliveSteps++
            if (seeking && nowMs == firstBufferAtMs) {
                thread.enqueueRaw(newSource, C.ENCODING_PCM_24BIT)
                seeking = false
            }
            onFeed?.invoke()
            if (nowMs in seeks) requestSeek()
            if (nowMs == resumeAtMs) thread.resumeStreaming()
            return !failFeed
        }

        override fun markIdle() {
            idleTimes += nowMs
            if (pendingSeek) return
            if (cancelled && !cancellationProbed) {
                cancellationProbed = true
                // Even another seek in the same pause must not reopen a cancelled budget.
                requestSeek()
                thread.enqueueRaw(newSource, C.ENCODING_PCM_24BIT)
                return
            }
            val nextSeek = seeks.firstOrNull { it > nowMs }
            if (nextSeek != null) {
                advance(nextSeek)
                requestSeek()
            } else if (!cancelled && resumeAtMs != null) {
                advance(resumeAtMs)
                thread.resumeStreaming()
            } else {
                done.countDown()
            }
        }

        override fun resumeAfterPause() {
            resumedAtMs = nowMs
            resumeLeadMs = (leadUntilMs - nowMs).coerceAtLeast(0)
        }

        override fun flush() = Unit
        override fun drainEndOfStream(): Boolean = true
    }

    private fun run(dac: Dac, host: UsbStreamingThread.DacRecoveryHost? = null) {
        val thread = UsbStreamingThread(dac, dacRecovery = host, nowNs = { dac.nowMs * 1_000_000 })
        dac.thread = thread
        thread.enqueueRaw(dac.oldSource, C.ENCODING_PCM_24BIT)
        thread.start()
        try {
            assertThat(dac.done.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            thread.stop()
        }
        println("B48-fix3 seeks=${dac.seekTimes} resume=${dac.resumedAtMs}ms dry=${dac.dryMs}ms " +
            "keepalive=${dac.keepaliveSteps}ms seekSilence=${dac.seekSteps}ms idle=${dac.idleTimes} audio=${dac.framesWritten}")
        logs.forEach { println(it) }
    }

    @Test
    fun `real cached skip continues silence after the new buffer while still paused`() {
        val dac = Dac(listOf(42), resumeAtMs = 380)
        run(dac)
        assertThat(dac.seekTimes).containsExactly(42L)
        assertThat(dac.seekSteps).isEqualTo(150)
        assertThat(dac.dryMs).isEqualTo(0)
        assertThat(dac.idleTimes).isEmpty()
        assertThat(dac.keepaliveSteps).isEqualTo(230)
        assertThat(dac.resumeLeadMs).isEqualTo(110)
        assertThat(dac.framesWritten).isEqualTo(256)
        assertThat(dac.audible).containsExactlyElementsIn(dac.oldSource.toList() + dac.newSource.toList()).inOrder()
        assertThat(logs).contains("Pause: keep-alive silence for up to 2000 ms (continued after seek, 1808 ms left)")
        assertThat(logs).contains("Pause: keep-alive ended after 380 ms (resume)")
        assertThat(logs.none { it.startsWith("Paused: idle") }).isTrue()
    }

    @Test
    fun `two seeks share one budget including time in the seek silence path`() {
        val dac = Dac(listOf(42, 500))
        run(dac)
        assertThat(dac.seekTimes).containsExactly(42L, 500L).inOrder()
        assertThat(dac.seekSteps).isEqualTo(300)
        assertThat(dac.keepaliveSteps).isEqualTo(1700)
        assertThat(dac.idleTimes).containsExactly(2000L)
        assertThat(dac.dryMs).isEqualTo(0)
        assertThat(dac.framesWritten).isEqualTo(128)
        assertThat(logs.count { it.contains("continued after seek") }).isEqualTo(2)
        assertThat(logs).contains("Pause: keep-alive ended after 2000 ms (timeout)")
    }

    @Test
    fun `a long pause with a seek at 500 ms idles at the original deadline without resuming`() {
        val dac = Dac(listOf(500))
        run(dac)
        assertThat(dac.keepaliveSteps).isEqualTo(1850)
        assertThat(dac.seekSteps).isEqualTo(150)
        assertThat(dac.idleTimes).containsExactly(2000L)
        assertThat(dac.resumedAtMs).isEqualTo(-1)
        assertThat(dac.framesWritten).isEqualTo(128)
        assertThat(logs).contains("Pause: keep-alive silence for up to 2000 ms (continued after seek, 1350 ms left)")
    }

    private class Device : StreamingDeviceOps {
        override fun setAltSetting(altSetting: Int) = true
        override fun setSampleRate(sampleRateHz: Int) = true
        override fun readSampleRate() = 48_000
        override fun readClockValid() = true
        override fun restoreLevelAfterReconfigure(reason: String) = OpenLevelCheck.VERIFIED
        override fun resetAndReclaimForStreaming() = true
    }

    private fun cancellation(why: String) {
        val owner = UsbDeviceOwner()
        val facts = owner.exclusive("configure") { owner.publishStream(48_000, 1) }
        val host = OwnedRecoveryHost(owner, StreamingInterfaceSequence(Device(), sleepMs = {}), facts.generation)
        val dac = Dac(listOf(42))
        dac.onFeed = {
            if (dac.nowMs == 220L) {
                dac.cancelled = true
                val started = System.nanoTime()
                when (why) {
                    "unplug" -> {
                        dac.thread.pauseStreaming()
                        owner.deviceDetached()
                    }
                    "rate-change" -> owner.exclusive(why) { owner.publishStream(96_000, 2) }
                    "stream-failed" -> dac.failFeed = true
                    else -> owner.exclusive(why) { owner.clearStream() }
                }
                assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(250)
            }
        }
        run(dac, host)
        assertThat(dac.nowMs).isEqualTo(220)
        assertThat(dac.keepaliveSteps).isEqualTo(70)
        assertThat(dac.seekSteps).isEqualTo(150)
        assertThat(dac.idleTimes).containsExactly(220L, 220L).inOrder()
        assertThat(dac.framesWritten).isEqualTo(128)
        val reason = if (why == "stream-failed") why else "superseded"
        assertThat(logs).contains("Pause: keep-alive ended after 220 ms ($reason)")
        assertThat(logs.count { it.contains("continued after seek") }).isEqualTo(1)
    }

    @Test fun `a new stream ends the continued keepalive for good`() { cancellation("rate-change") }
    @Test fun `unplug ends the continued keepalive for good`() { cancellation("unplug") }
    @Test fun `release ends the continued keepalive for good`() { cancellation("release") }
    @Test fun `hand back ends the continued keepalive for good`() { cancellation("hand-back") }
    @Test fun `a failed stream never continues the same pause after another seek`() { cancellation("stream-failed") }

    @Test
    fun `stop leaves the continued keepalive promptly`() {
        val dac = Dac(listOf(42))
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        dac.onFeed = {
            if (dac.nowMs == 220L) {
                reached.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val thread = UsbStreamingThread(dac, nowNs = { dac.nowMs * 1_000_000 })
        dac.thread = thread
        thread.enqueueRaw(dac.oldSource, C.ENCODING_PCM_24BIT)
        thread.start()
        try {
            assertThat(reached.await(1, TimeUnit.SECONDS)).isTrue()
            val finished = CountDownLatch(1)
            val stopper = Thread {
                thread.stop()
                finished.countDown()
            }
            val started = System.nanoTime()
            stopper.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (stopper.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
            release.countDown()
            assertThat(finished.await(250, TimeUnit.MILLISECONDS)).isTrue()
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(250)
            assertThat(dac.nowMs).isEqualTo(220)
            assertThat(dac.keepaliveSteps).isEqualTo(70)
            assertThat(dac.framesWritten).isEqualTo(128)
            assertThat(logs).contains("Pause: keep-alive ended after 220 ms (stop)")
        } finally {
            release.countDown()
            thread.stop()
        }
    }
}
