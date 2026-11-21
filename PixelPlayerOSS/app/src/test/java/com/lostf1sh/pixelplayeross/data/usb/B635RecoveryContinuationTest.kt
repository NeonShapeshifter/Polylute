package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.*
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test

class B635RecoveryContinuationTest {
    @Test
    fun `fault recovery does not fade pause mute or feed zeros and accepts reopen`() {
        for (step in 1..3) {
            val events = CopyOnWriteArrayList<String>()
            val done = CountDownLatch(1)
            val request = AtomicInteger(step)
            val writer = object : UsbStreamingThread.PcmWriter {
                override fun write(pcmBuffer: FloatArray) { events += "music" }
                override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) { events += "music" }
                override val framesWritten: Long get() = 0L
                override fun flush() = Unit
                override fun drainEndOfStream() = true
                override fun beginPauseFade(): Boolean { events += "fade"; return false }
                override fun markIdle() { events += "idle" }
                override fun feedSilenceStep(): Boolean { events += "zeros"; return true }
                override fun resumeAfterPause() { events += "resume" }
                override fun dacLockStep() = request.getAndSet(0)
                override fun dacRecoveryBegin(): Boolean { events += "drain"; return true }
                override fun dacRecoveryEnd(): Boolean { events += "ready"; return true }
                override fun noteDacRecovery(step: Int, ran: Boolean, elapsedMs: Long) { events += "result:$step:$ran"; done.countDown() }
            }
            val host = object : UsbStreamingThread.DacRecoveryHost {
                override fun begin(step: Int) = true
                override fun restartInterface(step: Int): RestartOutcome { events += "reopen:$step"; return RestartOutcome.RAN }
                override fun end(step: Int) {}
            }
            val thread = UsbStreamingThread(writer, dacRecovery = host)
            thread.enqueue(FloatArray(64))
            thread.start()
            try {
                assertThat(done.await(3, TimeUnit.SECONDS)).isTrue()
                assertThat(events).containsAtLeast("drain", "reopen:$step", "ready", "result:$step:true").inOrder()
                assertThat(events).containsNoneOf("fade", "idle", "zeros", "resume")
            } finally { thread.stop() }
        }
    }

    @Test
    fun `reopen starts fresh native underruns only after new music`() {
        val request = AtomicInteger(0)
        val notes = CopyOnWriteArrayList<Pair<Long, Long>>()
        val recovered = CountDownLatch(1)
        val newTimeout = CountDownLatch(1)
        val writer = object : UsbStreamingThread.PcmWriter {
            @Volatile var sequence = 11L
            override val diagnosticSequence: Long get() = sequence
            override fun write(pcmBuffer: FloatArray) = Unit
            override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) = Unit
            override val framesWritten: Long get() = 32L
            override fun flush() = Unit
            override fun drainEndOfStream() = true
            override fun dacLockStep() = request.getAndSet(0)
            override fun dacRecoveryEnd(): Boolean { sequence = 12L; return true }
            override fun noteDacRecovery(step: Int, ran: Boolean, elapsedMs: Long) { recovered.countDown() }
            override fun noteQueueTimeout(total: Long, depth: Int, burst: Long) {
                notes += sequence to total
                if (sequence == 11L) request.set(3) else newTimeout.countDown()
            }
        }
        val host = object : UsbStreamingThread.DacRecoveryHost {
            override fun begin(step: Int) = true
            override fun restartInterface(step: Int) = RestartOutcome.RAN
            override fun end(step: Int) = Unit
        }
        val thread = UsbStreamingThread(writer, dacRecovery = host)
        thread.enqueue(FloatArray(64)); thread.start()
        try {
            assertThat(recovered.await(3, TimeUnit.SECONDS)).isTrue()
            // An open with no music yet must not create producer-starvation evidence.
            assertThat(newTimeout.await(250, TimeUnit.MILLISECONDS)).isFalse()
            thread.enqueue(FloatArray(64))
            assertThat(newTimeout.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(notes.take(2)).containsExactly(11L to 1L, 12L to 1L).inOrder()
        } finally { thread.stop() }
    }

    @Test
    fun `full reopen closes stream before device and preserves the owner generation`() {
        val owner = UsbDeviceOwner()
        owner.exclusive("initial") { owner.publishStream(96000, 3) }
        val generation = owner.generation
        val claim = owner.claimForRecovery(generation, 3)!!
        val calls = mutableListOf<String>()
        val result = FullStreamReopen(object : FullStreamReopen.Ops {
            override fun closeStream(): Boolean { calls += "close-stream"; return true }
            override fun closeDevice() { calls += "close-device" }
            override fun openDevice(): Boolean { calls += "open-device"; return true }
            override fun configure(rate: Int, alt: Int): Boolean { calls += "configure:$rate:$alt"; return true }
            override fun openStream(): Boolean { calls += "open-stream"; return true }
        }).run(claim)
        claim.close()
        assertThat(result).isEqualTo(RestartOutcome.RAN)
        assertThat(calls).containsExactly("close-stream", "close-device", "open-device", "configure:96000:3", "open-stream").inOrder()
        assertThat(owner.generation).isEqualTo(generation)
    }

    @Test
    fun `detach supersede and volume failure end reopen before touching another stream`() {
        for (stopAt in 0..4) for (detach in listOf(true, false)) {
            val owner = UsbDeviceOwner(exclusiveWaitMs = 0)
            owner.exclusive("initial") { owner.publishStream(44100, 1) }
            val claim = owner.claimForRecovery(owner.generation, 3)!!
            var calls = 0
            fun boundary(): Boolean {
                if (calls++ == stopAt) {
                    if (detach) owner.deviceDetached() else owner.exclusive("new-format") { owner.publishStream(96000, 3) }
                }
                return true
            }
            // A non-waiting synthetic owner avoids the production 3s overlap wait in this control.
            val result = FullStreamReopen(object : FullStreamReopen.Ops {
                override fun closeStream() = boundary()
                override fun closeDevice() { boundary() }
                override fun openDevice() = boundary()
                override fun configure(rate: Int, alt: Int) = boundary()
                override fun openStream() = boundary()
            }).run(claim)
            claim.close()
            assertThat(result).isEqualTo(RestartOutcome.SUPERSEDED)
            assertThat(calls).isAtMost(stopAt + 1)
        }
        val owner = UsbDeviceOwner(); owner.exclusive("start") { owner.publishStream(96000, 3) }
        val claim = owner.claimForRecovery(owner.generation, 3)!!
        var openedStream = false
        val result = FullStreamReopen(object : FullStreamReopen.Ops {
            override fun closeStream() = true
            override fun closeDevice() = Unit
            override fun openDevice() = true
            override fun configure(rate: Int, alt: Int) = false // volume guard ignored/reset level
            override fun openStream(): Boolean { openedStream = true; return true }
        }).run(claim)
        claim.close()
        assertThat(result).isEqualTo(RestartOutcome.FAILED)
        assertThat(openedStream).isFalse()
    }
}
