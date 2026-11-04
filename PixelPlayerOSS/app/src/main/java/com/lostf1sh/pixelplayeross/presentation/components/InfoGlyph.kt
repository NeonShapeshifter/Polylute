package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import com.lostf1sh.pixelplayeross.ui.ds.Dap

/** The owner's 24-unit outline icons. Decorative icons are excluded from spoken labels. */
@Composable
internal fun InfoGlyph(name: String, modifier: Modifier = Modifier, description: String? = null, tint: Color = Dap.primaryText) {
    if (name == "loading") {
        CircularProgressIndicator(modifier.clearAndSetSemantics { if (description != null) contentDescription = description }, color = tint, strokeWidth = 2.dp)
        return
    }
    val circle = "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0"
    val data = when(name) {
        "close" -> "M6 6l12 12M18 6L6 18"
        "back" -> "M15 5l-7 7 7 7"
        "right" -> "M9 6l6 6-6 6"
        "down" -> "M6 9l6 6 6-6"
        "check" -> "M5 12.5l4.5 4.5L19 7.5"
        "saved" -> "$circle M8 12.5l3 3 5-6"
        "available" -> "$circle M12 7.5v8M8.5 12l3.5 3.5 3.5-3.5"
        "clock" -> "$circle M12 7v5l3 2"
        "unchecked", "loading" -> circle
        "missing" -> "$circle M8 12h8"
        "blocked" -> "$circle M5.6 5.6l12.8 12.8"
        "search" -> "M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0M20 20l-4-4"
        "LYRICS" -> "M4 6h16M4 10h16M4 14h10M4 18h7"
        "PHOTO" -> "M16 8a4 4 0 1 1-8 0a4 4 0 1 1 8 0M4 21a8 8 0 0 1 16 0"
        "BIO" -> "M6 3h9l4 4v14H6zM9 12h7M9 16h7"
        "COVER" -> "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2M11 9a2 2 0 1 1-4 0a2 2 0 1 1 4 0M21 15l-5-5L5 21"
        "DESCRIPTION" -> "$circle M14.5 12a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0"
        "list" -> "M4 6h16M4 12h16M4 18h10"
        "download" -> "M12 4v11M7 10l5 5 5-5M5 20h14"
        "delete" -> "M4 7h16M10 7V4h4v3M6 7l1 13h10l1-13"
        "folder" -> "M3 6.5h6l2 2h10V19H3z"
        "pause" -> "M9 5v14M15 5v14"
        "play" -> "M7 5l12 7-12 7z"
        "wifi" -> "M2 8.8a15 15 0 0 1 4.5-3M10 5a15 15 0 0 1 12 3.8M5 12.5a10 10 0 0 1 4-2.4M14.5 10.2a10 10 0 0 1 4.5 2.3M8.5 16a5 5 0 0 1 7 0M12 19.5h.01M3 3l18 18"
        "offline" -> "M3 3l18 18M17.5 17.5H7a5 5 0 0 1-1.6-9.7M9.3 5.6A6.5 6.5 0 0 1 18 10a4 4 0 0 1 2.6 6.4"
        "retry" -> "M20 12a8 8 0 1 1-2.3-5.6M20 4v5h-5"
        "warning", "error" -> "M12 3.5l9.5 17h-19zM12 10v4.5M12 17.5h.01"
        "music" -> "M9 18V5l11-2v13M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0M20 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0"
        else -> circle
    }
    val path = remember(data) { PathParser().parsePathString(data).toPath() }
    Canvas(modifier.then(if(description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, tint, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round,
                pathEffect = if(name == "unchecked") PathEffect.dashPathEffect(floatArrayOf(2.5f, 3f)) else null))
        }
    }
}
