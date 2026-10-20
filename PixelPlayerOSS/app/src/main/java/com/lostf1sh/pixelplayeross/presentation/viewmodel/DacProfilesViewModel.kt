package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.dac.DacProfileCatalog
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.dac.UsbDacDescriptor
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DacLiveStatus {
    ACTIVE,
    CONNECTED,
    NOT_CONNECTED,
}

data class DacProfileUiItem(
    val profile: DacProfile,
    val status: DacLiveStatus,
    val isSaved: Boolean = true,
)

data class DacProfilesUiState(
    val profiles: List<DacProfileUiItem> = emptyList(),
    val current: DacProfileUiItem? = null,
    val currentAdvertisedRates: List<Int> = emptyList(),
    val currentBestBitDepth: Int? = null,
)

data class DacActiveRouteEvidence(
    val directState: UsbDirectSinkState,
    val deviceName: String?,
    val advertisedRates: List<Int> = emptyList(),
    val bestBitDepth: Int? = null,
)

object DacProfilesUiResolver {
    fun resolve(
        profiles: List<DacProfile>,
        connected: List<UsbDacDescriptor>,
        activeEvidence: DacActiveRouteEvidence,
    ): DacProfilesUiState {
        val activeDescriptor = if (activeEvidence.directState == UsbDirectSinkState.USB_DIRECT_ACTIVE) {
            val name = activeEvidence.deviceName?.trim()?.takeIf { it.isNotEmpty() }
            val matches = connected.filter { descriptor ->
                name != null && (
                    descriptor.product.equals(name, ignoreCase = true) ||
                        descriptor.connectionPath == name ||
                        descriptor.detectedName.equals(name, ignoreCase = true)
                    )
            }
            matches.singleOrNull()
        } else {
            null
        }

        val savedItems = profiles.map { profile ->
            val descriptor = connected.firstOrNull {
                DacProfileCatalog.findForDescriptor(listOf(profile), it) != null
            }
            DacProfileUiItem(
                profile = profile,
                status = when {
                    descriptor == null -> DacLiveStatus.NOT_CONNECTED
                    descriptor == activeDescriptor -> DacLiveStatus.ACTIVE
                    else -> DacLiveStatus.CONNECTED
                },
            )
        }
        val unsavedItems = connected.mapNotNull { descriptor ->
            if (profiles.any { DacProfileCatalog.findForDescriptor(listOf(it), descriptor) != null }) {
                return@mapNotNull null
            }
            val transient = DacProfileCatalog.register(emptyList(), descriptor, 0L).profile
            DacProfileUiItem(
                profile = transient,
                status = if (descriptor == activeDescriptor) DacLiveStatus.ACTIVE else DacLiveStatus.CONNECTED,
                isSaved = false,
            )
        }
        val all = (savedItems + unsavedItems).sortedWith(
            compareBy<DacProfileUiItem> {
                when (it.status) {
                    DacLiveStatus.ACTIVE -> 0
                    DacLiveStatus.CONNECTED -> 1
                    DacLiveStatus.NOT_CONNECTED -> 2
                }
            }.thenByDescending { it.profile.lastSeenEpochMs }
                .thenBy { it.profile.displayName.lowercase() }
        )
        return DacProfilesUiState(
            profiles = all,
            current = all.firstOrNull { it.status == DacLiveStatus.ACTIVE }
                ?: all.firstOrNull { it.status == DacLiveStatus.CONNECTED },
            currentAdvertisedRates = activeEvidence.advertisedRates.distinct().sorted(),
            currentBestBitDepth = activeEvidence.bestBitDepth,
        )
    }
}

@HiltViewModel
class DacProfilesViewModel @Inject constructor(
    private val repository: DacProfileRepository,
    dualPlayerEngine: DualPlayerEngine,
) : ViewModel() {
    val uiState: StateFlow<DacProfilesUiState> = combine(
        repository.profiles,
        repository.connected,
        dualPlayerEngine.usbAudioDiagnostics,
        dualPlayerEngine.audioRouteState,
    ) { profiles, connected, diagnostics, route ->
        DacProfilesUiResolver.resolve(
            profiles = profiles,
            connected = connected,
            activeEvidence = DacActiveRouteEvidence(
                directState = if (route.actualBackend == AudioBackend.USB_DIRECT) {
                    UsbDirectSinkState.USB_DIRECT_ACTIVE
                } else {
                    diagnostics.state.takeUnless { it == UsbDirectSinkState.USB_DIRECT_ACTIVE }
                        ?: UsbDirectSinkState.ANDROID_AUDIO
                },
                deviceName = diagnostics.deviceName,
                advertisedRates = diagnostics.dacSupportedRates,
                bestBitDepth = diagnostics.dacBestBitDepth,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DacProfilesUiState())

    init {
        repository.start()
        repository.reconcile()
    }

    fun refresh() = repository.reconcile()

    fun rename(profileId: String, displayName: String) {
        viewModelScope.launch { repository.rename(profileId, displayName) }
    }

    fun forget(profileId: String) {
        viewModelScope.launch { repository.forget(profileId) }
    }
}
