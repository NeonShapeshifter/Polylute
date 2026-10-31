package com.lostf1sh.pixelplayeross.data.usb

import androidx.media3.common.C
import com.decent.usbaudio.OpenLevelCheck
import com.decent.usbaudio.StreamFacts
import com.decent.usbaudio.StreamingDeviceOps
import com.decent.usbaudio.StreamingInterfaceSequence
import com.decent.usbaudio.UsbDeviceOwner
import com.decent.usbaudio.media3.OwnedRecoveryHost
import com.decent.usbaudio.media3.UsbStreamingThread
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test

/**
 * B48-1: a DAC recovery and everything else that touches the USB device, with the real [UsbStreamingThread], the real [UsbDeviceOwner] / [OwnedRecoveryHost] /
 * [StreamingInterfaceSequence] and a device that records every call with its thread.
 *
 * "Legal" means: no two sections (a configure, a recovery, a hand-back) open at once, no two device calls at the same time, every call inside a section, and nothing claimed or
 * reset after a detach. The same recorder, with a model of B47's host that reads the sink's fields of the moment and no owner, is in `tools/B48HeadInterleavingProbe.kt` (run against HEAD).
 */
class B48RecoveryOwnershipTest {

    private val encoding = C.ENCODING_PCM_24BIT
    private val bytesPerBuffer = 1024 * 2 * 3

    /** The bound on the unplug path while a recovery is inside a device call: it takes a few milliseconds; the test allows what a loaded host may add, far below any wait for the recovery. */
    private val UNPLUG_BOUND_MS = 250L

    /** A recovery whose device work outlasts the 2 s the old rebuild was willing to join the writer for (a bus reset that re-enumerates slowly). */
    private val LONG_RECOVERY_MS = 2_500L

    /** A device that records what is called, by which thread, and whether two sections or two calls ever overlapped. */
    class RecordingDevice : StreamingDeviceOps {
        private val calls = CopyOnWriteArrayList<String>()
        val violations = CopyOnWriteArrayList<String>()
        /** `(thread, rate)` of every SET_CUR, to check which stream a recovery programmed. */
        val clockWrites = CopyOnWriteArrayList<Pair<Thread, Int>>()
        private val openSections = ConcurrentHashMap<String, Thread>()
        private val sectionOfThread = ConcurrentHashMap<Thread, String>()
        private val activeCalls = AtomicInteger(0)
        @Volatile var gone = false
        /** Device time of each call made by the writer thread (the recovery's), to make a recovery long enough to meet the next rebuild. */
        @Volatile var writerLatencyMs = 0L
        @Volatile private var blocker: Blocker? = null
        @Volatile private var currentRate = 0

        class Blocker(val threadName: String, val op: String) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
        }

        /** The next [op] called by the thread named [threadName] stops inside the call until [Blocker.release] (a long transfer). */
        fun blockNext(op: String, threadName: String): Blocker = Blocker(threadName, op).also { blocker = it }

        fun trail(): List<String> = calls.toList()

        fun mark(text: String) {
            calls += "MARK:$text"
        }

        fun section(name: String): AutoCloseable {
            val me = Thread.currentThread()
            val others = openSections.entries.filter { it.value !== me }
            if (others.isNotEmpty()) violations += "section '$name' (${me.name}) opened while '${others.first().key}' (${others.first().value.name}) is open"
            openSections[name] = me
            sectionOfThread[me] = name
            return AutoCloseable {
                openSections.remove(name)
                sectionOfThread.remove(me)
            }
        }

        private fun <T> call(op: String, body: () -> T): T {
            val me = Thread.currentThread()
            calls += "${me.name}:$op"
            if (sectionOfThread[me] == null) violations += "$op by ${me.name} outside any section"
            if (activeCalls.incrementAndGet() > 1) violations += "$op by ${me.name} while another device call is in progress"
            try {
                val b = blocker
                if (b != null && b.op == op.substringBefore('(') && b.threadName == me.name) {
                    blocker = null
                    b.entered.countDown()
                    b.release.await(10, TimeUnit.SECONDS)
                }
                if (writerLatencyMs > 0 && me.name == "UsbStreamingThread") Thread.sleep(writerLatencyMs)
                return body()
            } finally {
                activeCalls.decrementAndGet()
            }
        }

        override fun setAltSetting(altSetting: Int): Boolean = call("setAlt($altSetting)") { true }
        override fun setSampleRate(sampleRateHz: Int): Boolean = call("SET_CUR($sampleRateHz)") {
            currentRate = sampleRateHz
            clockWrites += Thread.currentThread() to sampleRateHz
            true
        }
        override fun readSampleRate(): Int = call("GET_CUR") { currentRate }
        override fun readClockValid(): Boolean = call("CLOCK_VALID") { true }
        override fun restoreLevelAfterReconfigure(reason: String): OpenLevelCheck = call("level") { OpenLevelCheck.VERIFIED }
        override fun resetAndReclaimForStreaming(): Boolean = call("resetAndReclaim") { true }
        fun handBack() = call("handBack") { }
    }

    /** The writer's side of the stream: only what the recovery needs of it. */
    private class StubWriter : UsbStreamingThread.PcmWriter {
        val events = CopyOnWriteArrayList<String>()
        val step = AtomicInteger(0)
        @Volatile var writerThread: Thread? = null
        @Volatile var onRecoveryBegin: (() -> Unit)? = null
        @Volatile private var frames = 0L
        override fun write(pcmBuffer: FloatArray) { frames += pcmBuffer.size / 2 }
        override fun writeRaw(pcmBuffer: ByteArray, encoding: Int) { frames += pcmBuffer.size / 6; events += "W"; Thread.sleep(1) }
        override val framesWritten: Long get() = frames
        override fun flush() {}
        override fun drainEndOfStream(): Boolean = true
        override fun markIdle() { events += "IDLE" }
        override fun beginSeek(): Int { events += "SEEK"; return com.decent.usbaudio.UsbAudioStream.SEEK_NONE }
        override fun dacLockStep(): Int { writerThread = Thread.currentThread(); return step.getAndSet(0) }
        override fun dacRecoveryBegin(): Boolean { events += "BEGIN"; onRecoveryBegin?.invoke(); return true }
        override fun dacRecoveryEnd(): Boolean { events += "END"; return true }
        override fun noteDacRecovery(step: Int, ran: Boolean, elapsedMs: Long) { events += "NOTE$step:$ran" }
    }

    /** The recovery host with a section around the claim, so that the recorder sees exactly the span in which the owner says the device is the recovery's. */
    private class TracingHost(private val inner: UsbStreamingThread.DacRecoveryHost, private val device: RecordingDevice) : UsbStreamingThread.DacRecoveryHost {
        private var open: AutoCloseable? = null
        override fun begin(step: Int): Boolean = inner.begin(step).also { if (it) open = device.section("recovery") }
        override fun restartInterface(step: Int) = inner.restartInterface(step)
        override fun end(step: Int) {
            open?.close()
            open = null
            inner.end(step)
        }
    }

    private inner class Writer(val stub: StubWriter, val thread: UsbStreamingThread, val generation: Long, val rate: Int) {
        fun requestRecovery(step: Int) {
            stub.step.set(step)
            thread.enqueueRaw(ByteArray(bytesPerBuffer), encoding)   // wakes the loop
        }

        fun stop() = thread.stop()
    }

    /** What the sink does with the owner, with the device recorded. */
    private inner class Rig {
        val logs = CopyOnWriteArrayList<String>()
        val device = RecordingDevice()
        val owner = UsbDeviceOwner(exclusiveWaitMs = 3_000, deviceGone = { device.gone }, log = { logs += it })
        val sequence = StreamingInterfaceSequence(device, sleepMs = { Thread.sleep(2) })

        /** `configureUsbBitPerfect`'s device section: step 1, the reconfigure, the stream published as the next generation. */
        fun configure(rate: Int, alt: Int, before: () -> Unit = {}) {
            owner.exclusive("configure rate=$rate") {
                device.section("configure rate=$rate").use {
                    before()
                    device.setAltSetting(0)
                    val reconfigured = sequence.reconfigure(rate, alt, "rate=$rate alt=$alt")
                    check(reconfigured.failure == null)
                    owner.publishStream(rate, alt)
                }
            }
        }

        fun handBack() {
            owner.exclusive("hand-back") {
                device.section("hand-back").use {
                    owner.clearStream()
                    device.handBack()
                }
            }
        }

        fun startWriter(paused: Boolean = false): Writer {
            val facts = owner.facts!!
            val stub = StubWriter()
            val host = TracingHost(OwnedRecoveryHost(owner, sequence, facts.generation) { logs += it }, device)
            val thread = UsbStreamingThread(stub, initiallyPaused = paused, channelCount = 2, dacRecovery = host)
            thread.start()
            return Writer(stub, thread, facts.generation, facts.sampleRateHz)
        }
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun playback(name: String = "playback", body: () -> Unit): CountDownLatch {
        val done = CountDownLatch(1)
        Thread({ try { body() } finally { done.countDown() } }, name).start()
        return done
    }

    private fun List<String>.callsBy(thread: String) = filter { it.startsWith("$thread:") }

    @Test
    fun `native replay decisions take both recovery steps through the same owner generation`() {
        // The native replay checks which watch fired and produces the same step decisions for
        // why=rail and why=stuck. The JNI step carries no reason field; both use this owner.
        for (why in listOf("rail", "stuck")) {
            val rig = Rig()
            rig.configure(48_000, 1)
            val writer = rig.startWriter(paused = why == "stuck")
            try {
                for (step in listOf(1, 2)) {
                    writer.requestRecovery(step)
                    if (why == "stuck" && step == 1) {
                        // Even a stuck decision must wait until this writer is playing.
                        Thread.sleep(100)
                        assertThat(writer.stub.events).doesNotContain("BEGIN")
                        assertThat(writer.stub.step.get()).isEqualTo(step)
                        writer.thread.resumeStreaming()
                    }
                    assertThat(waitFor { writer.stub.events.contains("NOTE$step:true") }).isTrue()
                    assertThat(rig.owner.generation).isEqualTo(writer.generation)
                }
            } finally {
                writer.stop()
            }
            assertThat(rig.device.violations).isEmpty()
            assertThat(rig.device.clockWrites.filter { it.first === writer.stub.writerThread }.map { it.second })
                .containsExactly(48_000, 48_000).inOrder()
            assertThat(rig.device.trail().count { it.endsWith("resetAndReclaim") }).isEqualTo(1)
        }
    }

    // ── recovery + a rate change ───────────────────────────────────────────────────────────────

    @Test
    fun `a rate change during a recovery waits for it, then the device sees the rebuild, and the recovery restarted its own stream`() {
        val rig = Rig()
        rig.configure(44_100, 1)
        val writer = rig.startWriter()
        val block = rig.device.blockNext("setAlt", "UsbStreamingThread")
        try {
            writer.requestRecovery(1)
            assertThat(block.entered.await(5, TimeUnit.SECONDS)).isTrue()          // the recovery is inside its first device call
            val changed = playback { rig.configure(96_000, 2) }
            assertThat(changed.await(250, TimeUnit.MILLISECONDS)).isFalse()        // the rate change waits for the recovery
            block.release.countDown()
            assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            writer.stop()
        }
        val trail = rig.device.trail()
        assertThat(rig.device.violations).isEmpty()
        // one after the other: every call of the recovery, then every call of the rate change
        assertThat(trail.indexOfLast { it.startsWith("UsbStreamingThread:") }).isLessThan(trail.indexOfFirst { it.startsWith("playback:") })
        // the recovery is step 1 of ITS stream: 44.1 kHz, alt 1 (the rate change had already announced the next generation)
        assertThat(trail.callsBy("UsbStreamingThread")).containsExactly(
            "UsbStreamingThread:setAlt(0)", "UsbStreamingThread:SET_CUR(44100)", "UsbStreamingThread:GET_CUR", "UsbStreamingThread:CLOCK_VALID",
            "UsbStreamingThread:setAlt(0)", "UsbStreamingThread:setAlt(1)", "UsbStreamingThread:level",
        ).inOrder()
        // and the new stream is up at the new rate, as the next generation
        assertThat(rig.owner.facts).isEqualTo(StreamFacts(rig.owner.generation, 96_000, 2))
        assertThat(rig.owner.generation).isGreaterThan(writer.generation)
        assertThat(rig.device.clockWrites.map { it.second }.takeLast(1)).containsExactly(96_000)
    }

    @Test
    fun `a recovery that takes longer than the old two second join still finishes before the rate change and the clock ends at the new rate`() {
        val rig = Rig()
        rig.configure(44_100, 1)
        val writer = rig.startWriter()
        val block = rig.device.blockNext("resetAndReclaim", "UsbStreamingThread")   // a bus reset that re-enumerates slowly
        val startedNs = System.nanoTime()
        try {
            writer.requestRecovery(2)
            assertThat(block.entered.await(5, TimeUnit.SECONDS)).isTrue()
            val changed = playback { rig.configure(96_000, 2) }
            Thread.sleep(LONG_RECOVERY_MS)
            assertThat(changed.await(100, TimeUnit.MILLISECONDS)).isFalse()        // still waiting: HEAD's join gave up here and went on to the device
            block.release.countDown()
            assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            writer.stop()
        }
        val waitedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertThat(waitedMs).isAtLeast(LONG_RECOVERY_MS)
        assertThat(rig.device.violations).isEmpty()
        // the last clock write is the new stream's: the recovery's 44.1 kHz never lands after the 96 kHz
        assertThat(rig.device.clockWrites.last().second).isEqualTo(96_000)
        assertThat(rig.logs.any { it.startsWith("OWNER_WAITED who=configure rate=96000") }).isTrue()
        assertThat(rig.logs.none { it.startsWith("OWNER_FORCED") }).isTrue()
    }

    @Test
    fun `a rate change before the recovery reaches the device supersedes it, which touches nothing`() {
        val rig = Rig()
        rig.configure(44_100, 1)
        val writer = rig.startWriter()
        val inFade = CountDownLatch(1)
        val letRecoveryOn = CountDownLatch(1)
        writer.stub.onRecoveryBegin = { inFade.countDown(); letRecoveryOn.await(10, TimeUnit.SECONDS) }   // the ring is draining: no device call yet
        try {
            writer.requestRecovery(1)
            assertThat(inFade.await(5, TimeUnit.SECONDS)).isTrue()
            val changed = playback { rig.configure(96_000, 2) }
            assertThat(changed.await(250, TimeUnit.MILLISECONDS)).isFalse()        // the claim is the recovery's until it lets go
            letRecoveryOn.countDown()
            assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            writer.stop()
        }
        assertThat(rig.device.violations).isEmpty()
        assertThat(rig.device.trail().callsBy("UsbStreamingThread")).isEmpty()      // not one device call of the old generation
        val line = rig.logs.single { it.startsWith("DAC_RECOVERY step=1 result=superseded") }
        assertThat(line).isEqualTo("DAC_RECOVERY step=1 result=superseded gen=${writer.generation} now=${rig.owner.generation} by=generation before=setAlt0")
        // the native policy is not told a step ran or failed for a stream that was replaced
        assertThat(writer.stub.events.none { it.startsWith("NOTE") }).isTrue()
        assertThat(rig.owner.facts).isEqualTo(StreamFacts(rig.owner.generation, 96_000, 2))
    }

    // ── recovery + the unplug ──────────────────────────────────────────────────────────────────

    @Test
    fun `an unplug during a recovery pauses at once, stops the recovery at its next call and claims nothing after the detach`() {
        val rig = Rig()
        rig.configure(44_100, 1)
        val writer = rig.startWriter()
        val block = rig.device.blockNext("setAlt", "UsbStreamingThread")
        try {
            writer.requestRecovery(2)                                              // step 2 would reset the bus and claim the interfaces
            assertThat(block.entered.await(5, TimeUnit.SECONDS)).isTrue()
            val startedNs = System.nanoTime()
            writer.thread.pauseStreaming()                                         // what the engine's pause comes down to on the sink side
            rig.owner.deviceDetached()
            rig.device.gone = true
            rig.device.mark("DETACH")
            val tookMs = (System.nanoTime() - startedNs) / 1_000_000
            assertThat(tookMs).isLessThan(UNPLUG_BOUND_MS)                         // the bound: neither the pause nor the detach waits for the recovery (it is blocked in a device call here)
            println("B48 unplug: pauseStreaming + deviceDetached took $tookMs ms while the recovery was inside a device call")
            block.release.countDown()
            assertThat(waitFor { writer.stub.events.contains("END") }).isTrue()
        } finally {
            writer.stop()
        }
        val trail = rig.device.trail()
        assertThat(rig.device.violations).isEmpty()
        assertThat(trail.dropWhile { it != "MARK:DETACH" }.drop(1)).isEmpty()      // not one call after the detach: no reset, no claim, no clock
        assertThat(trail.none { it.endsWith("resetAndReclaim") }).isTrue()
        assertThat(rig.logs.single { it.startsWith("DAC_RECOVERY step=2 result=superseded") }).contains("by=detached before=bus-reset")
        assertThat(writer.stub.events.none { it.startsWith("NOTE") }).isTrue()
    }

    // ── recovery + the hand-back ───────────────────────────────────────────────────────────────

    @Test
    fun `a hand-back during a recovery waits for it and nothing is claimed again after the hand-back`() {
        val rig = Rig()
        rig.configure(44_100, 1)
        val writer = rig.startWriter()
        val block = rig.device.blockNext("resetAndReclaim", "UsbStreamingThread")
        try {
            writer.requestRecovery(2)
            assertThat(block.entered.await(5, TimeUnit.SECONDS)).isTrue()         // inside the bus reset and the claims
            val handedBack = playback("main") { rig.handBack() }
            assertThat(handedBack.await(250, TimeUnit.MILLISECONDS)).isFalse()
            block.release.countDown()
            assertThat(handedBack.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            writer.stop()
        }
        val trail = rig.device.trail()
        assertThat(rig.device.violations).isEmpty()
        assertThat(trail.last()).isEqualTo("main:handBack")
        assertThat(trail.indexOfLast { it.startsWith("UsbStreamingThread:") }).isLessThan(trail.indexOf("main:handBack"))
        assertThat(rig.owner.facts).isNull()
    }

    @Test
    fun `a recovery requested after the hand-back does nothing to the device`() {
        val rig = Rig()
        rig.configure(44_100, 1)
        val writer = rig.startWriter()
        rig.handBack()
        rig.device.mark("HANDED_BACK")
        try {
            writer.requestRecovery(2)
            assertThat(waitFor { rig.logs.any { it.startsWith("DAC_RECOVERY step=2 result=superseded") } }).isTrue()
        } finally {
            writer.stop()
        }
        assertThat(rig.device.violations).isEmpty()
        assertThat(rig.device.trail().dropWhile { it != "MARK:HANDED_BACK" }.drop(1)).isEmpty()   // no reset, no re-claim of what was handed back
        assertThat(writer.stub.events).doesNotContain("BEGIN")                                    // not even the ring was touched
    }

    // ── recovery + a seek ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a seek during a recovery waits on the writer, not on the device, and is done after the recovery`() {
        val rig = Rig()
        rig.configure(44_100, 1)
        val writer = rig.startWriter()
        val generation = rig.owner.generation
        val block = rig.device.blockNext("setAlt", "UsbStreamingThread")
        try {
            writer.requestRecovery(1)
            assertThat(block.entered.await(5, TimeUnit.SECONDS)).isTrue()
            val startedNs = System.nanoTime()
            writer.thread.flush()                                                   // Media3's seek: the sink's flush() comes down to this
            val tookMs = (System.nanoTime() - startedNs) / 1_000_000
            assertThat(tookMs).isLessThan(500)                                      // the playback thread is not held by the recovery
            block.release.countDown()
            assertThat(waitFor { writer.stub.events.contains("SEEK") }).isTrue()
        } finally {
            writer.stop()
        }
        val events = writer.stub.events.toList()
        assertThat(rig.device.violations).isEmpty()
        // the seek's ring work never lands between the two halves of the recovery, and comes after it
        assertThat(events.subList(events.indexOf("BEGIN"), events.indexOf("END") + 1).none { it == "SEEK" || it == "W" }).isTrue()
        assertThat(events.indexOf("SEEK")).isGreaterThan(events.indexOf("END"))
        // a seek is not a new stream: same generation, same facts
        assertThat(rig.owner.generation).isEqualTo(generation)
        assertThat(rig.owner.facts).isEqualTo(StreamFacts(generation, 44_100, 1))
    }

    // ── fast skips ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `ten rate changes in two seconds with a recovery asked in the middle never interleave and a recovery restarts its own stream`() {
        fastSkipsWithARecovery(latencyMs = 12)                                      // a recovery is ~100 ms of device time: it overlaps the next rebuild
    }

    @Test
    fun `ten rate changes with a slow recovery in the middle wait for it and never interleave`() {
        fastSkipsWithARecovery(latencyMs = 350)                                     // a recovery is ~2.5 s of device time: longer than the old join
    }

    private fun fastSkipsWithARecovery(latencyMs: Long) {
        val rig = Rig()
        val rates = listOf(44_100, 96_000, 48_000, 192_000, 88_200, 44_100, 96_000, 176_400, 48_000, 96_000)
        val alts = listOf(1, 2, 1, 3, 2, 1, 2, 3, 1, 2)
        val writers = ArrayList<Writer>()
        rig.device.writerLatencyMs = latencyMs
        rig.configure(rates[0], alts[0])
        writers += rig.startWriter()
        val startedNs = System.nanoTime()
        val rebuilder = playback("playback") {
            for (i in 1 until rates.size) {
                if (i == 5) {
                    writers.last().requestRecovery(1)                              // a recovery of the 5th stream, asked right before the next skip
                    Thread.sleep(30)
                }
                val old = writers.last()
                // what the sink does: the section, the old writer stopped inside it, the new stream, a thread made for it
                rig.configure(rates[i], alts[i]) { old.stop() }
                writers += rig.startWriter()
                Thread.sleep(150)
            }
        }
        assertThat(rebuilder.await(20, TimeUnit.SECONDS)).isTrue()
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        writers.last().stop()
        assertThat(elapsedMs).isLessThan(if (latencyMs > 100) 15_000 else 5_000)
        assertThat(rig.device.violations).isEmpty()
        assertThat(rig.owner.facts!!.sampleRateHz).isEqualTo(rates.last())
        assertThat(rig.owner.generation).isAtLeast(9L)
        // whichever way the recovery went (finished before the skip, or superseded by it), it never programmed another stream's clock
        val recoveredWriter = writers[4]
        val recoveryClockWrites = rig.device.clockWrites.filter { it.first === recoveredWriter.stub.writerThread }.map { it.second }
        assertThat(recoveryClockWrites.all { it == recoveredWriter.rate }).isTrue()
        val superseded = rig.logs.any { it.startsWith("DAC_RECOVERY step=1 result=superseded") }
        assertThat(superseded || recoveryClockWrites.isNotEmpty()).isTrue()
    }
}
