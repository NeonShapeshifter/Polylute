package com.lostf1sh.pixelplayeross.presentation.viewmodel

/**
 * F-05: what the app may do about the system's screen timeout, as rules.
 *
 * The HONOR was found with `screen_off_timeout = 2147483647` ("never"), so the screen only ever
 * went off when the power button was pressed: no lock screen on its own, and no Doze, which needs
 * the screen off. That value did **not** come from this app. No path here writes it:
 * [isAppWritable] is the only gate in front of the write and admits the five presets and nothing
 * else, and the value is already in the logcat of 2026-08-30, before the app could write the setting
 * at all (the phase 7.11 report records the write being refused for lack of `WRITE_SETTINGS`, and
 * the timeout "remained 2147483647"). It is a device setting the app inherits.
 *
 * What the app owes in return:
 *
 * - it never leaves the system setting at "never" by its own doing, and never writes anything
 *   outside the presets ([isAppWritable]);
 * - "keep the screen on while playing" is a window flag held only while the player is in front and
 *   playing, so it cannot turn into a timeout of "never" (see [DapDeviceControlPolicy]);
 * - a system value that is "never" is *reported*, with a one-tap correction, and never changed
 *   without the user asking ([offer]).
 */
object ScreenTimeoutPolicy {
    /**
     * The DAP default and what the offer proposes: one minute. Long enough not to blank the screen
     * under a thumb that is still choosing something, short enough that the lock surface and Doze
     * arrive soon after the device is put down. (Thirty seconds is the shortest preset; it blanks
     * the screen mid-scroll through a large library.)
     */
    const val DAP_DEFAULT_MS = 60_000L

    /** The finite values the app offers, and the only ones it will ever write. */
    val PRESETS_MS: List<Long> = listOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L)

    /**
     * From here up the timeout is "never" in every way that matters. Android's own list stops at
     * 30 minutes, and what a device does above that (an hour, `Int.MAX_VALUE`, a vendor's "never")
     * is the same thing: the screen does not turn itself off.
     */
    const val NEVER_FROM_MS = 3_600_000L

    /** Whether a value read from `Settings.System.SCREEN_OFF_TIMEOUT` means the screen stays on. */
    fun isNever(systemTimeoutMs: Long): Boolean = systemTimeoutMs <= 0L || systemTimeoutMs >= NEVER_FROM_MS

    /** Whether the app may write [timeoutMs] to the system setting: a preset, never "never". */
    fun isAppWritable(timeoutMs: Long): Boolean = timeoutMs in PRESETS_MS

    /**
     * What to show about the system timeout.
     *
     * - [Offer.None] when the timeout is finite, or the user has no reason to be told.
     * - [Offer.CorrectNever] when it is "never": the screen will not turn itself off, whether or not
     *   the app is playing. The action proposes [DAP_DEFAULT_MS] and is only ever a button.
     *
     * @param dapMode Device Owner / dedicated device. The prominent notice (Settings root) is for
     *        the appliance; on a phone the same facts are shown only where the setting lives.
     */
    fun offer(systemTimeoutMs: Long, dapMode: Boolean): Offer = when {
        !isNever(systemTimeoutMs) -> Offer.None
        dapMode -> Offer.CorrectNever(prominent = true)
        else -> Offer.CorrectNever(prominent = false)
    }

    sealed interface Offer {
        data object None : Offer
        data class CorrectNever(val prominent: Boolean) : Offer
    }

    /**
     * What to do about the system value no longer being the one the user chose (B11-fix, FIX-2).
     *
     * On the device the app showed "30 s" (its stored choice) while the system was at 2147483647:
     * something overwrote the value after the app wrote it, and the app never noticed. The rule:
     *
     * - only a finite preset the user chose is the app's to keep; "follow the system" and "never
     *   while playing" own no system value, so there is nothing to drift from;
     * - **on the appliance** (Device Owner + HOME, where the app *is* the device's controller) a drift
     *   is put back, if Android lets the app write it;
     * - **on a phone** the system wins: the app never fights it, it only shows what is real.
     */
    enum class DriftAction { NONE, REAPPLY, SHOW_ONLY }

    data class Drift(val action: DriftAction, val storedMs: Long, val systemMs: Long) {
        val drifted: Boolean get() = action != DriftAction.NONE
    }

    fun drift(storedMode: Long, systemTimeoutMs: Long, dapMode: Boolean, canWrite: Boolean): Drift {
        val none = Drift(DriftAction.NONE, storedMode, systemTimeoutMs)
        if (!isAppWritable(storedMode)) return none
        if (systemTimeoutMs == storedMode) return none
        return Drift(
            action = if (dapMode && canWrite) DriftAction.REAPPLY else DriftAction.SHOW_ONLY,
            storedMs = storedMode,
            systemMs = systemTimeoutMs,
        )
    }
}
