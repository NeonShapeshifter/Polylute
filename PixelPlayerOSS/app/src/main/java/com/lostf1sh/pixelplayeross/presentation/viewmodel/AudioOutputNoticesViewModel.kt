package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.lostf1sh.pixelplayeross.data.volume.DeviceVolumeAnswer
import com.lostf1sh.pixelplayeross.data.volume.FixedOutputDac
import com.lostf1sh.pixelplayeross.data.volume.FixedOutputNoticeCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** What the Audio page says about the DAC attached that is not part of its profile: the notices a DAC can owe the listener (B39). */
@HiltViewModel
class AudioOutputNoticesViewModel @Inject constructor(
    private val fixedOutput: FixedOutputNoticeCoordinator,
) : ViewModel() {
    /** The DAC attached, if it has no volume the app can set. */
    val fixedOutputDac: StateFlow<FixedOutputDac?> = fixedOutput.fixedOutputDac

    /** B40-P3: what the listener answered for that DAC (null: not asked yet). */
    val answer: StateFlow<DeviceVolumeAnswer?> = fixedOutput.answer

    /** "Change it": raises the question with nothing to resume. */
    fun showFixedOutputNotice() {
        fixedOutput.showAgain()
    }
}
