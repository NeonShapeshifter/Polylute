package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.decent.usbaudio.media3.DirectSignalMode
import org.junit.jupiter.api.Test

/**
 * B39-A7: the route policy's half of "Purity never releases", and what a released DAC does to the route.
 *
 * B68: the half is now [AudioRouteFacts.dacShareAllowed] ([DacSharePolicy]), not "the app's DSP is in the signal": Purity off with nothing
 * to process (the owner's case) is shared like any Resonance, and Purity itself is shared in app mode only.
 */
class DacShareRoutePolicyTest {
    private val resonance = AudioProcessingRequirements(graphicEq = true)
    private val nothingToProcess = AudioProcessingRequirements()

    private fun facts(
        processing: AudioProcessingRequirements,
        released: Boolean,
        allowed: Boolean = true,
        crossfade: Boolean = false,
        dac: Boolean = true,
    ) = AudioRouteFacts(
        usbDacPresent = dac,
        usbPermissionGranted = true,
        processing = processing.copy(crossfade = crossfade),
        dacReleasedForSharing = released,
        dacShareAllowed = allowed,
        androidUsbRouteAvailable = true,
    )

    @Test
    fun `a released DAC in Resonance is the Android route, for a stated reason`() {
        val decision = AudioRoutePolicy.decide(facts(resonance, released = true))
        assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.DAC_SHARED_WITH_OTHER_APPS)
    }

    @Test
    fun `B68 - released and allowed with NO processing is the Android route, shared (the owner's case, and Purity in app mode)`() {
        val decision = AudioRoutePolicy.decide(facts(nothingToProcess, released = true, allowed = true))
        assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.DAC_SHARED_WITH_OTHER_APPS)
    }

    @Test
    fun `B68 - released but not allowed (Purity at a DAP level) stays DIRECT_PRISTINE - the fact is ignored`() {
        val decision = AudioRoutePolicy.decide(facts(nothingToProcess, released = true, allowed = false))
        assertThat(decision.backend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.DIRECT_PRISTINE)
        assertThat(decision.signalMode).isEqualTo(DirectSignalMode.PRISTINE)
    }

    @Test
    fun `B68 - not allowed with processing stays Direct, processed`() {
        val decision = AudioRoutePolicy.decide(facts(resonance, released = true, allowed = false))
        assertThat(decision.reason).isEqualTo(AudioRouteReason.DIRECT_PROCESSED)
    }

    @Test
    fun `B68 - the facts the engine builds from the policy - every mode, Purity in force and released`() {
        for (mode in listOf(null) + EffectiveProductMode.entries) {
            val allowed = DacSharePolicy.shareAllowed(switchOn = true, purityInForce = true, effectiveMode = mode)
            // Purity's effective requirements are empty (PristineModePolicy holds every contributor off)
            val decision = AudioRoutePolicy.decide(facts(nothingToProcess, released = true, allowed = allowed))
            val expected = if (mode == EffectiveProductMode.APP) AudioRouteReason.DAC_SHARED_WITH_OTHER_APPS else AudioRouteReason.DIRECT_PRISTINE
            assertThat(decision.reason).isEqualTo(expected)
        }
    }

    @Test
    fun `a DAC that is not released is Direct, processed, as before`() {
        val decision = AudioRoutePolicy.decide(facts(resonance, released = false))
        assertThat(decision.backend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.DIRECT_PROCESSED)
    }

    @Test
    fun `a DAC that is not released is DIRECT_PRISTINE with nothing to process, whatever the policy says`() {
        val decision = AudioRoutePolicy.decide(facts(nothingToProcess, released = false, allowed = true))
        assertThat(decision.reason).isEqualTo(AudioRouteReason.DIRECT_PRISTINE)
    }

    @Test
    fun `an Android requirement keeps its own reason ahead of the sharing one`() {
        val decision = AudioRoutePolicy.decide(facts(resonance, released = true, crossfade = true))
        assertThat(decision.reason).isEqualTo(AudioRouteReason.CROSSFADE_REQUIRES_ANDROID)
    }

    @Test
    fun `with no DAC the reason is the missing DAC, not the sharing`() {
        val decision = AudioRoutePolicy.decide(facts(resonance, released = true, dac = false))
        assertThat(decision.reason).isEqualTo(AudioRouteReason.NO_USB_DAC)
    }

    @Test
    fun `the default is a DAC held and sharing not allowed, so nothing that never heard of sharing changes`() {
        val defaults = AudioRouteFacts(usbDacPresent = true, usbPermissionGranted = true)
        assertThat(defaults.dacReleasedForSharing).isFalse()
        assertThat(defaults.dacShareAllowed).isFalse()
    }
}
