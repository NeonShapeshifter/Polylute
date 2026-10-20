package com.lostf1sh.pixelplayeross.presentation.screens

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.DacIdentityScope
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfile
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneSafetyPolicy
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import com.lostf1sh.pixelplayeross.data.volume.VolumeBackendSnapshot
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacLiveStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerOutputState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SafetyMaxOutputState
import org.junit.jupiter.api.Test

/** B21: what the EQ, headphone, Safety Max and DAC screens draw is built here, from what their ViewModels publish. */
class ProfileScreenStatesTest {

    private fun headphone(id: String, eq: HeadphoneEqState = HeadphoneEqState(), safety: HeadphoneSafetyPolicy = HeadphoneSafetyPolicy()) =
        HeadphoneProfile(id, "Headphones $id", eq, 1, 1, safety)

    private fun headphones(vararg items: HeadphoneProfileUiItem, associations: Map<String, String> = emptyMap()) =
        HeadphoneProfilesUiState(
            profiles = items.toList(),
            selected = items.firstOrNull { it.isSelected },
            associationByDacProfileId = associations,
        )

    private val direct = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT,
        transition = AudioRouteTransition.DIRECT_ACTIVE,
        signalMode = DirectSignalMode.PRISTINE,
        usbDacPresent = true,
    )

    // ── item 4: the EQ's state is said once ──────────────────────────────────────────────────────────

    @Test
    fun `an EQ that is off says so once, and a saved one says its mode and what it does now`() {
        val off = equalizerSummary(HeadphoneEqState(enabled = false), EqualizerOutputState.OFF)
        assertThat(off.output).isNull()
        assertThat(off.modeRes).isEqualTo(R.string.headphone_eq_mode_off)

        val ten = equalizerSummary(HeadphoneEqState(enabled = true, mode = HeadphoneEqMode.GRAPHIC_10), EqualizerOutputState.APPLIED)
        assertThat(ten).isEqualTo(EqualizerSummary(R.string.headphone_eq_mode_10, EqualizerOutputState.APPLIED))
        val fifteen = equalizerSummary(HeadphoneEqState(enabled = true, mode = HeadphoneEqMode.GRAPHIC_15), EqualizerOutputState.BYPASSED_PURE_DIRECT)
        assertThat(fifteen).isEqualTo(EqualizerSummary(R.string.headphone_eq_mode_15, EqualizerOutputState.BYPASSED_PURE_DIRECT))
    }

    @Test
    fun `an EQ that is off is not also reported as not applied because its profile is not selected`() {
        // "Saved: Off · Not applied · profile not selected" said the off state twice, and a profile with nothing on has nothing to apply.
        val summary = equalizerSummary(HeadphoneEqState(enabled = false), EqualizerOutputState.PROFILE_NOT_SELECTED)
        assertThat(summary.output).isNull()
        // With the EQ on, the not-selected profile does say it is not applied.
        assertThat(equalizerSummary(HeadphoneEqState(enabled = true), EqualizerOutputState.PROFILE_NOT_SELECTED).output)
            .isEqualTo(EqualizerOutputState.PROFILE_NOT_SELECTED)
    }

    // ── the headphone profile ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a profile that is gone has no item`() {
        val state = HeadphoneProfileScreenState.from("missing", headphones(), EqualizerUiState(), AudioStatus(), SafetyVolumeState())
        assertThat(state.item).isNull()
        assertThat(state.eqSummary).isNull()
        assertThat(state.safety).isNull()
    }

    @Test
    fun `the profile page's EQ line follows the status, not the selection alone`() {
        val on = headphone("a", HeadphoneEqState(enabled = true))
        val items = headphones(HeadphoneProfileUiItem(on, isSelected = true), HeadphoneProfileUiItem(headphone("b", HeadphoneEqState(enabled = true)), isSelected = false))
        val pure = AudioStatus(purityPreference = true, route = direct, sinkDirectActive = true)

        val selected = HeadphoneProfileScreenState.from("a", items, EqualizerUiState(), pure, SafetyVolumeState())
        assertThat(selected.eqSummary).isEqualTo(EqualizerSummary(R.string.headphone_eq_mode_10, EqualizerOutputState.BYPASSED_PURE_DIRECT))

        val notSelected = HeadphoneProfileScreenState.from("b", items, EqualizerUiState(), pure, SafetyVolumeState())
        assertThat(notSelected.eqSummary).isEqualTo(EqualizerSummary(R.string.headphone_eq_mode_10, EqualizerOutputState.PROFILE_NOT_SELECTED))

        val resonance = AudioStatus(purityPreference = false, route = direct.copy(signalMode = DirectSignalMode.PROCESSED), sinkDirectActive = true)
        assertThat(HeadphoneProfileScreenState.from("a", items, EqualizerUiState(), resonance, SafetyVolumeState()).eqSummary?.output)
            .isEqualTo(EqualizerOutputState.APPLIED)
    }

    @Test
    fun `the preset row names the curve the profile holds`() {
        val custom = EqualizerPreset("mine", "Mine", List(10) { 2 }, true)
        val tenBand = headphone("a", HeadphoneEqState(enabled = true, mode = HeadphoneEqMode.GRAPHIC_10, presetName = "mine"))
        val fifteen = headphone("c", HeadphoneEqState(enabled = true, mode = HeadphoneEqMode.GRAPHIC_15, bands15 = List(15) { 1 }))
        val items = headphones(HeadphoneProfileUiItem(tenBand, true), HeadphoneProfileUiItem(fifteen, false))
        val eq = EqualizerUiState(customPresets = kotlinx.collections.immutable.persistentListOf(custom))
        assertThat(HeadphoneProfileScreenState.from("a", items, eq, AudioStatus(), SafetyVolumeState()).presetForRow).isEqualTo(custom)
        // Fifteen bands have no named preset: it is a custom curve of the stored bands.
        assertThat(HeadphoneProfileScreenState.from("c", items, eq, AudioStatus(), SafetyVolumeState()).presetForRow)
            .isEqualTo(EqualizerPreset.custom(List(15) { 1 }))
        // A built-in preset is found by name when no custom one has it.
        val builtIn = headphone("d", HeadphoneEqState(enabled = true, presetName = "rock"))
        assertThat(HeadphoneProfileScreenState.from("d", headphones(HeadphoneProfileUiItem(builtIn, true)), EqualizerUiState(), AudioStatus(), SafetyVolumeState()).presetForRow)
            .isEqualTo(EqualizerPreset.fromName("rock"))
    }

    @Test
    fun `the Safety Max card reads the ceiling saved for the output in use`() {
        val calibration = SafetyMaxCalibration("usb-1", SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, 17, 0, 40, 1L)
        val withCeiling = headphone("a", safety = HeadphoneSafetyPolicy(true, mapOf(calibration.storageKey to calibration)))
        val volume = SafetyVolumeState(
            backend = VolumeBackendSnapshot(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, "usb-1", "DAC", 10, 0, 40, true),
            selectedProfileId = "a",
        )
        val card = SafetyMaxCardState.of(withCeiling, volume)
        assertThat(card.enabled).isTrue()
        assertThat(card.ceilingRaw).isEqualTo(17)
        assertThat(card.hasOtherOutputCalibration).isTrue()
        assertThat(card.backendKind).isEqualTo(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME)

        // The same profile on another output: a ceiling exists, but not for this one.
        val elsewhere = volume.copy(backend = volume.backend.copy(outputContextId = "bt-2", kind = SafetyVolumeBackendKind.ANDROID_MEDIA))
        val other = SafetyMaxCardState.of(withCeiling, elsewhere)
        assertThat(other.ceilingRaw).isNull()
        assertThat(other.hasOtherOutputCalibration).isTrue()

        // A profile that has never been calibrated.
        val none = SafetyMaxCardState.of(headphone("b"), volume)
        assertThat(none.ceilingRaw).isNull()
        assertThat(none.hasOtherOutputCalibration).isFalse()
        assertThat(none.outputState).isEqualTo(SafetyMaxOutputState.PROFILE_NOT_SELECTED)
    }

    // ── the DAC profile ──────────────────────────────────────────────────────────────────────────────

    private fun dac(id: String) = DacProfile(id, "key-$id", DacIdentityScope.UNIT_SPECIFIC, "DAC $id", null, null, 0x2972, 0x0126, null, 1L, 1L)

    @Test
    fun `a DAC page resolves its headphones through the stored association, not through a name`() {
        val a = DacProfileUiItem(dac("one"), DacLiveStatus.ACTIVE)
        val b = DacProfileUiItem(dac("two"), DacLiveStatus.NOT_CONNECTED)
        val hp1 = HeadphoneProfileUiItem(headphone("hp1"), isSelected = true)
        val hp2 = HeadphoneProfileUiItem(headphone("hp2"), isSelected = false)
        val dacs = DacProfilesUiState(
            profiles = listOf(a, b), current = a,
            currentAdvertisedRates = listOf(44100, 96000), currentBestBitDepth = 32,
        )
        val hps = headphones(hp1, hp2, associations = mapOf("one" to "hp1", "two" to "hp2"))

        val one = DacProfileScreenState.from("one", dacs, hps, canChangeHeadphone = true)
        assertThat(one.item).isSameInstanceAs(a)
        assertThat(one.isConnectedNow).isTrue()
        assertThat(one.advertisedRates).containsExactly(44100, 96000).inOrder()
        assertThat(one.bestBitDepth).isEqualTo(32)
        assertThat(one.headphone).isSameInstanceAs(hp1)
        assertThat(one.canChangeHeadphone).isTrue()

        val two = DacProfileScreenState.from("two", dacs, hps, canChangeHeadphone = false)
        assertThat(two.isConnectedNow).isFalse()
        assertThat(two.headphone).isSameInstanceAs(hp2)
        assertThat(two.canChangeHeadphone).isFalse()

        val missing = DacProfileScreenState.from("nope", dacs, hps, canChangeHeadphone = true)
        assertThat(missing.item).isNull()
        assertThat(missing.headphone).isNull()
    }

    @Test
    fun `the lists carry the profiles and nothing else`() {
        val a = DacProfileUiItem(dac("one"), DacLiveStatus.CONNECTED)
        assertThat(KnownDacsState.from(DacProfilesUiState(profiles = listOf(a))).profiles).containsExactly(a)
        val hp = HeadphoneProfileUiItem(headphone("x"), true)
        assertThat(KnownHeadphonesState.from(headphones(hp)).profiles).containsExactly(hp)
    }

    @Test
    fun `the equalizer page asks the status what its EQ is doing`() {
        val eq = EqualizerUiState(isEnabled = true)
        val pure = AudioStatus(purityPreference = true, route = direct, sinkDirectActive = true)
        val state = EqualizerScreenState.from(eq, pure.equalizerContext(isProfileSelected = true), pure)
        assertThat(state.output).isEqualTo(EqualizerOutputState.BYPASSED_PURE_DIRECT)
        assertThat(state.audio).isSameInstanceAs(pure)
        val off = EqualizerScreenState.from(EqualizerUiState(isEnabled = false), pure.equalizerContext(true), pure)
        assertThat(off.output).isEqualTo(EqualizerOutputState.OFF)
    }
}
