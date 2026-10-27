package com.lostf1sh.pixelplayeross.data.dac.fiio

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Where the listener's yes is kept, per model of DAC. */
interface DacEqConsentStore {
    /** When (wall clock) the listener allowed writes to [profileId], or null. */
    fun read(profileId: String): Long?
    fun write(profileId: String, atMs: Long?)
}

/**
 * B40-F2: the listener's explicit yes to "Polylute may change this DAC's USER 3 slot".
 *
 * The write path was built in B30-B31 behind two keys: the profile marked *verified on this app's hardware* (false for every row until a
 * reviewer's run passes) and a debug-only override. A listener with a Melody had neither, so the screen could read the DAC and never write
 * it. This is the third key, and it is **the listener's**: given on the DAC's screen after it says what will happen, kept per model, and
 * withdrawn from the same screen.
 *
 * It lifts one requirement of the gate, "the profile is verified", for a write the **listener asks for** ([WriteInitiator.USER_ACTION]).
 * Everything else the gate asks stays: a complete and fresh read, a backup of the DAC first, only the app's own slot, the limits, no state
 * above 0 dB net, the steps that need the music paused, a read-back after each write with the backup written back on a mismatch. It does
 * not let the reviewer-only steps through, and it does not let anything automatic through.
 *
 * Process-wide and persisted (release and debug alike), so the yes survives a restart; nothing is written to a DAC by giving it.
 */
class DacEqWriteConsent(private val store: DacEqConsentStore) {
    @Volatile
    private var cache: Map<String, Long?> = emptyMap()

    fun isGiven(profileId: String): Boolean {
        cache[profileId]?.let { return true }
        if (cache.containsKey(profileId)) return false
        val at = store.read(profileId)
        cache = cache + (profileId to at)
        return at != null
    }

    fun give(profileId: String, atMs: Long) {
        store.write(profileId, atMs)
        cache = cache + (profileId to atMs)
    }

    fun withdraw(profileId: String) {
        store.write(profileId, null)
        cache = cache + (profileId to null)
    }

    companion object {
        const val PREFERENCES = "dac_eq_write_consent"

        @Volatile
        private var shared: DacEqWriteConsent? = null

        /** The process-wide consent. The first call loads the preference file: call it off the main thread. */
        fun shared(context: Context): DacEqWriteConsent =
            shared ?: synchronized(this) {
                shared ?: DacEqWriteConsent(
                    PreferencesConsentStore(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)),
                ).also { shared = it }
            }
    }

    /** One key per profile id in whatever preferences it is given; reads no other key. */
    class PreferencesConsentStore(private val preferences: SharedPreferences) : DacEqConsentStore {
        override fun read(profileId: String): Long? = preferences.getLong(profileId, NONE).takeIf { it != NONE }

        // commit, not apply: the yes is not lost to a process that is killed right after it
        override fun write(profileId: String, atMs: Long?) {
            preferences.edit(commit = true) { if (atMs == null) remove(profileId) else putLong(profileId, atMs) }
        }

        private companion object {
            const val NONE = -1L
        }
    }
}
