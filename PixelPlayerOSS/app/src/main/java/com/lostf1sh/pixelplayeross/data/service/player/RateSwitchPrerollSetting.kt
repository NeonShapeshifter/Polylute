package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import android.content.SharedPreferences
import com.decent.usbaudio.media3.RateSwitchPreroll
import com.lostf1sh.pixelplayeross.BuildConfig

/** Where a debug number lives between processes; a seam so the setting is testable without a Context. */
interface DebugIntStore {
    fun read(): Int
    fun write(value: Int)
}

/**
 * DEBUG ONLY — `SET_RATE_SWITCH_PREROLL --ei ms 0|250|500|1000` (debug bridge), B12a-4.
 *
 * Milliseconds of silence queued ahead of an item's first frame after a stream rebuild for a **sample-rate**
 * change (see [RateSwitchPreroll]): the experiment for "some songs eat their first second", which is probably the
 * DAC muting while it relocks its clock. **Default 0** — nothing changes — until the owner confirms by ear which
 * value fixes it.
 *
 * - **Persisted**, so it survives the process while it is being judged by ear over days.
 * - **Only 0, 250, 500 or 1000**: [set] refuses anything else (and a stored value outside the list reads as 0), so
 *   a typo cannot become a 5-minute silence.
 * - **Inert outside a debug build**: [debugBuild] false reads 0 and refuses to write.
 * - **A cached field on the audio side.** The store is read once, when the setting is built.
 */
class RateSwitchPrerollSetting(
    private val store: DebugIntStore,
    private val debugBuild: Boolean,
) {
    @Volatile
    private var value: Int = if (debugBuild) RateSwitchPreroll.normalized(store.read()) else 0

    fun ms(): Int = value

    /** True when [ms] was one of the allowed values and was stored. */
    fun set(ms: Int): Boolean {
        if (!debugBuild || !RateSwitchPreroll.isAllowed(ms)) return false
        store.write(ms)
        value = ms
        return true
    }

    companion object {
        const val PREFERENCES = "debug_rate_switch_preroll"
        const val KEY = "ms"

        @Volatile
        private var shared: RateSwitchPrerollSetting? = null

        /** The process-wide setting. The first call loads the preference file: call it off the audio thread. */
        fun shared(context: Context): RateSwitchPrerollSetting =
            shared ?: synchronized(this) {
                shared ?: RateSwitchPrerollSetting(
                    PreferencesIntStore(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)),
                    BuildConfig.DEBUG,
                ).also { shared = it }
            }
    }

    /** The persisted number: [KEY] in whatever preferences it is given. */
    class PreferencesIntStore(private val preferences: SharedPreferences) : DebugIntStore {
        override fun read(): Int = preferences.getInt(KEY, 0)

        // commit, not apply: a shell that force-stops right after the broadcast must not lose it.
        override fun write(value: Int) {
            preferences.edit().putInt(KEY, value).commit()
        }
    }
}
