package com.lostf1sh.pixelplayeross.presentation.screens

import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxCalibrationResult
import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SafetyVolumeViewModel

/*
 * B21: the implementations behind the equalizer, headphone, Safety Max and DAC screens' actions. One line per
 * control, naming the ViewModel method it has always called or the route it has always opened. No state, no
 * decisions. `navigate` is the host's `navController.navigateSafely(route)`.
 */

internal class EqualizerScreenActionsImpl(
    private val vm: EqualizerViewModel,
    private val navigate: (String) -> Unit,
) : EqualizerScreenActions {
    override fun onEnabledChange(enabled: Boolean) = vm.setEnabled(enabled)
    override fun onModeChange(mode: HeadphoneEqMode) = vm.setMode(mode)
    override fun onSelectPreset(preset: EqualizerPreset) = vm.selectPreset(preset)
    override fun onTogglePinPreset(name: String) = vm.togglePinPreset(name)
    override fun onDeleteCustomPreset(preset: EqualizerPreset) = vm.deleteCustomPreset(preset)
    override fun onRenameCustomPreset(oldName: String, newName: String) = vm.renameCustomPreset(oldName, newName)
    override fun onSaveCurrentAsCustomPreset(name: String) = vm.saveCurrentAsCustomPreset(name)
    override fun onUpdateCustomPresetBands(presetName: String) = vm.updateCustomPresetBands(presetName)
    override fun onBandLevelChange(index: Int, level: Int) = vm.setBandLevel(index, level)
    override fun onResetBandsToFlat() = vm.resetActiveBandsToFlat()
    // B22: the Pure Direct switch is on the Audio page now (it used to be on the Settings root).
    override fun onOpenPurityLock() = navigate(Screen.SettingsCategory.createRoute(SettingsCategory.AUDIO.id))
}

internal class HeadphoneProfileScreenActionsImpl(
    private val profiles: HeadphoneProfilesViewModel,
    private val eq: EqualizerViewModel,
    private val back: () -> Unit,
    private val openEqualizer: (String) -> Unit,
    private val openSafetyMax: (String) -> Unit,
) : HeadphoneProfileScreenActions {
    override fun onBack() = back()
    override fun onSelect(profileId: String) = profiles.select(profileId)
    override fun onRename(profileId: String, name: String) = profiles.rename(profileId, name)
    override fun onForget(profileId: String) = profiles.forget(profileId, onForgotten = back)
    override fun onOpenEqualizer(profileId: String) = profiles.select(profileId) { openEqualizer(profileId) }
    override fun onOpenSafetyMax(profileId: String) = profiles.select(profileId) { openSafetyMax(profileId) }
    override fun onBassBoostEnabledChange(enabled: Boolean) = eq.setBassBoostEnabled(enabled)
    override fun onBassBoostStrengthChange(strength: Int) = eq.setBassBoostStrength(strength)
    override fun onVirtualizerEnabledChange(enabled: Boolean) = eq.setVirtualizerEnabled(enabled)
    override fun onVirtualizerStrengthChange(strength: Int) = eq.setVirtualizerStrength(strength)
    override fun onLoudnessEnhancerEnabledChange(enabled: Boolean) = eq.setLoudnessEnhancerEnabled(enabled)
    override fun onLoudnessEnhancerStrengthChange(strength: Int) = eq.setLoudnessEnhancerStrength(strength)
}

internal class KnownHeadphonesActionsImpl(
    private val vm: HeadphoneProfilesViewModel,
    private val back: () -> Unit,
    private val openProfile: (String) -> Unit,
) : KnownHeadphonesActions {
    override fun onBack() = back()
    override fun onOpenProfile(profileId: String) = openProfile(profileId)
    override fun onCreate(name: String) = vm.create(name) { profileId -> openProfile(profileId) }
}

internal class SafetyMaxScreenActionsImpl(
    private val volume: SafetyVolumeViewModel,
    private val back: () -> Unit,
) : SafetyMaxScreenActions {
    override fun onBack() = back()
    override fun onSetRawVolume(raw: Int) = volume.setRawVolume(raw)
    override fun onSafetyMaxEnabledChange(enabled: Boolean) = volume.setEnabled(enabled)
    override fun onCalibrate(onResult: (SafetyMaxCalibrationResult) -> Unit) = volume.calibrate(onResult)
}

internal class KnownDacsActionsImpl(
    private val vm: DacProfilesViewModel,
    private val back: () -> Unit,
    private val openProfile: (String) -> Unit,
) : KnownDacsActions {
    override fun onBack() = back()
    override fun onOpenProfile(profileId: String) = openProfile(profileId)
    override fun onRefresh() = vm.refresh()
}

internal class DacProfileScreenActionsImpl(
    private val vm: DacProfilesViewModel,
    private val back: () -> Unit,
    private val changeHeadphone: () -> Unit,
) : DacProfileScreenActions {
    override fun onBack() = back()
    override fun onRefresh() = vm.refresh()
    override fun onRename(profileId: String, name: String) = vm.rename(profileId, name)
    override fun onForget(profileId: String) {
        vm.forget(profileId)
        back()
    }
    override fun onChangeHeadphone() = changeHeadphone()
}
