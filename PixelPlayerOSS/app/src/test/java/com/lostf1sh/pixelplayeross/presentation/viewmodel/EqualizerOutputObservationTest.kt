package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.media.AudioAttributes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.data.audio.dsp.HeadphoneGraphicEqController
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrides
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerManager
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.*
import com.lostf1sh.pixelplayeross.data.preferences.EqualizerPreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.*
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EqualizerOutputObservationTest {
    @Test
    fun `open editor observes route lock profile and stored curve changes without writing audio settings`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val a = HeadphoneProfile("a", "A", HeadphoneEqState(enabled = true, bands10 = List(10) { 3 }), 1, 1)
            val b = a.copy(profileId = "b", equalizer = HeadphoneEqState())
            val profiles = MutableStateFlow(HeadphoneProfileState(listOf(a, b), "a"))
            val route = MutableStateFlow(AudioRouteState())
            val locked = MutableStateFlow(true)
            val repository = mockk<HeadphoneProfileRepository> { every { state } returns profiles }
            val engine = mockk<DualPlayerEngine> {
                every { audioRouteState } returns route
                every { usbAudioDiagnostics } returns MutableStateFlow(UsbDirectAudioDiagnostics())
                every { activeAudioSessionId } returns MutableStateFlow(0)
            }
            val prefs = mockk<UserPreferencesRepository> { every { pristineLockFlow } returns locked }
            val presets = mockk<EqualizerPreferencesRepository> {
                every { customPresetsFlow } returns flowOf(emptyList<EqualizerPreset>())
                every { pinnedPresetsFlow } returns flowOf(emptyList<String>())
            }
            val manager = mockk<EqualizerManager>(relaxed = true) { every { isAttached } returns true }
            val coordinator = mockk<HeadphoneEqualizerCoordinator>(relaxed = true) {
                coEvery { selectedProfileId() } returns "a"
            }
            mockkConstructor(AudioAttributes.Builder::class)
            every { anyConstructed<AudioAttributes.Builder>().setUsage(any()) } answers { self as AudioAttributes.Builder }
            every { anyConstructed<AudioAttributes.Builder>().setContentType(any()) } answers { self as AudioAttributes.Builder }
            every { anyConstructed<AudioAttributes.Builder>().build() } returns mockk(relaxed = true)
            val volume = mockk<SafetyVolumeController> { every { state } returns MutableStateFlow(SafetyVolumeState()) }
            val dsp = mockk<HeadphoneGraphicEqController>(relaxed = true)
            val overrides = mockk<MaintenanceOverrideStore> { every { state } returns MutableStateFlow(MaintenanceOverrides()) }
            val status = AudioStatusSource(engine, prefs, overrides, backgroundScope)
            val vm = EqualizerViewModel(manager, presets, coordinator, engine, repository, status,
                volume, dsp, backgroundScope, SavedStateHandle(mapOf("headphoneProfileId" to "a")))
            store.put("eq", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.outputContext.collect() }
            runCurrent()
            fun output() = vm.outputContext.value.state(vm.uiState.value.isEnabled)
            assertEquals(EqualizerOutputState.APPLIED, output())
            route.value = AudioRouteState(actualBackend = AudioBackend.USB_DIRECT,
                transition = AudioRouteTransition.DIRECT_ACTIVE, signalMode = DirectSignalMode.PRISTINE)
            runCurrent()
            assertEquals(EqualizerOutputState.BYPASSED_PURE_DIRECT, output())
            profiles.value = profiles.value.copy(selectedProfileId = "b")
            runCurrent()
            assertEquals(EqualizerOutputState.PROFILE_NOT_SELECTED, output())
            assertEquals(List(10) { 3 }, vm.uiState.value.bandLevels)
            val edited = a.copy(equalizer = a.equalizer.copy(bands10 = List(10) { -4 }))
            profiles.value = profiles.value.copy(profiles = listOf(edited, b), selectedProfileId = "a")
            locked.value = false
            route.value = route.value.copy(signalMode = DirectSignalMode.PROCESSED)
            runCurrent()
            assertEquals(EqualizerOutputState.APPLIED, output())
            assertEquals(List(10) { -4 }, vm.uiState.value.bandLevels)
            route.value = AudioRouteState()
            locked.value = true
            runCurrent()
            assertEquals(EqualizerOutputState.APPLIED, output())
            coVerify(exactly = 0) { coordinator.updateEqualizer(any(), any()) }
            verify(exactly = 0) { dsp.previewBands(any(), any(), any()) }
        } finally {
            backgroundScope.cancel()
            store.clear()
            runCurrent()
            unmockkConstructor(AudioAttributes.Builder::class)
            Dispatchers.resetMain()
        }
    }
}
