package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B56 phase 7.2 (#48): `cover.webp` and `cover.gif`, played by the platform's `ImageDecoder` / `AnimatedImageDrawable`.
 *
 * **Decoded at the size it is shown** ([targetSide]): the decoder scales every frame to the cover's size as it decodes (the 21.7 MB, 15 s GIF of the reviewer's files is not held at
 * its own size), and one engine exists at a time ([AnimatedCoverController]). The file is opened and its first frame decoded **off the main thread** ([decode]); frames after
 * the first are decoded by the platform's own animation thread and drawn by the render thread, never by the main thread.
 *
 * These formats have no hardware decoder: their frames are decoded by the CPU (Skia), unlike the MP4's. That is why the MP4 is the file that wins when a folder has several, why a
 * file over [MAX_BYTES] is not played, and why the whole feature is off by default on a DAP.
 */
internal class CoverImageEngine(
    context: Context,
    private val cover: AnimatedCover,
    private val targetSide: Int,
    private val scope: CoroutineScope,
    private val onFailed: (String) -> Unit,
    private val decodeOn: CoroutineDispatcher = Dispatchers.Default,
) : AnimatedCoverEngine {
    private val appContext: Context = context.applicationContext ?: context
    private val imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val first = MutableStateFlow(false)
    private var running = false
    private var released = false
    private var drawable: Drawable? = null
    private val job: Job

    override val view: View get() = imageView
    override val firstFrame: StateFlow<Boolean> = first.asStateFlow()

    init {
        job = scope.launch {
            try {
                val decoded = withContext(decodeOn) { decode(appContext, cover, targetSide) }
                if (released) return@launch
                drawable = decoded
                imageView.setImageDrawable(decoded)
                if (decoded is AnimatedImageDrawable) {
                    decoded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    if (running) decoded.start()
                }
                first.value = true
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (!released) onFailed("could not decode: ${t.javaClass.simpleName}")
            }
        }
    }

    override fun setRunning(running: Boolean) {
        this.running = running
        (drawable as? AnimatedImageDrawable)?.let { if (running) it.start() else it.stop() }
    }

    override fun release() {
        if (released) return
        released = true
        job.cancel()
        (drawable as? AnimatedImageDrawable)?.stop()
        imageView.setImageDrawable(null)
        drawable = null
    }

    companion object {
        /** A file bigger than this is not played (the reviewer's biggest animated file is 35.7 MB). */
        const val MAX_BYTES = 64L * 1024 * 1024

        /** The size a [width] x [height] picture is decoded at so that its longer side is at most [side]; never larger than it is. */
        fun decodeSize(width: Int, height: Int, side: Int): Pair<Int, Int> {
            val longer = max(width, height)
            if (longer <= 0 || side <= 0 || longer <= side) return width to height
            val scale = side.toFloat() / longer
            return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
        }

        internal fun decode(file: File, side: Int): Drawable =
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val (w, h) = decodeSize(info.size.width, info.size.height, side)
                decoder.setTargetSize(w, h)
            }

        /** The cover as the engine reads it: the plain file, or the document in the chosen folder (B57-fix1 4). */
        internal fun decode(context: Context, cover: AnimatedCover, side: Int): Drawable =
            if (cover.documentUri == null) decode(cover.file, side)
            else ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, cover.documentUri)) { decoder, info, _ ->
                val (w, h) = decodeSize(info.size.width, info.size.height, side)
                decoder.setTargetSize(w, h)
            }
    }
}
