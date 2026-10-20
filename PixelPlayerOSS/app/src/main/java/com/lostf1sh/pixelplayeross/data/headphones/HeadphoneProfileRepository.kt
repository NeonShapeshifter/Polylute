package com.lostf1sh.pixelplayeross.data.headphones

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class HeadphoneProfilesPayload(
    val schemaVersion: Int = HeadphoneProfilesCodec.CURRENT_SCHEMA_VERSION,
    val profiles: List<HeadphoneProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val dacAssociations: Map<String, String> = emptyMap(),
)

/**
 * Reads and writes the versioned `headphone_profiles_v1` payload.
 *
 * ## Schema history
 *
 * | Version | Phase | Change |
 * |---------|-------|--------|
 * | 1 | 8.2 | Headphone profiles with a ten-value `customBands` EQ state. |
 * | 2 | 8.3 | Adds the per-profile `safetyMax` policy. |
 * | 3 | 8.5 | Splits EQ into an explicit mode plus independent ten- and fifteen-band stores. |
 * | 4 | B30 | Adds the per-profile `dacEqPresets` and `dacEqSelections` (the DAC's own EQ, by headphones + DAC pair). Purely additive: a version 3 payload decodes to the same profiles with both empty, and nothing is rewritten until the first preset is saved. |
 *
 * ## Migration policy
 *
 * Migration is explicit and additive: [migrate] reads the *declared* version and applies the
 * documented transform for each older version rather than relying on deserialiser defaults to
 * happen to do the right thing. What it must never do is discard a user's data, so profile identity,
 * display names, the ten-band gains, the selected profile, the DAC association map and every Safety
 * Max calibration pass through untouched. The one field it re-derives is the ten-band curve, and
 * only because the pre-8.5 code derived it too — see [migrateEqualizer].
 *
 * Two failure modes are distinguished:
 *
 * - **Unparseable payload** — the pre-8.5 repository policy applies: the state resets to empty
 *   rather than half-loading a corrupt record.
 * - **Newer-than-known version** — the payload is decoded leniently and preserved as-is. Discarding
 *   a future version's profiles would destroy user data, so forward compatibility is preferred to
 *   a destructive downgrade. It is not written back in the newer format.
 */
internal object HeadphoneProfilesCodec {
    const val CURRENT_SCHEMA_VERSION = 4

    /** Version that introduced `mode` / `bands15`, and therefore the last one needing migration. */
    private const val GRAPHIC_MODES_VERSION = 3

    fun decode(json: Json, raw: String?): HeadphoneProfileState {
        if (raw.isNullOrBlank()) return HeadphoneProfileState()
        val payload = runCatching { json.decodeFromString<HeadphoneProfilesPayload>(raw) }
            .getOrNull() ?: return HeadphoneProfileState()
        return HeadphoneProfileCatalog.normalize(migrate(payload))
    }

    /**
     * Brings [payload] up to [CURRENT_SCHEMA_VERSION], preserving every field it already carries.
     */
    internal fun migrate(payload: HeadphoneProfilesPayload): HeadphoneProfileState {
        val profiles = if (payload.schemaVersion < GRAPHIC_MODES_VERSION) {
            payload.profiles.map { profile ->
                profile.copy(equalizer = migrateEqualizer(profile.equalizer))
            }
        } else {
            payload.profiles
        }

        return HeadphoneProfileState(
            profiles = profiles,
            selectedProfileId = payload.selectedProfileId,
            dacAssociations = payload.dacAssociations,
        )
    }

    /**
     * Converts a pre-8.5 equalizer state into the explicit ten-band store, reproducing the curve the
     * user was actually hearing rather than the bytes that happened to be on disk.
     *
     * Before Phase 8.5 the two fields had an asymmetric role: `presetName` chose the curve, and the
     * stored `customBands` were read *only* when it was `"custom"`. For every named preset the stored
     * bands were ignored outright — the old `EqualizerManager.restoreState` resolved
     * `EqualizerPreset.fromName(presetName).bandLevels` and discarded `customBands` entirely. So a
     * profile labelled `rock` whose `customBands` were left at all zeros really was playing the rock
     * curve, and copying those zeros into `bands10` would silently flatten an EQ the user had chosen.
     *
     * The migration therefore resolves the same field the old code resolved, and an unknown name
     * falls back to flat exactly as `fromName` did. Nothing is interpolated and nothing is invented:
     * both branches return values that already existed in this codebase before the phase began.
     *
     * Graphic 15 starts flat. Its first extended band (25 Hz) sits below the ten-band table's lowest
     * (31 Hz), so there is no honest curve to derive, and an undocumented interpolation would present
     * invented values as the user's choice.
     */
    private fun migrateEqualizer(equalizer: HeadphoneEqState): HeadphoneEqState = equalizer.copy(
        mode = HeadphoneEqMode.GRAPHIC_10,
        bands10 = if (equalizer.presetName == "custom") {
            equalizer.bands10
        } else {
            EqualizerPreset.fromName(equalizer.presetName).bandLevels
        },
        bands15 = List(HeadphoneEqState.BAND_COUNT_15) { 0 },
    )

    fun encode(json: Json, state: HeadphoneProfileState): String {
        val normalized = HeadphoneProfileCatalog.normalize(state)
        return json.encodeToString(
            HeadphoneProfilesPayload(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                profiles = normalized.profiles,
                selectedProfileId = normalized.selectedProfileId,
                dacAssociations = normalized.dacAssociations,
            )
        )
    }
}

@Singleton
/**
 * The result of an explicit per-profile equalizer write.
 *
 * [wasSelected] says whether the written profile is the one currently carrying audio, which is
 * what decides if the DSP-facing state should follow.
 */
data class EqualizerWrite(
    val state: HeadphoneEqState,
    val wasSelected: Boolean,
)

class HeadphoneProfileRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    @AppScope appScope: CoroutineScope,
) : DacEqPresetStore {
    private val profilesKey = stringPreferencesKey("headphone_profiles_v1")
    private val legacyEqMigratedKey = booleanPreferencesKey("headphone_eq_profile_authority_v1")

    override val state: StateFlow<HeadphoneProfileState> = dataStore.data
        .map { HeadphoneProfilesCodec.decode(json, it[profilesKey]) }
        .distinctUntilChanged()
        .stateIn(appScope, SharingStarted.Eagerly, HeadphoneProfileState())

    suspend fun currentState(): HeadphoneProfileState =
        dataStore.data.map { HeadphoneProfilesCodec.decode(json, it[profilesKey]) }.first()

    suspend fun createAndSelect(
        displayName: String,
        equalizer: HeadphoneEqState,
        dacProfileId: String? = null,
    ): HeadphoneProfile? {
        var created: HeadphoneProfile? = null
        dataStore.edit { preferences ->
            val before = HeadphoneProfilesCodec.decode(json, preferences[profilesKey])
            val after = HeadphoneProfileCatalog.createAndSelect(
                state = before,
                requestedName = displayName,
                equalizer = equalizer,
                nowEpochMs = System.currentTimeMillis(),
                dacProfileId = dacProfileId,
            )
            created = after.selectedProfile?.takeIf {
                it.profileId != before.selectedProfileId || after.profiles.size > before.profiles.size
            }
            preferences[profilesKey] = HeadphoneProfilesCodec.encode(json, after)
        }
        return created
    }

    suspend fun rename(profileId: String, displayName: String) {
        update { HeadphoneProfileCatalog.rename(it, profileId, displayName, System.currentTimeMillis()) }
    }

    suspend fun select(profileId: String, dacProfileId: String? = null): HeadphoneProfile? {
        var selected: HeadphoneProfile? = null
        update { current ->
            HeadphoneProfileCatalog.select(
                current,
                profileId,
                System.currentTimeMillis(),
                dacProfileId,
            ).also { selected = it.selectedProfile }
        }
        return selected
    }

    suspend fun restoreAssociation(dacProfileId: String): HeadphoneProfile? {
        val associated = HeadphoneProfileCatalog.associatedProfile(state.value, dacProfileId) ?: return null
        return select(associated.profileId, dacProfileId)
    }

    /**
     * Replaces the DAC→headphone association map, and only that map.
     *
     * Used by the one-shot reconciliation that re-keys associations still stored under a DAC
     * display name. It cannot touch a profile, an equalizer curve or a Safety Max calibration:
     * the transform it applies is confined to [HeadphoneProfileState.dacAssociations].
     */
    suspend fun replaceAssociations(associations: Map<String, String>) {
        update { current -> current.copy(dacAssociations = associations) }
    }

    suspend fun updateSelectedEqualizer(equalizer: HeadphoneEqState) {
        update {
            HeadphoneProfileCatalog.updateSelectedEqualizer(it, equalizer, System.currentTimeMillis())
        }
    }

    suspend fun updateSelectedEqualizer(
        transform: (HeadphoneEqState) -> HeadphoneEqState,
    ): HeadphoneEqState? {
        var updated: HeadphoneEqState? = null
        update { current ->
            val selected = current.selectedProfile ?: return@update current
            transform(selected.equalizer).normalized().also { updated = it }.let { equalizer ->
                HeadphoneProfileCatalog.updateSelectedEqualizer(
                    current,
                    equalizer,
                    System.currentTimeMillis(),
                )
            }
        }
        return updated
    }

    /**
     * Writes one profile's equalizer, named explicitly.
     *
     * The selected-profile overloads are the right shape for a control that acts on "the current
     * headphones". They are the wrong shape for the Equalizer screen, which is open on a profile
     * named in its route: the selection can move underneath it — `DacHeadphoneAssociationController`
     * re-selects an associated profile whenever the connected DAC's identity changes — and a
     * debounced band write that lands after that selects the wrong profile's record.
     *
     * Returns the written state and whether the target happened to be the selected profile, so the
     * caller can keep the applied-signal state in step without a second read.
     */
    suspend fun updateEqualizer(
        profileId: String,
        transform: (HeadphoneEqState) -> HeadphoneEqState,
    ): EqualizerWrite? {
        var write: EqualizerWrite? = null
        update { current ->
            val target = current.profiles.firstOrNull { it.profileId == profileId }
                ?: return@update current
            val updated = transform(target.equalizer).normalized()
            write = EqualizerWrite(
                state = updated,
                wasSelected = current.selectedProfileId == profileId,
            )
            HeadphoneProfileCatalog.updateEqualizer(current, profileId, updated, System.currentTimeMillis())
        }
        return write
    }

    /**
     * Atomically adopts the old global/effective EQ state only when no profile currently owns EQ.
     * The old keys are intentionally left unread thereafter so upgrades cannot lose a curve even
     * if process death lands between application startup and the first screen opening.
     */
    suspend fun migrateLegacyEqualizerIfNeeded(legacy: HeadphoneEqState) {
        dataStore.edit { preferences ->
            if (preferences[legacyEqMigratedKey] == true) return@edit
            val current = HeadphoneProfilesCodec.decode(json, preferences[profilesKey])
            val migrated = adoptLegacyEqualizerAuthority(
                current = current,
                legacy = legacy,
                nowEpochMs = System.currentTimeMillis(),
                migratedProfileId = MIGRATED_PROFILE_ID,
            )
            preferences[profilesKey] = HeadphoneProfilesCodec.encode(json, migrated)
            preferences[legacyEqMigratedKey] = true
        }
    }

    suspend fun calibrateSafetyMax(profileId: String, calibration: SafetyMaxCalibration) {
        update {
            HeadphoneProfileCatalog.calibrateSafetyMax(
                it,
                profileId,
                calibration,
                System.currentTimeMillis(),
            )
        }
    }

    suspend fun setSafetyMaxEnabled(profileId: String, enabled: Boolean) {
        update {
            HeadphoneProfileCatalog.setSafetyMaxEnabled(
                it,
                profileId,
                enabled,
                System.currentTimeMillis(),
            )
        }
    }

    suspend fun forget(profileId: String) {
        update { HeadphoneProfileCatalog.forget(it, profileId) }
    }

    // ── B30: the DAC's own EQ presets, owned by the headphones and made for a pair ──────────────────────────────

    /** Saves (or updates, within the pair) a DAC EQ preset of [headphoneId]; the saved preset, or null if the profile is gone or the name empty. */
    override suspend fun saveDacEqPreset(
        headphoneId: String,
        dac: DacEqPairDac,
        name: String,
        preGainTenthsDb: Int,
        accepted: List<DacEqFilterDef>,
        requested: List<DacEqFilterDef>?,
        presetId: String?,
    ): DacEqPreset? {
        var saved: DacEqPreset? = null
        update { current ->
            DacEqPresetCatalog.save(
                current, headphoneId, dac, name, preGainTenthsDb, accepted, requested, presetId, System.currentTimeMillis(),
            ).also { after ->
                saved = after.profiles.firstOrNull { it.profileId == headphoneId }?.let { DacEqPresetCatalog.selectionFor(it, dac.profileId) }
            }
        }
        return saved
    }

    override suspend fun selectDacEq(headphoneId: String, dacProfileId: String, presetId: String?, eqOn: Boolean) {
        update { DacEqPresetCatalog.select(it, headphoneId, dacProfileId, presetId, eqOn, System.currentTimeMillis()) }
    }

    override suspend fun renameDacEqPreset(headphoneId: String, dacProfileId: String, presetId: String, name: String) {
        update { DacEqPresetCatalog.rename(it, headphoneId, dacProfileId, presetId, name, System.currentTimeMillis()) }
    }

    override suspend fun deleteDacEqPreset(headphoneId: String, dacProfileId: String, presetId: String) {
        update { DacEqPresetCatalog.delete(it, headphoneId, dacProfileId, presetId, System.currentTimeMillis()) }
    }

    private suspend fun update(transform: (HeadphoneProfileState) -> HeadphoneProfileState) {
        dataStore.edit { preferences ->
            val current = HeadphoneProfilesCodec.decode(json, preferences[profilesKey])
            preferences[profilesKey] = HeadphoneProfilesCodec.encode(json, transform(current))
        }
    }

    private companion object {
        const val MIGRATED_PROFILE_ID = "headphone-migrated-eq"
    }
}

internal fun adoptLegacyEqualizerAuthority(
    current: HeadphoneProfileState,
    legacy: HeadphoneEqState,
    nowEpochMs: Long,
    migratedProfileId: String = "headphone-migrated-eq",
): HeadphoneProfileState = when {
    current.selectedProfile != null -> current
    current.profiles.any { it.profileId == migratedProfileId } ->
        HeadphoneProfileCatalog.select(current, migratedProfileId, nowEpochMs)
    else -> HeadphoneProfileCatalog.createAndSelect(
        state = current,
        requestedName = "Headphones",
        equalizer = legacy,
        nowEpochMs = nowEpochMs,
        profileId = migratedProfileId,
    )
}
