package com.lostf1sh.pixelplayeross.data.usb

import androidx.media3.exoplayer.LoadControl
import com.decent.usbaudio.media3.UsbAudioSink
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * AUD-130 (measured with B7): ~2.2 s of silence between two tracks of the same rate. The LoadControl
 * kept Media3 from loading anything while the native engine played, so at the natural end Media3
 * had to load and buffer the next item from scratch (BUFFERING 1.8 s) before its transition. In the
 * last seconds of the engine's track loading is allowed again, so the next item is ready in time.
 */
class NativeEnginePreloadTest {

    private val delegate = mockk<LoadControl>(relaxed = true).also {
        @Suppress("DEPRECATION")
        every { it.shouldContinueLoading(any<Long>(), any(), any()) } returns true
    }

    @Suppress("DEPRECATION")
    private fun LoadControl.loads() = shouldContinueLoading(0L, 0L, 1f)

    @Test
    fun `an active engine blocks loading outside the preload window`() {
        val control = UsbAudioSink.wrapLoadControl(delegate, isEnginePreloadWindow = { false }, isEngineActive = { true })
        assertThat(control.loads()).isFalse()
    }

    @Test
    fun `in the last seconds of the engine's track the delegate may load the next item`() {
        val control = UsbAudioSink.wrapLoadControl(delegate, isEnginePreloadWindow = { true }, isEngineActive = { true })
        assertThat(control.loads()).isTrue()
    }
}
