package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize

/**
 * An opaque copy of the already blurred glass, in root coordinates, underneath the shared panel veil.
 * It preserves the page's colour and phase while excluding list text entirely, on API 30 as well as 31+.
 * The tiny bitmaps are built once per field; no backdrop capture or RenderEffect is rebuilt per frame.
 */
internal fun Modifier.frostedBarBackdrop(): Modifier = composed {
    val scene = LocalCoverGlass.current ?: return@composed this
    if (!scene.visible) return@composed this
    val current = scene.crossfade.current
    val previous = scene.crossfade.previous
    fun bitmap(field: GlassField?) = field?.let { Bitmap.createBitmap(it.argb, it.size, it.size, Bitmap.Config.ARGB_8888).asImageBitmap() }
    val currentImage = remember(current) { bitmap(current) }
    val previousImage = remember(previous) { bitmap(previous) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    clipToBounds().onGloballyPositioned {
        origin = it.positionInRoot()
        rootSize = it.findRootCoordinates().size
    }.drawBehind {
        drawRect(scene.base)
        if (rootSize.width == 0 || rootSize.height == 0) return@drawBehind
        withTransform({ translate(-origin.x, -origin.y) }) {
            val pivot = Offset(rootSize.width / 2f, rootSize.height / 2f)
            fun drawField(image: androidx.compose.ui.graphics.ImageBitmap?, field: GlassField?, alpha: Float) {
                if (image == null || field == null) return
                for (i in 0 until (if (field.moves) GlassMotion.LAYERS else 1)) {
                    val layer = GlassMotion.layer(scene.clock.phase, i)
                    withTransform({
                        translate(layer.translationX * rootSize.width, layer.translationY * rootSize.height)
                        rotate(layer.rotationDegrees, pivot)
                        scale(layer.scale, layer.scale, pivot)
                    }) { drawImage(image, dstSize = rootSize, alpha = alpha * layer.alpha, filterQuality = FilterQuality.Low) }
                }
            }
            drawField(previousImage, previous, 1f)
            drawField(currentImage, current, scene.crossfade.blend)
        }
        drawRect(scene.base.copy(alpha = GlassScrim.PAGE_ALPHA))
    }
}
