package com.decent.usbaudio.media3

/**
 * B9-3: how a PRISTINE ↔ PROCESSED change is joined to the audio that is already queued, when the
 * ExoPlayer pipeline is the producer on both sides and so keeps its thread and its queue.
 *
 * Before B9 the change stopped the thread (or sought the player, which flushed it), throwing away
 * ~1.4 s of decoded audio and cutting the ring: with the pipeline as the only producer an EQ toggle would
 * do that every time. Now what is queued stays, and the next buffers are processed (or not) from the change
 * on. A hard switch at that buffer boundary would put a step in the waveform — the stage's output differs
 * from its input by the whole EQ, and a filter engaged cold starts from zero state — so the first
 * [PipelineThread.PROCESSING_CROSSFADE_MS] ms are crossfaded instead:
 *
 * - towards PROCESSED the output starts as the buffer's own samples (weight 0 on the stage, so it joins
 *   the audio queued before it exactly) and ends as the stage's output;
 * - towards PRISTINE it starts as the stage's output (continuing the processed audio before it) and ends
 *   as the buffer's own samples, from where the pipeline carries them bit-exact again.
 *
 * Ramp positions are claimed on the playback thread in the order buffers are queued, so each buffer's
 * lambda knows where it sits in the ramp without any shared counter at write time.
 */
class ProcessingCrossfade(
    /** The stage on the processed side of the change: the one just engaged, or the one just retired. */
    val stage: DirectPcmStage,
    val towardsProcessed: Boolean,
    val totalFrames: Int,
) {
    private var claimedFrames = 0

    /** The writer thread's own copy of a buffer's dry samples. */
    private var dry = FloatArray(0)

    /** The ramp is spoken for: later buffers take the plain path. Playback thread. */
    val isDone: Boolean
        get() = claimedFrames >= totalFrames

    /** Where in the ramp the next [frames] frames start; called in queue order. Playback thread. */
    fun claim(frames: Int): Int {
        val start = claimedFrames
        claimedFrames = (claimedFrames.toLong() + frames).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return start
    }

    /** Weight of the stage's output at ramp frame [k]: 0 = the buffer's own samples, 1 = fully processed. */
    fun wetWeight(k: Int): Float {
        val progress = ((k + 1).toFloat() / totalFrames).coerceIn(0f, 1f)
        return if (towardsProcessed) progress else 1f - progress
    }

    /**
     * Replaces [data] (interleaved, [frames] frames of [channels]) with the blend of its own samples and
     * the stage's output on them, where the buffer's first frame is ramp frame [rampStart]. Writer thread,
     * at write time and under the stage lock, like the stage itself (AUD-111).
     */
    fun blend(data: FloatArray, frames: Int, channels: Int, rampStart: Int) {
        val samples = frames * channels
        if (dry.size < samples) dry = FloatArray(samples)
        System.arraycopy(data, 0, dry, 0, samples)
        stage.process(data, frames)
        var f = 0
        while (f < frames) {
            val wet = wetWeight(rampStart + f)
            if (towardsProcessed && wet >= 1f) break
            if (!towardsProcessed && wet <= 0f) {
                System.arraycopy(dry, f * channels, data, f * channels, (frames - f) * channels)
                break
            }
            for (c in 0 until channels) {
                val i = f * channels + c
                val own = dry[i]
                data[i] = own + (data[i] - own) * wet
            }
            f++
        }
    }
}
