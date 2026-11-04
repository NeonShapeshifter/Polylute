package com.lostf1sh.pixelplayeross.data.diagnostics

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * B40-F7: "Detailed logging", for a release build that misbehaves where the developer cannot look.
 *
 * Normally the app writes nothing about what it does (a release build keeps only warnings and errors, and the route trace). The
 * listener can switch this on from Maintenance for [DetailedLogPolicy.DURATION_MS]; it switches itself off, and while it is on the app
 * writes what it is doing to a rotating text file in its own storage, which the listener can send with "Share log" and delete. Nothing
 * is recorded before the switch, nothing is sent by it, and the file leaves the phone only through the share sheet.
 *
 * **What is in it** ([DetailedLogPolicy.TECHNICAL_TAGS]): the app's own lines about the DAC and its HID conversation, the USB audio
 * route and format, the volume ceiling and the DAC's level, the Dedicated mode and the lock screen, the sleep timer, the library scan's
 * counters, the hardware events (USB attach, detach, permission, route and power changes), the route trace and, every ten seconds, the
 * USB output's own one-second summaries and, every minute, the stream's state: the same facts a noise mark holds.
 *
 * **What is not:** lines from the rest of the app are not written, because they can name a song, a playlist or a folder; only a warning
 * or an error from one is noted, by its tag, with its text withheld. Whatever is written passes the [LogScrubber] first: no content address, file path, file name,
 * web address, e-mail address, account name or serial number, and no name of a Bluetooth device or of the song playing. The listener can
 * switch on **"Include song titles and file paths"** (a second switch, off whenever logging starts); then every line of the app is written
 * and paths and titles stay in it, and the share sheet says so.
 *
 * **Limits:** two files of [DetailedLogPolicy.MAX_FILE_BYTES] (the older is dropped when the newer is full); a line is cut at
 * [DetailedLogPolicy.MAX_LINE_CHARS]; if the app logs faster than the file takes, lines are dropped and the count is written.
 */
object DetailedLogPolicy {
    /** How long it stays on. */
    const val DURATION_MS: Long = 30L * 60L * 1000L

    const val MAX_FILE_BYTES: Long = 512L * 1024L
    const val MAX_LINE_CHARS: Int = 600
    const val QUEUE_CAPACITY: Int = 2_000

    /** The output's one-second summaries are written this often (each write holds the seconds since the last). */
    const val SNAPSHOT_INTERVAL_MS: Long = 10_000L

    /** The stream's whole state is written this often. */
    const val STATE_INTERVAL_MS: Long = 60_000L

    /**
     * The tags whose lines are about the machinery and carry no media: written by default. A line with another tag is not written (a
     * warning or an error is noted with its text withheld); "Include song titles and file paths" lifts the list.
     *
     * `Hardware` and `RouteTrace` are not Timber tags: they are the sources of [DetailedLog.record] for the hardware event log and the
     * route trace; `Sampler` is this logger's own snapshots.
     */
    val TECHNICAL_TAGS: Set<String> = setOf(
        // the DAC, its HID conversation and its EQ
        "DAC_HID", "DacShare", "DacHeadphoneAssociation", "FixedOutputNotice", "UsbDirectAudio", "UsbAudioPermission",
        "UsbAudioDevice", "UsbAudioSink", "UsbAudioStream", "UsbStreamingThread", "PcmFloatCapability",
        "DirectDuck", "DirectPresence", "NoiseMark",
        // the volume, and its ceiling
        "SafetyVolume", "SafetyMaxNotice", "Polylute:override-confirm", "ScreenTimeout",
        // Dedicated mode, the lock screen, the system
        "DapContainment", "DapPolicyRestore", "DapDeviceOwner", "DapLockScreen", "DapDisplay", "Maintenance", "SystemBars", "AppLanguage",
        "MediaButtonReceiver",
        // timers
        "SleepTimerScheduler", "SleepTimerReceiver", "PlaybackTimerController",
        // the library scan's counters
        "SyncManager", "SyncWorker", "LibraryFileScanner", "LibraryRules", "LibraryRepair",
        // this logger's own sources
        "Hardware", "RouteTrace", "Sampler", "DetailedLog",
    )

    fun isRecorded(tag: String?, includeEverything: Boolean): Boolean = includeEverything || tag in TECHNICAL_TAGS

    fun remainingMs(untilMs: Long, nowMs: Long): Long = (untilMs - nowMs).coerceAtLeast(0L)

    fun isOn(untilMs: Long, nowMs: Long): Boolean = untilMs > nowMs
}

/**
 * Takes out of a line what must not be in a log the listener will send: pure text in, text out.
 *
 * **Always:** e-mail addresses, `name=value` pairs whose name says secret (token, password, key, authorization), account names in an
 * `Account {name=…}`, serial numbers, and long unbroken tokens that look like keys. **Unless the listener opted in to titles and
 * paths:** content and file addresses, paths into the user's storage (`/storage`, `/sdcard`, `/mnt`, `/emulated`), web addresses, and
 * the name of an audio, picture or lyrics file (a file name is usually a title), together with the few words before it on the line.
 * Paths into the app's own storage and `/dev/bus/usb/…` are kept: they say nothing about the listener.
 */
object LogScrubber {
    private val email = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+""")
    private val secretPair = Regex("""(?i)\b(token|secret|password|passwd|pwd|api[_\-]?key|authorization|bearer|cookie)\b(\s*[=:]\s*)(?:"[^"]*"|\S+)""")
    private val serialPair = Regex("""(?i)\b(serial(?:number|no)?|imei|android_id|ssaid)\b(\s*[=:]\s*)(?:"[^"]*"|\S+)""")
    private val bearer = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=\-]+""")
    private val accountName = Regex("""(?i)Account\s*\{\s*name\s*=\s*[^,}]+""")
    private val longToken = Regex("""\b[A-Za-z0-9_\-]{40,}\b""")
    private val bracketedSerial = Regex("""\s*\[[A-Za-z0-9_\-]{6,}]\s*$""")

    private val contentOrFileUri = Regex("""(?i)\b(?:content|file)://\S+""")
    private val webAddress = Regex("""(?i)\bhttps?://\S+""")
    private val userStoragePath = Regex("""(?<![\w.])/(?:storage|sdcard\d*|mnt|emulated|Music|Download|DCIM|Pictures)(?:/[^\s"'<>|,;]*)?""")
    private val mediaFileName = Regex(
        """(?i)(?:[\p{L}\p{N}_\-.()&'!, ]{0,60})\.(?:flac|mp3|m4a|mp4|ogg|oga|opus|wav|wave|aac|alac|aif|aiff|ape|wma|wv|dsf|dff|lrc|ttml|jpg|jpeg|png|webp)\b""",
    )

    fun scrub(text: String, includeEverything: Boolean): String {
        var s = text
        s = accountName.replace(s, "Account {name=<account>")
        s = bearer.replace(s) { "${it.groupValues[1]} <hidden>" }
        s = secretPair.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}<hidden>" }
        s = serialPair.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}<hidden>" }
        s = email.replace(s, "<email>")
        if (!includeEverything) {
            s = contentOrFileUri.replace(s, "<address>")
            s = webAddress.replace(s, "<url>")
            s = userStoragePath.replace(s, "<path>")
            s = mediaFileName.replace(s, "<file>")
        }
        s = longToken.replace(s, "<token>")
        return s
    }

    /** `0BDA:0316 Fosi DS2 [S12345]` becomes `0BDA:0316 Fosi DS2`: a unit's serial number identifies a unit. */
    fun withoutBracketedSerial(label: String): String = bracketedSerial.replace(label, "")
}

/**
 * The two files, `detailed-log.txt` and the older `detailed-log.1.txt`: appends lines to the first and, when the next line would take
 * it past [maxBytes], moves it over the second (dropping what that held). Not Android; [append] is synchronized.
 *
 * **Each file remembers, on disk, whether a line written with song titles and file paths is in it** (B40-fix1): an empty marker file
 * beside it (`<file>.titles`), made when the first such line is written, moved with the file when it rotates, and gone with the file when
 * it is dropped or [clear]ed. So what the share sheet says about the content is true of the content, whatever the switch says now and
 * across a restart of the app.
 */
class RotatingLogFile(private val directory: File, private val maxBytes: Long) {
    private val current get() = File(directory, CURRENT)
    private val older get() = File(directory, OLDER)
    private val currentMark get() = File(directory, CURRENT + MARK)
    private val olderMark get() = File(directory, OLDER + MARK)

    /** @param sensitive these lines were written with song titles and file paths allowed in them. */
    @Synchronized
    fun append(lines: List<String>, sensitive: Boolean = false) {
        if (lines.isEmpty()) return
        directory.mkdirs()
        var out = current.appendingWriter()
        var size = current.length()
        try {
            for (line in lines) {
                val bytes = line.toByteArray(Charsets.UTF_8).size + 1
                if (size + bytes > maxBytes && size > 0) {
                    out.close()
                    older.delete()
                    olderMark.delete()
                    current.renameTo(older)
                    if (currentMark.isFile) currentMark.renameTo(olderMark)
                    out = current.appendingWriter()
                    size = 0
                }
                if (sensitive && !currentMark.isFile) currentMark.createNewFile()
                out.write(line)
                out.write("\n")
                size += bytes
            }
        } finally {
            out.close()
        }
    }

    /** True when a line written with titles and paths allowed is in either file. */
    @Synchronized
    fun holdsTitlesAndPaths(): Boolean = (older.isFile && olderMark.isFile) || (current.isFile && currentMark.isFile)

    /** The older file then the current one: the log in the order it was written. */
    @Synchronized
    fun readAll(): String = buildString {
        if (older.isFile) append(older.readText())
        if (current.isFile) append(current.readText())
    }

    @Synchronized
    fun sizeBytes(): Long = (if (older.isFile) older.length() else 0L) + (if (current.isFile) current.length() else 0L)

    @Synchronized
    fun clear() {
        older.delete()
        current.delete()
        olderMark.delete()
        currentMark.delete()
    }

    fun hasContent(): Boolean = sizeBytes() > 0L

    private fun File.appendingWriter() = java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(this, true), Charsets.UTF_8))

    companion object {
        const val CURRENT = "detailed-log.txt"
        const val OLDER = "detailed-log.1.txt"
        private const val MARK = ".titles"
    }
}

/**
 * What the logger does, apart from the platform: a gate (on until a time; with or without titles), a bounded queue so a caller never
 * waits for the disk, and the drain that writes it. [DetailedLogging] gives it a clock, a folder and a thread.
 */
class DetailedLogRecorder(
    directory: File,
    private val clock: () -> Long,
    maxFileBytes: Long = DetailedLogPolicy.MAX_FILE_BYTES,
    private val queueCapacity: Int = DetailedLogPolicy.QUEUE_CAPACITY,
) {
    private val file = RotatingLogFile(directory, maxFileBytes)
    private val queue = LinkedBlockingQueue<Entry>(queueCapacity)
    private val dropped = AtomicLong(0)

    @Volatile
    private var untilMs: Long = 0L

    @Volatile
    private var includeEverything: Boolean = false

    /** [sensitive]: written while titles and paths were allowed in the log, so it may hold them. */
    private class Entry(val wallMs: Long, val line: String, val sensitive: Boolean)

    private val clockFormat = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT) }

    val isOn: Boolean get() = DetailedLogPolicy.isOn(untilMs, clock())
    val includesEverything: Boolean get() = includeEverything
    val until: Long get() = untilMs
    val logFile: RotatingLogFile get() = file

    /** Switches it on until [until]; a switch left on from an earlier run is kept. */
    fun start(until: Long, includeEverything: Boolean) {
        this.includeEverything = includeEverything
        this.untilMs = until
    }

    fun setIncludeEverything(value: Boolean) {
        includeEverything = value
    }

    /** Off: nothing more is accepted. What is queued is still written by [drainNow]. */
    fun stop() {
        untilMs = 0L
    }

    /**
     * Whether a call with this tag and priority is worth the work of building its line: while it is on, a technical tag at any level, a
     * warning or an error from any tag (its text is withheld, see [offer]), and every line when titles and paths are included.
     */
    fun accepts(tag: String?, priority: Int): Boolean =
        isOn && (DetailedLogPolicy.isRecorded(tag, includeEverything) || priority >= Log.WARN)

    /** Queues one line: scrubbed, or only its tag and level when the tag is not a technical one. Never blocks. */
    fun offer(tag: String?, priority: Int, message: String, error: Throwable? = null) {
        if (!isOn) return
        val everything = includeEverything
        val body = if (DetailedLogPolicy.isRecorded(tag, everything)) {
            val text = buildString {
                append(message)
                error?.let { append("\n  ").append(it.javaClass.name).append(": ").append(it.message.orEmpty()) }
                error?.stackTrace?.take(STACK_FRAMES)?.forEach { append("\n    at ").append(it) }
            }
            LogScrubber.scrub(text, everything)
        } else {
            "(text withheld: not a technical tag)"
        }
        val line = "${levelChar(priority)}/${tag ?: "-"}: $body".take(DetailedLogPolicy.MAX_LINE_CHARS)
        if (!queue.offer(Entry(clock(), line, everything))) dropped.incrementAndGet()
    }

    /** One line of the logger's own: a header, a footer, a snapshot. Accepted even a moment after [stop] (it is how the footer is written). */
    fun note(tag: String, message: String) {
        val everything = includeEverything
        val line = "I/$tag: ${LogScrubber.scrub(message, everything)}".take(DetailedLogPolicy.MAX_LINE_CHARS)
        if (!queue.offer(Entry(clock(), line, everything))) dropped.incrementAndGet()
    }

    /** Writes what is queued; the lines dropped since the last write are counted in a line of their own. */
    fun drainNow() {
        val batch = ArrayList<Entry>(queue.size)
        queue.drainTo(batch)
        writeBatch(batch)
    }

    /** The drain loop: waits for a line, writes in batches, ends when [keepGoing] says so (and writes what is left). */
    fun drainLoop(keepGoing: () -> Boolean) {
        while (keepGoing()) {
            val first = queue.poll(DRAIN_WAIT_MS, TimeUnit.MILLISECONDS) ?: continue
            val batch = ArrayList<Entry>(queue.size + 1)
            batch += first
            queue.drainTo(batch)
            writeBatch(batch)
        }
        drainNow()
    }

    private fun writeBatch(batch: List<Entry>) {
        val lost = dropped.getAndSet(0)
        if (batch.isEmpty() && lost == 0L) return
        val format = clockFormat.get()!!
        // in the order they came, in runs of the same kind, so the file knows which of them may hold titles and paths
        var start = 0
        while (start < batch.size) {
            val kind = batch[start].sensitive
            var end = start
            while (end < batch.size && batch[end].sensitive == kind) end++
            file.append(batch.subList(start, end).map { "${format.format(Date(it.wallMs))} ${it.line}" }, sensitive = kind)
            start = end
        }
        if (lost > 0) file.append(listOf("${format.format(Date(clock()))} W/DetailedLog: $lost line(s) dropped: the app logged faster than the file took them"))
    }

    private fun levelChar(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        else -> 'A'
    }

    private companion object {
        const val STACK_FRAMES = 6
        const val DRAIN_WAIT_MS = 1_000L
    }
}

/**
 * The call sites that are not Timber lines (the hardware event log, the route trace) say what happened through this, so they reach the
 * file whether or not R8 kept a log call. Costs one volatile read while the logger is off.
 */
object DetailedLog {
    @Volatile
    @PublishedApi
    internal var recorder: DetailedLogRecorder? = null

    inline fun record(tag: String, message: () -> String) {
        val r = recorder ?: return
        if (r.isOn) r.offer(tag, Log.INFO, message())
    }
}
