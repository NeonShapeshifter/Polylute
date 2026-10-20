package com.lostf1sh.pixelplayeross.dap.admin

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import com.decent.usbaudio.UsbAudioStream
import com.decent.usbaudio.media3.DirectPathTelemetry
import com.lostf1sh.pixelplayeross.BuildConfig
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import timber.log.Timber

/**
 * B27-A5 (review N-07): "I heard noise just now", written down while the evidence is still in memory.
 *
 * 2026-10-16 16:41 the owner heard ~2 s of static in Purity at 96 kHz and the log could not say what the feedback and the packets
 * did in that window. The output keeps one-second summaries of both (usb-second-ring.h, ~100 bytes a second, nothing logged), and
 * this writes the last [DEFAULT_SECONDS] of them to a file with the stream's state around them: how it was opened, which feedback
 * policy ran, the DAC's level, the route and the song. One log line points at the file. Debug builds only; the trigger is the
 * player's More menu (the row is shown through `DebugHooks`) or `adb shell am broadcast -a ...debug.MARK_NOISE`.
 *
 * Where the files are: `files/noise-marks/noise-<yyyyMMdd-HHmmss>.txt` of the debug app,
 * `adb shell run-as com.lostf1sh.pixelplayeross.debug cat files/noise-marks/<name>`.
 */
object NoiseMark {
    const val TAG = "NoiseMark"
    const val DIRECTORY = "noise-marks"
    const val DEFAULT_SECONDS = 30

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
        return file
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

    private fun gather(context: Context): List<Pair<String, String>> {
        val fields = ArrayList<Pair<String, String>>()
        fun add(name: String, read: () -> Any?) {
            fields += name to (runCatching { read()?.toString() }.getOrElse { "(${it.javaClass.simpleName})" } ?: "unknown")
        }
        add("build") { "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}" }
        val entry = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, DualPlayerEngineDebugEntryPoint::class.java)
        }.getOrNull()
        val engine = entry?.dualPlayerEngine()
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        add("song") {
            if (!onMain) "(not read: not on the main thread)" else engine?.masterPlayer?.currentMediaItem?.let {
                "${it.mediaId} ${it.mediaMetadata.title}"
            }
        }
        add("positionMs") { if (onMain) engine?.diagnosticPositionMs() else null }
        add("route") { engine?.audioRouteState?.value }
        add("usbDiagnostics") { engine?.usbAudioDiagnostics?.value }
        val t = DirectPathTelemetry.snapshot()
        add("usb.altSetting") { t.usbAltSetting }
        add("usb.requestedRateHz") { t.usbRequestedRateHz }
        add("usb.confirmedRateHz") { t.usbConfirmedRateHz }
        add("transport") { "${t.transportSampleRateHz} Hz, ${t.transportBitDepth} bit, ${t.transportChannelCount} ch, encoding ${t.transportPcmEncoding}" }
        add("producer") { t.producer }
        add("signal") { "${t.signalMode} ${t.bitPerfectVerdict} ${t.bitPerfectReason}" }
        add("feedback.policy") { t.feedbackPolicy }
        add("feedback.stuck") { "${t.feedbackStuck} (episodes ${t.feedbackStuckEpisodes}, readings stuck ${t.feedbackStuckReadings}, ${t.feedbackStuckMs} ms in all)" }
        add("feedback.last") { "last ${t.feedbackLastFpmf} sending ${t.feedbackSendingFpmf} (frames per microframe), readings ${t.feedbackReadings}, refused ${t.feedbackRejectedReadings}" }
        add("output") { "underruns ${t.underruns}, late ${t.lateBuffers}, dropped ${t.droppedBuffers}, usbFramesWritten ${t.usbFramesWritten}" }
        val safety = entry?.safetyVolumeController()?.state?.value
        add("volume.dac") {
            safety?.let {
                "${it.backend.kind} ${it.backend.outputLabel} step ${it.backend.rawLevel} of ${it.backend.rawMin}..${it.backend.rawMax}, " +
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
        return fields
    }
}
