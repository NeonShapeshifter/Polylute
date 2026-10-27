package com.decent.usbaudio

/**
 * B40-P4, corrected by B40-fix1 and B40-fix2: the hand-back after a bus reset, in the order that never lets Android reach a DAC whose
 * level is not known, and that says exactly what Polylute holds and what the kernel holds when it cannot.
 *
 * [UsbAudioDevice.releaseToAndroid] falls back to a bus reset (`USBDEVFS_RESET`) when an interface still has no kernel driver after the
 * CONNECT. The reset restarts the DAC's firmware, and a DAC can boot back at its default level (often 100 %). And the reset does not leave
 * the interfaces where they were: the kernel unbinds a driver that has no `pre_reset`/`post_reset` from its interface, usbfs is such a
 * driver, and the same ioctl then rebinds every interface to `snd-usb-audio` before it returns, so the card, streaming included, exists
 * again with the DAC at its power-up level. (Kernel sources and what window is left: B40-fix1-resultado.)
 *
 * The order here:
 *  1. the reset;
 *  2. **at once** the interfaces are claimed again, **streaming first** (it is what Android plays through) and then control, and each
 *     that failed is tried once more ([ClaimedInterfaces]); nothing, not even a log line, between the claims. What is claimed is
 *     recorded as exactly that, and nothing else;
 *  3. with control held the level is restored and read back, by the same rule as every open ([DacLevelGuard.restoreAfterReconfigure]);
 *  4. if **both** are held and the level is confirmed, the interfaces go back to Android: streaming, then control, then the kernel
 *     driver (the AUD-003 order) → [Outcome.RESTORED];
 *  5. both held and the level not confirmed: **nothing is released**, Polylute keeps both ([Outcome.UNCONFIRMED]);
 *  6. an interface that could not be claimed means the kernel holds it and the DAC is with Android at a level that is not confirmed
 *     ([Outcome.CONTROL_NOT_HELD], [Outcome.STREAMING_NOT_HELD], [Outcome.NOT_HELD]): what Polylute did hold goes back, the outcome
 *     says so, and the next claim of the DAC puts the level back the normal way.
 *
 * Pure: every platform call is a lambda, so the order is what is tested.
 */
class ResetLevelRestorer(
    private val reset: () -> Boolean,
    private val holdInterfaces: () -> ClaimedInterfaces,
    private val restoreLevel: () -> OpenLevelCheck,
    private val releaseStreaming: () -> Unit,
    private val releaseControl: () -> Unit,
    private val reattachKernelDriver: () -> Unit,
    private val keepHeld: (ClaimedInterfaces) -> Unit,
) {
    /** What the claims right after the reset got. Only what is true: a claim that failed is `false`. */
    data class ClaimedInterfaces(val streaming: Boolean, val control: Boolean) {
        val both: Boolean get() = streaming && control
        val none: Boolean get() = !streaming && !control
    }

    /**
     * How a hand-back that needed the bus reset ended, in terms of who holds the DAC. In each, the kernel holds whatever Polylute does
     * not (`snd-usb-audio` is bound to it again after the reset).
     */
    enum class Outcome(
        /** The level is known to be no louder than the confirmed one (or there is none to keep): the DAC may be shared. */
        val levelConfirmed: Boolean,
        /** Polylute still holds the streaming interface. */
        val polyluteHoldsStreaming: Boolean,
        /** Polylute still holds the control interface. */
        val polyluteHoldsControl: Boolean,
        /** The DAC is with Android, or may have been reached by it, at a level that is not confirmed. */
        val dacWithAndroid: Boolean,
    ) {
        /** The bus reset itself failed: nothing changed, nothing was claimed, there is no level to speak of. */
        RESET_FAILED(levelConfirmed = false, polyluteHoldsStreaming = false, polyluteHoldsControl = false, dacWithAndroid = false),

        /** The DAC has no hardware volume (fixed output): there is no level to restore, and it was not held. The kernel holds both. */
        NO_HARDWARE_VOLUME(levelConfirmed = true, polyluteHoldsStreaming = false, polyluteHoldsControl = false, dacWithAndroid = false),

        /** Both interfaces were held, the level was read after the reset, brought down to the confirmed one where it was above it, and read back; the DAC was given back. The kernel holds both. */
        RESTORED(levelConfirmed = true, polyluteHoldsStreaming = false, polyluteHoldsControl = false, dacWithAndroid = false),

        /**
         * Both held, the level could not be read or the restore did not take. **Polylute holds both**, the kernel none: the card is down
         * (the control interface is Polylute's) and Android cannot reach the DAC. The level is owed.
         */
        UNCONFIRMED(levelConfirmed = false, polyluteHoldsStreaming = true, polyluteHoldsControl = true, dacWithAndroid = false),

        /**
         * The control interface could not be claimed (not after a second try either), so the level could not be looked at or lowered. What
         * was held (streaming, if it was) was given back: **Polylute holds nothing, the kernel holds both, the DAC is with Android** at a
         * level nobody knows.
         */
        CONTROL_NOT_HELD(levelConfirmed = false, polyluteHoldsStreaming = false, polyluteHoldsControl = false, dacWithAndroid = true),

        /**
         * The streaming interface could not be claimed (not after a second try either): the kernel holds it, and Android may have been
         * streaming into the DAC at its power-up level before anything could be done. Control was held, so the level was brought down and
         * read as well as it could be, but **it is not confirmed**; control was given back: **Polylute holds nothing, the DAC is with Android**.
         */
        STREAMING_NOT_HELD(levelConfirmed = false, polyluteHoldsStreaming = false, polyluteHoldsControl = false, dacWithAndroid = true),

        /** Neither interface could be claimed: the kernel holds both, the level was not looked at, **the DAC is with Android**. */
        NOT_HELD(levelConfirmed = false, polyluteHoldsStreaming = false, polyluteHoldsControl = false, dacWithAndroid = true),
        ;

        /** The host is told how the hand-back ended (a failed reset changed nothing, so there is nothing to tell). */
        val reportsToHost: Boolean get() = this != RESET_FAILED

        /** Polylute still holds an interface, so the device stays open and the level is owed (it is looked at again at the next open or hand-back). */
        val deviceKept: Boolean get() = polyluteHoldsStreaming || polyluteHoldsControl
    }

    fun run(hasHardwareVolume: Boolean): Outcome {
        if (!reset()) return Outcome.RESET_FAILED
        // A fixed output has no level the reset could have raised: nothing to hold, nothing to restore (the interfaces went back to the
        // kernel inside the reset).
        if (!hasHardwareVolume) return Outcome.NO_HARDWARE_VOLUME
        val claimed = holdInterfaces()
        if (claimed.none) return Outcome.NOT_HELD
        // Control is the way to the level. Without it nothing can be looked at or lowered, and what is held goes back: the DAC is with Android.
        if (!claimed.control) {
            releaseStreaming()
            reattachKernelDriver()
            return Outcome.CONTROL_NOT_HELD
        }
        val check = try {
            restoreLevel()
        } catch (e: RuntimeException) {
            OpenLevelCheck.UNVERIFIED
        }
        // Streaming not held: whatever the restore found, Android may already have streamed into the DAC, so the level is not confirmed.
        // What is held (control) goes back, the same way, and the DAC is with Android.
        if (!claimed.streaming) {
            releaseControl()
            reattachKernelDriver()
            return Outcome.STREAMING_NOT_HELD
        }
        if (check == OpenLevelCheck.UNVERIFIED) {
            keepHeld(claimed)
            return Outcome.UNCONFIRMED
        }
        releaseStreaming()
        releaseControl()
        reattachKernelDriver()
        return if (check == OpenLevelCheck.VERIFIED) Outcome.RESTORED else Outcome.NO_HARDWARE_VOLUME
    }
}

/**
 * The claims right after the bus reset (B40-fix2), apart from the platform: **streaming first**, because that is what another app
 * plays through, then control; each that failed is tried once more, and only once (a bound on the time the DAC is in this state). Nothing
 * but the claims in between: no log line, no driver query.
 */
class ResetClaimer(
    private val claimStreaming: () -> Boolean,
    private val claimControl: () -> Boolean,
) {
    /** A streaming interface the device does not have has nothing to claim (and nothing to hold). */
    fun claim(hasStreaming: Boolean = true, hasControl: Boolean = true): ResetLevelRestorer.ClaimedInterfaces {
        var streaming = !hasStreaming || claimStreaming()
        var control = hasControl && claimControl()
        // the one more try, in the same order
        if (!streaming) streaming = claimStreaming()
        if (hasControl && !control) control = claimControl()
        return ResetLevelRestorer.ClaimedInterfaces(streaming = streaming && hasStreaming, control = control)
    }
}
