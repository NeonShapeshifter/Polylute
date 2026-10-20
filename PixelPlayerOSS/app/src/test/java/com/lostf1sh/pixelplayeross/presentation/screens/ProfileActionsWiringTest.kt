package com.lostf1sh.pixelplayeross.presentation.screens

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxCalibrationResult
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SafetyVolumeViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test

/** B21: every control of the EQ, headphone, Safety Max and DAC screens reaches what it always reached. */
class ProfileActionsWiringTest {

    private val routes = mutableListOf<String>()

    @Test
    fun `equalizer controls reach the equalizer ViewModel`() {
        val vm = mockk<EqualizerViewModel>(relaxed = true)
        val actions = EqualizerScreenActionsImpl(vm) { routes += it }
        val preset = EqualizerPreset("mine", "Mine", List(10) { 1 }, true)
        actions.onEnabledChange(true)
        actions.onModeChange(HeadphoneEqMode.GRAPHIC_15)
        actions.onSelectPreset(preset)
        actions.onTogglePinPreset("mine")
        actions.onDeleteCustomPreset(preset)
        actions.onRenameCustomPreset("mine", "yours")
        actions.onSaveCurrentAsCustomPreset("new")
        actions.onUpdateCustomPresetBands("mine")
        actions.onBandLevelChange(3, -4)
        actions.onResetBandsToFlat()
        actions.onOpenPurityLock()
        verify(exactly = 1) { vm.setEnabled(true) }
        verify(exactly = 1) { vm.setMode(HeadphoneEqMode.GRAPHIC_15) }
        verify(exactly = 1) { vm.selectPreset(preset) }
        verify(exactly = 1) { vm.togglePinPreset("mine") }
        verify(exactly = 1) { vm.deleteCustomPreset(preset) }
        verify(exactly = 1) { vm.renameCustomPreset("mine", "yours") }
        verify(exactly = 1) { vm.saveCurrentAsCustomPreset("new") }
        verify(exactly = 1) { vm.updateCustomPresetBands("mine") }
        verify(exactly = 1) { vm.setBandLevel(3, -4) }
        verify(exactly = 1) { vm.resetActiveBandsToFlat() }
        // B22: the Pure Direct switch is on the Audio page now.
        assertThat(routes).containsExactly(Screen.SettingsCategory.createRoute("audio"))
    }

    @Test
    fun `the headphone profile selects the profile before it opens the EQ or Safety Max, and forgets then goes back`() {
        val profiles = mockk<HeadphoneProfilesViewModel>(relaxed = true)
        val eq = mockk<EqualizerViewModel>(relaxed = true)
        val opened = mutableListOf<String>()
        var backs = 0
        // A real select() calls its continuation once the profile is the selected one.
        val continuation = slot<() -> Unit>()
        every { profiles.select("hp", capture(continuation)) } answers { continuation.captured() }
        val actions = HeadphoneProfileScreenActionsImpl(profiles, eq, { backs++ }, { opened += "eq:$it" }, { opened += "safety:$it" })

        actions.onOpenEqualizer("hp")
        actions.onOpenSafetyMax("hp")
        assertThat(opened).containsExactly("eq:hp", "safety:hp").inOrder()

        actions.onSelect("hp")
        actions.onRename("hp", "Renamed")
        actions.onBack()
        verify(exactly = 1) { profiles.select("hp") }
        verify(exactly = 1) { profiles.rename("hp", "Renamed") }
        assertThat(backs).isEqualTo(1)

        val forgotten = slot<() -> Unit>()
        every { profiles.forget("hp", capture(forgotten)) } answers { forgotten.captured() }
        actions.onForget("hp")
        assertThat(backs).isEqualTo(2)

        actions.onBassBoostEnabledChange(true)
        actions.onBassBoostStrengthChange(400)
        actions.onVirtualizerEnabledChange(true)
        actions.onVirtualizerStrengthChange(500)
        actions.onLoudnessEnhancerEnabledChange(true)
        actions.onLoudnessEnhancerStrengthChange(600)
        verify(exactly = 1) { eq.setBassBoostEnabled(true) }
        verify(exactly = 1) { eq.setBassBoostStrength(400) }
        verify(exactly = 1) { eq.setVirtualizerEnabled(true) }
        verify(exactly = 1) { eq.setVirtualizerStrength(500) }
        verify(exactly = 1) { eq.setLoudnessEnhancerEnabled(true) }
        verify(exactly = 1) { eq.setLoudnessEnhancerStrength(600) }
    }

    @Test
    fun `the known headphones list creates a profile and opens it`() {
        val vm = mockk<HeadphoneProfilesViewModel>(relaxed = true)
        val opened = mutableListOf<String>()
        var backs = 0
        val onCreated = slot<(String) -> Unit>()
        every { vm.create("Studio", capture(onCreated)) } answers { onCreated.captured("new-id") }
        val actions = KnownHeadphonesActionsImpl(vm, { backs++ }, { opened += it })
        actions.onCreate("Studio")
        actions.onOpenProfile("hp-9")
        actions.onBack()
        assertThat(opened).containsExactly("new-id", "hp-9").inOrder()
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `safety max controls reach the volume ViewModel`() {
        val vm = mockk<SafetyVolumeViewModel>(relaxed = true)
        var backs = 0
        val actions = SafetyMaxScreenActionsImpl(vm) { backs++ }
        val result = slot<(SafetyMaxCalibrationResult) -> Unit>()
        every { vm.calibrate(capture(result)) } answers { result.captured(SafetyMaxCalibrationResult.LEVEL_UNCONFIRMED) }
        var accepted: SafetyMaxCalibrationResult? = null
        actions.onSetRawVolume(7)
        actions.onSafetyMaxEnabledChange(false)
        actions.onCalibrate { accepted = it }
        actions.onBack()
        verify(exactly = 1) { vm.setRawVolume(7) }
        verify(exactly = 1) { vm.setEnabled(false) }
        // the reason reaches the screen untouched: it is not folded into a yes/no (RT-01)
        assertThat(accepted).isEqualTo(SafetyMaxCalibrationResult.LEVEL_UNCONFIRMED)
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `DAC controls reach the DAC ViewModel`() {
        val vm = mockk<DacProfilesViewModel>(relaxed = true)
        var backs = 0
        var changed = 0
        val opened = mutableListOf<String>()
        val profile = DacProfileScreenActionsImpl(vm, { backs++ }) { changed++ }
        profile.onRefresh()
        profile.onRename("dac", "Mine")
        profile.onChangeHeadphone()
        profile.onForget("dac")
        verify(exactly = 1) { vm.refresh() }
        verify(exactly = 1) { vm.rename("dac", "Mine") }
        // Forgetting a DAC goes back, after the forget.
        verify(exactly = 1) { vm.forget("dac") }
        assertThat(backs).isEqualTo(1)
        assertThat(changed).isEqualTo(1)

        val list = KnownDacsActionsImpl(vm, { backs++ }, { opened += it })
        list.onRefresh()
        list.onOpenProfile("dac")
        list.onBack()
        verify(exactly = 2) { vm.refresh() }
        assertThat(opened).containsExactly("dac")
        assertThat(backs).isEqualTo(2)
    }
}
