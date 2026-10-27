package com.lostf1sh.pixelplayeross.data.diagnostics

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import androidx.core.content.FileProvider
import com.decent.usbaudio.UsbAudioStream
import com.decent.usbaudio.media3.DirectPathTelemetry
import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.BuildConfig
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import timber.log.Timber

/**
 * B27-A5 (review N-07), in release since B39-S2: "I heard noise just now", written down while the evidence is still in memory.
 *
 * 2026-10-16 16:41 the owner heard ~2 s of static in Purity at 96 kHz and the log could not say what the feedback and the packets
 * did in that window; in 1.0 he heard a ~3 s "shh" once and there was nothing to look at at all. The output keeps one-second summaries of both
 * (usb-second-ring.h, ~100 bytes a second, nothing logged, in every build), and this writes the last [DEFAULT_SECONDS] of them to a
 * file with the stream's state around them: how it was opened, which feedback policy ran, the DAC's level, the route and the song. One log
 * line points at the file, and the file can be shared. It is a button: nothing is written, and nothing leaves the phone, until somebody presses
 * it (and then sends the file somewhere on purpose). The row is in the player's More sheet and in Maintenance; a debug build can also run
 * it with `adb shell am broadcast -a ...debug.MARK_NOISE`, the only part of the debug bridge that has anything to do with it.
 *
 * Where the files are: `files/noise-marks/noise-<yyyyMMdd-HHmmss>.txt`, the newest [KEEP_FILES] of them.
 */
object NoiseMark {
    const val TAG = "NoiseMark"
    const val DIRECTORY = "noise-marks"
    const val DEFAULT_SECONDS = 30

    /** The marks kept on the phone; the oldest go when a new one is written, so a release install does not collect them. */
    const val KEEP_FILES = 20

    /** What is known about the stream outside the native ring, as ordered `name to value` pairs ([compose] writes them as they come). */
    data class Facts(val wallClockMs: Long, val fields: List<Pair<String, String>>)

    /** `noise-20261017-154012.txt`, in [zone]'s clock. */
    fun fileName(wallClockMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        "noise-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = zone }.format(Date(wallClockMs)) + ".txt"

    /** The file's text: when, the stream's state, then the native seconds exactly as the output reports them. */
    fun compose(facts: Facts, seconds: Int, secondSummaries: String, zone: TimeZone = TimeZone.getDefault()): String = buildString {
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS zzz", Locale.ROOT).apply { timeZone = zone }
        appendLine("NOISE MARK")
        appendLine("when=${clock.format(Date(facts.wallClockMs))} wallMs=${facts.wallClockMs}")
        appendLine("window=${seconds}s (the output keeps the last 128 s; this is the part ending now)")
        appendLine()
        appendLine("-- state at the mark --")
        facts.fields.forEach { (name, value) -> appendLine("$name=$value") }
        appendLine()
        appendLine("-- the last ${seconds}s, one line per second that streamed (t-N = N s before the mark) --")
        append(secondSummaries.ifBlank { "(the output reported nothing)\n" })
        if (!secondSummaries.endsWith("\n")) appendLine()
    }

    /** Writes [text] under [directory]; a second mark in the same second gets a suffix instead of replacing the first. */
    fun write(directory: File, wallClockMs: Long, text: String, zone: TimeZone = TimeZone.getDefault()): File {
        directory.mkdirs()
        val base = fileName(wallClockMs, zone).removeSuffix(".txt")
        var file = File(directory, "$base.txt")
        var n = 1
        while (file.exists()) file = File(directory, "$base-${n++}.txt")
        file.writeText(text)
        prune(directory, KEEP_FILES)
        return file
    }

    /** Deletes all but the newest [keep] marks in [directory] (by name, which is by time). */
    fun prune(directory: File, keep: Int) {
        directory.listFiles { f -> f.isFile && f.name.startsWith("noise-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            ?.drop(keep)
            ?.forEach { runCatching { it.delete() } }
    }

    /**
     * Marks the noise now: gathers the state, takes the output's last [seconds] and writes the file. Main thread (the player is read
     * through its own accessors). Returns the file, or null when nothing could be written; never throws into the caller.
     */
    fun mark(context: Context, seconds: Int = DEFAULT_SECONDS): File? = runCatching {
        val wall = System.currentTimeMillis()
        val window = seconds.coerceIn(1, 128)
        val summaries = runCatching { UsbAudioStream.nativeGetSecondSummaries(window * 1000L) }
            .getOrElse { "(the output could not be asked: ${it.javaClass.simpleName})\n" }
        val text = compose(Facts(wall, gather(context)), window, summaries)
        val file = write(File(context.filesDir, DIRECTORY), wall, text)
        val lines = summaries.lineSequence().count { it.startsWith("t-") }
        Timber.tag(TAG).i("NOISE_MARK file=%s window=%ds seconds=%d", file.absolutePath, window, lines)
        file
    }.onFailure { Timber.tag(TAG).w(it, "NOISE_MARK failed") }.getOrNull()

    /** Purity for the untouched path, Resonance for the processed one (the enum's own name stays in the code and the logs). */
    internal fun signalName(mode: DirectSignalMode): String = when (mode) {
        DirectSignalMode.PRISTINE -> "Purity"
        DirectSignalMode.PROCESSED -> "Resonance"
    }

    /** The route reason in the product's words where it names a path (the enum's own name stays in the code and the logs). */
    internal fun reasonName(reason: AudioRouteReason): String = when (reason) {
        AudioRouteReason.DIRECT_PRISTINE -> "Purity"
        AudioRouteReason.DIRECT_PROCESSED -> "Resonance"
        else -> reason.name
    }

    /** B39-A4: the route as one readable line, with Purity and Resonance where `AudioRouteState.toString()` printed PRISTINE and PROCESSED. */
    internal fun routeSummary(route: AudioRouteState, withDeviceName: Boolean = true): String =
        "actual=${route.actualBackend} desired=${route.desiredBackend} transition=${route.transition} reason=${reasonName(route.reason)} " +
            "signal=${signalName(route.signalMode)} device=${if (withDeviceName) route.deviceName else WITHHELD} permission=${route.permissionGranted} " +
            "confirmedRateHz=${route.confirmedOutputRateHz} error=${route.directError}"

    /** The same for the sink's diagnostics. */
    internal fun usbSummary(usb: UsbDirectAudioDiagnostics, withDeviceName: Boolean = true): String =
        "state=${usb.state} device=${if (withDeviceName) usb.deviceName else WITHHELD} permission=${usb.permissionGranted} " +
            "source=${usb.sourceSampleRate} Hz ${usb.sourceBitDepth}-bit ${usb.sourceCodec} " +
            "dacBestBitDepth=${usb.dacBestBitDepth} dacRates=${usb.dacSupportedRates} " +
            "signal=${usb.signalMode?.let(::signalName) ?: "none"} bitPerfect=${usb.bitPerfect.verdict}/${usb.bitPerfect.reason} " +
            "transport=${usb.transportBitDepth}-bit/encoding ${usb.transportPcmEncoding} error=${usb.errorReason}"

    /** What a field stands for when the name of a device or the title of a song is not to be written down (the detailed log, B40-F7). */
    internal const val WITHHELD = "<withheld>"

    /**
     * The stream's state as ordered `name to value` pairs. A noise mark is a file the listener makes on purpose and takes it all
     * ([withNames] and [withSongTitle] true); the detailed log passes false for both unless the listener included titles: the name of an
     * output can be a Bluetooth device's, and the song is a title.
     */
    internal fun gather(context: Context, withNames: Boolean = true, withSongTitle: Boolean = true): List<Pair<String, String>> {
        val fields = ArrayList<Pair<String, String>>()
        fun add(name: String, read: () -> Any?) {
            fields += name to (runCatching { read()?.toString() }.getOrElse { "(${it.javaClass.simpleName})" } ?: "unknown")
        }
        add("build") { "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}" }
        add("device") { "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" }
        val entry = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, NoiseMarkEntryPoint::class.java)
        }.getOrNull()
        val engine = entry?.dualPlayerEngine()
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        add("song") {
            if (!onMain) "(not read: not on the main thread)" else engine?.masterPlayer?.currentMediaItem?.let {
                if (withSongTitle) "${it.mediaId} ${it.mediaMetadata.title}" else it.mediaId
            }
        }
        add("positionMs") { if (onMain) engine?.diagnosticPositionMs() else null }
        add("route") { engine?.audioRouteState?.value?.let { routeSummary(it, withNames) } }
        add("usbDiagnostics") { engine?.usbAudioDiagnostics?.value?.let { usbSummary(it, withNames) } }
        val t = DirectPathTelemetry.snapshot()
        add("usb.altSetting") { t.usbAltSetting }
        add("usb.requestedRateHz") { t.usbRequestedRateHz }
        add("usb.confirmedRateHz") { t.usbConfirmedRateHz }
        add("transport") { "${t.transportSampleRateHz} Hz, ${t.transportBitDepth} bit, ${t.transportChannelCount} ch, encoding ${t.transportPcmEncoding}" }
        add("producer") { t.producer }
        // B39-A4: the product's names for the two paths; the verdict and its reason keep the engine's own words (they are what an engineer greps for).
        add("signal") { "${signalName(t.signalMode)}, bit-perfect ${t.bitPerfectVerdict}: ${t.bitPerfectReason}" }
        add("feedback.policy") { t.feedbackPolicy }
        add("feedback.stuck") { "${t.feedbackStuck} (episodes ${t.feedbackStuckEpisodes}, readings stuck ${t.feedbackStuckReadings}, ${t.feedbackStuckMs} ms in all)" }
        add("feedback.last") { "last ${t.feedbackLastFpmf} sending ${t.feedbackSendingFpmf} (frames per microframe), readings ${t.feedbackReadings}, refused ${t.feedbackRejectedReadings}" }
        add("output") { "underruns ${t.underruns}, late ${t.lateBuffers}, dropped ${t.droppedBuffers}, usbFramesWritten ${t.usbFramesWritten}" }
        val safety = entry?.safetyVolumeController()?.state?.value
        add("volume.dac") {
            safety?.let {
                "${it.backend.kind} ${if (withNames) it.backend.outputLabel else WITHHELD} step ${it.backend.rawLevel} of ${it.backend.rawMin}..${it.backend.rawMax}, " +
                    "${it.hardwareDecibels} dB, confirmed by the DAC ${it.backend.levelVerified}"
            }
        }
        add("volume.safetyMax") {
            safety?.let { "enabled ${it.safetyEnabled}, ceiling step ${it.calibration?.ceilingRaw}, override ${it.temporaryOverride}" }
        }
        add("volume.android") {
            context.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        add("gain") { engine?.gainModel?.value }
        // B40-F8: whether the Android route's peak limiter is in the signal of the stream that is configured
        add("limiter") { engine?.resonanceLimiterRunning?.value }
        return fields
    }

    /** The share sheet for [file]: plain text, through the app's own file provider. Null if it could not be built. */
    fun shareIntent(context: Context, file: File): Intent? = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, context.getString(R.string.noise_mark_share_chooser)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrNull()
}

/** Read-only handles a noise mark takes from the object graph: the engine's published state and the DAC's level. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NoiseMarkEntryPoint {
    fun dualPlayerEngine(): DualPlayerEngine
    fun safetyVolumeController(): SafetyVolumeController
}
