package com.lostf1sh.pixelplayeross.data.volume

/**
 * FIX-5: asking for the temporary Safety Max override with the screen off.
 *
 * With the screen off or the device locked, the volume keys reach the media session's remote volume
 * (F-07). At the ceiling that path refuses and says so in a notification, but the override gesture
 * (three presses, then a confirmation) only exists in the unlocked app, so someone listening to the
 * DAP in a pocket who really wants a louder level had to take the device out and wake it first.
 *
 * What this adds is a way to *ask*, never a way to *get*: five refused Volume Up presses in a short
 * window turn the screen on and put the same confirmation in front of the user. Nothing here changes a
 * volume, grants an override or touches the ceiling: the five presses are refused like the first one,
 * and only the tap on the confirmation button grants it ([SafetyVolumeController.allowTemporaryOverride],
 * which is still the one place that does).
 *
 * Pure: no clock, no Android; the controller and the activity supply the facts.
 */
object ScreenOffOverridePolicy {
    /**
     * Five, against three with the screen on. With the screen on there is a readout that says what the
     * presses are doing and the person is looking at it; with the screen off nothing says anything, the
     * device may be in a pocket, and the consequence of a false positive is bigger (the screen comes
     * on, the battery pays, a dialog appears). Five deliberate presses is something a pocket does not
     * produce by accident.
     */
    const val REQUIRED_PRESSES = 5

    /**
     * Five presses in three seconds is 600 ms per press: quick, deliberate tapping. Long enough that
     * nobody has to count against the clock, short enough that presses brushed at different moments
     * of a walk never add up.
     */
    const val WINDOW_MS = 3_000L

    /**
     * Events closer than this are one press. A held key repeats every ~50 ms after its initial delay;
     * the remote path cannot see `repeatCount`, so a held key would otherwise be five presses in a
     * quarter of a second. A fast, deliberate tap is at least ~150 ms from the next (a release and a
     * press in between), so 120 ms separates the two.
     */
    const val MIN_PRESS_GAP_MS = 120L

    /** How long the confirmation waits for an answer before it closes and the screen is left to go off. */
    const val CONFIRM_TIMEOUT_MS = 10_000L

    /**
     * A normal phone: how long after the fifth press an unlock still shows the confirmation. Thirty
     * seconds: unlocking takes a moment (waking the phone, the fingerprint or the PIN, opening the app),
     * and the 10 s it had at first expired on the device before anyone could finish unlocking.
     */
    const val UNLOCK_GRACE_MS = 30_000L

    /**
     * The dedicated appliance: from the moment the screen is woken until the app's activity is up and
     * can draw the confirmation. That is a resume, a fraction of a second; ten seconds is a margin for a
     * slow one, and it is also how long the wake is held if the activity never comes.
     */
    const val WAKE_GRACE_MS = 10_000L

    fun newGesture(): SafetyOverrideGesture = SafetyOverrideGesture(REQUIRED_PRESSES, WINDOW_MS, MIN_PRESS_GAP_MS)

    /**
     * Whether one refused raise on the remote path is a press of this gesture.
     *
     * The caller only asks for raises the ceiling refused outright (level already at the ceiling, no
     * override active; see [SafetyVolumePolicy.raiseRefusedByCeiling]), so "only counts presses refused
     * at the ceiling" is already true by the time this is asked. What remains: the screen has to be
     * off or locked (with it on, the in-app gesture is the one), and the request has to be a single
     * step up, which is what a key press is (`increaseDeviceVolume` asks for current + 1). The system
     * panel's slider asks for whatever level the thumb is at, and dragging it past the ceiling is not
     * five key presses.
     */
    fun countsAsPress(singleStepRaise: Boolean, screenOffOrLocked: Boolean): Boolean =
        singleStepRaise && screenOffOrLocked

    /** Where the confirmation is shown, and whether at all. */
    enum class Delivery {
        /** Nothing to ask: Safety Max is not protecting this output, or the override is already on. */
        NONE,

        /** The appliance, with its activity already resumed (screen on, app in front): show it now. */
        OVER_LOCK_SCREEN,

        /**
         * The appliance (Device Owner + HOME + Lock Task), with its activity **not** resumed, which is
         * the normal state with the screen off: on the device the activity is stopped when the screen
         * goes off even under Lock Task (2026-10-07, `mLockTaskModeState=LOCKED`), so waiting for it
         * to be resumed never happens. The screen is woken, the request is left pending, and the
         * activity (the Lock Task one, which comes to the front when the screen turns on) shows the
         * dialog as soon as it resumes. Nothing is started from the background.
         */
        WAKE_THEN_SHOW,

        /**
         * A normal phone: the notification (F-07) is the message, the screen is not touched, and the
         * confirmation is shown if the app comes to the front within [UNLOCK_GRACE_MS]. No full-screen
         * intent: that is for calls and alarms.
         */
        ON_UNLOCK,
    }

    /**
     * @param dedicated a fact about the **device** (Device Owner, HOME, Lock Task allowed: the product
     *        mode), read when the fifth press arrives, never inferred from whether an activity of the
     *        app happens to be resumed: with the screen off it is not, and that is the case this exists for.
     * @param hostResumed the confirmation's activity is resumed right now.
     */
    fun delivery(
        protectionActive: Boolean,
        overrideActive: Boolean,
        dedicated: Boolean,
        hostResumed: Boolean,
    ): Delivery = when {
        !protectionActive || overrideActive -> Delivery.NONE
        dedicated && hostResumed -> Delivery.OVER_LOCK_SCREEN
        dedicated -> Delivery.WAKE_THEN_SHOW
        else -> Delivery.ON_UNLOCK
    }

    /** How long a pending request waits for the app to come up, counted from [pendingSinceMs]. */
    fun graceMs(delivery: Delivery): Long = when (delivery) {
        Delivery.WAKE_THEN_SHOW -> WAKE_GRACE_MS
        Delivery.ON_UNLOCK -> UNLOCK_GRACE_MS
        Delivery.NONE, Delivery.OVER_LOCK_SCREEN -> 0L
    }

    /**
     * Whether a request that was left pending is still worth showing now that the app is in front:
     * soon enough ([graceMs], counted from [pendingSinceMs]: the moment the screen was woken for
     * [Delivery.WAKE_THEN_SHOW], the fifth press for [Delivery.ON_UNLOCK]), and the situation that
     * justified it still holds (Safety Max is still protecting, no override has been given since, the
     * level is still at the ceiling).
     */
    fun showWhenAppComesUp(
        delivery: Delivery,
        pendingSinceMs: Long,
        nowMs: Long,
        protectionActive: Boolean,
        overrideActive: Boolean,
        level: Int,
        ceilingRaw: Int?,
    ): Boolean {
        val grace = graceMs(delivery)
        return grace > 0L &&
            pendingSinceMs > 0L &&
            nowMs - pendingSinceMs in 0L..grace &&
            protectionActive &&
            !overrideActive &&
            ceilingRaw != null &&
            level >= ceilingRaw
    }
}
