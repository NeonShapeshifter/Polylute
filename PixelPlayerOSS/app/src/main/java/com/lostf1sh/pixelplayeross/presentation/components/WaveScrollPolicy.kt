package com.lostf1sh.pixelplayeross.presentation.components

import kotlin.math.roundToLong

/**
 * AUD-146: when the wavy seek bar's wave may scroll.
 *
 * Material's `LinearWavyProgressIndicator` runs an endless linear tween on the wave offset for as
 * long as its `waveSpeed` is above zero, **whatever the amplitude**. The offset is read in the draw
 * phase, so while that tween runs the window is drawn at display refresh rate: measured on the
 * HONOR, 719 frames in 12 s with the external player in front and music playing, which is
 * 50–85 % of a core spent on a squiggle. The full player made it worse in a quiet way: its seek bar
 * is drawn flat (amplitude 0, "the seek bar does not wave"), so it paid the same 60 fps for a wave
 * nobody could see.
 *
 * The rule here keeps the look and removes the idle cost:
 *
 * - the wave never scrolls unless it can be seen (visible, with an amplitude above zero);
 * - when it can be seen it scrolls for [ACTIVE_WINDOW_MS] after whatever asked for it (the bar
 *   appearing, playback starting, a touch on the bar, a new track), and then rests;
 * - it rests **on a cycle boundary**. Material restarts the offset from 0 whenever the speed
 *   changes, so freezing in mid-cycle would snap the whole squiggle to a different phase. One cycle
 *   is exactly one wavelength, so a freeze on the boundary looks identical to the frame before it.
 *
 * Pure: no Android, no Compose. Times are the Compose frame clock's, in nanoseconds, because that is
 * the clock Material's animation is measured on (it keeps counting while the frame clock is paused
 * by the lifecycle, which the caller handles by starting a fresh window on resume).
 */
internal object WaveScrollPolicy {
    /**
     * How long the wave keeps scrolling after it was last asked to. Four cycles of the default
     * speed (2 s each): long enough to read as alive when the bar appears or is touched, short
     * enough that a screen left on the player is not redrawn for the whole of a track.
     */
    const val ACTIVE_WINDOW_MS = 8_000L

    /** Material coerces its cycle to at least this; mirrored so the boundary maths matches. */
    const val MIN_CYCLE_MS = 50L

    private const val NANOS_PER_MS = 1_000_000L

    /**
     * Material's cycle: `wavelength / waveSpeed * 1000` ms, never below [MIN_CYCLE_MS]. Both are
     * density-independent pixels, so their ratio is a time in seconds. A non-positive speed has no
     * cycle (the wave does not move), reported as 0.
     */
    fun cycleMillis(wavelengthDp: Float, waveSpeedDp: Float): Long {
        if (wavelengthDp <= 0f || waveSpeedDp <= 0f) return 0L
        return (wavelengthDp / waveSpeedDp * 1000f).roundToLong().coerceAtLeast(MIN_CYCLE_MS)
    }

    /**
     * Whether the wave has any business scrolling: it is on screen, and it is not flat. A flat wave
     * scrolls invisibly, which is exactly the cost this removes.
     */
    fun mayScroll(visible: Boolean, amplitudeShown: Boolean): Boolean = visible && amplitudeShown

    /** The window a re-arm opens, in frame-clock nanoseconds. */
    fun windowNanos(windowMs: Long = ACTIVE_WINDOW_MS): Long = windowMs * NANOS_PER_MS

    /** Which wave cycle [frameNanos] falls in, counted from [originNanos]. */
    fun cycleIndex(frameNanos: Long, originNanos: Long, cycleNanos: Long): Long =
        Math.floorDiv(frameNanos - originNanos, cycleNanos)

    /**
     * Whether the scroll should stop on this frame: the window has run out **and** this is the
     * first frame of a new cycle, so the offset Material resets to 0 is the offset it was already
     * at. Before the window has run out it never stops; after it, it waits for the boundary.
     *
     * A cycle of 0 (no scroll) is always "stop": there is nothing to wait for.
     */
    fun shouldFreeze(
        frameNanos: Long,
        previousFrameNanos: Long,
        originNanos: Long,
        cycleNanos: Long,
        windowStartNanos: Long,
        windowNanos: Long,
    ): Boolean {
        if (cycleNanos <= 0L) return true
        if (frameNanos - windowStartNanos < windowNanos) return false
        return cycleIndex(frameNanos, originNanos, cycleNanos) !=
            cycleIndex(previousFrameNanos, originNanos, cycleNanos)
    }
}
