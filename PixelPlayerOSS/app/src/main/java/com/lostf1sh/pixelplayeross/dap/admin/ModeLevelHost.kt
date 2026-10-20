package com.lostf1sh.pixelplayeross.dap.admin

/**
 * What the "Inicio y modo DAP" page asks of the activity that owns Lock Task (B34). `MainActivity` is the one implementation; the page and
 * its tests see only this, so "Pinned asks Android to pin" and "release the owner" can be checked with a recording host instead of a device.
 * (The Dedicated level's other actions, lifting Lock Task for the HOME picker and "Volver al DAP", are [SystemSurfaceHost]'s.)
 */
interface ModeLevelHost {
    /**
     * Asks Android to pin Polylute (`startLockTask()` without an allowlist: Android shows its own confirmation, and the pin exists only
     * if the listener confirms). True when the request was made; whether it took is what Android reads afterwards.
     */
    fun requestAppPinning(): Boolean

    /** Releases an app pin Polylute holds. True when nothing is pinned afterwards. A Device Owner's Lock Task is not touched. */
    fun releaseAppPinning(): Boolean

    /**
     * "Dejar de ser propietario del dispositivo" ([DapReleaseOwnerCoordinator]). [onResult] says what is true afterwards: released only on a
     * confirmed absence, "could not verify" when the ownership query kept failing, and for a failure whether the containment is confirmed
     * back (B34-fix1). It is not called if the activity is destroyed first: the operation then settles itself (window closed, policies put
     * back or the departure finished, by what the ownership query says) and the next activity takes the Lock Task. A second call while one
     * is running is ignored.
     */
    fun releaseDeviceOwner(onResult: (DapReleaseResult) -> Unit)
}
