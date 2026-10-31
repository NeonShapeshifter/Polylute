package com.decent.usbaudio

/**
 * The device calls that (re)configure the streaming interface and that a locked-up DAC's recovery repeats. [UsbAudioDevice] in production, a recorder in tests (B48-1).
 */
interface StreamingDeviceOps {
    fun setAltSetting(altSetting: Int): Boolean
    fun setSampleRate(sampleRateHz: Int): Boolean
    fun readSampleRate(): Int
    fun readClockValid(): Boolean
    fun restoreLevelAfterReconfigure(reason: String): OpenLevelCheck

    /** Step 2 of a recovery: the bus reset, the interfaces claimed again and the level restored. True when both interfaces are held and the level is confirmed. */
    fun resetAndReclaimForStreaming(): Boolean
}

/** Why [StreamingInterfaceSequence.reconfigure] stopped: the message the caller reports, and whether the interface goes back to zero bandwidth first (as the clock and level failures always did). */
class InterfaceFailure(val message: String, val altZeroFirst: Boolean)

class InterfaceReconfigured(val confirmedSampleRate: Int, val failure: InterfaceFailure?)

/** How a recovery's interface restart ended. */
enum class RestartOutcome {
    /** Every call was made and succeeded. */
    RAN,

    /** A call failed (the reason is in the log). */
    FAILED,

    /** The owner said the device is not this recovery's to touch (a newer generation, or the DAC is gone); `DAC_RECOVERY ... result=superseded` is logged. */
    SUPERSEDED,
}

/**
 * The xHCI-verified sequence after the old ISO ring is freed (`setAlt(0)` is the caller's step 1: it has a stale-fd path of its own): the new rate in the clock source and
 * read back, `setAlt(0)` again, `setAlt(N)`, ~47 ms for the DAC's PLL, then the level (AUD-126: the Melody puts its Feature Unit back to 0 dB on these; the quietest of the
 * saved Direct level and the guard's answer, never raising). B47-A5: the stream's start-up and a locked-up DAC's recovery run exactly this, so a step of the recovery is the
 * ordinary reconfigure and not a second implementation of it. B48-1: moved out of the sink so that both run against one [StreamingDeviceOps] under one [UsbDeviceOwner].
 */
class StreamingInterfaceSequence(
    private val device: StreamingDeviceOps,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
) {
    fun reconfigure(sampleRate: Int, altSetting: Int, levelReason: String): InterfaceReconfigured {
        // Step 2: SET_CUR — write new sample rate
        val sampleRateSet = device.setSampleRate(sampleRate)

        // Step 3: GET_CUR — verify the requested rate before enabling direct output.
        // Continuing after a failed SET_CUR can make a DAC run at its previous/default
        // clock while Media3 feeds a different source rate, producing incorrect-speed audio.
        val confirmedSampleRate = device.readSampleRate()
        val clockValid = device.readClockValid()
        log("Step 2-3: SET_CUR=$sampleRate success=$sampleRateSet, GET_CUR=$confirmedSampleRate, CLOCK_VALID=$clockValid")
        if (!sampleRateSet || confirmedSampleRate != sampleRate || !clockValid) {
            return InterfaceReconfigured(
                confirmedSampleRate,
                InterfaceFailure(
                    "USB DAC rejected or did not lock sample rate $sampleRate Hz " +
                        "(confirmed=$confirmedSampleRate, clockValid=$clockValid)",
                    altZeroFirst = true,
                ),
            )
        }

        // Step 4: setAlt(0) AGAIN — defensive reset after clock change
        if (!device.setAltSetting(0)) {
            return InterfaceReconfigured(confirmedSampleRate, InterfaceFailure("USB DAC failed to enter zero-bandwidth state", altZeroFirst = false))
        }
        log("Step 4: setAlt(0) again — defensive reset")

        // Step 5: setAlt(N) — ALLOC new ISO rings
        val altResult = device.setAltSetting(altSetting)
        log("Step 5: setAlt($altSetting): $altResult — new ISO ring allocated")
        if (!altResult) {
            return InterfaceReconfigured(confirmedSampleRate, InterfaceFailure("USB DAC rejected streaming alternate setting $altSetting", altZeroFirst = false))
        }

        // Step 6: wait ~47ms — DAC PLL lock time
        sleepMs(PLL_SETTLE_MS)

        // Step 6½ (AUD-126): the level, read after the alt and the clock settled and before the first
        // packet. The Melody resets its Feature Unit to 0 dB on these; the claim-time guard (AUD-118)
        // never saw a reconfigure of the device already open.
        val levelCheck = device.restoreLevelAfterReconfigure(levelReason)
        if (levelCheck == OpenLevelCheck.UNVERIFIED) {
            return InterfaceReconfigured(
                confirmedSampleRate,
                InterfaceFailure(
                    "DAC volume could not be read or restored after the stream reconfigure; Direct refused (AUD-126)",
                    altZeroFirst = true,
                ),
            )
        }
        return InterfaceReconfigured(confirmedSampleRate, null)
    }

    /**
     * B47-A5, B48-1: what a locked-up DAC's recovery does on the device, for the stream of [claim]'s generation: its rate and its alt, from the facts fixed when that stream came
     * up (the sink's fields of the moment may already be those of the next stream). Step 1 is `setAlt(0)` and the ordinary [reconfigure]; step 2 puts a bus reset and the claims
     * between them. The first call is [UsbDeviceOwner.RecoveryClaim.commit]ted, the others checked; once begun it runs to the end, the level restore included, unless the DAC is gone.
     */
    fun restart(claim: UsbDeviceOwner.RecoveryClaim): RestartOutcome {
        val rate = claim.facts.sampleRateHz
        val alt = claim.facts.altSetting
        if (rate <= 0 || alt <= 0) return RestartOutcome.FAILED
        val step = claim.step
        if (!claim.commit("setAlt0")) return RestartOutcome.SUPERSEDED
        if (!device.setAltSetting(0)) return RestartOutcome.FAILED
        if (step == 2) {
            if (!claim.mayTouchDevice("bus-reset")) return RestartOutcome.SUPERSEDED
            if (!device.resetAndReclaimForStreaming()) return RestartOutcome.FAILED
        }
        if (!claim.mayTouchDevice("reconfigure")) return RestartOutcome.SUPERSEDED
        val reconfigured = reconfigure(rate, alt, "dac-recovery step=$step rate=$rate alt=$alt")
        return if (reconfigured.failure == null) RestartOutcome.RAN else RestartOutcome.FAILED
    }

    companion object {
        /** The DAC's PLL lock time after the alt is set. */
        const val PLL_SETTLE_MS = 50L
    }
}
