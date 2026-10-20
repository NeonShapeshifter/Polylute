package com.lostf1sh.pixelplayeross.data.dac.fiio

import kotlinx.serialization.Serializable

/**
 * B30-P1: the filter types FiiO's PEQ knows, by the code the protocol carries in a band's last data byte.
 * Source: WolfEQ `Models/EqBand.cs` (`EqFilterType`, MIT); DevicePEQ's `fiioUsbHidHandler.js` maps only PK/LSQ/HSQ (0/1/2).
 */
@Serializable
enum class FiioFilterType(val code: Int) {
    PEAK(0),
    LOW_SHELF(1),
    HIGH_SHELF(2),
    BAND_PASS(3),
    LOW_PASS(4),
    HIGH_PASS(5),
    ALL_PASS(6),
    ;

    /** The app can evaluate the response of this type (and so say what a curve does to the level): the three it writes. */
    val isGainFilter: Boolean get() = this == PEAK || this == LOW_SHELF || this == HIGH_SHELF

    companion object {
        fun ofCode(code: Int): FiioFilterType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One filter, as the DAC holds it: whole units of what the wire carries (gain in tenths of a dB, Q in hundredths), so two
 * readings compare exactly and nothing drifts through a float.
 *
 * [typeCode] is kept as read: a code this table does not know is a band the app can show but never claims to understand.
 */
@Serializable
data class DacEqBand(
    val index: Int,
    val typeCode: Int,
    val frequencyHz: Int,
    val gainTenthsDb: Int,
    val qHundredths: Int,
) {
    val type: FiioFilterType? get() = FiioFilterType.ofCode(typeCode)
    val gainDb: Double get() = gainTenthsDb / 10.0
    val q: Double get() = qHundredths / 100.0

    /** A band that does nothing: a gain filter at 0 dB. (A pass filter at 0 dB is not flat.) */
    val isFlat: Boolean get() = gainTenthsDb == 0 && type?.isGainFilter == true

    /**
     * B31 P1.5: a band the DAC holds **outside the profile's limits** (0 Hz, Q 0: what "Close EQ" and an unused slot read as) is *unset*:
     * it is not a filter, and the app never writes one back. [DacEqCurve] already gives it no response.
     */
    val isUnset: Boolean get() = frequencyHz <= 0 || qHundredths <= 0

    /** Does nothing to the sound: unset, or a gain filter at 0 dB. */
    val isInert: Boolean get() = isUnset || isFlat

    /**
     * The same filter, or an **unset** band against one that does nothing: they sound alike, and a flat band is what the app writes where
     * the DAC held zeros. (Two flat bands at different frequencies are not the same: the listener may have moved one.)
     */
    fun sameEffectAs(other: DacEqBand): Boolean =
        sameAs(other) || (index == other.index && ((isUnset && other.isInert) || (other.isUnset && isInert)))

    /** The DAC's own value for this band matches [other] as WolfEQ's `BandsMatch` reads it (all four fields exact at these units). */
    fun sameAs(other: DacEqBand): Boolean =
        index == other.index && typeCode == other.typeCode && frequencyHz == other.frequencyHz &&
            gainTenthsDb == other.gainTenthsDb && qHundredths == other.qHundredths

    companion object {
        fun of(index: Int, type: FiioFilterType, frequencyHz: Int, gainDb: Double, q: Double) = DacEqBand(
            index = index,
            typeCode = type.code,
            frequencyHz = frequencyHz,
            gainTenthsDb = Math.round(gainDb * 10).toInt(),
            qHundredths = Math.round(q * 100).toInt(),
        )
    }
}

/** The band layout the app writes when it needs a flat curve: where the screen's bands sit, and a gentle Q. */
object DacEqDefaults {
    val FREQUENCIES = intArrayOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val Q = 0.71

    /** One flat peak per band at the usual frequencies: the curve of a DAC EQ that does nothing. */
    fun flatBands(bandCount: Int): List<DacEqBand> =
        (0 until bandCount).map { DacEqBand.of(it, FiioFilterType.PEAK, FREQUENCIES.getOrElse(it) { 1000 }, 0.0, Q) }

    /** [band] as the app would write it: an unset band becomes a real, flat one; any other is as it is. */
    fun writable(band: DacEqBand): DacEqBand =
        if (band.isUnset) DacEqBand.of(band.index, FiioFilterType.PEAK, FREQUENCIES.getOrElse(band.index) { 1000 }, band.gainDb, Q) else band
}

/** What one value of the EQ state came to: read, not knowable from this DAC, or a read that went wrong. */
sealed interface EqRead<out T> {
    /** The DAC answered and the answer parsed. */
    data class Read<T>(val value: T) : EqRead<T>

    /** Nothing to go on: not asked, or the DAC gave no answer to this command (the Melody's `1A`). */
    data class Unknown(val why: String) : EqRead<Nothing>

    /** An answer that cannot be used (a bad value, a transfer error). */
    data class Failed(val why: String) : EqRead<Nothing>

    val valueOrNull: T? get() = (this as? Read<T>)?.value
}

/**
 * What the table says this DAC can do (the catalog's claim) — kept apart from [DacEqState], what the device itself said.
 * N-06: "no presentar los datos del catálogo como una autodetección ya verificada".
 */
data class DacEqCapability(
    val profileId: String,
    val displayName: String,
    val bandCount: Int,
    val minGainDb: Double,
    val maxGainDb: Double,
    val minQ: Double,
    val maxQ: Double,
    val minFrequencyHz: Int,
    val maxFrequencyHz: Int,
    val filterTypes: Set<FiioFilterType>,
    /** The preset id that is "Close EQ" / "BYPASS" on this model (WolfEQ `DisabledPresetId`). */
    val disabledPresetId: Int?,
    /** Verified by this app on real hardware. False for every row until a reviewer's device run passes. */
    val verified: Boolean,
) {
    companion object {
        fun of(profile: FiioHidProfile) = DacEqCapability(
            profileId = profile.id,
            displayName = profile.displayName,
            bandCount = profile.bandCount,
            minGainDb = profile.minGainDb,
            maxGainDb = profile.maxGainDb,
            minQ = profile.minQ,
            maxQ = profile.maxQ,
            minFrequencyHz = profile.minFrequencyHz,
            maxFrequencyHz = profile.maxFrequencyHz,
            filterTypes = profile.supportedFilters,
            disabledPresetId = profile.disabledPresetId,
            verified = profile.verified,
        )
    }
}

/**
 * The DAC's EQ as the device reported it. Every value is [EqRead.Read], [EqRead.Unknown] or [EqRead.Failed]; nothing is
 * filled in from [capability].
 */
data class DacEqState(
    val capability: DacEqCapability,
    /** The current preset or slot id (`GET 16`). */
    val activePreset: EqRead<Int>,
    /** The band count the DAC reports (`GET 18`). */
    val bandCount: EqRead<Int>,
    /** The global gain in tenths of a dB (`GET 17`, signed 16 bit big endian). */
    val preGainTenthsDb: EqRead<Int>,
    /** EQ on/off if the DAC answers `GET 1A`; the Melody does not. */
    val eqSwitch: EqRead<Boolean>,
    /** One entry per band of the *active* preset, index 0 up; the count the DAC reported, else the table's. */
    val bands: List<EqRead<DacEqBand>>,
) {
    /** Preset, count, gain and every band read, and the count agrees with the table: the precondition for a write. */
    val isComplete: Boolean
        get() = activePreset is EqRead.Read && preGainTenthsDb is EqRead.Read &&
            (bandCount as? EqRead.Read)?.value == capability.bandCount &&
            bands.size == capability.bandCount && bands.all { it is EqRead.Read } &&
            bands.mapIndexed { i, b -> (b as? EqRead.Read)?.value?.index == i }.all { it }

    val readBands: List<DacEqBand> get() = bands.mapNotNull { it.valueOrNull }

    /** What the EQ does to the sound, from what was read. See [DacEqVerdicts]. */
    val verdict: DacEqVerdict get() = DacEqVerdicts.of(this)
}

enum class DacEqVerdict {
    /** Closed: the DAC's own "Close EQ" / bypass preset is selected, or its switch says off. */
    OFF,

    /** A preset is selected and every band is a 0 dB gain filter with no global gain: it does nothing. */
    FLAT,

    /** The EQ shapes the sound (a factory preset, or a curve with something in it). */
    ON,

    /** Not readable: the app cannot say. */
    UNKNOWN,
}

object DacEqVerdicts {
    /**
     * The decision table (B30-P1.4):
     * - a switch the DAC answers wins when it says off; on, it still needs the preset to say the same;
     * - the model's "Close EQ" preset is OFF; with the switch saying on it contradicts itself, so UNKNOWN;
     * - any other preset is a selected EQ, and its bands say what it does (`GET 15` answers for the active preset, a factory
     *   one included): it is FLAT only when every band was read as a 0 dB gain filter and the global gain read as 0; one band
     *   or the gain read as something else is ON; a band that could not be read leaves it UNKNOWN unless something already
     *   proved ON;
     * - no preset and no switch: UNKNOWN.
     */
    fun of(state: DacEqState): DacEqVerdict {
        val switch = (state.eqSwitch as? EqRead.Read)?.value
        val preset = (state.activePreset as? EqRead.Read)?.value
        val disabled = state.capability.disabledPresetId
        if (switch == false) return DacEqVerdict.OFF
        if (preset == null) return if (switch == true) DacEqVerdict.ON else DacEqVerdict.UNKNOWN
        if (disabled != null && preset == disabled) return if (switch == true) DacEqVerdict.UNKNOWN else DacEqVerdict.OFF

        val readBands = state.readBands
        val gain = (state.preGainTenthsDb as? EqRead.Read)?.value
        if (readBands.any { !it.isFlat } || (gain != null && gain != 0)) return DacEqVerdict.ON
        val everythingRead = gain != null && state.bands.size == state.capability.bandCount && state.bands.all { it is EqRead.Read }
        return if (everythingRead) DacEqVerdict.FLAT else DacEqVerdict.UNKNOWN
    }
}
