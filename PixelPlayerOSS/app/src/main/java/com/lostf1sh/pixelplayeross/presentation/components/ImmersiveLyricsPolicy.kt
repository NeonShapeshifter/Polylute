package com.lostf1sh.pixelplayeross.presentation.components

/**
 * B40-F5: when the lyrics page folds its controls away and enlarges the text ("immersive"), and what a touch means while it is folded.
 *
 * The 1.0 mode started a countdown from a handful of taps and drags and folded the page under the listener's finger: scrolling the lyrics
 * did not count as being there, a paused song or an open options sheet folded it anyway, and the tap that was meant to bring the controls
 * back landed on a lyric line and seeked the song to it. These are the rules that replace that; the page only asks.
 *
 * Pure: the page passes what it sees, the answer is a decision.
 */
internal object ImmersiveLyricsPolicy {
    /** While a finger rests on the page the countdown is re-checked this often: a finger that has not moved is still an interaction. */
    const val TOUCH_RECHECK_MS = 250L

    /** A finger that keeps moving restarts the countdown at most this often: restarting it on every move event would only cost frames. */
    const val MOVE_RESTART_INTERVAL_MS = 250L

    /**
     * Whether the countdown may run at all. It does not while the feature is off, while there are no synced lines to follow (plain lyrics
     * and "no lyrics" have nothing that moves), while the song is paused (the listener is reading or about to press play: the controls
     * stay), after "not this time", or while the options sheet or a dialog from this page is open (the page behind it is not being left
     * alone, it is being used).
     */
    fun mayEngage(
        featureOn: Boolean,
        syncedLinesShown: Boolean,
        playing: Boolean,
        switchedOffOnce: Boolean,
        coveredByAnotherSurface: Boolean,
    ): Boolean = featureOn && syncedLinesShown && playing && !switchedOffOnce && !coveredByAnotherSurface
}

/**
 * What the pointer on the lyrics page tells the lyric lines. Not Compose state: it is read when a line is tapped, and writing it must
 * never recompose the page.
 *
 * A touch that **begins while the page is immersive** only brings the controls back: its tap does not seek. The controls are hidden, so a
 * listener who taps to show them cannot know which line is under the finger; seeking there is a surprise that moves the song. The next
 * tap, with the controls shown, seeks as it always did.
 */
internal class ImmersiveTouchGate {
    private var beganImmersive = false

    /** Whether a finger is down on the page right now. */
    @Volatile
    var isTouching: Boolean = false
        private set

    /** A new touch went down; [immersive] is whether the page was folded at that moment (before the touch unfolded it). */
    fun touchBegan(immersive: Boolean) {
        beganImmersive = immersive
        isTouching = true
    }

    fun touchEnded() {
        isTouching = false
    }

    /**
     * Whether the tap on a lyric line that just arrived should seek. Answers once per touch: it forgets the touch, so a click that comes
     * with no touch of its own (an accessibility service's click) is a plain tap.
     */
    fun lineTapSeeks(): Boolean {
        val seeks = !beganImmersive
        beganImmersive = false
        return seeks
    }
}
