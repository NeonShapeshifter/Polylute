package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.UacFeatureUnitParser
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-119 diagnostic (no fix yet, by the owner's decision): the Melody's own buttons change a volume
 * the app does not see. Before deciding what to control, the dump lists every Feature Unit of the
 * AudioControl interface with its `bmaControls` per channel (0 = master, 1, 2), and whether that
 * interface has an interrupt endpoint (UAC2 status notifications).
 */
class FeatureUnitDiagnosticTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun le32(v: Int) = intArrayOf(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF, (v shr 24) and 0xFF)

    private val acInterface = b(9, 0x04, 0, 0, 1, 0x01, 0x01, 0x20, 0)

    /** FU: bLength = 6 + (channels + 1) * 4. */
    private fun featureUnit(id: Int, source: Int, vararg controls: Int): ByteArray {
        val body = controls.flatMap { le32(it).toList() }
        val length = 6 + controls.size * 4
        return b(length, 0x24, 0x06, id, source, *body.toIntArray(), 0)
    }

    private val interruptIn = b(7, 0x05, 0x83, 0x03, 6, 0, 8)
    private val streaming = b(9, 0x04, 1, 1, 1, 0x01, 0x02, 0x20, 0)

    @Test
    fun `every Feature Unit is listed with its controls per channel`() {
        val raw = acInterface +
            featureUnit(10, 2, 0x0000000C, 0, 0) +              // volume on master only
            featureUnit(11, 10, 0, 0x0000000C, 0x0000000C) +     // volume on channels 1 and 2
            interruptIn + streaming
        val units = UacFeatureUnitParser.describeAll(raw)

        assertThat(units.map { it.unitId }).containsExactly(10, 11).inOrder()
        assertThat(units[0].controlsPerChannel).containsExactly(0x0C, 0, 0).inOrder()
        assertThat(units[0].volumeChannels).containsExactly(0)
        assertThat(units[1].volumeChannels).containsExactly(1, 2).inOrder()
        assertThat(units[1].sourceId).isEqualTo(10)
    }

    @Test
    fun `the AudioControl interrupt endpoint is reported, and one on another interface is not`() {
        assertThat(UacFeatureUnitParser.audioControlInterruptEndpoint(acInterface + interruptIn + streaming))
            .isEqualTo(0x83)
        assertThat(UacFeatureUnitParser.audioControlInterruptEndpoint(acInterface + streaming + interruptIn))
            .isNull()
    }
}
