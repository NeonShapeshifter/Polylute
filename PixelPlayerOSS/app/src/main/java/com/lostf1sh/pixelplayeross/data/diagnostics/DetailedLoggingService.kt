package com.lostf1sh.pixelplayeross.data.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.decent.usbaudio.UsbAudioStream
import com.lostf1sh.pixelplayeross.BuildConfig
import com.lostf1sh.pixelplayeross.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * What the Maintenance switch shows: on until a time, with or without titles, how much is on the phone, and whether what is on the phone
 * holds song titles and file paths ([holdsTitlesAndPaths]: a fact about the files, which the switch being off now does not undo).
 */
data class DetailedLogState(
    val on: Boolean = false,
    val untilMs: Long = 0L,
    val includeEverything: Boolean = false,
    val sizeBytes: Long = 0L,
    val holdsTitlesAndPaths: Boolean = false,
)

/**
 * B40-F7: the switch, its clock and its file (the rules are in [DetailedLogPolicy], the writing in [DetailedLogRecorder]).
 *
 * It is on for [DetailedLogPolicy.DURATION_MS] from the press and then off by itself; a restart of the app inside that time carries on
 * until the same moment (the end time is kept), and a restart after it finds it off. "Include song titles and file paths" is off every
 * time logging starts. The file is kept after logging stops, for sharing, until the listener deletes it or the next run overwrites it.
 *
 * The Timber tree is planted once at start and costs a flag read per log call while off; the sampler, the thread that writes and the
 * expiry exist only while it is on.
 */
@Singleton
class DetailedLogging @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val directory = File(context.filesDir, DIRECTORY)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val recorder = DetailedLogRecorder(directory, clock = System::currentTimeMillis)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(
        DetailedLogState(sizeBytes = recorder.logFile.sizeBytes(), holdsTitlesAndPaths = recorder.logFile.holdsTitlesAndPaths()),
    )
    val state: StateFlow<DetailedLogState> = _state.asStateFlow()

    /** How a file becomes an address the share sheet can read: the app's file provider (a test puts its own). */
    internal var uriFor: (File) -> android.net.Uri = { FileProvider.getUriForFile(context, "${context.packageName}.provider", it) }

    private var samplerJob: Job? = null
    private var expiryJob: Job? = null

    @Volatile
    private var drainThread: Thread? = null

    @Volatile
    private var running = false

    /** The tree to plant: it takes what [DetailedLogPolicy] allows while logging is on, and nothing otherwise. */
    val tree: Timber.Tree = object : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean = recorder.accepts(tag, priority)
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) = recorder.offer(tag, priority, message, t)
    }

    init {
        val until = prefs.getLong(KEY_UNTIL, 0L)
        if (DetailedLogPolicy.isOn(until, System.currentTimeMillis())) {
            activate(until, prefs.getBoolean(KEY_EVERYTHING, false), resumed = true)
        } else if (until != 0L) {
            prefs.edit { putLong(KEY_UNTIL, 0L).putBoolean(KEY_EVERYTHING, false) }
        }
    }

    /** Switches logging on for [DetailedLogPolicy.DURATION_MS], without titles and paths. */
    @Synchronized
    fun enable() {
        if (recorder.isOn) return
        val until = System.currentTimeMillis() + DetailedLogPolicy.DURATION_MS
        prefs.edit { putLong(KEY_UNTIL, until).putBoolean(KEY_EVERYTHING, false) }
        // a new run starts a new file: the log shared is the run it says it is
        recorder.logFile.clear()
        activate(until, includeEverything = false, resumed = false)
    }

    @Synchronized
    fun disable() = deactivate("switched off")

    @Synchronized
    fun setIncludeEverything(value: Boolean) {
        if (!recorder.isOn || recorder.includesEverything == value) return
        recorder.setIncludeEverything(value)
        prefs.edit { putBoolean(KEY_EVERYTHING, value) }
        recorder.note(TAG, "song titles and file paths are now ${if (value) "INCLUDED" else "left out"} from this line on")
        publish()
    }

    /** Deletes the log from the phone (it asks nothing: the page does). */
    @Synchronized
    fun delete() {
        recorder.drainNow()
        recorder.logFile.clear()
        File(directory, SHARE_FILE).delete()
        publish()
    }

    /** The log as one file in the share sheet, or null when there is nothing to share or the sheet could not be built. */
    fun shareIntent(): Intent? = runCatching {
        recorder.drainNow()
        if (!recorder.logFile.hasContent()) return@runCatching null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val out = File(directory, SHARE_FILE)
        out.writeText(
            "POLYLUTE DETAILED LOG\nshared=$stamp\n" +
                // a fact about the file, not about the switch: lines written while it was on are still in it when it is off (B40-fix1)
                "includes song titles and file paths=${recorder.logFile.holdsTitlesAndPaths()}\n" +
                "titles and paths switch now=${if (recorder.includesEverything) "on" else "off"}\n\n" +
                recorder.logFile.readAll(),
        )
        val uri = uriFor(out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "polylute-log-$stamp.txt")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, context.getString(R.string.detailed_logging_share_chooser)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrNull()

    private fun activate(until: Long, includeEverything: Boolean, resumed: Boolean) {
        recorder.start(until, includeEverything)
        DetailedLog.recorder = recorder
        running = true
        startDrain()
        recorder.note(TAG, if (resumed) "DETAILED LOG RESUMED after a restart of the app" else "DETAILED LOG START")
        recorder.note(TAG, "build=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        recorder.note(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        recorder.note(TAG, "until=${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(until))} titlesAndPaths=$includeEverything")
        samplerJob?.cancel()
        samplerJob = scope.launch { sample() }
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(DetailedLogPolicy.remainingMs(until, System.currentTimeMillis()))
            synchronized(this@DetailedLogging) { if (recorder.until == until) deactivate("time is up") }
        }
        publish()
    }

    private fun deactivate(reason: String) {
        if (!recorder.isOn && !running) return
        recorder.note(TAG, "DETAILED LOG END ($reason)")
        recorder.stop()
        prefs.edit { putLong(KEY_UNTIL, 0L).putBoolean(KEY_EVERYTHING, false) }
        DetailedLog.recorder = null
        samplerJob?.cancel()
        expiryJob?.cancel()
        running = false
        recorder.drainNow()
        publish()
    }

    private fun startDrain() {
        if (drainThread?.isAlive == true) return
        drainThread = Thread({ recorder.drainLoop { running } }, "DetailedLog").apply {
            isDaemon = true
            start()
        }
    }

    /** The output's seconds every [DetailedLogPolicy.SNAPSHOT_INTERVAL_MS], and the stream's state every minute. */
    private suspend fun sample() {
        var ticks = 0
        while (true) {
            delay(DetailedLogPolicy.SNAPSHOT_INTERVAL_MS)
            if (!recorder.isOn) return
            val seconds = runCatching { UsbAudioStream.nativeGetSecondSummaries(DetailedLogPolicy.SNAPSHOT_INTERVAL_MS) }.getOrDefault("")
            seconds.lineSequence().filter { it.isNotBlank() }.forEach { recorder.note(SAMPLER, it) }
            ticks++
            if (ticks % (DetailedLogPolicy.STATE_INTERVAL_MS / DetailedLogPolicy.SNAPSHOT_INTERVAL_MS).toInt() == 0) {
                val everything = recorder.includesEverything
                // the player is read through its own accessors, on the main thread
                val fields = withContext(Dispatchers.Main) {
                    runCatching { NoiseMark.gather(context, withNames = everything, withSongTitle = everything) }.getOrDefault(emptyList())
                }
                fields.forEach { (name, value) -> recorder.note(SAMPLER, "$name=$value") }
            }
            publish()
        }
    }

    private fun publish() {
        _state.value = DetailedLogState(
            on = recorder.isOn,
            untilMs = recorder.until,
            includeEverything = recorder.includesEverything,
            sizeBytes = recorder.logFile.sizeBytes(),
            holdsTitlesAndPaths = recorder.logFile.holdsTitlesAndPaths(),
        )
    }

    companion object {
        const val DIRECTORY = "detailed-logs"
        const val PREFS = "detailed_logging"
        private const val KEY_UNTIL = "until_ms"
        private const val KEY_EVERYTHING = "include_everything"
        private const val SHARE_FILE = "polylute-detailed-log.txt"
        private const val TAG = "DetailedLog"
        private const val SAMPLER = "Sampler"
    }
}
