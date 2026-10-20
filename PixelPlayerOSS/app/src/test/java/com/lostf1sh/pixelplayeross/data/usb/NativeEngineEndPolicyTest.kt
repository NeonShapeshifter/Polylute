package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.NativeEngineExit
import com.decent.usbaudio.media3.NativeEngineEndPolicy
import com.decent.usbaudio.media3.NativeEngineEndPolicy.Action
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-013 / AUD-056: the decode thread stopped the same way on EOS, on a failed USB submit and on a
 * read timeout, and the sink resolved every stop as a natural end — skipping to the next track on a
 * detach or a slow SD card.
 */
class NativeEngineEndPolicyTest {

    @Test
    fun `only end of stream advances the queue or reports ended`() {
        assertThat(NativeEngineEndPolicy.resolve(NativeEngineExit.END_OF_STREAM)).isEqualTo(Action.NATURAL_END)
        assertThat(NativeEngineEndPolicy.reportsEnded(NativeEngineExit.END_OF_STREAM)).isTrue()
        for (exit in listOf(NativeEngineExit.USB_ERROR, NativeEngineExit.DECODE_ERROR, NativeEngineExit.STOPPED)) {
            assertThat(NativeEngineEndPolicy.reportsEnded(exit)).isFalse()
        }
    }

    @Test
    fun `a USB failure fails Direct instead of skipping`() {
        assertThat(NativeEngineEndPolicy.resolve(NativeEngineExit.USB_ERROR)).isEqualTo(Action.FAIL_DIRECT)
    }

    @Test
    fun `a decode error or read timeout pauses where it stopped`() {
        assertThat(NativeEngineEndPolicy.resolve(NativeEngineExit.DECODE_ERROR)).isEqualTo(Action.PAUSE_AT_POSITION)
    }

    @Test
    fun `running or stopped on request needs nothing`() {
        assertThat(NativeEngineEndPolicy.resolve(NativeEngineExit.RUNNING)).isEqualTo(Action.NONE)
        assertThat(NativeEngineEndPolicy.resolve(NativeEngineExit.STOPPED)).isEqualTo(Action.NONE)
        assertThat(NativeEngineEndPolicy.reportsEnded(NativeEngineExit.RUNNING)).isFalse()
    }

    @Test
    fun `native codes map in order, unknown codes are errors not ends`() {
        assertThat(NativeEngineExit.fromNative(1)).isEqualTo(NativeEngineExit.END_OF_STREAM)
        assertThat(NativeEngineExit.fromNative(3)).isEqualTo(NativeEngineExit.USB_ERROR)
        assertThat(NativeEngineExit.fromNative(99)).isEqualTo(NativeEngineExit.DECODE_ERROR)
    }
}
