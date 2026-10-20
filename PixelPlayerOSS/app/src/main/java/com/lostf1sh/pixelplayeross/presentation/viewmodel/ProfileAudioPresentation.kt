package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfile
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileState
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import com.lostf1sh.pixelplayeross.data.volume.VolumeBackendSnapshot

/** Read-only descriptions. These never decide volume or DSP policy. */
enum class SafetyMaxOutputState {
    OFF, PROFILE_NOT_SELECTED, UNAVAILABLE, NOT_CALIBRATED, ACTIVE, ACTIVE_RESCALED, TEMPORARY_OVERRIDE,
}

internal fun safetyMaxOutputCompatible(backend: VolumeBackendSnapshot): Boolean =
    backend.controllable && backend.outputContextId != null && backend.rawMax > backend.rawMin &&
        backend.kind in setOf(SafetyVolumeBackendKind.ANDROID_MEDIA, SafetyVolumeBackendKind.USB_HARDWARE_VOLUME)

internal fun safetyMaxOutputState(profile: HeadphoneProfile, volume: SafetyVolumeState): SafetyMaxOutputState = when {
    profile.profileId != volume.selectedProfileId -> SafetyMaxOutputState.PROFILE_NOT_SELECTED
    !safetyMaxOutputCompatible(volume.backend) -> SafetyMaxOutputState.UNAVAILABLE
    !profile.safetyMax.enabled || !volume.safetyEnabled -> SafetyMaxOutputState.OFF
    !volume.protectionActive -> SafetyMaxOutputState.NOT_CALIBRATED
    volume.temporaryOverride -> SafetyMaxOutputState.TEMPORARY_OVERRIDE
    volume.calibrationNeedsRefresh -> SafetyMaxOutputState.ACTIVE_RESCALED
    else -> SafetyMaxOutputState.ACTIVE
}

enum class EqualizerOutputState {
    OFF, PROFILE_NOT_SELECTED, BYPASSED_PURE_DIRECT, BYPASSED_USB_DIRECT, BYPASSED_MAINTENANCE, APPLIED,
}

data class EqualizerOutputContext(
    val isProfileSelected: Boolean = false,
    val directPristine: Boolean = false,
    val pureDirect: Boolean = false,
    val maintenanceBypass: Boolean = false,
) {
    fun state(enabled: Boolean): EqualizerOutputState = when {
        !isProfileSelected -> EqualizerOutputState.PROFILE_NOT_SELECTED
        !enabled -> EqualizerOutputState.OFF
        maintenanceBypass -> EqualizerOutputState.BYPASSED_MAINTENANCE
        directPristine && pureDirect -> EqualizerOutputState.BYPASSED_PURE_DIRECT
        directPristine -> EqualizerOutputState.BYPASSED_USB_DIRECT
        else -> EqualizerOutputState.APPLIED
    }
}

/** Whether the profile being edited (or the selected one, when none is named) is the one the DSP follows. */
internal fun isEditedProfileSelected(profiles: HeadphoneProfileState, editingProfileId: String?): Boolean =
    profiles.selectedProfileId != null &&
        (editingProfileId == null || profiles.selectedProfileId == editingProfileId)

/** The equalizer's output context for the profile being edited, from the one [AudioStatus]. */
internal fun equalizerOutputContext(
    profiles: HeadphoneProfileState,
    editingProfileId: String?,
    audio: AudioStatus,
) = audio.equalizerContext(isEditedProfileSelected(profiles, editingProfileId))

internal fun equalizerOutputContext(
    profiles: HeadphoneProfileState,
    editingProfileId: String?,
    route: AudioRouteState,
    pureDirect: Boolean,
    maintenanceBypass: Boolean,
) = equalizerOutputContext(
    profiles,
    editingProfileId,
    AudioStatus(purityPreference = pureDirect, route = route, maintenanceDspBypass = maintenanceBypass),
)

/** A named editor keeps showing its own saved curve when a DAC selects another profile. */
internal fun editedEqualizer(profiles: HeadphoneProfileState, editingProfileId: String?): HeadphoneEqState =
    (if (editingProfileId == null) profiles.selectedProfile
    else profiles.profiles.firstOrNull { it.profileId == editingProfileId })?.equalizer ?: HeadphoneEqState()
