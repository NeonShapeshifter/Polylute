package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.dac.fiio.AttachedFiio
import com.lostf1sh.pixelplayeross.data.dac.fiio.AttachmentToken
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBackup
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBackupState
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqDefaults
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLive
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqSlotSnapshot
import com.lostf1sh.pixelplayeross.data.dac.fiio.PreGainSense
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCapability
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCurve
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqReadout
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqService
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqVerdict
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteAvailability
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteResult
import com.lostf1sh.pixelplayeross.data.dac.fiio.EqRead
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioFilterType
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfile
import com.lostf1sh.pixelplayeross.data.dac.fiio.GateRefusal
import com.lostf1sh.pixelplayeross.data.dac.fiio.WriteInitiator
import com.lostf1sh.pixelplayeross.data.headphones.DacEqCompat
import com.lostf1sh.pixelplayeross.data.headphones.DacEqFilterDef
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPairDac
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPreset
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPresetCatalog
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPresetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

/**
 * B30-P3: the Resonance screen when it edits the DAC's own EQ. Plain Kotlin with the scope handed in, so the tests drive it with a
 * fake DAC and a test clock; [DacEqViewModel] is the thin Hilt wrapper.
 *
 * **What it does, and does not.**
 * - It edits the DAC's real filters (the DAC's band count, filter types and limits; ten for the Melody) plus the pre-gain, which it
 *   shows as what it is: computed from the curve by the same function the gate uses.
 * - **A drag is not a write.** Edits change the curve on screen at once; the DAC is written once the edits have stopped for
 *   [debounceMs], with the curve as it is then, never one write per pixel. A write that is running is not interrupted: edits made
 *   meanwhile are written after it, as one more.
 * - Every write goes through the gate (`DacEqWriter`), is read back and compared, and a read-back that differs is **shown as an
 *   error** with the backup already written back.
 * - **An edit belongs to the attachment it was made on** (B30-fix1 H2): it carries the DAC attachment (its connection and the
 *   controller's generation of it) and the pair, and is written only while all of that, and Purity being off and writing being
 *   allowed, still holds, checked when the debounce ends and again by the writer once the HID lock is won. Otherwise it is dropped
 *   with nothing sent and the screen says so. What a write that did start comes to (selection, label) is recorded against **its**
 *   DAC, even if another one is attached by then.
 * - **A recognised pair gets its saved selection shown, silently**: the state says which preset the headphones had on this DAC and
 *   whether the DAC holds it. Nothing is written until the listener taps.
 * - **The curve and the base of an edit come from the current attachment only** (B31, auditor R30-F1-02): a pending edit, the curve to
 *   turn the EQ on from and the preset a tap selected all carry the attachment they were made on, and are neither shown nor used as the
 *   base of a new edit once another attachment is current.
 * - **The slot is offered, never forced** (B31): when the app's slot is not its own yet the screen says so and offers to take it over
 *   (only while playback is paused; the gate refuses otherwise); it lists the DAC's own presets from the inventory and selects one on a
 *   tap (a pointer move, confirmed first while music plays). Nothing of this runs by itself or on connect.
 * - It never touches the software EQ, never the app's DSP, and never reads or writes volume.
 */
class DacEqScreenController(
    private val scope: CoroutineScope,
    private val headphoneId: String?,
    private val service: DacEqService,
    private val store: DacEqPresetStore,
    audio: Flow<AudioStatus>,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val waitMs: Long = ResonanceModePolicy.WAIT_MS,
) {
    /** What an edit was accepted under (B30-fix1 H2): the attachment of the DAC it was made for. */
    private data class EditOrigin(val att: AttachedFiio, val token: AttachmentToken)

    private data class Edit(
        val pending: List<DacEqBand>? = null,
        /** The attachment [pending] was accepted on; it is written to that one or to none. */
        val origin: EditOrigin? = null,
        val busy: Boolean = false,
        val error: DacEqError? = null,
        /** The curve to base a switch from "off" on, taken when the EQ was turned off. It belongs to [offBaseToken]'s attachment. */
        val offBase: List<DacEqBand>? = null,
        val offBaseToken: AttachmentToken? = null,
        /** The preset a tap selected, written with [pending] and recorded once it is. Cleared with it, so it never outlives its attachment. */
        val selection: String? = null,
        /** B31: the step the screen is running on the DAC that is not an edit of the curve. */
        val operation: DacEqOperation? = null,
        /** B31: a select of this DAC preset waits for the listener's yes because music is playing. */
        val confirmSelect: Int? = null,
    )

    private val edit = MutableStateFlow(Edit())
    private val _mode = MutableStateFlow(ResonanceMode.DECIDING)

    /** Which screen this is. Decided once and kept for as long as the screen lives (see [ResonanceMode]). */
    val mode: StateFlow<ResonanceMode> = _mode.asStateFlow()

    private var debounceJob: Job? = null
    private var drainJob: Job? = null

    @Volatile
    private var rerun = false

    /** Number of writes the DAC was sent by this screen (for the debounce test and the log). */
    @Volatile
    var writesStarted: Int = 0
        private set

    init {
        scope.launch {
            // What is attached right now, not what the controller has got round to publishing: a screen opened at once after the
            // DAC, or after the app started, must not read "no DAC" from a flow that has not caught up.
            service.controller.resolveNow()
            val readout = service.controller.readout
            val first = readout.value
            // The listener opened the screen: if a DAC the app may write is attached and its EQ has not been read yet, read it now
            // (a read, GETs only) instead of waiting for the attach read.
            val att = first.attachedOrNull
            if (att != null && service.availability(att).canWrite && first is DacEqReadout.Pending) {
                launch { service.read(att) }
            }
            var waited = 0L
            val timer = launch { delay(waitMs); waited = waitMs; decide(readout.value, waited) }
            // The backup on disk follows the DAC that is attached (B31 P1.4: the list of the DAC's presets is read from it).
            launch {
                readout.map { it.attachedOrNull }.distinctUntilChanged { a, b -> a?.attachKey == b?.attachKey }
                    .collect { service.refreshBackup(it) }
            }
            readout.collect { decide(it, waited) }
            timer.cancel()
        }
    }

    private fun decide(readout: DacEqReadout, waited: Long) {
        if (_mode.value != ResonanceMode.DECIDING) return
        val att = readout.attachedOrNull
        val next = ResonanceModePolicy.decide(att, service.availability(att), readout, waited)
        if (next != ResonanceMode.DECIDING) _mode.value = next
    }

    val uiState: StateFlow<DacEqUiState> = combine(
        service.controller.readout,
        store.state,
        audio,
        edit,
        service.backupState,
    ) { readout, profiles, status, editState, backupState -> build(readout, profiles, status, editState, backupState) }
        .stateIn(scope, SharingStarted.Eagerly, DacEqUiState())

    private fun build(
        readout: DacEqReadout,
        profiles: com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileState,
        status: AudioStatus,
        editState: Edit,
        backupState: DacEqBackupState,
    ): DacEqUiState {
        val att = readout.attachedOrNull
        val profile = profiles.profiles.firstOrNull { it.profileId == headphoneId }
        val capability = att?.let { DacEqCapability.of(it.profile) }
        val availability = service.availability(att)
        val state = (readout as? DacEqReadout.Known)?.state
        val verdict = state?.verdict ?: DacEqVerdict.UNKNOWN
        val read = readout.readBands()
        val eqOn = verdict == DacEqVerdict.ON || verdict == DacEqVerdict.FLAT
        val fiio = att?.profile
        val dacProfileId = att?.dacProfileId
        val presets = if (profile != null && dacProfileId != null) DacEqPresetCatalog.presetsFor(profile, dacProfileId) else emptyList()
        val selected = if (profile != null && dacProfileId != null) DacEqPresetCatalog.selectionFor(profile, dacProfileId) else null
        // The curve on screen: an edit not yet written, what the DAC reports while its EQ is on, else (EQ off) what turning it on
        // would write: the pair's selected preset, or flat. Nothing is written by showing it.
        val selectedBands = if (selected != null && fiio != null) DacEqCompat.bandsFor(selected, DacEqCapability.of(fiio), fiio.canBeWritten) else null
        // B31 (R30-F1-02): only what was made on the attachment that is current is shown. An edit left over from another DAC, the curve
        // an "off" was taken from, and the preset a tap selected are all dropped from view the moment the attachment changes.
        val currentToken = if (att != null) service.controller.attachment()?.takeIf { it.first.attachKey == att.attachKey }?.second else null
        val pending = editState.pending?.takeIf { currentToken != null && editState.origin?.token == currentToken }
        val offBase = editState.offBase?.takeIf { currentToken != null && editState.offBaseToken == currentToken }
        val shown: List<DacEqBand> = pending
            ?: (if (eqOn) read else null)
            ?: offBase
            ?: selectedBands
            ?: (if (att != null) flatBands(att) else emptyList())
        val sense = service.sense(att)
        val backup = backupState.backup?.takeIf { att != null && backupState.backupKey == att.backupKey }
        val owned = fiio?.appSlotId?.let { backup?.owns(it) } == true
        val presetRows = dacPresetRows(fiio, backup, (state?.activePreset as? EqRead.Read)?.value, sense)
        val preGainOrNull = if (shown.isEmpty()) 0 else DacEqCurve.preGainTenthsFor(shown, sense)
        val preGain = preGainOrNull ?: 0
        val dacPreGain = (state?.preGainTenthsDb as? EqRead.Read)?.value
        val selectedState = when {
            selected == null -> SelectedPresetState.NONE
            eqOn && read != null && dacPreGain != null &&
                selected.filters == read.map { DacEqFilterDef.of(it) } && selected.preGainTenthsDb == dacPreGain ->
                SelectedPresetState.MATCHES_DAC
            else -> SelectedPresetState.DIFFERS_FROM_DAC
        }
        return DacEqUiState(
            profileName = profile?.displayName,
            dacName = att?.displayName,
            dacProfileId = dacProfileId,
            availability = availability,
            capability = capability,
            readout = readout,
            verdict = verdict,
            bands = shown,
            preGainTenthsDb = preGain,
            appSlotName = fiio?.let { p -> p.slots.firstOrNull { it.id == p.appSlotId }?.name },
            activePresetName = state?.let { s ->
                (s.activePreset as? EqRead.Read)?.value?.let { id -> fiio?.slots?.firstOrNull { it.id == id }?.name }
            },
            busy = editState.busy,
            error = editState.error,
            pendingWrite = pending != null && !editState.busy,
            frozenByPurity = status.purityInForce,
            conflict = status.dacEqConflict(),
            directActive = status.directActive,
            presets = presets,
            selectedPresetId = selected?.presetId,
            selectedPresetState = selectedState,
            reusable = if (profile != null && dacProfileId != null) {
                DacEqPresetCatalog.reusableOn(profile, dacProfileId, capability, fiio?.canBeWritten == true)
            } else {
                emptyList()
            },
            netPeakDb = if (shown.isEmpty()) 0.0 else DacEqCurve.netPeakDb(shown, preGain, sense),
            debugOverride = availability == DacEqWriteAvailability.DEBUG_OVERRIDE,
            slotOwned = owned,
            takeOverOffered = att != null && fiio?.canBeWritten == true && availability.canWrite && !owned && editState.operation == null,
            operation = editState.operation,
            dacPresets = presetRows,
            presetsRead = fiio != null && fiio.slots.isNotEmpty() && fiio.slots.all { it.id in (backup?.slots?.keys ?: emptySet()) },
            confirmSelect = editState.confirmSelect?.let { id -> presetRows.firstOrNull { it.id == id } },
            preGainSense = sense,
        )
    }

    /** The DAC's presets by name, with what the inventory read of each (B31 P1.4). Empty where the profile lists none. */
    private fun dacPresetRows(profile: FiioHidProfile?, backup: DacEqBackup?, activeId: Int?, sense: PreGainSense): List<DacPresetRow> =
        profile?.slots?.map { slot ->
            val snapshot = backup?.slots?.get(slot.id)
            val closed = slot.id == profile.disabledPresetId
            val net = snapshot?.let { DacEqLive.of(it, sense).netPeakDb }
            DacPresetRow(
                id = slot.id,
                name = slot.name,
                isActive = slot.id == activeId,
                isAppSlot = slot.id == profile.appSlotId,
                isClosed = closed,
                readFromDac = snapshot != null,
                preGainTenthsDb = snapshot?.preGainTenthsDb,
                activeFilters = snapshot?.bands?.count { !it.isInert },
                // The same question the gate asks before it selects a slot: can it play louder than the limit under some reading.
                locked = !closed && backup?.owns(slot.id) != true && net != null && net > 0.0,
            )
        }.orEmpty()

    private fun flatBands(att: AttachedFiio): List<DacEqBand> = DacEqDefaults.flatBands(att.profile.bandCount)

    // ── Editing ─────────────────────────────────────────────────────────────────────────────────────────

    /** Changes one filter on screen at once and schedules the write. [change] receives the filter as it is now. */
    fun editBand(index: Int, change: (DacEqBand) -> DacEqBand) {
        val ui = uiState.value
        // Edits are taken while a write is running (they are written after it); not under the Pure Direct lock, not on a read-only DAC.
        if (ui.frozenByPurity || !ui.availability.canWrite || ui.operation != null) return
        val origin = currentOrigin() ?: return
        // The base is the curve of the attachment that is current: its own pending edit, else what is shown (which never includes an edit
        // left over from another attachment, see build()). A manual edit makes the curve custom, so a preset a tap selected is let go.
        val base = edit.value.pending?.takeIf { edit.value.origin?.token == origin.token } ?: ui.bands
        if (index !in base.indices) return
        val updated = base.toMutableList().also { it[index] = change(base[index]).copy(index = index) }
        edit.update { it.copy(pending = updated, origin = origin, selection = null, error = null) }
        schedule(immediate = false)
    }

    /** The Off/On picker: Off closes the DAC's EQ; On writes the curve on screen (the pair's saved one, or flat) and selects it. */
    fun setEnabled(on: Boolean) {
        val ui = uiState.value
        if (ui.frozenByPurity || !ui.availability.canWrite || ui.busy) return
        val origin = currentOrigin() ?: return
        if (!on) {
            debounceJob?.cancel()
            scope.launch {
                edit.update {
                    it.copy(busy = true, error = null, pending = null, origin = null, selection = null, offBase = ui.bands, offBaseToken = origin.token)
                }
                val result = service.screenWriter.closeEq(origin.att, WriteInitiator.USER_ACTION) { staleReason(origin) }
                handle(result, origin, closing = true)
            }
        } else {
            val pending = edit.value.pending?.takeIf { edit.value.origin?.token == origin.token }
            edit.update { it.copy(pending = pending ?: ui.bands, origin = origin, selection = null, error = null) }
            schedule(immediate = true)
        }
    }

    fun resetFlat() {
        val origin = currentOrigin() ?: return
        if (!uiState.value.canEdit) return
        edit.update { it.copy(pending = flatBands(origin.att), origin = origin, selection = null, error = null) }
        schedule(immediate = false)
    }

    /** Selects a preset of this pair: its filters become the curve and are written. Never automatic. */
    fun selectPreset(presetId: String) {
        val ui = uiState.value
        val origin = currentOrigin() ?: return
        val preset = ui.presets.firstOrNull { it.presetId == presetId } ?: return
        applyPreset(preset, origin, selectAfter = true)
    }

    /** Reuses a preset made for another pair, by the listener's choice, if it fits or can be approximated. */
    fun reusePreset(presetId: String) {
        val ui = uiState.value
        val origin = currentOrigin() ?: return
        val reuse = ui.reusable.firstOrNull { it.preset.presetId == presetId } ?: return
        applyPreset(reuse.preset, origin, selectAfter = false)
    }

    private fun applyPreset(preset: DacEqPreset, origin: EditOrigin, selectAfter: Boolean) {
        val ui = uiState.value
        if (ui.frozenByPurity || !ui.availability.canWrite || ui.busy) return
        val att = origin.att
        val bands = DacEqCompat.bandsFor(preset, DacEqCapability.of(att.profile), att.profile.canBeWritten) ?: return
        edit.update { it.copy(pending = bands, origin = origin, selection = if (selectAfter) preset.presetId else null, error = null) }
        schedule(immediate = true)
    }

    /** Saves what the DAC holds (the read-back) as a preset of this pair. Only while the EQ is on: that is what "accepted" means. */
    fun savePreset(name: String, updatePresetId: String? = null) {
        val hp = headphoneId ?: return
        val ui = uiState.value
        val att = service.attached() ?: return
        val dacProfileId = att.dacProfileId ?: return
        val state = ui.dacState ?: return
        // What is saved is what the DAC holds, so not while an edit is still waiting to be written (it would save the old curve).
        if (!ui.eqOn || !state.isComplete || ui.pendingWrite || ui.busy) return
        val accepted = state.readBands.map { DacEqFilterDef.of(it) }
        val gain = (state.preGainTenthsDb as EqRead.Read).value
        val requested = edit.value.pending?.takeIf { edit.value.origin?.token == service.controller.attachment()?.second }?.map { DacEqFilterDef.of(it) }
        scope.launch {
            store.saveDacEqPreset(
                headphoneId = hp,
                dac = DacEqPairDac(dacProfileId, att.profile.id, att.displayName),
                name = name,
                preGainTenthsDb = gain,
                accepted = accepted,
                requested = requested,
                presetId = updatePresetId,
            )
        }
    }

    fun renamePreset(presetId: String, name: String) {
        val hp = headphoneId ?: return
        val dac = service.attached()?.dacProfileId ?: return
        scope.launch { store.renameDacEqPreset(hp, dac, presetId, name) }
    }

    fun deletePreset(presetId: String) {
        val hp = headphoneId ?: return
        val dac = service.attached()?.dacProfileId ?: return
        scope.launch { store.deleteDacEqPreset(hp, dac, presetId) }
    }

    /** Reads the DAC's EQ again, now (a user action: the screen's refresh). */
    fun refresh() {
        val att = service.attached() ?: return
        scope.launch { service.read(att) }
    }

    fun dismissError() = edit.update { it.copy(error = null) }

    // ── B31: taking the slot over, the DAC's own presets ────────────────────────────────────────────────

    /**
     * Takes the app's slot over (P1.3): its factory curve goes into the backup and a flat curve is saved in it. Asked by the listener on
     * the card, never by a connect. The writer refuses it while music plays and says so.
     */
    fun takeOver() = operate(DacEqOperation.TAKE_OVER) { att -> service.screenWriter.takeOver(att, WriteInitiator.USER_ACTION) }

    /** Gives the slot its factory curve back (P1.3), after the listener's yes. */
    fun restoreFactory() = operate(DacEqOperation.RESTORE_FACTORY) { att -> service.screenWriter.restoreFactory(att, WriteInitiator.USER_ACTION) }

    /** Reads every preset the DAC lists into the backup (P1.4): pointer moves only, refused while music plays. */
    fun readDacPresets() = operate(DacEqOperation.READ_PRESETS) { att -> service.screenWriter.inventory(att, WriteInitiator.USER_ACTION) }

    /**
     * Selects one of the DAC's own presets and nothing else (P1.4). While music plays it asks first ([DacEqUiState.confirmSelect]); paused,
     * it goes straight through. The gate has the last word on whether the preset is safe to select.
     */
    fun selectDacPreset(presetId: Int, confirmed: Boolean = false) {
        val ui = uiState.value
        if (ui.busy || ui.operation != null || ui.frozenByPurity || !ui.availability.canWrite) return
        val row = ui.dacPresets.firstOrNull { it.id == presetId } ?: return
        if (!row.selectable) return
        if (!confirmed && service.isPlaying()) {
            edit.update { it.copy(confirmSelect = presetId, error = null) }
            return
        }
        edit.update { it.copy(confirmSelect = null) }
        operate(DacEqOperation.SELECT_PRESET) { att ->
            service.screenWriter.selectPreset(att, presetId, WriteInitiator.USER_ACTION, confirmed = confirmed || service.isPlaying())
        }
    }

    fun dismissConfirmSelect() = edit.update { it.copy(confirmSelect = null) }

    /** One step on the DAC that is not an edit: busy while it runs, and its result said the way every write's is. */
    private fun operate(operation: DacEqOperation, block: suspend (AttachedFiio) -> DacEqWriteResult) {
        val ui = uiState.value
        if (ui.busy || ui.operation != null || ui.frozenByPurity || !ui.availability.canWrite) return
        val origin = currentOrigin() ?: return
        scope.launch {
            edit.update { it.copy(operation = operation, error = null) }
            val result = block(origin.att)
            edit.update { it.copy(operation = null) }
            when (result) {
                is DacEqWriteResult.Done -> edit.update { it.copy(error = null) }
                is DacEqWriteResult.Refused -> edit.update { it.copy(error = DacEqError.Refused(result.refusal)) }
                is DacEqWriteResult.Unavailable -> edit.update { it.copy(error = DacEqError.Unavailable) }
                is DacEqWriteResult.Mismatch -> edit.update { it.copy(error = DacEqError.ReadBackDiffers(result.restored)) }
                is DacEqWriteResult.Failed -> edit.update { it.copy(error = DacEqError.Failed(result.restored)) }
            }
        }
    }

    // ── The write, debounced and single-flight ──────────────────────────────────────────────────────────

    private fun schedule(immediate: Boolean) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            if (!immediate) delay(debounceMs)
            kick()
        }
    }

    private fun kick() {
        if (drainJob?.isActive == true) {
            rerun = true
            return
        }
        drainJob = scope.launch {
            do {
                rerun = false
                applyOnce()
            } while (rerun)
        }
    }

    /** The DAC the edit being made now is for: its attachment, as the controller knows it this instant. Null when none is attached. */
    private fun currentOrigin(): EditOrigin? = service.controller.attachment()?.let { (att, token) -> EditOrigin(att, token) }

    /**
     * Why an edit accepted on [origin] must not begin now, or null. The DAC is another one or the same one plugged in again, Purity is
     * in force, or writing was switched off. Asked when the debounce ends, and again by the writer once it holds the HID lock (and at
     * each stage until its first SET).
     */
    private fun dropReason(origin: EditOrigin): DacEqDropReason? {
        if (!service.controller.isCurrent(origin.token)) return DacEqDropReason.DAC_CHANGED
        val now = service.attached()
        if (now?.dacProfileId != null && origin.att.dacProfileId != null && now.dacProfileId != origin.att.dacProfileId) {
            return DacEqDropReason.DAC_CHANGED
        }
        if (uiState.value.frozenByPurity) return DacEqDropReason.PURITY_ON
        if (!service.availability(origin.att).canWrite) return DacEqDropReason.WRITES_OFF
        return null
    }

    private fun staleReason(origin: EditOrigin): String? = dropReason(origin)?.name

    private fun drop(reason: DacEqDropReason) {
        edit.update { it.copy(pending = null, origin = null, selection = null, busy = false, error = DacEqError.Dropped(reason)) }
    }

    private suspend fun applyOnce() {
        val state = edit.value
        val bands = state.pending ?: return
        val origin = state.origin ?: return edit.update { it.copy(pending = null, selection = null) }
        dropReason(origin)?.let { return drop(it) }
        // Taken now, with the edit: the selection that goes with this write is the one made with it, not one made while it ran.
        val selection = state.selection
        edit.update { it.copy(busy = true, error = null) }
        writesStarted++
        val result = service.screenWriter.applyCurve(origin.att, bands, WriteInitiator.USER_ACTION) { staleReason(origin) }
        handle(result, origin, bands = bands, selection = selection)
    }

    private suspend fun handle(
        result: DacEqWriteResult,
        origin: EditOrigin,
        bands: List<DacEqBand>? = null,
        closing: Boolean = false,
        selection: String? = null,
    ) {
        when (result) {
            is DacEqWriteResult.Done -> {
                edit.update { current ->
                    // What was written is now what the DAC reports; a newer edit made meanwhile stays on screen.
                    val settled = current.pending == bands
                    current.copy(
                        busy = false, error = null,
                        pending = if (settled) null else current.pending,
                        origin = if (settled) null else current.origin,
                        selection = if (settled) null else current.selection,
                    )
                }
                // Recorded for the DAC the write was made on, whatever is attached by now.
                if (!closing) recordSelection(origin.att, eqOn = true, applied = bands, selection = selection) else recordSelection(origin.att, eqOn = false)
            }
            is DacEqWriteResult.Refused ->
                if (result.refusal == GateRefusal.EDIT_OUTDATED) {
                    drop(dropReason(origin) ?: DacEqDropReason.DAC_CHANGED)
                } else {
                    edit.update { it.copy(busy = false, error = DacEqError.Refused(result.refusal)) }
                }
            is DacEqWriteResult.Unavailable -> edit.update { it.copy(busy = false, pending = null, origin = null, selection = null, error = DacEqError.Unavailable) }
            is DacEqWriteResult.Mismatch -> edit.update { it.copy(busy = false, pending = null, origin = null, selection = null, error = DacEqError.ReadBackDiffers(result.restored)) }
            is DacEqWriteResult.Failed -> edit.update { it.copy(busy = false, pending = null, origin = null, selection = null, error = DacEqError.Failed(result.restored)) }
        }
    }

    /**
     * What the pair has selected after a write. A preset the listener tapped is selected; writing the curve of the preset already
     * selected (turning the EQ on from it) keeps it; any other curve is "custom" (no preset). Closing the EQ keeps the selection.
     */
    private suspend fun recordSelection(att: AttachedFiio, eqOn: Boolean, applied: List<DacEqBand>? = null, selection: String? = null) {
        val hp = headphoneId ?: return
        val dac = att.dacProfileId ?: return
        // From the store, for **this** pair: the screen's own state is about whichever DAC is attached now.
        val profile = store.state.value.profiles.firstOrNull { it.profileId == hp }
        val selected = profile?.let { DacEqPresetCatalog.selectionFor(it, dac) }
        val presetId = when {
            !eqOn -> selected?.presetId
            selection != null -> selection
            else -> selected?.let { preset ->
                val bands = DacEqCompat.bandsFor(preset, DacEqCapability.of(att.profile), att.profile.canBeWritten)
                preset.presetId.takeIf { bands != null && applied != null && bands == applied }
            }
        }
        store.selectDacEq(hp, dac, presetId, eqOn)
    }

    companion object {
        /** Edits settle this long before the DAC is written: a drag is not a stream of writes. */
        const val DEBOUNCE_MS = 700L
    }
}
