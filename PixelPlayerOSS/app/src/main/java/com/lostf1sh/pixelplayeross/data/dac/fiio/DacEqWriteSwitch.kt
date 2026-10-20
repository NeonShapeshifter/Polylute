package com.lostf1sh.pixelplayeross.data.dac.fiio

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lostf1sh.pixelplayeross.BuildConfig
import com.lostf1sh.pixelplayeross.data.service.player.DebugFlagStore

/**
 * DEBUG ONLY — `SET_DAC_EQ_WRITES_ALLOWED --ez allowed true|false` (debug bridge), B30-P2.
 *
 * The Melody's profile stays **unverified** (`FiioHidProfile.verified` is false for every row) until a reviewer's device run passes,
 * so the write gate refuses it. This switch is how the reviewer lets the gate through *on this phone*, to run that verification:
 *
 * - **Off by default**, and off unless explicitly turned on; nothing is written when it is only read.
 * - **Persisted**, so it survives a process restart between the runbook's steps (and across the unplug step, which may restart the
 *   process). That is also its risk, so every debug command reports it and the Resonance screen's DAC-EQ editing says "debug
 *   override" while it is the only reason a write is allowed.
 * - **Inert outside a debug build**: [debugBuild] false ignores a stored value and refuses to write, so no release path can open the
 *   gate. A release build's only way to write is a profile marked verified, which a later batch decides.
 * - It only lifts the **"verified" requirement**. Every other condition of the gate (read, backup, user slot, limits, net boost) stays.
 *
 * Only the debug receiver writes it (`DacEqWriteSwitchBoundaryTest` pins that).
 */
class DacEqWriteSwitch(
    private val store: DebugFlagStore,
    private val debugBuild: Boolean,
) {
    @Volatile
    private var allowed: Boolean = debugBuild && store.read()

    fun isAllowed(): Boolean = allowed

    fun set(value: Boolean) {
        if (!debugBuild) return
        store.write(value)
        allowed = value
    }

    companion object {
        const val PREFERENCES = "debug_dac_eq_writes"
        const val KEY = "dac_eq_writes_allowed"

        @Volatile
        private var shared: DacEqWriteSwitch? = null

        /** The process-wide switch. The first call loads the preference file: call it off the main thread. */
        fun shared(context: Context): DacEqWriteSwitch =
            shared ?: synchronized(this) {
                shared ?: DacEqWriteSwitch(
                    PreferencesFlagStore(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)),
                    BuildConfig.DEBUG,
                ).also { shared = it }
            }
    }

    /** The persisted flag: [KEY] in whatever preferences it is given. Reads no other key. */
    class PreferencesFlagStore(private val preferences: SharedPreferences) : DebugFlagStore {
        override fun read(): Boolean = preferences.getBoolean(KEY, false)

        // commit, not apply: a shell that force-stops right after the broadcast must not lose it.
        override fun write(value: Boolean) {
            preferences.edit(commit = true) { putBoolean(KEY, value) }
        }
    }
}
