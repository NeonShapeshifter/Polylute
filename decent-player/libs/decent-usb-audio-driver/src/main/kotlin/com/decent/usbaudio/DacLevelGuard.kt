package com.decent.usbaudio

/** The DAC's Feature Unit volume, as [DacLevelGuard] needs it; [UsbAudioDevice] implements it. */
interface FeatureUnitPort {
    fun readRange(): UsbVolumeRange?
    fun readUnits(): Int?
    /** Writes [units] and returns the level read back, or null if the write failed. */
    fun writeUnits(units: Int): Int?
}

/**
 * The DAC level across stream reconfigurations, and the log of every Feature Unit change (AUD-126).
 *
 * Measured on the device: the SNOWSKY Melody went from −6 dB (the owner's Safety ceiling, restored by
 * the AUD-118 guard at the claim) to 0 dB with nobody touching a volume. In between, ten NEXT with
 * rate changes: each one `setAlt(0)` → clock `SET_CUR` → `setAlt(3)` on the device already open,
 * which the claim-time guard never saw. So the same restore — the quietest of the saved Direct level
 * and Safety's ceiling, never raising — runs after every reconfiguration, before the first packet.
 *
 * Every write is logged with its origin, and every read that differs from the last known level as an
 * external change, so an unknown writer of the Feature Unit can no longer go unseen.
 */
class DacLevelGuard(private val log: (String) -> Unit) {
    /** The level the Feature Unit was last known at: our own write, or a read. */
    @Volatile
    var lastKnownUnits: Int? = null
        private set

    fun onWrite(origin: String, requested: Int, target: Int, readBack: Int?) {
        log("FU_WRITE origin=$origin requested=$requested target=$target readBack=$readBack " +
            "(${dbOf(target)} dB) was=${lastKnownUnits?.let { "$it (${dbOf(it)} dB)" } ?: "unknown"}")
        if (readBack != null) lastKnownUnits = readBack
    }

    fun onRead(origin: String, units: Int) {
        val known = lastKnownUnits
        if (known != null && known != units) {
            log("FU_EXTERNAL_CHANGE $known → $units (${dbOf(known)} → ${dbOf(units)} dB) seen by $origin")
        }
        lastKnownUnits = units
    }

    /**
     * After an alt/clock/rate change, before any audio: brings the level down to the quietest of
     * [savedUnits] (the level heard before the reconfiguration when nothing is saved) and
     * [guardTargetUnits] (the host's guard: the lower of its saved level and Safety's ceiling). Never raises. UNVERIFIED when it cannot read the level or the restore does not
     * take — nothing may then play through the DAC.
     */
    fun restoreAfterReconfigure(port: FeatureUnitPort, savedUnits: Int?, guardTargetUnits: Int?, reason: String): OpenLevelCheck {
        val before = lastKnownUnits
        val range = port.readRange()
        val current = port.readUnits()
        if (range == null || current == null) {
            log("FU_RECONFIGURE $reason current=unreadable — level UNVERIFIED")
            return OpenLevelCheck.UNVERIFIED
        }
        onRead("reconfigure", current)
        val target = DirectOpenLevelPolicy.target(current, savedUnits ?: before, guardTargetUnits)?.let(range::clamp)
        log("FU_RECONFIGURE $reason current=$current (${dbOf(current)} dB) before=${before ?: "unknown"} " +
            "savedDirect=${savedUnits ?: "none"} guardTarget=${guardTargetUnits ?: "none"} " +
            "target=${target?.let { "$it (${dbOf(it)} dB)" } ?: "unchanged"}")
        if (target == null) return OpenLevelCheck.VERIFIED
        val readBack = port.writeUnits(target)
        onWrite("reconfigure", target, target, readBack)
        return if (readBack == null || readBack > target) {
            log("FU_RECONFIGURE $reason restore to $target failed (readBack=$readBack) — level UNVERIFIED")
            OpenLevelCheck.UNVERIFIED
        } else {
            OpenLevelCheck.VERIFIED
        }
    }

    private fun dbOf(units: Int): Float = units / UsbVolumeRange.UNITS_PER_DB.toFloat()
}
