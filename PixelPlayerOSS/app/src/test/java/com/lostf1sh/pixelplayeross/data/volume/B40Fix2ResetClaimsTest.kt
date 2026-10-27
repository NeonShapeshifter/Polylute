package com.lostf1sh.pixelplayeross.data.volume

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import androidx.test.core.app.ApplicationProvider
import com.decent.usbaudio.ResetLevelRestorer
import com.decent.usbaudio.UacVolumeTopology
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B40-fix2 item 1, from the outside review of B40-fix1 (`ReviewB40Fix1ResetTest`, ported as it came: it failed on the fix1 code, see
 * `B40-fix2-evidence/before/`): the real `UsbAudioDevice.releaseToAndroid()` over synthetic USB ports (a mocked connection and interfaces;
 * the bus reset is a seam that answers, so no JNI and no physical USB is touched). The fields that say what Polylute holds must be what
 * was claimed, and nothing else: a claim that failed leaves no stale field behind, and the next open or hand-back claims for real.
 *
 * Every call that matters is recorded in order, so "streaming first, then control" and "nothing released after the reset" are read off it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B40Fix2ResetClaimsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val calls = mutableListOf<String>()

    private val conn = mockk<UsbDeviceConnection>(relaxed = true)
    private val control = mockk<UsbInterface>(relaxed = true)
    private val stream = mockk<UsbInterface>(relaxed = true)
    private val usb = mockk<UsbDevice>(relaxed = true)
    private lateinit var device: UsbAudioDevice

    /** What each claim answers, in order, per interface; past the end it answers false. */
    private val streamingClaims = ArrayDeque<Boolean>()
    private val controlClaims = ArrayDeque<Boolean>()
    private var told: ResetLevelRestorer.Outcome? = null

    private fun put(name: String, value: Any?) { UsbAudioDevice::class.java.getDeclaredField(name).apply { isAccessible = true }.set(device, value) }
    private fun get(name: String): Any? = UsbAudioDevice::class.java.getDeclaredField(name).apply { isAccessible = true }.get(device)

    @Before
    fun setUp() {
        val ctor = UsbAudioDevice::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        device = ctor.newInstance(context)
        every { control.id } returns 0
        every { control.interfaceClass } returns 1
        every { control.interfaceSubclass } returns 1
        every { stream.id } returns 1
        every { stream.interfaceClass } returns 1
        every { stream.interfaceSubclass } returns 2
        every { conn.fileDescriptor } returns 999
        every { conn.claimInterface(control, true) } answers { (controlClaims.removeFirstOrNull() ?: false).also { calls += "claim control=$it" } }
        every { conn.claimInterface(stream, true) } answers { (streamingClaims.removeFirstOrNull() ?: false).also { calls += "claim streaming=$it" } }
        every { conn.releaseInterface(control) } answers { calls += "release control"; true }
        every { conn.releaseInterface(stream) } answers { calls += "release streaming"; true }
        // the level cannot be read: a restore that is never confirmed
        every { conn.controlTransfer(any(), any(), any(), any(), any(), any(), any()) } answers { calls += "level transfer"; -1 }
        every { conn.close() } answers { calls += "close"; Unit }
        every { usb.interfaceCount } returns 2
        every { usb.getInterface(0) } returns control
        every { usb.getInterface(1) } returns stream
        put("connection", conn)
        put("currentDevice", usb)
        put("selectedStreamingInterfaceNumber", 1)
        put("volumeTopology", UacVolumeTopology(1, listOf(1)))
        put("cachedDeviceInfo", mockk<UsbAudioDeviceInfo>(relaxed = true))
        // the bus reset answers (the kernel has rebound snd-usb-audio to every interface by the time it returns)
        put("busReset", { _: Int -> calls += "reset"; 0 })
        device.resetLevelListener = { told = it }
    }

    /** The device as the hand-back finds it: both interfaces claimed by Polylute, the kernel driver gone. */
    private fun claimedByPolylute() {
        put("claimedControlInterface", control)
        put("claimedInterface", stream)
    }

    private fun afterReset() = calls.drop(calls.indexOf("reset") + 1)

    // ── the review's case ────────────────────────────────────────────────────────────────────────

    @Test
    fun claims_that_failed_after_reset_are_retried_on_the_next_recovery() {
        // The state a hand-back leaves when two claims failed: the fields hold nothing (they used to hold both interfaces, "claimed" by
        // assignment), and the level is owed. Nothing is held, so the next recovery has to claim both, for real, streaming first.
        put("claimedControlInterface", null)
        put("claimedInterface", null)
        put("levelOwedAfterReset", true)
        device.releaseToAndroid()
        verify(exactly = 1) { conn.claimInterface(control, true) }
        verify(exactly = 1) { conn.claimInterface(stream, true) }
        assertThat(calls.indexOf("claim streaming=false")).isLessThan(calls.indexOf("claim control=false"))
        // neither claim took: it is handed over and said so, not held; the debt goes with the closed device
        assertThat(told).isEqualTo(ResetLevelRestorer.Outcome.CONTROL_NOT_HELD)
        assertThat(get("claimedInterface")).isNull()
        assertThat(get("claimedControlInterface")).isNull()
    }

    // ── what the hand-back records and does, claim by claim ──────────────────────────────────────

    @Test
    fun both_claims_take_and_the_level_cannot_be_confirmed_so_polylute_keeps_both_and_records_exactly_those() {
        claimedByPolylute()
        streamingClaims += true
        controlClaims += true
        device.releaseToAndroid()
        // streaming first, then control, straight after the reset, and nothing is let go of afterwards
        assertThat(afterReset().take(2)).containsExactly("claim streaming=true", "claim control=true").inOrder()
        assertThat(afterReset().filter { it.startsWith("release") || it == "close" }).isEmpty()
        assertThat(told).isEqualTo(ResetLevelRestorer.Outcome.UNCONFIRMED)
        assertThat(get("claimedInterface")).isSameInstanceAs(stream)
        assertThat(get("claimedControlInterface")).isSameInstanceAs(control)
        assertThat(get("levelOwedAfterReset")).isEqualTo(true)
        assertThat(get("connection")).isSameInstanceAs(conn)
    }

    @Test
    fun the_control_claim_fails_twice_so_the_dac_is_not_kept_the_fields_are_empty_and_the_next_open_claims_for_real() {
        claimedByPolylute()
        streamingClaims += true
        // control: false, false (the retry)
        device.releaseToAndroid()
        assertThat(afterReset().take(3)).containsExactly("claim streaming=true", "claim control=false", "claim control=false").inOrder()
        assertThat(told).isEqualTo(ResetLevelRestorer.Outcome.CONTROL_NOT_HELD)
        assertThat(told!!.dacWithAndroid).isTrue()
        assertThat(told!!.deviceKept).isFalse()
        // what was held (streaming) went back; nothing else was released; the device is closed and nothing is recorded as held
        assertThat(afterReset().filter { it.startsWith("release") }).containsExactly("release streaming")
        assertThat(get("claimedInterface")).isNull()
        assertThat(get("claimedControlInterface")).isNull()
        assertThat(get("levelOwedAfterReset")).isEqualTo(false)
        assertThat(get("connection")).isNull()
    }

    @Test
    fun the_streaming_claim_fails_twice_so_the_outcome_says_the_dac_is_with_android_and_the_retry_is_bounded() {
        claimedByPolylute()
        controlClaims += true
        // streaming: false, false (the retry)
        device.releaseToAndroid()
        val claims = afterReset().filter { it.startsWith("claim") }
        assertThat(claims).containsExactly("claim streaming=false", "claim control=true", "claim streaming=false").inOrder()
        assertThat(told).isEqualTo(ResetLevelRestorer.Outcome.STREAMING_NOT_HELD)
        assertThat(told!!.levelConfirmed).isFalse()
        assertThat(told!!.dacWithAndroid).isTrue()
        // control, which was held, is let go of (streaming never was); the level was still tried through it
        assertThat(afterReset().filter { it.startsWith("release") }).containsExactly("release control")
        assertThat(afterReset()).contains("level transfer")
        assertThat(get("claimedInterface")).isNull()
        assertThat(get("claimedControlInterface")).isNull()
        assertThat(get("connection")).isNull()
    }

    @Test
    fun a_streaming_claim_that_takes_on_the_second_try_is_held_and_recorded() {
        claimedByPolylute()
        streamingClaims += listOf(false, true)
        controlClaims += true
        device.releaseToAndroid()
        assertThat(afterReset().filter { it.startsWith("claim") })
            .containsExactly("claim streaming=false", "claim control=true", "claim streaming=true").inOrder()
        assertThat(told).isEqualTo(ResetLevelRestorer.Outcome.UNCONFIRMED)
        assertThat(get("claimedInterface")).isSameInstanceAs(stream)
        assertThat(get("claimedControlInterface")).isSameInstanceAs(control)
    }

    @Test
    fun neither_claim_takes_so_nothing_is_released_nothing_is_recorded_and_the_dac_is_with_android() {
        claimedByPolylute()
        device.releaseToAndroid()
        assertThat(afterReset().filter { it.startsWith("claim") }).hasSize(4)
        assertThat(afterReset().filter { it.startsWith("release") }).isEmpty()
        assertThat(told).isEqualTo(ResetLevelRestorer.Outcome.NOT_HELD)
        assertThat(get("claimedInterface")).isNull()
        assertThat(get("claimedControlInterface")).isNull()
        assertThat(get("connection")).isNull()
    }

    // ── after a kept DAC: the next recovery claims what is missing, and close lets go of what is held ──

    @Test
    fun a_kept_dac_whose_level_still_cannot_be_confirmed_stays_held_and_a_recovery_with_a_lost_claim_claims_it_again() {
        claimedByPolylute()
        streamingClaims += true
        controlClaims += true
        device.releaseToAndroid()                    // kept: both held, owed
        calls.clear()
        // the next recovery: both are held, so nothing is claimed again, the level is tried and still not confirmed
        device.releaseToAndroid()
        assertThat(calls.filter { it.startsWith("claim") }).isEmpty()
        assertThat(calls.filter { it.startsWith("release") }).isEmpty()
        assertThat(told).isEqualTo(ResetLevelRestorer.Outcome.UNCONFIRMED)
        // one claim is lost (a field is empty): it is claimed for real, streaming first; if it takes, the DAC is held again
        put("claimedInterface", null)
        streamingClaims += true
        calls.clear()
        device.releaseToAndroid()
        assertThat(calls.filter { it.startsWith("claim") }).containsExactly("claim streaming=true")
        assertThat(get("claimedInterface")).isSameInstanceAs(stream)
    }

    @Test
    fun a_recovery_that_cannot_claim_what_is_missing_hands_the_dac_over_and_says_so_instead_of_holding_half() {
        put("claimedControlInterface", control)
        put("claimedInterface", null)
        put("levelOwedAfterReset", true)
        // streaming cannot be claimed again (it answers false)
        device.releaseToAndroid()
        assertThat(told).isEqualTo(ResetLevelRestorer.Outcome.STREAMING_NOT_HELD)
        assertThat(get("claimedInterface")).isNull()
        assertThat(get("claimedControlInterface")).isNull()
        assertThat(get("connection")).isNull()
        assertThat(get("levelOwedAfterReset")).isEqualTo(false)
    }

    @Test
    fun closeDevice_lets_go_of_what_was_held_and_only_that() {
        put("claimedInterface", stream)
        put("claimedControlInterface", null)
        device.closeDevice()
        assertThat(calls.filter { it.startsWith("release") }).containsExactly("release streaming")
        assertThat(calls).contains("close")
        assertThat(get("claimedInterface")).isNull()
        assertThat(get("levelOwedAfterReset")).isEqualTo(false)
    }
}
