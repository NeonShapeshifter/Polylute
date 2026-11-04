package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * B56 phase 7.2/7.3 (#48): the engine for each kind of file: the MP4 on a hardware video decoder ([CoverVideoEngine]), the WebP and the GIF on the platform's image decoder
 * ([CoverImageEngine]). A file over [CoverImageEngine.MAX_BYTES] or one that is not readable is not played.
 */
class PlatformAnimatedCoverEngines(private val context: Context, private val scope: CoroutineScope) : AnimatedCoverEngineFactory {
    override fun create(cover: AnimatedCover, sizePx: Int, onFailed: (String) -> Unit): AnimatedCoverEngine? {
        if (cover.sizeBytes > CoverImageEngine.MAX_BYTES || !cover.canRead()) return null
        return when (cover.kind) {
            AnimatedCoverKind.VIDEO -> CoverVideoEngine(context, cover, onFailed)
            AnimatedCoverKind.WEBP, AnimatedCoverKind.GIF -> CoverImageEngine(context, cover, sizePx, scope, onFailed)
        }
    }
}
