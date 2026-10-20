package com.lostf1sh.pixelplayeross.data.headphones

import java.util.UUID
import kotlinx.serialization.SerialName
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import kotlinx.serialization.Serializable

/** Which Graphic EQ band set a headphone profile is currently presenting. */
@Serializable
enum class HeadphoneEqMode {
    /** Ten independent filters, one octave apart — the default and simple mode. */
    GRAPHIC_10,

    /** Fifteen independent filters, two-thirds of an octave apart. */
    GRAPHIC_15,
}

@Serializable
data class HeadphoneEqState(
    val enabled: Boolean = false,
    val presetName: String = "flat",
    /**
     * Ten-band Graphic EQ gains in dB, one entry per [HeadphoneEqMode.GRAPHIC_10] filter.
     *
     * The serialised name stays `customBands` so profiles written before Phase 8.5 load without a
     * rewrite step and without any change to their values. Phase 8.5 only renamed the Kotlin
     * property to make it obvious that this is the ten-band store specifically.
     */
    @SerialName("customBands")
    val bands10: List<Int> = List(BAND_COUNT_10) { 0 },
    /** Which band set is presented and applied. Independent of the stored values. */
    val mode: HeadphoneEqMode = HeadphoneEqMode.GRAPHIC_10,
    /**
     * Fifteen-band Graphic EQ gains in dB, one entry per [HeadphoneEqMode.GRAPHIC_15] filter.
     *
     * Independent of [bands10]: switching modes and switching back restores whichever set was last
     * edited. New profiles start flat here rather than interpolating the ten-band curve, because an
     * undocumented interpolation would present invented values as if the user had chosen them.
     */
    val bands15: List<Int> = List(BAND_COUNT_15) { 0 },
    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Int = 0,
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Int = 0,
    val loudnessEnhancerEnabled: Boolean = false,
    val loudnessEnhancerStrength: Int = 0,
    val bassBoostDismissed: Boolean = false,
    val virtualizerDismissed: Boolean = false,
    val loudnessDismissed: Boolean = false,
) {
    fun normalized() = copy(
        presetName = presetName.trim().ifEmpty { "flat" },
        bands10 = bands10.normalizedGains(BAND_COUNT_10),
        bands15 = bands15.normalizedGains(BAND_COUNT_15),
        bassBoostStrength = bassBoostStrength.coerceIn(0, 1000),
        virtualizerStrength = virtualizerStrength.coerceIn(0, 1000),
        loudnessEnhancerStrength = loudnessEnhancerStrength.coerceIn(0, 1000),
    )

    /** The gains the active [mode] presents and applies. */
    val activeBands: List<Int>
        get() = if (mode == HeadphoneEqMode.GRAPHIC_15) bands15 else bands10

    /** Centre frequencies of the active [mode], in Hz, one per active band. */
    val activeFrequenciesHz: IntArray
        get() = com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqBands.frequenciesFor(
            activeBands.size
        )

    companion object {
        const val BAND_COUNT_10 = 10
        const val BAND_COUNT_15 = 15

        private fun List<Int>.normalizedGains(count: Int): List<Int> = take(count)
            .map { it.coerceIn(-15, 15) }
            .let { it + List(count - it.size) { 0 } }
    }
}

@Serializable
enum class SafetyVolumeBackendKind {
    ANDROID_MEDIA,
    USB_HARDWARE_VOLUME,
    FIXED_OUTPUT,
    UNKNOWN,
}

@Serializable
data class SafetyMaxCalibration(
    val outputContextId: String,
    val backendKind: SafetyVolumeBackendKind,
    val ceilingRaw: Int,
    val backendMinRaw: Int,
    val backendMaxRaw: Int,
    val calibratedAtEpochMs: Long,
) {
    val isUsable: Boolean
        get() = outputContextId.isNotBlank() && ceilingRaw > backendMinRaw &&
            ceilingRaw <= backendMaxRaw && backendMaxRaw > backendMinRaw

    val storageKey: String
        get() = storageKey(outputContextId, backendKind)

    companion object {
        /**
         * One DAC is reached through two backends with different raw scales — Android media volume
         * and its own Feature Unit — so the context alone cannot key a calibration: calibrating one
         * route used to overwrite the other (AUD-024).
         */
        fun storageKey(outputContextId: String, backendKind: SafetyVolumeBackendKind): String =
            "$outputContextId|${backendKind.name}"
    }
}

@Serializable
data class HeadphoneSafetyPolicy(
    val enabled: Boolean = true,
    /** Keyed by [SafetyMaxCalibration.storageKey]: one calibration per output context *and* backend. */
    val calibrations: Map<String, SafetyMaxCalibration> = emptyMap(),
) {
    fun calibrationFor(outputContextId: String?, backendKind: SafetyVolumeBackendKind): SafetyMaxCalibration? =
        outputContextId?.let { calibrations[SafetyMaxCalibration.storageKey(it, backendKind)] }
}

@Serializable
data class HeadphoneProfile(
    val profileId: String,
    val displayName: String,
    val equalizer: HeadphoneEqState,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** Added in Phase 8.3; the default keeps Phase 8.2 payloads backward compatible. */
    val safetyMax: HeadphoneSafetyPolicy = HeadphoneSafetyPolicy(),
    /**
     * B30 (N-06): the presets of the DAC's own EQ that belong to these headphones. Each records the DAC it was made for (the pair);
     * see [DacEqPresetCatalog]. Not the software EQ's presets, and nothing is ever converted between the two. The default keeps
     * payloads from before B30 loading unchanged (schema 4).
     */
    val dacEqPresets: List<DacEqPreset> = emptyList(),
    /** B30: which preset (or "EQ off") these headphones had selected on each DAC, by DAC profile id: what a recognised pair restores. */
    val dacEqSelections: Map<String, DacEqSelection> = emptyMap(),
)

@Serializable
data class HeadphoneProfileState(
    val profiles: List<HeadphoneProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val dacAssociations: Map<String, String> = emptyMap(),
) {
    val selectedProfile: HeadphoneProfile?
        get() = profiles.firstOrNull { it.profileId == selectedProfileId }
}

object HeadphoneProfileCatalog {
    fun normalize(state: HeadphoneProfileState): HeadphoneProfileState {
        val profiles = state.profiles
            .filter { it.profileId.isNotBlank() && it.displayName.isNotBlank() }
            .distinctBy { it.profileId }
            .map { profile ->
                profile.copy(
                    equalizer = profile.equalizer.normalized(),
                    safetyMax = profile.safetyMax.copy(
                        // Re-keyed from each calibration's own context and backend, which also
                        // migrates entries stored under the pre-AUD-024 context-only key. Should
                        // both forms exist for one backend, the newer calibration wins.
                        calibrations = profile.safetyMax.calibrations.values
                            .filter(SafetyMaxCalibration::isUsable)
                            .sortedBy(SafetyMaxCalibration::calibratedAtEpochMs)
                            .associateBy(SafetyMaxCalibration::storageKey),
                    ),
                )
            }
        val ids = profiles.mapTo(mutableSetOf()) { it.profileId }
        return state.copy(
            profiles = profiles,
            selectedProfileId = state.selectedProfileId?.takeIf(ids::contains),
            dacAssociations = state.dacAssociations.filter { (dacId, headphoneId) ->
                dacId.isNotBlank() && headphoneId in ids
            },
        )
    }

    fun createAndSelect(
        state: HeadphoneProfileState,
        requestedName: String,
        equalizer: HeadphoneEqState,
        nowEpochMs: Long,
        profileId: String = "headphone-${UUID.randomUUID()}",
        dacProfileId: String? = null,
    ): HeadphoneProfileState {
        val name = requestedName.trim()
        if (name.isEmpty() || profileId.isBlank() || state.profiles.any { it.profileId == profileId }) {
            return normalize(state)
        }
        val profile = HeadphoneProfile(
            profileId = profileId,
            displayName = name,
            equalizer = equalizer.normalized(),
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        return normalize(
            state.copy(
                profiles = state.profiles + profile,
                selectedProfileId = profileId,
                dacAssociations = state.dacAssociations.withAssociation(dacProfileId, profileId),
            )
        )
    }

    fun rename(
        state: HeadphoneProfileState,
        profileId: String,
        requestedName: String,
        nowEpochMs: Long,
    ): HeadphoneProfileState {
        val name = requestedName.trim()
        if (name.isEmpty()) return normalize(state)
        return normalize(
            state.copy(
                profiles = state.profiles.map { profile ->
                    if (profile.profileId == profileId) {
                        profile.copy(displayName = name, updatedAtEpochMs = nowEpochMs)
                    } else {
                        profile
                    }
                }
            )
        )
    }

    fun select(
        state: HeadphoneProfileState,
        profileId: String,
        nowEpochMs: Long,
        dacProfileId: String? = null,
    ): HeadphoneProfileState {
        if (state.profiles.none { it.profileId == profileId }) return normalize(state)
        return normalize(
            state.copy(
                selectedProfileId = profileId,
                profiles = state.profiles.map { profile ->
                    if (profile.profileId == profileId) profile.copy(updatedAtEpochMs = nowEpochMs)
                    else profile
                },
                dacAssociations = state.dacAssociations.withAssociation(dacProfileId, profileId),
            )
        )
    }

    /** The same write as [updateSelectedEqualizer], addressed to a named profile. */
    fun updateEqualizer(
        state: HeadphoneProfileState,
        profileId: String,
        equalizer: HeadphoneEqState,
        nowEpochMs: Long,
    ): HeadphoneProfileState {
        if (state.profiles.none { it.profileId == profileId }) return normalize(state)
        return normalize(
            state.copy(
                profiles = state.profiles.map { profile ->
                    if (profile.profileId == profileId) {
                        profile.copy(equalizer = equalizer.normalized(), updatedAtEpochMs = nowEpochMs)
                    } else {
                        profile
                    }
                }
            )
        )
    }

    fun updateSelectedEqualizer(
        state: HeadphoneProfileState,
        equalizer: HeadphoneEqState,
        nowEpochMs: Long,
    ): HeadphoneProfileState {
        val selected = state.selectedProfileId ?: return normalize(state)
        return normalize(
            state.copy(
                profiles = state.profiles.map { profile ->
                    if (profile.profileId == selected) {
                        profile.copy(equalizer = equalizer.normalized(), updatedAtEpochMs = nowEpochMs)
                    } else {
                        profile
                    }
                }
            )
        )
    }

    fun calibrateSafetyMax(
        state: HeadphoneProfileState,
        profileId: String,
        calibration: SafetyMaxCalibration,
        nowEpochMs: Long,
    ): HeadphoneProfileState {
        if (!calibration.isUsable || state.profiles.none { it.profileId == profileId }) {
            return normalize(state)
        }
        return normalize(
            state.copy(
                profiles = state.profiles.map { profile ->
                    if (profile.profileId == profileId) {
                        profile.copy(
                            safetyMax = profile.safetyMax.copy(
                                enabled = true,
                                calibrations = profile.safetyMax.calibrations +
                                    (calibration.storageKey to calibration),
                            ),
                            updatedAtEpochMs = nowEpochMs,
                        )
                    } else profile
                },
            )
        )
    }

    fun setSafetyMaxEnabled(
        state: HeadphoneProfileState,
        profileId: String,
        enabled: Boolean,
        nowEpochMs: Long,
    ): HeadphoneProfileState = normalize(
        state.copy(
            profiles = state.profiles.map { profile ->
                if (profile.profileId == profileId) {
                    profile.copy(
                        safetyMax = profile.safetyMax.copy(enabled = enabled),
                        updatedAtEpochMs = nowEpochMs,
                    )
                } else profile
            },
        )
    )

    fun forget(state: HeadphoneProfileState, profileId: String): HeadphoneProfileState = normalize(
        state.copy(
            profiles = state.profiles.filterNot { it.profileId == profileId },
            selectedProfileId = state.selectedProfileId.takeUnless { it == profileId },
            dacAssociations = state.dacAssociations.filterValues { it != profileId },
        )
    )

    fun associatedProfile(state: HeadphoneProfileState, dacProfileId: String): HeadphoneProfile? =
        state.dacAssociations[dacProfileId]?.let { profileId ->
            state.profiles.firstOrNull { it.profileId == profileId }
        }

    /**
     * What happened to one key of the association map when it was checked against the DAC
     * profiles that exist.
     */
    enum class AssociationKeyKind {
        /** The key is a DAC profile id. Nothing to do. */
        PROFILE_ID,

        /** The key was a DAC display name that matched exactly one profile. Re-keyed. */
        MIGRATED,

        /**
         * The key was a display name matching more than one DAC profile. Left as it was: binding it
         * to either record would be a guess, and a guess here silently attaches a listener's
         * equalizer curve to the wrong pair of headphones.
         */
        AMBIGUOUS,

        /** The key matches no DAC profile id and no display name. Left as it was. */
        UNRESOLVED,
    }

    data class AssociationKeyOutcome(
        val key: String,
        val kind: AssociationKeyKind,
        val resolvedDacProfileId: String? = null,
    )

    data class AssociationMigration(
        val state: HeadphoneProfileState,
        val outcomes: List<AssociationKeyOutcome>,
    ) {
        val migrated: List<AssociationKeyOutcome> get() = outcomes.filter { it.kind == AssociationKeyKind.MIGRATED }
        val ambiguous: List<AssociationKeyOutcome> get() = outcomes.filter { it.kind == AssociationKeyKind.AMBIGUOUS }
        val unresolved: List<AssociationKeyOutcome> get() = outcomes.filter { it.kind == AssociationKeyKind.UNRESOLVED }
        val needsAttention: Boolean get() = ambiguous.isNotEmpty() || unresolved.isNotEmpty()
    }

    /**
     * Re-keys any association stored under a DAC **display name** to the DAC's profile id.
     *
     * The association has always been documented and written as "DAC profile id → headphone profile
     * id", and nothing in the current tree writes a name key. This exists because the invariant was
     * never enforced at the read side, so a payload that acquired a name key — from an older build,
     * a hand-edited backup, or a future writer that reaches for the friendlier-looking key — would
     * simply stop matching, and the remembered headphones would appear to have been forgotten.
     *
     * Three rules, in order of how much they assume:
     *
     * 1. A key that **is** a known profile id is left alone. This is the normal case and it is a
     *    pure no-op.
     * 2. A key that is not a profile id but **matches exactly one** DAC's display name is re-keyed
     *    to that DAC's profile id. Unambiguous, so it is safe to act on.
     * 3. A key that matches **more than one** DAC's display name, or nothing at all, is left exactly
     *    as it was and reported. Ambiguity is not resolved by picking the first match — that would
     *    be indistinguishable, afterwards, from having got it right.
     *
     * Nothing is deleted in any branch, so a key this cannot resolve is still there for an explicit
     * re-association, and the headphone profile it points at is untouched either way.
     */
    fun reconcileDacAssociations(
        state: HeadphoneProfileState,
        dacs: List<DacProfile>,
    ): AssociationMigration {
        if (state.dacAssociations.isEmpty()) {
            return AssociationMigration(state, emptyList())
        }

        val knownIds = dacs.map { it.profileId }.toSet()
        val profileIdsByName = dacs.groupBy { it.displayName }

        val outcomes = mutableListOf<AssociationKeyOutcome>()
        val rebuilt = LinkedHashMap<String, String>(state.dacAssociations.size)

        state.dacAssociations.forEach { (key, headphoneProfileId) ->
            when {
                key in knownIds -> {
                    outcomes += AssociationKeyOutcome(key, AssociationKeyKind.PROFILE_ID, key)
                    rebuilt[key] = headphoneProfileId
                }

                profileIdsByName[key].orEmpty().size == 1 -> {
                    val resolved = profileIdsByName.getValue(key).single().profileId
                    outcomes += AssociationKeyOutcome(key, AssociationKeyKind.MIGRATED, resolved)
                    // A re-keyed entry never overwrites a real one: if the profile id is already
                    // present as a key, that association is the authoritative one and was written
                    // by a build that knew what it was doing.
                    rebuilt.putIfAbsent(resolved, headphoneProfileId)
                }

                profileIdsByName[key].orEmpty().size > 1 -> {
                    outcomes += AssociationKeyOutcome(key, AssociationKeyKind.AMBIGUOUS)
                    rebuilt[key] = headphoneProfileId
                }

                else -> {
                    outcomes += AssociationKeyOutcome(key, AssociationKeyKind.UNRESOLVED)
                    rebuilt[key] = headphoneProfileId
                }
            }
        }

        return AssociationMigration(state.copy(dacAssociations = rebuilt), outcomes)
    }

    private fun Map<String, String>.withAssociation(dacProfileId: String?, profileId: String) =
        if (dacProfileId.isNullOrBlank()) this else this + (dacProfileId to profileId)
}
