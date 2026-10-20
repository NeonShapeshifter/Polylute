package com.decent.usbaudio.media3

import androidx.media3.common.C

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
 * - **The clock still marks 0:00 at the item's real first frame.** The silence is queue lead like any other
 *   audio ahead of a buffer (AUD-143, [PipelinePositionClock.accept]): the clock starts that much earlier and
 *   reaches the item's first timestamp exactly when its first frame is written.
 * - **Only at a rate change.** A same-rate join keeps its cached stream and never comes here (exact and
 *   seamless, as before); a rebuild for another depth or channel layout at the same rate is not a rate change.
 *   The first stream after a start counts as one — the DAC relocks from its idle clock — and the log says so.
 * - **Pipeline only.** The native engine (plan B) does not get it.
 */
object RateSwitchPreroll {
    /** The values `SET_RATE_SWITCH_PREROLL` accepts; anything else is refused and changes nothing. */
    val ALLOWED_MS: List<Int> = listOf(0, 250, 500, 1000)

    /** Silence is queued in buffers of this many frames (≤ 24 of them for 1 s at 192 kHz). */
    const val CHUNK_FRAMES = 8192

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

    fun frames(ms: Int, rateHz: Int): Long = if (ms <= 0 || rateHz <= 0) 0L else rateHz.toLong() * ms / 1000L

    /** [totalFrames] split into buffers of at most [CHUNK_FRAMES] whose sizes add up to it exactly. */
    fun chunks(totalFrames: Long): List<Int> {
        val sizes = ArrayList<Int>()
        var left = totalFrames
        while (left > 0) {
            val n = minOf(left, CHUNK_FRAMES.toLong()).toInt()
            sizes += n
            left -= n
        }
        return sizes
    }

    /** Queues [ms] of silence on [thread], ahead of whatever is queued after. Returns the frames queued. */
    fun enqueue(thread: UsbStreamingThread, ms: Int, rateHz: Int, channels: Int): Long {
        val total = frames(ms, rateHz)
        for (n in chunks(total)) thread.enqueueRaw(ByteArray(n * channels * BYTES_PER_SAMPLE), ENCODING)
        return total
    }
}
