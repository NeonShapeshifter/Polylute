package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.fiio.AttachedFiio
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCapability
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabel
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqReadout
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqVerdict
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteAvailability
import com.lostf1sh.pixelplayeross.data.dac.fiio.EqRead
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioFilterType
import com.lostf1sh.pixelplayeross.data.dac.fiio.GateRefusal
import com.lostf1sh.pixelplayeross.data.dac.fiio.PreGainSense
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPreset
import com.lostf1sh.pixelplayeross.data.headphones.DacEqReuse
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason

/**
 * What the Resonance screen is, for the headphones it was opened on (B30-P3).
 *
 * - [DAC]: a FiiO/Snowsky DAC whose EQ was **read** and that this build may **write** (profile verified, or the debug override):
 *   the screen edits the DAC's real filters, not the software 10/15-band modes, and **never creates the software EQ's view model**,
 *   whose open editor would hold the signal on the processed path.
 * - [SOFTWARE]: anything else: the app's own graphic EQ, as before.
 * - [DECIDING]: a writable DAC is attached and its EQ has not been read yet; a moment, then one of the two.
 */
enum class ResonanceMode { DECIDING, DAC, SOFTWARE }

object ResonanceModePolicy {
    /** How long the screen waits for the first read before it falls back to the software EQ. */
    const val WAIT_MS = 4_000L

    fun decide(
        attached: AttachedFiio?,
        availability: DacEqWriteAvailability,
        readout: DacEqReadout,
        waitedMs: Long,
    ): ResonanceMode = when {
        attached == null || !availability.canWrite -> ResonanceMode.SOFTWARE
        readout is DacEqReadout.Known ->
            if (readout.state.isComplete) ResonanceMode.DAC else ResonanceMode.SOFTWARE
        readout is DacEqReadout.Unreadable -> ResonanceMode.SOFTWARE
        else -> if (waitedMs >= WAIT_MS) ResonanceMode.SOFTWARE else ResonanceMode.DECIDING
    }
}

/** A listening choice that keeps the stream on the Android route (B30-P4). */
enum class DacEqConflict(@StringRes val textRes: Int) {
    CROSSFADE(R.string.dac_eq_conflict_crossfade),
    SPEED(R.string.dac_eq_conflict_speed),
    ANDROID_EFFECT(R.string.dac_eq_conflict_effect),
}

/**
 * B30-P4: what, if anything, is keeping a stream that a DAC carries off USB Direct. Said, never silent: the Resonance screen and
 * Details both draw it. The DAC's own EQ still applies on the Android route (it lives in the DAC), so the sentence says that too.
 * This reads the route's own decision; it does not change it.
 */
fun AudioStatus.dacEqConflict(): DacEqConflict? {
    if (!dacConnected || route.actualBackend != AudioBackend.ANDROID_AUDIO) return null
    return when (route.reason) {
        AudioRouteReason.CROSSFADE_REQUIRES_ANDROID -> DacEqConflict.CROSSFADE
        AudioRouteReason.SPEED_REQUIRES_ANDROID -> DacEqConflict.SPEED
        AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID -> DacEqConflict.ANDROID_EFFECT
        else -> null
    }
}

/** What went wrong with the last attempt to write the DAC's EQ, in a form the screen can say. */
sealed interface DacEqError {
    /** The gate said no, and nothing was sent. [refusal] names the condition. */
    data class Refused(val refusal: GateRefusal) : DacEqError

    /** The DAC did not hold what was written; the backup was written back ([restored]) or not. */
    data class ReadBackDiffers(val restored: Boolean) : DacEqError

    /** A transfer failed or the DAC stopped answering. */
    data class Failed(val restored: Boolean?) : DacEqError

    /** The HID interface could not be used (permission, claim, the DAC is gone). */
    data object Unavailable : DacEqError

    /** B30-fix1 H2: an edit was dropped without a byte sent, because what it was accepted under changed while it waited. */
    data class Dropped(val reason: DacEqDropReason) : DacEqError
}

/** Why a waiting edit was dropped instead of written (B30-fix1 H2). */
enum class DacEqDropReason(@StringRes val textRes: Int) {
    /** The DAC was unplugged, replugged or replaced: the edit was made for another attachment. */
    DAC_CHANGED(R.string.dac_eq_dropped_dac),

    /** The Pure Direct lock was turned on: nothing may be started while it is in force. */
    PURITY_ON(R.string.dac_eq_dropped_purity),

    /** Writing the DAC is not allowed any more (the debug override was switched off). */
    WRITES_OFF(R.string.dac_eq_dropped_writes),
}

/**
 * B31 P1.4: one of the DAC's own presets, as the inventory read it. [readFromDac] false: the app has not looked at it yet and says so.
 * [locked]: it was read and could play louder than the app allows under some reading of the pre-gain, so the app will not select it.
 */
@Immutable
data class DacPresetRow(
    val id: Int,
    val name: String,
    val isActive: Boolean,
    val isAppSlot: Boolean,
    val isClosed: Boolean,
    val readFromDac: Boolean,
    val preGainTenthsDb: Int?,
    val activeFilters: Int?,
    val locked: Boolean,
) {
    val selectable: Boolean get() = !isActive && !locked && (readFromDac || isClosed)
}

/** Whether the pair's selected preset is what the DAC holds. A recognised pair gets its selection shown; a write needs a tap. */
enum class SelectedPresetState { NONE, MATCHES_DAC, DIFFERS_FROM_DAC }

/**
 * What the DAC EQ screen draws (B30-P3).
 *
 * [bands] is the curve shown: what the DAC reports, or the pending edit on top of it. [preGainTenthsDb] is the DAC's global gain
 * for it (computed by the same function the gate uses, so what is shown is what will be written).
 */
@Immutable
data class DacEqUiState(
    val profileName: String? = null,
    val dacName: String? = null,
    val dacProfileId: String? = null,
    val availability: DacEqWriteAvailability = DacEqWriteAvailability.READ_ONLY_MODEL,
    /** What the table says the DAC can do (limits for the editors); never a reading. */
    val capability: DacEqCapability? = null,
    val readout: DacEqReadout = DacEqReadout.NotApplicable,
    val verdict: DacEqVerdict = DacEqVerdict.UNKNOWN,
    val bands: List<DacEqBand> = emptyList(),
    val preGainTenthsDb: Int = 0,
    /** The slot the app stores its curve in, by the DAC's own name ("USER 3"). */
    val appSlotName: String? = null,
    /** The DAC's currently selected preset, by name when the table has one ("Close EQ", "Jazz", "USER 3"). */
    val activePresetName: String? = null,
    val busy: Boolean = false,
    val error: DacEqError? = null,
    /** The curve on screen has been edited and not yet written (the debounce is running). */
    val pendingWrite: Boolean = false,
    /** The Pure Direct lock is in force: nothing shapes the sound, so the DAC's EQ is read-only here. */
    val frozenByPurity: Boolean = false,
    val conflict: DacEqConflict? = null,
    val directActive: Boolean = false,
    val presets: List<DacEqPreset> = emptyList(),
    val selectedPresetId: String? = null,
    val selectedPresetState: SelectedPresetState = SelectedPresetState.NONE,
    val reusable: List<DacEqReuse> = emptyList(),
    /** The net peak of the curve after the pre-gain, in dB (≤ 0 by the gate's rule). */
    val netPeakDb: Double = 0.0,
    /** The debug override is the only reason a write is allowed: said on the screen, so it is never mistaken for a verified DAC. */
    val debugOverride: Boolean = false,
    /** B31 P1.3: the app has taken its slot over (a flat curve is saved in it and its factory curve is in the backup). */
    val slotOwned: Boolean = false,
    /** B31 P1.3: the Resonance screen offers the take-over (never as a popup on connect): a writable DAC whose slot is not the app's yet. */
    val takeOverOffered: Boolean = false,
    /** B31 P1.3: a take-over, a give-back or an inventory is running. */
    val operation: DacEqOperation? = null,
    /** B31 P1.4: the DAC's presets by name, from the inventory in the backup; empty when there is no DAC to list. */
    val dacPresets: List<DacPresetRow> = emptyList(),
    /** B31 P1.4: every preset the profile lists has been read. */
    val presetsRead: Boolean = false,
    /** B31 P1.4: a select that needs the listener's confirmation because music is playing. */
    val confirmSelect: DacPresetRow? = null,
    /** B31 P1.2: what a positive global gain does on this DAC while the screen is open (unconfirmed until the reviewer's check). */
    val preGainSense: PreGainSense = PreGainSense.UNCONFIRMED,
) {
    val eqOn: Boolean get() = verdict == DacEqVerdict.ON || verdict == DacEqVerdict.FLAT
    val canEdit: Boolean get() = availability.canWrite && !frozenByPurity && !busy && operation == null

    /** B31 P1.2: the curve rises above 0 dB and, the sense being unconfirmed, no pre-gain holds it down; the gate would refuse it. */
    val senseBlocksCurve: Boolean get() = preGainSense == PreGainSense.UNCONFIRMED && bands.isNotEmpty() && netPeakDb > 0.0

    /** The DAC's whole state as read, for the parts of the screen that name what the DAC holds. */
    val dacState get() = (readout as? DacEqReadout.Known)?.state

    val label: DacEqLabel get() = readout.label

    companion object {
        /** The filter types the screen offers: the three the app writes (it can evaluate their response). */
        val offeredTypes: List<FiioFilterType> = listOf(FiioFilterType.PEAK, FiioFilterType.LOW_SHELF, FiioFilterType.HIGH_SHELF)
    }
}

/** A step the screen runs on the DAC that is not an edit of the curve (B31). */
enum class DacEqOperation { TAKE_OVER, RESTORE_FACTORY, READ_PRESETS, SELECT_PRESET }

/** The curve the DAC reports, as bands, when everything was read; null otherwise. */
internal fun DacEqReadout.readBands(): List<DacEqBand>? =
    (this as? DacEqReadout.Known)?.state?.takeIf { it.isComplete }?.bands?.map { (it as EqRead.Read).value }

/** What the gate's refusal says, in the listener's words. */
@StringRes
fun GateRefusal.textRes(): Int = when (this) {
    GateRefusal.NOT_USER_INITIATED -> R.string.dac_eq_refusal_not_user
    GateRefusal.PROFILE_CANNOT_BE_WRITTEN -> R.string.dac_eq_refusal_profile
    GateRefusal.PROFILE_NOT_VERIFIED -> R.string.dac_eq_refusal_unverified
    GateRefusal.READ_NOT_WORKING, GateRefusal.STALE_READ -> R.string.dac_eq_refusal_read
    GateRefusal.NO_BACKUP, GateRefusal.BACKUP_FOR_OTHER_DAC, GateRefusal.BACKUP_INCOMPLETE -> R.string.dac_eq_refusal_backup
    GateRefusal.NOT_USER_SLOT -> R.string.dac_eq_refusal_slot
    GateRefusal.OUT_OF_LIMITS -> R.string.dac_eq_refusal_limits
    GateRefusal.NET_BOOST -> R.string.dac_eq_refusal_boost
    GateRefusal.SLOT_NOT_INSPECTED -> R.string.dac_eq_refusal_slot_unknown
    GateRefusal.SLOT_UNSAFE_TO_SELECT -> R.string.dac_eq_refusal_slot_unsafe
    GateRefusal.REVIEWER_ONLY -> R.string.dac_eq_refusal_reviewer
    GateRefusal.EDIT_OUTDATED -> R.string.dac_eq_refusal_outdated
    GateRefusal.PLAYBACK_NOT_PAUSED -> R.string.dac_eq_refusal_playing
    GateRefusal.PRE_GAIN_SENSE_UNCONFIRMED -> R.string.dac_eq_refusal_sense
    GateRefusal.SLOT_NOT_TAKEN_OVER -> R.string.dac_eq_refusal_not_taken
    GateRefusal.SLOT_IS_THE_APPS -> R.string.dac_eq_refusal_taken
    GateRefusal.CONFIRMATION_NEEDED -> R.string.dac_eq_refusal_confirm
    GateRefusal.LOW_LEVEL_NOT_CONFIRMED -> R.string.dac_eq_refusal_low_level
}
