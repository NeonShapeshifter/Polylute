package com.lostf1sh.pixelplayeross.data.dac.fiio

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lostf1sh.pixelplayeross.BuildConfig

/** A persisted debug setting that holds one word. */
interface DebugTextStore {
    fun read(): String?
    fun write(value: String?)
}

/**
 * DEBUG ONLY — `SET_DAC_EQ_PRE_GAIN_SENSE --es sense boost|attenuation|unconfirmed` (debug bridge), B31 P1.2.
 *
 * Every profile's [FiioHidProfile.preGainSense] is [PreGainSense.UNCONFIRMED]: the app does not know whether a positive global gain
 * amplifies or attenuates, and judges every level under both readings until it is told. **The reviewer's listening check** (a flat curve
 * on the app's slot with the pre-gain at −6 and at +6 dB, music playing, the DAC at a low level) says which, and this switch records the
 * answer **on this phone** so the rest of the runbook (a +1 dB band, the roundtrip of a curve) can run. A later step writes the answer
 * into the profile row; this is not that step.
 *
 * - **Unset by default**; [PreGainSense.UNCONFIRMED] clears it.
 * - **Persisted** like the writes override, so it survives the process restart of the unplug step.
 * - **Inert outside a debug build**: a stored value is ignored and [set] does nothing, so no release path can claim a direction.
 *
 * Only the debug receiver writes it (`DacEqSupportTest` pins that).
 */
class DacEqPreGainSenseSwitch(
    private val store: DebugTextStore,
    private val debugBuild: Boolean,
) {
    @Volatile
    private var override: PreGainSense? = if (debugBuild) PreGainSense.parse(store.read())?.takeIf { it != PreGainSense.UNCONFIRMED } else null

    /** The reviewer's answer on this phone, or null when none was given (or in a release build). */
    fun override(): PreGainSense? = override

    fun set(sense: PreGainSense) {
        if (!debugBuild) return
        store.write(if (sense == PreGainSense.UNCONFIRMED) null else sense.name.lowercase())
        override = sense.takeIf { it != PreGainSense.UNCONFIRMED }
    }

    /** The sense a level is judged under for [profile]: the reviewer's answer if there is one, else what the profile row says. */
    fun effective(profile: FiioHidProfile): PreGainSense = override ?: profile.preGainSense

    companion object {
        const val KEY = "dac_eq_pre_gain_sense"

        @Volatile
        private var shared: DacEqPreGainSenseSwitch? = null

        /** The process-wide switch. The first call loads the preference file: call it off the main thread. */
        fun shared(context: Context): DacEqPreGainSenseSwitch =
            shared ?: synchronized(this) {
                shared ?: DacEqPreGainSenseSwitch(
                    PreferencesTextStore(context.applicationContext.getSharedPreferences(DacEqWriteSwitch.PREFERENCES, Context.MODE_PRIVATE)),
                    BuildConfig.DEBUG,
                ).also { shared = it }
            }
    }

    /** [KEY] in whatever preferences it is given. Reads no other key. */
    class PreferencesTextStore(private val preferences: SharedPreferences) : DebugTextStore {
        override fun read(): String? = preferences.getString(KEY, null)

        // commit, not apply: a shell that force-stops right after the broadcast must not lose it.
        override fun write(value: String?) {
            preferences.edit(commit = true) { if (value == null) remove(KEY) else putString(KEY, value) }
        }
    }
}
