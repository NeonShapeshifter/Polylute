package com.lostf1sh.pixelplayeross.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImagePainter
import coil.size.Size
import com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverHost
import com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverLayer
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing

/** Still artwork remains underneath the shared engine, including failed or not-yet-ready animations. */
@Composable
internal fun DetailArtwork(
    model: Any?, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    animated: AnimatedCoverHost? = null, targetSize: Size = Size(600, 600),
    alpha: Float = 1f, onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    Box(modifier.clipToBounds().graphicsLayer { this.alpha = alpha }.clickable(onClick = onClick).testTag("detail-artwork").onSizeChanged { animated?.onSize(maxOf(it.width, it.height)) }) {
        SmartImage(model, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, targetSize = targetSize, allowHardware = true, crossfadeDurationMillis = 0, onState = onState)
        AnimatedCoverLayer(animated, Modifier.fillMaxSize())
    }
}

/** The same modal for album covers and artist photos. Its caller owns the animated host. */
@Composable
internal fun ArtworkViewer(model: Any?, description: String, onDismiss: () -> Unit, animated: AnimatedCoverHost? = null, mountInputBridge: Boolean = true) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        if (mountInputBridge) ModalInputBridge()
        BackHandler(onBack = onDismiss)
        ArtworkViewerContent(model, description, onDismiss, animated)
    }
}

@Composable
internal fun ArtworkViewerContent(model: Any?, description: String, onDismiss: () -> Unit, animated: AnimatedCoverHost? = null) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var drag by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    var bounds by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    fun clampPan(value: Offset): Offset {
        val x = bounds.width * (zoom - 1f) / 2f
        val y = bounds.height * (zoom - 1f) / 2f
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    Box(Modifier.fillMaxSize().background(Dap.background).testTag("artwork-viewer")) {
        Box(
            Modifier.align(Alignment.Center).fillMaxSize().clipToBounds().onSizeChanged { bounds = it; animated?.onSize(it.width) }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 2.5f; pan = Offset.Zero }) }
                // One recognizer owns pinch, pan and dismiss: a one-finger drag cannot steal a pinch.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var multipleFingers = false
                        do {
                            val event = awaitPointerEvent()
                            val movement = event.calculatePan()
                            if (event.changes.count { it.pressed } >= 2) {
                                multipleFingers = true
                                drag = 0f
                                zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                                pan = if (zoom == 1f) Offset.Zero else clampPan(pan + movement)
                            } else if (zoom > 1f) {
                                pan = clampPan(pan + movement)
                            } else if (!multipleFingers) {
                                drag += movement.y
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (event.changes.any { it.pressed })
                        if (!multipleFingers && zoom == 1f && drag > threshold) onDismiss()
                        drag = 0f
                    }
                }
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y + drag }
                .testTag("artwork-zoom"),
        ) {
            SmartImage(model, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, targetSize = Size(1536, 1536), allowHardware = true, crossfadeDurationMillis = 0)
            AnimatedCoverLayer(animated, Modifier.fillMaxSize(), fit = true)
        }
        DapOverlayBack(Modifier.align(Alignment.TopStart), onClick = onDismiss)
    }
}
