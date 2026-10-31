package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.UacStreamingAlt
import com.decent.usbaudio.UacStreamingDescriptors
import com.decent.usbaudio.UsbPcmConversion
import com.decent.usbaudio.UsbTransportPlan
import com.decent.usbaudio.UsbTransportPlanner
import com.google.common.truth.Truth.assertThat
import kotlin.math.ceil
import org.junit.jupiter.api.Test

/**
 * B46-A3: Kotlin asks the native transport plan, and says yes and no to exactly the formats it said yes and no to before.
 *
 * The JVM here loads a host build of the plan (`buildHostTransportPlanLib`), the C++ the writer plans with. The oracles are the
 * formulas Kotlin used before the plan existed, copied from B45's tree:
 *  - `UacStreamingDescriptors.canCarry`: `ceil(rate / 8000)` frames x channels x subslot must fit the alt's maxPacket;
 *  - `UsbPcmConversion.canConvert`: source and container are 16/24/32 bits and the container does not narrow the source.
 * The native gate pins the same plan to the C++ writer's numbers (tools/usb-transport-plan-test.cpp).
 */
class UsbTransportPlanEquivalenceTest {

    private val rates = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000)

    /** The preflight of B45's tree. */
    private fun legacyCanCarry(maxPacketBytes: Int, rate: Int, channels: Int, subslot: Int): Boolean {
        if (rate <= 0 || channels <= 0) return false
        return maxPacketBytes >= ceil(rate / 8000.0).toInt() * channels * subslot
    }

    private fun alt(
        subslot: Int, channels: Int, maxPacket: Int, altSetting: Int = 1, resolution: Int = subslot * 8,
        bInterval: Int = 1, feedbackPacket: Int = 4, feedbackAddress: Int = 0x81,
    ) = UacStreamingAlt(
        interfaceNumber = 1, altSetting = altSetting, formatType = 1, bmFormats = 1L, channels = channels, subslotSize = subslot,
        bitResolution = resolution, endpointOutAddress = 0x01, maxPacketBytes = maxPacket, feedbackEndpointAddress = feedbackAddress,
        bInterval = bInterval, feedbackMaxPacketBytes = feedbackPacket,
    )

    @Test
    fun `the preflight carries exactly the formats it carried before, over the whole matrix`() {
        var cells = 0
        for (rate in rates) for (channels in 1..2) for (subslot in 2..4) {
            val widest = ceil(rate / 8000.0).toInt() * channels * subslot
            // one byte under, exactly enough, and a generous high-bandwidth endpoint
            for (maxPacket in listOf(widest - 1, widest, 3 * 1024)) {
                val candidate = alt(subslot, channels, maxPacket)
                assertThat(UacStreamingDescriptors.canCarry(candidate, rate, channels))
                    .isEqualTo(legacyCanCarry(maxPacket, rate, channels, subslot))
                cells++
            }
        }
        assertThat(cells).isEqualTo(8 * 2 * 3 * 3)
    }

    @Test
    fun `the format selection agrees with the old formulas for every source depth`() {
        for (rate in rates) for (channels in 1..2) for (subslot in 2..4) for (sourceBits in listOf(null, 16, 24, 32)) {
            val widest = ceil(rate / 8000.0).toInt() * channels * subslot
            for (maxPacket in listOf(widest - 1, widest)) {
                val alts = listOf(alt(subslot, channels, maxPacket))
                val expected = legacyCanCarry(maxPacket, rate, channels, subslot) &&
                    (sourceBits == null || UsbPcmConversion.canConvert(sourceBits, subslot * 8))
                assertThat(UacStreamingDescriptors.selectForFormat(alts, rate, channels, sourceBits) != null)
                    .isEqualTo(expected)
            }
        }
    }

    @Test
    fun `the plan and the Kotlin container mirror accept the same depths and containers`() {
        for (sourceBits in listOf(0, 8, 12, 16, 20, 24, 32, 33)) for (containerBytes in 2..4) {
            val plan = UsbTransportPlanner.plan(
                sampleRateHz = 48_000, channels = 2, containerBytes = containerBytes, sourceBits = sourceBits.takeIf { it != 0 },
                maxPacketBytes = 3 * 1024, bInterval = 1, hasFeedback = true, feedbackBytes = 4,
            )
            val mirror = sourceBits == 0 || UsbPcmConversion.canConvert(sourceBits, containerBytes * 8)
            assertThat(plan.supported).isEqualTo(mirror)
            if (!mirror) assertThat(plan.reasonName()).isEqualTo("container-narrower-than-source")
        }
    }

    @Test
    fun `a device whose timing the output does not drive yet is still carried and is reported`() {
        // bInterval 4, a 3-byte feedback and an unreadable speed: B45's preflight accepted them and so does this one.
        val odd = alt(subslot = 4, channels = 2, maxPacket = 400, bInterval = 4, feedbackPacket = 3)
        assertThat(UacStreamingDescriptors.canCarry(odd, 96_000, 2)).isTrue()
        val plan = UsbTransportPlanner.plan(odd, 96_000, 2, sourceBits = 24)
        assertThat(plan.supported).isTrue()
        assertThat(plan.assumptions).isEqualTo(
            UsbTransportPlan.Assumption.BINTERVAL or UsbTransportPlan.Assumption.FEEDBACK_WIDTH or UsbTransportPlan.Assumption.SPEED_UNKNOWN,
        )
        // Full speed, with the bus speed known: planned as high speed and said so.
        val fullSpeed = UsbTransportPlanner.plan(odd, 96_000, 2, sourceBits = 24, busSpeed = 2)
        assertThat(fullSpeed.supported).isTrue()
        assertThat(fullSpeed.assumptions and UsbTransportPlan.Assumption.SERVICE_INTERVAL).isNotEqualTo(0)
        assertThat(fullSpeed.ringUrbs).isEqualTo(plan.ringUrbs)
    }

    @Test
    fun `the descriptor parser reads bInterval and the feedback packet size`() {
        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        fun endpoint(address: Int, maxPacket: Int, usage: Int, interval: Int) =
            b(7, 0x05, address, 0x05 or (usage shl 4), maxPacket and 0xFF, (maxPacket shr 8) and 0xFF, interval)
        val raw = b(9, 0x04, 1, 1, 2, 0x01, 0x02, 0x20, 0) +
            b(16, 0x24, 0x01, 0x01, 0x00, 1, 1, 0, 0, 0, 2, 0x03, 0, 0, 0, 0) + b(6, 0x24, 0x02, 0x01, 4, 32) +
            endpoint(0x01, 400, usage = 0, interval = 4) + endpoint(0x81, 3, usage = 1, interval = 8)
        val alt = UacStreamingDescriptors.parse(raw).single()
        assertThat(alt.bInterval).isEqualTo(4)            // the DATA endpoint's, not the feedback endpoint's 8
        assertThat(alt.feedbackMaxPacketBytes).isEqualTo(3)
        assertThat(alt.maxPacketBytes).isEqualTo(400)
    }

    @Test
    fun `the numbers Kotlin reads are the plan's, at the values the writer has always run with`() {
        val plan = UsbTransportPlanner.plan(
            sampleRateHz = 96_000, channels = 2, containerBytes = 4, sourceBits = 24, maxPacketBytes = 400, bInterval = 1,
            hasFeedback = true, feedbackBytes = 4, prerollMs = 500, busSpeed = 3,
        )
        assertThat(plan.supported).isTrue()
        assertThat(plan.assumptions).isEqualTo(0)
        // every index of UsbTransportPlan.Field against the value the native tests pin for this format
        assertThat(plan[UsbTransportPlan.Field.SERVICE_INTERVAL_US]).isEqualTo(125L)
        assertThat(plan[UsbTransportPlan.Field.INTERVALS_PER_SECOND]).isEqualTo(8000L)
        assertThat(plan[UsbTransportPlan.Field.NOMINAL_NUM]).isEqualTo(96_000L)
        assertThat(plan[UsbTransportPlan.Field.NOMINAL_DEN]).isEqualTo(8000L)
        assertThat(plan[UsbTransportPlan.Field.BYTES_PER_FRAME]).isEqualTo(8L)
        assertThat(plan[UsbTransportPlan.Field.MAX_FRAMES_PER_PACKET]).isEqualTo(12L)
        assertThat(plan[UsbTransportPlan.Field.MAX_PACKET_BYTES]).isEqualTo(96L)
        assertThat(plan[UsbTransportPlan.Field.PACKETS_PER_URB]).isEqualTo(8L)
        assertThat(plan[UsbTransportPlan.Field.URB_MAX_BYTES]).isEqualTo(3200L)
        assertThat(plan[UsbTransportPlan.Field.URB_SLOT_BYTES]).isEqualTo(4096L)
        assertThat(plan[UsbTransportPlan.Field.RESIDUAL_CAP_BYTES]).isEqualTo(24_576L)
        // B47-A2: 128 URBs of 1 ms (it was 80): a writer stalled for the 82 ms of 2026-10-29 17:32:33 leaves the DAC with data
        assertThat(plan[UsbTransportPlan.Field.RING_URBS]).isEqualTo(128L)
        assertThat(plan[UsbTransportPlan.Field.BACKPRESSURE_URBS]).isEqualTo(128L)
        assertThat(plan[UsbTransportPlan.Field.LOW_RING_URBS]).isEqualTo(64L)
        // B46-B2: the queue limits are durations, 1440 ms and 11 900 ms of this stream's rate
        assertThat(plan.queueSoftFrames).isEqualTo(96_000L * 1440 / 1000)
        assertThat(plan.queueHardFrames).isEqualTo(96_000L * 11_900 / 1000)
        // B46-B1 / B47-A2: the silence lead is 110 ms (60 before the ring was 128 URBs deep), and B47-A3 the resume lead is 50 ms
        assertThat(plan[UsbTransportPlan.Field.SILENCE_LEAD_FRAMES]).isEqualTo(10_560L)
        assertThat(plan[UsbTransportPlan.Field.RESUME_LEAD_FRAMES]).isEqualTo(4800L)
        assertThat(plan.resumeLeadFrames).isEqualTo(4800L)
        assertThat(plan[UsbTransportPlan.Field.SILENCE_CHUNK_FRAMES]).isEqualTo(97L)
        assertThat(plan[UsbTransportPlan.Field.FADE_OUT_FRAMES]).isEqualTo(768L)
        assertThat(plan[UsbTransportPlan.Field.FADE_IN_FRAMES]).isEqualTo(1440L)
        assertThat(plan.prerollFrames).isEqualTo(48_000L)
        assertThat(plan.prerollBlocks()).containsExactly(8192, 8192, 8192, 8192, 8192, 7040).inOrder()
        assertThat(plan[UsbTransportPlan.Field.PREROLL_BLOCKS]).isEqualTo(6L)
        assertThat(plan[UsbTransportPlan.Field.FEEDBACK_BYTES]).isEqualTo(4L)
        // B48-fix1: the feedback start guard, appended: the first 500 ms after the interface starts are sent at nominal (0 for an endpoint with no feedback to hold back)
        assertThat(plan[UsbTransportPlan.Field.FEEDBACK_START_GUARD_MS]).isEqualTo(500L)
        assertThat(plan.feedbackStartGuardMs).isEqualTo(500)
        assertThat(UsbTransportPlan.Field.COUNT).isEqualTo(32)
        assertThat(plan.pauseKeepaliveMs).isEqualTo(2000L)
        assertThat(UsbTransportPlan.Field.PAUSE_KEEPALIVE_MS).isEqualTo(31)
        assertThat(UsbTransportPlanner.defaultQueueFrames(44_100)).isEqualTo(44_100L * 1440 / 1000 to 44_100L * 11_900 / 1000)
    }

    @Test
    fun `an endpoint too narrow for the nominal packet is refused with its reason`() {
        val plan = UsbTransportPlanner.plan(
            sampleRateHz = 384_000, channels = 2, containerBytes = 4, sourceBits = null, maxPacketBytes = 383, bInterval = 1,
            hasFeedback = true, feedbackBytes = 4,
        )
        assertThat(plan.supported).isFalse()
        assertThat(plan.reasonName()).isEqualTo("packet-over-max-packet")
    }
}
