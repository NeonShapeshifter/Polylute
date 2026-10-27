package com.lostf1sh.pixelplayeross.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B42-fix3 (item 7): which backdrop a cover is made into. Both are a [GlassField] and travel through the same scene, crossfade, scrims and
 * still copies; they differ in what the 32 x 32 grid is made from and whether it moves.
 */
enum class GlassKind(val moves: Boolean) {
    /** "Cover glass": the blurred cover itself (its colours and their layout), gliding in the full player and on the lock screen. */
    GLASS(moves = true),

    /** "From album art": the cover's two strongest colours as a soft gradient. No part of the picture, no motion. */
    GRADIENT(moves = false),
}

/**
 * B41: the glass of each cover, made once and kept.
 *
 * Two things are kept apart because they cost differently and change for different reasons. The **grid** is the cover shrunk and blurred
 * (a decode of a small copy of the cover, which Coil's own cache already holds because the player showed it, and a few thousand additions):
 * it depends on the cover alone, so it is kept per cover. The **field** is that grid brought into one theme's brightness band (a thousand
 * cells and a search each): it depends on the theme too, so it is kept per cover and band. A cover shown again is two lookups and no work;
 * a theme change is the second half only.
 *
 * Nothing here runs on the main thread, and nothing blurs per frame: a field is a 32 x 32 picture, and the GPU's bilinear filter does the
 * rest when it is drawn stretched over the screen. That is the same on every API level (no `RenderEffect`, which needs 31), so the cost is
 * the same on API 30.
 */
@Singleton
class CoverGlassFields @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val grids = Lru<GlassGrid>(GRID_CACHE)
    private val fields = Lru<GlassField>(FIELD_CACHE)

    /** What the tests count: decodes of a cover, and fields built from a grid. */
    internal val decodes = AtomicInteger(0)
    internal val builds = AtomicInteger(0)

    /**
     * Decodes a small copy of the cover into pixels (width, height, ARGB); a seam for the tests. The real one asks Coil for 96 x 96, which
     * comes from its memory cache when the player already showed the cover.
     */
    internal var decoder: suspend (uri: String) -> Triple<Int, Int, IntArray>? = { uri -> decode(uri) }

    /** The backdrop of [kind] for [uri] in [band]'s brightness, or null when the cover cannot be read (the theme's own background then). */
    suspend fun fieldFor(uri: String, kind: GlassKind, band: GlassBand): GlassField? {
        val fieldKey = "$uri|$kind|${band.key}"
        fields.get(fieldKey)?.let { return it }
        val gridKey = "$uri|$kind"
        val grid = grids.get(gridKey) ?: run {
            val pixels = decoder(uri) ?: return null
            decodes.incrementAndGet()
            withContext(Dispatchers.Default) {
                when (kind) {
                    GlassKind.GLASS -> GlassFieldMath.grid(pixels.third, pixels.first, pixels.second)
                    GlassKind.GRADIENT -> CoverColours.pick(pixels.third, pixels.first, pixels.second).let { GlassFieldMath.gradientGrid(it.lighter, it.darker) }
                }
            }.also { grids.put(gridKey, it) }
        }
        return withContext(Dispatchers.Default) { GlassFieldMath.field(grid, band, moves = kind.moves) }.also {
            builds.incrementAndGet()
            fields.put(fieldKey, it)
        }
    }

    /** Forgets everything made (the app is short of memory, or the artwork changed under a cover). */
    fun clear() {
        grids.clear()
        fields.clear()
    }

    private suspend fun decode(uri: String): Triple<Int, Int, IntArray>? = withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false)
                .size(Size(DECODE_SIZE, DECODE_SIZE))
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(if (LocalArtworkUri.isLocalArtworkUri(uri)) CachePolicy.DISABLED else CachePolicy.ENABLED)
                .build()
            val drawable = context.imageLoader.execute(request).drawable ?: return@withContext null
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = createBitmap(w, h)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.recycle()
            Triple(w, h, pixels)
        } catch (e: Exception) {
            null
        }
    }

    /** A small least-recently-used map (a plain JVM class, so the tests need no platform). */
    private class Lru<V>(private val capacity: Int) {
        private val map = object : LinkedHashMap<String, V>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > capacity
        }

        @Synchronized fun get(key: String): V? = map[key]
        @Synchronized fun put(key: String, value: V) { map[key] = value }
        @Synchronized fun clear() = map.clear()
    }

    private companion object {
        const val DECODE_SIZE = 96
        const val GRID_CACHE = 16
        const val FIELD_CACHE = 8
    }
}
