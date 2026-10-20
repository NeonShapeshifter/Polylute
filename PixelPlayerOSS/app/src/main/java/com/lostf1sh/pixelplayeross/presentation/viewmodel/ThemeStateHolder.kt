package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.net.Uri
import android.content.ComponentCallbacks2
import androidx.compose.ui.graphics.Color
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtColorAccuracy
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtPaletteStyle
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreferencesRepository
import com.lostf1sh.pixelplayeross.ui.theme.DarkColorScheme
import com.lostf1sh.pixelplayeross.ui.theme.clearExtractedColorCache
import com.lostf1sh.pixelplayeross.utils.traceAsyncSection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeStateHolder @Inject constructor(
    private val colorSchemeProcessor: ColorSchemeProcessor,
    private val themePreferencesRepository: ThemePreferencesRepository
) {

    private var scope: CoroutineScope? = null
    @Volatile
    private var currentPaletteStyle: AlbumArtPaletteStyle = AlbumArtPaletteStyle.default
    @Volatile
    private var currentPaletteAccuracy: Int = AlbumArtColorAccuracy.DEFAULT

    /**
     * The cover the newest request is for (B29-L11). A palette is published only while its cover is still the newest one asked for, so a slow
     * one that arrives after a skip is dropped; before this each request compared with the current song as it was **when the request began**, and
     * a late A replaced B's palette with its own.
     */
    @Volatile
    private var latestRequestedUri: String? = null

    /**
     * B29-fix1 F4: which request for the global palette is the current one. It moves when the cover the listener is on changes (to
     * another cover, back to one asked for before, or to none), so a result that began under an earlier request is stale **even when its
     * cover is the one asked for again**. Every publisher of the global palette (a song's own request, and the refresh a change of
     * extraction style or accuracy starts) checks it together with the cover; the per-cover cache is updated either way, because what a
     * refresh computed is right for the cover it was computed for.
     */
    @Volatile
    private var requestGeneration = 0L

    /** A song's request for [uri] (null: the song has no cover) is now the newest. Returns its generation. */
    private fun beginRequest(uri: String?): Long {
        if (latestRequestedUri != uri) requestGeneration++
        latestRequestedUri = uri
        return requestGeneration
    }

    /**
     * B30-fix1 H4 (auditor R29-F1-01): the configuration a palette was computed under. [requestGeneration] only moves with the cover, so
     * an old forced regeneration of the **same** cover (begun under the old style) kept a valid cover and generation, and published the
     * old style's colours **after** the new style's palette. A result may now become the global palette only while all of this still
     * holds: the cover, the request generation, the extraction style, the accuracy, the settings epoch (every change of either moves
     * it) and the regeneration of that cover (every forced regeneration of it moves it). What an older configuration computed stays in
     * the processor's own cache entry, which is keyed by it; it just never replaces the newest.
     */
    private data class PaletteRequest(
        val uri: String,
        val generation: Long,
        val regeneration: Long,
        val epoch: Long,
        val style: AlbumArtPaletteStyle,
        val accuracy: Int,
    )

    /** Moves whenever the extraction style or the accuracy changes. */
    @Volatile
    private var configEpoch = 0L

    /** How many forced regenerations each cover has had. */
    private val regenerations = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun capture(uri: String, generation: Long) = PaletteRequest(
        uri = uri,
        generation = generation,
        regeneration = regenerations[uri] ?: 0L,
        epoch = configEpoch,
        style = currentPaletteStyle,
        accuracy = currentPaletteAccuracy,
    )

    /** The settings the result was computed under are still the settings. */
    private fun configCurrent(request: PaletteRequest): Boolean =
        configEpoch == request.epoch && currentPaletteStyle == request.style && currentPaletteAccuracy == request.accuracy

    /** No forced regeneration of this cover began after the request did. */
    private fun regenerationCurrent(request: PaletteRequest): Boolean = (regenerations[request.uri] ?: 0L) == request.regeneration

    /** This request is still the newest for the cover the listener is on. */
    private fun sameCover(request: PaletteRequest): Boolean = latestRequestedUri == request.uri && requestGeneration == request.generation

    /** The palette of the request may still become the global one. */
    private fun isCurrentRequest(request: PaletteRequest): Boolean =
        sameCover(request) && configCurrent(request) && regenerationCurrent(request)

    private val _currentAlbumArtColorSchemePair = MutableStateFlow<ColorSchemePair?>(null)
    val currentAlbumArtColorSchemePair: StateFlow<ColorSchemePair?> = _currentAlbumArtColorSchemePair.asStateFlow()
    private val _currentAlbumArtUri = MutableStateFlow<String?>(null)
    val currentAlbumArtUri: StateFlow<String?> = _currentAlbumArtUri.asStateFlow()

    private val _lavaLampColors = MutableStateFlow<ImmutableList<Color>>(persistentListOf())
    val lavaLampColors: StateFlow<ImmutableList<Color>> = _lavaLampColors.asStateFlow()

    private val playerThemePreference = themePreferencesRepository.playerThemePreferenceFlow

    private val _activePlayerColorSchemePair = MutableStateFlow<ColorSchemePair?>(null)
    val activePlayerColorSchemePair: StateFlow<ColorSchemePair?> = _activePlayerColorSchemePair.asStateFlow()

    fun initialize(scope: CoroutineScope) {
        this.scope = scope

        scope.launch {
            combine(playerThemePreference, _currentAlbumArtColorSchemePair) { playerPref, albumScheme ->
                when (playerPref) {
                    com.lostf1sh.pixelplayeross.data.preferences.ThemePreference.ALBUM_ART -> albumScheme
                    else -> null
                }
            }.collect { _activePlayerColorSchemePair.value = it }
        }

        scope.launch {
            combine(
                themePreferencesRepository.albumArtPaletteStyleFlow,
                themePreferencesRepository.albumArtColorAccuracyFlow
            ) { style, accuracy -> style to accuracy }
                .collect { (style, accuracy) ->
                    val paletteChanged =
                        currentPaletteStyle != style || currentPaletteAccuracy != accuracy
                    currentPaletteStyle = style
                    currentPaletteAccuracy = accuracy

                    if (!paletteChanged) return@collect
                    configEpoch++

                    val uri = _currentAlbumArtUri.value ?: return@collect
                    val request = capture(uri, requestGeneration)
                    val refreshedScheme = colorSchemeProcessor.getOrGenerateColorScheme(
                        albumArtUri = uri,
                        paletteStyle = request.style,
                        colorAccuracyLevel = request.accuracy
                    )
                    // by now the listener may be on another song (or none), or have changed the settings again: then this refresh is
                    // for a cover or a configuration that is no longer the current one, and it must not become the global palette.
                    // The cover's own cached palette still gets it, when it is still the palette for the settings.
                    if (isCurrentRequest(request)) _currentAlbumArtColorSchemePair.value = refreshedScheme
                    if (configCurrent(request) && regenerationCurrent(request)) individualAlbumColorSchemes[uri]?.value = refreshedScheme
                }
        }

        scope.launch {
            activePlayerColorSchemePair.collect { schemePair ->
                 updateLavaLampColors(schemePair)
            }
        }
    }

    suspend fun extractAndGenerateColorScheme(
        albumArtUriAsUri: Uri?,
        currentSongUriString: String?,
        isPreload: Boolean = false
    ): Unit = traceAsyncSection("ThemeStateHolder.extractAndGenerateColorScheme") {
        var request: PaletteRequest? = null
        try {
            if (albumArtUriAsUri == null) {
                if (!isPreload && currentSongUriString == null) {
                    beginRequest(null)
                    _currentAlbumArtColorSchemePair.value = null
                    _currentAlbumArtUri.value = null
                }
                return@traceAsyncSection
            }

            val uriString = albumArtUriAsUri.toString()
            val generation = if (!isPreload) beginRequest(uriString) else -1L
            var schemePair: ColorSchemePair?
            var attempts = 0
            do {
                val captured = capture(uriString, generation)
                request = captured
                schemePair = colorSchemeProcessor.getOrGenerateColorScheme(
                    albumArtUri = uriString,
                    paletteStyle = captured.style,
                    colorAccuracyLevel = captured.accuracy
                )
                // The listener changed the settings (or a regeneration of this cover began) while this was computed: what came back is
                // for a configuration that is gone. While this is still the song that is playing, compute again under the current one
                // rather than publish the old palette, or none.
            } while (!isPreload && !(configCurrent(captured) && regenerationCurrent(captured)) && sameCover(captured) &&
                ++attempts < MAX_CONFIG_RETRIES
            )

            if (!isPreload && currentSongUriString == uriString && isCurrentRequest(request)) {
                _currentAlbumArtColorSchemePair.value = schemePair
                _currentAlbumArtUri.value = uriString
            }
        } catch (e: Exception) {
            if (!isPreload && albumArtUriAsUri != null && currentSongUriString == albumArtUriAsUri.toString() &&
                request?.let(::isCurrentRequest) == true
            ) {
                _currentAlbumArtColorSchemePair.value = null
                _currentAlbumArtUri.value = null
            }
        }
    }

    private fun updateLavaLampColors(schemePair: ColorSchemePair?) {
        val schemeForLava = schemePair?.dark ?: DarkColorScheme
        _lavaLampColors.update {
            listOf(schemeForLava.primary, schemeForLava.secondary, schemeForLava.tertiary).distinct().toImmutableList()
        }
    }

    private val individualAlbumColorSchemes = object : LinkedHashMap<String, MutableStateFlow<ColorSchemePair?>>(
        32, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableStateFlow<ColorSchemePair?>>?): Boolean {
            return size > 96
        }
    }

    private val emptyAlbumColorScheme = MutableStateFlow<ColorSchemePair?>(null).asStateFlow()
    private val pendingAlbumColorSchemeLock = Any()
    private val pendingAlbumColorSchemeTargets = mutableMapOf<String, MutableSet<MutableStateFlow<ColorSchemePair?>>>()

    private fun requestAlbumColorSchemeGeneration(
        uriString: String,
        targetFlow: MutableStateFlow<ColorSchemePair?>
    ) {
        if (uriString.isBlank()) return

        val shouldStartRequest = synchronized(pendingAlbumColorSchemeLock) {
            val existingTargets = pendingAlbumColorSchemeTargets[uriString]
            if (existingTargets != null) {
                existingTargets.add(targetFlow)
                false
            } else {
                pendingAlbumColorSchemeTargets[uriString] = mutableSetOf(targetFlow)
                true
            }
        }

        if (!shouldStartRequest) return

        val requestScope = scope
        if (requestScope == null) {
            synchronized(pendingAlbumColorSchemeLock) {
                pendingAlbumColorSchemeTargets.remove(uriString)
            }
            return
        }

        requestScope.launch(Dispatchers.IO) {
            var scheme: ColorSchemePair? = null
            try {
                scheme = colorSchemeProcessor.getOrGenerateColorScheme(
                    albumArtUri = uriString,
                    paletteStyle = currentPaletteStyle,
                    colorAccuracyLevel = currentPaletteAccuracy
                )
            } catch (_: Exception) {
            } finally {
                val targets = synchronized(pendingAlbumColorSchemeLock) {
                    pendingAlbumColorSchemeTargets.remove(uriString)?.toList().orEmpty()
                }
                targets.forEach { it.value = scheme }
            }
        }
    }

    fun getAlbumColorSchemeFlow(
        uriString: String,
        eager: Boolean = true
    ): StateFlow<ColorSchemePair?> {
        if (uriString.isBlank()) return emptyAlbumColorScheme

        val existingFlow = individualAlbumColorSchemes[uriString]
        if (existingFlow != null) {
            if (eager && existingFlow.value == null) {
                requestAlbumColorSchemeGeneration(uriString, existingFlow)
            }
            return existingFlow.asStateFlow()
        }

        val newFlow = MutableStateFlow<ColorSchemePair?>(null)
        individualAlbumColorSchemes[uriString] = newFlow

        if (eager) {
            requestAlbumColorSchemeGeneration(uriString, newFlow)
        }

        return newFlow.asStateFlow()
    }

    fun ensureAlbumColorScheme(uriString: String) {
        if (uriString.isBlank()) return

        val targetFlow = individualAlbumColorSchemes[uriString]
            ?: MutableStateFlow<ColorSchemePair?>(null).also { individualAlbumColorSchemes[uriString] = it }

        if (targetFlow.value != null) return
        requestAlbumColorSchemeGeneration(uriString, targetFlow)
    }
    
    suspend fun getOrGenerateColorScheme(uriString: String): ColorSchemePair? {
         return colorSchemeProcessor.getOrGenerateColorScheme(
             albumArtUri = uriString,
             paletteStyle = currentPaletteStyle,
             colorAccuracyLevel = currentPaletteAccuracy
         )
    }

    suspend fun forceRegenerateColorScheme(
        uriString: String?,
        regenerateAllStyles: Boolean = false
    ) {
         if (uriString == null) {
             beginRequest(null)
             _currentAlbumArtColorSchemePair.value = null
             _currentAlbumArtUri.value = null
             return
         }

         // B29-fix1 F4: the regeneration below is long; the listener may have moved on by the time it ends. B30-fix1 H4: and may have
         // changed the settings, or asked for another regeneration of this cover; this one begins a new regeneration of the cover.
         regenerations.merge(uriString, 1L) { a, b -> a + b }
         val request = capture(uriString, requestGeneration)
         Timber.tag("ThemeStateHolder").d("forceRegenerateColorScheme called for: $uriString")
         Timber.tag("ThemeStateHolder").d("Current tracked global URI: ${_currentAlbumArtUri.value}")
         
         colorSchemeProcessor.invalidateScheme(uriString)

         val newScheme = if (regenerateAllStyles) {
             var selectedStyleScheme: ColorSchemePair? = null
             AlbumArtPaletteStyle.entries.forEach { style ->
                 val generated = colorSchemeProcessor.getOrGenerateColorScheme(
                     albumArtUri = uriString,
                     paletteStyle = style,
                     colorAccuracyLevel = request.accuracy,
                     forceRefresh = true
                 )
                 if (style == request.style) {
                     selectedStyleScheme = generated
                 }
             }
             selectedStyleScheme
         } else {
             colorSchemeProcessor.getOrGenerateColorScheme(
                 albumArtUri = uriString,
                 paletteStyle = request.style,
                 colorAccuracyLevel = request.accuracy,
                 forceRefresh = true
             )
         }

         // A regeneration under settings that are gone, or that a newer regeneration of this cover has replaced, stays in the
         // processor's own entry for those settings and replaces nothing that is shown.
         val activeFlow = individualAlbumColorSchemes[uriString]
         if (activeFlow != null && configCurrent(request) && regenerationCurrent(request)) {
             activeFlow.value = newScheme
         }
         
         if (_currentAlbumArtUri.value == uriString && isCurrentRequest(request)) {
             Timber.tag("ThemeStateHolder").d("Updating global color scheme flow directly.")
             _currentAlbumArtColorSchemePair.value = newScheme
         } else {
             Timber.tag("ThemeStateHolder").d("Global URI did not match. Skipping global update.")
         }
    }

    @Suppress("DEPRECATION")
    fun trimMemory(level: Int) {
        colorSchemeProcessor.clearMemoryCache()
        clearExtractedColorCache()

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            individualAlbumColorSchemes.clear()
        }

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            synchronized(pendingAlbumColorSchemeLock) {
                pendingAlbumColorSchemeTargets.clear()
            }
        }
    }

    fun onCleared() {
        scope = null
    }

    private companion object {
        /** How many times an ordinary request recomputes when the settings change under it, before it gives up. */
        const val MAX_CONFIG_RETRIES = 3
    }

}
