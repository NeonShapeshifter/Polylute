package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtColorAccuracy
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtPaletteStyle
import com.lostf1sh.pixelplayeross.data.database.AlbumArtThemeDao
import com.lostf1sh.pixelplayeross.data.database.AlbumArtThemeEntity
import com.lostf1sh.pixelplayeross.data.database.StoredColorSchemeValues
import com.lostf1sh.pixelplayeross.data.database.toComposeColor
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import com.lostf1sh.pixelplayeross.utils.traceAsyncSection
import com.lostf1sh.pixelplayeross.ui.theme.clearExtractedColorCache
import com.lostf1sh.pixelplayeross.ui.theme.extractSeedColor
import com.lostf1sh.pixelplayeross.ui.theme.generateColorSchemeFromSeed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Efficient color scheme processor for album art.
 * 
 * Optimizations:
 * - In-memory LRU cache to avoid disk reads for recently accessed schemes
 * - Mutex-protected processing to avoid duplicate work
 * - Batched bitmap operations on IO dispatcher
 * - Reduced bitmap size (128x128) for faster processing
 * 
 * Extracted from PlayerViewModel to improve modularity.
 */
@Singleton
class ColorSchemeProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val albumArtThemeDao: AlbumArtThemeDao
) {
    private val memoryCache = LruCache<String, ColorSchemePair>(20)

    /**
     * B31 P1.7 (R30-F1-03): which generation of a cover's palette may write the cache. A regeneration that a later one has replaced still
     * returns its result to whoever asked, but it no longer goes where the next ordinary request will read it.
     */
    internal val currency = PaletteCacheCurrency()

    /**
     * Computes a palette from the cover (decode, extract, generate). A seam for the tests, which hand in one they can hold and release in
     * either order; the real one is below. Everything around it (the caches, the single flight, the currency) is the real code.
     */
    internal var generator: suspend (uri: String, style: AlbumArtPaletteStyle, accuracy: Int, forceRefresh: Boolean) -> ColorSchemePair? =
        { uri, style, accuracy, forceRefresh -> computeScheme(uri, style, accuracy, forceRefresh) }

    /**
     * One extraction per cover at a time (B29-L11): the song change, a sheet's own request and a preload used to decode their own bitmap and
     * run their own extraction for the same cover; now they share one. It runs in its own scope, so a caller that leaves (a fast skip) stops
     * waiting and the palette is still cached for whoever asked.
     */
    private val extractionFlight = SingleFlight<String, ColorSchemePair?>(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    private val processingMutex = Mutex()
    private val inProgressUris = mutableSetOf<String>()

    /**
     * Channel for queuing color scheme requests.
     * Used by PlayerViewModel for background processing.
     * Capacity is bounded with DROP_OLDEST to prevent unbounded growth during rapid
     * track changes (e.g. fast seek through a large playlist).
     */
    val requestChannel = Channel<String>(capacity = 32, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /**
     * Gets or generates a color scheme for the given album art URI.
     * Checks memory cache first, then database, then generates new.
     * All heavy operations are performed on appropriate dispatchers.
     */
    /**
     * Gets or generates a color scheme for the given album art URI.
     * Checks memory cache first, then database, then generates new.
     * @param forceRefresh If true, bypasses caches and forces regeneration from source image.
     */
    suspend fun getOrGenerateColorScheme(
        albumArtUri: String,
        paletteStyle: AlbumArtPaletteStyle,
        colorAccuracyLevel: Int = AlbumArtColorAccuracy.DEFAULT,
        forceRefresh: Boolean = false
    ): ColorSchemePair? = traceAsyncSection("ColorSchemeProcessor.getOrGenerate") {
        val resolvedAccuracyLevel = AlbumArtColorAccuracy.clamp(colorAccuracyLevel)
        if (!forceRefresh) {
            loadCachedColorScheme(
                albumArtUri = albumArtUri,
                paletteStyle = paletteStyle,
                colorAccuracyLevel = resolvedAccuracyLevel
            )?.let { schemePair ->
                return@traceAsyncSection schemePair
            }
        }

        val generate: suspend () -> ColorSchemePair? = {
            generateAndCacheColorScheme(
                albumArtUri = albumArtUri,
                paletteStyle = paletteStyle,
                colorAccuracyLevel = resolvedAccuracyLevel,
                forceRefresh = forceRefresh
            )
        }
        // A forced refresh is somebody asking for new work; everything else for the same cover shares one.
        if (forceRefresh) generate()
        else extractionFlight.run(buildCacheKey(albumArtUri, paletteStyle, resolvedAccuracyLevel), generate)
    }

    suspend fun getPreviewColorScheme(
        albumArtUri: String,
        paletteStyle: AlbumArtPaletteStyle,
        colorAccuracyLevel: Int = AlbumArtColorAccuracy.DEFAULT
    ): ColorSchemePair? {
        val resolvedAccuracyLevel = AlbumArtColorAccuracy.clamp(colorAccuracyLevel)
        return loadCachedColorScheme(
            albumArtUri = albumArtUri,
            paletteStyle = paletteStyle,
            colorAccuracyLevel = resolvedAccuracyLevel
        ) ?: generateAndCacheColorScheme(
            albumArtUri = albumArtUri,
            paletteStyle = paletteStyle,
            colorAccuracyLevel = resolvedAccuracyLevel,
            persistToDatabase = false
        )
    }

    /**
     * Generates a color scheme from the album art bitmap.
     * All processing done on Default dispatcher for CPU-bound work.
     */
    private suspend fun generateAndCacheColorScheme(
        albumArtUri: String,
        paletteStyle: AlbumArtPaletteStyle,
        colorAccuracyLevel: Int,
        persistToDatabase: Boolean = true,
        forceRefresh: Boolean = false
    ): ColorSchemePair? = traceAsyncSection("ColorSchemeProcessor.generate") {
        val cacheKey = buildCacheKey(albumArtUri, paletteStyle, colorAccuracyLevel)
        // Noted before any work begins: whether this result may be written is decided by what began after it (see PaletteCacheCurrency).
        val ticket = currency.begin(cacheKey, forceRefresh)
        try {
            val schemePair = generator(albumArtUri, paletteStyle, colorAccuracyLevel, forceRefresh)
                ?: return@traceAsyncSection null

            currency.entry(cacheKey).writeLock.withLock {
                if (currency.mayWrite(ticket)) {
                    memoryCache.put(cacheKey, schemePair)
                    if (persistToDatabase) {
                        withContext(Dispatchers.IO) {
                            albumArtThemeDao.insertTheme(
                                mapColorSchemePairToEntity(
                                    uri = albumArtUri,
                                    paletteStyle = paletteStyle,
                                    colorAccuracyLevel = colorAccuracyLevel,
                                    pair = schemePair
                                )
                            )
                        }
                    }
                }
                // Otherwise a newer generation (or an invalidation) of this cover came after this one began: the result goes back to
                // its caller, and the cache keeps what the newer one wrote.
            }

            schemePair
        } catch (e: Exception) {
            null
        } finally {
            currency.end(ticket)
        }
    }

    /** The real computation: decode a small bitmap of the cover, extract its seed colour and build the scheme pair from it. */
    private suspend fun computeScheme(
        albumArtUri: String,
        paletteStyle: AlbumArtPaletteStyle,
        colorAccuracyLevel: Int,
        forceRefresh: Boolean,
    ): ColorSchemePair? {
        val bitmap = withContext(Dispatchers.IO) {
            loadBitmapForColorExtraction(albumArtUri, forceRefresh)
        } ?: return null
        return withContext(Dispatchers.Default) {
            val seed = extractSeedColor(
                bitmap = bitmap,
                config = com.lostf1sh.pixelplayeross.ui.theme.ColorExtractionConfig(
                    accuracyLevel = colorAccuracyLevel
                )
            )
            bitmap.recycle()
            generateColorSchemeFromSeed(
                seedColor = seed,
                paletteStyle = paletteStyle
            )
        }
    }

    /**
     * Loads a small bitmap optimized for color extraction.
     */
    private suspend fun loadBitmapForColorExtraction(uri: String, skipCache: Boolean): Bitmap? {
        return try {
            val cachePolicy = if (skipCache) CachePolicy.DISABLED else CachePolicy.ENABLED
            val diskCachePolicy = if (LocalArtworkUri.isLocalArtworkUri(uri)) CachePolicy.DISABLED else cachePolicy
            
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false)
                .size(Size(128, 128))
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .memoryCachePolicy(cachePolicy)
                .diskCachePolicy(diskCachePolicy)
                .build()
            
            val drawable = context.imageLoader.execute(request).drawable ?: return null
            
            createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1)
            ).also { bmp ->
                Canvas(bmp).let { canvas ->
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks if a URI is currently being processed.
     * Used to avoid duplicate work.
     */
    suspend fun markProcessing(uri: String): Boolean {
        return processingMutex.withLock {
            if (inProgressUris.contains(uri)) {
                false
            } else {
                inProgressUris.add(uri)
                true
            }
        }
    }

    /**
     * Marks a URI as finished processing.
     */
    suspend fun markComplete(uri: String) {
        processingMutex.withLock {
            inProgressUris.remove(uri)
        }
    }

    /**
     * Clears the in-memory cache.
     * Call when memory is low or on configuration changes.
     */
    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    /**
     * Removes a specific URI from the cache.
     */
    fun evictFromCache(uri: String) {
        removeUriFromMemoryCache(uri)
    }

    /**
     * Invalidates the color scheme for a URI in both memory and database.
     */
    suspend fun invalidateScheme(uri: String) {
        clearExtractedColorCache()
        // B31 P1.7: whatever began before this must not write the cover's palette back once it is removed.
        currency.invalidate("$uri$CACHE_KEY_SEPARATOR")
        removeUriFromMemoryCache(uri)
        withContext(Dispatchers.IO) {
            albumArtThemeDao.deleteThemesByUris(listOf(uri))
        }
    }

    private fun removeUriFromMemoryCache(uri: String) {
        val prefix = "$uri$CACHE_KEY_SEPARATOR"
        memoryCache.snapshot().keys
            .filter { key -> key == uri || key.startsWith(prefix) }
            .forEach { key -> memoryCache.remove(key) }
    }

    private fun mapColorSchemePairToEntity(
        uri: String,
        paletteStyle: AlbumArtPaletteStyle,
        colorAccuracyLevel: Int,
        pair: ColorSchemePair
    ): AlbumArtThemeEntity {
        fun mapScheme(cs: ColorScheme) = StoredColorSchemeValues(
            primary = cs.primary.toHexString(),
            onPrimary = cs.onPrimary.toHexString(),
            primaryContainer = cs.primaryContainer.toHexString(),
            onPrimaryContainer = cs.onPrimaryContainer.toHexString(),
            secondary = cs.secondary.toHexString(),
            onSecondary = cs.onSecondary.toHexString(),
            secondaryContainer = cs.secondaryContainer.toHexString(),
            onSecondaryContainer = cs.onSecondaryContainer.toHexString(),
            tertiary = cs.tertiary.toHexString(),
            onTertiary = cs.onTertiary.toHexString(),
            tertiaryContainer = cs.tertiaryContainer.toHexString(),
            onTertiaryContainer = cs.onTertiaryContainer.toHexString(),
            background = cs.background.toHexString(),
            onBackground = cs.onBackground.toHexString(),
            surface = cs.surface.toHexString(),
            onSurface = cs.onSurface.toHexString(),
            surfaceVariant = cs.surfaceVariant.toHexString(),
            onSurfaceVariant = cs.onSurfaceVariant.toHexString(),
            error = cs.error.toHexString(),
            onError = cs.onError.toHexString(),
            outline = cs.outline.toHexString(),
            errorContainer = cs.errorContainer.toHexString(),
            onErrorContainer = cs.onErrorContainer.toHexString(),
            inversePrimary = cs.inversePrimary.toHexString(),
            inverseSurface = cs.inverseSurface.toHexString(),
            inverseOnSurface = cs.inverseOnSurface.toHexString(),
            surfaceTint = cs.surfaceTint.toHexString(),
            outlineVariant = cs.outlineVariant.toHexString(),
            scrim = cs.scrim.toHexString(),
            surfaceBright = cs.surfaceBright.toHexString(),
            surfaceDim = cs.surfaceDim.toHexString(),
            surfaceContainer = cs.surfaceContainer.toHexString(),
            surfaceContainerHigh = cs.surfaceContainerHigh.toHexString(),
            surfaceContainerHighest = cs.surfaceContainerHighest.toHexString(),
            surfaceContainerLow = cs.surfaceContainerLow.toHexString(),
            surfaceContainerLowest = cs.surfaceContainerLowest.toHexString(),
            primaryFixed = cs.primaryFixed.toHexString(),
            primaryFixedDim = cs.primaryFixedDim.toHexString(),
            onPrimaryFixed = cs.onPrimaryFixed.toHexString(),
            onPrimaryFixedVariant = cs.onPrimaryFixedVariant.toHexString(),
            secondaryFixed = cs.secondaryFixed.toHexString(),
            secondaryFixedDim = cs.secondaryFixedDim.toHexString(),
            onSecondaryFixed = cs.onSecondaryFixed.toHexString(),
            onSecondaryFixedVariant = cs.onSecondaryFixedVariant.toHexString(),
            tertiaryFixed = cs.tertiaryFixed.toHexString(),
            tertiaryFixedDim = cs.tertiaryFixedDim.toHexString(),
            onTertiaryFixed = cs.onTertiaryFixed.toHexString(),
            onTertiaryFixedVariant = cs.onTertiaryFixedVariant.toHexString()
        )
        return AlbumArtThemeEntity(
            albumArtUriString = uri,
            paletteStyle = paletteStyleCacheKey(paletteStyle, colorAccuracyLevel),
            lightThemeValues = mapScheme(pair.light),
            darkThemeValues = mapScheme(pair.dark)
        )
    }

    private fun mapEntityToColorSchemePair(entity: AlbumArtThemeEntity): ColorSchemePair {
        fun mapStored(sv: StoredColorSchemeValues) = ColorScheme(
            primary = sv.primary.toComposeColor(),
            onPrimary = sv.onPrimary.toComposeColor(),
            primaryContainer = sv.primaryContainer.toComposeColor(),
            onPrimaryContainer = sv.onPrimaryContainer.toComposeColor(),
            inversePrimary = sv.inversePrimary.toComposeColor(),
            secondary = sv.secondary.toComposeColor(),
            onSecondary = sv.onSecondary.toComposeColor(),
            secondaryContainer = sv.secondaryContainer.toComposeColor(),
            onSecondaryContainer = sv.onSecondaryContainer.toComposeColor(),
            tertiary = sv.tertiary.toComposeColor(),
            onTertiary = sv.onTertiary.toComposeColor(),
            tertiaryContainer = sv.tertiaryContainer.toComposeColor(),
            onTertiaryContainer = sv.onTertiaryContainer.toComposeColor(),
            background = sv.background.toComposeColor(),
            onBackground = sv.onBackground.toComposeColor(),
            surface = sv.surface.toComposeColor(),
            onSurface = sv.onSurface.toComposeColor(),
            surfaceVariant = sv.surfaceVariant.toComposeColor(),
            onSurfaceVariant = sv.onSurfaceVariant.toComposeColor(),
            error = sv.error.toComposeColor(),
            onError = sv.onError.toComposeColor(),
            outline = sv.outline.toComposeColor(),
            errorContainer = sv.errorContainer.toComposeColor(),
            onErrorContainer = sv.onErrorContainer.toComposeColor(),
            inverseSurface = sv.inverseSurface.toComposeColor(),
            inverseOnSurface = sv.inverseOnSurface.toComposeColor(),
            surfaceTint = sv.surfaceTint.toComposeColor(),
            outlineVariant = sv.outlineVariant.toComposeColor(),
            scrim = sv.scrim.toComposeColor(),
            surfaceBright = sv.surfaceBright.toComposeColor(),
            surfaceDim = sv.surfaceDim.toComposeColor(),
            surfaceContainer = sv.surfaceContainer.toComposeColor(),
            surfaceContainerHigh = sv.surfaceContainerHigh.toComposeColor(),
            surfaceContainerHighest = sv.surfaceContainerHighest.toComposeColor(),
            surfaceContainerLow = sv.surfaceContainerLow.toComposeColor(),
            surfaceContainerLowest = sv.surfaceContainerLowest.toComposeColor(),
            primaryFixed = sv.primaryFixed.toComposeColor(),
            primaryFixedDim = sv.primaryFixedDim.toComposeColor(),
            onPrimaryFixed = sv.onPrimaryFixed.toComposeColor(),
            onPrimaryFixedVariant = sv.onPrimaryFixedVariant.toComposeColor(),
            secondaryFixed = sv.secondaryFixed.toComposeColor(),
            secondaryFixedDim = sv.secondaryFixedDim.toComposeColor(),
            onSecondaryFixed = sv.onSecondaryFixed.toComposeColor(),
            onSecondaryFixedVariant = sv.onSecondaryFixedVariant.toComposeColor(),
            tertiaryFixed = sv.tertiaryFixed.toComposeColor(),
            tertiaryFixedDim = sv.tertiaryFixedDim.toComposeColor(),
            onTertiaryFixed = sv.onTertiaryFixed.toComposeColor(),
            onTertiaryFixedVariant = sv.onTertiaryFixedVariant.toComposeColor()
        )
        return ColorSchemePair(
            light = mapStored(entity.lightThemeValues),
            dark = mapStored(entity.darkThemeValues)
        )
    }

    private fun Color.toHexString(): String {
        return String.format("#%08X", toArgb())
    }

    private fun buildCacheKey(
        uri: String,
        paletteStyle: AlbumArtPaletteStyle,
        colorAccuracyLevel: Int
    ): String {
        return "$uri$CACHE_KEY_SEPARATOR${paletteStyleCacheKey(paletteStyle, colorAccuracyLevel)}"
    }

    private fun paletteStyleCacheKey(
        paletteStyle: AlbumArtPaletteStyle,
        colorAccuracyLevel: Int
    ): String {
        return buildString {
            append(paletteStyle.storageKey)
            append(CACHE_KEY_SEPARATOR)
            append("accuracy_")
            append(AlbumArtColorAccuracy.clamp(colorAccuracyLevel))
            append(CACHE_KEY_SEPARATOR)
            append(CACHE_ALGORITHM_VERSION)
        }
    }

    private suspend fun loadCachedColorScheme(
        albumArtUri: String,
        paletteStyle: AlbumArtPaletteStyle,
        colorAccuracyLevel: Int
    ): ColorSchemePair? {
        val cacheKey = buildCacheKey(albumArtUri, paletteStyle, colorAccuracyLevel)

        memoryCache.get(cacheKey)?.let { return it }

        val cachedEntity = withContext(Dispatchers.IO) {
            albumArtThemeDao.getThemeByUriAndStyle(
                albumArtUri,
                paletteStyleCacheKey(paletteStyle, colorAccuracyLevel)
            )
        }
        if (cachedEntity == null) return null

        return mapEntityToColorSchemePair(cachedEntity).also { schemePair ->
            memoryCache.put(cacheKey, schemePair)
        }
    }

    companion object {
        private const val TAG = "ColorSchemeProcessor"
        private const val CACHE_KEY_SEPARATOR = "|"
        private const val CACHE_ALGORITHM_VERSION = "algo_v7"
    }
}
