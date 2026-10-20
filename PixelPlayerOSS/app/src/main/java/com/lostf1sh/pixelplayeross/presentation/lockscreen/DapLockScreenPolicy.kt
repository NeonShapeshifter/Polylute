package com.lostf1sh.pixelplayeross.presentation.lockscreen

/**
 * When the DAP lock surface is shown.
 *
 * Kept as a pure decision so it can be reasoned about and tested without a screen, a device or a
 * composition, and so there is exactly one place that answers the question.
 *
 * **This is not a security boundary.** The surface is an app-owned playback screen for a dedicated
 * player. It never claims to protect anything, and it never shows over a locked device — the
 * Keyguard check below is not a lock to be defeated, it is a rule that Android's own lock screen
 * stays in charge whenever it is present.
 */
object DapLockScreenPolicy {

    /**
     * Whether the appliance may take the wake surface at all, given the Maintenance feature kill.
     *
     * The kill is applied here rather than at each call site so it cannot be honoured by one caller
     * and forgotten by another — an override that is only half-respected is worse than none,
     * because the diagnostic then reports a behaviour the device is not actually running.
     *
     * When killed, the answer is the same as for a disabled setting: the platform keeps its lock
     * screen, exactly as it would if the feature had never been turned on.
     */
    fun effectiveEnabled(
        enabled: Boolean,
        lockScreenIntegrationKilled: Boolean,
    ): Boolean = enabled && !lockScreenIntegrationKilled

    /**
     * Whether waking should raise the surface.
     *
     * Every condition has to hold, and each one is a deliberate refusal rather than a convenience:
     *
     *  - the user asked for it ([enabled]);
     *  - Polylute is the device's home app ([dedicated], named for the appliance this was first built for; B34: true at every level
     *    with Polylute as home, Launcher, Pinned and Dedicated, and false in App Mode) — on a phone whose home is another launcher this
     *    would be a second home screen nobody asked for;
     *  - Android's Keyguard is **not** showing ([keyguardLocked]) — if the platform owns the
     *    screen, the platform owns the screen;
     *  - the screen really was off and has just come back ([wokeFromScreenOff]) — otherwise any
     *    ordinary return to the app would raise it;
     *  - there is something to control ([hasSong]) — an empty playback surface is not a lock screen,
     *    it is a blank page standing between the user and their music.
     */
    fun shouldShow(
        enabled: Boolean,
        dedicated: Boolean,
        keyguardLocked: Boolean,
        wokeFromScreenOff: Boolean,
        hasSong: Boolean,
    ): Boolean =
        enabled && dedicated && !keyguardLocked && wokeFromScreenOff && hasSong

    /**
     * Whether a screen-off should arm the surface for the next wake.
     *
     * Separate from [shouldShow] because these are two moments: arming happens while the screen is
     * going away, and the Keyguard answer is only meaningful at the moment of waking. Deciding both
     * at wake time is what makes a lock that appeared mid-session get respected.
     */
    fun shouldArm(enabled: Boolean, dedicated: Boolean): Boolean = enabled && dedicated
}
