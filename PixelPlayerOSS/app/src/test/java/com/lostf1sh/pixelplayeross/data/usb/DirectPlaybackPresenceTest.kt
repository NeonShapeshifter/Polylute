package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.DirectPathTelemetry
import com.decent.usbaudio.media3.DirectPlaybackPresence
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * F-10 / B12a-3: while USB Direct plays, Android must see music playing — a silent `AudioTrack` that exists only
 * while Direct is really playing. The lifecycle is checked here against a fake platform and a virtual clock; what
 * the Android half is made of (silent, speaker-pinned, looped, volume 0, no focus) is pinned from its source,
 * and `dumpsys audio` on the device is the reviewer's check.
 */
class DirectPlaybackPresenceTest {

    /** The platform and the clock, virtual: tasks run when the test says so, in order, at their due time. */
    private class Rig(
        private val noSpeaker: Boolean = false,
        private val startFails: Boolean = false,
        enabled: Boolean = true,
        private val factoryThrows: Boolean = false,
    ) {
        var now = 0L
        var creations = 0
        var starts = 0
        var releases = 0
        private var current: Fake? = null
        private val tasks = ArrayList<Pair<Long, Runnable>>()

        private val scheduler = object : DirectPlaybackPresence.PresenceScheduler {
            override fun post(task: Runnable) { tasks += now to task }
            override fun postDelayed(task: Runnable, delayMs: Long) { tasks += (now + delayMs) to task }
            override fun cancel(task: Runnable) { tasks.removeAll { it.second === task } }
        }

        private inner class Fake : DirectPlaybackPresence.SilentPlayback {
            var unsafeRoute: ((String) -> Unit)? = null
            override fun start(onUnsafeRoute: (String) -> Unit): Boolean {
                unsafeRoute = onUnsafeRoute
                if (startFails) return false
                starts++
                return true
            }
            override fun release() { releases++ }
        }

        val presence = DirectPlaybackPresence(
            playbackFactory = {
                creations++
                if (factoryThrows) throw IllegalStateException("the platform is down")
                if (noSpeaker) null else Fake().also { current = it }
            },
            scheduler = scheduler,
            enabled = { enabled },
            nowMs = { now },
        )

        /** Runs every task that is due, in the order it was posted. */
        fun run() {
            while (true) {
                val next = tasks.firstOrNull { it.first <= now } ?: return
                tasks.remove(next)
                next.second.run()
            }
        }

        fun advance(ms: Long) {
            now += ms
            run()
        }

        fun routeAwayFromSpeaker(reason: String) {
            current!!.unsafeRoute!!.invoke(reason)
        }
    }

    @Test
    fun `it starts only while the user is playing and the USB stream is running`() {
        val rig = Rig()
        rig.presence.update(playing = false, streamAlive = false); rig.run()
        rig.presence.update(playing = true, streamAlive = false); rig.run()
        rig.presence.update(playing = false, streamAlive = true); rig.run()
        assertThat(rig.starts).isEqualTo(0)
        rig.presence.update(playing = true, streamAlive = true); rig.run()
        assertThat(rig.starts).isEqualTo(1)
        assertThat(rig.presence.isActive).isTrue()
    }

    @Test
    fun `it stops at once on a pause, and starts again on the next play with a clean slate`() {
        val rig = Rig()
        rig.presence.update(true, true); rig.run()
        assertThat(rig.presence.isActive).isTrue()
        rig.presence.update(false, true); rig.run()
        assertThat(rig.presence.isActive).isFalse()
        assertThat(rig.releases).isEqualTo(1)
        rig.presence.update(true, true); rig.run()
        assertThat(rig.starts).isEqualTo(2)
        assertThat(rig.presence.isActive).isTrue()
    }

    @Test
    fun `it stops when the USB stream is not running, after a grace that rides through a rate change`() {
        val rig = Rig()
        rig.presence.update(true, true); rig.run()
        // a rate change releases and recreates the stream within ~100 ms: the track stays
        rig.presence.update(true, false); rig.run()
        rig.advance(DirectPlaybackPresence.STREAM_GAP_GRACE_MS - 1)
        rig.presence.update(true, true); rig.run()
        rig.advance(DirectPlaybackPresence.STREAM_GAP_GRACE_MS * 2)
        assertThat(rig.presence.isActive).isTrue()
        assertThat(rig.starts).isEqualTo(1)
        assertThat(rig.releases).isEqualTo(0)
        // a stream that stays gone ends it
        rig.presence.update(true, false); rig.run()
        rig.advance(DirectPlaybackPresence.STREAM_GAP_GRACE_MS)
        assertThat(rig.presence.isActive).isFalse()
        assertThat(rig.releases).isEqualTo(1)
    }

    @Test
    fun `a pause during the grace still stops it at once, and the grace does not stop a resumed stream later`() {
        val rig = Rig()
        rig.presence.update(true, true); rig.run()
        rig.presence.update(true, false); rig.run()
        rig.presence.update(false, false); rig.run()
        assertThat(rig.presence.isActive).isFalse()
        rig.presence.update(true, true); rig.run()
        rig.advance(DirectPlaybackPresence.STREAM_GAP_GRACE_MS * 3)   // the old grace timer must be gone
        assertThat(rig.presence.isActive).isTrue()
    }

    @Test
    fun `it is released with the sink`() {
        val rig = Rig()
        rig.presence.update(true, true); rig.run()
        rig.presence.release(); rig.run()
        assertThat(rig.presence.isActive).isFalse()
        assertThat(rig.releases).isEqualTo(1)
    }

    @Test
    fun `with no speaker to pin to it is never started, and it does not retry on every update`() {
        val rig = Rig(noSpeaker = true)
        repeat(5) { rig.presence.update(true, true); rig.run() }
        assertThat(rig.presence.isActive).isFalse()
        assertThat(rig.creations).isEqualTo(1)                     // not once per update
        assertThat(DirectPathTelemetry.presenceFailures.get()).isAtLeast(1L)
        rig.advance(DirectPlaybackPresence.RETRY_AFTER_FAILURE_MS)
        rig.presence.update(false, false); rig.run()
        rig.presence.update(true, true); rig.run()
        assertThat(rig.creations).isEqualTo(2)                     // after the back-off, it tries again
    }

    @Test
    fun `a start the platform refuses leaves nothing behind`() {
        val rig = Rig(startFails = true)
        rig.presence.update(true, true); rig.run()
        assertThat(rig.presence.isActive).isFalse()
        assertThat(rig.releases).isEqualTo(1)                       // the half-made track is released
    }

    @Test
    fun `a route to anything but the speaker stops it and it stays off for that playback`() {
        val rig = Rig()
        rig.presence.update(true, true); rig.run()
        rig.routeAwayFromSpeaker("routed to device type 22, not the built-in speaker"); rig.run()
        assertThat(rig.presence.isActive).isFalse()
        assertThat(rig.releases).isEqualTo(1)
        // more updates of the same playback do not bring it back onto an unsafe route
        rig.presence.update(true, true); rig.run()
        rig.presence.update(true, false); rig.run()
        rig.presence.update(true, true); rig.run()
        assertThat(rig.starts).isEqualTo(1)
        // the next playback starts clean
        rig.presence.update(false, true); rig.run()
        rig.presence.update(true, true); rig.run()
        assertThat(rig.starts).isEqualTo(2)
    }

    @Test
    fun `the process override turns it off and nothing starts`() {
        val rig = Rig(enabled = false)
        rig.presence.update(true, true); rig.run()
        assertThat(rig.presence.isActive).isFalse()
        assertThat(rig.creations).isEqualTo(0)
    }

    @Test
    fun `a failing platform never throws into playback`() {
        val rig = Rig(factoryThrows = true)
        rig.presence.update(true, true); rig.run()
        rig.presence.update(false, true); rig.run()
        assertThat(rig.presence.isActive).isFalse()
    }

    // ── What the Android half is made of ─────────────────────────────────────────────────────────────

    private val source = File("../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/DirectPlaybackPresence.kt")
        .readText().replace("\r\n", "\n")
    /** The presence's code without its comments, which are allowed to name what the code must not touch. */
    private val code = source.lines()
        .filterNot { l -> l.trim().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") } }
        .joinToString("\n")
    private val sink = File("../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt")
        .readText().replace("\r\n", "\n")

    @Test
    fun `the silent track is media, silent, speaker-pinned, looped and cheap`() {
        val platform = source.substringAfter("internal class AndroidSilentPlayback")
        assertThat(platform).contains("setUsage(AudioAttributes.USAGE_MEDIA)")
        assertThat(platform).contains("setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)")
        assertThat(platform).contains("track.setVolume(PRESENCE_TRACK_VOLUME)")
        assertThat(platform).contains("const val PRESENCE_TRACK_VOLUME = 0f")
        assertThat(platform).contains("val zeros = ShortArray(")                 // silence, never a sample of anything
        // the only thing ever written to it is those zeros
        assertThat(Regex("""track\.write\(""").findAll(platform).count()).isEqualTo(1)
        assertThat(platform).contains("track.write(zeros, 0, zeros.size)")
        assertThat(platform).contains("track.setLoopPoints(0, zeros.size, -1)")   // the platform loops it: no writer thread
        assertThat(platform).contains("AudioTrack.MODE_STATIC")
        assertThat(platform).contains("AudioTrack.PERFORMANCE_MODE_POWER_SAVING")
        assertThat(platform).contains("const val RATE_HZ = 8_000")
        assertThat(platform).contains("AudioDeviceInfo.TYPE_BUILTIN_SPEAKER")
        assertThat(platform).contains("track.preferredDevice = speaker")
        assertThat(platform).contains("if (speaker == null) {\n                null")     // never started unpinned
        // the routing check, at start and on every routing change
        assertThat(platform).contains("addOnRoutingChangedListener")
        assertThat(platform).contains("routed.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER")
    }

    @Test
    fun `the presence asks for no audio focus and says nothing to the media session`() {
        for (forbidden in listOf("requestAudioFocus", "AudioFocusRequest", "MediaSession", "abandonAudioFocus", "setSessionId")) {
            assertThat(code).doesNotContain(forbidden)
        }
    }

    @Test
    fun `the DAC path is untouched - the presence has no USB, no stream and no sink`() {
        for (forbidden in listOf("UsbAudioStream", "UsbAudioDevice", "usbfs", "enqueueRaw", "UsbStreamingThread", "NativeAudioEngine")) {
            assertThat(code).doesNotContain(forbidden)
        }
    }

    @Test
    fun `the sink tells the presence after it has dealt with the DAC, at every play, pause, stream and release`() {
        assertThat(sink).contains("DirectPlaybackPresence.forContext(context)")
        val play = sink.substringAfter("override fun play() {").substringBefore("\n    }\n")
        assertThat(play.indexOf("usbStreamingThread?.resumeStreaming()")).isLessThan(play.indexOf("updatePresence()"))
        val pause = sink.substringAfter("override fun pause() {").substringBefore("\n    }\n")
        assertThat(pause.indexOf("pauseStreamingThread()")).isLessThan(pause.indexOf("updatePresence()"))
        assertThat(pause).contains("isPlaying = false")
        assertThat(sink.substringAfter("override fun release() {").substringBefore("\n    }\n")).contains("presence?.release()")
        assertThat(sink.substringAfter("private fun releaseUsbStream() {").substringBefore("\n    }\n")).contains("updatePresence()")
        // started with the stream
        assertThat(sink).contains("usbAudioStream = stream\n        currentSampleRate = sampleRate\n        currentChannelCount = channelCount\n        muteDelegateIfNeeded()\n        updatePresence()")
        // and only for a Direct sink the config wants it for
        assertThat(sink).contains("if (config.directPresence && config.directEnabled) DirectPlaybackPresence.forContext(context) else null")
    }

    @Test
    fun `the debug bridge can turn it off for a process and reports it, and release builds have no such action`() {
        val receiver = File("src/debug/java/com/lostf1sh/pixelplayeross/dap/admin/DapDebugPolicyReceiver.kt").readText()
        val debugManifest = File("src/debug/AndroidManifest.xml").readText()
        val mainManifest = File("src/main/AndroidManifest.xml").readText()
        assertThat(debugManifest).contains("com.lostf1sh.pixelplayeross.debug.SET_DIRECT_PRESENCE")
        assertThat(mainManifest).doesNotContain("DIRECT_PRESENCE")
        assertThat(receiver).contains("ACTION_SET_DIRECT_PRESENCE ->")
        assertThat(receiver).contains("SET_DIRECT_PRESENCE needs --ez enabled true|false; unchanged")
        assertThat(receiver).contains("DIRECT_PRESENCE|enabled=")
        // process-only: nothing persisted
        val action = receiver.substringAfter("ACTION_SET_DIRECT_PRESENCE ->").substringBefore("private fun reportReplayGainFactor")
        for (persisted in listOf("SharedPreferences", "DataStore", ".edit", "putBoolean")) assertThat(action).doesNotContain(persisted)
    }
}
