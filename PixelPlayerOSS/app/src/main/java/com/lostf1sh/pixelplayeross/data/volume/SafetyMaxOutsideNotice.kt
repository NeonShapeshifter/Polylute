package com.lostf1sh.pixelplayeross.data.volume

/**
 * B69 (owner, 2026-11-23, app mode on the S22): Volume Up past Safety Max with another app in front made the system slider rise and
 * snap back with nothing visible, and the override question only appeared once Polylute was opened. The F-07 notification is silent and
 * IMPORTANCE_LOW: it was made for the lock screen, and with the screen on it never comes into view.
 *
 * What this decides, per **ceiling episode** (refused raises at the ceiling, until the level goes down, the output or the override
 * changes, or [EPISODE_IDLE_MS] pass without a refused raise):
 * - the short visible notice, once, on the first refused raise made with the screen on and another app in front;
 * - the notification's "Raise the limit" action, once the episode reached the ask that exists today (three refused presses with the screen
 *   on, five with it off or locked), outside the app and never on the Dedicated appliance, where the app is always in front.
 *
 * It decides what is *said*, never a volume: the clamp is [SafetyVolumePolicy.requestedRaw], and only the tap on the confirmation grants
 * an override (the confirmation's button, through the controller). Pure: the controller gives the clock and the facts.
 */
class SafetyMaxOutsideNotice(private val episodeIdleMs: Long = EPISODE_IDLE_MS) {

    /** Where the refused raise was made. */
    enum class Where {
        /** Polylute's activity is resumed: its own readout and dialog speak. */
        APP_IN_FRONT,

        /** Screen on and unlocked, another app in front: nothing of Polylute's is on screen. */
        OTHER_APP,

        /** Screen off or locked: the F-07 notification is the message (unchanged). */
        LOCKED,
    }

    /** How the action is offered: as a heads-up over the app in front, or in the lock-screen notification, behind an unlock. */
    enum class OfferStyle { HEADS_UP, LOCK_SCREEN }

    private var lastRefusalAtMs = NONE
    private var noticeShown = false
    private var offer: OfferStyle? = null

    /**
     * Whether this refused raise shows the visible notice: the first one of the episode made with another app in front. Never on the
     * Dedicated appliance ([dedicated]), where Polylute is the only app.
     */
    @Synchronized
    fun onRefusedRaise(nowMs: Long, where: Where, dedicated: Boolean = false): Boolean {
        roll(nowMs)
        if (where != Where.OTHER_APP || dedicated || noticeShown) return false
        noticeShown = true
        return true
    }

    /**
     * The episode reached the ask (the press count that asks for the override today). Returns the style of the action when it is offered
     * now, the first time in the episode, or null: Polylute is in front (its own dialog asks), the device is the Dedicated appliance (it
     * wakes the screen and shows the app's own dialog, FIX-5), or the action was already offered in this episode.
     */
    @Synchronized
    fun onOverrideAsked(nowMs: Long, where: Where, dedicated: Boolean): OfferStyle? {
        roll(nowMs)
        if (where == Where.APP_IN_FRONT || dedicated || offer != null) return null
        val style = if (where == Where.LOCKED) OfferStyle.LOCK_SCREEN else OfferStyle.HEADS_UP
        offer = style
        return style
    }

    /** The action offered in this episode, which every later notice of the same episode keeps, or null. */
    @Synchronized
    fun activeOffer(nowMs: Long): OfferStyle? {
        roll(nowMs, touch = false)
        return offer
    }

    /** The level went down, the override was granted or declined, the output or Safety Max changed: the next refusal starts afresh. */
    @Synchronized
    fun endEpisode() {
        lastRefusalAtMs = NONE
        noticeShown = false
        offer = null
    }

    private fun roll(nowMs: Long, touch: Boolean = true) {
        if (lastRefusalAtMs != NONE && nowMs - lastRefusalAtMs > episodeIdleMs) endEpisode()
        if (touch) lastRefusalAtMs = nowMs
    }

    companion object {
        /**
         * A minute without a refused raise ends the episode. Long enough that a few presses, a pause to listen and a few more are one
         * episode (one notice); short enough that coming back to the ceiling later in the evening is told again.
         */
        const val EPISODE_IDLE_MS = 60_000L
        private const val NONE = Long.MIN_VALUE
    }
}
