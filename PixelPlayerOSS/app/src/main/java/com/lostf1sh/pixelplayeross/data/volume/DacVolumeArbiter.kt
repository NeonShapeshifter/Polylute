package com.lostf1sh.pixelplayeross.data.volume

/** What a temporary write (a Direct duck's step) came to. */
enum class TemporaryWrite {
    /** The DAC holds the step. */
    WRITTEN,

    /** The DAC would not take it, or would not read it back. Nothing else owns the volume: the caller may stop. */
    REFUSED,

    /** Somebody else wrote the DAC's volume (or the DAC is no longer the one the duck began on): nothing was written. */
    SUPERSEDED,

    /**
     * B16-G2: the step would raise the DAC above the Safety Max ceiling as it stands **at the moment of the write**:
     * nothing was written. The volume is still the duck's: the caller reads the ceiling again and decides (a restore
     * stops at the new ceiling, or at where the DAC already is).
     */
    ABOVE_CEILING,
}

/**
 * B15-F1: one owner of the DAC's volume at a time. B16-G1: and one lock.
 *
 * Two kinds of writer reach the DAC's volume:
 * - the **listener's** (and Safety's): a volume key, the readout, the ceiling clamp, the fade that ends an override,
 *   the guard that lowers the DAC at open. Each of these is the listener's level, and it wins;
 * - a **temporary** one: a Direct duck's ramp, which only ever lowers and then gives the level back.
 *
 * Both run under the same lock, so a duck step and a listener write never interleave on the wire, and every
 * listener write bumps a counter. A duck takes the counter when it reads the DAC ([observe]) and presents it with
 * every step ([temporaryWrite]): a step is a compare-and-set, written only if nobody else has written since, the
 * DAC still reads the step the duck last left there, and (B16-G2) the step does not raise the DAC above the ceiling
 * *as it is then*. Anything else is [TemporaryWrite.SUPERSEDED] or [TemporaryWrite.ABOVE_CEILING] and nothing is
 * written, so the listener's choice stands.
 *
 * ## The lock order (B16-G1), in one sentence
 *
 * **The USB device's own monitor is the only lock there is: the arbiter has none of its own, it takes the device's
 * (`UsbAudioDevice`'s `@Synchronized` instance monitor, passed in as [lock]), so no thread can ever hold the arbiter
 * and wait for the device, or the other way round, and the callbacks the driver runs under that monitor (the volume
 * guard) may call [supersede] and [listenerWrite] because the monitor is re-entrant and they take nothing else.**
 *
 * B15 had a lock of its own here and took it *before* the device's monitor (a volume write: arbiter → device) while
 * the driver's `restoreLevelAfterReconfigure` took the device's monitor and, through the volume guard, the arbiter
 * (device → arbiter): a rate change and a volume write at the same moment waited for each other for good.
 *
 * Everything inside the lock must stay non-blocking except the DAC's own control transfers: the `read`/`write`/
 * `ceilingStep` lambdas may read `StateFlow.value`s and volatile fields, and nothing that takes an app lock.
 *
 * @param lock the monitor every access to the DAC's volume is serialised on: in the app, the `UsbAudioDevice` instance.
 */
class DacVolumeArbiter(private val lock: Any) {
    /** Guarded by [lock]. */
    private var listenerWrites = 0L

    /** Runs a listener or Safety write: serialised with every duck step, and marks that the volume was set by someone else. */
    fun <T> listenerWrite(write: () -> T): T = synchronized(lock) {
        listenerWrites++
        write()
    }

    /**
     * The volume changed hands without a write of ours (a different DAC, a lowering at open): outstanding ducks are over.
     * Safe to call from inside the device's monitor (the volume guard does): it is re-entrant.
     */
    fun supersede() {
        synchronized(lock) { listenerWrites++ }
    }

    /** Reads the DAC under the lock together with the counter a duck must present from now on. */
    fun <T> observe(read: () -> T): Pair<Long, T> = synchronized(lock) { listenerWrites to read() }

    /**
     * One step of a ramp, compare-and-set.
     *
     * @param ticket the counter [observe] returned when the duck read the DAC
     * @param expectedStep where the duck last left the DAC
     * @param step the step to write
     * @param ceilingStep the Safety Max ceiling in steps **now** (null: none in force), evaluated here, under the same
     *        lock as the write, after the DAC was read: a step that raises the DAC above it is not written. A step
     *        that lowers is always allowed, whatever the ceiling.
     * @param readStep the DAC's level now in steps, null if it cannot be read or is not the hardware backend any more
     * @param writeStep writes the step and reads it back; true only if the DAC holds it
     */
    fun temporaryWrite(
        ticket: Long,
        expectedStep: Int,
        step: Int,
        ceilingStep: () -> Int?,
        readStep: () -> Int?,
        writeStep: () -> Boolean,
    ): TemporaryWrite = synchronized(lock) {
        if (listenerWrites != ticket) return@synchronized TemporaryWrite.SUPERSEDED
        if (readStep() != expectedStep) return@synchronized TemporaryWrite.SUPERSEDED
        if (step > expectedStep) {
            val ceiling = ceilingStep()
            if (ceiling != null && step > ceiling) return@synchronized TemporaryWrite.ABOVE_CEILING
        }
        if (writeStep()) TemporaryWrite.WRITTEN else TemporaryWrite.REFUSED
    }
}
