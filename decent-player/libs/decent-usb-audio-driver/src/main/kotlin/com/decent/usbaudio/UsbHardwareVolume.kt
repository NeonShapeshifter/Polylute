package com.decent.usbaudio

/**
 * The UAC2 Feature Unit volume control on the path to the DAC's analog output.
 *
 * @property unitId Feature Unit entity ID, used in wIndex.
 * @property channels Control numbers carrying a host-programmable volume: `[0]` for a master
 *           control, otherwise the logical channels (1 = left, 2 = right, ...). A DAC that exposes
 *           only per-channel volume must have every channel written, or it plays unbalanced.
 */
data class UacVolumeTopology(
    val unitId: Int,
    val channels: List<Int>,
)

/**
 * A UAC2 volume RANGE, in the spec's native unit of 1/256 dB.
 *
 * 0 dB is no attenuation — the loudest the DAC's analog stage goes without gain. Some DACs report a
 * positive maximum (analog gain above 0 dB).
 */
data class UsbVolumeRange(
    val minUnits: Int,
    val maxUnits: Int,
    val resolutionUnits: Int,
) {
    val isValid: Boolean
        get() = minUnits < maxUnits && resolutionUnits > 0

    fun clamp(units: Int): Int = units.coerceIn(minUnits, maxUnits)

    companion object {
        const val UNITS_PER_DB = 256
    }
}

/**
 * Finds the Feature Unit volume control that feeds the DAC's analog output.
 *
 * Pure over the raw configuration descriptors so it can be tested against captured bytes. Only
 * UAC2 AudioControl is understood (4-byte bmaControls); anything else yields null, which callers
 * treat as "no hardware volume" rather than guessing.
 */
/** One Feature Unit of the AudioControl interface, for diagnostics (AUD-119). */
data class UacFeatureUnitInfo(
    val unitId: Int,
    val sourceId: Int,
    /** `bmaControls` per channel: index 0 is the master channel, then channel 1, 2, … */
    val controlsPerChannel: List<Int>,
) {
    /** Channels whose volume control (bits 2-3) is present at all, read-only or programmable. */
    val volumeChannels: List<Int>
        get() = controlsPerChannel.indices.filter { (controlsPerChannel[it] shr 2) and 0b11 != 0 }
}

object UacFeatureUnitParser {

    /** Every Feature Unit in the AudioControl interface, in descriptor order (AUD-119 diagnostic). */
    fun describeAll(raw: ByteArray): List<UacFeatureUnitInfo> {
        val units = mutableListOf<UacFeatureUnitInfo>()
        var i = 0
        var inControl = false
        while (i + 1 < raw.size) {
            val bLength = raw.u8(i)
            if (bLength < 2 || i + bLength > raw.size) break
            val type = raw.u8(i + 1)
            if (type == 0x04 && bLength >= 9) {
                inControl = raw.u8(i + 5) == 1 && raw.u8(i + 6) == 1
            } else if (inControl && type == CS_INTERFACE && bLength >= 10 && raw.u8(i + 2) == AC_FEATURE_UNIT &&
                (bLength - 6) % 4 == 0
            ) {
                val controls = (0 until (bLength - 6) / 4).map { ch -> raw.le32(i + 5 + ch * 4) }
                units += UacFeatureUnitInfo(unitId = raw.u8(i + 3), sourceId = raw.u8(i + 4), controlsPerChannel = controls)
            }
            i += bLength
        }
        return units
    }

    /**
     * The interrupt endpoint of the AudioControl interface (UAC2 status/change notifications), or
     * null. A DAC that announces front-panel volume changes does it through this endpoint.
     */
    fun audioControlInterruptEndpoint(raw: ByteArray): Int? {
        var i = 0
        var inControl = false
        while (i + 1 < raw.size) {
            val bLength = raw.u8(i)
            if (bLength < 2 || i + bLength > raw.size) break
            val type = raw.u8(i + 1)
            if (type == 0x04 && bLength >= 9) {
                inControl = raw.u8(i + 5) == 1 && raw.u8(i + 6) == 1
            } else if (inControl && type == 0x05 && bLength >= 7 && (raw.u8(i + 3) and 0x03) == 0x03) {
                return raw.u8(i + 2)
            }
            i += bLength
        }
        return null
    }

    private const val CS_INTERFACE = 0x24
    private const val AC_INPUT_TERMINAL = 0x02
    private const val AC_OUTPUT_TERMINAL = 0x03
    private const val AC_FEATURE_UNIT = 0x06
    private const val UAC2_PROTOCOL = 0x20
    private const val TERMINAL_USB_STREAMING = 0x0101

    /** bmaControls bits 2..3: Volume Control. 0b11 = host programmable. */
    private const val VOLUME_PROGRAMMABLE = 0b11

    private data class FeatureUnit(val sourceId: Int, val controls: List<Int>)
    private data class OutputTerminal(val terminalType: Int, val sourceId: Int)

    fun findOutputVolume(raw: ByteArray): UacVolumeTopology? {
        val featureUnits = mutableMapOf<Int, FeatureUnit>()
        val outputs = mutableListOf<OutputTerminal>()
        val inputTerminals = mutableSetOf<Int>()

        var i = 0
        var inUac2Control = false
        while (i + 1 < raw.size) {
            val bLength = raw.u8(i)
            if (bLength < 2 || i + bLength > raw.size) break
            val bDescriptorType = raw.u8(i + 1)

            if (bDescriptorType == 0x04 && bLength >= 9) {
                inUac2Control = raw.u8(i + 5) == 1 && raw.u8(i + 6) == 1 && raw.u8(i + 7) == UAC2_PROTOCOL
            } else if (inUac2Control && bDescriptorType == CS_INTERFACE && bLength >= 3) {
                when (raw.u8(i + 2)) {
                    AC_INPUT_TERMINAL -> if (bLength >= 4) inputTerminals += raw.u8(i + 3)
                    AC_OUTPUT_TERMINAL -> if (bLength >= 12) {
                        outputs += OutputTerminal(
                            terminalType = raw.u8(i + 4) or (raw.u8(i + 5) shl 8),
                            sourceId = raw.u8(i + 7),
                        )
                    }
                    // bLength = 6 + (channels + 1) * 4: header(5) + bmaControls + iFeature(1).
                    AC_FEATURE_UNIT -> if (bLength >= 10 && (bLength - 6) % 4 == 0) {
                        val controls = (0 until (bLength - 6) / 4).map { ch -> raw.le32(i + 5 + ch * 4) }
                        featureUnits[raw.u8(i + 3)] = FeatureUnit(raw.u8(i + 4), controls)
                    }
                }
            }
            i += bLength
        }

        // Walk back from each analog output. Only a chain of Feature Units is followed: a mixer,
        // selector or processing unit in between means the level heard depends on more than this
        // control, and claiming it as "the" volume would not be the truth.
        for (output in outputs) {
            if (output.terminalType == TERMINAL_USB_STREAMING) continue
            var id = output.sourceId
            val visited = mutableSetOf<Int>()
            while (visited.add(id)) {
                val unit = featureUnits[id] ?: break
                val channels = volumeChannels(unit.controls)
                if (channels.isNotEmpty()) return UacVolumeTopology(unitId = id, channels = channels)
                if (unit.sourceId in inputTerminals) break
                id = unit.sourceId
            }
        }
        return null
    }

    private fun volumeChannels(controls: List<Int>): List<Int> {
        fun hasVolume(ch: Int) = (controls[ch] shr 2) and 0b11 == VOLUME_PROGRAMMABLE
        if (controls.isNotEmpty() && hasVolume(0)) return listOf(0)
        return (1 until controls.size).filter(::hasVolume)
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.le32(offset: Int): Int =
        u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16) or (u8(offset + 3) shl 24)
}
