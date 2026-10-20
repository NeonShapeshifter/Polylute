package com.lostf1sh.pixelplayeross.data.usb

import android.os.Process
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.util.Collections
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B23-P2: the USB writer asks for `THREAD_PRIORITY_URGENT_AUDIO` **from inside its own thread**, before it submits
 * anything. The owner's wake of 2026-10-14 had it 175 ms late on a ring that holds ~80 ms, at `Thread.MAX_PRIORITY`
 * (nice -8). The priority is a nice value, not a realtime class.
 *
 * It is a wiring test: what a real device grants (or an OEM refuses) is for the reviewer's log line, which reads the
 * priority back.
 */
class StreamingThreadPriorityTest {

    @AfterEach
    fun unmock() {
        unmockkStatic(Process::class)
    }

    /** The order things happened in, as seen from the thread each happened on. */
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private inner class RecordingWriter : UsbStreamingThread.PcmWriter {
        override fun write(pcmBuffer: FloatArray) {
            events += "write@${Thread.currentThread().name}"
        }
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) {
            events += "write@${Thread.currentThread().name}"
        }
        override val framesWritten: Long get() = 0
        override fun flush() = Unit
        override fun drainEndOfStream(): Boolean = true
    }

    private fun runUntilFirstWrite(thread: UsbStreamingThread) {
        thread.start()
        try {
            thread.enqueue(FloatArray(8) { 0.25f })
            val deadline = System.currentTimeMillis() + 3_000
            while (events.none { it.startsWith("write@") } && System.currentTimeMillis() < deadline) Thread.sleep(5)
        } finally {
            thread.stop()
        }
    }

    @Test
    fun `the production writer sets URGENT_AUDIO on its own thread, before its first write`() {
        mockkStatic(Process::class)
        every { Process.myTid() } returns 4242
        every { Process.getThreadPriority(any()) } returns Process.THREAD_PRIORITY_URGENT_AUDIO
        every { Process.setThreadPriority(any()) } answers {
            events += "setThreadPriority(${firstArg<Int>()})@${Thread.currentThread().name}"
        }

        runUntilFirstWrite(UsbStreamingThread(RecordingWriter()))

        assertThat(Process.THREAD_PRIORITY_URGENT_AUDIO).isEqualTo(-19)
        assertThat(events.first()).isEqualTo("setThreadPriority(-19)@UsbStreamingThread")
        assertThat(events).contains("write@UsbStreamingThread")
        // once: a per-write call would be a syscall in the hot loop
        assertThat(events.count { it.startsWith("setThreadPriority") }).isEqualTo(1)
    }

    @Test
    fun `the call is made by the writer thread, not by the thread that starts it`() {
        val callers = Collections.synchronizedList(mutableListOf<String>())
        val priority = object : UsbStreamingThread.WriterPriority {
            override fun raiseCurrentThread(): String {
                callers += Thread.currentThread().name
                events += "raise"
                return "fake"
            }
        }
        val starter = Thread.currentThread().name

        runUntilFirstWrite(UsbStreamingThread(RecordingWriter(), writerPriority = priority))

        assertThat(callers).containsExactly("UsbStreamingThread")
        assertThat(callers.single()).isNotEqualTo(starter)
        assertThat(events.indexOf("raise")).isLessThan(events.indexOfFirst { it.startsWith("write@") })
    }

    @Test
    fun `a refused priority leaves the writer running and says so`() {
        mockkStatic(Process::class)
        every { Process.myTid() } returns 4242
        every { Process.getThreadPriority(any()) } returns -8
        every { Process.setThreadPriority(any()) } throws SecurityException("no CAP_SYS_NICE")

        val description = runCatching { UsbStreamingThread.ProcessAudioPriority.raiseCurrentThread() }

        assertThat(description.isSuccess).isTrue()
        assertThat(description.getOrThrow()).contains("refused")
        assertThat(description.getOrThrow()).contains("SecurityException")
        // ... and a writer built on it still writes
        runUntilFirstWrite(UsbStreamingThread(RecordingWriter()))
        assertThat(events).contains("write@UsbStreamingThread")
    }

    @Test
    fun `the granted priority is read back for the log, and no realtime class is requested`() {
        mockkStatic(Process::class)
        every { Process.myTid() } returns 4242
        every { Process.getThreadPriority(4242) } returnsMany listOf(-8, -19)
        every { Process.setThreadPriority(any()) } returns Unit

        val line = UsbStreamingThread.ProcessAudioPriority.raiseCurrentThread()

        assertThat(line).contains("nice -8 -> -19")
        assertThat(line).contains("URGENT_AUDIO")
        // SCHED_FIFO is out of this batch: the pipeline's output and its writer ask for none (the native plan-B engine's
        // own FIFO attempt is a different file)
        val roots = listOf(
            "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbStreamingThread.kt",
            "../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt",
            "../../decent-player/libs/decent-usb-audio-driver/src/main/jni/usb-audio-output.cpp",
        )
        for (path in roots) {
            val code = File(path).readLines().joinToString("\n") { it.substringBefore("//").substringBefore(" * ") }
            for (forbidden in listOf("sched_setscheduler", "pthread_setschedparam", "setSchedPolicy", "SCHED_RR")) {
                assertThat(code).doesNotContain(forbidden)
            }
            // SCHED_FIFO may be named in a comment that says "no"; it is never called
            assertThat(code).doesNotContain("SCHED_FIFO")
        }
    }
}
