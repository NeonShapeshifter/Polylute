package com.lostf1sh.pixelplayeross.data.audio.dsp

import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileRepository
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqualizerCoordinator
import com.lostf1sh.pixelplayeross.di.AppScope
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The bridge between the persisted headphone EQ state and the audio thread.
 *
 * Audio processing cannot block on a DataStore read or on a suspend call, and the UI cannot hold a
 * lock the audio thread takes. So the state is pushed instead: this controller follows the single
 * effective-state stream and republishes each value as one immutable [GraphicEqSettings] snapshot
 * through an [AtomicReference]. The processor reads that reference once per buffer, which is a
 * volatile load and needs no lock, no allocation and no coroutine.
 *
 * Following the stream rather than being called by each writer means every path that can change EQ
 * — selecting a profile, dragging a slider, restoring after process death or service restart —
 * converges on the audio engine without that writer needing to know the DSP exists. There is no
 * second place where "the current EQ" can be defined and drift out of agreement.
 *
 * The snapshot is rebuilt on every emission even when nothing effectively changed; a redundant
 * rebuild costs one small allocation and a few microseconds of coefficient design.
 */
@Singleton
class HeadphoneGraphicEqController @Inject constructor(
    coordinator: HeadphoneEqualizerCoordinator,
    private val maintenanceOverrides: MaintenanceOverrideStore,
    @AppScope appScope: CoroutineScope,
    private val profiles: HeadphoneProfileRepository,
) {

    private data class Publication(val profileId: String?, val settings: GraphicEqSettings)
    private data class Preview(val profileId: String?, val mode: HeadphoneEqMode, val bands: List<Int>)
    private val published = AtomicReference(Publication(null, GraphicEqSettings.BYPASS))

    /** The last persisted state, which a live preview is layered on. */
    @Volatile
    private var persistedState: HeadphoneEqState? = null

    /** Bands being dragged in the editor, rendered before the debounced write lands (AUD-111). */
    private val preview = AtomicReference<Preview?>(null)

    private val _editorOpen = MutableStateFlow(false)

    /**
     * Whether the EQ editor is open. The routing policy keeps the signal PROCESSED while it is, so
     * editing never toggles PRISTINE ↔ PROCESSED — each toggle rebuilt the producer (AUD-110/111).
     */
    val editorOpen: StateFlow<Boolean> = _editorOpen.asStateFlow()

    init {
        appScope.launch {
            combine(coordinator.effectiveStateFlow, profiles.state) { fallback, catalog ->
                catalog.selectedProfile?.equalizer ?: fallback
            }.collect { state ->
                persistedState = state
                // Storage has caught up with the live value: the store owns the curve again. An
                // older value arriving mid-drag leaves the preview in place.
                val live = preview.get()
                if (live != null && (live.profileId != profiles.state.value.selectedProfileId ||
                    live.mode != state.mode || live.bands == state.activeBands)) {
                    preview.compareAndSet(live, null)
                }
                republish()
            }
        }
    }

    /**
     * Renders [bands] for [mode] at once, ahead of the debounced write (AUD-111). Only the band gains
     * are previewed; everything else (enabled, mode) keeps following the persisted state.
     */
    fun previewBands(mode: HeadphoneEqMode, bands: List<Int>, profileId: String?) {
        // Recheck ownership here, at the publishing boundary, rather than caching it on screen entry.
        if (profileId != profiles.state.value.selectedProfileId) return
        preview.set(Preview(profileId, mode, bands.toList()))
        republish()
    }

    fun setEditorOpen(open: Boolean) {
        _editorOpen.value = open
        if (!open) {
            preview.set(null)
            republish()
        }
    }

    private fun republish() {
        val catalog = profiles.state.value
        val state = catalog.selectedProfile?.equalizer ?: persistedState ?: return
        val live = preview.get()
        val effective = if (live != null && live.profileId == catalog.selectedProfileId && live.mode == state.mode) {
            when (live.mode) {
                HeadphoneEqMode.GRAPHIC_10 -> state.copy(bands10 = live.bands)
                HeadphoneEqMode.GRAPHIC_15 -> state.copy(bands15 = live.bands)
            }
        } else {
            state
        }
        published.set(Publication(catalog.selectedProfileId, effective.toDspSettings()))
    }

    /**
     * The snapshot the audio thread should render.
     *
     * Before the first emission this is [GraphicEqSettings.BYPASS], i.e. untouched PCM. The
     * controller is constructed before any player exists, so collection has normally already
     * delivered by the time audio starts; the fallback is bypass rather than a guess so the worst
     * case is "EQ not applied yet", never "EQ applied from stale or invented values".
     *
     * The Maintenance DSP kill is applied *here*, at the point of delivery to the audio thread,
     * rather than by clearing the profile. The user's equalizer curve is left exactly as it is, so
     * removing the override restores the same sound without anything having to be re-entered or
     * remembered — and there is no window in which the override has edited real configuration.
     */
    fun settings(): GraphicEqSettings =
        if (maintenanceOverrides.current().dspGraphDisabled()) {
            GraphicEqSettings.BYPASS
        } else {
            val snapshot = published.get()
            // If selection races collection, bypass the old publication for this buffer. No stale
            // profile preview is allowed onto a newly selected profile, and no audio-thread allocation.
            if (snapshot.profileId == profiles.state.value.selectedProfileId) snapshot.settings
            else GraphicEqSettings.BYPASS
        }

    companion object {
        /**
         * Maps a profile EQ state onto the DSP's view of it.
         *
         * Only the band set the profile's mode selects is converted. The inactive set is not
         * averaged, blended or folded in, so a 15-band profile can never have its curve silently
         * influenced by leftovers in the ten-band store, and vice versa.
         */
        fun HeadphoneEqState.toDspSettings(): GraphicEqSettings {
            val bands = activeBands
            val frequencies = GraphicEqBands.frequenciesFor(bands.size)
            val gains = FloatArray(bands.size) { index -> bands[index].toFloat() }
            return GraphicEqSettings(
                enabled = enabled,
                frequenciesHz = frequencies,
                gainsDb = gains,
            )
        }
    }
}
