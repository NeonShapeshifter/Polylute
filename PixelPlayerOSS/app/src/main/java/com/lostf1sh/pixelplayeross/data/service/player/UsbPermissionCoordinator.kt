package com.lostf1sh.pixelplayeross.data.service.player

/**
 * The single owner of USB permission requests (AUD-031), keyed by device path.
 *
 * - One pending request per device; a second path asking for the same device is refused.
 * - A result counts only for the device it names.
 * - A denial is remembered for the attachment, so a dismissed dialog is not re-raised on every
 *   diagnostic refresh; a detach forgets it.
 * - A request with no answer after [timeoutMs] expires and may be asked again: under Lock Task the
 *   dialog can fail to show, and an unanswered request used to block the device until a replug.
 * - A request abandoned before it was shown (no foreground activity, suspension refused) is
 *   forgotten outright (AUD-030, AUD-101).
 *
 * Not thread-safe: driven from the engine's main thread.
 */
class UsbPermissionCoordinator(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    private val pendingSince = mutableMapOf<String, Long>()
    private val denied = mutableSetOf<String>()

    /** True when the caller should ask for [device] now. */
    fun tryBegin(device: String, nowMs: Long): Boolean {
        if (device in denied || device in pendingSince) return false
        pendingSince[device] = nowMs
        return true
    }

    /** Records an answer. False when it is for a device with no request pending. */
    fun onResult(device: String, granted: Boolean): Boolean {
        if (pendingSince.remove(device) == null) return false
        if (!granted) denied += device
        return true
    }

    /** Drops requests unanswered for [timeoutMs]; returns the devices dropped. */
    fun expire(nowMs: Long): List<String> {
        val expired = pendingSince.filterValues { nowMs - it >= timeoutMs }.keys.toList()
        expired.forEach(pendingSince::remove)
        return expired
    }

    /** The request was never shown; it does not count as asked. */
    fun abandon(device: String) {
        pendingSince.remove(device)
    }

    fun onAllDetached() {
        pendingSince.clear()
        denied.clear()
    }

    companion object {
        /** Generous: under Lock Task the listener may take a while with the system dialog. */
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /**
         * Whether the activity may raise the dialog now. Only when resumed: a destroyed activity
         * cannot, and suspending Lock Task from the background or with the screen off is AUD-101.
         */
        fun requesterAdmits(activityResumed: Boolean): Boolean = activityResumed
    }
}
