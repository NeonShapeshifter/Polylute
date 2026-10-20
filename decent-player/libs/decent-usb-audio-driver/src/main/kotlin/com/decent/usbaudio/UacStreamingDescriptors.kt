package com.decent.usbaudio

import kotlin.math.ceil

/**
 * One UAC2 AudioStreaming playback alternate setting, read from the raw configuration descriptors.
 *
 * [containerBits] is what the transport carries per sample (`bSubslotSize`); [bitResolution] only
 * says how many of those bits are significant. Using the resolution as the container sent 3-byte
 * samples to DACs expecting 4 (AUD-007).
 */
data class UacStreamingAlt(
    val interfaceNumber: Int,
    val altSetting: Int,
    val formatType: Int,
    val bmFormats: Long,
    val channels: Int,
    val subslotSize: Int,
    val bitResolution: Int,
    val endpointOutAddress: Int,
    val maxPacketBytes: Int,
    val feedbackEndpointAddress: Int,
) {
    /** FORMAT_TYPE_I with the PCM bit (D0) of `bmFormats`; RAW/DSD alts are never streamed. */
    val isPcm: Boolean
        get() = formatType == FORMAT_TYPE_I && (bmFormats and PCM_FORMAT_BIT) != 0L

    val containerBits: Int
        get() = subslotSize * 8

    private companion object {
        const val FORMAT_TYPE_I = 1
        const val PCM_FORMAT_BIT = 1L
    }
}

/**
 * Reads every playback alt of every AudioStreaming interface, and picks the one to stream (AUD-006,
 * AUD-007, AUD-058). Pure over the raw descriptor bytes, so it is testable without a device.
 *
 * The endpoint, its maxPacket and the container all come from the alt that is actually selected.
 * `openDevice` used to take them from the first AS interface with endpoints — on the DS2 alt 1
 * (16-bit, maxPacket 200) while alt 3 (32-bit, maxPacket 400) was streamed, which made every
 * 352.8/384 kHz URB plan empty.
 */
object UacStreamingDescriptors {
    private const val DESCRIPTOR_INTERFACE = 0x04
    private const val DESCRIPTOR_ENDPOINT = 0x05
    private const val DESCRIPTOR_CS_INTERFACE = 0x24
    private const val AS_GENERAL = 0x01
    private const val FORMAT_TYPE = 0x02
    private const val USB_CLASS_AUDIO = 1
    private const val SUBCLASS_AUDIO_STREAMING = 2
    private const val TRANSFER_ISOCHRONOUS = 1
    private const val USAGE_FEEDBACK = 1
    private const val DIR_IN = 0x80
    private const val MICROFRAMES_PER_SECOND = 8000.0

    fun parse(raw: ByteArray?): List<UacStreamingAlt> {
        raw ?: return emptyList()
        val result = mutableListOf<UacStreamingAlt>()
        var current: Builder? = null
        var i = 0
        while (i + 1 < raw.size) {
            val length = raw.u8(i)
            if (length < 2 || i + length > raw.size) break
            when (raw.u8(i + 1)) {
                DESCRIPTOR_INTERFACE -> if (length >= 9) {
                    current?.build()?.let(result::add)
                    current = if (raw.u8(i + 5) == USB_CLASS_AUDIO && raw.u8(i + 6) == SUBCLASS_AUDIO_STREAMING) {
                        Builder(interfaceNumber = raw.u8(i + 2), altSetting = raw.u8(i + 3))
                    } else null
                }
                DESCRIPTOR_CS_INTERFACE -> current?.let { alt ->
                    when (raw.u8(i + 2)) {
                        AS_GENERAL -> if (length >= 11) {
                            alt.formatType = raw.u8(i + 5)
                            alt.bmFormats = raw.u8(i + 6).toLong() or (raw.u8(i + 7).toLong() shl 8) or
                                (raw.u8(i + 8).toLong() shl 16) or (raw.u8(i + 9).toLong() shl 24)
                            alt.channels = raw.u8(i + 10)
                        }
                        FORMAT_TYPE -> if (length >= 6) {
                            alt.subslotSize = raw.u8(i + 4)
                            alt.bitResolution = raw.u8(i + 5)
                        }
                    }
                }
                DESCRIPTOR_ENDPOINT -> current?.let { alt ->
                    if (length >= 7 && (raw.u8(i + 3) and 0x03) == TRANSFER_ISOCHRONOUS) {
                        val address = raw.u8(i + 2)
                        val usage = (raw.u8(i + 3) shr 4) and 0x03
                        val w = raw.u8(i + 4) or (raw.u8(i + 5) shl 8)
                        // High-bandwidth: bits 11-12 are additional transactions per microframe.
                        val maxPacket = (w and 0x7FF) * (1 + ((w shr 11) and 0x03))
                        if (address and DIR_IN == 0) {
                            if (alt.endpointOut < 0) {
                                alt.endpointOut = address
                                alt.maxPacket = maxPacket
                            }
                        } else if (usage == USAGE_FEEDBACK || alt.feedback < 0) {
                            alt.feedback = address
                        }
                    }
                }
            }
            i += length
        }
        current?.build()?.let(result::add)
        return result
    }

    /**
     * The playback alt to stream: PCM only, stereo preferred (the transport is stereo; mono sources
     * are upmixed), then the highest resolution, then the widest container and maxPacket.
     */
    fun selectForPlayback(alts: List<UacStreamingAlt>): UacStreamingAlt? {
        val pcm = alts.filter { it.isPcm && it.altSetting > 0 && it.subslotSize in 2..4 }
        val candidates = pcm.filter { it.channels == 2 }.ifEmpty { pcm }
        return candidates.maxWithOrNull(
            compareBy<UacStreamingAlt>({ it.bitResolution }, { it.containerBits }, { it.maxPacketBytes })
                .thenByDescending { it.altSetting }
        )
    }

    /**
     * The alt to stream one track on (R4-3): on the interface [selectForPlayback] claims, the
     * highest-resolution PCM alt that can carry [sampleRateHz] × [channels] in its maxPacket and hold
     * [sourceBits] without loss. [sourceBits] is null for a float (processed) source, which any
     * container accepts. Null when no alt can — a format rejection, decided from descriptors alone.
     *
     * The previous choice was one alt per device ("most bits"): if that alt's maxPacket could not
     * carry the rate, Direct was refused even when a narrower alt could.
     *
     * Only an alt with exactly [channels] channels: the stream is framed with that count, so a
     * 6-channel alt under a stereo stream plays every frame spread over three, at 3× (audit 2).
     */
    fun selectForFormat(alts: List<UacStreamingAlt>, sampleRateHz: Int, channels: Int, sourceBits: Int?): UacStreamingAlt? {
        val interfaceNumber = selectForPlayback(alts)?.interfaceNumber ?: return null
        val candidates = alts.filter {
            it.interfaceNumber == interfaceNumber && it.isPcm && it.altSetting > 0 && it.subslotSize in 2..4 &&
                it.channels == channels && canCarry(it, sampleRateHz, channels) &&
                (sourceBits == null || UsbPcmConversion.canConvert(sourceBits, it.containerBits))
        }
        return candidates.maxWithOrNull(
            compareBy<UacStreamingAlt>({ it.bitResolution }, { it.containerBits }, { it.maxPacketBytes })
                .thenByDescending { it.altSetting }
        )
    }

    /**
     * Whether [alt] can carry [sampleRateHz] with [channels] channels: the largest microframe packet
     * (`ceil(rate / 8000)` frames) must fit its maxPacket. The packetizer refuses larger packets, so a
     * failed check here is silence later.
     */
    fun canCarry(alt: UacStreamingAlt, sampleRateHz: Int, channels: Int): Boolean {
        if (sampleRateHz <= 0 || channels <= 0) return false
        val framesPerMicroframe = ceil(sampleRateHz / MICROFRAMES_PER_SECOND).toInt()
        return alt.maxPacketBytes >= framesPerMicroframe * channels * alt.subslotSize
    }

    private class Builder(val interfaceNumber: Int, val altSetting: Int) {
        var formatType = 0
        var bmFormats = 0L
        var channels = 0
        var subslotSize = 0
        var bitResolution = 0
        var endpointOut = -1
        var maxPacket = 0
        var feedback = -1

        /** Only an alt with a playback (OUT) data endpoint is a playback alt; capture has none. */
        fun build(): UacStreamingAlt? = if (endpointOut < 0) null else UacStreamingAlt(
            interfaceNumber, altSetting, formatType, bmFormats, channels, subslotSize, bitResolution,
            endpointOut, maxPacket, feedback,
        )
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
}
