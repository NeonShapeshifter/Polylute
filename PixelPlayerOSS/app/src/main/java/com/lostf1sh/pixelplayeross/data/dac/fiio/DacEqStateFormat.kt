package com.lostf1sh.pixelplayeross.data.dac.fiio

import java.util.Locale

/** The whole parsed EQ state as log lines (`DAC_EQ_READ`, and the writer's before/after). One value per line, nothing summarised away. */
object DacEqStateFormat {
    fun lines(state: DacEqState, prefix: String = "DAC_HID|STATE"): List<String> {
        val capability = state.capability
        return buildList {
            add(
                "$prefix profile=${capability.profileId} (the table's claim, not a reading): ${capability.bandCount} bands, " +
                    "${db(capability.minGainDb)}..${db(capability.maxGainDb)} dB, Q ${capability.minQ}..${capability.maxQ}, " +
                    "${capability.minFrequencyHz}..${capability.maxFrequencyHz} Hz, filters=${capability.filterTypes.joinToString("/") { it.name }}, " +
                    "closePreset=${capability.disabledPresetId?.let { "0x%02X".format(it) } ?: "none"}, verified=${capability.verified}"
            )
            add("$prefix read: preset=${preset(state.activePreset, capability)} count=${read(state.bandCount) { it.toString() }} " +
                "preGain=${read(state.preGainTenthsDb) { "${tenths(it)} dB" }} switch=${read(state.eqSwitch) { if (it) "on" else "off" }}")
            state.bands.forEachIndexed { i, entry ->
                add("$prefix band ${i + 1}: ${read(entry) { b -> band(b) }}")
            }
            add("$prefix verdict=${state.verdict} label=${DacEqLabel.of(state.verdict)} complete=${state.isComplete}")
        }
    }

    fun band(b: DacEqBand): String =
        "${b.type?.name ?: "TYPE_${b.typeCode}"} ${b.frequencyHz} Hz ${tenths(b.gainTenthsDb)} dB Q ${String.format(Locale.US, "%.2f", b.q)}"

    fun tenths(value: Int): String = String.format(Locale.US, "%+.1f", value / 10.0)

    private fun db(value: Double) = String.format(Locale.US, "%+.0f", value)

    private fun <T, R> read(value: EqRead<T>, show: (T) -> R): String = when (value) {
        is EqRead.Read -> "read ${show(value.value)}"
        is EqRead.Unknown -> "unknown (${value.why})"
        is EqRead.Failed -> "FAILED (${value.why})"
    }

    private fun preset(value: EqRead<Int>, capability: DacEqCapability): String = read(value) { id ->
        val close = if (id == capability.disabledPresetId) " = Close EQ" else ""
        "0x%02X$close".format(id)
    }
}
