package com.decent.usbaudio.media3

/** Host-facing state. ACTIVE means a live USB stream exists; it is not hardware verification. */
enum class UsbDirectSinkState {
    ANDROID_AUDIO,
    USB_DIRECT_AVAILABLE,
    USB_DIRECT_ACTIVE,
    USB_PERMISSION_REQUIRED,
    USB_DIRECT_ERROR,
}

/**
 * Whether the samples reaching the DAC are the source's own.
 *
 * Three values rather than a boolean because the honest answer is often [UNKNOWN]. Bit-perfectness
 * is a claim about the *whole* chain — decoder, resampler, mixer, USB packetizer, and the DAC's own
 * handling of the rate — and a component can only ever speak for the part it owns. A transport that
 * has been observed to pass samples through unmodified, with a decoder whose output format matches
 * the source, has earned [YES]; one that is applying DSP has earned [NO]; anything else is
 * [UNKNOWN] until it has actually been measured.
 */
enum class BitPerfectVerdict { YES, NO, UNKNOWN }

/**
 * Why a stream is not bit-perfect, named rather than merely implied.
 *
 * A listener deciding whether a change they can hear is theirs deserves the specific answer. The
 * values are ordered by nothing — [BitPerfectTruth.reason] picks the first active stage in its own
 * declared order, and reports the count when more than one is running.
 */
enum class BitPerfectReason {
    EQUALIZER_ACTIVE,
    DIGITAL_VOLUME_ACTIVE,
    REPLAY_GAIN_ACTIVE,
    SPEED_ACTIVE,
    NOT_ON_DIRECT_TRANSPORT,
    NOT_MEASURED,
}

/**
 * The truthful answer to "is this bit-perfect, and if not, why not".
 *
 * [verified] is the only field that licenses [BitPerfectVerdict.YES]. It is set by measurement —
 * a payload hash taken at the USB submit boundary matching the source — never by the absence of a
 * known modification, because "we did not knowingly change it" and "it did not change" are
 * different claims and only the second one is worth showing a user.
 */
data class BitPerfectTruth(
    val verdict: BitPerfectVerdict = BitPerfectVerdict.UNKNOWN,
    val reason: BitPerfectReason? = null,
    /** Extra stages beyond the one named in [reason]; zero when [reason] is the whole story. */
    val additionalReasons: Int = 0,
    /** True only when this stream's payload has been compared against the source and matched. */
    val verified: Boolean = false,
) {
    companion object {
        /** No application modification is being applied to the signal. */
        val PRISTINE_UNVERIFIED = BitPerfectTruth(
            verdict = BitPerfectVerdict.UNKNOWN,
            reason = BitPerfectReason.NOT_MEASURED,
        )

        fun processed(active: Set<DirectProcessing>): BitPerfectTruth {
            val ordered = listOf(
                DirectProcessing.EQUALIZER to BitPerfectReason.EQUALIZER_ACTIVE,
                DirectProcessing.DIGITAL_VOLUME to BitPerfectReason.DIGITAL_VOLUME_ACTIVE,
                DirectProcessing.REPLAY_GAIN to BitPerfectReason.REPLAY_GAIN_ACTIVE,
                DirectProcessing.SPEED to BitPerfectReason.SPEED_ACTIVE,
            )
            val matched = ordered.filter { (stage, _) -> stage in active }
            return BitPerfectTruth(
                verdict = BitPerfectVerdict.NO,
                reason = matched.firstOrNull()?.second,
                additionalReasons = (matched.size - 1).coerceAtLeast(0),
            )
        }

        val NOT_DIRECT = BitPerfectTruth(
            verdict = BitPerfectVerdict.NO,
            reason = BitPerfectReason.NOT_ON_DIRECT_TRANSPORT,
        )
    }
}

data class UsbAudioSinkDiagnostics(
    val state: UsbDirectSinkState = UsbDirectSinkState.ANDROID_AUDIO,
    val deviceName: String? = null,
    val permissionGranted: Boolean? = null,
    val sourceSampleRate: Int? = null,
    val sourceChannelCount: Int? = null,
    val sourcePcmEncoding: Int? = null,
    val sourceBitDepth: Int? = null,
    val dacBestBitDepth: Int? = null,
    val dacSupportedRates: List<Int> = emptyList(),
    val errorReason: String? = null,

    // ── Signal path truth ───────────────────────────────────────────────
    //
    // Separate from `state`, which describes the transport. A stream can be USB_DIRECT_ACTIVE and
    // PROCESSED at the same time; that combination is the whole point of the dual-path model, and
    // a single enum could not express it.

    /** What the application is doing to the signal, or `null` when no Direct stream is running. */
    val signalMode: DirectSignalMode? = null,

    /** The PCM encoding actually handed to the USB writer, post-processing. */
    val transportPcmEncoding: Int? = null,

    /** The bit depth actually handed to the USB writer, post-processing. */
    val transportBitDepth: Int? = null,

    /** Whether the negotiated USB alt setting matches what is being written to it. */
    val transportFormatTruthful: Boolean? = null,

    val bitPerfect: BitPerfectTruth = BitPerfectTruth.PRISTINE_UNVERIFIED,
)

/**
 * Why a Direct stream that was running is not any more (B25-S1).
 *
 * [DEVICE_GONE] and [STREAM_STOPPED] both end in a `USB_DIRECT_ERROR`; the host reads the difference. A DAC that was
 * unplugged is not a failed claim: nothing is latched against it, and playback is paused instead of being handed to
 * whatever output Android falls back to (the phone's speaker).
 */
object DirectStreamLoss {
    const val DEVICE_GONE = "USB DAC disconnected"
    const val STREAM_STOPPED = "USB stream stopped or device disconnected"

    fun reasonFor(deviceGone: Boolean): String = if (deviceGone) DEVICE_GONE else STREAM_STOPPED

    fun isDeviceGone(errorReason: String?): Boolean = errorReason == DEVICE_GONE
}
