package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxCalibrationResult
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SafetyVolumeViewModel @Inject constructor(
    private val controller: SafetyVolumeController,
) : ViewModel() {
    val state: StateFlow<SafetyVolumeState> = controller.state

    fun setRawVolume(raw: Int) = controller.setRawLevel(raw)
    fun calibrate(onResult: (SafetyMaxCalibrationResult) -> Unit = {}) = controller.useCurrentAsSafetyMax(onResult)
    fun setEnabled(enabled: Boolean) = controller.setSafetyEnabled(enabled)
}
