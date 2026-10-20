package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabel
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabelSource
import com.lostf1sh.pixelplayeross.data.dac.fiio.NoDacEqLabels
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Builds [AudioStatus] once, from what the engine and the preferences already publish, and hands the same
 * stream to every screen's ViewModel. Nothing here decides anything: it observes the route, the Pure Direct
 * preference, the sink's own verdict, the Maintenance DSP override and (B30) what the DAC's own EQ was read as, and joins them.
 *
 * It is a singleton so that two screens open at once (the Settings root under the Audio page, the EQ over a
 * profile) read the same value from the same collection instead of each running its own combine.
 */
@Singleton
class AudioStatusSource @Inject constructor(
    dualPlayerEngine: DualPlayerEngine,
    userPreferencesRepository: UserPreferencesRepository,
    maintenanceOverrides: MaintenanceOverrideStore,
    @AppScope scope: CoroutineScope,
    dacEqLabels: DacEqLabelSource = NoDacEqLabels,
) {
    val status: StateFlow<AudioStatus> = combine(
        dualPlayerEngine.audioRouteState,
        dualPlayerEngine.usbAudioDiagnostics
            .map { it.state == UsbDirectSinkState.USB_DIRECT_ACTIVE }
            .distinctUntilChanged(),
        userPreferencesRepository.pristineLockFlow,
        maintenanceOverrides.state.map { it.dspGraphDisabled() }.distinctUntilChanged(),
        dacEqLabels.label,
    ) { route: AudioRouteState, sinkDirectActive: Boolean, purity: Boolean, dspBypass: Boolean, dacEq: DacEqLabel ->
        AudioStatus(
            purityPreference = purity,
            route = route,
            sinkDirectActive = sinkDirectActive,
            maintenanceDspBypass = dspBypass,
            dacEq = dacEq,
        )
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), AudioStatus())
}
