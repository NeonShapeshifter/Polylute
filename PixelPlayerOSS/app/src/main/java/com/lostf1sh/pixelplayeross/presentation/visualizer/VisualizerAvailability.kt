package com.lostf1sh.pixelplayeross.presentation.visualizer

/**
 * What the visualizer can truthfully show right now.
 *
 * Every value here is a *statement about the audio path*, not about the UI. A visualizer that
 * cannot observe the audio must say so rather than draw something plausible: fabricated bars that
 * move with nothing behind them are worse than no bars, because they assert something untrue about
 * what the device is playing.
 *
 * [Unavailable] states are deliberately distinct. "There is no Android audio session because the
 * output is USB Direct" and "there is a session but we are not allowed to observe it" are
 * different problems with different answers, and collapsing them into one "unavailable" would hide
 * which one the user actually has.
 */
sealed interface VisualizerAvailability {

    /** Audio is observable. [sessionId] is the Android audio session being watched. */
    data class Available(val sessionId: Int) : VisualizerAvailability

    /**
     * The output bypasses Android audio entirely — USB Direct.
     *
     * There is no session to observe and there never will be, because observing Direct would mean
     * routing it through Android, which is precisely what Direct exists to avoid.
     */
    data object UnavailableDirect : VisualizerAvailability

    /**
     * A session exists but cannot be observed — the platform refused the observation.
     *
     * On modern Android the platform `Visualizer` effect requires the record-audio permission. The
     * effect throws when it is absent, and this is where that lands.
     */
    data object UnavailablePermission : VisualizerAvailability

    /**
     * No session yet: nothing is playing, or the player has not produced a session.
     *
     * Distinct from the two above because it is temporary and resolves by itself.
     */
    data object UnavailableSession : VisualizerAvailability

    /** Playback is paused or stopped. Observation is idle, not broken. */
    data object Stopped : VisualizerAvailability

    /** Whether the visualizer can draw anything at all right now. */
    val isDrawable: Boolean get() = this is Available

    /**
     * Whether to offer the visualizer as a choice, as opposed to explaining why it is not on offer.
     * A stopped visualizer is still supported; it simply has nothing to show at this moment.
     */
    val isSupportedOnThisRoute: Boolean
        get() = this is Available || this is Stopped || this is UnavailableSession

    /** A short, truthful explanation for the one case the user can act on. */
    fun explain(): String? = when (this) {
        is UnavailableDirect -> "Visualizer unavailable on this output path."
        is UnavailablePermission -> "Visualizer unavailable: audio observation was refused."
        else -> null
    }
}
