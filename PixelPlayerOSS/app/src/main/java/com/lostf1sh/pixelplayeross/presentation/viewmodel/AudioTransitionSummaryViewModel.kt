package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.model.TransitionSettings
import com.lostf1sh.pixelplayeross.data.repository.TransitionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * B22: what the Audio page's crossfade row says about the global rule ("4 s · fundido inteligente").
 *
 * It only reads. The editor (`EditTransitionScreen`) owns the rules; this follows the same record, so coming back from
 * the editor shows what was just saved without the page having to ask again.
 */
@HiltViewModel
class AudioTransitionSummaryViewModel @Inject constructor(
    transitionRepository: TransitionRepository,
) : ViewModel() {
    /** The global transition rule, or null until the first read. */
    val globalSettings: StateFlow<TransitionSettings?> = transitionRepository.getGlobalSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
