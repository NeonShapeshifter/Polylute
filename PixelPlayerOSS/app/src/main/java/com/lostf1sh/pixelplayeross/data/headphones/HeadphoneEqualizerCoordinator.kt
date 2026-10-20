package com.lostf1sh.pixelplayeross.data.headphones

import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerManager
import com.lostf1sh.pixelplayeross.data.preferences.EqualizerPreferencesRepository
import com.lostf1sh.pixelplayeross.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Single write boundary for profile-owned EQ. Legacy effective keys are migration input only. */
@Singleton
class HeadphoneEqualizerCoordinator @Inject constructor(
    private val profiles: HeadphoneProfileRepository,
    private val legacyPreferences: EqualizerPreferencesRepository,
    private val equalizerManager: EqualizerManager,
    @AppScope appScope: CoroutineScope,
) {
    private val selectionMutex = Mutex()
    private val published = MutableStateFlow(HeadphoneEqState())

    val effectiveStateFlow: StateFlow<HeadphoneEqState> = published.asStateFlow()

    init {
        appScope.launch {
            ensureProfileAuthority()
            profiles.state.collect { state ->
                state.selectedProfile?.equalizer?.let { published.value = it }
            }
        }
    }

    suspend fun currentEffectiveState(): HeadphoneEqState = profiles.currentState()
        .selectedProfile?.equalizer ?: published.value

    suspend fun createAndSelect(displayName: String, dacProfileId: String? = null): HeadphoneProfile? =
        selectionMutex.withLock {
            ensureProfileAuthorityLocked()
            profiles.createAndSelect(displayName, published.value, dacProfileId)?.also {
                published.value = it.equalizer
            }
        }

    suspend fun select(profileId: String, dacProfileId: String? = null): HeadphoneProfile? =
        selectionMutex.withLock {
            val selected = profiles.select(profileId, dacProfileId) ?: return@withLock null
            published.value = selected.equalizer
            publishAndroidExtras(selected.equalizer)
            selected
        }

    suspend fun restoreAssociation(dacProfileId: String): HeadphoneProfile? =
        selectionMutex.withLock {
            val associated = profiles.restoreAssociation(dacProfileId) ?: return@withLock null
            published.value = associated.equalizer
            publishAndroidExtras(associated.equalizer)
            associated
        }

    suspend fun replaceEffectiveState(state: HeadphoneEqState) = selectionMutex.withLock {
        ensureProfileAuthorityLocked()
        val normalized = state.normalized()
        profiles.updateSelectedEqualizer(normalized)
        published.value = normalized
        publishAndroidExtras(normalized)
    }

    suspend fun updateEffectiveState(transform: (HeadphoneEqState) -> HeadphoneEqState) =
        selectionMutex.withLock {
            ensureProfileAuthorityLocked()
            profiles.updateSelectedEqualizer(transform)?.let {
                published.value = it
                publishAndroidExtras(it)
            }
        }

    /**
     * Writes the equalizer of one named profile.
     *
     * This is the write the Equalizer screen uses. [updateEffectiveState] targets whatever is
     * selected, which is right for a control acting on "the current headphones" and wrong for a
     * screen that is open on a specific profile: the selection can move while a debounced band
     * edit is still in flight, and the edit then lands on the profile that just became selected.
     *
     * The applied-signal state follows only when the written profile is the selected one — editing
     * a profile that is not carrying audio must persist without changing what is being processed.
     */
    suspend fun updateEqualizer(
        profileId: String,
        transform: (HeadphoneEqState) -> HeadphoneEqState,
    ): HeadphoneEqState? = selectionMutex.withLock {
        ensureProfileAuthorityLocked()
        profiles.updateEqualizer(profileId, transform)?.let { write ->
            if (write.wasSelected) {
                published.value = write.state
                publishAndroidExtras(write.state)
            }
            write.state
        }
    }

    /** The profile currently carrying audio, which is the one the DSP settings describe. */
    suspend fun selectedProfileId(): String? = profiles.currentState().selectedProfileId

    suspend fun ensureProfileAuthority() = selectionMutex.withLock { ensureProfileAuthorityLocked() }

    private suspend fun ensureProfileAuthorityLocked() {
        profiles.migrateLegacyEqualizerIfNeeded(legacyPreferences.readLegacyStateForMigration())
        profiles.currentState().selectedProfile?.equalizer?.let { published.value = it }
    }

    private fun publishAndroidExtras(state: HeadphoneEqState) {
        val normalized = state.normalized()
        equalizerManager.restoreState(
            normalized.bassBoostEnabled,
            normalized.bassBoostStrength,
            normalized.virtualizerEnabled,
            normalized.virtualizerStrength,
            normalized.loudnessEnhancerEnabled,
            normalized.loudnessEnhancerStrength,
        )
    }
}
