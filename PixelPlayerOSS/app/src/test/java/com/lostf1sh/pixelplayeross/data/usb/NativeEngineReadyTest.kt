package com.lostf1sh.pixelplayeross.data.usb

import androidx.media3.exoplayer.LoadControl
import com.decent.usbaudio.media3.UsbAudioSink
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * AUD-114 (measured on the HONOR): after NEXT in PRISTINE the native engine was playing at +0.31 s,
 * but Media3 only reached READY at +0.875 s — the bar did not move until then. The engine is created
 * paused with LoadControl briefly *unblocked* (so one buffer can arrive), and during that window
 * "start playback" fell back to the delegate's 2.5 s buffering rule for audio Media3 does not produce.
 *
 * Once the engine for the current item exists, playback may start without Media3's buffer.
 */
class NativeEngineReadyTest {

    private val delegate = mockk<LoadControl>(relaxed = true).also {
        @Suppress("DEPRECATION")
        every { it.shouldStartPlayback(any<Long>(), any(), any(), any()) } returns false
    }

    @Suppress("DEPRECATION")
    private fun LoadControl.startsWithEmptyBuffer() = shouldStartPlayback(0L, 1f, false, 0L)

    @Test
    fun `an engine ready for the current item starts playback while loading is briefly unblocked`() {
        val control = UsbAudioSink.wrapLoadControl(delegate, isEngineReady = { true }, isEngineActive = { false })
        assertThat(control.startsWithEmptyBuffer()).isTrue()
    }

    @Test
    fun `without an engine the delegate's buffering rule still applies`() {
        val control = UsbAudioSink.wrapLoadControl(delegate, isEngineReady = { false }, isEngineActive = { false })
        assertThat(control.startsWithEmptyBuffer()).isFalse()
    }

    @Test
    fun `an active engine starts playback, as before`() {
        val control = UsbAudioSink.wrapLoadControl(delegate, isEngineActive = { true })
        assertThat(control.startsWithEmptyBuffer()).isTrue()
    }
}
