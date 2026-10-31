package com.decent.usbaudio.media3

import androidx.media3.common.C
import com.decent.usbaudio.UsbTransportPlan

/**
 * B12a-4: silence ahead of the first frame after a **sample-rate** change, so a DAC that mutes while it relocks
 * its clock does not swallow the start of the song (UAPP-style pre-roll).
 *
 * The owner's report (2026-10-07): "sometimes some songs eat their first second; if I go back to the start, the
 * song is complete". The reviewer's hypothesis, not yet confirmed by ear: a rate change rebuilds the USB stream
 * and the DAC mutes its output until its clock has locked; the pipeline delivers every byte at a rate change
 * (B8b: exact hash), so what is lost is not ours; replaying the song does not rebuild the stream, so it is
 * complete. Hence the experiment: [ms] of zeros queued in front of the item's first buffer, only at a rate
 * change, **default 0** until the owner says by ear which value fixes it.
 *
 * What it is and is not:
 * - **Extra frames at the start.** The item's own frames are never replaced, shortened or shifted: the silence
 *   is queued as ordinary 16-bit zero buffers (exact zeros at every DAC depth) ahead of the first real buffer,
 *   in the same queue, so the writer plays it first and the item follows without a frame lost.
 * - **The numbers are the transport plan's** (B46-A3): how many frames [ms] is at the stream's rate and the blocks it is
 *   queued in come from the stream's `UsbTransportPlan` (`UsbAudioStream.transportPlan(ms)`), not from arithmetic here.
 * - **The clock still marks 0:00 at the item's real first frame.** The silence is queue lead like any other
 *   audio ahead of a buffer (AUD-143, [PipelinePositionClock.accept]): the clock starts that much earlier and
 *   reaches the item's first timestamp exactly when its first frame is written.
 * - **Only at a rate change.** A same-rate join keeps its cached stream and never comes here (exact and
 *   seamless, as before); a rebuild for another depth or channel layout at the same rate is not a rate change.
 *   The first stream after a start counts as one — the DAC relocks from its idle clock — and the log says so.
 */
object RateSwitchPreroll {
    /** The values `SET_RATE_SWITCH_PREROLL` accepts; anything else is refused and changes nothing. */
    val ALLOWED_MS: List<Int> = listOf(0, 250, 500, 1000)

    /** The silence's encoding: 16-bit zeros widen to zeros at 16, 24 and 32 bits, whatever the item's own encoding. */
    const val ENCODING = C.ENCODING_PCM_16BIT
    private const val BYTES_PER_SAMPLE = 2

    fun isAllowed(ms: Int): Boolean = ms in ALLOWED_MS

    /** [ms] when it is one of [ALLOWED_MS], else 0 (no pre-roll): an unknown stored value must never become one. */
    fun normalized(ms: Int): Int = if (isAllowed(ms)) ms else 0

    /**
     * A stream is rebuilt for a rate change when the new stream's rate differs from the one it replaces
     * ([previousRateHz] 0: no stream played before in this process — the DAC's clock is idle, which is also a relock).
     */
    fun isRateSwitch(previousRateHz: Int, newRateHz: Int): Boolean = newRateHz > 0 && previousRateHz != newRateHz

    /** What a rebuild queues: [requestedMs] (normalized) at a rate change, 0 otherwise. */
    fun msFor(requestedMs: Int, previousRateHz: Int, newRateHz: Int): Int =
        if (isRateSwitch(previousRateHz, newRateHz)) normalized(requestedMs) else 0

    /**
     * Queues the silence [plan] asks for (its pre-roll frames, in its blocks) on [thread], ahead of whatever is queued after.
     * Returns the frames queued.
     */
    fun enqueue(thread: UsbStreamingThread, plan: UsbTransportPlan, channels: Int): Long {
        for (n in plan.prerollBlocks()) thread.enqueueRaw(ByteArray(n * channels * BYTES_PER_SAMPLE), ENCODING)
        return plan.prerollFrames
    }
}
