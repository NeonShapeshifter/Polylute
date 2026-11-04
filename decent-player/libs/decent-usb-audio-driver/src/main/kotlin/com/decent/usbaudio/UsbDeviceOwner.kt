package com.decent.usbaudio

/**
 * The facts of one live USB stream, fixed when it came up: the [generation] it belongs to, the clock rate it was configured with and the alternate setting it uses.
 * A recovery reads these, never the sink's fields of the moment (B48-1).
 */
data class StreamFacts(val generation: Long, val sampleRateHz: Int, val altSetting: Int)

/**
 * B48-1: who may touch the USB device's interfaces right now.
 *
 * Several threads work on the same device: the writer thread (a locked-up DAC's recovery restarts the streaming interface), the playback thread (a track at another rate
 * recreates the stream; a release; a hand-back after a failed claim), the main thread (the hand-back when Direct is left, the unplug) and, for the volume, any thread. Each of
 * those is a sequence of calls that is only meaningful if nothing else moves the interface in between (`setAlt(0)`, the clock, `setAlt(N)`, the level). B47 let the writer run its
 * sequence whenever it decided to, reading the sink's rate and alt of that moment.
 *
 * **One owner.** One instance per [UsbAudioDevice], the singleton every party already reaches the device through (it holds the one connection and the claims), so two parties
 * cannot hold different owners. A sequence runs in one of two ways:
 *  - [exclusive]: a stream (re)configuration, a stream release, a hand-back. Waits for a recovery that holds the device, at most [exclusiveWaitMs].
 *  - [claimForRecovery]: the writer's recovery. It never waits: if the generation it was started for is not the current one, or somebody else holds the device, it is
 *    **superseded** and does nothing.
 *
 * **One generation.** A counter bumped whenever the stream or the attachment is replaced: an [exclusive] section starts (a rebuild, a release, a hand-back) and the DAC is gone
 * ([deviceDetached]). A recovery is started for generation N ([StreamFacts.generation]) and is asked at two moments:
 *  - [RecoveryClaim.commit], before its **first** device call: false if the generation moved (a rate change, a release or a hand-back came first) or the DAC is gone. It then
 *    touches nothing, and says so in one log line.
 *  - [RecoveryClaim.mayTouchDevice], before each later call: false only when the DAC is gone. A recovery that has begun finishes its sequence, the level restore included
 *    (the Melody puts its Feature Unit back to 0 dB on a clock or alt change; B47 promises the level never comes back higher), and the section that arrived meanwhile
 *    waits for it, at most [exclusiveWaitMs]. The device sees the recovery's calls and then the rebuild's, never both at once.
 *
 * **What it does not hold.** The monitor is held only to read and write the owner's own state, never across a device call, so [deviceDetached], [facts] and [generation] never
 * wait for a recovery (the unplug must pause playback at once; B25). The pause, a seek and the producer's queue do not touch the device's interfaces and do not go through it.
 */
class UsbDeviceOwner(
    private val exclusiveWaitMs: Long = EXCLUSIVE_WAIT_MS,
    private val deviceGone: () -> Boolean = { false },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    private val monitor = Object()

    /** B50: the last successfully started stream in this attachment, retained across releases. */
    @Volatile
    private var previousStreamRateValue = 0
    val previousStreamRateHz: Int get() = previousStreamRateValue

    @Volatile
    private var generationValue = 0L

    /** Bumped by [deviceDetached] only: what stops a recovery that has already begun. */
    @Volatile
    private var detachCount = 0L

    @Volatile
    private var factsValue: StreamFacts? = null

    /** The thread inside an [exclusive] section and how deep it is (the sections nest on one thread: a failed claim hands back from inside a configure). */
    private var exclusiveThread: Thread? = null
    private var exclusiveDepth = 0
    private var exclusiveWho = ""

    /** The recovery that holds the device, if one does. */
    private var recovery: RecoveryClaim? = null

    /** The current generation. Lock-free; read by anybody. */
    val generation: Long get() = generationValue

    /** The facts of the live stream (published by [publishStream]), or null when there is none. */
    val facts: StreamFacts? get() = factsValue

    /**
     * Runs [block] with the device to itself: a stream (re)configuration, a stream release, a hand-back. Starting the section is a new generation, so a recovery that has
     * not reached the device gives up; one that has finishes, and the section waits for it, at most [exclusiveWaitMs]. After that bound the section runs
     * anyway (as every section did before B48) and says so in the log: a hand-back or a release that never ran would leave the DAC stuck with Polylute, which is worse than the
     * overlap it replaces. Re-entrant on one thread; another thread's section is waited for in the same way.
     */
    fun <T> exclusive(who: String, block: () -> T): T {
        enter(who)
        try {
            return block()
        } finally {
            leave()
        }
    }

    private fun enter(who: String) {
        val me = Thread.currentThread()
        val startedMs = nowMs()
        synchronized(monitor) {
            if (exclusiveThread === me) {
                exclusiveDepth++
                return
            }
            generationValue++
            var waitedFor = ""
            while ((exclusiveThread != null || recovery != null) && nowMs() - startedMs < exclusiveWaitMs) {
                waitedFor = heldBy()
                monitor.wait((exclusiveWaitMs - (nowMs() - startedMs)).coerceAtLeast(1L))
            }
            if (exclusiveThread != null || recovery != null) {
                log("OWNER_FORCED who=$who waitedMs=${nowMs() - startedMs} stillHeldBy=${heldBy()}: the bound passed, going on as before B48")
            } else if (waitedFor.isNotEmpty()) {
                log("OWNER_WAITED who=$who waitedMs=${nowMs() - startedMs} for=$waitedFor gen=$generationValue")
            }
            exclusiveThread = me
            exclusiveDepth = 1
            exclusiveWho = who
        }
    }

    private fun heldBy(): String = if (exclusiveThread != null) exclusiveWho else "recovery step ${recovery?.step}"

    private fun leave() {
        synchronized(monitor) {
            if (--exclusiveDepth == 0) {
                exclusiveThread = null
                exclusiveWho = ""
                monitor.notifyAll()
            }
        }
    }

    /** A stream is up (the end of a configure, inside its [exclusive] section): its facts are those a recovery reads. */
    fun publishStream(sampleRateHz: Int, altSetting: Int): StreamFacts = synchronized(monitor) {
        val facts = StreamFacts(generationValue, sampleRateHz, altSetting)
        factsValue = facts
        previousStreamRateValue = sampleRateHz
        facts
    }

    /** The stream is gone (released, failed, handed back): nothing is left to recover. */
    fun clearStream() {
        synchronized(monitor) { factsValue = null }
    }

    /**
     * The DAC went away (the bus says so, or the stream found itself dead for that reason). Never waits: a recovery in the middle of its sequence stops at its next device call,
     * and nothing is claimed or reset after this returns.
     */
    fun deviceDetached() {
        val now: Long
        synchronized(monitor) {
            previousStreamRateValue = 0
            // Said on every refresh of the bus while no DAC is there: only a stream, a recovery or a section in flight has anything to stop.
            if (factsValue == null && recovery == null && exclusiveThread == null) return
            generationValue++
            detachCount++
            factsValue = null
            now = generationValue
        }
        log("OWNER_DETACHED gen=$now: no device call of an earlier generation will be made")
    }

    /**
     * The writer's recovery asks to take the device for [step], for the stream of [generation] (the thread's own, fixed when it was made). Null when it is superseded: the
     * generation is not the current one, the DAC is gone, or a section holds the device. It never waits. The claim must be [RecoveryClaim.close]d.
     */
    fun claimForRecovery(generation: Long, step: Int): RecoveryClaim? {
        val gone = deviceGone()   // outside the monitor: it asks the bus
        val why: String
        synchronized(monitor) {
            val facts = factsValue
            why = when {
                generation != generationValue -> "generation"
                facts == null || facts.generation != generation -> "no-stream"
                exclusiveThread != null -> "exclusive:$exclusiveWho"
                recovery != null -> "recovery"
                gone -> "device-gone"
                else -> ""
            }
            if (why.isEmpty()) {
                val claim = RecoveryClaim(this, facts!!, step, detachCount)
                recovery = claim
                return claim
            }
        }
        log("DAC_RECOVERY step=$step result=superseded gen=$generation now=$generationValue by=$why")
        return null
    }

    private fun releaseRecovery(claim: RecoveryClaim) {
        synchronized(monitor) {
            if (recovery === claim) {
                recovery = null
                monitor.notifyAll()
            }
        }
    }

    private fun mayTouch(claim: RecoveryClaim, what: String, first: Boolean): Boolean {
        val gone = deviceGone()   // outside the monitor: it asks the bus
        val why: String
        synchronized(monitor) {
            why = when {
                claim.detachCountAtClaim != detachCount -> "detached"
                gone -> "device-gone"
                first && claim.facts.generation != generationValue -> "generation"
                else -> ""
            }
            if (why.isEmpty()) return true
        }
        log("DAC_RECOVERY step=${claim.step} result=superseded gen=${claim.facts.generation} now=$generationValue by=$why before=$what")
        return false
    }

    /** The recovery's hold on the device for one step. It knows the facts of its own generation. */
    class RecoveryClaim internal constructor(private val owner: UsbDeviceOwner, val facts: StreamFacts, val step: Int, internal val detachCountAtClaim: Long) : AutoCloseable {
        /** Before the first device call: false = superseded (the generation moved, or the DAC is gone); make no call. The owner has logged the one line. */
        fun commit(what: String): Boolean = owner.mayTouch(this, what, first = true)

        /** Before each later call: false only when the DAC is gone; stop, and claim or reset nothing more. */
        fun mayTouchDevice(what: String): Boolean = owner.mayTouch(this, what, first = false)

        override fun close() = owner.releaseRecovery(this)
    }

    companion object {
        /**
         * How long an exclusive section waits for a recovery (or another section) that holds the device: a recovery's device work is 200–400 ms in the writer simulation
         * (a bus reset re-enumerates in a few seconds), and 3 s stays below the 8 s a track's tail may take to play out before a rebuild.
         */
        const val EXCLUSIVE_WAIT_MS = 3_000L
    }
}
