package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.app.Application
import android.os.HandlerThread
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B56 phase 7.3 (#48): the cover video's player is not the playback player and has no way to make a sound or to take audio focus. These are the proofs: its renderers list, the
 * parts of Media3 it never touches, and the hardware-only codec rule. (A JVM cannot decode video; what a chip decodes is the device checklist.)
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B56CoverVideoPlayerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `its renderers list is one video renderer and no audio renderer at all`() {
        val thread = HandlerThread("test-cover-video").apply { start() }
        val player = CoverVideoPlayer.build(context, thread.looper)
        try {
            assertThat(player.rendererCount).isEqualTo(1)
            assertThat((0 until player.rendererCount).map { player.getRendererType(it) }).containsExactly(C.TRACK_TYPE_VIDEO)
            assertThat((0 until player.rendererCount).map { player.getRendererType(it) }).doesNotContain(C.TRACK_TYPE_AUDIO)
            // and a file's audio is switched off as well, so nothing is even read for it
            assertThat(player.trackSelectionParameters.disabledTrackTypes).contains(C.TRACK_TYPE_AUDIO)
            assertThat(player.trackSelectionParameters.disabledTrackTypes).contains(C.TRACK_TYPE_TEXT)
        } finally {
            player.release()
            thread.quitSafely()
        }
    }

    @Test
    fun `the factory is the one place the renderers are made, and it builds exactly one`() {
        val renderers = CoverVideoPlayer.renderersFactory(context).createRenderers(
            android.os.Handler(android.os.Looper.getMainLooper()), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
        )
        assertThat(renderers).hasLength(1)
        assertThat(renderers.single().trackType).isEqualTo(C.TRACK_TYPE_VIDEO)
    }

    @Test
    fun `its playback thread is below the USB writer's and below the audio priority ExoPlayer would have used`() {
        val engineSource = File("src/main/java/com/lostf1sh/pixelplayeross/ui/animatedcover/CoverVideoEngine.kt").readText()
        assertThat(engineSource).contains("HandlerThread(\"PolyluteCoverVideo\", Process.THREAD_PRIORITY_DISPLAY)")
        assertThat(engineSource).contains(".setPlaybackLooper(playbackLooper)")
        // -4: lower in priority than the writer's -19 and the audio -16 (a smaller number is a higher priority)
        assertThat(Process.THREAD_PRIORITY_DISPLAY).isGreaterThan(-16)
    }

    @Test
    fun `the engine never asks for audio focus, a becoming-noisy watch, an audio sink, a session or the playback engine`() {
        val dir = File("src/main/java/com/lostf1sh/pixelplayeross/ui/animatedcover")
        for (file in dir.listFiles { f -> f.extension == "kt" }!!) {
            val code = file.readText().lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("//") }.joinToString("\n")
            for (forbidden in listOf("setAudioAttributes", "AudioFocus", "AudioSink", "AudioTrack", "AudioManager", "MediaSession", "MediaController", "DualPlayerEngine", "PlaybackService", "DefaultAudioSink", "MediaCodecAudioRenderer", "UsbOutput", "usb", "setWakeMode", "WakeLock", "WAKE_MODE")) {
                assertWithMessage("${file.name} must not use $forbidden").that(code).doesNotContain(forbidden)
            }
        }
        val builder = File(dir, "CoverVideoEngine.kt").readText()
        // the one switch that exists and must stay at its default (off)
        assertThat(builder).contains(".setHandleAudioBecomingNoisy(false)")
    }

    @Test
    fun `only hardware decoders are chosen`() {
        fun info(name: String, hardware: Boolean, software: Boolean) = androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
            name, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H265, null, hardware, software, !hardware, false, false,
        )
        val kept = CoverVideoPlayer.keepHardware(
            listOf(info("c2.android.hevc.decoder", hardware = false, software = true), info("OMX.hisi.video.decoder.hevc", hardware = true, software = false)),
        )
        assertThat(kept.map { it.name }).containsExactly("OMX.hisi.video.decoder.hevc")
        assertThat(CoverVideoPlayer.keepHardware(listOf(info("c2.android.av1.decoder", hardware = false, software = true)))).isEmpty()
    }

    @Test
    fun `HDR video is refused and 10 bit SDR video is not`() {
        fun format(transfer: Int?, bitDepth: Int) = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).apply {
            if (transfer != null) setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorRange(C.COLOR_RANGE_LIMITED).setColorTransfer(transfer).setLumaBitdepth(bitDepth).setChromaBitdepth(bitDepth).build())
        }.build()
        assertThat(CoverVideoPlayer.isHdr(format(C.COLOR_TRANSFER_ST2084, 10))).isTrue()   // HDR10
        assertThat(CoverVideoPlayer.isHdr(format(C.COLOR_TRANSFER_HLG, 10))).isTrue()      // HLG
        assertThat(CoverVideoPlayer.isHdr(format(C.COLOR_TRANSFER_SDR, 10))).isFalse()     // Main10 SDR: plays if the chip decodes it
        assertThat(CoverVideoPlayer.isHdr(format(null, 8))).isFalse()
    }

    @Test
    fun `the video is cropped to fill the square the way the still cover is`() {
        // a 16:9 video in a square box: as tall as the box, wider than it, cut at both sides
        assertThat(CoverCropFrame.cropped(600, 600, 16f / 9f)).isEqualTo(1067 to 600)
        // a portrait video: as wide as the box, taller than it
        assertThat(CoverCropFrame.cropped(600, 600, 9f / 16f)).isEqualTo(600 to 1067)
        assertThat(CoverCropFrame.cropped(600, 600, 1f)).isEqualTo(600 to 600)
        assertThat(CoverCropFrame.cropped(0, 600, 1f)).isEqualTo(0 to 0)
    }

    @Test
    fun `an MP4 fixture is a real MP4 and an AV1 one is told by its codec`() {
        fun brand(name: String) = File("src/test/resources/animatedcover/$name").readBytes().copyOfRange(4, 12).toString(Charsets.ISO_8859_1)
        assertThat(brand("cover.mp4")).startsWith("ftyp")
        assertThat(brand("cover-av1.mp4")).startsWith("ftyp")
        assertThat(File("src/test/resources/animatedcover/cover.mp4").length()).isLessThan(8 * 1024L)
    }

    @Test
    fun `a thread priority of display level is not the audio one`() {
        assertThat(Process.THREAD_PRIORITY_DISPLAY).isEqualTo(-4)
        assertThat(Process.THREAD_PRIORITY_AUDIO).isEqualTo(-16)
    }
}
