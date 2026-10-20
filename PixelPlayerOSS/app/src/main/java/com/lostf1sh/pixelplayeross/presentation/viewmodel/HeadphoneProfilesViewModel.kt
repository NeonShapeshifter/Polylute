package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.dac.UsbDacDescriptor
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCapability
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfiles
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqualizerCoordinator
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfile
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileRepository
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileState
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HeadphoneProfileUiItem(
    val profile: HeadphoneProfile,
    val isSelected: Boolean,
    val associatedDacName: String? = null,
)

/**
 * B30: the DAC in use, when it is one the app can ask about its EQ: what a DAC EQ preset made elsewhere is judged against. The
 * capability is the table's claim; whether it can be written is [canBeWritten] (a slot of ours exists).
 */
data class CurrentDacEq(
    val dacProfileId: String,
    val dacName: String,
    val capability: DacEqCapability,
    val canBeWritten: Boolean,
)

data class HeadphoneProfilesUiState(
    val profiles: List<HeadphoneProfileUiItem> = emptyList(),
    val selected: HeadphoneProfileUiItem? = null,
    val currentDacProfileId: String? = null,
    /**
     * The association as stored: DAC profile id → headphone profile id.
     *
     * Exposed so a screen can ask "which headphones are remembered for *this* DAC profile?"
     * without going through a display name. [HeadphoneProfileUiItem.associatedDacName] is a
     * presentation convenience — it answers the opposite question for the list, and it is a name,
     * so two DAC profiles with the same visible name are indistinguishable through it.
     */
    val associationByDacProfileId: Map<String, String> = emptyMap(),
    /** The DAC in use, when its EQ is one the app can ask about (B30); null otherwise. */
    val currentDacEq: CurrentDacEq? = null,
)

object DacHeadphoneContextResolver {
    fun resolve(
        profiles: List<DacProfile>,
        connected: List<UsbDacDescriptor>,
        directState: UsbDirectSinkState,
        directDeviceName: String?,
    ): String? {
        val uiState = DacProfilesUiResolver.resolve(
            profiles = profiles,
            connected = connected,
            activeEvidence = DacActiveRouteEvidence(directState, directDeviceName),
        )
        val active = uiState.profiles.filter { it.isSaved && it.status == DacLiveStatus.ACTIVE }
        if (active.size == 1) return active.single().profile.profileId
        if (active.isNotEmpty()) return null
        val merelyConnected = uiState.profiles.filter {
            it.isSaved && it.status == DacLiveStatus.CONNECTED
        }
        return merelyConnected.singleOrNull()?.profile?.profileId
    }
}

object HeadphoneProfilesUiResolver {
    fun resolve(
        state: HeadphoneProfileState,
        dacs: List<DacProfile>,
        currentDacProfileId: String?,
    ): HeadphoneProfilesUiState {
        val dacNames = dacs.associate { it.profileId to it.displayName }
        val items = state.profiles.map { profile ->
            val associatedDacId = when {
                currentDacProfileId != null &&
                    state.dacAssociations[currentDacProfileId] == profile.profileId -> currentDacProfileId
                else -> state.dacAssociations.entries
                    .firstOrNull { it.value == profile.profileId }
                    ?.key
            }
            HeadphoneProfileUiItem(
                profile = profile,
                isSelected = state.selectedProfileId == profile.profileId,
                associatedDacName = associatedDacId?.let(dacNames::get),
            )
        }.sortedWith(
            compareByDescending<HeadphoneProfileUiItem> { it.isSelected }
                .thenByDescending { it.profile.updatedAtEpochMs }
                .thenBy { it.profile.displayName.lowercase() }
        )
        val currentDacEq = dacs.firstOrNull { it.profileId == currentDacProfileId }?.let { dac ->
            FiioHidProfiles.match(dac.vendorId, dac.productId, dac.detectedProduct)?.let { profile ->
                CurrentDacEq(dac.profileId, dac.displayName, DacEqCapability.of(profile), profile.canBeWritten)
            }
        }
        return HeadphoneProfilesUiState(
            profiles = items,
            selected = items.firstOrNull { it.isSelected },
            currentDacProfileId = currentDacProfileId,
            associationByDacProfileId = state.dacAssociations,
            currentDacEq = currentDacEq,
        )
    }
}

@HiltViewModel
class HeadphoneProfilesViewModel @Inject constructor(
    private val repository: HeadphoneProfileRepository,
    private val equalizerCoordinator: HeadphoneEqualizerCoordinator,
    dacRepository: DacProfileRepository,
    playerEngine: DualPlayerEngine,
) : ViewModel() {
    private val currentDacProfileId = combine(
        dacRepository.profiles,
        dacRepository.connected,
        playerEngine.usbAudioDiagnostics,
    ) { profiles, connected, diagnostics ->
        DacHeadphoneContextResolver.resolve(
            profiles = profiles,
            connected = connected,
            directState = diagnostics.state,
            directDeviceName = diagnostics.deviceName,
        )
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<HeadphoneProfilesUiState> = combine(
        repository.state,
        dacRepository.profiles,
        currentDacProfileId,
    ) { headphones, dacs, currentDac ->
        HeadphoneProfilesUiResolver.resolve(headphones, dacs, currentDac)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HeadphoneProfilesUiState())

    fun create(name: String, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            equalizerCoordinator.createAndSelect(name, currentDacProfileId.value)?.let {
                onCreated(it.profileId)
            }
        }
    }

    fun select(profileId: String, onSelected: () -> Unit = {}) {
        viewModelScope.launch {
            if (equalizerCoordinator.select(profileId, currentDacProfileId.value) != null) {
                onSelected()
            }
        }
    }

    fun rename(profileId: String, name: String) {
        viewModelScope.launch { repository.rename(profileId, name) }
    }

    fun forget(profileId: String, onForgotten: () -> Unit = {}) {
        viewModelScope.launch {
            repository.forget(profileId)
            onForgotten()
        }
    }
}
