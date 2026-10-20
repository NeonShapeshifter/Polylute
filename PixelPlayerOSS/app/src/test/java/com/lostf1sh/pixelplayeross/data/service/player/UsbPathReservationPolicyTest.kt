package com.lostf1sh.pixelplayeross.data.service.player

import com.decent.usbaudio.media3.UsbDirectSinkState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Phase 13 — the exclusive-path reservation rule.
 *
 * The defect this pins: `USB_DIRECT_AVAILABLE` is published as soon as a permitted DAC is
 * enumerated, and `TransitionController` used that flag alone to cancel overlap transitions. On a
 * device whose listening state (EQ, ReplayGain, speed, crossfade) pins the route to Android,
 * Direct can never activate — so the suppression was pure loss, and attaching a DAC silently
 * disabled crossfade and gapless playback for the rest of the session.
 */
class UsbPathReservationPolicyTest {

    private fun diagnostics(state: UsbDirectSinkState) =
        UsbDirectAudioDiagnostics(state = state, deviceName = "SNOWSKY Melody")

    @Test fun `a permitted DAC alone does not reserve the path when the route is Android`() {
        val usb = diagnostics(UsbDirectSinkState.USB_DIRECT_AVAILABLE)
        assertThat(usb.reservesExclusiveUsbPath).isTrue()
        assertThat(usb.reservesExclusiveUsbPathFor(AudioBackend.ANDROID_AUDIO)).isFalse()
    }

    /**
     * The exact crossfade scenario: crossfade is enabled, so the policy pins Android, but a DAC is
     * attached. Overlap must be allowed — it is the whole point of the setting.
     */
    @Test fun `crossfade with an attached DAC keeps overlap enabled`() {
        val crossfadePinsAndroid = AudioRouteFacts(
            usbDacPresent = true,
            usbPermissionGranted = true,
            processing = AudioProcessingRequirements(crossfade = true),
        )
        val decision = AudioRoutePolicy.decide(crossfadePinsAndroid)
        assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)

        val usb = diagnostics(UsbDirectSinkState.USB_DIRECT_AVAILABLE)
        assertThat(usb.reservesExclusiveUsbPathFor(decision.backend)).isFalse()
    }

    @Test fun `the same holds for every other listening choice that pins Android`() {
        // EQ, ReplayGain and digital volume are no longer in this list: they keep the Direct
        // transport and are carried by its processed mode, so they must *reserve* the path rather
        // than release it. Leaving them here would have asserted the opposite of the new contract
        // while still passing, because the assertion only ever checked the released case.
        val choices = listOf(
            AudioProcessingRequirements(nonDefaultSpeed = true),
            AudioProcessingRequirements(androidSessionEffect = true),
        )
        val usb = diagnostics(UsbDirectSinkState.USB_DIRECT_AVAILABLE)
        choices.forEach { processing ->
            val decision = AudioRoutePolicy.decide(
                AudioRouteFacts(
                    usbDacPresent = true,
                    usbPermissionGranted = true,
                    processing = processing,
                )
            )
            assertThat(decision.backend).isEqualTo(AudioBackend.ANDROID_AUDIO)
            assertThat(usb.reservesExclusiveUsbPathFor(decision.backend)).isFalse()
        }
    }

    @Test fun `processed Direct still reserves the exclusive path`() {
        // A Direct stream with the equalizer running holds the same exclusive claim on the DAC as a
        // pristine one, so overlap transitions must still stand down for it. Dropping the
        // listening choices from the pinning list must not be read as "processed Direct is any
        // less exclusive than pristine".
        val carried = listOf(
            AudioProcessingRequirements(graphicEq = true),
            AudioProcessingRequirements(replayGain = true),
            AudioProcessingRequirements(digitalVolume = true),
        )
        val usb = diagnostics(UsbDirectSinkState.USB_DIRECT_AVAILABLE)
        carried.forEach { processing ->
            val decision = AudioRoutePolicy.decide(
                AudioRouteFacts(
                    usbDacPresent = true,
                    usbPermissionGranted = true,
                    processing = processing,
                )
            )
            assertThat(decision.backend).isEqualTo(AudioBackend.USB_DIRECT)
            assertThat(usb.reservesExclusiveUsbPathFor(decision.backend)).isTrue()
        }
    }

    @Test fun `a transparent listening state does reserve the path`() {
        val decision = AudioRoutePolicy.decide(
            AudioRouteFacts(usbDacPresent = true, usbPermissionGranted = true)
        )
        assertThat(decision.backend).isEqualTo(AudioBackend.USB_DIRECT)
        assertThat(diagnostics(UsbDirectSinkState.USB_DIRECT_AVAILABLE).reservesExclusiveUsbPathFor(decision.backend))
            .isTrue()
    }

    @Test fun `an active Direct stream reserves the path`() {
        assertThat(diagnostics(UsbDirectSinkState.USB_DIRECT_ACTIVE).reservesExclusiveUsbPathFor(AudioBackend.USB_DIRECT))
            .isTrue()
    }

    @Test fun `a DAC that is merely present without permission never reserves the path`() {
        val usb = diagnostics(UsbDirectSinkState.USB_PERMISSION_REQUIRED)
        assertThat(usb.reservesExclusiveUsbPath).isFalse()
        assertThat(usb.reservesExclusiveUsbPathFor(AudioBackend.USB_DIRECT)).isFalse()
    }

    @Test fun `Android audio with no DAC never reserves the path`() {
        val usb = diagnostics(UsbDirectSinkState.ANDROID_AUDIO)
        assertThat(usb.reservesExclusiveUsbPathFor(AudioBackend.ANDROID_AUDIO)).isFalse()
        assertThat(usb.reservesExclusiveUsbPathFor(AudioBackend.USB_DIRECT)).isFalse()
    }

    @Test fun `a failed Direct negotiation does not reserve the path`() {
        val usb = diagnostics(UsbDirectSinkState.USB_DIRECT_ERROR)
        assertThat(usb.reservesExclusiveUsbPathFor(AudioBackend.USB_DIRECT)).isFalse()
    }
}
