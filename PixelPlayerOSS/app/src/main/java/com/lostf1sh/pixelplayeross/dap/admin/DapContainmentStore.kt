package com.lostf1sh.pixelplayeross.dap.admin

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DapContainmentStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun isDedicatedModeEnabled(): Boolean =
        preferences.getBoolean(KEY_DEDICATED_MODE_ENABLED, false)

    fun setDedicatedModeEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DEDICATED_MODE_ENABLED, enabled).apply()
    }

    /**
     * Mirrors the DAP Lock Screen setting into the synchronous store the Device Owner controller
     * reads. Device policy must be reconcilable from authoritative product state at any time —
     * including after process death — so it cannot depend on an in-memory flag.
     */
    fun isDapLockScreenEnabled(): Boolean =
        preferences.getBoolean(KEY_DAP_LOCK_SCREEN_ENABLED, false)

    fun setDapLockScreenEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DAP_LOCK_SCREEN_ENABLED, enabled).apply()
    }

    /**
     * Whether this app wrote the persistent HOME entry: `applyPersistentHome` records it on before it adds the entry, the exit's clear records it
     * off. A key nobody wrote is "not written" (B35-fix1 R2). It used to read true, so a fresh install claimed an entry nobody had added, the
     * restore judged the HOME ready and "Volver al DAP" judged the policy ready, and a Device Owner whose listener had only picked Polylute in
     * Android's picker never got the entry.
     */
    fun isPersistentHomeEnabled(): Boolean =
        preferences.getBoolean(KEY_PERSISTENT_HOME_ENABLED, false)

    fun setPersistentHomeEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_PERSISTENT_HOME_ENABLED, enabled).apply()
    }

    /**
     * B34-fix2 R1: the listener asked to leave Dedicated through "Choose home app" and Android's picker has not settled it yet. A recorded
     * fact, not an inference: while it stands nothing re-arms the Dedicated policy ([DapContainmentReconciler]), whatever Android reads
     * (Polylute is still the home app until the listener picks another one, which is what the old Device Owner + home inference took for a
     * lost policy). Read by every restore trigger, boot and package replacement included, so it is written synchronously: a process that
     * dies right after the tap must find it.
     */
    fun isHomeExitChosen(): Boolean =
        preferences.getBoolean(KEY_HOME_EXIT_CHOSEN, false)

    fun setHomeExitChosen(chosen: Boolean) {
        preferences.edit(commit = true) { putBoolean(KEY_HOME_EXIT_CHOSEN, chosen) }
    }

    private companion object {
        const val PREFERENCES_NAME = "dap_containment"
        const val KEY_DEDICATED_MODE_ENABLED = "dedicated_mode_enabled"
        const val KEY_PERSISTENT_HOME_ENABLED = "persistent_home_enabled"
        const val KEY_DAP_LOCK_SCREEN_ENABLED = "dap_lock_screen_enabled"
        const val KEY_HOME_EXIT_CHOSEN = "home_exit_chosen"
    }
}

/**
 * The window in which Lock Task is lifted from this process (B32-Q6).
 *
 * It was the Maintenance PIN's session; there is no PIN any more, and the window now opens when the System page asks for an Android
 * surface (Settings, Wi-Fi, ...) and closes when the app is back and the containment has been put back. The states, the idle and the
 * absolute limit are what they were. `AUTHENTICATED_*` is the old name: it now says "the window is open", not "a PIN was typed".
 */
enum class MaintenanceSessionState {
    NOT_AUTHENTICATED,
    AUTHENTICATED_ACTIVE,
    AUTHENTICATED_EXTERNAL_FLOW,
    EXPIRED,
}

data class MaintenanceSessionSnapshot(
    val state: MaintenanceSessionState = MaintenanceSessionState.NOT_AUTHENTICATED,
    val authenticatedAtElapsedMs: Long? = null,
    val lastInteractionElapsedMs: Long? = null,
    /**
     * B32-fix1 R2: the window's exit kind. True for a window the System page opened for an Android surface: when the app is back in
     * front, the containment is put back. It lives here, with the window, and not in the activity that opened it: the activity can be
     * destroyed and recreated while the surface is out (a configuration change, the system reclaiming it) and the new one must still
     * know. False for every other window, and for the HOME recovery, which leaves the device the way the owner asked and ends its
     * window at once.
     */
    val restoreOnReturn: Boolean = false,
) {
    val isAuthenticated: Boolean
        get() = state == MaintenanceSessionState.AUTHENTICATED_ACTIVE ||
            state == MaintenanceSessionState.AUTHENTICATED_EXTERNAL_FLOW
}

/** Pure, process-local Maintenance session policy. All time is monotonic elapsed time. */
class MaintenanceSessionController(
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val absoluteTimeoutMs: Long = ABSOLUTE_TIMEOUT_MS,
) {
    private val _snapshot = MutableStateFlow(MaintenanceSessionSnapshot())
    val snapshot: StateFlow<MaintenanceSessionSnapshot> = _snapshot.asStateFlow()

    @Synchronized
    fun begin(nowElapsedMs: Long) {
        _snapshot.value = MaintenanceSessionSnapshot(
            state = MaintenanceSessionState.AUTHENTICATED_ACTIVE,
            authenticatedAtElapsedMs = nowElapsedMs,
            lastInteractionElapsedMs = nowElapsedMs,
        )
    }

    @Synchronized
    fun recordInteraction(nowElapsedMs: Long): Boolean {
        if (expireIfNeededLocked(nowElapsedMs)) return false
        val current = _snapshot.value
        if (current.state != MaintenanceSessionState.AUTHENTICATED_ACTIVE) return false
        _snapshot.value = current.copy(lastInteractionElapsedMs = nowElapsedMs)
        return true
    }

    @Synchronized
    fun beginExternalFlow(nowElapsedMs: Long, restoreOnReturn: Boolean = false): Boolean {
        if (expireIfNeededLocked(nowElapsedMs)) return false
        val current = _snapshot.value
        if (!current.isAuthenticated) return false
        _snapshot.value = current.copy(
            state = MaintenanceSessionState.AUTHENTICATED_EXTERNAL_FLOW,
            lastInteractionElapsedMs = nowElapsedMs,
            restoreOnReturn = restoreOnReturn,
        )
        return true
    }

    @Synchronized
    fun returnFromExternalFlow(nowElapsedMs: Long): Boolean {
        if (expireIfNeededLocked(nowElapsedMs)) return false
        val current = _snapshot.value
        if (current.state != MaintenanceSessionState.AUTHENTICATED_EXTERNAL_FLOW) {
            return current.state == MaintenanceSessionState.AUTHENTICATED_ACTIVE
        }
        // the obligation is taken by whoever reads it just before this call ([DapSystemSurfaceCoordinator.reconcileOnResume]): it is
        // asked once per return, not left standing for the next resume
        _snapshot.value = current.copy(
            state = MaintenanceSessionState.AUTHENTICATED_ACTIVE,
            lastInteractionElapsedMs = nowElapsedMs,
            restoreOnReturn = false,
        )
        return true
    }

    @Synchronized
    fun expireIfNeeded(nowElapsedMs: Long): Boolean = expireIfNeededLocked(nowElapsedMs)

    @Synchronized
    fun millisUntilExpiry(nowElapsedMs: Long): Long? {
        if (expireIfNeededLocked(nowElapsedMs)) return 0L
        val current = _snapshot.value
        if (!current.isAuthenticated) return null
        val authenticatedAt = current.authenticatedAtElapsedMs ?: return 0L
        val absoluteRemaining = authenticatedAt + absoluteTimeoutMs - nowElapsedMs
        val remaining = if (current.state == MaintenanceSessionState.AUTHENTICATED_EXTERNAL_FLOW) {
            absoluteRemaining
        } else {
            val lastInteraction = current.lastInteractionElapsedMs ?: authenticatedAt
            minOf(absoluteRemaining, lastInteraction + idleTimeoutMs - nowElapsedMs)
        }
        return remaining.coerceAtLeast(0L)
    }

    @Synchronized
    fun invalidate(expired: Boolean = false) {
        _snapshot.value = MaintenanceSessionSnapshot(
            if (expired) MaintenanceSessionState.EXPIRED
            else MaintenanceSessionState.NOT_AUTHENTICATED,
        )
    }

    private fun expireIfNeededLocked(nowElapsedMs: Long): Boolean {
        val current = _snapshot.value
        if (!current.isAuthenticated) return current.state == MaintenanceSessionState.EXPIRED
        val authenticatedAt = current.authenticatedAtElapsedMs ?: run {
            invalidate(expired = true)
            return true
        }
        val absoluteExpired = nowElapsedMs - authenticatedAt >= absoluteTimeoutMs
        val idleExpired = current.state == MaintenanceSessionState.AUTHENTICATED_ACTIVE &&
            nowElapsedMs - (current.lastInteractionElapsedMs ?: authenticatedAt) >= idleTimeoutMs
        if (absoluteExpired || idleExpired) {
            invalidate(expired = true)
            return true
        }
        return false
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 5 * 60 * 1_000L
        const val ABSOLUTE_TIMEOUT_MS = 15 * 60 * 1_000L
    }
}

/**
 * Deliberately process-local. Process death and reboot construct a fresh unauthenticated object;
 * no authentication token or boolean is written to preferences or saved instance state.
 */
object DapMaintenanceSession {
    private val controller = MaintenanceSessionController()
    val snapshot: StateFlow<MaintenanceSessionSnapshot> = controller.snapshot

    val isActive: Boolean
        get() = controller.snapshot.value.isAuthenticated &&
            !controller.expireIfNeeded(SystemClock.elapsedRealtime())

    fun begin() = controller.begin(SystemClock.elapsedRealtime())
    fun touch(): Boolean = controller.recordInteraction(SystemClock.elapsedRealtime())
    fun beginExternalFlow(restoreOnReturn: Boolean = false): Boolean =
        controller.beginExternalFlow(SystemClock.elapsedRealtime(), restoreOnReturn)
    fun returnFromExternalFlow(): Boolean =
        controller.returnFromExternalFlow(SystemClock.elapsedRealtime())
    fun expireIfNeeded(): Boolean = controller.expireIfNeeded(SystemClock.elapsedRealtime())
    fun millisUntilExpiry(): Long? = controller.millisUntilExpiry(SystemClock.elapsedRealtime())
    fun end() = controller.invalidate()

    /** What the app coming back to the front does about the window (B32-fix1 R2): decided from the window, not from any activity. */
    internal fun reconcileOnResume(): WindowReturn =
        DapSystemSurfaceCoordinator.reconcileOnResume(controller, SystemClock.elapsedRealtime())
}

/** Process-local guard for a single user-initiated, platform-owned confirmation activity. */
object DapTemporarySystemFlowSession {
    @Volatile
    var isActive: Boolean = false
        private set

    fun begin() {
        isActive = true
    }

    fun end() {
        isActive = false
    }
}
