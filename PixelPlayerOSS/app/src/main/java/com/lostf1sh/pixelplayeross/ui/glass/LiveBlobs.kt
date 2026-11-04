package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

/**
 * B56 phase 7.1 (#48): the live background of the full player, the way Apple Music's drifts: large, soft blobs of three to five colours **of the cover's own palette**
 * ([CoverColours.palette], taken from the same band-adjusted field the still glass is made of, so the ink's contrast holds), turning and drifting slowly.
 *
 * **Where it runs.** Android 13 and later ([available]): an AGSL `RuntimeShader`, one full-screen rectangle, no bitmap work and no per-frame allocation (the colours are set once
 * per cover; a frame sets the time and the travel). Before it, the glass is the classic drifting layers (`GlassFieldLayers`), moving or not as the setting says. **When it runs**
 * is the glass's own policy plus two stricter stops for the live path ([LiveBackgroundPolicy]): it holds still under the DAP lock screen and under a sheet.
 *
 * The AGSL is plain GLSL-like arithmetic: each blob is a Gaussian of the distance to its centre (a slow Lissajous in time), the colour at a pixel is the blobs' colours weighted
 * by them, over the cover's base colour so no pixel is ever empty.
 */
object LiveBlobs {
    /** AGSL needs Android 13 (API 33). */
    fun available(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= 33

    /** The most blobs the shader draws: the palette has three to five colours. */
    const val MAX_BLOBS = 5

    /** How many frames a second the live background is stepped at: its periods are tens of seconds, so 30 looks the same as 60 and halves the work (pick: see B56-resultado). */
    const val FPS = 30
    const val FRAME_NANOS = 1_000_000_000L / FPS

    const val AGSL = """
        uniform float2 resolution;
        uniform float time;
        uniform float travel;
        uniform float count;
        layout(color) uniform half4 base;
        layout(color) uniform half4 c0;
        layout(color) uniform half4 c1;
        layout(color) uniform half4 c2;
        layout(color) uniform half4 c3;
        layout(color) uniform half4 c4;

        half4 blob(int i) {
            if (i == 0) return c0;
            if (i == 1) return c1;
            if (i == 2) return c2;
            if (i == 3) return c3;
            return c4;
        }

        half4 main(float2 fragCoord) {
            float aspect = resolution.x / resolution.y;
            float2 p = float2(fragCoord.x / resolution.y, fragCoord.y / resolution.y);
            half3 acc = half3(0.0);
            float total = 0.0;
            for (int i = 0; i < 5; i++) {
                if (float(i) >= count) break;
                float f = float(i);
                // each blob's own slow orbit: periods are tens of seconds and none is a multiple of another
                float2 centre = float2(
                    aspect * (0.5 + 0.34 * travel * sin(time * (0.19 + 0.037 * f) + 1.7 * f)),
                    0.5 + 0.30 * travel * cos(time * (0.15 + 0.041 * f) + 2.3 * f)
                );
                float radius = 0.46 + 0.10 * sin(time * (0.11 + 0.023 * f) + f);
                float2 d = p - centre;
                float w = exp(-dot(d, d) / (radius * radius * 0.5));
                acc += blob(i).rgb * half(w);
                total += w;
            }
            float cover = clamp(total, 0.0, 1.0);
            half3 colour = mix(base.rgb, acc / half(max(total, 0.0001)), half(cover));
            return half4(colour, 1.0);
        }
    """

    /** The colours the shader is given: the palette, at most [MAX_BLOBS] of them. */
    fun colorsOf(palette: List<Color>): List<Int> = palette.take(MAX_BLOBS).map { it.toArgb() }
}

/**
 * The stops of the live background that are stricter than the classic glass's ([GlassMotionPolicy]): it holds still under the DAP lock screen and under a sheet, and an
 * ordinary [GlassMotionPolicy.shouldMove] is its precondition. A held clock keeps its phase, so it resumes where it stopped (no jump): see [GlassTicker].
 */
object LiveBackgroundPolicy {
    fun shouldRun(motionMoves: Boolean, sdkInt: Int, glassMoves: Boolean, lockSurfaceVisible: Boolean, sheetOpen: Boolean): Boolean =
        motionMoves && LiveBlobs.available(sdkInt) && glassMoves && !lockSurfaceVisible && !sheetOpen
}

private val COLOUR_UNIFORMS = arrayOf("c0", "c1", "c2", "c3", "c4")

/** The size the shader's still uniforms were last set for. */
private class PushedUniforms {
    var width = -1f
    var height = -1f
}

/** The blobs, over the layer below them (the cover's base field). Android 13+ only: the caller checks [LiveBlobs.available]. */
@RequiresApi(33)
@Composable
internal fun LiveBlobsLayer(field: GlassField, clock: GlassClock, modifier: Modifier = Modifier) {
    val shader = remember { RuntimeShader(LiveBlobs.AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val palette = remember(field) { field.palette }
    val colours = remember(palette) { LiveBlobs.colorsOf(palette) }
    val base = remember(field) { field.mean.toArgb() }
    // the colours and the size are set when they change (a new cover, a new size), not every frame: a frame sets the time and the travel and nothing else, and allocates nothing
    val pushed = remember(shader, colours, base) { PushedUniforms() }
    Box(
        modifier.fillMaxSize().drawBehind {
            if (pushed.width != size.width || pushed.height != size.height) {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("count", colours.size.toFloat())
                shader.setColorUniform("base", base)
                for (i in COLOUR_UNIFORMS.indices) shader.setColorUniform(COLOUR_UNIFORMS[i], colours.getOrElse(i) { colours.last() })
                pushed.width = size.width
                pushed.height = size.height
            }
            shader.setFloatUniform("time", clock.phase)
            shader.setFloatUniform("travel", clock.motion.travel.coerceAtLeast(0.0001f))
            drawRect(brush)
        }
    )
}
