package com.lostf1sh.pixelplayeross.presentation.viewmodel

/**
 * AUD-146: how often playback position is allowed to reach the UI, in one place.
 *
 * Every number here is a ceiling on how often a surface can be asked to redraw *because of the
 * position*. The player's clock is not what these are about (that runs at the audio rate); they are
 * the pace at which what is on screen is refreshed:
 *
 * - **1 Hz** wherever the position is read as a number of seconds (a time label, the mini player,
 *   the lock surface, anything while the screen is not being looked at). A label that shows whole
 *   seconds cannot use a faster value, and a thin bar that moves a pixel or two a second does not
 *   need one.
 * - **4 Hz** only for the bar of the expanded full player, which is the one place a bar is being
 *   watched. A 2 dp track on a ~330 dp bar moves 0.55–2 dp per second for 3–10 minute tracks, so a
 *   tick every 250 ms advances it by at most ~0.5 dp (about one device pixel): the steps cannot be
 *   told apart from continuous motion, and it is a sixth of the work of drawing at refresh rate.
 *
 * Nothing here ticks while nobody is looking: the progress loop in `PlaybackStateHolder` only runs
 * while something collects the position, and a stopped activity stops collecting.
 */
object PlaybackProgressRates {
    /** The expanded full player's bar is on screen: 4 Hz. */
    const val SLIDER_TICK_MS = 250L

    /** Anything that shows the position as whole seconds (mini player, labels): 1 Hz. */
    const val SECONDS_TICK_MS = 1_000L

    /** The screen is off or the app is in the background but something still collects: 1 Hz. */
    const val BACKGROUND_TICK_MS = SECONDS_TICK_MS

    /** The DAP lock surface's elapsed time and line: whole seconds, so 1 Hz. */
    const val LOCK_SURFACE_TICK_MS = SECONDS_TICK_MS

    /**
     * How often the full player samples the position it reads while its bar is expanded. Equal to
     * [SLIDER_TICK_MS] on purpose: sampling faster than the source changes only repeats a value.
     */
    const val EXPANDED_BAR_SAMPLE_MS = SLIDER_TICK_MS

    /** The full player's bar is mid-gesture or half open: the position is not being read closely. */
    const val COLLAPSED_BAR_SAMPLE_MS = 500L

    /** The tick of the shared progress loop for the current screen state. */
    fun tickMs(screenInteractive: Boolean, sliderMounted: Boolean): Long = when {
        !screenInteractive -> BACKGROUND_TICK_MS
        sliderMounted -> SLIDER_TICK_MS
        else -> SECONDS_TICK_MS
    }

    /** Position updates per second at a tick, for the documentation of a ceiling. */
    fun hertz(tickMs: Long): Double = if (tickMs <= 0L) Double.POSITIVE_INFINITY else 1000.0 / tickMs
}
