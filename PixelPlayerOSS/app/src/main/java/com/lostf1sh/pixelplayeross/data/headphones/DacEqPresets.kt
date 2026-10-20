package com.lostf1sh.pixelplayeross.data.headphones

import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCapability
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioFilterType
import java.util.UUID
import kotlinx.serialization.Serializable

/** One filter of a DAC EQ preset, in the wire's own units (gain in tenths of a dB, Q in hundredths): exactly what a DAC held. */
@Serializable
data class DacEqFilterDef(
    val typeCode: Int,
    val frequencyHz: Int,
    val gainTenthsDb: Int,
    val qHundredths: Int,
) {
    val type: FiioFilterType? get() = FiioFilterType.ofCode(typeCode)
    val isFlat: Boolean get() = gainTenthsDb == 0 && type?.isGainFilter == true

    fun toBand(index: Int) = DacEqBand(index, typeCode, frequencyHz, gainTenthsDb, qHundredths)

    companion object {
        fun of(band: DacEqBand) = DacEqFilterDef(band.typeCode, band.frequencyHz, band.gainTenthsDb, band.qHundredths)
    }
}

/**
 * B30-P3 (N-06): a preset of a DAC's own EQ, **owned by a headphone profile and made for a pair**: these headphones with that DAC.
 *
 * "El preset se guarda en el audífono; se recupera con la combinación para la que fue hecho." It records the DAC it was made for
 * ([dacProfileId], plus the model and the name it had then) and the **full filter definition**: type, frequency, gain and Q per
 * filter, and the pre-gain, as **the DAC actually accepted** them (the read-back), not as asked. It is not the software graphic EQ's
 * preset and nothing converts between the two: ten software gains are not ten PEQ filters.
 *
 * Safety Max is deliberately not here: it is specific to headphones + output + backend and is never carried with a preset.
 */
@Serializable
data class DacEqPreset(
    val presetId: String,
    val name: String,
    /** The DAC half of the pair this preset was made for (`DacProfile.profileId`). The headphone half is the profile that holds it. */
    val dacProfileId: String,
    /** The table's model id when it was made (`snowsky-melody`): what a reuse on another DAC compares against. */
    val dacModelId: String,
    val dacName: String,
    val preGainTenthsDb: Int,
    /** What the DAC accepted, one per filter, in the DAC's own order. */
    val filters: List<DacEqFilterDef>,
    /** What was asked, when the DAC held something else; null when they were the same. */
    val requested: List<DacEqFilterDef>? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** What a pair had selected: a preset of the headphones, or "the DAC's EQ off". */
@Serializable
data class DacEqSelection(
    /** The preset selected for this pair; null when none is (the EQ off, or never chosen). */
    val presetId: String? = null,
    val eqOn: Boolean = false,
)

/** How a preset fits a DAC other than the one it was made for. Never decided by the band count alone. */
sealed interface DacEqFit {
    /** Every filter is inside the target's limits: it goes in as it is. */
    data object Fits : DacEqFit

    /** It goes in after [changes] (a gain or Q or frequency brought inside the target's range). */
    data class Approximate(val changes: List<String>) : DacEqFit

    /** It cannot be expressed on the target at all. [reasons] are for the log; [kinds] are what a screen translates. */
    data class Incompatible(val reasons: List<String>, val kinds: Set<DacEqIncompatibility> = emptySet()) : DacEqFit
}

/** Why a preset cannot be used on a DAC, in the few ways a screen says it. */
enum class DacEqIncompatibility { NO_USABLE_EQ, TOO_MANY_FILTERS, FILTER_TYPE }

/**
 * B30-P3: whether a preset made for one DAC can be used on another, and what it becomes.
 *
 * The test is the filter *definitions* against the target's limits, never the number of bands: ten filters on a ten-band DAC can be
 * incompatible (a filter type the target lacks, or one this app does not write: a pass filter), five on a ten-band one fit. A filter that does nothing (a gain filter at 0 dB)
 * is not counted. A target that is not known to be writable has no usable EQ here at all.
 */
object DacEqCompat {
    fun fit(preset: DacEqPreset, target: DacEqCapability?, targetCanBeWritten: Boolean = true): DacEqFit {
        if (target == null || !targetCanBeWritten) {
            return DacEqFit.Incompatible(listOf("the DAC has no EQ this app can write"), setOf(DacEqIncompatibility.NO_USABLE_EQ))
        }
        val reasons = mutableListOf<String>()
        val kinds = mutableSetOf<DacEqIncompatibility>()
        val changes = mutableListOf<String>()
        val active = preset.filters.withIndex().filter { !it.value.isFlat }
        if (active.size > target.bandCount) {
            reasons += "${active.size} filters do something; the DAC has ${target.bandCount}"
            kinds += DacEqIncompatibility.TOO_MANY_FILTERS
        }
        for ((i, filter) in active) {
            val n = i + 1
            val type = filter.type
            // A filter is usable only if the DAC has it **and** this app writes it (the gate allows peaks and shelves only: they are
            // the types whose effect on the level the app can evaluate).
            if (type == null || type !in target.filterTypes || !type.isGainFilter) {
                reasons += "filter $n: ${type?.name ?: "type ${filter.typeCode}"} is not a filter this app can write to the DAC"
                kinds += DacEqIncompatibility.FILTER_TYPE
                continue
            }
            val gain = filter.gainTenthsDb
            val lowGain = Math.round(target.minGainDb * 10).toInt()
            val highGain = Math.round(target.maxGainDb * 10).toInt()
            if (gain !in lowGain..highGain) changes += "filter $n: gain ${gain / 10.0} dB brought to ${gain.coerceIn(lowGain, highGain) / 10.0} dB"
            if (filter.frequencyHz !in target.minFrequencyHz..target.maxFrequencyHz) {
                changes += "filter $n: ${filter.frequencyHz} Hz brought to ${filter.frequencyHz.coerceIn(target.minFrequencyHz, target.maxFrequencyHz)} Hz"
            }
            val lowQ = Math.round(target.minQ * 100).toInt()
            val highQ = Math.round(target.maxQ * 100).toInt()
            if (filter.qHundredths !in lowQ..highQ) changes += "filter $n: Q ${filter.qHundredths / 100.0} brought to ${filter.qHundredths.coerceIn(lowQ, highQ) / 100.0}"
        }
        return when {
            reasons.isNotEmpty() -> DacEqFit.Incompatible(reasons, kinds)
            changes.isNotEmpty() -> DacEqFit.Approximate(changes)
            else -> DacEqFit.Fits
        }
    }

    /**
     * The filters of [preset] as the [target] would take them, limits applied; null when [fit] says incompatible. Each filter keeps
     * its place when the target has room for the preset's list as it is; a list longer than the target's bands (its extra entries
     * all flat, which [fit] allows) is packed with the filters that do something, in order. The rest is flat at the usual
     * frequencies. The pre-gain is not carried: the write gate computes it for the curve.
     */
    fun bandsFor(preset: DacEqPreset, target: DacEqCapability, targetCanBeWritten: Boolean = true): List<DacEqBand>? {
        if (fit(preset, target, targetCanBeWritten) is DacEqFit.Incompatible) return null
        val defaults = intArrayOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        val slots: List<DacEqFilterDef?> =
            if (preset.filters.size <= target.bandCount) preset.filters else preset.filters.filter { !it.isFlat }
        val lowGain = Math.round(target.minGainDb * 10).toInt()
        val highGain = Math.round(target.maxGainDb * 10).toInt()
        val lowQ = Math.round(target.minQ * 100).toInt()
        val highQ = Math.round(target.maxQ * 100).toInt()
        return (0 until target.bandCount).map { i ->
            val filter = slots.getOrNull(i)
            if (filter == null) {
                DacEqBand.of(i, FiioFilterType.PEAK, defaults.getOrElse(i) { 1000 }.coerceIn(target.minFrequencyHz, target.maxFrequencyHz), 0.0, 0.71)
            } else {
                DacEqBand(
                    index = i,
                    typeCode = filter.typeCode,
                    frequencyHz = filter.frequencyHz.coerceIn(target.minFrequencyHz, target.maxFrequencyHz),
                    gainTenthsDb = filter.gainTenthsDb.coerceIn(lowGain, highGain),
                    qHundredths = filter.qHundredths.coerceIn(lowQ, highQ),
                )
            }
        }
    }
}

/** A preset of these headphones offered for reuse on the DAC that is connected, with how it fits. */
data class DacEqReuse(val preset: DacEqPreset, val fit: DacEqFit)

/**
 * B30-P3: the rules of who owns what. Pure functions over the profile state, like the rest of [HeadphoneProfileCatalog].
 *
 * - A preset belongs to **one headphone profile** and records **one DAC** (the pair). It is found through the headphones.
 * - Editing for one pair **never touches another pair's** presets, even with the same name (names are unique per pair).
 * - A recognised pair gets its saved selection back, **silently**: [selectionFor] answers, and nothing else happens (a write to
 *   the DAC needs a user action; this only says what the screen should show as chosen).
 * - A preset made for another pair is never selected for this one automatically: [reusableOn] lists it, with its fit, for the
 *   listener to apply by choice.
 */
object DacEqPresetCatalog {
    fun presetsFor(profile: HeadphoneProfile, dacProfileId: String): List<DacEqPreset> =
        profile.dacEqPresets.filter { it.dacProfileId == dacProfileId }

    /** Every preset of the headphones made for a DAC other than [dacProfileId]: the ones that can be reused by choice. */
    fun madeForOtherDacs(profile: HeadphoneProfile, dacProfileId: String): List<DacEqPreset> =
        profile.dacEqPresets.filter { it.dacProfileId != dacProfileId }

    fun reusableOn(profile: HeadphoneProfile, dacProfileId: String, target: DacEqCapability?, targetCanBeWritten: Boolean = true): List<DacEqReuse> =
        madeForOtherDacs(profile, dacProfileId).map { DacEqReuse(it, DacEqCompat.fit(it, target, targetCanBeWritten)) }

    /** The preset a recognised pair had selected; null for a pair never seen, or one that had none (or whose preset is gone). */
    fun selectionFor(profile: HeadphoneProfile, dacProfileId: String): DacEqPreset? {
        val selection = profile.dacEqSelections[dacProfileId] ?: return null
        val id = selection.presetId ?: return null
        return presetsFor(profile, dacProfileId).firstOrNull { it.presetId == id }
    }

    /**
     * Saves a preset for the pair ([headphoneId] + [dac]): a new one, or [presetId] updated if it is one of this pair's. A preset id
     * that belongs to another pair is not overwritten: a new preset is made instead.
     */
    fun save(
        state: HeadphoneProfileState,
        headphoneId: String,
        dac: DacEqPairDac,
        name: String,
        preGainTenthsDb: Int,
        accepted: List<DacEqFilterDef>,
        requested: List<DacEqFilterDef>? = null,
        presetId: String? = null,
        nowEpochMs: Long,
        newId: () -> String = { "dac-eq-${UUID.randomUUID()}" },
    ): HeadphoneProfileState {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || state.profiles.none { it.profileId == headphoneId }) return HeadphoneProfileCatalog.normalize(state)
        return update(state, headphoneId) { profile ->
            val own = presetsFor(profile, dac.profileId)
            val target = own.firstOrNull { it.presetId == presetId }
                ?: own.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            val saved = DacEqPreset(
                presetId = target?.presetId ?: newId(),
                name = trimmed,
                dacProfileId = dac.profileId,
                dacModelId = dac.modelId,
                dacName = dac.name,
                preGainTenthsDb = preGainTenthsDb,
                filters = accepted,
                requested = requested?.takeIf { it != accepted },
                createdAtEpochMs = target?.createdAtEpochMs ?: nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            )
            val presets = if (target == null) {
                profile.dacEqPresets + saved
            } else {
                profile.dacEqPresets.map { if (it.presetId == target.presetId && it.dacProfileId == dac.profileId) saved else it }
            }
            profile.copy(
                dacEqPresets = presets,
                dacEqSelections = profile.dacEqSelections + (dac.profileId to DacEqSelection(saved.presetId, eqOn = true)),
                updatedAtEpochMs = nowEpochMs,
            )
        }
    }

    /** Records what the pair has selected (a preset of its own, or null for "off"); a preset of another pair is not accepted. */
    fun select(state: HeadphoneProfileState, headphoneId: String, dacProfileId: String, presetId: String?, eqOn: Boolean, nowEpochMs: Long): HeadphoneProfileState =
        update(state, headphoneId) { profile ->
            val valid = presetId == null || presetsFor(profile, dacProfileId).any { it.presetId == presetId }
            if (!valid) profile else profile.copy(
                dacEqSelections = profile.dacEqSelections + (dacProfileId to DacEqSelection(presetId, eqOn)),
                updatedAtEpochMs = nowEpochMs,
            )
        }

    fun rename(state: HeadphoneProfileState, headphoneId: String, dacProfileId: String, presetId: String, name: String, nowEpochMs: Long): HeadphoneProfileState {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return HeadphoneProfileCatalog.normalize(state)
        return update(state, headphoneId) { profile ->
            if (presetsFor(profile, dacProfileId).any { it.presetId != presetId && it.name.equals(trimmed, ignoreCase = true) }) return@update profile
            profile.copy(
                dacEqPresets = profile.dacEqPresets.map {
                    if (it.presetId == presetId && it.dacProfileId == dacProfileId) it.copy(name = trimmed, updatedAtEpochMs = nowEpochMs) else it
                },
                updatedAtEpochMs = nowEpochMs,
            )
        }
    }

    fun delete(state: HeadphoneProfileState, headphoneId: String, dacProfileId: String, presetId: String, nowEpochMs: Long): HeadphoneProfileState =
        update(state, headphoneId) { profile ->
            profile.copy(
                dacEqPresets = profile.dacEqPresets.filterNot { it.presetId == presetId && it.dacProfileId == dacProfileId },
                dacEqSelections = profile.dacEqSelections.mapValues { (dac, selection) ->
                    if (dac == dacProfileId && selection.presetId == presetId) DacEqSelection(null, selection.eqOn) else selection
                },
                updatedAtEpochMs = nowEpochMs,
            )
        }

    private fun update(state: HeadphoneProfileState, headphoneId: String, change: (HeadphoneProfile) -> HeadphoneProfile): HeadphoneProfileState =
        HeadphoneProfileCatalog.normalize(
            state.copy(profiles = state.profiles.map { if (it.profileId == headphoneId) change(it) else it })
        )
}

/**
 * Where the DAC EQ presets are kept: the headphone profiles' own store ([HeadphoneProfileRepository]). A seam so the Resonance
 * screen's logic runs in the JVM against a plain in-memory state.
 */
interface DacEqPresetStore {
    val state: kotlinx.coroutines.flow.StateFlow<HeadphoneProfileState>

    suspend fun saveDacEqPreset(
        headphoneId: String,
        dac: DacEqPairDac,
        name: String,
        preGainTenthsDb: Int,
        accepted: List<DacEqFilterDef>,
        requested: List<DacEqFilterDef>? = null,
        presetId: String? = null,
    ): DacEqPreset?

    suspend fun selectDacEq(headphoneId: String, dacProfileId: String, presetId: String?, eqOn: Boolean)
    suspend fun renameDacEqPreset(headphoneId: String, dacProfileId: String, presetId: String, name: String)
    suspend fun deleteDacEqPreset(headphoneId: String, dacProfileId: String, presetId: String)
}

/** The DAC half of a pair as a preset records it. */
data class DacEqPairDac(val profileId: String, val modelId: String, val name: String)
