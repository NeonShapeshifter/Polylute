package com.decent.usbaudio

/**
 * B47-A5, step 2 of a locked-up DAC's recovery: a bus reset in the middle of a stream, the interfaces claimed again, and the DAC's level put back, before any audio.
 *
 * It is the B39/B40 sequence ([ResetLevelRestorer], [ResetClaimer]) with one difference: the hand-back gives the interfaces back to Android after the level is confirmed,
 * and this keeps them, because the stream goes on. A bus reset restarts the DAC's firmware, and a DAC can boot back at its default level (often 100 %); usbfs claims do not
 * survive it. So the order is the hand-back's: reset, **at once** the claims (streaming first, then control, each retried once), then the level restored and read back by
 * the rule of every open ([DacLevelGuard.restoreAfterReconfigure] through `restoreLevel`).
 *
 * The volume never comes back higher than it was: `restoreLevel` is [UsbAudioDevice.restoreLevelAfterReconfigure], which writes only a level below the current one
 * ([DirectOpenLevelPolicy.target]: the quietest of the saved Direct level and the guard's ceiling, and only when that is below what the DAC reports now), reads it back
 * and answers UNVERIFIED when the read-back is above the target or the level cannot be read. UNVERIFIED here is [Result.ok] = false and `levelOwed` = true: nothing may
 * play at an unknown level, and the debt is settled at the next open or hand-back as after a hand-back's reset.
 *
 * Pure: every platform call is a lambda, so the order and the outcomes are what is tested.
 */
class StreamingBusReset(
    private val reset: () -> Boolean,
    private val hasStreaming: Boolean,
    private val hasControl: Boolean,
    private val claimStreaming: () -> Boolean,
    private val claimControl: () -> Boolean,
    private val restoreLevel: () -> OpenLevelCheck,
) {
    data class Result(
        /** Reset, both claims, and the level confirmed (or there is no hardware volume): the stream may carry on. */
        val ok: Boolean,
        val outcome: ResetLevelRestorer.Outcome,
        /** What Polylute holds after it: exactly what the claims got. */
        val heldStreaming: Boolean,
        val heldControl: Boolean,
        /** The level was held back as unconfirmed after the reset: the device must look at it again at the next open. */
        val levelOwed: Boolean,
    )

    fun run(): Result {
        var heldStreaming = false
        var heldControl = false
        var levelOwed = false
        val outcome = ResetLevelRestorer(
            reset = reset,
            holdInterfaces = {
                val claimed = ResetClaimer(claimStreaming, claimControl).claim(hasStreaming = hasStreaming, hasControl = hasControl)
                heldStreaming = claimed.streaming
                heldControl = claimed.control
                claimed
            },
            restoreLevel = restoreLevel,
            // The stream goes on: nothing goes back to Android, so nothing is released and no kernel driver is attached.
            releaseStreaming = {},
            releaseControl = {},
            reattachKernelDriver = {},
            keepHeld = { levelOwed = true },
        // A device without a hardware volume still needs its interfaces claimed again after the reset: ask the restorer to claim in every case.
        // (`restoreLevel` answers NO_HARDWARE_VOLUME for it, which the restorer takes as "nothing to restore".)
        ).run(hasHardwareVolume = true)
        val ok = (outcome == ResetLevelRestorer.Outcome.RESTORED || outcome == ResetLevelRestorer.Outcome.NO_HARDWARE_VOLUME) &&
            heldStreaming == hasStreaming && heldControl == hasControl
        return Result(ok = ok, outcome = outcome, heldStreaming = heldStreaming, heldControl = heldControl, levelOwed = levelOwed)
    }
}
