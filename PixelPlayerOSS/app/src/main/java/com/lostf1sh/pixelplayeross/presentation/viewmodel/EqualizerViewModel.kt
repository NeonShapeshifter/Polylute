package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqBands
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerManager
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqualizerCoordinator
import com.lostf1sh.pixelplayeross.data.preferences.EqualizerPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.di.AppScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

data class EqualizerUiState(
    val isEnabled: Boolean = false,
    val currentPreset: EqualizerPreset = EqualizerPreset.FLAT,
    /** The gains of the active [mode], in the order of [bandFrequencies]. */
    val bandLevels: ImmutableList<Int> = List(HeadphoneEqState.BAND_COUNT_10) { 0 }.toImmutableList(),
    /** Labels of the filters the active [mode] actually runs, one per entry in [bandLevels]. */
    val bandFrequencies: ImmutableList<String> =
        GraphicEqBands.labelsFor(HeadphoneEqState.BAND_COUNT_10).toImmutableList(),
    val mode: HeadphoneEqMode = HeadphoneEqMode.GRAPHIC_10,
    val editingPresetName: String? = null,
    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Float = 0f,
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Float = 0f,
    val loudnessEnhancerEnabled: Boolean = false,
    val loudnessEnhancerStrength: Float = 0f,
    val isBassBoostSupported: Boolean = true,
    val isVirtualizerSupported: Boolean = true,
    val isLoudnessEnhancerSupported: Boolean = true,
    val isBassBoostDismissed: Boolean = false,
    val isVirtualizerDismissed: Boolean = false,
    val isLoudnessDismissed: Boolean = false,
    val customPresets: ImmutableList<EqualizerPreset> = persistentListOf(),
    val pinnedPresetsNames: ImmutableList<String> = persistentListOf(),
) {
    val accessiblePresets: ImmutableList<EqualizerPreset>
        get() {
            return pinnedPresetsNames.mapNotNull { name ->
                customPresets.find { it.name == name }
                    ?: EqualizerPreset.fromName(name)
            }.toImmutableList()
        }

    val allAvailablePresets: ImmutableList<EqualizerPreset>
        get() = (EqualizerPreset.ALL_PRESETS + customPresets).toImmutableList()
}

/**
 * The band count of a mode, next to the mode so the two cannot drift apart.
 *
 * Both counts are spelled out rather than derived from one another: a future third mode has to
 * state its own count instead of silently inheriting one.
 */
private val HeadphoneEqMode.bandCount: Int
    get() = when (this) {
        HeadphoneEqMode.GRAPHIC_10 -> HeadphoneEqState.BAND_COUNT_10
        HeadphoneEqMode.GRAPHIC_15 -> HeadphoneEqState.BAND_COUNT_15
    }

/**
 * The preset name to persist when the Equalizer screen is left without any edit having been made.
 *
 * A named preset keeps its name only while its own curve is what is stored. As soon as the bands in
 * front of it are something else, the truthful label is `custom` — so this is a comparison rather
 * than a copy, and it never invents a name for a curve the user actually moved.
 */
internal fun presetNameForExit(preset: EqualizerPreset, bands: List<Int>): String =
    if (preset.bandLevels == bands) preset.name else "custom"

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val equalizerManager: EqualizerManager,
    private val equalizerPreferencesRepository: EqualizerPreferencesRepository,
    private val headphoneEqualizerCoordinator: HeadphoneEqualizerCoordinator,
    private val dualPlayerEngine: DualPlayerEngine,
    private val headphoneProfiles: com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileRepository,
    audioStatusSource: AudioStatusSource,
    private val safetyVolumeController: com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController,
    private val graphicEqController: com.lostf1sh.pixelplayeross.data.audio.dsp.HeadphoneGraphicEqController,
    @param:AppScope private val appScope: CoroutineScope,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    /**
     * The profile this screen is editing, taken from its route.
     *
     * Every write below targets this id rather than "whatever is selected". The route has always
     * named a profile — `equalizer/{headphoneProfileId}` — but the screen used to ignore it and act
     * on the selection, which `DacHeadphoneAssociationController` can change at any moment. A
     * debounced band edit that landed after such a change wrote to the wrong profile's record.
     */
    private val editingProfileId: String? =
        savedStateHandle.get<String>("headphoneProfileId")?.takeIf { it.isNotBlank() }

    /** The profile this screen edits, for the screen that names it (B22: "TYGR · con SNOWSKY Melody"). */
    val editedProfileId: String? get() = editingProfileId

    companion object {
        private const val TAG = "EqualizerViewModel"
        private const val SLIDER_PERSIST_DEBOUNCE_MS = 150L
    }

    /**
     * Bands the user is currently dragging, held until the debounced write has been observed.
     *
     * Without this the screen would snap back to the previously persisted curve for the ~150 ms
     * between a slider release and the write landing, because every other preference change
     * re-emits the whole state from the store.
     */
    private data class PendingEdit(
        val mode: HeadphoneEqMode,
        val bands: List<Int>,
        val preset: EqualizerPreset,
    )

    private var pendingEdit: PendingEdit? = null

    private val _uiState = MutableStateFlow(EqualizerUiState())
    val uiState: StateFlow<EqualizerUiState> = _uiState.asStateFlow()

    val systemVolume = safetyVolumeController.state

    /** What is applied right now (B21: the one [AudioStatus]); the screens ask it about this profile's EQ. */
    val audioStatus: StateFlow<AudioStatus> = audioStatusSource.status

    val outputContext: StateFlow<EqualizerOutputContext> = combine(
        headphoneProfiles.state,
        audioStatusSource.status,
    ) { profiles, audio ->
        equalizerOutputContext(profiles, editingProfileId, audio)
    }.distinctUntilChanged().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), EqualizerOutputContext(),
    )

    private var persistBandLevelsJob: Job? = null
    private var persistBassBoostJob: Job? = null
    private var persistVirtualizerJob: Job? = null
    private var persistLoudnessJob: Job? = null

    init {
        // While this editor is open the signal stays PROCESSED, so editing never toggles the mode
        // and rebuilds the producer (AUD-110/111).
        graphicEqController.setEditorOpen(true)
        initializeEqualizer()
        observeEqualizerState()
        adoptEditedProfile()
    }

    /**
     * Makes the profile named in the route the selected one, once, on entry.
     *
     * Selection is what decides which curve the DSP applies and which record the effects read, so
     * a screen open on a profile that is not selected would edit one curve while the device played
     * another. Doing it here is a deliberate act on open, not a side effect of navigation — the
     * writes themselves no longer depend on it either way.
     */
    private fun adoptEditedProfile() {
        val profileId = editingProfileId ?: return
        viewModelScope.launch {
            if (headphoneEqualizerCoordinator.selectedProfileId() != profileId) {
                headphoneEqualizerCoordinator.select(profileId)
            }
        }
    }

    fun setSystemVolume(raw: Int) = safetyVolumeController.setRawLevel(raw)

    private fun initializeEqualizer() {
        viewModelScope.launch {
            Timber.tag(TAG).d("Initializing equalizer screen...")

            // Only the Android effect extras are restored here; MusicService does the same at
            // startup. The Graphic EQ needs neither, because software DSP follows the persisted
            // state stream on its own and works even before any audio session exists.
            if (!equalizerManager.isAttached) {
                headphoneEqualizerCoordinator.ensureProfileAuthority()
                val eq = headphoneEqualizerCoordinator.currentEffectiveState()
                equalizerManager.restoreState(
                    bassBoostEnabled = eq.bassBoostEnabled,
                    bassBoostStrength = eq.bassBoostStrength,
                    virtualizerEnabled = eq.virtualizerEnabled,
                    virtualizerStrength = eq.virtualizerStrength,
                    loudnessEnabled = eq.loudnessEnhancerEnabled,
                    loudnessStrength = eq.loudnessEnhancerStrength,
                )

            } else {
                Timber.tag(TAG).d("Audio effects already attached by service, skipping restore.")
            }

            _uiState.update { it.refreshEffectSupport() }

            dualPlayerEngine.activeAudioSessionId.collect { sessionId ->
                if (sessionId != 0) {
                    Timber.tag(TAG).d("Audio Session ID changed to $sessionId.")
                    _uiState.update { it.refreshEffectSupport() }
                }
            }
        }
    }

    private fun EqualizerUiState.refreshEffectSupport() = copy(
        isBassBoostSupported = equalizerManager.isBassBoostSupported(),
        isVirtualizerSupported = equalizerManager.isVirtualizerSupported(),
        isLoudnessEnhancerSupported = equalizerManager.isLoudnessEnhancerSupported(),
    )

    private fun observeEqualizerState() {
        viewModelScope.launch {
            combine(
                headphoneProfiles.state.map { editedEqualizer(it, editingProfileId) }.distinctUntilChanged(),
                equalizerPreferencesRepository.customPresetsFlow,
                equalizerPreferencesRepository.pinnedPresetsFlow,
            ) { eq, customPresets, pinnedPresets ->
                val pending = pendingEdit
                val persistedActive = if (eq.mode == HeadphoneEqMode.GRAPHIC_15) eq.bands15 else eq.bands10
                if (pending != null && pending.mode == eq.mode && pending.bands == persistedActive) {
                    // The debounced write landed; stop overriding the store.
                    pendingEdit = null
                }
                val activeBands = pending?.takeIf { it.mode == eq.mode }?.bands ?: persistedActive

                val currentPreset = when {
                    eq.mode == HeadphoneEqMode.GRAPHIC_15 -> EqualizerPreset.custom(activeBands)
                    eq.presetName == "custom" -> EqualizerPreset.custom(activeBands)
                    else -> customPresets.find { it.name == eq.presetName }
                        ?: EqualizerPreset.fromName(eq.presetName)
                }

                EqualizerUiState(
                    isEnabled = eq.enabled,
                    currentPreset = currentPreset,
                    bandLevels = activeBands.toImmutableList(),
                    bandFrequencies = GraphicEqBands.labelsFor(eq.mode.bandCount).toImmutableList(),
                    mode = eq.mode,
                    editingPresetName = _uiState.value.editingPresetName
                        .takeIf { eq.mode == HeadphoneEqMode.GRAPHIC_10 },
                    bassBoostEnabled = eq.bassBoostEnabled,
                    bassBoostStrength = eq.bassBoostStrength.toFloat(),
                    virtualizerEnabled = eq.virtualizerEnabled,
                    virtualizerStrength = eq.virtualizerStrength.toFloat(),
                    loudnessEnhancerEnabled = eq.loudnessEnhancerEnabled,
                    loudnessEnhancerStrength = eq.loudnessEnhancerStrength.toFloat(),
                    isBassBoostDismissed = eq.bassBoostDismissed,
                    isVirtualizerDismissed = eq.virtualizerDismissed,
                    isLoudnessDismissed = eq.loudnessDismissed,
                    customPresets = customPresets.toImmutableList(),
                    pinnedPresetsNames = pinnedPresets.toImmutableList(),
                    isBassBoostSupported = _uiState.value.isBassBoostSupported,
                    isVirtualizerSupported = _uiState.value.isVirtualizerSupported,
                    isLoudnessEnhancerSupported = _uiState.value.isLoudnessEnhancerSupported
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    /**
     * Switches which band set is presented and applied.
     *
     * The two sets are stored independently and are never derived from one another, so switching
     * modes and switching back restores exactly what was last edited in each.
     */
    fun setMode(mode: HeadphoneEqMode) {
        if (_uiState.value.mode == mode) return
        pendingEdit = null
        viewModelScope.launch {
            writeToEditedProfile { it.copy(mode = mode) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _uiState.update { current ->
            current.copy(isEnabled = enabled)
        }
        viewModelScope.launch {
            writeToEditedProfile { it.copy(enabled = enabled) }
        }
    }

    fun toggleEqualizer() {
        setEnabled(!_uiState.value.isEnabled)
    }

    fun selectPreset(preset: EqualizerPreset) {
        // Presets are ten-band curves. Applying one while the fifteen-band set is active would
        // either overwrite it with ten values or silently drop five bands, so the screen restricts
        // presets to the ten-band mode and this refuses the switch rather than guessing.
        if (_uiState.value.mode != HeadphoneEqMode.GRAPHIC_10) return
        persistBandLevelsJob?.cancel()
        pendingEdit = PendingEdit(HeadphoneEqMode.GRAPHIC_10, preset.bandLevels, preset)
        _uiState.update { current ->
            current.copy(
                currentPreset = preset,
                bandLevels = preset.bandLevels.toImmutableList(),
                editingPresetName = null
            )
        }
        viewModelScope.launch {
            writeToEditedProfile {
                it.copy(presetName = preset.name, bands10 = preset.bandLevels)
            }
        }
    }

    fun setBandLevel(bandIndex: Int, level: Int) {
        val current = _uiState.value
        if (bandIndex !in current.bandLevels.indices) return
        val clampedLevel = level.coerceIn(-15, 15)
        val updatedBands = current.bandLevels.toMutableList().also { it[bandIndex] = clampedLevel }
        val mode = current.mode
        pendingEdit = PendingEdit(mode, updatedBands, current.currentPreset)

        _uiState.update { state ->
            val editingName = if (mode == HeadphoneEqMode.GRAPHIC_10) {
                state.editingPresetName
                    ?: state.currentPreset.name.takeIf { state.currentPreset.isCustom && it != "custom" }
            } else {
                null
            }
            state.copy(
                // In the fifteen-band mode there is no preset to name, so the curve is presented as
                // a custom curve rather than being attributed to a ten-band preset it does not match.
                currentPreset = if (mode == HeadphoneEqMode.GRAPHIC_10) {
                    EqualizerPreset.custom(updatedBands)
                } else {
                    state.currentPreset
                },
                bandLevels = updatedBands.toImmutableList(),
                editingPresetName = editingName
            )
        }

        // The live value goes to the DSP now; only the write to storage is debounced (AUD-111).
        graphicEqController.previewBands(mode, updatedBands,
            editingProfileId ?: headphoneProfiles.state.value.selectedProfileId)

        persistBandLevelsJob?.cancel()
        persistBandLevelsJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            persistBands(mode, updatedBands, current.currentPreset)
        }
    }

    /**
     * Writes one band set, and only that one.
     *
     * A fifteen-band edit never touches the ten-band keys, so the two sets stay genuinely
     * independent rather than one shadowing the other.
     */
    private suspend fun persistBands(
        mode: HeadphoneEqMode,
        bands: List<Int>,
        preset: EqualizerPreset = EqualizerPreset.custom(bands),
    ) {
        writeToEditedProfile { current ->
            when (mode) {
                HeadphoneEqMode.GRAPHIC_15 -> current.copy(bands15 = bands)
                // The name follows the curve rather than the gesture: dragging a band back onto the
                // preset's own curve leaves the preset selected, and moving any band off it makes
                // the curve custom. Stamping every ten-band write as `custom` renamed a curve that
                // had not actually changed.
                HeadphoneEqMode.GRAPHIC_10 -> current.copy(
                    bands10 = bands,
                    presetName = presetNameForExit(preset, bands),
                )
            }
        }
    }

    /**
     * Routes a write to the profile named in the route, falling back to the selected profile only
     * when the route did not name one.
     *
     * The fallback keeps a screen opened without the argument working exactly as it did before,
     * rather than silently dropping the user's edit.
     */
    private suspend fun writeToEditedProfile(transform: (HeadphoneEqState) -> HeadphoneEqState) {
        val profileId = editingProfileId
        if (profileId == null) {
            headphoneEqualizerCoordinator.updateEffectiveState(transform)
        } else {
            headphoneEqualizerCoordinator.updateEqualizer(profileId, transform)
        }
    }

    /**
     * Clears the active band set to 0 dB, and only the active band set.
     *
     * The inactive mode's curve is deliberately untouched: it is a separate store the user also
     * authored, and "reset" on a screen showing the ten-band editor means the ten bands in front of
     * them. The pending debounced edit is dropped first, or a drag still in flight would write the
     * old curve back over the reset a moment later.
     */
    fun resetActiveBandsToFlat() {
        val mode = _uiState.value.mode
        persistBandLevelsJob?.cancel()
        pendingEdit = null
        _uiState.update { state ->
            state.copy(
                bandLevels = List(state.bandLevels.size) { 0 }.toImmutableList(),
                currentPreset = if (mode == HeadphoneEqMode.GRAPHIC_10) {
                    EqualizerPreset.FLAT
                } else {
                    // Fifteen bands have no preset to name, and inventing one would be a claim the
                    // model cannot support. It is a flat custom curve.
                    EqualizerPreset.custom(List(HeadphoneEqState.BAND_COUNT_15) { 0 })
                },
                editingPresetName = null,
            )
        }
        viewModelScope.launch {
            writeToEditedProfile { current ->
                when (mode) {
                    HeadphoneEqMode.GRAPHIC_15 ->
                        current.copy(bands15 = List(HeadphoneEqState.BAND_COUNT_15) { 0 })
                    HeadphoneEqMode.GRAPHIC_10 -> current.copy(
                        bands10 = List(HeadphoneEqState.BAND_COUNT_10) { 0 },
                        presetName = "flat",
                    )
                }
            }
        }
    }

    fun saveCurrentAsCustomPreset(name: String) {
        if (_uiState.value.mode != HeadphoneEqMode.GRAPHIC_10) return
        viewModelScope.launch {
            val bands = _uiState.value.bandLevels
            val preset = EqualizerPreset(name, name, bands, true)
            equalizerPreferencesRepository.saveCustomPreset(preset)

            togglePinPreset(name)

            selectPreset(preset)
        }
    }

    fun deleteCustomPreset(preset: EqualizerPreset) {
        viewModelScope.launch {
            equalizerPreferencesRepository.deleteCustomPreset(preset.name)
            if (_uiState.value.currentPreset.name == preset.name) {
                selectPreset(EqualizerPreset.FLAT)
            }
        }
    }

    fun renameCustomPreset(oldName: String, newName: String) {
        if (newName.isBlank() || oldName == newName) return
        viewModelScope.launch {
            equalizerPreferencesRepository.renameCustomPreset(oldName, newName)
            writeToEditedProfile {
                if (it.presetName == oldName) it.copy(presetName = newName) else it
            }
        }
    }

    fun updateCustomPresetBands(presetName: String) {
        if (_uiState.value.mode != HeadphoneEqMode.GRAPHIC_10) return
        viewModelScope.launch {
            val bands = _uiState.value.bandLevels
            equalizerPreferencesRepository.updateCustomPresetBands(presetName, bands)
            selectPreset(EqualizerPreset(presetName, presetName, bands, true))
        }
    }

    fun setBassBoostEnabled(enabled: Boolean) {
        equalizerManager.setBassBoostEnabled(enabled)
        _uiState.update { current ->
            current.copy(bassBoostEnabled = enabled)
        }
        viewModelScope.launch {
            headphoneEqualizerCoordinator.updateEffectiveState { it.copy(bassBoostEnabled = enabled) }
        }
    }

    fun setBassBoostStrength(strength: Int) {
        val clampedStrength = strength.coerceIn(0, 1000)
        equalizerManager.setBassBoostStrength(clampedStrength)
        _uiState.update { current ->
            current.copy(bassBoostStrength = clampedStrength.toFloat())
        }

        persistBassBoostJob?.cancel()
        persistBassBoostJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            headphoneEqualizerCoordinator.updateEffectiveState { it.copy(bassBoostStrength = clampedStrength) }
        }
    }

    fun setVirtualizerEnabled(enabled: Boolean) {
        equalizerManager.setVirtualizerEnabled(enabled)
        _uiState.update { current ->
            current.copy(virtualizerEnabled = enabled)
        }
        viewModelScope.launch {
            headphoneEqualizerCoordinator.updateEffectiveState { it.copy(virtualizerEnabled = enabled) }
        }
    }

    fun setVirtualizerStrength(strength: Int) {
        val clampedStrength = strength.coerceIn(0, 1000)
        equalizerManager.setVirtualizerStrength(clampedStrength)
        _uiState.update { current ->
            current.copy(virtualizerStrength = clampedStrength.toFloat())
        }

        persistVirtualizerJob?.cancel()
        persistVirtualizerJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            headphoneEqualizerCoordinator.updateEffectiveState { it.copy(virtualizerStrength = clampedStrength) }
        }
    }

    fun setLoudnessEnhancerEnabled(enabled: Boolean) {
        equalizerManager.setLoudnessEnhancerEnabled(enabled)
        _uiState.update { current ->
            current.copy(loudnessEnhancerEnabled = enabled)
        }
        viewModelScope.launch {
            headphoneEqualizerCoordinator.updateEffectiveState { it.copy(loudnessEnhancerEnabled = enabled) }
        }
    }

    fun setLoudnessEnhancerStrength(strength: Int) {
        val clampedStrength = strength.coerceIn(0, 1000)
        equalizerManager.setLoudnessEnhancerStrength(clampedStrength)
        _uiState.update { current ->
            current.copy(loudnessEnhancerStrength = clampedStrength.toFloat())
        }

        persistLoudnessJob?.cancel()
        persistLoudnessJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            headphoneEqualizerCoordinator.updateEffectiveState {
                it.copy(loudnessEnhancerStrength = clampedStrength)
            }
        }
    }

    fun setBassBoostDismissed(dismissed: Boolean) {
        viewModelScope.launch {
            headphoneEqualizerCoordinator.updateEffectiveState { it.copy(bassBoostDismissed = dismissed) }
        }
    }

    fun setVirtualizerDismissed(dismissed: Boolean) {
        viewModelScope.launch {
            headphoneEqualizerCoordinator.updateEffectiveState { it.copy(virtualizerDismissed = dismissed) }
        }
    }

    fun setLoudnessDismissed(dismissed: Boolean) {
        viewModelScope.launch {
            headphoneEqualizerCoordinator.updateEffectiveState { it.copy(loudnessDismissed = dismissed) }
        }
    }

    fun updatePinnedPresetsOrder(newOrder: List<String>) {
        viewModelScope.launch {
            equalizerPreferencesRepository.setPinnedPresets(newOrder)
        }
    }

    fun resetPinnedPresetsToDefault() {
        viewModelScope.launch {
            val defaultOrder = EqualizerPreset.ALL_PRESETS.map { it.name }
            equalizerPreferencesRepository.setPinnedPresets(defaultOrder)
        }
    }

    fun togglePinPreset(presetName: String) {
        viewModelScope.launch {
            val currentPinned = _uiState.value.pinnedPresetsNames.toMutableList()
            if (currentPinned.contains(presetName)) {
                currentPinned.remove(presetName)
            } else {
                currentPinned.add(presetName)
            }
            equalizerPreferencesRepository.setPinnedPresets(currentPinned)
        }
    }

    /**
     * Finishes the one edit that may still be in flight, and nothing else.
     *
     * This used to push the whole of this instance's `_uiState` — enabled, both band sets, all three
     * effects — back into the store. That is not a flush, it is a write of a snapshot, and with more
     * than one screen hosting this view model it meant the instance that happened to be destroyed
     * last won: curve, preset name, bypass state and effect strengths all reverted to whatever that
     * screen had last seen. It is the reason an EQ edit made in one place reappeared as a different
     * curve somewhere else.
     *
     * Every user edit already writes through as it happens, so the only thing that can genuinely
     * still be pending is a slider drag inside the [SLIDER_PERSIST_DEBOUNCE_MS] window. That, and
     * only that, is written here.
     */
    override fun onCleared() {
        val pending = pendingEdit
        persistBandLevelsJob?.cancel()
        persistBassBoostJob?.cancel()
        persistVirtualizerJob?.cancel()
        persistLoudnessJob?.cancel()
        if (pending != null) {
            appScope.launch {
                runCatching { persistBands(pending.mode, pending.bands, pending.preset) }
                    .onFailure { Timber.tag(TAG).w(it, "Failed to finish a pending band edit") }
                // Closed after the last drag is stored, so the curve does not step back meanwhile.
                graphicEqController.setEditorOpen(false)
            }
        } else {
            graphicEqController.setEditorOpen(false)
        }
        super.onCleared()
        Timber.tag(TAG).d("ViewModel cleared")
    }
}
