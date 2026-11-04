package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * B56 phase 7.3 (#48): the cover video's player. **It is not the playback player.** It is a second Media3 `ExoPlayer`, built here with **no audio renderer at all** (see
 * [CoverVideoPlayer.renderersFactory]: the list has one entry, a video renderer), so it has no `AudioTrack`, no `AudioSink`, and nothing in it to ask for audio focus (focus is
 * only requested when `setAudioAttributes(attributes, true)` is called, and it never is) or to watch for the output becoming noisy (`setHandleAudioBecomingNoisy` stays at its default,
 * false). It is not a `MediaSession` player and no session or controller is ever given it.
 *
 * **Threads.** The main thread owns the view and the listener. The player's playback thread is a [HandlerThread] of this class's own, at `THREAD_PRIORITY_DISPLAY` (-4), **below**
 * the USB writer's (-19): ExoPlayer's own thread would have run at the audio priority (-16), above video work that has no deadline an ear can hear. That thread only moves buffers
 * between the extractor and the codec, and renders them to the surface at their time. The loader thread reads the file. The decoding itself is the hardware codec's, on the SoC.
 *
 * **No CPU frame copies.** The codec is hardware only ([CoverVideoPlayer.hardwareOnly]) and renders straight to a `TextureView`'s surface: a frame is never a `Bitmap` or a
 * `ByteArray` the CPU touches. A cover whose codec has no hardware decoder here (AV1 on the HONOR, or a profile the chip lacks) does not play: the still cover stays.
 */
@OptIn(UnstableApi::class)
internal class CoverVideoEngine(
    context: Context,
    private val cover: AnimatedCover,
    private val onFailed: (String) -> Unit,
) : AnimatedCoverEngine {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("PolyluteCoverVideo", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val frame = CoverCropFrame(context)
    private val first = MutableStateFlow(false)
    private var player: ExoPlayer? = CoverVideoPlayer.build(context, thread.looper)
    private var released = false

    override val view: View get() = frame
    override val firstFrame: StateFlow<Boolean> = first.asStateFlow()

    /** If no frame arrives in this long after the file is handed to the codec, the cover gives up (a codec that never answers must not hold the player's cover up). */
    private val noFrame = Runnable { fail("no frame within ${NO_FRAME_TIMEOUT_MS} ms") }

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            main.removeCallbacks(noFrame)
            first.value = true
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                frame.setAspect(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
            if (video == null) {
                if (tracks.groups.isNotEmpty()) fail("no video track")
                return
            }
            val format = video.getTrackFormat(0)
            if (!video.isSupported) {
                fail("no hardware decoder for ${format.sampleMimeType} ${format.codecs.orEmpty()}")
            } else if (CoverVideoPlayer.isHdr(format)) {
                // HDR (PQ or HLG) on a window that is not HDR shows flat; the cover is not worth a wrong picture
                fail("HDR video (${format.sampleMimeType})")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            fail("player error ${error.errorCodeName}")
        }
    }

    init {
        val p = player!!
        p.setVideoTextureView(frame.texture)
        p.addListener(listener)
        p.setMediaItem(MediaItem.fromUri(cover.uri))
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.volume = 0f
        // paused: the codec draws the first frame and waits
        p.playWhenReady = false
        p.prepare()
        main.postDelayed(noFrame, NO_FRAME_TIMEOUT_MS)
    }

    /** Reported from a later turn of the main thread, never from inside the player's own callback: the controller answers by releasing this very player. */
    private fun fail(reason: String) {
        main.post { if (!released) onFailed(reason) }
    }

    override fun setRunning(running: Boolean) {
        player?.playWhenReady = running
    }

    override fun release() {
        if (released) return
        released = true
        main.removeCallbacks(noFrame)
        player?.let {
            it.removeListener(listener)
            it.clearVideoTextureView(frame.texture)
            it.release()
        }
        player = null
        thread.quitSafely()
    }

    /** For the tests: the player this engine owns (null once released). */
    internal val playerForTest: ExoPlayer? get() = player

    internal companion object {
        const val NO_FRAME_TIMEOUT_MS = 4_000L
    }
}

/** Builds the cover video's `ExoPlayer`. One place, so the test of "no audio renderer" is a test of the player the app really builds. */
@OptIn(UnstableApi::class)
internal object CoverVideoPlayer {
    /** Hardware decoders only: a software codec would copy every frame through the CPU, which is what the USB writer cannot be asked to share with. */
    val hardwareOnly = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        keepHardware(MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder))
    }

    fun keepHardware(infos: List<MediaCodecInfo>): List<MediaCodecInfo> = infos.filter { it.hardwareAccelerated && !it.softwareOnly }

    /** **One video renderer and nothing else**: no audio, no text, no metadata, no camera motion. */
    fun renderersFactory(context: Context): RenderersFactory = RenderersFactory { handler, videoListener, _, _, _ ->
        arrayOf<Renderer>(
            MediaCodecVideoRenderer.Builder(context)
                .setMediaCodecSelector(hardwareOnly)
                .setEnableDecoderFallback(false)
                .setEventHandler(handler)
                .setEventListener(videoListener)
                .setMaxDroppedFramesToNotify(5)
                .build()
        )
    }

    /**
     * MP4 only (the file is `cover.mp4`), read from the file system before Android 13 and through the folder the listener chose (a `content://` document) after it. The default
     * source opens both; the app hands it only `file://` and `content://` addresses, made by the finder.
     */
    private val extractors = ExtractorsFactory { arrayOf<Extractor>(Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED), FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED)) }

    fun build(context: Context, playbackLooper: Looper): ExoPlayer =
        ExoPlayer.Builder(context.applicationContext, renderersFactory(context.applicationContext))
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context.applicationContext), extractors))
            .setPlaybackLooper(playbackLooper)
            // a few seconds of a few-MB file, not the default's fifty
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 3_000, 250, 500).build())
            .setHandleAudioBecomingNoisy(false)
            .build()
            .also { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }

    /** PQ and HLG are the HDR transfers; SDR video of 10 bits (Main10) is not HDR and plays when the chip decodes it. */
    fun isHdr(format: androidx.media3.common.Format): Boolean {
        val transfer = format.colorInfo?.colorTransfer ?: return false
        return transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
    }
}

/** The video drawn to fill the cover's square, cropped at the middle (the way the still cover is cropped): a `TextureView` sized to the video's aspect and clipped by this frame. */
internal class CoverCropFrame(context: Context) : FrameLayout(context) {
    val texture = TextureView(context)
    private var aspect = 1f

    init {
        clipChildren = true
        clipToPadding = true
        addView(texture, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun setAspect(value: Float) {
        if (value > 0f && value != aspect) {
            aspect = value
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val (cw, ch) = cropped(w, h, aspect)
        texture.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val (cw, ch) = cropped(w, h, aspect)
        val left = (w - cw) / 2
        val top = (h - ch) / 2
        texture.layout(left, top, left + cw, top + ch)
    }

    internal companion object {
        /** The size a [aspect] (width over height) picture takes to cover a [w] x [h] box exactly, cropped at the middle. */
        fun cropped(w: Int, h: Int, aspect: Float): Pair<Int, Int> {
            if (w <= 0 || h <= 0) return 0 to 0
            val boxAspect = w.toFloat() / h
            return if (aspect >= boxAspect) (h * aspect).roundToInt() to h else w to (w / aspect).roundToInt()
        }
    }
}
