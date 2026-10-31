package com.lostf1sh.pixelplayeross.data.usb

import androidx.media3.common.C
import com.decent.usbaudio.OpenLevelCheck
import com.decent.usbaudio.StreamingDeviceOps
import com.decent.usbaudio.StreamingInterfaceSequence
import com.decent.usbaudio.UsbAudioStream
import com.decent.usbaudio.UsbDeviceOwner
import com.decent.usbaudio.UsbTransportPlanner
import com.decent.usbaudio.media3.OwnedRecoveryHost
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/** Virtual time and a DAC with a 128 ms ring. Only the writer advances time, one millisecond per silence step. */
class B48Fix2PauseKeepaliveTest {
    private class Dac(val pauseMs: Long, val seek: Boolean = false) : UsbStreamingThread.PcmWriter {
        lateinit var thread: UsbStreamingThread
        val done = CountDownLatch(1)
        val source = ByteArray(128 * 6) { (it % 127 + 1).toByte() }
        val audible = ArrayList<Byte>()
        var nowMs = 0L
        var leadUntilMs = 128L
        var dryMs = 0L
        var silenceSteps = 0
        var idleAtMs = -1L
        var seekAtMs = -1L
        var writes = 0
        var onFeed: (() -> Unit)? = null
        var cancelled = false
        private var frames = 0L
        override val framesWritten: Long get() = frames
        override val pauseKeepaliveMs: Long get() = 2000L
        fun advance(toMs: Long) {
            dryMs += (toMs - maxOf(nowMs, leadUntilMs)).coerceAtLeast(0)
            nowMs = toMs
        }
        override fun write(pcmBuffer: FloatArray) = error("raw test")
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) {
            audible.addAll(pcmBuffer.toList())
            frames += pcmBuffer.size / 6
            writes++
            if (writes == 1) thread.pauseStreaming() else done.countDown()
        }
        private fun resume() {
            thread.enqueueRaw(source, C.ENCODING_PCM_24BIT)
            thread.resumeStreaming()
        }
        override fun feedSilenceStep(): Boolean {
            advance(nowMs + 1)
            leadUntilMs = maxOf(leadUntilMs, nowMs + 110)
            silenceSteps++
            onFeed?.invoke()
            if (!cancelled && nowMs == pauseMs) {
                if (seek) thread.flush() else resume()
            }
            return true
        }
        override fun markIdle() {
            idleAtMs = nowMs
            if (cancelled) {
                done.countDown()
            } else if (seek && seekAtMs < 0 && silenceSteps > 0) {
                // The pending seek owns the next iteration and keeps the same ring fed.
            } else {
                advance(pauseMs)
                resume()
            }
        }
        override fun beginSeek(): Int {
            seekAtMs = nowMs
            resume()
            return UsbAudioStream.SEEK_SILENCE
        }
        override fun flush() = Unit
        override fun drainEndOfStream(): Boolean = true
    }

    private fun runPause(pauseMs: Long, seek: Boolean = false): Dac {
        val dac = Dac(pauseMs, seek)
        val thread = UsbStreamingThread(dac, nowNs = { dac.nowMs * 1_000_000 })
        dac.thread = thread
        thread.enqueueRaw(dac.source, C.ENCODING_PCM_24BIT)
        thread.start()
        try {
            assertThat(dac.done.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            thread.stop()
        }
        println("B48-fix2 pause=$pauseMs seek=$seek dry=${dac.dryMs}ms silence=${dac.silenceSteps}ms idle=${dac.idleAtMs}ms audio=${dac.framesWritten}")
        assertThat(dac.framesWritten).isEqualTo(256)
        assertThat(dac.audible).containsExactlyElementsIn(dac.source.toList() + dac.source.toList()).inOrder()
        return dac
    }

    @Test
    fun `cached skip with a 200 ms pause never dries the DAC`() {
        val dac = runPause(200)
        assertThat(dac.dryMs).isEqualTo(0)
        assertThat(dac.silenceSteps).isEqualTo(200)
        assertThat(dac.idleAtMs).isEqualTo(-1)
    }

    @Test
    fun `pause play at one and a half seconds retains the lead without counting silence`() {
        val dac = runPause(1500)
        assertThat(dac.dryMs).isEqualTo(0)
        assertThat(dac.silenceSteps).isEqualTo(1500)
    }

    @Test
    fun `a seek takes over the silence immediately during a pause`() {
        val dac = runPause(200, seek = true)
        assertThat(dac.dryMs).isEqualTo(0)
        assertThat(dac.seekAtMs).isEqualTo(200)
        assertThat(dac.silenceSteps).isEqualTo(200)
    }

    @Test
    fun `a three second pause feeds two seconds then announces the untimed idle`() {
        val dac = runPause(3000)
        assertThat(dac.silenceSteps).isEqualTo(2000)
        assertThat(dac.idleAtMs).isEqualTo(2000)
        assertThat(dac.dryMs).isEqualTo(890)
    }

    private class Device : StreamingDeviceOps {
        override fun setAltSetting(altSetting: Int) = true
        override fun setSampleRate(sampleRateHz: Int) = true
        override fun readSampleRate() = 48_000
        override fun readClockValid() = true
        override fun restoreLevelAfterReconfigure(reason: String) = OpenLevelCheck.VERIFIED
        override fun resetAndReclaimForStreaming() = true
    }

    private fun ownerCancels(why: String) {
        val owner = UsbDeviceOwner()
        val facts = owner.exclusive("configure") { owner.publishStream(48_000, 1) }
        val host = OwnedRecoveryHost(owner, StreamingInterfaceSequence(Device(), sleepMs = {}), facts.generation)
        val dac = Dac(3000)
        val thread = UsbStreamingThread(dac, dacRecovery = host, nowNs = { dac.nowMs * 1_000_000 })
        dac.thread = thread
        dac.onFeed = {
            if (dac.nowMs == 20L) {
                dac.cancelled = true
                val started = System.nanoTime()
                if (why == "unplug") {
                    thread.pauseStreaming()
                    owner.deviceDetached()
                } else {
                    owner.exclusive(why) { owner.clearStream() }
                }
                assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(250)
            }
        }
        thread.enqueueRaw(dac.source, C.ENCODING_PCM_24BIT)
        thread.start()
        try {
            assertThat(dac.done.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            thread.stop()
        }
        assertThat(dac.silenceSteps).isEqualTo(20)
        assertThat(dac.idleAtMs).isEqualTo(20)
        assertThat(dac.framesWritten).isEqualTo(128)
    }

    @Test fun `unplug ends keepalive through the owner without waiting`() { ownerCancels("unplug") }
    @Test fun `release ends keepalive through the owner`() { ownerCancels("release") }
    @Test fun `rate change ends keepalive through the owner`() { ownerCancels("rate-change") }
    @Test fun `hand back ends keepalive through the owner`() { ownerCancels("hand-back") }

    @Test
    fun `stop leaves an active keepalive promptly`() {
        val dac = Dac(3000)
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = UsbStreamingThread(dac, nowNs = { dac.nowMs * 1_000_000 })
        dac.thread = thread
        dac.onFeed = {
            if (dac.nowMs == 20L) {
                dac.cancelled = true
                reached.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        thread.enqueueRaw(dac.source, C.ENCODING_PCM_24BIT)
        thread.start()
        try {
            assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()
            val finished = CountDownLatch(1)
            val stopper = Thread {
                thread.stop()
                finished.countDown()
            }
            val started = System.nanoTime()
            stopper.start()
            // The stop sets running=false before joining the writer; the fake is released after that join starts.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (stopper.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
            release.countDown()
            assertThat(finished.await(250, TimeUnit.MILLISECONDS)).isTrue()
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(250)
            assertThat(dac.silenceSteps).isEqualTo(20)
            assertThat(dac.framesWritten).isEqualTo(128)
        } finally {
            release.countDown()
            thread.stop()
        }
    }

    @Test
    fun `the native transport plan exports the two second pause window in the next free field`() {
        val plan = UsbTransportPlanner.plan(48_000, 2, 4, sourceBits = 24, maxPacketBytes = 400, bInterval = 1, hasFeedback = true, feedbackBytes = 4)
        assertThat(plan.pauseKeepaliveMs).isEqualTo(2000)
        assertThat(plan.toArray().last()).isEqualTo(2000)
    }
}
