package com.decent.usbaudio.media3

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * F-10 (B12a-3): while USB Direct plays, **Android must see music playing**.
 *
 * In Direct the PCM goes through usbfs and none of the app's `AudioTrack`s is started, so for Android
 * *nothing is playing*: `dumpsys audio` shows every player of the app released and `AudioManager.isMusicActive()`
 * is false. Measured on the HONOR: with the screen off in Direct a Volume Down press never reached the app,
 * because the MagicOS flashlight gesture takes the key when no music is active (in PROCESSED, on the Android
 * route, it worked). The same blind spot hits whatever else keys on "music is active": ducking, Do Not
 * Disturb heuristics, and the choice of which app's media session gets the hardware keys.
 *
 * The fix is to give Android what it looks for and nothing else: **one silent `AudioTrack`** that exists only
 * while Direct is really playing.
 * - It **cannot sound**: 16-bit mono zeros, looped from a static buffer, track volume 0.
 * - It **cannot reach the DAC or another output**: it is pinned to the built-in speaker, and a routing
 *   listener stops it the moment the platform routes it anywhere else (a USB device, a Bluetooth headset);
 *   with no speaker to pin to it is never started. The DAC path (usbfs, the sink's thread) is not touched.
 * - It **costs next to nothing**: 8 kHz, a 100 ms static buffer, `PERFORMANCE_MODE_POWER_SAVING`, no writer
 *   thread (the platform loops the buffer), created when playback starts and released when it stops.
 * - It **stops** on pause, on stop/release, and when the USB stream is not running (after a short grace, so
 *   the ~100 ms rebuild of a rate change does not recreate the track).
 * - It takes **no audio focus** (an `AudioTrack` never does; focus stays with ExoPlayer's `AudioFocusManager`)
 *   and says nothing to the `MediaSession`, whose state is the player's.
 *
 * The decision and the lifecycle are plain Kotlin behind two seams ([SilentPlayback], [PresenceScheduler]) so
 * they are unit-tested without Android; [AndroidSilentPlayback] is the only part that needs the platform.
 * Nothing here may break playback: every platform call is guarded, and a start that fails is retried only
 * after [RETRY_AFTER_FAILURE_MS].
 */
class DirectPlaybackPresence(
    private val playbackFactory: () -> SilentPlayback?,
    private val scheduler: PresenceScheduler,
    private val enabled: () -> Boolean = { processOverride ?: true },
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    /**
     * One silent playback's lifetime. [start] false means it could not be made safe (no speaker, a failure).
     * Public so a host's tests can stand in for the platform; production uses [AndroidSilentPlayback].
     */
    interface SilentPlayback {
        /** Starts it. [onUnsafeRoute] is called, from any thread, if the platform routes it away from the speaker. */
        fun start(onUnsafeRoute: (String) -> Unit): Boolean
        fun release()
    }

    /** Where the presence does its platform work: one thread, in order, with a delay. */
    interface PresenceScheduler {
        fun post(task: Runnable)
        fun postDelayed(task: Runnable, delayMs: Long)
        fun cancel(task: Runnable)
    }

    @Volatile
    private var wantPlaying = false

    @Volatile
    private var streamAlive = false

    // Everything below is touched only on the scheduler's thread.
    private var playback: SilentPlayback? = null
    private var unsafeRouteSeen = false
    private var failedAtMs = NEVER
    private var graceTask: Runnable? = null
    private val reconcileTask = Runnable { reconcile() }

    /** True while a silent playback is running. For diagnostics only. */
    @Volatile
    var isActive: Boolean = false
        private set

    /**
     * What the sink knows: whether the user is playing through Direct ([playing]) and whether the USB stream
     * is running ([streamAlive]). Cheap and safe from any thread; the platform work happens on the scheduler.
     */
    fun update(playing: Boolean, streamAlive: Boolean) {
        if (wantPlaying == playing && this.streamAlive == streamAlive && !(playing && streamAlive && !isActive)) return
        wantPlaying = playing
        this.streamAlive = streamAlive
        runCatching { scheduler.post(reconcileTask) }
    }

    /** The sink is released: stop now and forget everything. */
    fun release() {
        wantPlaying = false
        streamAlive = false
        runCatching { scheduler.post(reconcileTask) }
    }

    private fun reconcile() {
        try {
            when {
                !enabled() -> stop("disabled")
                !wantPlaying -> {
                    unsafeRouteSeen = false          // the next playback starts clean
                    stop("paused or stopped")
                }
                streamAlive -> {
                    cancelGrace()
                    if (playback == null && !unsafeRouteSeen) start()
                }
                else -> if (playback != null && graceTask == null) {
                    val task = Runnable {
                        graceTask = null
                        if (wantPlaying && !streamAlive) stop("USB stream not running")
                    }
                    graceTask = task
                    scheduler.postDelayed(task, STREAM_GAP_GRACE_MS)
                }
            }
        } catch (t: Throwable) {
            // Never break playback for a courtesy to the platform.
            Log.w(TAG, "presence reconcile failed: ${t.message}")
        }
    }

    private fun start() {
        val failedAt = failedAtMs
        if (failedAt != NEVER && nowMs() - failedAt < RETRY_AFTER_FAILURE_MS) return
        val made = playbackFactory()
        val started = made != null && made.start { reason -> scheduler.post(Runnable { onUnsafeRoute(made, reason) }) }
        if (!started) {
            made?.release()
            failedAtMs = nowMs()
            DirectPathTelemetry.presenceFailures.incrementAndGet()
            Log.w(TAG, "Direct presence not started (${if (made == null) "no safe output" else "start failed"}); " +
                "retry in ${RETRY_AFTER_FAILURE_MS / 1000} s")
            return
        }
        failedAtMs = NEVER
        playback = made
        isActive = true
        DirectPathTelemetry.presenceActive = true
        DirectPathTelemetry.presenceStarts.incrementAndGet()
        Log.i(TAG, "Direct presence started: a silent track on the speaker, so Android sees music playing")
    }

    private fun onUnsafeRoute(source: SilentPlayback, reason: String) {
        if (playback !== source) return
        unsafeRouteSeen = true
        Log.w(TAG, "Direct presence stopped: $reason")
        stop("unsafe route")
    }

    private fun stop(why: String) {
        cancelGrace()
        val current = playback ?: return
        playback = null
        isActive = false
        DirectPathTelemetry.presenceActive = false
        runCatching { current.release() }
        DirectPathTelemetry.presenceStops.incrementAndGet()
        Log.i(TAG, "Direct presence stopped ($why)")
    }

    private fun cancelGrace() {
        graceTask?.let { scheduler.cancel(it) }
        graceTask = null
    }

    companion object {
        private const val TAG = "DirectPresence"
        private const val NEVER = Long.MIN_VALUE

        /** A rate change releases and re-creates the USB stream in ~100 ms; the track rides through it. */
        const val STREAM_GAP_GRACE_MS = 1_500L

        /** After a failed start (no speaker, a platform error) do not try again on every state update. */
        const val RETRY_AFTER_FAILURE_MS = 30_000L

        /**
         * Process-wide DEBUG override (`SET_DIRECT_PRESENCE`), not persisted: null leaves it on (the default),
         * false turns it off to compare `dumpsys audio` and the battery with and without it.
         */
        @Volatile
        var processOverride: Boolean? = null

        /** A presence that does its platform work on a shared low-priority thread, started on first use. */
        fun forContext(context: Context): DirectPlaybackPresence =
            DirectPlaybackPresence(
                playbackFactory = { AndroidSilentPlayback.create(context.applicationContext) },
                scheduler = SharedThreadScheduler,
            )
    }
}

/** One thread for every sink's presence work, created when the first presence is used. */
internal object SharedThreadScheduler : DirectPlaybackPresence.PresenceScheduler {
    private val handler: Handler by lazy {
        val thread = HandlerThread("DirectPresence", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        Handler(thread.looper)
    }

    override fun post(task: Runnable) {
        handler.post(task)
    }

    override fun postDelayed(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }

    override fun cancel(task: Runnable) {
        handler.removeCallbacks(task)
    }
}

/**
 * The platform half: a looping silent static `AudioTrack` pinned to the built-in speaker, with a routing
 * listener that gives it up if it is ever routed elsewhere. The numbers are the cheapest that work.
 */
internal class AndroidSilentPlayback private constructor(
    private val track: AudioTrack,
) : DirectPlaybackPresence.SilentPlayback {

    private var listener: AudioRouting.OnRoutingChangedListener? = null

    override fun start(onUnsafeRoute: (String) -> Unit): Boolean = try {
        val routingListener = AudioRouting.OnRoutingChangedListener { routing ->
            val routed = routing.routedDevice
            // null: not routed yet (the first callback follows play()); anything but the speaker is unsafe.
            if (routed != null && routed.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                onUnsafeRoute("routed to device type ${routed.type}, not the built-in speaker")
            }
        }
        listener = routingListener
        track.addOnRoutingChangedListener(routingListener, null)
        track.play()
        val routed = track.routedDevice
        if (routed != null && routed.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            onUnsafeRoute("routed to device type ${routed.type}, not the built-in speaker")
            false
        } else {
            track.playState == AudioTrack.PLAYSTATE_PLAYING
        }
    } catch (t: Throwable) {
        Log.w(TAG, "silent track start failed: ${t.message}")
        false
    }

    override fun release() {
        runCatching { listener?.let { track.removeOnRoutingChangedListener(it) } }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    companion object {
        private const val TAG = "DirectPresence"

        /** The lowest rate that works everywhere: no resampling cost worth the name, a few kB of buffer. */
        const val RATE_HZ = 8_000

        /** 100 ms of zeros, looped by the platform: no writer thread, no wake-ups of ours. */
        const val LOOP_FRAMES = 800

        /**
         * The track's own volume. 0 on top of the zeros, so it cannot sound even if the data were ever not silent. If a
         * phone's "music is active" check turns out to ignore a muted player, this is the one knob to try first.
         */
        const val PRESENCE_TRACK_VOLUME = 0f

        /** Null when there is no built-in speaker to pin to (never start unpinned) or the track cannot be made. */
        fun create(context: Context): AndroidSilentPlayback? = try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker == null) {
                null
            } else {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
                val minBytes = AudioTrack.getMinBufferSize(RATE_HZ, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bytes = maxOf(minBytes, LOOP_FRAMES * 2)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(bytes)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                    .build()
                try {
                    track.setVolume(PRESENCE_TRACK_VOLUME)
                    track.preferredDevice = speaker
                    val zeros = ShortArray(bytes / 2)       // silence, written once; the platform loops it
                    track.write(zeros, 0, zeros.size)
                    track.setLoopPoints(0, zeros.size, -1)
                    AndroidSilentPlayback(track)
                } catch (t: Throwable) {
                    track.release()
                    throw t
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "silent track could not be made: ${t.message}")
            null
        }
    }
}
