package com.lostf1sh.pixelplayeross.data.diagnostics

import com.decent.usbaudio.media3.BitPerfectReason
import com.decent.usbaudio.media3.BitPerfectTruth
import com.decent.usbaudio.media3.BitPerfectVerdict
import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.data.dac.UsbDacDescriptor
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics
import com.lostf1sh.pixelplayeross.data.service.player.AudioProcessingRequirements

/**
 * One diagnostic line. [value] is what the runtime actually reports; [isUnknown] marks a fact the
 * platform would not answer, so the surface can print "unknown" instead of inventing a plausible
 * default. Reporting a guess where the platform declined to answer is the failure mode this type
 * exists to prevent.
 */
data class HardwareDiagnosticRow(
    val label: String,
    val value: String,
    val isUnknown: Boolean = false,
)

data class HardwareDiagnosticGroup(
    val title: String,
    val rows: List<HardwareDiagnosticRow>,
)

/**
 * Assembles the read-only hardware picture from runtime state that other components already own.
 *
 * Pure by construction: it takes plain values, never a `Context`, and never queries the platform
 * itself. That keeps every statement here derived from something observable and testable, and
 * makes it impossible for this surface to become a second, drifting source of hardware truth.
 */
object HardwareDiagnostics {

    const val UNKNOWN = "unknown"

    fun deviceGroup(
        manufacturer: String,
        model: String,
        androidRelease: String,
        sdkInt: Int,
        buildDisplay: String?,
        buildIncremental: String?,
    ): HardwareDiagnosticGroup = HardwareDiagnosticGroup(
        title = "Device",
        rows = listOf(
            HardwareDiagnosticRow("Manufacturer", manufacturer),
            HardwareDiagnosticRow("Model", model),
            HardwareDiagnosticRow("Android", "$androidRelease (API $sdkInt)"),
            row("Build", buildDisplay),
            row("Incremental", buildIncremental),
        ),
    )

    /**
     * USB rows. [hostDeclared] is the manifest's `uses-feature` claim, not a probe; the honest
     * statement of whether the port can host is [attachedCount] plus what the platform enumerates.
     */
    fun usbGroup(
        hostDeclared: Boolean,
        descriptors: List<UsbDacDescriptor>,
        apiPermissionGranted: Boolean?,
    ): HardwareDiagnosticGroup {
        val rows = mutableListOf(
            HardwareDiagnosticRow("Host declared", yesNo(hostDeclared)),
            HardwareDiagnosticRow("Attached DACs", descriptors.size.toString()),
        )
        if (descriptors.isEmpty()) {
            rows += HardwareDiagnosticRow("Device", "none", isUnknown = true)
            rows += HardwareDiagnosticRow("Permission", "n/a", isUnknown = true)
            return HardwareDiagnosticGroup("USB", rows)
        }
        descriptors.forEachIndexed { index, descriptor ->
            val prefix = if (descriptors.size > 1) "Dev ${index + 1} " else ""
            rows += HardwareDiagnosticRow("${prefix}VID:PID", "%04X:%04X".format(descriptor.vendorId, descriptor.productId))
            rows += HardwareDiagnosticRow("${prefix}Name", descriptor.detectedName)
            rows += HardwareDiagnosticRow(
                "${prefix}Serial",
                descriptor.serialNumber?.takeIf { it.isNotBlank() } ?: "none (model-scoped)",
                isUnknown = descriptor.serialNumber.isNullOrBlank(),
            )
            rows += HardwareDiagnosticRow("${prefix}Identity", descriptor.identityScope.name)
        }
        rows += HardwareDiagnosticRow(
            "Permission",
            when (apiPermissionGranted) {
                true -> "granted"
                false -> "not granted"
                null -> UNKNOWN
            },
            isUnknown = apiPermissionGranted == null,
        )
        return HardwareDiagnosticGroup("USB", rows)
    }

    /**
     * Audio rows. Every value is read from the route authority rather than from any user setting,
     * so a deliberately-configured Direct preference that has not negotiated still reads as
     * "not active".
     */
    fun audioGroup(
        route: AudioRouteState,
        usb: UsbDirectAudioDiagnostics,
        processing: AudioProcessingRequirements,
        forceAndroidOverride: Boolean,
        directTransitionOverride: Boolean,
    ): HardwareDiagnosticGroup {
        val blockers = processingBlockers(processing) +
            buildList {
                if (forceAndroidOverride) add("Maintenance: force Android")
                if (directTransitionOverride) add("Maintenance: Direct transition off")
            }
        return HardwareDiagnosticGroup(
            title = "Audio",
            rows = listOf(
                HardwareDiagnosticRow("Desired route", route.desiredBackend.label()),
                HardwareDiagnosticRow("Actual route", route.actualBackend.label()),
                HardwareDiagnosticRow("Transition", route.transition.label()),
                HardwareDiagnosticRow(
                    "Direct candidate",
                    when {
                        route.transition == AudioRouteTransition.DIRECT_ELIGIBLE -> "yes"
                        route.transition == AudioRouteTransition.DIRECT_NEGOTIATING -> "negotiating"
                        route.isDirectActive -> "active"
                        else -> "no"
                    },
                ),
                HardwareDiagnosticRow("Direct active", yesNo(route.isDirectActive)),
                HardwareDiagnosticRow(
                    "Confirmed rate",
                    route.confirmedOutputRateHz?.let { "$it Hz" } ?: "none",
                    isUnknown = route.confirmedOutputRateHz == null,
                ),
                HardwareDiagnosticRow("Route reason", route.reason.label()),
                row("Direct error", route.directError),
                row("Sink output", usb.sourceCodec),
                HardwareDiagnosticRow(
                    "Source rate",
                    usb.sourceSampleRate?.let { "$it Hz" } ?: "—",
                    isUnknown = usb.sourceSampleRate == null,
                ),
                HardwareDiagnosticRow(
                    "DAC best depth",
                    usb.dacBestBitDepth?.let { "$it-bit" } ?: "—",
                    isUnknown = usb.dacBestBitDepth == null,
                ),
                HardwareDiagnosticRow(
                    "DAC rates",
                    usb.dacSupportedRates.takeIf { it.isNotEmpty() }
                        ?.joinToString(", ") { "$it" } ?: "—",
                    isUnknown = usb.dacSupportedRates.isEmpty(),
                ),
                HardwareDiagnosticRow(
                    "DSP blockers",
                    blockers.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none",
                ),
                // The signal path, stated separately from the transport. "Direct active" above says
                // which wire the audio is on; these say what the application did to it on the way,
                // which is the question a listener comparing two listening modes is asking.
                HardwareDiagnosticRow(
                    "Signal path",
                    when {
                        !route.isDirectActive -> "—"
                        route.signalMode == DirectSignalMode.PROCESSED -> "Processed"
                        else -> "Pristine"
                    },
                    isUnknown = !route.isDirectActive,
                ),
                HardwareDiagnosticRow(
                    "Direct processing",
                    processingCarriedOnDirect(processing)
                        .takeIf { it.isNotEmpty() && route.isDirectActive }
                        ?.joinToString(", ")
                        ?: if (route.isDirectActive) "none" else "—",
                ),
                HardwareDiagnosticRow(
                    "Bit-perfect",
                    route.bitPerfect.label(),
                    isUnknown = route.bitPerfect.verdict == BitPerfectVerdict.UNKNOWN,
                ),
            ),
        )
    }

    /**
     * The bit-perfect verdict as a listener-facing sentence.
     *
     * Never derived from the transport. "USB Direct" is not an answer to this question and the
     * surface must not print it as one; a Direct stream running an equalizer is not bit-perfect,
     * and a stream whose payload was never compared against its source has not earned the claim
     * however clean its path looks.
     */
    private fun BitPerfectTruth.label(): String = when (verdict) {
        BitPerfectVerdict.YES -> "Yes"
        BitPerfectVerdict.UNKNOWN -> "Not measured"
        BitPerfectVerdict.NO -> when (reason) {
            BitPerfectReason.EQUALIZER_ACTIVE -> "No — Equalizer active"
            BitPerfectReason.DIGITAL_VOLUME_ACTIVE -> "No — Digital volume active"
            BitPerfectReason.REPLAY_GAIN_ACTIVE -> "No — ReplayGain active"
            BitPerfectReason.SPEED_ACTIVE -> "No — Speed active"
            BitPerfectReason.NOT_ON_DIRECT_TRANSPORT -> "No — not on Direct"
            BitPerfectReason.NOT_MEASURED, null -> "No"
        }
    }

    fun powerGroup(
        batteryPercent: Int?,
        charging: Boolean?,
        pluggedLabel: String?,
        screenInteractive: Boolean?,
    ): HardwareDiagnosticGroup = HardwareDiagnosticGroup(
        title = "Power",
        rows = listOf(
            HardwareDiagnosticRow(
                "Battery",
                batteryPercent?.let { "$it%" } ?: UNKNOWN,
                isUnknown = batteryPercent == null,
            ),
            HardwareDiagnosticRow(
                "Charging",
                when (charging) {
                    true -> "yes"
                    false -> "no"
                    null -> UNKNOWN
                },
                isUnknown = charging == null,
            ),
            HardwareDiagnosticRow(
                "Power source",
                pluggedLabel ?: "none",
                isUnknown = pluggedLabel == null,
            ),
            HardwareDiagnosticRow(
                "Screen",
                when (screenInteractive) {
                    true -> "interactive"
                    false -> "off"
                    null -> UNKNOWN
                },
                isUnknown = screenInteractive == null,
            ),
        ),
    )

    fun applianceGroup(
        dedicatedMode: Boolean,
        lockTaskState: String,
        dapLockScreenEnabled: Boolean,
        maintenanceActive: Boolean,
        overrides: MaintenanceOverrides,
    ): HardwareDiagnosticGroup = HardwareDiagnosticGroup(
        title = "Appliance",
        rows = listOf(
            HardwareDiagnosticRow("Dedicated Mode", yesNo(dedicatedMode)),
            HardwareDiagnosticRow("Lock Task", lockTaskState),
            HardwareDiagnosticRow("DAP Lock Screen", yesNo(dapLockScreenEnabled)),
            HardwareDiagnosticRow("Maintenance", yesNo(maintenanceActive)),
            HardwareDiagnosticRow(
                "Active overrides",
                if (overrides.isEmpty) "none" else "${overrides.count} (${overrides.diagnosticCount} diagnostic)",
            ),
        ),
    )

    /** The DSP reasons the Android path is required, named as the policy names them. */
    /**
     * The requirements that leave USB Direct unreachable, as opposed to the ones it carries.
     *
     * This list is deliberately shorter than it used to be. EQ, ReplayGain and digital volume are
     * PCM-domain work the Direct transport can do itself, so naming them here would report a
     * listener's equalizer as the reason Direct was unavailable at the exact moment Direct was
     * available and using it. Only requirements with no Direct implementation remain: they are
     * carried by the Android path because that is where their implementation lives.
     */
    fun processingBlockers(processing: AudioProcessingRequirements): List<String> = buildList {
        if (processing.nonDefaultSpeed) add("speed")
        if (processing.crossfade) add("crossfade")
        if (processing.androidSessionEffect) add("Android effect")
    }

    /**
     * The requirements the Direct path is currently satisfying with DSP of its own.
     *
     * Reported separately from [processingBlockers] because the two are different answers to
     * different questions — "why is this not Direct" and "what is Direct doing to the sound". A
     * single column could only ever answer one of them.
     */
    fun processingCarriedOnDirect(processing: AudioProcessingRequirements): List<String> = buildList {
        if (processing.graphicEq) add("EQ")
        if (processing.replayGain) add("ReplayGain")
        if (processing.digitalVolume) add("digital volume")
    }

    private fun row(label: String, value: String?): HardwareDiagnosticRow =
        HardwareDiagnosticRow(label, value?.takeIf { it.isNotBlank() } ?: "—", isUnknown = value.isNullOrBlank())

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    private fun AudioBackend.label(): String = when (this) {
        AudioBackend.ANDROID_AUDIO -> "Android audio"
        AudioBackend.USB_DIRECT -> "USB Direct"
    }

    private fun AudioRouteTransition.label(): String = when (this) {
        AudioRouteTransition.ANDROID_ACTIVE -> "Android active"
        AudioRouteTransition.DIRECT_ELIGIBLE -> "Direct eligible"
        AudioRouteTransition.DIRECT_NEGOTIATING -> "Direct negotiating"
        AudioRouteTransition.DIRECT_ACTIVE -> "Direct active"
        AudioRouteTransition.DIRECT_FAILED_FALLBACK -> "Direct failed → Android"
    }

    private fun AudioRouteReason.label(): String = when (this) {
        AudioRouteReason.STARTING -> "Starting"
        AudioRouteReason.NO_USB_DAC -> "No USB DAC"
        AudioRouteReason.USB_PERMISSION_REQUIRED -> "USB permission required"
        AudioRouteReason.SOURCE_NOT_DIRECT_COMPATIBLE -> "Source not Direct-compatible"
        AudioRouteReason.SPEED_REQUIRES_ANDROID -> "Speed requires Android"
        AudioRouteReason.CROSSFADE_REQUIRES_ANDROID -> "Crossfade requires Android"
        AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID -> "Android effect requires Android"
        AudioRouteReason.SAFETY_TRANSITION_DEFERRED -> "Transition deferred (audible)"
        // The two Direct reasons name the signal path, because that is the part of a Direct route a
        // listener can actually hear the difference between.
        AudioRouteReason.DIRECT_PRISTINE -> "Direct — pristine"
        AudioRouteReason.DIRECT_PROCESSED -> "Direct — processed"
        AudioRouteReason.DIRECT_ACTIVE -> "Direct active"
        AudioRouteReason.DIRECT_FAILED_FALLBACK -> "Direct failed → Android"
        AudioRouteReason.MAINTENANCE_FORCE_ANDROID -> "Maintenance: force Android"
        AudioRouteReason.MAINTENANCE_DIRECT_DISABLED -> "Maintenance: Direct disabled"
    }
}
