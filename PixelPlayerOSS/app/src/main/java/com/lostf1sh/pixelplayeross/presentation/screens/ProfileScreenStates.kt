package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.DacEqCompat
import com.lostf1sh.pixelplayeross.data.headphones.DacEqFit
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPreset
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfile
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxCalibrationResult
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapAudioRouteKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerOutputContext
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerOutputState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FeatureStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.equalizerStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SafetyMaxOutputState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.safetyMaxOutputState

/*
 * B21: what the EQ, the headphone profile, the Safety Max page and the DAC profile draw, and what they can do.
 *
 * Each screen has the same three parts the Settings pages have: a state it draws (built here, from what its
 * ViewModels publish, by pure functions the tests call), an actions interface it calls (implemented in
 * `ProfileActionsWiring.kt`), and a composable that takes only those two. The screens' public entry points
 * (`EqualizerScreen`, `HeadphoneProfileScreen`, …) are now the thin hosts that join them.
 */

// ── Equalizer ────────────────────────────────────────────────────────────────────────────────────────

/**
 * What the equalizer draws: its curve and presets, what the output does with them now, and what is applied.
 *
 * B22: [status] is the one status line (`AudioFeatureStatus`) for the profile being edited, and [profileName] /
 * [dacName] are what the title says it is editing ("TYGR · con SNOWSKY Melody"). [output] is the older three-way
 * answer the profile page still draws; both come from the same [audio].
 */
@Immutable
data class EqualizerScreenState(
    val eq: EqualizerUiState,
    /** What the EQ is doing on the current output: applied, off, or skipped and why. */
    val output: EqualizerOutputState,
    val audio: AudioStatus,
    val status: FeatureStatus = FeatureStatus.Applied,
    val profileName: String? = null,
    val dacName: String? = null,
    /** Where the sound goes when no USB DAC carries it: the route's name on the status row. */
    val outputKind: DapAudioRouteKind = DapAudioRouteKind.ANDROID_AUDIO,
) {
    companion object {
        fun from(
            eq: EqualizerUiState,
            outputContext: EqualizerOutputContext,
            audio: AudioStatus,
            profile: HeadphoneProfileUiItem? = null,
            outputKind: DapAudioRouteKind = DapAudioRouteKind.ANDROID_AUDIO,
        ) = EqualizerScreenState(
            eq = eq,
            output = outputContext.state(eq.isEnabled),
            audio = audio,
            status = audio.equalizerStatus(outputContext.isProfileSelected, eq.isEnabled),
            profileName = profile?.profile?.displayName,
            dacName = profile?.associatedDacName,
            outputKind = outputKind,
        )
    }
}

/** Everything the equalizer can do. */
interface EqualizerScreenActions {
    fun onEnabledChange(enabled: Boolean)
    fun onModeChange(mode: HeadphoneEqMode)
    fun onSelectPreset(preset: EqualizerPreset)
    fun onTogglePinPreset(name: String)
    fun onDeleteCustomPreset(preset: EqualizerPreset)
    fun onRenameCustomPreset(oldName: String, newName: String)
    fun onSaveCurrentAsCustomPreset(name: String)
    fun onUpdateCustomPresetBands(presetName: String)
    fun onBandLevelChange(index: Int, level: Int)
    fun onResetBandsToFlat()
    /** Leads to the Pure Direct switch, which lives on the Audio page. */
    fun onOpenPurityLock()
}

// ── Headphone profile ────────────────────────────────────────────────────────────────────────────────

/**
 * What the profile page says about its EQ: the saved mode and, when there is anything to add, what it does now.
 *
 * With the EQ off the effect is "off" too, and said once ([output] is null): the page used to print
 * "Saved: Off · OFF" (B21, the doubled state).
 */
@Immutable
data class EqualizerSummary(
    @StringRes val modeRes: Int,
    val output: EqualizerOutputState?,
)

/** The saved mode (and the effect on the current output), the way the profile page's EQ row prints it. */
fun equalizerSummary(eq: HeadphoneEqState, output: EqualizerOutputState): EqualizerSummary = EqualizerSummary(
    modeRes = when {
        !eq.enabled -> R.string.headphone_eq_mode_off
        eq.mode == HeadphoneEqMode.GRAPHIC_15 -> R.string.headphone_eq_mode_15
        else -> R.string.headphone_eq_mode_10
    },
    output = output.takeIf { eq.enabled },
)

/** The Safety Max card on the profile page, read for the output in use. */
@Immutable
data class SafetyMaxCardState(
    val enabled: Boolean,
    /** The saved ceiling for the output in use; null when none is stored for it. */
    val ceilingRaw: Int?,
    /** A ceiling is stored, but for another output. */
    val hasOtherOutputCalibration: Boolean,
    val outputState: SafetyMaxOutputState,
    val backendKind: SafetyVolumeBackendKind,
) {
    companion object {
        fun of(profile: HeadphoneProfile, volume: SafetyVolumeState): SafetyMaxCardState {
            val calibration = profile.safetyMax.calibrationFor(volume.backend.outputContextId, volume.backend.kind)
            return SafetyMaxCardState(
                enabled = profile.safetyMax.enabled,
                ceilingRaw = calibration?.ceilingRaw,
                hasOtherOutputCalibration = profile.safetyMax.calibrations.isNotEmpty(),
                outputState = safetyMaxOutputState(profile, volume),
                backendKind = volume.backend.kind,
            )
        }
    }
}

/**
 * One DAC EQ preset of these headphones as the profile page lists it (B30-P3): the preset, whether it was made for the DAC in use,
 * and, for one made for another DAC, how it fits the one in use (null when no DAC the app can ask is in use).
 */
@Immutable
data class DacEqPresetRow(
    val preset: DacEqPreset,
    val forCurrentDac: Boolean,
    val fit: DacEqFit?,
)

/** What one headphone profile's page draws. [item] is null when the profile no longer exists. */
@Immutable
data class HeadphoneProfileScreenState(
    val item: HeadphoneProfileUiItem?,
    /** The EQ's own state (effects, saved presets), for the Bass boost / Virtualizer / Loudness rows. */
    val eq: EqualizerUiState,
    val eqSummary: EqualizerSummary?,
    /** The preset the "Preset" row names (only drawn while the profile's EQ is on). */
    val presetForRow: EqualizerPreset?,
    val safety: SafetyMaxCardState?,
    /** B30: the DAC EQ presets that belong to these headphones, found here and reused by choice. Empty when there are none. */
    val dacEqPresets: List<DacEqPresetRow> = emptyList(),
) {
    companion object {
        fun from(
            profileId: String,
            profiles: HeadphoneProfilesUiState,
            eq: EqualizerUiState,
            audio: AudioStatus,
            volume: SafetyVolumeState,
        ): HeadphoneProfileScreenState {
            val item = profiles.profiles.firstOrNull { it.profile.profileId == profileId }
                ?: return HeadphoneProfileScreenState(null, eq, null, null, null)
            val profile = item.profile
            val output = audio.equalizer(isProfileSelected = item.isSelected, enabled = profile.equalizer.enabled)
            val preset = if (profile.equalizer.mode == HeadphoneEqMode.GRAPHIC_15) {
                EqualizerPreset.custom(profile.equalizer.bands15)
            } else {
                eq.customPresets.firstOrNull { it.name == profile.equalizer.presetName }
                    ?: EqualizerPreset.fromName(profile.equalizer.presetName)
            }
            return HeadphoneProfileScreenState(
                item = item,
                eq = eq,
                eqSummary = equalizerSummary(profile.equalizer, output),
                presetForRow = preset,
                safety = SafetyMaxCardState.of(profile, volume),
                dacEqPresets = profile.dacEqPresets
                    .sortedWith(compareBy({ it.dacProfileId != profiles.currentDacEq?.dacProfileId }, { it.name.lowercase() }))
                    .map { preset ->
                        val current = profiles.currentDacEq
                        val forCurrent = current != null && preset.dacProfileId == current.dacProfileId
                        DacEqPresetRow(
                            preset = preset,
                            forCurrentDac = forCurrent,
                            fit = if (current != null && !forCurrent) {
                                DacEqCompat.fit(preset, current.capability, current.canBeWritten)
                            } else {
                                null
                            },
                        )
                    },
            )
        }
    }
}

/** Everything a headphone profile's page can do. */
interface HeadphoneProfileScreenActions {
    fun onBack()
    /** Makes it the selected profile. */
    fun onSelect(profileId: String)
    fun onRename(profileId: String, name: String)
    /** Forgets it, and goes back. */
    fun onForget(profileId: String)
    /** Selects it (only the selected profile's curve reaches the DSP), then opens its equalizer. */
    fun onOpenEqualizer(profileId: String)
    /** Selects it, then opens its Safety Max page. */
    fun onOpenSafetyMax(profileId: String)
    fun onBassBoostEnabledChange(enabled: Boolean)
    fun onBassBoostStrengthChange(strength: Int)
    fun onVirtualizerEnabledChange(enabled: Boolean)
    fun onVirtualizerStrengthChange(strength: Int)
    fun onLoudnessEnhancerEnabledChange(enabled: Boolean)
    fun onLoudnessEnhancerStrengthChange(strength: Int)
}

/** The known headphones list. */
@Immutable
data class KnownHeadphonesState(val profiles: List<HeadphoneProfileUiItem>) {
    companion object {
        fun from(state: HeadphoneProfilesUiState) = KnownHeadphonesState(state.profiles)
    }
}

interface KnownHeadphonesActions {
    fun onBack()
    fun onOpenProfile(profileId: String)
    /** Creates a profile with this name, selects it and opens it. */
    fun onCreate(name: String)
}

// ── Safety Max ───────────────────────────────────────────────────────────────────────────────────────

/** What the Safety Max page draws: the profile it belongs to (null when it is gone) and the volume it reads. */
@Immutable
data class SafetyMaxScreenState(
    val profileId: String,
    val profile: HeadphoneProfile?,
    val volume: SafetyVolumeState,
) {
    companion object {
        fun from(profileId: String, profiles: HeadphoneProfilesUiState, volume: SafetyVolumeState) =
            SafetyMaxScreenState(profileId, profiles.profiles.firstOrNull { it.profile.profileId == profileId }?.profile, volume)
    }
}

interface SafetyMaxScreenActions {
    fun onBack()
    fun onSetRawVolume(raw: Int)
    fun onSafetyMaxEnabledChange(enabled: Boolean)
    /** Saves the current level as the ceiling; [onResult] says whether it was saved and, if not, why (RT-01). */
    fun onCalibrate(onResult: (SafetyMaxCalibrationResult) -> Unit)
}

// ── DAC profile ──────────────────────────────────────────────────────────────────────────────────────

/** The known DACs list. */
@Immutable
data class KnownDacsState(val profiles: List<DacProfileUiItem>) {
    companion object {
        fun from(state: DacProfilesUiState) = KnownDacsState(state.profiles)
    }
}

interface KnownDacsActions {
    fun onBack()
    fun onOpenProfile(profileId: String)
    /** Reads the connected DACs again. */
    fun onRefresh()
}

/** What one DAC's page draws. [item] is null when the profile no longer exists. */
@Immutable
data class DacProfileScreenState(
    val item: DacProfileUiItem?,
    /** The DAC is the one connected right now (only then are its advertised capabilities stated). */
    val isConnectedNow: Boolean,
    val advertisedRates: List<Int>,
    val bestBitDepth: Int?,
    /** The headphones remembered for this DAC; null when none. */
    val headphone: HeadphoneProfileUiItem?,
    /** The Change action next to the headphones is offered. */
    val canChangeHeadphone: Boolean,
) {
    val profile: DacProfile? get() = item?.profile

    companion object {
        fun from(
            profileId: String,
            dacs: DacProfilesUiState,
            headphones: HeadphoneProfilesUiState,
            canChangeHeadphone: Boolean,
        ): DacProfileScreenState {
            val item = dacs.profiles.firstOrNull { it.profile.profileId == profileId }
            // Resolved through the stored association, which is keyed by DAC profile id: a name comparison
            // stands in for an identity lookup and breaks with two units of one model.
            val headphone = headphones.associationByDacProfileId[profileId]?.let { headphoneProfileId ->
                headphones.profiles.firstOrNull { it.profile.profileId == headphoneProfileId }
            }
            return DacProfileScreenState(
                item = item,
                isConnectedNow = dacs.current?.profile?.profileId == profileId,
                advertisedRates = dacs.currentAdvertisedRates,
                bestBitDepth = dacs.currentBestBitDepth,
                headphone = headphone,
                canChangeHeadphone = canChangeHeadphone,
            )
        }
    }
}

interface DacProfileScreenActions {
    fun onBack()
    fun onRefresh()
    fun onRename(profileId: String, name: String)
    /** Forgets it, and goes back. */
    fun onForget(profileId: String)
    /** Opens the known-headphones list, the flow the DAC-to-headphone association is stored through. */
    fun onChangeHeadphone()
}
