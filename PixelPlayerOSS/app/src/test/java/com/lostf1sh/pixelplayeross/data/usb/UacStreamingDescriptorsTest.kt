package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.UacStreamingDescriptors
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-006 / AUD-007 / AUD-058: the streaming format comes from the alt that is actually selected.
 *
 * - `openDevice` took endpoint and maxPacket from the *first* AS interface with endpoints. On the DS2
 *   that is alt 1 (16-bit, maxPacket 200) while alt 3 (32-bit, maxPacket 400) is streamed, so every
 *   352.8/384 kHz URB plan came out empty — silence (AUD-006).
 * - `bBitResolution` was used as the container size, ignoring `bSubslotSize`, and non-PCM alts were
 *   not excluded (AUD-007).
 * - Capture interfaces were mixed in, and alts were matched by alt number alone (AUD-058).
 *
 * Descriptors below are synthetic but follow UAC2 byte layouts; the DS2 set mirrors
 * `analysis/usb-dac-descriptors.md` (alt1 16-bit/200, alt3 32-bit/400).
 */
class UacStreamingDescriptorsTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun iface(number: Int, alt: Int, endpoints: Int, subclass: Int = 2) =
        b(9, 0x04, number, alt, endpoints, 0x01, subclass, 0x20, 0)

    /** UAC2 AS_GENERAL: terminal link, controls, format type, bmFormats (LE32), channels, config, name. */
    private fun asGeneral(bmFormats: Long = 1L, channels: Int = 2, formatType: Int = 1) = b(
        16, 0x24, 0x01, 0x01, 0x00, formatType,
        (bmFormats and 0xFF).toInt(), ((bmFormats shr 8) and 0xFF).toInt(),
        ((bmFormats shr 16) and 0xFF).toInt(), ((bmFormats shr 24) and 0xFF).toInt(),
        channels, 0x03, 0, 0, 0, 0,
    )

    /** UAC2 FORMAT_TYPE_I: format type, subslot size, bit resolution. */
    private fun formatType1(subslot: Int, resolution: Int) = b(6, 0x24, 0x02, 0x01, subslot, resolution)

    /** Isochronous endpoint; usage 0 = data, 1 = feedback. */
    private fun endpoint(address: Int, maxPacket: Int, usage: Int = 0) =
        b(7, 0x05, address, 0x05 or (usage shl 4), maxPacket and 0xFF, (maxPacket shr 8) and 0xFF, 1)

    private val audioControl = b(9, 0x04, 0, 0, 0, 0x01, 0x01, 0x20, 0)

    private fun playbackAlt(number: Int, alt: Int, subslot: Int, resolution: Int, maxPacket: Int,
                            bmFormats: Long = 1L, channels: Int = 2) =
        iface(number, alt, 2) + asGeneral(bmFormats, channels) + formatType1(subslot, resolution) +
            endpoint(0x01, maxPacket) + endpoint(0x81, 4, usage = 1)

    private val ds2 = audioControl + iface(1, 0, 0) +
        playbackAlt(1, 1, subslot = 2, resolution = 16, maxPacket = 200) +
        playbackAlt(1, 2, subslot = 3, resolution = 24, maxPacket = 300) +
        playbackAlt(1, 3, subslot = 4, resolution = 32, maxPacket = 400)

    @Test
    fun `the selected alt carries its own endpoint and maxPacket, not the first alt's`() {
        val selected = UacStreamingDescriptors.selectForPlayback(UacStreamingDescriptors.parse(ds2))!!
        assertThat(selected.altSetting).isEqualTo(3)
        assertThat(selected.maxPacketBytes).isEqualTo(400)
        assertThat(selected.endpointOutAddress).isEqualTo(0x01)
        assertThat(selected.feedbackEndpointAddress).isEqualTo(0x81)
        assertThat(selected.interfaceNumber).isEqualTo(1)
    }

    @Test
    fun `352_8 and 384 kHz fit the selected alt and not the first alt's maxPacket`() {
        val alts = UacStreamingDescriptors.parse(ds2)
        val alt1 = alts.single { it.altSetting == 1 }
        val alt3 = alts.single { it.altSetting == 3 }
        for (rate in listOf(352_800, 384_000)) {
            assertThat(UacStreamingDescriptors.canCarry(alt3, rate, channels = 2)).isTrue()
            // The defect: alt 3's 8-byte frames against alt 1's maxPacket (the probe's maxPkt=200).
            val alt3WithFirstAltsPacket = alt3.copy(maxPacketBytes = alt1.maxPacketBytes)
            assertThat(UacStreamingDescriptors.canCarry(alt3WithFirstAltsPacket, rate, channels = 2)).isFalse()
        }
    }

    @Test
    fun `the container is the subslot, the resolution only describes it`() {
        // Common ESS/XMOS layout: 24 significant bits in a 4-byte slot.
        val raw = audioControl + playbackAlt(1, 1, subslot = 4, resolution = 24, maxPacket = 1024)
        val alt = UacStreamingDescriptors.selectForPlayback(UacStreamingDescriptors.parse(raw))!!
        assertThat(alt.containerBits).isEqualTo(32)
        assertThat(alt.bitResolution).isEqualTo(24)
    }

    @Test
    fun `a non-PCM alt is never selected, however high its resolution`() {
        val raw = audioControl +
            playbackAlt(1, 1, subslot = 4, resolution = 32, maxPacket = 1024, bmFormats = 0x80000000L) + // RAW/DSD
            playbackAlt(1, 2, subslot = 3, resolution = 24, maxPacket = 1024)
        val alt = UacStreamingDescriptors.selectForPlayback(UacStreamingDescriptors.parse(raw))!!
        assertThat(alt.altSetting).isEqualTo(2)
        assertThat(alt.isPcm).isTrue()
    }

    @Test
    fun `a capture interface is ignored, and the playback interface is identified by number`() {
        val capture = iface(2, 1, 1) + asGeneral(channels = 1) + formatType1(2, 16) + endpoint(0x82, 96)
        val raw = audioControl + capture + playbackAlt(1, 1, subslot = 3, resolution = 24, maxPacket = 600)
        val alts = UacStreamingDescriptors.parse(raw)
        assertThat(alts.map { it.interfaceNumber }).containsExactly(1)
        assertThat(UacStreamingDescriptors.selectForPlayback(alts)!!.interfaceNumber).isEqualTo(1)
    }

    @Test
    fun `high-bandwidth maxPacket counts the additional transactions`() {
        // wMaxPacketSize = 0x0A00 | 512: 512 bytes x (1 + 1) transactions.
        val raw = audioControl + iface(1, 1, 1) + asGeneral() + formatType1(4, 32) +
            b(7, 0x05, 0x01, 0x05, 0x00, 0x0A, 1)
        assertThat(UacStreamingDescriptors.parse(raw).single().maxPacketBytes).isEqualTo(1024)
    }

    // ── R4-3: the alt is chosen for the track, not once per device ─────────────────────────

    @Test
    fun `the highest-resolution alt that can carry the track is chosen`() {
        // 32-bit alt too small for 384 kHz (48 x 8 = 384 > 300); the 24-bit 3-byte alt carries it
        // (48 x 6 = 288). The per-device "most bits" choice rejected Direct here.
        val raw = audioControl + iface(1, 0, 0) +
            playbackAlt(1, 1, subslot = 3, resolution = 24, maxPacket = 300) +
            playbackAlt(1, 2, subslot = 4, resolution = 32, maxPacket = 300)
        val alts = UacStreamingDescriptors.parse(raw)

        assertThat(UacStreamingDescriptors.selectForFormat(alts, 384_000, 2, sourceBits = 24)!!.altSetting).isEqualTo(1)
        assertThat(UacStreamingDescriptors.selectForFormat(alts, 44_100, 2, sourceBits = 24)!!.altSetting).isEqualTo(2)
    }

    @Test
    fun `a source deeper than every container has no alt`() {
        val raw = audioControl + playbackAlt(1, 1, subslot = 2, resolution = 16, maxPacket = 600)
        val alts = UacStreamingDescriptors.parse(raw)
        assertThat(UacStreamingDescriptors.selectForFormat(alts, 44_100, 2, sourceBits = 24)).isNull()
        assertThat(UacStreamingDescriptors.selectForFormat(alts, 44_100, 2, sourceBits = 16)!!.altSetting).isEqualTo(1)
    }

    @Test
    fun `a float source (processed) accepts any container, still bounded by maxPacket`() {
        val alts = UacStreamingDescriptors.parse(ds2)
        assertThat(UacStreamingDescriptors.selectForFormat(alts, 384_000, 2, sourceBits = null)!!.altSetting).isEqualTo(3)
    }

    @Test
    fun `the per-track alt stays on the interface the device selection claims`() {
        val otherInterface = playbackAlt(2, 1, subslot = 4, resolution = 32, maxPacket = 1024)
        val raw = audioControl + playbackAlt(1, 1, subslot = 3, resolution = 24, maxPacket = 600) + otherInterface
        val alts = UacStreamingDescriptors.parse(raw)
        val claimed = UacStreamingDescriptors.selectForPlayback(alts)!!.interfaceNumber
        assertThat(UacStreamingDescriptors.selectForFormat(alts, 44_100, 2, sourceBits = 16)!!.interfaceNumber)
            .isEqualTo(claimed)
    }

    // ── Audit 2, minor #3: a multichannel-only DAC ──────────────────────────────────────────

    @Test
    fun `a DAC with only a 6-channel alt has no alt for the stereo transport`() {
        // canCarry was evaluated with the requested 2 channels, and the stereo preference fell back to
        // the 6-channel alt: every frame would have been spread over three, at 3x speed.
        val raw = audioControl + iface(1, 0, 0) +
            playbackAlt(1, 1, subslot = 4, resolution = 24, maxPacket = 1024, channels = 6)
        val alts = UacStreamingDescriptors.parse(raw)
        assertThat(UacStreamingDescriptors.selectForFormat(alts, 48_000, 2, sourceBits = 24)).isNull()
    }

    @Test
    fun `a stereo alt is still chosen next to a multichannel one`() {
        val raw = audioControl + iface(1, 0, 0) +
            playbackAlt(1, 1, subslot = 4, resolution = 24, maxPacket = 1024, channels = 6) +
            playbackAlt(1, 2, subslot = 4, resolution = 24, maxPacket = 400)
        val alts = UacStreamingDescriptors.parse(raw)
        assertThat(UacStreamingDescriptors.selectForFormat(alts, 48_000, 2, sourceBits = 24)!!.altSetting).isEqualTo(2)
    }
}
