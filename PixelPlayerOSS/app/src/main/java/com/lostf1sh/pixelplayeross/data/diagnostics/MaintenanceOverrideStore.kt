package com.lostf1sh.pixelplayeross.data.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence for [MaintenanceOverride]s.
 *
 * ## Why this is separate from normal configuration
 *
 * An override must never mutate the setting it is overriding. Turning on
 * [MaintenanceOverride.FORCE_ANDROID_AUDIO] must not touch the user's equalizer, speed, ReplayGain
 * or crossfade choices; removing it must restore exactly the state those settings already
 * describe. Keeping the overrides in their own store, keyed only by override id, is what makes
 * that structural rather than a promise — there is no code path from here into
 * `UserPreferencesRepository`.
 *
 * ## Persistence semantics, stated explicitly
 *
 * Overrides **do** survive process death and reboot. They are a deliberate act performed inside an
 * authenticated Maintenance session, and silently forgetting them on reboot would produce a
 * diagnostic session that reports different behaviour than it was configured for. The counters
 * that must *not* survive a reboot — the Safety Max temporary override and its triple-press
 * progress — live in `SafetyVolumeController`'s memory only and are unaffected by this store.
 *
 * Removal is a delete of a single id. There is no shadow copy of a previous normal value to
 * restore, because no normal value was ever written.
 */
@Singleton
class MaintenanceOverrideStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private val _state = MutableStateFlow(read())
    val state: StateFlow<MaintenanceOverrides> = _state.asStateFlow()

    /** Synchronous read for callers on the broadcast path that cannot await a flow. */
    fun current(): MaintenanceOverrides = _state.value

    fun isActive(override: MaintenanceOverride): Boolean = override.id in _state.value.activeIds

    fun setActive(override: MaintenanceOverride, active: Boolean) {
        val current = _state.value
        val updated = current.applied(override, active)
        if (updated == current) return
        persist(updated)
    }

    /** "Clear all maintenance overrides" — one write, one state emission. */
    fun clearAll() {
        if (_state.value.activeIds.isEmpty()) return
        preferences.edit().remove(KEY_ACTIVE_IDS).apply()
        _state.value = MaintenanceOverrides()
    }

    private fun persist(state: MaintenanceOverrides) {
        preferences.edit().putStringSet(KEY_ACTIVE_IDS, state.activeIds).apply()
        _state.value = state
    }

    /** Reads persisted state, dropping ids this build no longer defines. */
    private fun read(): MaintenanceOverrides =
        MaintenanceOverrides.fromStoredIds(preferences.getStringSet(KEY_ACTIVE_IDS, emptySet()))

    private companion object {
        const val PREFERENCES_NAME = "maintenance_overrides"
        const val KEY_ACTIVE_IDS = "active_override_ids"
    }
}
