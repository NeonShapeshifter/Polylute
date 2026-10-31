package com.decent.usbaudio

/**
 * B47-A5: what the native lock-up detector and its escalation say to the writer thread, one look.
 *
 * The native layer detects (the Melody's feedback railed beyond +-4.5 % for 1 500 ms, usb-glitch.h) and decides which step is next (one attempt per step per
 * episode, a 30 s cooldown between episodes); the platform work of a step is Kotlin's, because it goes through [UsbAudioDevice] and its level restore. [step]
 * is what to do now: [STEP_NONE] almost always.
 */
data class DacLockState(
    /** The detector says the DAC is locked up right now. */
    val locked: Boolean,
    /** Lock-ups detected in this process. */
    val locks: Long,
    /** How long the open rail has lasted (0 outside one). */
    val railedMs: Long,
    /** The rail's offset from nominal, parts per million. */
    val railPpm: Long,
    /** Completions that took longer than 20 ms during it. */
    val lateReaps: Long,
    /** [STEP_NONE], [STEP_INTERFACE], [STEP_BUS_RESET], or [STEP_GAVE_UP] (already logged: nothing to do). */
    val step: Int,
) {
    companion object {
        const val STEP_NONE = 0

        /** Step 1: restart the streaming interface (SETINTERFACE alt 0, then the format's alt) and carry on at the same position. */
        const val STEP_INTERFACE = 1

        /** Step 2: a bus reset and the claims again, the DAC's level restored by the rule of every open. */
        const val STEP_BUS_RESET = 2
        const val STEP_GAVE_UP = 3

        /** The native state array: `[locked, locks, railedMs, ppm, lateReaps, step]`. */
        fun of(raw: LongArray?): DacLockState {
            if (raw == null || raw.size < 6) return NONE
            return DacLockState(raw[0] != 0L, raw[1], raw[2], raw[3], raw[4], raw[5].toInt())
        }

        val NONE = DacLockState(locked = false, locks = 0, railedMs = 0, railPpm = 0, lateReaps = 0, step = STEP_NONE)
    }
}
