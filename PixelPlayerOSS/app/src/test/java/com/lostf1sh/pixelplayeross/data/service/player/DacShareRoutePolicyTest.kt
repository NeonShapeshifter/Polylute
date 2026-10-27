package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** B39-A7: the route policy's half of "Purity never releases", and what a released DAC does to the route. */
class DacShareRoutePolicyTest {
    private val resonance = AudioProcessingRequirements(graphicEq = true)
    private val purity = AudioProcessingRequirements()

    private fun facts(
        processing: AudioProcessingRequirements,
        released: Boolean,
        crossfade: Boolean = false,
        dac: Boolean = true,
    ) = AudioRouteFacts(
        usbDacPresent = dac,
        usbPermissionGranted = true,
        processing = processing.copy(crossfade = crossfade),
        dacReleasedForSharing = released,
        androidUsbRouteAvailable = true,
    )

    @Test
    fun `a released DAC in Resonance is the Android route, for a stated reason`() {
        val decision = AudioRoutePolicy.decide(facts(resonance, released = true))
        assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.DAC_SHARED_WITH_OTHER_APPS)
    }

    @Test
    fun `a DAC that is not released is Direct, processed, as before`() {
        val decision = AudioRoutePolicy.decide(facts(resonance, released = false))
        assertThat(decision.backend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(decision.reason).isEqualTo(AudioRouteReason.DIRECT_PROCESSED)
    }

    @Test
    fun `Purity never releases because the fact is ignored when the signal needs no DSP`() {
        val decision = AudioRoutePolicy.decide(facts(purity, released = true))
        assertThat(decision.backend).isEqualTo(AudioBackend.USB_DIRECT)
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
    fun `the default is a DAC held, so nothing that never heard of sharing changes`() {
        assertThat(AudioRouteFacts(usbDacPresent = true, usbPermissionGranted = true).dacReleasedForSharing).isFalse()
    }
}
