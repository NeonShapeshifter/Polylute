package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqService
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * B30-P3: the Resonance screen's gate and, when a writable DAC's EQ is read, its editor ([DacEqScreenController]).
 *
 * This is created by the screen's route host **before** the software EQ's view model, and it never creates that one: the software
 * editor holds the signal on the processed path while it is open (`HeadphoneGraphicEqController.setEditorOpen`), which a DAC EQ
 * has no need of and must not cause.
 */
@HiltViewModel
class DacEqViewModel @Inject constructor(
    service: DacEqService,
    headphones: HeadphoneProfileRepository,
    audioStatusSource: AudioStatusSource,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val controller = DacEqScreenController(
        scope = viewModelScope,
        headphoneId = savedStateHandle.get<String>("headphoneProfileId")?.takeIf { it.isNotBlank() },
        service = service,
        store = headphones,
        audio = audioStatusSource.status,
    )
}
