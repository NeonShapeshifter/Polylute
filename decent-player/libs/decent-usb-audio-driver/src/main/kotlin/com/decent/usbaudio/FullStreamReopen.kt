package com.decent.usbaudio

class FullStreamReopen(private val ops: Ops) {
    interface Ops {
        fun closeStream(): Boolean
        fun closeDevice()
        fun openDevice(): Boolean
        fun configure(rate: Int, alt: Int): Boolean
        fun openStream(): Boolean
    }
    fun run(claim: UsbDeviceOwner.RecoveryClaim): RestartOutcome {
        if (!claim.commit("full-close-stream")) return RestartOutcome.SUPERSEDED
        if (!ops.closeStream()) return RestartOutcome.FAILED
        if (!claim.isCurrent() || !claim.mayTouchDevice("close-device")) return RestartOutcome.SUPERSEDED
        ops.closeDevice()
        if (!claim.isCurrent() || !claim.mayTouchDevice("open-device")) return RestartOutcome.SUPERSEDED
        if (!ops.openDevice()) return RestartOutcome.FAILED
        if (!claim.isCurrent() || !claim.mayTouchDevice("reopen-configure")) return RestartOutcome.SUPERSEDED
        if (!ops.configure(claim.facts.sampleRateHz, claim.facts.altSetting)) return RestartOutcome.FAILED
        if (!claim.isCurrent() || !claim.mayTouchDevice("open-stream")) return RestartOutcome.SUPERSEDED
        if (!ops.openStream()) return RestartOutcome.FAILED
        return if (claim.isCurrent()) RestartOutcome.RAN else RestartOutcome.SUPERSEDED
    }
}
