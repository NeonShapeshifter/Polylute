package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber

/**
 * How big an animated cover may be (B58 1, #69). **An MP4 has no cap**: the hardware video decoder is fed a few seconds at a time from the file ([CoverVideoPlayer.build]: a
 * 1-3 s buffer), so a 102 MB `cover.mp4` costs the same memory as a 3 MB one; the only thing read whole is the index of the samples (a few bytes per frame, a few MB for
 * an hour). A cap on a video would only refuse a file that plays. WebP and GIF keep [CoverImageEngine.MAX_BYTES]: those are decoded by the CPU into memory, and the cap is what
 * keeps a 100 MB GIF from taking the heap.
 */
object AnimatedCoverLimits {
    /** The biggest file of [kind] that is played, or null when there is no limit. */
    fun maxBytes(kind: AnimatedCoverKind): Long? = when (kind) {
        AnimatedCoverKind.VIDEO -> null
        AnimatedCoverKind.WEBP, AnimatedCoverKind.GIF -> CoverImageEngine.MAX_BYTES
    }

    /** Why [cover] is not played for its size, in words for the log, or null when it is not too big. */
    fun tooBig(cover: AnimatedCover): String? {
        val cap = maxBytes(cover.kind) ?: return null
        if (cover.sizeBytes <= cap) return null
        return "too big (${megabytes(cover.sizeBytes)} MB, the cap is ${megabytes(cap)} MB)"
    }

    /** Megabytes (1 MB = 1,048,576 bytes, what the cap is written in) with one decimal, and no trailing ".0" on a round number. */
    internal fun megabytes(bytes: Long): String {
        val tenths = Math.round(bytes * 10.0 / (1024 * 1024))
        return if (tenths % 10L == 0L) (tenths / 10L).toString() else String.format(Locale.ROOT, "%.1f", tenths / 10.0)
    }
}

/**
 * B56 phase 7.2/7.3 (#48): the engine for each kind of file: the MP4 on a hardware video decoder ([CoverVideoEngine]), the WebP and the GIF on the platform's image decoder
 * ([CoverImageEngine]). A WebP or GIF over [CoverImageEngine.MAX_BYTES] ([AnimatedCoverLimits]; an MP4 has no cap) or a file that is not readable is not played.
 *
 * A cover skipped for its size says so **once** (per file, until the file changes) under the tag `AnimatedCover`: `skip kind=GIF file=… too big (100 MB, the cap is 64 MB)`.
 * [makeVideo] and [makeImage] are the seams the tests replace; they are the real engines here.
 */
class PlatformAnimatedCoverEngines internal constructor(
    private val makeVideo: (AnimatedCover, (String) -> Unit) -> AnimatedCoverEngine,
    private val makeImage: (AnimatedCover, Int, (String) -> Unit) -> AnimatedCoverEngine,
    private val log: (String) -> Unit,
) : AnimatedCoverEngineFactory {
    constructor(context: Context, scope: CoroutineScope) : this(
        makeVideo = { cover, onFailed -> CoverVideoEngine(context, cover, onFailed) },
        makeImage = { cover, sizePx, onFailed -> CoverImageEngine(context, cover, sizePx, scope, onFailed) },
        log = { Timber.tag(AnimatedCoverController.TAG).i(it) },
    )

    /** The identities already reported as too big: one line per file, not one per question. */
    private val skipped = LinkedHashSet<String>()

    override fun create(cover: AnimatedCover, sizePx: Int, onFailed: (String) -> Unit): AnimatedCoverEngine? {
        AnimatedCoverLimits.tooBig(cover)?.let { why ->
            if (skipped.add(cover.identity)) {
                if (skipped.size > MAX_SKIPPED) skipped.remove(skipped.first())
                log("skip kind=${cover.kind} file=${cover.file.path} $why")
            }
            return null
        }
        if (!cover.canRead()) return null
        return when (cover.kind) {
            AnimatedCoverKind.VIDEO -> makeVideo(cover, onFailed)
            AnimatedCoverKind.WEBP, AnimatedCoverKind.GIF -> makeImage(cover, sizePx, onFailed)
        }
    }

    private companion object {
        const val MAX_SKIPPED = 64
    }
}
