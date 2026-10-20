package com.lostf1sh.pixelplayeross.data.diagnostics

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.dac.DacIdentityScope
import com.decent.usbaudio.media3.BitPerfectTruth
import com.decent.usbaudio.media3.DirectProcessing
import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.data.dac.UsbDacDescriptor
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioProcessingRequirements
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics
import com.decent.usbaudio.media3.UsbDirectSinkState
import org.junit.jupiter.api.Test

/**
 * Phase 13 — the device diagnostic surface.
 *
 * The property under test throughout is that the surface reports what the runtime says and never
 * what the user configured. A diagnostic that infers hardware state from a setting is worse than
 * no diagnostic, because it agrees with the user's belief instead of checking it.
 */
class HardwareDiagnosticsTest {

    private val melody = UsbDacDescriptor(
        vendorId = 0x2972,
        productId = 0x0126,
        manufacturer = "FIIO",
        product = "SNOWSKY Melody",
        serialNumber = null,
        connectionPath = "/dev/bus/usb/001/002",
    )

    private fun rows(group: HardwareDiagnosticGroup, label: String): HardwareDiagnosticRow =
        group.rows.first { it.label == label }

    private fun value(group: HardwareDiagnosticGroup, label: String): String =
        rows(group, label).value

    // ---- USB -------------------------------------------------------------------------------

    @Test fun `the Melody reports its real VID PID and its absent serial honestly`() {
        val group = HardwareDiagnostics.usbGroup(
            hostDeclared = true,
            descriptors = listOf(melody),
            apiPermissionGranted = true,
        )
        assertThat(value(group, "VID:PID")).isEqualTo("2972:0126")
        assertThat(value(group, "Name")).isEqualTo("SNOWSKY Melody")
        assertThat(value(group, "Serial")).isEqualTo("none (model-scoped)")
        assertThat(rows(group, "Serial").isUnknown).isTrue()
        assertThat(value(group, "Identity")).isEqualTo(DacIdentityScope.MODEL_SCOPED.name)
        assertThat(value(group, "Permission")).isEqualTo("granted")
    }

    /** The absent serial must not be rendered as unknown-but-probably-there, nor as a blank. */
    @Test fun `an absent serial is stated as model-scoped rather than left empty`() {
        val group = HardwareDiagnostics.usbGroup(true, listOf(melody), true)
        val serial = rows(group, "Serial")
        assertThat(serial.value).isNotEmpty()
        assertThat(serial.value).contains("model-scoped")
        assertThat(serial.isUnknown).isTrue()
    }

    @Test fun `a DAC with a real serial reports it and is unit-scoped`() {
        val group = HardwareDiagnostics.usbGroup(
            hostDeclared = true,
            descriptors = listOf(melody.copy(serialNumber = "SN-0001")),
            apiPermissionGranted = true,
        )
        assertThat(value(group, "Serial")).isEqualTo("SN-0001")
        assertThat(rows(group, "Serial").isUnknown).isFalse()
        assertThat(value(group, "Identity")).isEqualTo(DacIdentityScope.UNIT_SPECIFIC.name)
    }

    @Test fun `no attached DAC reads as none rather than as an empty list`() {
        val group = HardwareDiagnostics.usbGroup(true, emptyList(), null)
        assertThat(value(group, "Attached DACs")).isEqualTo("0")
        assertThat(value(group, "Device")).isEqualTo("none")
        assertThat(rows(group, "Device").isUnknown).isTrue()
    }

    @Test fun `withheld permission is reported as not granted, never as granted`() {
        val group = HardwareDiagnostics.usbGroup(true, listOf(melody), false)
        assertThat(value(group, "Permission")).isEqualTo("not granted")
        assertThat(rows(group, "Permission").isUnknown).isFalse()
    }

    @Test fun `an unanswerable permission question is marked unknown`() {
        val group = HardwareDiagnostics.usbGroup(true, listOf(melody), null)
        assertThat(rows(group, "Permission").isUnknown).isTrue()
    }

    @Test fun `two attached DACs are both listed without collapsing`() {
        val group = HardwareDiagnostics.usbGroup(
            hostDeclared = true,
            descriptors = listOf(melody, melody.copy(vendorId = 0x262A, productId = 0x0001, product = "Fosi DS2")),
            apiPermissionGranted = true,
        )
        assertThat(value(group, "Attached DACs")).isEqualTo("2")
        assertThat(group.rows.count { it.label.endsWith("VID:PID") }).isEqualTo(2)
        assertThat(value(group, "Dev 1 VID:PID")).isEqualTo("2972:0126")
        assertThat(value(group, "Dev 2 VID:PID")).isEqualTo("262A:0001")
    }

    // ---- Audio -----------------------------------------------------------------------------

    @Test fun `an unnegotiated Direct preference does not read as Direct active`() {
        val group = HardwareDiagnostics.audioGroup(
            route = AudioRouteState(
                desiredBackend = AudioBackend.USB_DIRECT,
                actualBackend = AudioBackend.ANDROID_AUDIO,
                transition = AudioRouteTransition.DIRECT_NEGOTIATING,
                reason = AudioRouteReason.DIRECT_PRISTINE,
            ),
            usb = UsbDirectAudioDiagnostics(),
            processing = AudioProcessingRequirements(),
            forceAndroidOverride = false,
            directTransitionOverride = false,
        )
        assertThat(value(group, "Direct active")).isEqualTo("no")
        assertThat(value(group, "Direct candidate")).isEqualTo("negotiating")
    }

    @Test fun `an active Direct route reports its confirmed rate`() {
        val group = HardwareDiagnostics.audioGroup(
            route = AudioRouteState(
                desiredBackend = AudioBackend.USB_DIRECT,
                actualBackend = AudioBackend.USB_DIRECT,
                transition = AudioRouteTransition.DIRECT_ACTIVE,
                reason = AudioRouteReason.DIRECT_ACTIVE,
                confirmedOutputRateHz = 96_000,
            ),
            usb = UsbDirectAudioDiagnostics(state = UsbDirectSinkState.USB_DIRECT_ACTIVE),
            processing = AudioProcessingRequirements(),
            forceAndroidOverride = false,
            directTransitionOverride = false,
        )
        assertThat(value(group, "Direct active")).isEqualTo("yes")
        assertThat(value(group, "Confirmed rate")).isEqualTo("96000 Hz")
        assertThat(rows(group, "Confirmed rate").isUnknown).isFalse()
    }

    /** A rate is only ever shown when something confirmed it; otherwise it is unknown. */
    @Test fun `an unconfirmed rate is never invented`() {
        val group = HardwareDiagnostics.audioGroup(
            route = AudioRouteState(),
            usb = UsbDirectAudioDiagnostics(),
            processing = AudioProcessingRequirements(),
            forceAndroidOverride = false,
            directTransitionOverride = false,
        )
        assertThat(value(group, "Confirmed rate")).isEqualTo("none")
        assertThat(rows(group, "Confirmed rate").isUnknown).isTrue()
    }

    @Test fun `only the requirements Direct cannot carry are reported as blockers`() {
        // The split this asserts is the whole point of the dual-path model. EQ, ReplayGain and
        // digital volume are PCM work the Direct transport now does itself, so listing them as
        // blockers would tell a listener their equalizer was why Direct was unavailable while
        // Direct was in fact carrying it.
        val group = HardwareDiagnostics.audioGroup(
            route = AudioRouteState(reason = AudioRouteReason.SPEED_REQUIRES_ANDROID),
            usb = UsbDirectAudioDiagnostics(),
            processing = AudioProcessingRequirements(
                graphicEq = true,
                replayGain = true,
                digitalVolume = true,
                nonDefaultSpeed = true,
                crossfade = true,
                androidSessionEffect = true,
            ),
            forceAndroidOverride = false,
            directTransitionOverride = false,
        )
        val blockers = value(group, "DSP blockers")
        assertThat(blockers).contains("speed")
        assertThat(blockers).contains("crossfade")
        assertThat(blockers).contains("Android effect")
        assertThat(blockers).doesNotContain("EQ")
        assertThat(blockers).doesNotContain("ReplayGain")
    }

    @Test fun `processed Direct names its signal path and refuses the bit-perfect claim`() {
        val group = HardwareDiagnostics.audioGroup(
            route = AudioRouteState(
                desiredBackend = AudioBackend.USB_DIRECT,
                actualBackend = AudioBackend.USB_DIRECT,
                transition = AudioRouteTransition.DIRECT_ACTIVE,
                reason = AudioRouteReason.DIRECT_PROCESSED,
                signalMode = DirectSignalMode.PROCESSED,
                bitPerfect = BitPerfectTruth.processed(setOf(DirectProcessing.EQUALIZER)),
            ),
            usb = UsbDirectAudioDiagnostics(signalMode = DirectSignalMode.PROCESSED),
            processing = AudioProcessingRequirements(graphicEq = true),
            forceAndroidOverride = false,
            directTransitionOverride = false,
        )
        assertThat(value(group, "Direct active")).isEqualTo("yes")
        assertThat(value(group, "Signal path")).isEqualTo("Processed")
        assertThat(value(group, "Direct processing")).contains("EQ")
        // The claim that must never reappear: Direct active, therefore bit-perfect.
        assertThat(value(group, "Bit-perfect")).isEqualTo("No — Equalizer active")
    }

    @Test fun `pristine Direct does not assert bit-perfect without a measurement`() {
        val group = HardwareDiagnostics.audioGroup(
            route = AudioRouteState(
                desiredBackend = AudioBackend.USB_DIRECT,
                actualBackend = AudioBackend.USB_DIRECT,
                transition = AudioRouteTransition.DIRECT_ACTIVE,
                reason = AudioRouteReason.DIRECT_PRISTINE,
                signalMode = DirectSignalMode.PRISTINE,
            ),
            usb = UsbDirectAudioDiagnostics(signalMode = DirectSignalMode.PRISTINE),
            processing = AudioProcessingRequirements(),
            forceAndroidOverride = false,
            directTransitionOverride = false,
        )
        assertThat(value(group, "Signal path")).isEqualTo("Pristine")
        assertThat(value(group, "Direct processing")).isEqualTo("none")
        assertThat(value(group, "Bit-perfect")).isEqualTo("Not measured")
    }

    @Test fun `no blockers reads as none rather than blank`() {
        val group = HardwareDiagnostics.audioGroup(
            route = AudioRouteState(),
            usb = UsbDirectAudioDiagnostics(),
            processing = AudioProcessingRequirements(),
            forceAndroidOverride = false,
            directTransitionOverride = false,
        )
        assertThat(value(group, "DSP blockers")).isEqualTo("none")
    }

    /** A forced route must be visible as a maintenance act, not mistaken for a hardware limit. */
    @Test fun `a maintenance override is named among the blockers`() {
        val group = HardwareDiagnostics.audioGroup(
            route = AudioRouteState(reason = AudioRouteReason.MAINTENANCE_FORCE_ANDROID),
            usb = UsbDirectAudioDiagnostics(),
            processing = AudioProcessingRequirements(),
            forceAndroidOverride = true,
            directTransitionOverride = false,
        )
        assertThat(value(group, "DSP blockers")).contains("Maintenance: force Android")
        assertThat(value(group, "Route reason")).isEqualTo("Maintenance: force Android")
    }

    @Test fun `the Direct transition override is named among the blockers`() {
        val group = HardwareDiagnostics.audioGroup(
            route = AudioRouteState(),
            usb = UsbDirectAudioDiagnostics(),
            processing = AudioProcessingRequirements(),
            forceAndroidOverride = false,
            directTransitionOverride = true,
        )
        assertThat(value(group, "DSP blockers")).contains("Maintenance: Direct transition off")
    }

    @Test fun `a Direct failure reports its error rather than an empty row`() {
        val group = HardwareDiagnostics.audioGroup(
            route = AudioRouteState(
                transition = AudioRouteTransition.DIRECT_FAILED_FALLBACK,
                reason = AudioRouteReason.DIRECT_FAILED_FALLBACK,
                directError = "USB DAC rejected or did not lock sample rate 96000 Hz",
            ),
            usb = UsbDirectAudioDiagnostics(),
            processing = AudioProcessingRequirements(),
            forceAndroidOverride = false,
            directTransitionOverride = false,
        )
        assertThat(value(group, "Direct error")).contains("96000 Hz")
        assertThat(rows(group, "Direct error").isUnknown).isFalse()
    }

    @Test fun `DAC capabilities are reported when the sink knows them`() {
        val group = HardwareDiagnostics.audioGroup(
            route = AudioRouteState(),
            usb = UsbDirectAudioDiagnostics(
                sourceSampleRate = 44_100,
                dacBestBitDepth = 32,
                dacSupportedRates = listOf(44_100, 48_000, 96_000, 192_000),
            ),
            processing = AudioProcessingRequirements(),
            forceAndroidOverride = false,
            directTransitionOverride = false,
        )
        assertThat(value(group, "Source rate")).isEqualTo("44100 Hz")
        assertThat(value(group, "DAC best depth")).isEqualTo("32-bit")
        assertThat(value(group, "DAC rates")).contains("192000")
    }

    // ---- Power -----------------------------------------------------------------------------

    @Test fun `an unanswerable battery reading is unknown rather than zero`() {
        val group = HardwareDiagnostics.powerGroup(null, null, null, null)
        assertThat(rows(group, "Battery").isUnknown).isTrue()
        assertThat(value(group, "Battery")).isEqualTo(HardwareDiagnostics.UNKNOWN)
        assertThat(value(group, "Charging")).isEqualTo(HardwareDiagnostics.UNKNOWN)
        assertThat(value(group, "Screen")).isEqualTo(HardwareDiagnostics.UNKNOWN)
    }

    @Test fun `a real power reading is reported as read`() {
        val group = HardwareDiagnostics.powerGroup(
            batteryPercent = 72,
            charging = false,
            pluggedLabel = null,
            screenInteractive = true,
        )
        assertThat(value(group, "Battery")).isEqualTo("72%")
        assertThat(value(group, "Charging")).isEqualTo("no")
        assertThat(value(group, "Screen")).isEqualTo("interactive")
        assertThat(rows(group, "Battery").isUnknown).isFalse()
    }

    /**
     * The HONOR while powering a DAC: host mode means it is the power source and is not charging.
     * Reporting that truthfully matters, because it is the opposite of what "USB is plugged in"
     * would suggest.
     */
    @Test fun `powering a DAC reports not charging`() {
        val group = HardwareDiagnostics.powerGroup(64, charging = false, pluggedLabel = null, screenInteractive = true)
        assertThat(value(group, "Charging")).isEqualTo("no")
        assertThat(value(group, "Power source")).isEqualTo("none")
    }

    // ---- Appliance -------------------------------------------------------------------------

    @Test fun `the appliance group reports the override count and how many are diagnostic`() {
        val group = HardwareDiagnostics.applianceGroup(
            dedicatedMode = true,
            lockTaskState = "LOCKED",
            dapLockScreenEnabled = true,
            maintenanceActive = true,
            overrides = MaintenanceOverrides()
                .applied(MaintenanceOverride.FORCE_ANDROID_AUDIO, active = true)
                .applied(MaintenanceOverride.DAP_LOCK_SCREEN_INTEGRATION, active = true),
        )
        assertThat(value(group, "Active overrides")).isEqualTo("2 (1 diagnostic)")
    }

    @Test fun `no overrides reads as none`() {
        val group = HardwareDiagnostics.applianceGroup(
            dedicatedMode = false,
            lockTaskState = "NONE",
            dapLockScreenEnabled = false,
            maintenanceActive = false,
            overrides = MaintenanceOverrides(),
        )
        assertThat(value(group, "Active overrides")).isEqualTo("none")
    }

    // ---- Device ----------------------------------------------------------------------------

    @Test fun `device identity is reported verbatim`() {
        val group = HardwareDiagnostics.deviceGroup(
            manufacturer = "HONOR",
            model = "LGN-NX3",
            androidRelease = "15",
            sdkInt = 35,
            buildDisplay = "LGN-N33 9.0.0.136(C605E1R201P2)",
            buildIncremental = "9.0.0.136C605E1R201P2",
        )
        assertThat(value(group, "Manufacturer")).isEqualTo("HONOR")
        assertThat(value(group, "Model")).isEqualTo("LGN-NX3")
        assertThat(value(group, "Android")).isEqualTo("15 (API 35)")
        assertThat(value(group, "Build")).contains("9.0.0.136")
    }

    @Test fun `an absent build string is marked unknown`() {
        val group = HardwareDiagnostics.deviceGroup("HONOR", "LGN-NX3", "15", 35, null, null)
        assertThat(rows(group, "Build").isUnknown).isTrue()
    }
}
