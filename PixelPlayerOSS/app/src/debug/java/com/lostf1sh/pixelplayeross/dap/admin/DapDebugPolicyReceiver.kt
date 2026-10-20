package com.lostf1sh.pixelplayeross.dap.admin

import android.content.BroadcastReceiver
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.decent.usbaudio.FeedbackPolicy
import com.decent.usbaudio.UsbAudioStream
import com.decent.usbaudio.media3.DirectPathTelemetry
import com.decent.usbaudio.media3.DirectPlaybackPresence
import com.decent.usbaudio.media3.RateSwitchPreroll
import com.lostf1sh.pixelplayeross.data.diagnostics.HardwareEventLog
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqualizerCoordinator
import com.lostf1sh.pixelplayeross.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.NativeEngineDebugSwitch
import com.lostf1sh.pixelplayeross.data.service.player.RateSwitchPrerollSetting
import com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics
import com.lostf1sh.pixelplayeross.data.volume.PristineLockDiagnostic
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File

/** Read-only handles the debug bridge needs from the object graph. Not used by release builds. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DualPlayerEngineDebugEntryPoint {
    fun dualPlayerEngine(): DualPlayerEngine

    /**
     * The same profile authority the UI edits.
     *
     * The EQ bridge writes through this rather than around it, so what the stability gates exercise
     * is the real state the DSP reads — not a parallel debug flag that could agree with itself while
     * disagreeing with the audio path.
     */
    fun headphoneEqualizerCoordinator(): HeadphoneEqualizerCoordinator

    /** Background scope for the suspend profile writes. */
    @AppScope
    fun appScope(): CoroutineScope

    /** B27-A5: the DAC's level and Safety Max, read (never written) into a noise mark. */
    fun safetyVolumeController(): SafetyVolumeController

    /** B30: the DAC's own EQ: the read, the backups, the one gate's writer and the debug override. */
    fun dacEqService(): com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqService
}
/**
 * Reads the live route authority from the object graph.
 *
 * The engine is a `@Singleton` created with the music service; the bridge only reads its published
 * state, so resolving it lazily here cannot start playback or change a route. Returns `null` when
 * the graph is not up yet, so a query before the service exists reports "unavailable" rather than
 * inventing a default.
 */
internal object DualPlayerEngineDebugAccess {
    internal fun engineInternal(context: Context): DualPlayerEngine? = engine(context)

    private fun engine(context: Context): DualPlayerEngine? = runCatching {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DualPlayerEngineDebugEntryPoint::class.java,
        ).dualPlayerEngine()
    }.getOrNull()

    fun route(context: Context): AudioRouteState? = engine(context)?.audioRouteState?.value

    fun usbDiagnostics(context: Context): UsbDirectAudioDiagnostics? =
        engine(context)?.usbAudioDiagnostics?.value

    fun positionMs(context: Context): Long = engine(context)?.diagnosticPositionMs() ?: -1L

    fun rebuildCount(context: Context): Long = engine(context)?.rebuildCount() ?: -1L

    fun durationMs(context: Context): Long = engine(context)?.diagnosticDurationMs() ?: -1L

    fun gainModel(context: Context): DualPlayerEngine.GainModel? = engine(context)?.gainModel?.value

    fun controlPrecondition(context: Context): String =
        engine(context)?.diagnosticControlPrecondition() ?: "engine=null"

    /** `delivered`, `no-setter` (the engine exists, the service has not registered its processor) or `engine-null`. */
    fun setReplayGainFactor(context: Context, factor: Float?): String {
        val engine = engine(context) ?: return "engine-null"
        return if (engine.setDiagnosticReplayGainFactor(factor)) "delivered" else "no-setter"
    }

    fun setVolume(context: Context, volume: Float) {
        engine(context)?.setDiagnosticUserGain(volume)
    }
}

/** Shell-only development bridge for policy recovery and validation. Not in release builds. */
class DapDebugPolicyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller by lazy { DapDeviceOwnerController(context) }
        when (intent.action) {
            ACTION_APPLY_HOME -> controller.applyPersistentHome()
            ACTION_CLEAR_HOME -> controller.clearPersistentHome()
            ACTION_ENABLE_CONTAINMENT -> controller.enableDedicatedMode()
            ACTION_DISABLE_CONTAINMENT -> {
                controller.disableDedicatedMode()
                controller.clearPersistentHome()
            }
            ACTION_RESTORE_POLICY -> controller.restoreOwnerPolicies()
            ACTION_START_USB_PAYLOAD_VALIDATION -> {
                val requestedLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                val safeLabel = requestedLabel
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(80)
                    .ifBlank { "unnamed" }
                val outputDir = File(context.filesDir, "bitperfect-validation").apply { mkdirs() }
                val manifest = File(outputDir, "$safeLabel.json")
                val expectedBytes = intent.getLongExtra(EXTRA_EXPECTED_BYTES, 0L).coerceAtLeast(0L)
                val started = UsbAudioStream.nativeStartPayloadValidation(
                    manifest.absolutePath,
                    requestedLabel.ifBlank { safeLabel },
                    expectedBytes,
                )
                Log.i(
                    TAG,
                    "USB payload validation start=$started label=$safeLabel expectedBytes=$expectedBytes",
                )
            }
            ACTION_STOP_USB_PAYLOAD_VALIDATION -> {
                val result = UsbAudioStream.nativeStopPayloadValidation()
                Log.i(TAG, "USB payload validation stopped manifest=${result ?: "none"}")
            }
            ACTION_USB_PAYLOAD_VALIDATION_STATUS -> {
                Log.i(TAG, "USB payload validation ${UsbAudioStream.nativeGetPayloadValidationStatus()}")
            }
            ACTION_REQUEST_USB_AUDIO_PERMISSION -> {
                val usbManager = context.getSystemService(UsbManager::class.java)
                val device = usbManager?.deviceList?.values?.firstOrNull { candidate ->
                    (0 until candidate.interfaceCount).any { index ->
                        candidate.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_AUDIO
                    }
                }
                if (usbManager == null || device == null) {
                    Log.w(TAG, "USB audio permission request skipped: no USB audio device")
                } else if (usbManager.hasPermission(device)) {
                    Log.i(TAG, "USB audio permission already granted")
                } else {
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            PendingIntent.FLAG_MUTABLE
                        } else {
                            0
                        }
                    val permissionIntent = PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(ACTION_USB_AUDIO_PERMISSION_RESULT).setPackage(context.packageName),
                        flags,
                    )
                    usbManager.requestPermission(device, permissionIntent)
                    Log.i(TAG, "USB audio permission requested through Android")
                }
            }
            ACTION_USB_AUDIO_PERMISSION_RESULT -> {
                Log.i(
                    TAG,
                    "USB audio permission result granted=" +
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                )
            }
            ACTION_REPORT_AUDIO_ROUTE -> logAudioRoute(context)
            ACTION_DUMP_DAC_VOLUME_CONTROLS -> {
                // AUD-119: run before and after pressing the DAC's own buttons, with Direct active.
                val dump = com.decent.usbaudio.UsbAudioDevice.getInstance(context).dumpVolumeControls()
                dump.lines().forEach { Log.i(TAG, it) }
                appendDiagnostic(context, "dac-volume-controls.txt", dump)
            }
            // F-01 phase 1: read-only FiiO HID probe (GETs only) and a passive listen; own thread, since
            // the USB transfers and the listen window must not block the main thread.
            ACTION_PROBE_DAC_HID, ACTION_DAC_HID_LISTEN -> {
                val pending = goAsync()
                val seconds = intent.getIntExtra(EXTRA_SECONDS, 20)
                Thread({
                    try {
                        val probe = com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProbe(context) { line ->
                            Log.i(DAC_HID_TAG, line)
                            appendDiagnostic(context, "dac-hid.txt", line)
                        }
                        // B30: the probe opens its own connection, so it takes the HID lock the attach read and the writer take.
                        val lock = runCatching {
                            EntryPointAccessors.fromApplication(context.applicationContext, DualPlayerEngineDebugEntryPoint::class.java)
                                .dacEqService().controller.access.lock
                        }.getOrNull()
                        kotlinx.coroutines.runBlocking {
                            if (lock != null) lock.lock()
                            try {
                                if (intent.action == ACTION_PROBE_DAC_HID) probe.probe() else probe.listen(seconds)
                            } finally {
                                if (lock != null) lock.unlock()
                            }
                        }
                    } finally {
                        pending.finish()
                    }
                }, "DacHidProbe").start()
            }
            // B30: the DAC's own EQ (read, backup, roundtrip, one band, restore, the write override). Own thread: USB transfers block.
            in DacEqDebugCommands.ACTIONS -> {
                val pending = goAsync()
                Thread({
                    try {
                        DacEqDebugCommands.run(context, intent) { line ->
                            timber.log.Timber.tag(DAC_HID_TAG).i(line)
                            appendDiagnostic(context, "dac-hid.txt", line)
                        }
                    } finally {
                        pending.finish()
                    }
                }, "DacEqDebug").start()
            }
            ACTION_REPORT_HARDWARE_EVENTS -> logHardwareEvents(context)
            ACTION_START_DIRECT_PATH_CAPTURE -> DirectPathCapture.start(context)
            ACTION_SNAPSHOT_DIRECT_PATH_CAPTURE -> DirectPathCapture.snapshot(context)
            ACTION_STOP_DIRECT_PATH_CAPTURE -> DirectPathCapture.stop(context)
            ACTION_SET_HEADPHONE_EQ -> setHeadphoneEq(context, intent)
            // Clears only the PCM level accumulators, so a gain measurement starts from zero
            // without disturbing the transition counters the round trip is judged on.
            ACTION_RESET_PCM_LEVEL -> DirectPathTelemetry.resetPcmLevel()
            ACTION_ARM_PROVOCATION -> {
                val rebase = intent.getBooleanExtra(EXTRA_REBASE_ENABLED, false)
                DirectPathTelemetry.diagnosticRebaseOperandOnSeek = rebase
                DirectPathTelemetry.diagnosticRebaseTargetUs = -1L
                DirectPathTelemetry.diagnosticRebasePendingTargetUs = -1L
                Log.i(TAG, "ARM_PROVOCATION rebase=$rebase")
                armProvocation(context)
            }
            ACTION_REPORT_CONTROL_PRECONDITION ->
                Log.i(TAG, "CONTROL_PRECONDITION|" + DualPlayerEngineDebugAccess.controlPrecondition(context))
            ACTION_SET_REBASE_OPERAND -> {
                val enabled = intent.getBooleanExtra(EXTRA_REBASE_ENABLED, false)
                DirectPathTelemetry.diagnosticRebaseOperandOnSeek = enabled
                DirectPathTelemetry.diagnosticRebaseTargetUs = -1L
                DirectPathTelemetry.diagnosticRebasePendingTargetUs = -1L
                Log.i(TAG, "Diagnostic rebase operand on seek = $enabled")
            }
            ACTION_SET_VOLUME -> {
                val volume = intent.getFloatExtra(EXTRA_VOLUME, 1f).coerceIn(0f, 1f)
                DualPlayerEngineDebugAccess.setVolume(context, volume)
            }
            ACTION_SET_REPLAY_GAIN_FACTOR -> {
                // A negative value means "no ReplayGain contribution"; 1.0 is a real, unity factor
                // and is deliberately distinct from it.
                val raw = intent.getFloatExtra(EXTRA_RG_FACTOR, -1f)
                val factor = if (raw < 0f) null else raw.coerceIn(0f, 4f)
                val delivered = DualPlayerEngineDebugAccess.setReplayGainFactor(context, factor)
                reportReplayGainFactor(context, intent, factor, delivered)
            }
            ACTION_SET_PRISTINE_LOCK_OVERRIDE -> {
                // B9-4. The extra is required, like the other switches: a missing one must not read as "off".
                if (!intent.hasExtra(EXTRA_IGNORE)) {
                    Log.w(TAG, "SET_PRISTINE_LOCK_OVERRIDE needs --ez ignore true|false; unchanged")
                } else {
                    PristineLockDiagnostic.setIgnoring(intent.getBooleanExtra(EXTRA_IGNORE, false))
                }
                reportPristineLockOverride(context, intent.getStringExtra(EXTRA_TAG))
            }
            ACTION_SET_FORCE_NATIVE_ENGINE -> {
                // B12a plan B. The extra is required: a missing or misspelled one must not read as "off".
                if (!intent.hasExtra(EXTRA_ENABLED)) {
                    Log.w(TAG, "SET_FORCE_NATIVE_ENGINE needs --ez enabled true|false; unchanged")
                } else {
                    NativeEngineDebugSwitch.shared(context).set(intent.getBooleanExtra(EXTRA_ENABLED, false))
                }
                reportForceNativeEngine(context, "set", intent.getStringExtra(EXTRA_TAG))
            }
            ACTION_REPORT_FORCE_NATIVE_ENGINE ->
                reportForceNativeEngine(context, "report", intent.getStringExtra(EXTRA_TAG))
            // Retired by B12a: the pipeline is the default PRISTINE producer, so there is nothing left to force
            // and the persisted value is inert. A script that still sends them gets a warning, not a silent no-op.
            ACTION_SET_PRISTINE_EXOPLAYER_PIPELINE, ACTION_REPORT_PRISTINE_EXOPLAYER_PIPELINE -> {
                Log.w(TAG, "${intent.action?.substringAfterLast('.')} is retired (B12a): PRISTINE already goes through the " +
                    "ExoPlayer pipeline by default and the old persisted value is ignored; " +
                    "SET_FORCE_NATIVE_ENGINE --ez enabled true brings the native engine back")
                reportForceNativeEngine(context, "retired-action", intent.getStringExtra(EXTRA_TAG))
            }
            ACTION_SET_USB_WAIT_MODE -> {
                // AUD-145. The extra is required, like the other switches: a missing one must not read as a mode.
                when (intent.getStringExtra(EXTRA_MODE)) {
                    "poll" -> UsbAudioStream.nativeSetPollWait(true)
                    "sleep" -> UsbAudioStream.nativeSetPollWait(false)
                    else -> Log.w(TAG, "SET_USB_WAIT_MODE needs --es mode poll|sleep; unchanged")
                }
                reportUsbWaitMode(context, intent.getStringExtra(EXTRA_TAG))
            }
            ACTION_MARK_NOISE -> {
                // B27-A5 (N-07). Same as the More menu's row: writes the last seconds of the output's summaries and the stream's
                // state to files/noise-marks/ and logs one line (`NoiseMark: NOISE_MARK file=...`) pointing at it. Optional
                // `--ei seconds N` (1..128, default 30).
                NoiseMark.mark(context, intent.getIntExtra(EXTRA_SECONDS, NoiseMark.DEFAULT_SECONDS))
            }
            ACTION_SET_FEEDBACK_POLICY -> {
                // B10-2. Required, like the other switches: a missing or misspelled mode must not read as `follow`.
                val mode = FeedbackPolicy.parse(intent.getStringExtra(EXTRA_MODE))
                if (mode == null) {
                    Log.w(TAG, "SET_FEEDBACK_POLICY needs --es mode follow|nominal|clamp|auto; unchanged")
                } else {
                    runCatching { UsbAudioStream.nativeSetFeedbackPolicy(mode) }
                        .onFailure { Log.w(TAG, "SET_FEEDBACK_POLICY: native layer unavailable", it) }
                }
                reportFeedbackPolicy(context, intent.getStringExtra(EXTRA_TAG))
            }
            ACTION_SET_RATE_SWITCH_PREROLL -> {
                // B12a-4. Required, like the other switches, and only 0, 250, 500 or 1000: a missing or any other
                // value must not become a pre-roll.
                if (!intent.hasExtra(EXTRA_MS)) {
                    Log.w(TAG, "SET_RATE_SWITCH_PREROLL needs --ei ms 0|250|500|1000; unchanged")
                } else if (!RateSwitchPrerollSetting.shared(context).set(intent.getIntExtra(EXTRA_MS, -1))) {
                    Log.w(TAG, "SET_RATE_SWITCH_PREROLL: ${intent.getIntExtra(EXTRA_MS, -1)} is not one of " +
                        "${RateSwitchPreroll.ALLOWED_MS.joinToString("|")}; unchanged")
                }
                reportRateSwitchPreroll(context, intent.getStringExtra(EXTRA_TAG))
            }
            ACTION_SET_DIRECT_PRESENCE -> {
                // B12a-3 / F-10. Required, like the other switches: a missing one must not read as "off".
                if (!intent.hasExtra(EXTRA_ENABLED)) {
                    Log.w(TAG, "SET_DIRECT_PRESENCE needs --ez enabled true|false; unchanged")
                } else {
                    DirectPlaybackPresence.processOverride = intent.getBooleanExtra(EXTRA_ENABLED, true)
                }
                reportDirectPresence(context, intent.getStringExtra(EXTRA_TAG))
            }
        }
    }

    /**
     * Says what a `SET_REPLAY_GAIN_FACTOR` did and whether it can switch the mode (B9-4). The factor
     * reaching the processor is not enough: while the owner's Pure Direct lock (F-02) is on, every DSP
     * contributor's requirement is frozen, so the stream stays PRISTINE however the factor is set — which
     * is why this "stopped working" between 2026-09-29 and 2026-10-06. The line names that, and the
     * `extraType` catches the other silent failure: `getFloatExtra` returns its default for an extra that
     * is not a float (`--ei`, `--es`), which this action reads as "no ReplayGain contribution".
     */
    private fun reportReplayGainFactor(context: Context, intent: Intent, factor: Float?, delivered: String) {
        val extra = intent.extras?.get(EXTRA_RG_FACTOR)
        val locked = PristineLockDiagnostic.lastPersistedLock()
        val ignoring = PristineLockDiagnostic.ignoringLock.value
        val switches = PristineLockDiagnostic.replayGainSwitchesToProcessed(factor, locked, ignoring)
        val why = when {
            factor == null -> "no-contribution(extraType=${extra?.javaClass?.simpleName ?: "missing"}; use --ef rg_factor <x>)"
            delivered != "delivered" -> delivered
            switches -> "ok"
            kotlin.math.abs(factor - 1f) <= PristineLockDiagnostic.UNITY_TOLERANCE -> "unity-factor"
            locked != false && !ignoring -> "pure-direct-lock-freezes-requirements(use SET_PRISTINE_LOCK_OVERRIDE --ez ignore true)"
            else -> "unknown"
        }
        val line = "REPLAY_GAIN_FACTOR|factor=$factor|extraType=${extra?.javaClass?.simpleName ?: "missing"}" +
            "|delivered=$delivered|persistedPristineLock=${locked ?: "unseen"}|ignoringLock=$ignoring" +
            "|switchesToProcessed=$switches|why=$why|tag=${intent.getStringExtra(EXTRA_TAG) ?: "-"}"
        Log.i(TAG, line)
        appendDiagnostic(context, "replay-gain-factor.txt", line)
    }

    /** Says whether this process reads the Pure Direct lock as off for the requirements (B9-4). */
    private fun reportPristineLockOverride(context: Context, tag: String?) {
        val line = "PRISTINE_LOCK_OVERRIDE|ignoring=${PristineLockDiagnostic.ignoringLock.value}" +
            "|persistedPristineLock=${PristineLockDiagnostic.lastPersistedLock() ?: "unseen"}|persisted=false|tag=${tag ?: "-"}"
        Log.i(TAG, line)
        appendDiagnostic(context, "pristine-lock-override.txt", line)
    }

    /**
     * Says how the USB writer waits for a URB completion now (AUD-145), and its totals so far: `poll`
     * is poll(2) on the usbfs fd, `sleep` the old 125 µs loop. Process-lifetime, not persisted: a new
     * process starts in `poll`. [tag] is echoed back like the other reports.
     */
    private fun reportUsbWaitMode(context: Context, tag: String?) {
        val stats = runCatching { UsbAudioStream.nativeGetWaitStats() }.getOrNull()
        val line = "USB_WAIT_MODE|mode=${when (stats?.getOrNull(0)) { 1L -> "poll"; 0L -> "sleep"; else -> "?" }}" +
            "|waits=${stats?.getOrNull(1)}|waitedUs=${stats?.getOrNull(2)}" +
            "|timeouts=${stats?.getOrNull(3)}|empty=${stats?.getOrNull(4)}" +
            "|tag=${tag ?: "-"}"
        Log.i(TAG, line)
        appendDiagnostic(context, "usb-wait-mode.txt", line)
    }

    /**
     * Says what the USB output does with the DAC's feedback now and what it has seen (B10-2, AUD-124 "shh"):
     * `mode` is follow, nominal, clamp or auto; `stuck` whether the same off-nominal reading has been answered
     * for 5 s or more; `lastFpmf` the DAC's latest reading and `nominal` the stream's rate / 8000 (frames per
     * microframe), `sending` what the packetizer was last given (equal to `lastFpmf` only under follow).
     * Process-only, not persisted: a new process starts in `auto` (B12a-2, debug and release); `follow` is the override to
     * diagnose. `rejected` counts the readings not obeyed (beyond +-5 %, or implausible under `auto`), `streaks` the
     * streaks of implausible readings and `lastRejected` the last one's value. [tag] is echoed back like the other reports.
     */
    private fun reportFeedbackPolicy(context: Context, tag: String?) {
        val stats = runCatching { UsbAudioStream.nativeGetFeedbackStats() }.getOrNull()
        val rate = stats?.getOrNull(10)
        val line = "FEEDBACK_POLICY|mode=${FeedbackPolicy.name(stats?.getOrNull(0))}" +
            "|stuck=${stats?.getOrNull(1)?.let { it == 1L } ?: "?"}" +
            "|lastFpmf=${fpmfText(FeedbackPolicy.fpmf(stats?.getOrNull(9)))}" +
            "|nominal=${fpmfText(rate?.takeIf { it > 0 }?.let { it / 8_000.0 })}" +
            "|sending=${fpmfText(FeedbackPolicy.fpmf(stats?.getOrNull(11)))}" +
            "|episodes=${stats?.getOrNull(2)}|stuckMs=${stats?.getOrNull(4)}" +
            "|rejected=${stats?.getOrNull(12)}|streaks=${stats?.getOrNull(13)}" +
            "|lastRejected=${fpmfText(FeedbackPolicy.fpmf(stats?.getOrNull(14)))}" +
            "|tag=${tag ?: "-"}"
        Log.i(TAG, line)
        appendDiagnostic(context, "feedback-policy.txt", line)
    }

    /**
     * Says the pre-roll queued after a rate change (B12a-4): `ms` is the persisted setting (0 = off, the default; it
     * applies from the next stream rebuild), `queued` how many pre-rolls this process has queued and `lastMs` the
     * last one's length. The line `Rate-switch preroll: N ms of silence before <item>` is in logcat (tag UsbAudioSink)
     * each time one is queued.
     */
    private fun reportRateSwitchPreroll(context: Context, tag: String?) {
        val line = "RATE_SWITCH_PREROLL|ms=${RateSwitchPrerollSetting.shared(context).ms()}" +
            "|queued=${DirectPathTelemetry.rateSwitchPrerolls.get()}|lastMs=${DirectPathTelemetry.lastRateSwitchPrerollMs}" +
            "|tag=${tag ?: "-"}"
        Log.i(TAG, line)
        appendDiagnostic(context, "rate-switch-preroll.txt", line)
    }

    /**
     * Says whether the silent track that makes Android see USB Direct playing is on and running (B12a-3, F-10).
     * `enabled` is the process override (default true); `active` whether a silent track is playing right now (it
     * follows the user's play/pause and the USB stream, so it is false while paused); `starts`/`stops`/`failures`
     * are process totals. Compare with `adb shell dumpsys audio` (a player of this package, state started).
     * A change applies from the next play/pause or stream event, not to a track already running.
     */
    private fun reportDirectPresence(context: Context, tag: String?) {
        val line = "DIRECT_PRESENCE|enabled=${DirectPlaybackPresence.processOverride ?: true}" +
            "|active=${DirectPathTelemetry.presenceActive}" +
            "|starts=${DirectPathTelemetry.presenceStarts.get()}|stops=${DirectPathTelemetry.presenceStops.get()}" +
            "|failures=${DirectPathTelemetry.presenceFailures.get()}" +
            "|tag=${tag ?: "-"}"
        Log.i(TAG, line)
        appendDiagnostic(context, "direct-presence.txt", line)
    }

    /** `6.0049`, or `none` before the first reading of a stream (a value of 0 means nothing was read). */
    private fun fpmfText(fpmf: Double?): String =
        if (fpmf == null || fpmf <= 0.0) "none" else "%.4f".format(java.util.Locale.ROOT, fpmf)

    /**
     * Says whether plan B (the native engine) is on, and what the sink last decided under it (B12a).
     *
     * `forced` is the persisted switch, off by default (the ExoPlayer pipeline is the PRISTINE producer);
     * `lastDecisionForced` is whether it was on at the sink's most recent producer decision, which lags
     * `forced` until the next track starts (the sink reads the switch only where it decides whether an
     * engine may exist). `producer` is `EXOPLAYER` by default and `NATIVE_FLAC` for a local PRISTINE FLAC
     * with plan B on. Written to a file as well as to logcat, like the other reports here. [tag] is echoed
     * back (`--es tag <nonce>`), so a script can tell this reading from an older line still in the log buffer.
     */
    private fun reportForceNativeEngine(context: Context, why: String, tag: String?) {
        val line = "FORCE_NATIVE_ENGINE|$why" +
            "|forced=${NativeEngineDebugSwitch.shared(context).isForced()}" +
            "|lastDecisionForced=${DirectPathTelemetry.debugForcedNativeEngine}" +
            "|producer=${DirectPathTelemetry.producer}" +
            "|tag=${tag ?: "-"}"
        Log.i(TAG, line)
        appendDiagnostic(context, "force-native-engine.txt", line)
    }

    /**
     * Reads the route authority and writes it to the app's own files directory.
     *
     * Written to a file rather than only to logcat because this device's OEM logging stack does not
     * surface app logs through `adb logcat`, and a physical transition matrix has to be read back
     * from the device rather than inferred. Read-only: it queries and writes, and changes no route.
     */
    private fun logAudioRoute(context: Context) {
        val route = DualPlayerEngineDebugAccess.route(context)
        val line = if (route == null) {
            "ROUTE|unavailable"
        } else {
            "ROUTE|desired=${route.desiredBackend}|actual=${route.actualBackend}" +
                "|transition=${route.transition}|reason=${route.reason}" +
                "|directActive=${route.isDirectActive}" +
                "|confirmedRate=${route.confirmedOutputRateHz ?: "none"}" +
                "|device=${route.deviceName ?: "none"}" +
                "|permission=${route.permissionGranted ?: "unknown"}" +
                "|error=${route.directError ?: "none"}"
        }
        Log.i(TAG, line)
        appendDiagnostic(context, "audio-route.txt", line)
    }

    /**
     * Drives the headphone Graphic EQ from the debug bridge, so the mode-transition and stability
     * gates can be scripted instead of tapped.
     *
     * Extras: `eq_enabled` (boolean) and `eq_gain_db` (int, dB on the lowest band).
     *
     * The three shapes this can produce are exactly the three the gates need:
     *
     * | extras | resulting state | what it exercises |
     * |---|---|---|
     * | `enabled=false` | switched off | the bypass path |
     * | `enabled=true, gain=0` | on, every band flat | an enabled EQ that must still be a true bypass |
     * | `enabled=true, gain≠0` | on, one band lifted | real filtering — the processed path |
     *
     * Only the lowest band is lifted, deliberately. A uniform gain across all bands would be a
     * broadband level change, which is indistinguishable from the volume stage and would let a
     * broken EQ pass an "the samples changed" check without any filter having run. One band lifted
     * is a peaking filter or nothing.
     *
     * Writes through [HeadphoneEqualizerCoordinator.updateEqualizer], the same call the profile
     * editor makes, so the applied-signal state the DSP reads changes exactly as a user edit would.
     */
    private fun setHeadphoneEq(context: Context, intent: Intent) {
        val enabled = intent.getBooleanExtra(EXTRA_EQ_ENABLED, false)
        val gainDb = intent.getIntExtra(EXTRA_EQ_GAIN_DB, 0).coerceIn(-12, 12)
        runCatching {
            val entry = EntryPointAccessors.fromApplication(
                context.applicationContext,
                DualPlayerEngineDebugEntryPoint::class.java,
            )
            val coordinator = entry.headphoneEqualizerCoordinator()
            entry.appScope().launch {
                runCatching {
                    coordinator.ensureProfileAuthority()
                    val profileId = coordinator.selectedProfileId()
                    if (profileId == null) {
                        Log.w(TAG, "EQ bridge: no selected profile")
                        return@launch
                    }
                    coordinator.updateEqualizer(profileId) { state ->
                        state.copy(
                            enabled = enabled,
                            bands10 = state.bands10.indices.map { band ->
                                if (band == 0) gainDb else 0
                            },
                            bands15 = state.bands15.indices.map { band ->
                                if (band == 0) gainDb else 0
                            },
                        )
                    }
                    Log.i(TAG, "EQ bridge: enabled=$enabled band0=${gainDb}dB")
                }.onFailure { Log.w(TAG, "EQ bridge write failed", it) }
            }
        }.onFailure { Log.w(TAG, "EQ bridge unavailable", it) }
    }

    /** Dumps the bounded hardware event log, newest first, to a file the harness can read back. */
    private fun logHardwareEvents(context: Context) {
        val events = HardwareEventLog.snapshot()
        val lines = buildList {
            add("EVENTS|count=${events.size}|dropped=${HardwareEventLog.droppedCount()}")
            events.forEach { event ->
                // Instrumentation: emit the raw monotonic millisecond stamp alongside the
                // human-readable label. `uptimeLabel` is only mm:ss, which cannot resolve a route
                // transition. Additive and debug-only — it changes what the diagnostic prints, not
                // what the engine does.
                add(
                    "EVENT|${event.elapsedRealtimeMs}|${event.uptimeLabel}|" +
                        "${event.type.name}|${event.detail ?: ""}"
                )
            }
        }
        lines.forEach { Log.i(TAG, it) }
        appendDiagnostic(context, "hardware-events.txt", lines.joinToString("\n"))
    }

    /**
     * Appends to a file under the app's external files directory, keeping only the most recent
     * [DIAGNOSTIC_FILE_MAX_LINES] lines so a long physical session cannot grow it without bound.
     *
     * External rather than internal because this device's OEM logging stack does not surface app
     * logs through `adb logcat`, and the external files directory is readable straight from a host
     * with no `run-as` and no root. It is still app-private storage, removed with the app.
     */
    /**
     * Diagnostic only: fires the provocation the moment the natural STATE_ENDED window opens.
     *
     * The window is short, so a human cannot land inside it reliably; polling from the main looper
     * is what makes both arms take the same path at the same point in the state machine. It only
     * observes until the precondition is true, then runs the provocation and the Play trigger
     * back-to-back.
     */
    private fun armProvocation(context: Context) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val appContext = context.applicationContext
        var tries = 0
        var lastSeen = "none"
        val tick = object : Runnable {
            override fun run() {
                if (tries++ > PROVOCATION_POLL_MAX) {
                    Log.i(TAG, "ARM_PROVOCATION gave up after ${PROVOCATION_POLL_MAX} polls; lastSeen=$lastSeen")
                    return
                }
                // No `runCatching` swallow: a failure here must be visible, not indistinguishable
                // from "the window never opened". That mistake already cost a run.
                val engine = try {
                    DualPlayerEngineDebugAccess.engineInternal(appContext)
                } catch (t: Throwable) {
                    Log.w(TAG, "PROVOCATION access failed", t)
                    null
                }
                if (engine == null) {
                    Log.w(TAG, "ARM_PROVOCATION: no engine handle; aborting")
                    return
                }
                lastSeen = engine.diagnosticProvocationBrief()
                if (!engine.diagnosticProvocationReady()) {
                    if (tries % PROVOCATION_LOG_EVERY == 0) Log.i(TAG, "PROVOCATION_POLL|$lastSeen")
                    handler.postDelayed(this, PROVOCATION_POLL_MS)
                    return
                }
                Log.i(TAG, "PROVOCATION_WINDOW_OPEN|$lastSeen")
                // Provoke only. The operator performs the real UI trigger afterwards; the unlatched
                // offset persists until something latches it, so the two need not be simultaneous.
                val result = try {
                    engine.diagnosticProvokeOnly()
                } catch (t: Throwable) {
                    "PROVOCATION_FAILED ${t.javaClass.name}: ${t.message}"
                }
                Log.i(TAG, "PROVOCATION|$result")
            }
        }
        handler.post(tick)
    }

    private fun appendDiagnostic(context: Context, fileName: String, content: String) {
        runCatching {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, "diagnostics").apply { mkdirs() }
            val file = File(dir, fileName)
            val existing = if (file.isFile) file.readLines() else emptyList()
            val combined = existing + content.lines()
            file.writeText(combined.takeLast(DIAGNOSTIC_FILE_MAX_LINES).joinToString("\n"))
        }.onFailure { Log.w(TAG, "Could not write $fileName", it) }
    }

    companion object {
        const val ACTION_APPLY_HOME =
            "com.lostf1sh.pixelplayeross.debug.APPLY_PERSISTENT_HOME"
        const val ACTION_CLEAR_HOME =
            "com.lostf1sh.pixelplayeross.debug.CLEAR_PERSISTENT_HOME"
        const val ACTION_ENABLE_CONTAINMENT =
            "com.lostf1sh.pixelplayeross.debug.ENABLE_CONTAINMENT"
        const val ACTION_DISABLE_CONTAINMENT =
            "com.lostf1sh.pixelplayeross.debug.DISABLE_CONTAINMENT"
        const val ACTION_RESTORE_POLICY =
            "com.lostf1sh.pixelplayeross.debug.RESTORE_OWNER_POLICY"
        const val ACTION_START_USB_PAYLOAD_VALIDATION =
            "com.lostf1sh.pixelplayeross.debug.START_USB_PAYLOAD_VALIDATION"
        const val ACTION_STOP_USB_PAYLOAD_VALIDATION =
            "com.lostf1sh.pixelplayeross.debug.STOP_USB_PAYLOAD_VALIDATION"
        const val ACTION_USB_PAYLOAD_VALIDATION_STATUS =
            "com.lostf1sh.pixelplayeross.debug.USB_PAYLOAD_VALIDATION_STATUS"
        const val ACTION_REQUEST_USB_AUDIO_PERMISSION =
            "com.lostf1sh.pixelplayeross.debug.REQUEST_USB_AUDIO_PERMISSION"
        const val ACTION_USB_AUDIO_PERMISSION_RESULT =
            "com.lostf1sh.pixelplayeross.debug.USB_AUDIO_PERMISSION_RESULT"
        const val ACTION_REPORT_AUDIO_ROUTE =
            "com.lostf1sh.pixelplayeross.debug.REPORT_AUDIO_ROUTE"
        const val ACTION_PROBE_DAC_HID = "com.lostf1sh.pixelplayeross.debug.PROBE_DAC_HID"
        const val ACTION_DAC_HID_LISTEN = "com.lostf1sh.pixelplayeross.debug.DAC_HID_LISTEN"
        const val EXTRA_SECONDS = "seconds"
        const val DAC_HID_TAG = "DAC_HID"
        const val ACTION_DUMP_DAC_VOLUME_CONTROLS =
            "com.lostf1sh.pixelplayeross.debug.DUMP_DAC_VOLUME_CONTROLS"
        const val ACTION_REPORT_HARDWARE_EVENTS =
            "com.lostf1sh.pixelplayeross.debug.REPORT_HARDWARE_EVENTS"

        /**
         * Direct-path capture. Start clears the counters and begins one-sample-per-second
         * recording; Snapshot writes a full reading without ending the session; Stop finalises.
         *
         * Read the result back from
         * `Android/data/<pkg>/files/diagnostics/direct-path-capture.txt`.
         */
        const val ACTION_START_DIRECT_PATH_CAPTURE =
            "com.lostf1sh.pixelplayeross.debug.START_DIRECT_PATH_CAPTURE"
        const val ACTION_SNAPSHOT_DIRECT_PATH_CAPTURE =
            "com.lostf1sh.pixelplayeross.debug.SNAPSHOT_DIRECT_PATH_CAPTURE"
        const val ACTION_STOP_DIRECT_PATH_CAPTURE =
            "com.lostf1sh.pixelplayeross.debug.STOP_DIRECT_PATH_CAPTURE"

        /** Debug-only Graphic EQ control; see [setHeadphoneEq]. */
        const val ACTION_SET_HEADPHONE_EQ =
            "com.lostf1sh.pixelplayeross.debug.SET_HEADPHONE_EQ"
        const val ACTION_RESET_PCM_LEVEL =
            "com.lostf1sh.pixelplayeross.debug.RESET_PCM_LEVEL"

        /**
         * DIAGNOSTIC ONLY. Toggles the single-variable causal differential: whether the first mapping
         * latch after a position reset subtracts the new epoch's declared target instead of the
         * possibly-stale `initialPlayerPositionUs`. Default off; see
         * [DirectPathTelemetry.diagnosticRebaseOperandOnSeek].
         */
        const val ACTION_SET_REBASE_OPERAND =
            "com.lostf1sh.pixelplayeross.debug.SET_REBASE_OPERAND"

        /** Diagnostic only: one-line precondition reading for the control reconstruction. */
        const val ACTION_REPORT_CONTROL_PRECONDITION =
            "com.lostf1sh.pixelplayeross.debug.REPORT_CONTROL_PRECONDITION"

        /**
         * Diagnostic only: arms the controlled A/B provocation. Waits for the natural STATE_ENDED
         * window, unlatches the mapping offset through the production cleanup transition, then
         * invokes the ordinary Play entry and lets production code seek/configure/latch.
         *
         * `rebase_enabled` is the single differential variable between the two arms.
         */
        const val ACTION_ARM_PROVOCATION =
            "com.lostf1sh.pixelplayeross.debug.ARM_PROVOCATION"

        private const val PROVOCATION_POLL_MS = 40L
        private const val PROVOCATION_POLL_MAX = 750
        private const val PROVOCATION_LOG_EVERY = 25
        const val EXTRA_REBASE_ENABLED = "rebase_enabled"
        const val ACTION_SET_VOLUME =
            "com.lostf1sh.pixelplayeross.debug.SET_VOLUME"
        const val ACTION_SET_REPLAY_GAIN_FACTOR =
            "com.lostf1sh.pixelplayeross.debug.SET_REPLAY_GAIN_FACTOR"
        const val EXTRA_RG_FACTOR = "rg_factor"

        /**
         * B9-4: `--ez ignore true|false` makes this process read the Pure Direct lock (F-02) as off for the
         * requirements, so `SET_REPLAY_GAIN_FACTOR` can measure PROCESSED while the owner's lock is on. Not
         * persisted; a new process starts with it off. `tag` is echoed in the reply line.
         */
        const val ACTION_SET_PRISTINE_LOCK_OVERRIDE =
            "com.lostf1sh.pixelplayeross.debug.SET_PRISTINE_LOCK_OVERRIDE"
        const val EXTRA_IGNORE = "ignore"

        /**
         * Bit-perfect measurement (`B-bitperfect-medicion.md`): `--ez enabled true|false` sends
         * PRISTINE FLACs through the ExoPlayer pipeline instead of the native engine. Persisted, off by
         * default; the report action prints the current value.
         */
        const val ACTION_SET_FORCE_NATIVE_ENGINE =
            "com.lostf1sh.pixelplayeross.debug.SET_FORCE_NATIVE_ENGINE"
        const val ACTION_REPORT_FORCE_NATIVE_ENGINE =
            "com.lostf1sh.pixelplayeross.debug.REPORT_FORCE_NATIVE_ENGINE"
        /** Retired in B12a (the pipeline is the default); kept so an old script gets a warning. */
        const val ACTION_SET_PRISTINE_EXOPLAYER_PIPELINE =
            "com.lostf1sh.pixelplayeross.debug.SET_PRISTINE_EXOPLAYER_PIPELINE"
        const val ACTION_REPORT_PRISTINE_EXOPLAYER_PIPELINE =
            "com.lostf1sh.pixelplayeross.debug.REPORT_PRISTINE_EXOPLAYER_PIPELINE"
        /**
         * AUD-145: `--es mode poll|sleep` picks how the USB writer waits for a URB completion (poll(2) on
         * the usbfs fd, or the old 125 µs sleep). Not persisted; `tag` is echoed in the reply line.
         */
        const val ACTION_SET_USB_WAIT_MODE =
            "com.lostf1sh.pixelplayeross.debug.SET_USB_WAIT_MODE"
        /**
         * B10-2 (AUD-124 "shh"): `--es mode follow|nominal|clamp|auto` picks what the USB output does with the DAC's
         * feedback: follow (what it always did, the default), nominal (ignore it), clamp (within +-300 ppm of
         * nominal) or auto (follow, except while it is stuck). Not persisted; `tag` is echoed in the reply line.
         */
        const val ACTION_SET_RATE_SWITCH_PREROLL =
            "com.lostf1sh.pixelplayeross.debug.SET_RATE_SWITCH_PREROLL"
        const val ACTION_SET_DIRECT_PRESENCE =
            "com.lostf1sh.pixelplayeross.debug.SET_DIRECT_PRESENCE"
        const val ACTION_SET_FEEDBACK_POLICY =
            "com.lostf1sh.pixelplayeross.debug.SET_FEEDBACK_POLICY"
        const val ACTION_MARK_NOISE =
            "com.lostf1sh.pixelplayeross.debug.MARK_NOISE"
        const val EXTRA_MODE = "mode"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_MS = "ms"
        const val EXTRA_TAG = "tag"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_EQ_ENABLED = "eq_enabled"
        const val EXTRA_EQ_GAIN_DB = "eq_gain_db"
        const val EXTRA_LABEL = "label"
        const val EXTRA_EXPECTED_BYTES = "expected_bytes"
        private const val DIAGNOSTIC_FILE_MAX_LINES = 400
        private const val TAG = "DapDebugPolicyReceiver"
    }
}
