package com.lostf1sh.pixelplayeross.data.dac.fiio

/** One preset slot of a DAC: its id on the wire, its name, and whether the user may write it. */
data class FiioSlot(val id: Int, val name: String, val writable: Boolean)

/**
 * F-01 / B30: FiiO / Snowsky DACs that speak FiiO's HID control protocol.
 *
 * The table (product ids, report ids, band counts, gain and Q ranges, HID interface, name aliases, supported filter types, the
 * "Close EQ" preset id and, for the Melody, the slot list) is ported from WolfEQ's `Models/FiioDeviceProfile.cs`
 * (github.com/audioslayer/wolfeq, MIT), whose community entries are in turn adapted from Pragmatic Audio's DevicePEQ
 * (0BSD, `devicePEQ/usbDeviceConfig.js` and `peqConstraintsConfig.json`); see THIRD_PARTY_NOTICES.md. The code is our own.
 *
 * Two flags, kept apart on purpose (B30):
 * - [upstreamVerified] mirrors WolfEQ's own `IsVerified`: only the K13 R2R is verified *there*. It is provenance.
 * - [verified] means verified **by this app on real hardware**, and it is what the write gate asks. It is false for every row:
 *   until a reviewer's device run passes (B30-resultado, runbook), a flag copied from another project must not let this app
 *   write a DAC's persistent configuration.
 *
 * [slots] and [appSlotId] exist only where this app is prepared to write (the Melody); a row without them is read-only whatever
 * else is switched on.
 */
data class FiioHidProfile(
    val id: String,
    val displayName: String,
    /** Null where the table matches the model by name only. */
    val productId: Int?,
    val reportId: Int,
    /** Null: use the device's first HID interface. */
    val hidInterfaceNumber: Int?,
    val bandCount: Int,
    val minGainDb: Double,
    val maxGainDb: Double,
    val minQ: Double,
    val maxQ: Double,
    val productNameAliases: List<String> = emptyList(),
    val upstreamVerified: Boolean = false,
    val verified: Boolean = false,
    val supportedFilters: Set<FiioFilterType> = FiioFilterType.entries.toSet(),
    val minFrequencyHz: Int = 20,
    val maxFrequencyHz: Int = 20_000,
    /** The preset id that is "Close EQ" / "BYPASS" (WolfEQ `DisabledPresetId`, DevicePEQ `disabledPresetId`). */
    val disabledPresetId: Int? = null,
    val slots: List<FiioSlot> = emptyList(),
    /** The command that persists the working set into a slot (WolfEQ `SaveCommandId`, DevicePEQ `saveCommandId`). */
    val saveCommand: Int = 0x19,
    /** The user slot this app writes its EQ into, never one the user may be using by hand. */
    val appSlotId: Int? = null,
    /**
     * B31 P1.2: what a positive global gain does to the level on this model. **[PreGainSense.UNCONFIRMED] for every row** until the
     * reviewer's listening check says which it is and a later step writes it here; the debug override [DacEqPreGainSenseSwitch]
     * stands in for that step on one phone.
     */
    val preGainSense: PreGainSense = PreGainSense.UNCONFIRMED,
) {
    val writableSlots: List<FiioSlot> get() = slots.filter { it.writable }

    /** This row can carry a write at all (a slot table and a slot of ours); [verified] and the gate decide whether it does. */
    val canBeWritten: Boolean get() = appSlotId != null && slots.any { it.id == appSlotId && it.writable }
}

object FiioHidProfiles {
    /** FiiO's USB vendor id, shared by the Snowsky line. */
    const val VENDOR_ID = 0x2972

    private val coreFilters = setOf(FiioFilterType.PEAK, FiioFilterType.LOW_SHELF, FiioFilterType.HIGH_SHELF)
    private val allExceptBandPass = FiioFilterType.entries.toSet() - FiioFilterType.BAND_PASS

    /**
     * The Melody's slots: WolfEQ's `SnowskyMelody.Slots` and DevicePEQ's `"SNOWSKY Melody".availableSlots` agree: seven
     * factory presets (0..6), three user slots (0xA0..0xA2) and 0xF0 "Close EQ". The app uses USER 3 (0xA2).
     */
    private val melodySlots = listOf(
        FiioSlot(0x00, "Jazz", false), FiioSlot(0x01, "Pop", false), FiioSlot(0x02, "Rock", false),
        FiioSlot(0x03, "Dance", false), FiioSlot(0x04, "R&B", false), FiioSlot(0x05, "Classic", false),
        FiioSlot(0x06, "HipHop", false),
        FiioSlot(0xA0, "USER 1", true), FiioSlot(0xA1, "USER 2", true), FiioSlot(0xA2, "USER 3", true),
        FiioSlot(0xF0, "Close EQ", false),
    )

    val all: List<FiioHidProfile> = listOf(
        FiioHidProfile("fiio-k13-r2r", "FiiO K13 R2R", 0x0120, 0x07, 3, 10, -24.0, 12.0, 0.1, 10.0,
            upstreamVerified = true, disabledPresetId = 0xF0),
        FiioHidProfile("fiio-br15-r2r", "FiiO BR15 R2R", null, 0x07, null, 10, -24.0, 12.0, 0.1, 10.0,
            listOf("FIIO BR15 R2R", "BR15 R2R"), supportedFilters = allExceptBandPass, disabledPresetId = 0xF0),
        FiioHidProfile("fiio-qx13", "FiiO QX13", null, 0x07, null, 10, -24.0, 12.0, 0.1, 10.0,
            listOf("FIIO QX13", "QX13"), disabledPresetId = 0xF0),
        FiioHidProfile("fiio-btr17", "FiiO BTR17", null, 0x07, null, 10, -24.0, 12.0, 0.1, 10.0,
            listOf("FIIO BTR17", "BTR17"), supportedFilters = coreFilters, disabledPresetId = 0xF0, saveCommand = 0x21),
        FiioHidProfile("fiio-btr13", "FiiO BTR13", null, 0x07, null, 10, -12.0, 12.0, 0.1, 10.0,
            listOf("FIIO BTR13", "BTR13"), disabledPresetId = 0x0B),
        FiioHidProfile("fiio-ka15", "FiiO KA15", 0x0104, 0x07, null, 10, -12.0, 12.0, 0.1, 10.0, disabledPresetId = 0x0A),
        FiioHidProfile("fiio-ka17", "FiiO KA17", 0x0093, 0x01, null, 10, -12.0, 12.0, 0.1, 10.0, disabledPresetId = 0x0A),
        FiioHidProfile("fiio-ja11", "FiiO JA11", 0x0102, 0x02, null, 5, -12.0, 12.0, 0.1, 10.0,
            supportedFilters = coreFilters, disabledPresetId = 0x04),
        FiioHidProfile("snowsky-melody", "Snowsky Melody", 0x0126, 0x07, null, 10, -12.0, 12.0, 0.1, 10.0,
            disabledPresetId = 0xF0, slots = melodySlots, appSlotId = 0xA2),
        FiioHidProfile("snowsky-retro-nano", "Snowsky Retro Nano", null, 0x07, 3, 10, -12.0, 12.0, 0.1, 10.0,
            listOf("RETRO NANO", "SNOWSKY RETRO NANO", "FiiO SNOWSKY RETRO NANO"), disabledPresetId = 0x0B),
    )

    /** By product id first; by product name only for the models the table knows by name. */
    fun match(vendorId: Int, productId: Int, productName: String?): FiioHidProfile? {
        if (vendorId != VENDOR_ID) return null
        all.firstOrNull { it.productId == productId }?.let { return it }
        val name = productName?.trim()?.uppercase() ?: return null
        return all.firstOrNull { profile ->
            profile.productId == null && profile.productNameAliases.any { it.uppercase() == name }
        }
    }
}
