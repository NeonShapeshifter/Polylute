package com.lostf1sh.pixelplayeross.dap.admin

import android.content.Intent

/** How a System-page request to open an Android surface ended. */
enum class SystemSurfaceOutcome {
    /** The surface is in front; the containment comes back when the app does. */
    OPENED,

    /** Lock Task could not be lifted: nothing was started and the containment was put back. */
    NOT_LIFTED,

    /** Lock Task was lifted but the surface would not start (or, for the HOME recovery, the policy could not be cleared): the containment was put back. */
    NOT_STARTED,
}

/** How "Volver al DAP" ended (B32-fix1 R3). Only [CONTAINED] says the device is the DAP again. */
enum class DapReturnOutcome {
    /** Device Owner: HOME, the dedicated flag and the allowlist are in place and Lock Task was observed LOCKED. */
    CONTAINED,

    /** No Device Owner (App Mode): there is nothing to contain. Any open window was closed; Lock Task was not requested. */
    NOTHING_TO_CONTAIN,

    /** Device Owner, but HOME, the flag or the allowlist could not be put back: nothing was locked. */
    POLICY_NOT_APPLIED,

    /** The policy is back but Lock Task was not observed: containment was retried through the usual recovery. */
    NOT_LOCKED,
}

/**
 * What the System page asks of the activity that owns Lock Task (B32-Q6). `MainActivity` is the one implementation; the page and its
 * tests see only this, so "the openers lift Lock Task" can be checked with a recording host instead of a device.
 */
interface SystemSurfaceHost {
    /**
     * Opens an Android surface (Settings, Wi-Fi, Bluetooth, USB preferences) with Lock Task lifted first and put back when the app is back
     * in front. [onResult] says how it ended; unless it is [SystemSurfaceOutcome.OPENED] the containment is back.
     */
    fun openSystemSurface(intent: Intent, onResult: (SystemSurfaceOutcome) -> Unit)

    /**
     * Clears the DAP's HOME policy (Lock Task lifted first) and, once it is cleared, calls [openSelector] to show Android's HOME selector.
     * [onResult] is [SystemSurfaceOutcome.NOT_STARTED] when the policy could not be cleared.
     *
     * B34-fix2 R1: the exit is recorded until the picker is over (the next activity start or resume settles it: another launcher chosen,
     * the exit stands; Polylute still home, it locks again). [openSelector] says whether the picker opened; a picker that did not is
     * settled at once, as a cancel.
     */
    fun restoreHonorLauncher(openSelector: () -> Boolean, onResult: (SystemSurfaceOutcome) -> Unit)

    /**
     * "Volver al DAP" (B32-fix1 R3): the listener's explicit request to be contained again, with Device Owner: HOME, the dedicated flag and
     * the allowlist put back, Lock Task requested **and verified**. It does not depend on what the product mode currently infers (after the
     * HONOR recovery it infers App Mode) and it is never run by the app coming back. [onResult] says what happened; only
     * [DapReturnOutcome.CONTAINED] means the device is the DAP again.
     */
    fun returnToDap(onResult: (DapReturnOutcome) -> Unit)
}
