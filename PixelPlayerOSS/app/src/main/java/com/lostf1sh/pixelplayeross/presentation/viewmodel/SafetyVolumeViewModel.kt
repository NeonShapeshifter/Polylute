package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeController
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxCalibrationResult
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SafetyVolumeViewModel @Inject constructor(
    private val controller: SafetyVolumeController,
    private val preferences: UserPreferencesRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val state: StateFlow<SafetyVolumeState> = controller.state
    val notificationPromptAsked: StateFlow<Boolean> = preferences.safetyMaxNotificationPromptAskedFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setRawVolume(raw: Int) = controller.setRawLevel(raw)
    fun calibrate(onResult: (SafetyMaxCalibrationResult) -> Unit = {}) = controller.useCurrentAsSafetyMax(onResult)
    fun setEnabled(enabled: Boolean) = controller.setSafetyEnabled(enabled)
    fun markNotificationPromptAsked() {
        viewModelScope.launch { preferences.setSafetyMaxNotificationPromptAsked(true) }
    }
    fun currentDapLevel(): ProductLevel? =
        runCatching { ProductModeController(context).currentState().level }.getOrNull()
}
