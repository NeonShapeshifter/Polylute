package com.lostf1sh.pixelplayeross.data.usb

import android.content.Context
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
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
 * B8b-2: during `directFailureHold` the delegate must stay silent (AUD-004), but since B8a every
 * `setVolume` in the non-Direct branch un-muted it (`unmuteDelegateIfNeeded` always did
 * `super.setVolume(pendingVolume)`): a ReplayGain volume at a track change or a FadingPlayer ramp in
 * that window let audio out of whatever output Android has.
 *
 * Behavioural: the sink is built with a mocked delegate and the hold is set the way a failed claim
 * sets it (the claim itself needs usbfs, which a unit test does not have), then what the delegate
 * receives is asserted.
 */
@OptIn(UnstableApi::class)
class DirectFailureHoldVolumeTest {

    private fun sink(delegate: DefaultAudioSink, directEnabled: Boolean = true): UsbAudioSink {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.USB_SERVICE) } returns mockk<UsbManager>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
        return UsbAudioSink(delegate, context, UsbAudioSinkConfig(directEnabled = directEnabled))
    }

    private fun setHold(sink: UsbAudioSink, value: Boolean) {
        val field = UsbAudioSink::class.java.getDeclaredField("directFailureHold")
        field.isAccessible = true
        field.setBoolean(sink, value)
    }

    @Test
    fun `a volume written while the failure hold is active does not un-mute the delegate`() {
        val delegate = mockk<DefaultAudioSink>(relaxed = true)
        val sink = sink(delegate)
        setHold(sink, true)

        sink.setVolume(0.6f)     // ReplayGain at a track change
        sink.setVolume(0.3f)     // a FadingPlayer ramp step

        verify(exactly = 0) { delegate.setVolume(any()) }
    }

    @Test
    fun `when the hold ends the delegate gets the latest volume written during it`() {
        val delegate = mockk<DefaultAudioSink>(relaxed = true)
        val sink = sink(delegate)
        setHold(sink, true)
        sink.setVolume(0.6f)
        sink.setVolume(0.3f)
        verify(exactly = 0) { delegate.setVolume(any()) }

        // The hold's own exit: the next configure clears it and, with no Direct claim to carry the
        // audio (no DAC here), leaves the delegate audible at the volume it was last given.
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(44_100)
            .setChannelCount(2)
            .build()
        sink.configure(format, 0, null)

        verify(exactly = 1) { delegate.setVolume(0.3f) }
        verify(exactly = 0) { delegate.setVolume(0.6f) }
    }

    @Test
    fun `without a hold every volume still reaches the delegate while it is the output`() {
        val delegate = mockk<DefaultAudioSink>(relaxed = true)
        val sink = sink(delegate)

        sink.setVolume(0.6f)
        sink.setVolume(0.3f)

        verify(exactly = 1) { delegate.setVolume(0.6f) }
        verify(exactly = 1) { delegate.setVolume(0.3f) }
        assertThat(sink.isUsbDirectActive).isFalse()
    }
}
