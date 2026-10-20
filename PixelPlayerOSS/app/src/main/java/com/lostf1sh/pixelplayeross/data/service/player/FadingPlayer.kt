package com.lostf1sh.pixelplayeross.data.service.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wraps the session-facing player so play/pause ramp the volume over a short fade instead
 * of cutting the audio hard.
 *
 * The fade target is captured from the inner player at fade start: ReplayGain writes its
 * normalized volume directly on the engine's master player (not through this wrapper), so
 * tracking only wrapper-side [setVolume] calls would make every fade end at full volume and
 * defeat the normalization. [getVolume] reports the logical volume during a fade rather
 * than the transient mid-ramp value.
 */
@OptIn(UnstableApi::class)
class FadingPlayer(
    val innerPlayer: Player,
    private val scope: CoroutineScope,
) : ForwardingPlayer(innerPlayer) {

    private var fadeJob: Job? = null
    // Volume the player should sit at when no fade is running.
    private var logicalVolume = innerPlayer.volume

    override fun setVolume(volume: Float) {
        logicalVolume = volume
        if (fadeJob?.isActive != true) {
            super.setVolume(volume)
        }
    }

    override fun getVolume(): Float {
        return if (fadeJob?.isActive == true) logicalVolume else super.getVolume()
    }

    override fun play() {
        val wasFading = fadeJob?.isActive == true
        fadeJob?.cancel()
        if (isPlaying) {
            // A play during the pause ramp: the ramp was cancelled mid-way, so put the level back.
            if (wasFading) rampSet(logicalVolume)
            super.play()
            return
        }

        // Capture the volume ReplayGain (or the user) set directly on the engine player — but only
        // when no ramp is in flight. Mid-ramp, the inner volume is a ramp step, and taking it as the
        // target made every interrupted fade end lower than the last: the level ratcheted down.
        if (!wasFading) logicalVolume = innerPlayer.volume
        val targetVolume = logicalVolume
        rampSet(0f)
        super.play()

        fadeJob = scope.launch(Dispatchers.Main) {
            val stepTime = FADE_DURATION_MS / FADE_STEPS
            for (i in 1..FADE_STEPS) {
                if (!isActive) break
                val volume = (i.toFloat() / FADE_STEPS) * targetVolume
                rampSet(volume)
                delay(stepTime)
            }
            rampSet(targetVolume)
        }
    }

    override fun pause() {
        val wasFading = fadeJob?.isActive == true
        fadeJob?.cancel()
        if (!isPlaying) {
            // A pause that cancels a fade-in before playback started: leave the level at its target,
            // not at whatever step the ramp had reached.
            if (wasFading) rampSet(logicalVolume)
            super.pause()
            return
        }

        val startVolume = if (wasFading) logicalVolume else innerPlayer.volume
        logicalVolume = startVolume
        fadeJob = scope.launch(Dispatchers.Main) {
            // Pause uses its own, much shorter ramp.
            //
            // This one is on the critical path in a way the fade-in is not: the player is only
            // paused after the loop finishes, so the ramp IS the delay between the user's finger
            // and the music stopping. At the shared 500 ms it made pause feel broken while play
            // felt instant -- the same gesture, in the same place, one of them ~600 ms behind.
            //
            // A ramp only has to be long enough to avoid a discontinuity at the cut, which is a
            // few tens of milliseconds; 500 ms was never buying anything but the delay.
            val stepTime = PAUSE_FADE_DURATION_MS / PAUSE_FADE_STEPS
            for (i in 1..PAUSE_FADE_STEPS) {
                if (!isActive) break
                val volume = ((PAUSE_FADE_STEPS - i).toFloat() / PAUSE_FADE_STEPS) * startVolume
                rampSet(volume)
                delay(stepTime)
            }
            rampSet(0f)
            super.pause()
            // Restore the logical volume so the paused player resumes from the right level.
            rampSet(startVolume)
        }
    }

    /**
     * Writes one ramp step. Marked so volume observers can tell a ramp step from a real change:
     * ExoPlayer reports `onVolumeChanged` synchronously inside the write, on this same thread.
     */
    private fun rampSet(volume: Float) {
        rampWriteDepth++
        try {
            super.setVolume(volume)
        } finally {
            rampWriteDepth--
        }
    }

    companion object {
        // Main-thread only, like every write in this class.
        private var rampWriteDepth = 0

        /**
         * True while a fade step is being written. A volume change seen now is the ramp, not the
         * user: taking it as the user's level left the digital gain stuck at a ramp step (measured:
         * 20% after a quick play/pause), which also forced the Direct path into PROCESSED.
         */
        val isRampWrite: Boolean
            get() = rampWriteDepth > 0

        /** Fade-IN on play. Off the critical path: playback starts immediately either way. */
        private const val FADE_DURATION_MS = 500L
        private const val FADE_STEPS = 25

        /**
         * Fade-OUT on pause, which the actual `pause()` waits behind.
         *
         * Kept just long enough to avoid a hard cut, and short enough that the music stops when
         * the user asked it to.
         */
        private const val PAUSE_FADE_DURATION_MS = 72L
        private const val PAUSE_FADE_STEPS = 6
    }
}
