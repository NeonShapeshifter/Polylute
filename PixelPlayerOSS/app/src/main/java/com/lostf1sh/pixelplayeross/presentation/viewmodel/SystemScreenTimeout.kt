package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.provider.Settings
import timber.log.Timber

/**
 * The one place the app reads and writes `Settings.System.SCREEN_OFF_TIMEOUT` (F-05, FIX-2).
 *
 * [write] is the only write, and it refuses anything [ScreenTimeoutPolicy] does not allow, so no path
 * (the settings list, the drift guard) can put "never" or any other non-preset value into the system.
 */
object SystemScreenTimeout {
    private const val TAG = "ScreenTimeout"

    /** The system's value now, or null when it cannot be read. */
    fun read(context: Context): Long? = runCatching {
        Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
    }.getOrNull()

    fun canWrite(context: Context): Boolean = runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /** Writes a preset; false when it is not one, or Android refuses. */
    fun write(context: Context, timeoutMs: Long): Boolean {
        if (!ScreenTimeoutPolicy.isAppWritable(timeoutMs)) return false
        return runCatching {
            Settings.System.putLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeoutMs)
        }.onFailure { Timber.tag(TAG).w(it, "Unable to write the screen timeout") }.getOrDefault(false)
    }
}
