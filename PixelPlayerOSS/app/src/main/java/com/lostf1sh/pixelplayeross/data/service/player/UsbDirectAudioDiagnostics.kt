package com.lostf1sh.pixelplayeross.data.service.player

import com.decent.usbaudio.media3.BitPerfectTruth
import com.decent.usbaudio.media3.DirectSignalMode
import com.decent.usbaudio.media3.UsbDirectSinkState

data class UsbDirectAudioDiagnostics(
    val state: UsbDirectSinkState = UsbDirectSinkState.ANDROID_AUDIO,
    val deviceName: String? = null,
    val permissionGranted: Boolean? = null,
    val sourceSampleRate: Int? = null,
    val sourceBitDepth: Int? = null,
    val sourceCodec: String? = null,
    val dacBestBitDepth: Int? = null,
    val dacSupportedRates: List<Int> = emptyList(),
    val errorReason: String? = null,

    // ── Signal path, kept apart from the transport state above ──────────
    //
    // `state` says which transport is carrying the audio. These say what the application is doing
    // to it. A surface that shows only the first cannot distinguish a pristine Direct stream from a
    // Direct stream whose equalizer is running, and those are different listening experiences.

    /** What the Direct transport is doing to the signal, or null when Direct is not running. */
    val signalMode: DirectSignalMode? = null,

    /** The sink's verdict on bit-perfectness, with its reason. Never inferred from the backend. */
    val bitPerfect: BitPerfectTruth = BitPerfectTruth.PRISTINE_UNVERIFIED,

    /** The PCM encoding handed to the USB writer after processing, when a Direct stream is up. */
    val transportPcmEncoding: Int? = null,

    /** The bit depth handed to the USB writer after processing, when a Direct stream is up. */
    val transportBitDepth: Int? = null,
) {
    /**
     * A property of the *sink*: this state is holding a stream on the exclusive USB path. It is
     * true whenever a permitted DAC is attached and usable, which is **not** the same question as
     * "the engine is trying to be Direct".
     *
     * Do not gate overlap transitions on this directly — see [reservesExclusiveUsbPathFor].
     */
    val reservesExclusiveUsbPath: Boolean
        get() = state == UsbDirectSinkState.USB_DIRECT_AVAILABLE ||
            state == UsbDirectSinkState.USB_DIRECT_ACTIVE
}

/**
 * Whether overlap (crossfade / gapless) playback must stand down for this route.
 *
 * Two conditions, and both are required:
 *
 * 1. [desiredBackend] is [AudioBackend.USB_DIRECT] — the engine is actually trying to hold the
 *    exclusive path. A listening choice such as EQ, ReplayGain, speed or crossfade pins the route
 *    to Android, and Direct can then never activate; reserving the path in that state would
 *    suppress crossfade permanently for a route that will never be taken.
 * 2. The sink reports the path as available or active.
 *
 * Condition 1 is the one that was missing. `USB_DIRECT_AVAILABLE` is published as soon as a
 * permitted DAC is enumerated, so without it, attaching a DAC to any device with the equalizer on
 * silently cancelled crossfades and gapless transitions for good.
 */
fun UsbDirectAudioDiagnostics.reservesExclusiveUsbPathFor(
    desiredBackend: AudioBackend,
): Boolean = desiredBackend == AudioBackend.USB_DIRECT && reservesExclusiveUsbPath
