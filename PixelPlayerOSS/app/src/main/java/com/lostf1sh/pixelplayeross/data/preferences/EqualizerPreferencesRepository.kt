package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EqualizerPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json
) {
    private object Keys {
        val EQUALIZER_ENABLED = booleanPreferencesKey("equalizer_enabled")
        val EQUALIZER_PRESET = stringPreferencesKey("equalizer_preset")
        val EQUALIZER_CUSTOM_BANDS = stringPreferencesKey("equalizer_custom_bands")
        val BASS_BOOST_STRENGTH = intPreferencesKey("bass_boost_strength")
        val VIRTUALIZER_STRENGTH = intPreferencesKey("virtualizer_strength")
        val BASS_BOOST_ENABLED = booleanPreferencesKey("bass_boost_enabled")
        val VIRTUALIZER_ENABLED = booleanPreferencesKey("virtualizer_enabled")
        val LOUDNESS_ENHANCER_ENABLED = booleanPreferencesKey("loudness_enhancer_enabled")
        val LOUDNESS_ENHANCER_STRENGTH = intPreferencesKey("loudness_enhancer_strength")
        val BASS_BOOST_DISMISSED = booleanPreferencesKey("bass_boost_dismissed")
        val VIRTUALIZER_DISMISSED = booleanPreferencesKey("virtualizer_dismissed")
        val LOUDNESS_DISMISSED = booleanPreferencesKey("loudness_dismissed")
        val VIEW_MODE = stringPreferencesKey("equalizer_view_mode")
        val CUSTOM_PRESETS = stringPreferencesKey("custom_presets_json")
        val PINNED_PRESETS = stringPreferencesKey("pinned_presets_json")

        /**
         * Phase 8.5. The ten-band store keeps its historical key so pre-8.5 bridge values are
         * loaded in place; these two carry the newly added fields.
         */
        val EQUALIZER_MODE = stringPreferencesKey("equalizer_mode")
        val EQUALIZER_BANDS_15 = stringPreferencesKey("equalizer_bands_15")
    }

    private fun decodeBands(raw: String?, count: Int): List<Int> = raw?.let { stored ->
        runCatching { json.decodeFromString<List<Int>>(stored) }.getOrNull()
    }.orEmpty().let { decoded ->
        when {
            decoded.size >= count -> decoded.take(count)
            else -> decoded + List(count - decoded.size) { 0 }
        }
    }

    private fun decodeMode(raw: String?): HeadphoneEqMode = raw?.let { stored ->
        runCatching { HeadphoneEqMode.valueOf(stored) }.getOrNull()
    } ?: HeadphoneEqMode.GRAPHIC_10

    val equalizerViewModeFlow: Flow<UserPreferencesRepository.EqualizerViewMode> = dataStore.data.map { preferences ->
        val modeString = preferences[Keys.VIEW_MODE]
        if (modeString != null) {
            try {
                UserPreferencesRepository.EqualizerViewMode.valueOf(modeString)
            } catch (_: Exception) {
                UserPreferencesRepository.EqualizerViewMode.SLIDERS
            }
        } else {
            val isGraph = preferences[booleanPreferencesKey("is_graph_view")] ?: false
            if (isGraph) UserPreferencesRepository.EqualizerViewMode.GRAPH else UserPreferencesRepository.EqualizerViewMode.SLIDERS
        }
    }

    val customPresetsFlow: Flow<List<EqualizerPreset>> = dataStore.data.map { preferences ->
        val jsonString = preferences[Keys.CUSTOM_PRESETS]
        if (jsonString != null) {
            try {
                json.decodeFromString<List<EqualizerPreset>>(jsonString)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    val pinnedPresetsFlow: Flow<List<String>> = dataStore.data.map { preferences ->
        val jsonString = preferences[Keys.PINNED_PRESETS]
        if (jsonString != null) {
            try {
                json.decodeFromString<List<String>>(jsonString)
            } catch (_: Exception) {
                EqualizerPreset.ALL_PRESETS.map { it.name }
            }
        } else {
            EqualizerPreset.ALL_PRESETS.map { it.name }
        }
    }

    /** One-time upgrade input. Runtime EQ never observes or writes these legacy keys. */
    suspend fun readLegacyStateForMigration(): HeadphoneEqState = dataStore.data.map { preferences ->
        HeadphoneEqState(
            enabled = preferences[Keys.EQUALIZER_ENABLED] ?: false,
            presetName = preferences[Keys.EQUALIZER_PRESET] ?: "flat",
            bands10 = decodeBands(preferences[Keys.EQUALIZER_CUSTOM_BANDS], HeadphoneEqState.BAND_COUNT_10),
            mode = decodeMode(preferences[Keys.EQUALIZER_MODE]),
            bands15 = decodeBands(preferences[Keys.EQUALIZER_BANDS_15], HeadphoneEqState.BAND_COUNT_15),
            bassBoostEnabled = preferences[Keys.BASS_BOOST_ENABLED] ?: false,
            bassBoostStrength = preferences[Keys.BASS_BOOST_STRENGTH] ?: 0,
            virtualizerEnabled = preferences[Keys.VIRTUALIZER_ENABLED] ?: false,
            virtualizerStrength = preferences[Keys.VIRTUALIZER_STRENGTH] ?: 0,
            loudnessEnhancerEnabled = preferences[Keys.LOUDNESS_ENHANCER_ENABLED] ?: false,
            loudnessEnhancerStrength = preferences[Keys.LOUDNESS_ENHANCER_STRENGTH] ?: 0,
            bassBoostDismissed = preferences[Keys.BASS_BOOST_DISMISSED] ?: false,
            virtualizerDismissed = preferences[Keys.VIRTUALIZER_DISMISSED] ?: false,
            loudnessDismissed = preferences[Keys.LOUDNESS_DISMISSED] ?: false,
        ).normalized()
    }.first()

    suspend fun setEqualizerViewMode(mode: UserPreferencesRepository.EqualizerViewMode) =
        dataStore.edit { preferences ->
            preferences[Keys.VIEW_MODE] = mode.name
        }

    suspend fun setPinnedPresets(presetNames: List<String>) =
        dataStore.edit { preferences ->
            preferences[Keys.PINNED_PRESETS] = json.encodeToString(presetNames)
        }

    suspend fun saveCustomPreset(preset: EqualizerPreset) {
        val current = customPresetsFlow.first().toMutableList()
        current.removeAll { it.name == preset.name }
        current.add(preset)
        dataStore.edit { preferences ->
            preferences[Keys.CUSTOM_PRESETS] = json.encodeToString(current)
        }
    }

    suspend fun deleteCustomPreset(presetName: String) {
        val current = customPresetsFlow.first().toMutableList()
        current.removeAll { it.name == presetName }
        dataStore.edit { preferences ->
            preferences[Keys.CUSTOM_PRESETS] = json.encodeToString(current)
        }

        val pinned = pinnedPresetsFlow.first().toMutableList()
        if (pinned.remove(presetName)) {
            setPinnedPresets(pinned)
        }
    }

    suspend fun renameCustomPreset(oldName: String, newName: String) {
        val current = customPresetsFlow.first().toMutableList()
        val index = current.indexOfFirst { it.name == oldName }
        if (index == -1) return

        current[index] = current[index].copy(name = newName, displayName = newName)
        dataStore.edit { preferences ->
            preferences[Keys.CUSTOM_PRESETS] = json.encodeToString(current)
        }

        val pinned = pinnedPresetsFlow.first().toMutableList()
        val pinnedIndex = pinned.indexOf(oldName)
        if (pinnedIndex != -1) {
            pinned[pinnedIndex] = newName
            setPinnedPresets(pinned)
        }

    }

    suspend fun updateCustomPresetBands(presetName: String, bandLevels: List<Int>) {
        val current = customPresetsFlow.first().toMutableList()
        val index = current.indexOfFirst { it.name == presetName }
        if (index == -1) return

        current[index] = current[index].copy(bandLevels = bandLevels)
        dataStore.edit { preferences ->
            preferences[Keys.CUSTOM_PRESETS] = json.encodeToString(current)
        }
    }
}
