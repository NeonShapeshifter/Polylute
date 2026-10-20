package com.lostf1sh.pixelplayeross.data.stats

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.decent.usbaudio.media3.BitPerfectVerdict
import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics

/*
 * What a listening event records about the audio it was heard through (B28a).
 *
 * These types are part of the wire format of `playback_history.json` and of the backup. Every field is
 * nullable and written only when known: null means "not recorded", never "none". A value is added by
 * adding a constant or a field; an old reader skips what it does not know (Gson reads an unknown enum name
 * as null and ignores an unknown field), and an event written before B28a simply has none of it.
 */

/** Which output carried a listen. */
enum class ListenRoute {
    /** The app drives the DAC itself. */
    USB_DIRECT,

    /** A USB audio output on the Android route: a DAC, but the system mixer carries the stream. */
    ANDROID_USB,

    SPEAKER,
    BLUETOOTH,
    WIRED,

    /** The platform named no output the app can classify. */
    OTHER,
}

/**
 * What the Direct transport did to the signal ([DirectSignalMode]): PRISTINE is [PURITY], PROCESSED is [RESONANCE].
 * It describes the stream, not the Purity switch, and it exists only on USB Direct: on any other route it is null.
 */
enum class ListenSignal { PURITY, RESONANCE }

/**
 * The bit-perfect truth model's verdict, copied and never upgraded. [UNKNOWN] is the model saying it has not
 * measured, and it stays that way here.
 */
enum class ListenBitPerfect { YES, NO, UNKNOWN }

/**
 * Where a play came from. B28a wrote only [EXTERNAL_VIEW], the one source the player knew for certain; since B28 the
 * screens that load a queue pass a [ListenSource] (see `PlayerViewModel.playSongs`), so the rest of the vocabulary is
 * written too. A value is added at the end of this list and an old reader reads it as "not recorded".
 *
 * [COLLECTION] is one of the automatic collections, [TOP_LIST] a "Top N" of Stats, [HOME_MIX] a mix Home shows,
 * [RECENT] the recently-played list.
 */
enum class ListenSourceKind { ALBUM, ARTIST, PLAYLIST, COLLECTION, LIBRARY, SEARCH, HOME_MIX, EXTERNAL_VIEW, OTHER, GENRE, RECENT, TOP_LIST }

/** The typed source a queue was loaded from: what kind of list, and which one when that is an id (never a title). */
data class ListenSource(val kind: ListenSourceKind, val ref: String? = null)

/**
 * The audio facts of a listen, as the app's own model held them while it played.
 *
 * When they changed during one listen, these are the ones that carried the most listening time and [share] says
 * how much that was; see `ListeningStatsTracker`.
 */
data class ListenAudioFacts(
    val route: ListenRoute? = null,
    val signal: ListenSignal? = null,
    val bitPerfect: ListenBitPerfect? = null,
    /** The source's sample rate, bit depth and codec (the MIME type the decoder named). */
    val sourceRateHz: Int? = null,
    val sourceBits: Int? = null,
    val sourceCodec: String? = null,
    /** What the Direct transport carried: the DAC-confirmed rate and the bit depth handed to the USB writer. */
    val transportRateHz: Int? = null,
    val transportBits: Int? = null,
    /**
     * What the transport carried, when it is not the file's codec: on Direct the sink is handed decoded PCM, so this is
     * `audio/raw` there and [sourceCodec] is the file's own (FLAC, ALAC...). Added by B28; absent from older events.
     */
    val transportCodec: String? = null,
    /** The DAC's display name, on the two USB routes. */
    val deviceName: String? = null,
    /**
     * The percentage of the listened time these facts cover, when it was not all of it (1..99). Absent means the
     * whole listen, or that the listen did not change them. Not part of the facts: [withoutShare] drops it.
     */
    val share: Int? = null,
) {
    fun withoutShare(): ListenAudioFacts = if (share == null) this else copy(share = null)
}

/**
 * The source context of a listen: where it came from and whether shuffle was on when it started.
 *
 * [ref] names the list when it has an id (a playlist id, an album id, a collection id, the label of a Top N); it is
 * never a title, so nothing of the library is copied into the history. Added by B28.
 */
data class ListenContext(
    val kind: ListenSourceKind? = null,
    val shuffle: Boolean? = null,
    val ref: String? = null,
)

/** One output the platform lists: its `AudioDeviceInfo.TYPE_*` and the name it gives. */
data class AndroidOutputDevice(val type: Int, val productName: String?)

/** The Android output a listen is going to when the app is not driving the DAC. */
data class AndroidOutput(val route: ListenRoute, val deviceName: String?)

/**
 * Names the Android output from the list the platform gives, with the precedence the Details sheet uses (USB,
 * Bluetooth, wired, speaker), so the history and the screen say the same thing. Pure: reading the list is the
 * caller's. The BLE types are newer than the minimum SDK; as constants they are plain ints that no output reports
 * on a platform that lacks them, which is what the Details sheet relies on too.
 */
@SuppressLint("InlinedApi")
object AndroidOutputClassifier {
    private val USB = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )
    private val BLUETOOTH = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )
    private val WIRED = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
    )
    private val SPEAKER = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
    )

    fun classify(outputs: List<AndroidOutputDevice>): AndroidOutput {
        outputs.firstOrNull { it.type in USB }?.let { return AndroidOutput(ListenRoute.ANDROID_USB, it.productName) }
        outputs.firstOrNull { it.type in BLUETOOTH }?.let { return AndroidOutput(ListenRoute.BLUETOOTH, null) }
        outputs.firstOrNull { it.type in WIRED }?.let { return AndroidOutput(ListenRoute.WIRED, null) }
        outputs.firstOrNull { it.type in SPEAKER }?.let { return AndroidOutput(ListenRoute.SPEAKER, null) }
        return AndroidOutput(ListenRoute.OTHER, null)
    }
}

/**
 * Builds [ListenAudioFacts] from what the app already holds: the engine's route state, the Direct sink's
 * diagnostics, the player's live audio format and the Android output. Nothing here probes the DAC or the
 * platform; a fact the model does not hold is left null.
 */
object ListenAudioFactsAssembler {

    private fun isRawPcm(mime: String?): Boolean = isRawPcmMime(mime)

    /**
     * @param fileMimeType the codec of the file that is playing, as its media item and the library name it. The sink only
     *   ever sees decoded PCM (`audio/raw`), so the file's own is what "formats heard" needs; raw PCM stays the transport fact.
     */
    fun assemble(
        route: AudioRouteState,
        diagnostics: UsbDirectAudioDiagnostics,
        androidOutput: AndroidOutput?,
        liveFormat: DualPlayerEngine.AudioFormatSnapshot?,
        fileMimeType: String? = null,
    ): ListenAudioFacts {
        val direct = route.isDirectActive
        // The failed-claim fallback is reported as the speaker, as the Details sheet does for it.
        val android = if (route.transition == AudioRouteTransition.DIRECT_FAILED_FALLBACK) {
            AndroidOutput(ListenRoute.SPEAKER, null)
        } else {
            androidOutput
        }
        val listenRoute = if (direct) ListenRoute.USB_DIRECT else android?.route ?: ListenRoute.OTHER

        // The sink reads the source when it configures for Direct and publishes nothing for an Android stream, so
        // its numbers are the stream's only while Direct is carrying it; otherwise the player's live format is.
        val sourceRate = if (direct) {
            diagnostics.sourceSampleRate ?: liveFormat?.sampleRate
        } else {
            liveFormat?.sampleRate
        }
        val sourceBits = if (direct) {
            diagnostics.sourceBitDepth ?: liveFormat?.pcmEncoding?.let(::pcmEncodingBits)
        } else {
            liveFormat?.pcmEncoding?.let(::pcmEncodingBits)
        }
        val sinkCodec = (if (direct) diagnostics.sourceCodec ?: liveFormat?.sampleMimeType else liveFormat?.sampleMimeType)
            ?.takeIf { it.isNotBlank() }
        val sourceCodec = fileMimeType?.trim()?.takeIf { it.isNotEmpty() && !isRawPcm(it) }
            ?: sinkCodec?.takeUnless(::isRawPcm)
        val transportCodec = if (direct && isRawPcm(sinkCodec)) sinkCodec else null

        return ListenAudioFacts(
            route = listenRoute,
            signal = if (direct) {
                if (route.signalMode == DirectSignalMode.PROCESSED) ListenSignal.RESONANCE else ListenSignal.PURITY
            } else {
                null
            },
            bitPerfect = when (route.bitPerfect.verdict) {
                BitPerfectVerdict.YES -> ListenBitPerfect.YES
                BitPerfectVerdict.NO -> ListenBitPerfect.NO
                BitPerfectVerdict.UNKNOWN -> ListenBitPerfect.UNKNOWN
            },
            sourceRateHz = sourceRate?.takeIf { it > 0 },
            sourceBits = sourceBits?.takeIf { it > 0 },
            sourceCodec = sourceCodec,
            transportRateHz = if (direct) route.confirmedOutputRateHz?.takeIf { it > 0 } else null,
            transportBits = if (direct) diagnostics.transportBitDepth?.takeIf { it > 0 } else null,
            transportCodec = transportCodec,
            deviceName = when (listenRoute) {
                ListenRoute.USB_DIRECT -> route.deviceName ?: diagnostics.deviceName
                ListenRoute.ANDROID_USB -> android?.deviceName
                else -> null
            }?.takeIf { it.isNotBlank() && !it.startsWith("/") },
        )
    }

    /**
     * The bit depth a PCM encoding names, or null for an encoding that is not PCM (a compressed source reports none). The
     * big-endian 24-bit constant is Media3's unstable API, as it is in the engine's own table.
     */
    @OptIn(UnstableApi::class)
    private fun pcmEncodingBits(pcmEncoding: Int): Int? = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT,
        C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }
}

/**
 * What the Direct sink and an Android decoder name when they hand over decoded samples (`audio/raw`). It is the shape of the stream
 * that reached the output, not a codec the listener has a file in: B28a wrote it as `sourceCodec` on Direct for the events between its
 * install and B28, and everything that reads a codec back treats it as one that was not recorded.
 */
fun isRawPcmMime(mime: String?): Boolean = mime != null && mime.startsWith("audio/raw", ignoreCase = true)
