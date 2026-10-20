package com.lostf1sh.pixelplayeross.data.service

import android.media.AudioDeviceInfo

/**
 * The "resume when the headset comes back" rule, and which pauses arm it.
 *
 * **What it is** (it existed before B25 inside `MusicService`; this is the same rule, moved so it can be tested):
 * - it is a setting, off by default ([enabled]);
 * - only a pause that was the output going away arms it: the platform's "audio becoming noisy" pause, and (B25-S1) the
 *   engine's own pause when the USB DAC is unplugged ([onOutputLostPause]); the user's pause, a pause for a call, a
 *   sleep timer, none of those do;
 * - an output of a kind in [isReconnectableOutputType] appearing within [windowMs] of that pause resumes it, once;
 * - anything else that happens first (the user plays, the queue ends or empties, the window runs out) disarms it.
 *
 * **B25-S1 added a DAC to it and nothing else**: a USB DAC replugged within the window resumes the music when the setting
 * is on (`TYPE_USB_HEADSET` was already in the list; `TYPE_USB_DEVICE`, how Android lists a DAC without a headset
 * terminal, was not, so that kind of DAC could never resume). With the setting off, nothing resumes by itself, as before.
 *
 * Main thread only; the caller passes the clock so the rule has none of its own.
 */
internal class HeadsetReconnectRule(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    var enabled = false
        set(value) {
            field = value
            if (!value) clear()
        }

    var isArmed = false
        private set

    private var pausedAtMs = 0L
    private var ownPauseUntilMs = 0L

    /**
     * The player's `playWhenReady` changed.
     * @param noisyPause the change was reported with `PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY`.
     */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean, noisyPause: Boolean, nowMs: Long) {
        when {
            playWhenReady -> clear()
            !enabled -> clear()
            noisyPause -> arm(nowMs)
            // The pause the engine issued for a lost output is reported as an ordinary one; it was armed just before it.
            isArmed && nowMs <= ownPauseUntilMs -> Unit
            else -> clear()
        }
    }

    /** The engine is pausing because the output went away (called just before the pause, or just after it). */
    fun onOutputLostPause(nowMs: Long) {
        if (!enabled) return
        arm(nowMs)
        ownPauseUntilMs = nowMs + OWN_PAUSE_GRACE_MS
    }

    /**
     * An audio output appeared. True when playback should be resumed now; the rule is spent either way once it has decided.
     */
    fun onOutputAdded(
        nowMs: Long,
        reconnectableOutputPresent: Boolean,
        hasMediaItem: Boolean,
        playWhenReady: Boolean,
        idleOrEnded: Boolean,
    ): Boolean {
        if (!enabled || !isArmed) return false
        if (nowMs - pausedAtMs > windowMs) {
            clear()
            return false
        }
        if (!reconnectableOutputPresent) return false
        if (!hasMediaItem || playWhenReady || idleOrEnded) {
            clear()
            return false
        }
        clear()
        return true
    }

    fun clear() {
        isArmed = false
        pausedAtMs = 0L
        ownPauseUntilMs = 0L
    }

    private fun arm(nowMs: Long) {
        isArmed = true
        pausedAtMs = nowMs
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 15_000L

        /** A pause and the event that reports it arrive within the same turn; this is how long "just before" may be. */
        const val OWN_PAUSE_GRACE_MS = 1_000L

        /** Outputs a listener plugs in on purpose; a speaker or an earpiece appearing is not one. */
        fun isReconnectableOutputType(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
            else -> false
        }
    }
}
