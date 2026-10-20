package com.lostf1sh.pixelplayeross.data.volume

import android.content.Context
import android.os.PowerManager
import timber.log.Timber

/**
 * Turns the screen on for the Safety Max override confirmation (FIX-5), and nothing else.
 *
 * This is the one wake lock of the app (F-06 pinned that there were none of its own, and why: a
 * hand-rolled lock outlives a pause). It is not that: it is acquired only here, **with a timeout**
 * (so it releases itself even if nobody calls [release]), only after five deliberate presses at the
 * ceiling in the dedicated appliance, and it holds the screen for at most the time the confirmation
 * waits for an answer. `ACQUIRE_CAUSES_WAKEUP` is what turns the screen on; the window flags
 * (`setTurnScreenOn`) only act when an activity is launched, and this one is already resumed under
 * Lock Task. The class is deprecated-flag-aware on purpose: it is the documented way to wake the
 * device from a background component and has no replacement for that.
 */
class OverrideScreenWaker(context: Context) {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    @Suppress("DEPRECATION")
    fun wake(timeoutMs: Long) {
        val manager = power ?: return
        release()
        lock = manager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            TAG,
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire(timeoutMs) }
                .onFailure { Timber.tag(TAG).w(it, "Unable to wake the screen for the override confirmation") }
        }
    }

    @Synchronized
    fun release() {
        val held = lock ?: return
        lock = null
        runCatching { if (held.isHeld) held.release() }
    }

    private companion object {
        const val TAG = "Polylute:override-confirm"
    }
}
