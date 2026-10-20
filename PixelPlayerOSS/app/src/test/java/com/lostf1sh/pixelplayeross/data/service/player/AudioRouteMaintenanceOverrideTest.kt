package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Phase 13 — the Maintenance diagnostic overrides are route inputs, and they must be reported
 * under their own reasons. A forced route that reports "no DAC" or "permission required" would
 * make the diagnostic surface lie about the hardware, which is the one thing it exists to prevent.
 */
class AudioRouteMaintenanceOverrideTest {

    private val transparentUsb = AudioRouteFacts(
        usbDacPresent = true,
        usbPermissionGranted = true,
    )

    @Test fun `forcing Android selects Android even with a permitted DAC present`() {
        val decision = AudioRoutePolicy.decide(transparentUsb.copy(maintenanceForceAndroid = true))
        assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.MAINTENANCE_FORCE_ANDROID)
    }

    @Test fun `disabling the Direct transition selects Android with its own reason`() {
        val decision = AudioRoutePolicy.decide(transparentUsb.copy(maintenanceDirectDisabled = true))
        assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.MAINTENANCE_DIRECT_DISABLED)
    }

    @Test fun `clearing the override restores the previous Direct decision`() {
        assertThat(AudioRoutePolicy.decide(transparentUsb.copy(maintenanceForceAndroid = true)).backend)
            .isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(AudioRoutePolicy.decide(transparentUsb).backend)
            .isEqualTo(AudioBackend.USB_DIRECT)
    }

    @Test fun `clearing the transition override restores the previous Direct decision`() {
        assertThat(AudioRoutePolicy.decide(transparentUsb.copy(maintenanceDirectDisabled = true)).backend)
            .isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(AudioRoutePolicy.decide(transparentUsb).backend)
            .isEqualTo(AudioBackend.USB_DIRECT)
    }

    /**
     * A genuine blocker must still be named as itself. Reporting a maintenance override when the
     * user's own equalizer is the reason would hide a real cause behind a diagnostic switch.
     */
    @Test fun `a listening choice outranks a maintenance override in the reported reason`() {
        // The requirement used here must be one that genuinely still blocks Direct. EQ no longer
        // does — it selects processed Direct — so asserting on it would have tested nothing while
        // appearing to test the precedence.
        val decision = AudioRoutePolicy.decide(
            transparentUsb.copy(
                processing = AudioProcessingRequirements(crossfade = true),
                maintenanceForceAndroid = true,
            )
        )
        assertThat(decision.reason).isEqualTo(AudioRouteReason.CROSSFADE_REQUIRES_ANDROID)
    }

    /**
     * The override sits before the capability gates on purpose: with no DAC attached, forcing
     * Android is still the honest description of why the route is Android, not "no DAC".
     */
    @Test fun `a maintenance override outranks a missing DAC in the reported reason`() {
        val decision = AudioRoutePolicy.decide(
            transparentUsb.copy(usbDacPresent = false, maintenanceForceAndroid = true)
        )
        assertThat(decision.reason).isEqualTo(AudioRouteReason.MAINTENANCE_FORCE_ANDROID)
    }

    @Test fun `a maintenance override outranks missing permission in the reported reason`() {
        val decision = AudioRoutePolicy.decide(
            transparentUsb.copy(usbPermissionGranted = false, maintenanceDirectDisabled = true)
        )
        assertThat(decision.reason).isEqualTo(AudioRouteReason.MAINTENANCE_DIRECT_DISABLED)
    }

    @Test fun `overrides default to inactive`() {
        val facts = AudioRouteFacts(usbDacPresent = true, usbPermissionGranted = true)
        assertThat(facts.maintenanceForceAndroid).isFalse()
        assertThat(facts.maintenanceDirectDisabled).isFalse()
    }
}
