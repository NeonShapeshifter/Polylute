package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import org.junit.jupiter.api.Test

/** B29-L4: the mode Details states is read from the route model, and only while USB Direct is carrying the stream. */
class DirectOutputModeTest {

    private fun direct(mode: DirectSignalMode) = AudioRouteState(
        desiredBackend = AudioBackend.USB_DIRECT,
        actualBackend = AudioBackend.USB_DIRECT,
        transition = AudioRouteTransition.DIRECT_ACTIVE,
        signalMode = mode,
    )

    @Test
    fun `Direct with the untouched signal is Purity`() {
        assertThat(resolveDirectOutputMode(direct(DirectSignalMode.PRISTINE))).isEqualTo(DirectOutputMode.PURITY)
    }

    @Test
    fun `Direct with the application's DSP is Resonance`() {
        assertThat(resolveDirectOutputMode(direct(DirectSignalMode.PROCESSED))).isEqualTo(DirectOutputMode.RESONANCE)
    }

    @Test
    fun `the Android route has no mode, whatever the signal mode field was left at`() {
        // the field defaults to PRISTINE on the Android route: it must not turn into "Purity"
        assertThat(resolveDirectOutputMode(AudioRouteState())).isNull()
        assertThat(resolveDirectOutputMode(AudioRouteState(signalMode = DirectSignalMode.PROCESSED))).isNull()
    }

    @Test
    fun `a Direct claim that failed and fell back has no mode`() {
        val failed = AudioRouteState(
            desiredBackend = AudioBackend.USB_DIRECT,
            actualBackend = AudioBackend.ANDROID_AUDIO,
            transition = AudioRouteTransition.DIRECT_FAILED_FALLBACK,
        )
        assertThat(resolveDirectOutputMode(failed)).isNull()
    }

    @Test
    fun `Direct that is wanted but not yet carrying the stream has no mode`() {
        val starting = AudioRouteState(desiredBackend = AudioBackend.USB_DIRECT, actualBackend = AudioBackend.USB_DIRECT, transition = AudioRouteTransition.ANDROID_ACTIVE)
        assertThat(resolveDirectOutputMode(starting)).isNull()
    }
}
