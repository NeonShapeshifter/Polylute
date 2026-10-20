package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * FIX-2 (B11-fix): notices when the system's screen timeout is no longer what the user chose, and on
 * the appliance puts it back.
 *
 * Device, 2026-10-07: the app showed "30 s" (its stored choice) while the system was at
 * 2147483647. Something wrote "never" after the app had written 30 s, and nothing here noticed.
 * Who did is unknown, so every drift is logged with the time it was found, which is how the culprit
 * gets found: `Screen timeout drifted from X to Y; re-applied`.
 *
 * Runs when the app comes back to the foreground and at boot. The decision is
 * [ScreenTimeoutPolicy.drift]: re-apply only on the appliance and only if Android allows the write;
 * on a phone it never writes.
 */
object ScreenTimeoutDriftGuard {
    private const val TAG = "ScreenTimeout"

    fun reconcile(context: Context, storedMode: Long, dapMode: Boolean, trigger: String): ScreenTimeoutPolicy.Drift {
        val real = SystemScreenTimeout.read(context)
            ?: return ScreenTimeoutPolicy.Drift(ScreenTimeoutPolicy.DriftAction.NONE, storedMode, storedMode)
        val drift = ScreenTimeoutPolicy.drift(storedMode, real, dapMode, SystemScreenTimeout.canWrite(context))
        if (!drift.drifted) return drift

        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        if (drift.action == ScreenTimeoutPolicy.DriftAction.REAPPLY) {
            val applied = SystemScreenTimeout.write(context, storedMode)
            Timber.tag(TAG).w(
                "Screen timeout drifted from %d to %d; %s (trigger=%s, at=%s)",
                drift.storedMs, drift.systemMs, if (applied) "re-applied" else "re-apply failed", trigger, at
            )
        } else {
            Timber.tag(TAG).w(
                "Screen timeout drifted from %d to %d; not re-applied (%s, trigger=%s, at=%s)",
                drift.storedMs, drift.systemMs,
                if (dapMode) "no WRITE_SETTINGS" else "app mode: the system's value stands",
                trigger, at
            )
        }
        return drift
    }
}
