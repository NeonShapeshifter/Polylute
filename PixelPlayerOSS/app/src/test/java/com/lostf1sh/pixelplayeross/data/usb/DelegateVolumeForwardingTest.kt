package com.lostf1sh.pixelplayeross.data.usb

import android.content.Context
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.decent.usbaudio.media3.UsbAudioSink
import com.decent.usbaudio.media3.UsbAudioSinkConfig
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * AUD-142: with the delegate audible — the Android route, or Direct not carrying the PCM — every
 * `player.volume` write must reach it.
 *
 * The sink used to hand the delegate a volume only on the transition out of mute, so in the steady
 * state `setVolume` discarded the value: ReplayGain per track, the play/pause ramps of
 * `FadingPlayer`, the user's digital gain and the profile compensation of AUD-025 all write
 * `player.volume` and none of them was heard.
 *
 * Behavioural rather than source-level: the sink is constructed here with a mocked delegate, so the
 * assertion is on what the delegate receives, not on the shape of the code that sends it.
 */
@OptIn(UnstableApi::class)
class DelegateVolumeForwardingTest {

    private companion object {
        /** The Android route: the sink may not carry PCM itself, so the delegate is the output. */
        const val DELEGATE_AUDIBLE = false
    }

    private fun sink(delegate: DefaultAudioSink): UsbAudioSink {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.USB_SERVICE) } returns mockk<UsbManager>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
        return UsbAudioSink(delegate, context, UsbAudioSinkConfig(directEnabled = DELEGATE_AUDIBLE))
    }

    @Test
    fun `every volume reaches the delegate while it is the output, not just the one that ends a mute`() {
        val delegate = mockk<DefaultAudioSink>(relaxed = true)
        val sink = sink(delegate)

        sink.setVolume(0.3f)
        sink.setVolume(0.5f)

        verify(exactly = 1) { delegate.setVolume(0.3f) }
        verify(exactly = 1) { delegate.setVolume(0.5f) }
    }

    @Test
    fun `a volume written before any mute is the one the delegate holds`() {
        val delegate = mockk<DefaultAudioSink>(relaxed = true)
        val sink = sink(delegate)

        sink.setVolume(0.42f)

        verify(exactly = 1) { delegate.setVolume(0.42f) }
        // The delegate is not silenced on the way: muting it here would mute the Android route.
        verify(exactly = 0) { delegate.setVolume(0f) }
    }

    @Test
    fun `the sink's own view of the delegate volume follows the last write`() {
        val delegate = mockk<DefaultAudioSink>(relaxed = true)
        val sink = sink(delegate)

        sink.setVolume(0.25f)

        assertThat(sink.isUsbDirectActive).isFalse()
        verify { delegate.setVolume(0.25f) }
    }
}
