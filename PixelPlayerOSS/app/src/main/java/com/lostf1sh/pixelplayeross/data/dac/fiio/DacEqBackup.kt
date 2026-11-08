package com.lostf1sh.pixelplayeross.data.dac.fiio

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One preset slot's content as the DAC held it: its global gain and all its filters. */
@Serializable
data class DacEqSlotSnapshot(
    val presetId: Int,
    val preGainTenthsDb: Int,
    val bands: List<DacEqBand>,
) {
    /** The same content, as the DAC reports it (a slot is the same if gain and every band are). */
    fun sameContentAs(other: DacEqSlotSnapshot): Boolean =
        preGainTenthsDb == other.preGainTenthsDb && bands.size == other.bands.size &&
            bands.indices.all { bands[it].sameAs(other.bands[it]) }

    /**
     * The same sound (B31 P1.5): the gain and every band alike, where a band that does nothing (unset, or flat) matches any other that
     * does nothing. What a read-back after a write is compared with: the app writes a flat band where the DAC held an unset one.
     */
    fun sameEffectAs(other: DacEqSlotSnapshot): Boolean =
        preGainTenthsDb == other.preGainTenthsDb && bands.size == other.bands.size &&
            bands.indices.all { bands[it].sameEffectAs(other.bands[it]) }

    /**
     * B59 (#53): [sameEffectAs] with the Q one hundredth either way (the DAC's own rounding of what it was sent): the comparison of a read-back with
     * what was written. The gain, the frequency, the type and the pre-gain stay exact.
     */
    fun sameEffectAsAllowingQRounding(other: DacEqSlotSnapshot): Boolean =
        preGainTenthsDb == other.preGainTenthsDb && bands.size == other.bands.size &&
            bands.indices.all { bands[it].sameEffectAsAllowingQRounding(other.bands[it]) }

    /** This content as the app writes it: no unset band (they become flat ones). */
    fun writable(): DacEqSlotSnapshot = copy(bands = bands.map(DacEqDefaults::writable))

    companion object {
        /** The active preset of a complete read, as a snapshot; null when the read is not complete. */
        fun of(state: DacEqState): DacEqSlotSnapshot? {
            if (!state.isComplete) return null
            return DacEqSlotSnapshot(
                presetId = (state.activePreset as EqRead.Read).value,
                preGainTenthsDb = (state.preGainTenthsDb as EqRead.Read).value,
                bands = state.readBands,
            )
        }
    }
}

/**
 * B30-P2: the EQ state of one DAC, saved before anything is written to it.
 *
 * [slots] holds a snapshot of the preset that was active when the backup was taken and, once the reviewer has looked at the app's
 * slot, of that slot too (read by selecting it, under the gate, with the original selection put back): a write to a slot that is not
 * in the backup is refused, and so is selecting a slot whose snapshot boosts. [activePresetId] is what the DAC had selected, which is what a restore selects again at the end.
 *
 * Filed under [backupKey] (`AttachedFiio.backupKey`: per serial when the DAC exposes one, else per model).
 */
@Serializable
data class DacEqBackup(
    val backupKey: String,
    val profileId: String,
    val dacName: String,
    val takenAtEpochMs: Long,
    val activePresetId: Int,
    val eqSwitch: Boolean? = null,
    val slots: Map<Int, DacEqSlotSnapshot>,
    /**
     * B31 P1.3: the slots the app has **taken over**: it saved a flat curve with the pre-gain at 0 in them, so selecting them is safe.
     * [slots] keeps what each held before (the factory curve), untouched, and "restore factory curve" gives it back.
     */
    val ownedSlots: Set<Int> = emptySet(),
    /**
     * B59 (#53): the user slot the app uses on this DAC, **chosen once** (the first take-over reads the user slots and picks a harmless one, or the
     * listener picks) and remembered here. Null: not chosen yet; then a slot the app already took over ([ownedSlots]) is the app's, else the profile's
     * default ([FiioHidProfile.appSlotId]). Older backup files have no such field and read as null.
     */
    val chosenSlot: Int? = null,
) {
    /** Every snapshot is a whole one and the one for the active preset is there. */
    val isComplete: Boolean
        get() = slots[activePresetId] != null && slots.all { (id, slot) -> slot.presetId == id && slot.bands.isNotEmpty() }

    fun withSlot(snapshot: DacEqSlotSnapshot): DacEqBackup = copy(slots = slots + (snapshot.presetId to snapshot))

    fun withOwned(slot: Int): DacEqBackup = copy(ownedSlots = ownedSlots + slot)

    fun withoutOwned(slot: Int): DacEqBackup = copy(ownedSlots = ownedSlots - slot)

    fun owns(slot: Int): Boolean = slot in ownedSlots

    fun withChosenSlot(slot: Int?): DacEqBackup = copy(chosenSlot = slot)

    /**
     * B59 (#53): **the slot the app writes on this DAC.** A slot the app already took over stays the app's (B31's record), whatever else; else the one
     * chosen for this DAC; else, while nothing is chosen, the profile's default (USER 3 on the Melody), which is what the app used before the choice existed.
     * Only a slot the profile lists as writable counts.
     */
    fun appSlotFor(profile: FiioHidProfile): Int? {
        val writable = profile.writableSlots.map { it.id }
        return ownedSlots.firstOrNull { it in writable }
            ?: chosenSlot?.takeIf { it in writable }
            ?: profile.appSlotId
    }

    /** B59: the app has a slot on this DAC already (taken over, or chosen): nothing more to decide. */
    fun hasAppSlot(profile: FiioHidProfile): Boolean {
        val writable = profile.writableSlots.map { it.id }
        return ownedSlots.any { it in writable } || chosenSlot in writable
    }

    companion object {
        fun of(att: AttachedFiio, state: DacEqState, nowEpochMs: Long): DacEqBackup? {
            val snapshot = DacEqSlotSnapshot.of(state) ?: return null
            // B60 (#53): a pointer the model does not list (the Melody's 0x0A marker that no claim could name) is not a selection to restore to: no backup from it.
            if (att.profile.slots.isNotEmpty() && att.profile.slots.none { it.id == snapshot.presetId }) return null
            return DacEqBackup(
                backupKey = att.backupKey,
                profileId = att.profile.id,
                dacName = att.displayName,
                takenAtEpochMs = nowEpochMs,
                activePresetId = snapshot.presetId,
                eqSwitch = (state.eqSwitch as? EqRead.Read)?.value,
                slots = mapOf(snapshot.presetId to snapshot),
            )
        }
    }
}

/**
 * B30-P2: backups on disk, one file per DAC, written atomically (a temporary file renamed over the old one) and read back before
 * [save] reports success. The one a DAC already has is **never overwritten silently**: [save] with `replace` moves the old one
 * aside first (`<key>.<epoch>.json`) and keeps it.
 */
class DacEqBackupStore(private val directory: File, private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }) {
    private fun file(key: String) = File(directory, "${safe(key)}.json")

    fun load(key: String): DacEqBackup? = runCatching {
        file(key).takeIf { it.isFile }?.let { json.decodeFromString<DacEqBackup>(it.readText()) }
    }.getOrNull()

    /**
     * Writes [backup] and reads it back. False when it could not be stored exactly, in which case nothing has changed on disk for
     * the caller to rely on.
     */
    fun save(backup: DacEqBackup, replace: Boolean = false, nowEpochMs: Long = backup.takenAtEpochMs): Boolean {
        directory.mkdirs()
        val target = file(backup.backupKey)
        if (target.isFile) {
            if (!replace) return false
            val archive = File(directory, "${safe(backup.backupKey)}.$nowEpochMs.json")
            if (!target.renameTo(archive)) return false
        }
        val temporary = File(directory, "${safe(backup.backupKey)}.tmp")
        return runCatching {
            temporary.writeText(json.encodeToString(backup))
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target)) { "rename failed" }
            }
            load(backup.backupKey) == backup
        }.getOrDefault(false)
    }

    /** Replaces the content of an existing backup in place (adding a slot's snapshot): the original snapshots are untouched. */
    fun update(backup: DacEqBackup): Boolean {
        val current = load(backup.backupKey) ?: return false
        if (current.takenAtEpochMs != backup.takenAtEpochMs || current.activePresetId != backup.activePresetId) return false
        if (current.slots.any { (id, slot) -> backup.slots[id]?.sameContentAs(slot) != true }) return false
        val temporary = File(directory, "${safe(backup.backupKey)}.tmp")
        return runCatching {
            temporary.writeText(json.encodeToString(backup))
            val target = file(backup.backupKey)
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target)) { "rename failed" }
            }
            load(backup.backupKey) == backup
        }.getOrDefault(false)
    }

    private fun safe(key: String) = key.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
}
