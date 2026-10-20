package com.lostf1sh.pixelplayeross.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.util.lerp
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.ui.ds.AlbumAccent
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreference
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemePair

/**
 * Theme state for the player sheet.
 *
 * Expansion-dependent values ([miniAlpha], elevation) are **no longer** included here.
 * They are computed inline in the consuming composable's `graphicsLayer` / `derivedStateOf`,
 * reading directly from the [Animatable] expansion fraction during the draw phase.
 * This eliminates per-frame recomposition that the old [Transition]-based approach caused.
 */
internal data class SheetThemeState(
    val albumColorScheme: ColorScheme,
    /**
     * The accent the current cover gives the full player (B29-L11), or null: no cover, the player colour set to neutral, or nothing extracted
     * yet (then the app's accent stays). Settles at once: it is read from the scheme the sheet already holds, not animated.
     */
    val albumAccent: Color?,
    val miniPlayerScheme: ColorScheme,
    val isPreparingPlayback: Boolean,
    val miniReadyAlpha: Float,
    val miniAppearScale: Float,
    val playerAreaBackground: Color
)

internal fun resolvePlayerSheetTargetScheme(
    isAlbumArtTheme: Boolean,
    hasAlbumArt: Boolean,
    currentSongActiveScheme: ColorScheme?,
    lastAlbumScheme: ColorScheme?,
    systemColorScheme: ColorScheme
): ColorScheme {
    return when {
        !isAlbumArtTheme || !hasAlbumArt -> systemColorScheme
        currentSongActiveScheme != null -> currentSongActiveScheme
        lastAlbumScheme != null -> lastAlbumScheme
        else -> systemColorScheme
    }
}

@Composable
internal fun rememberSheetThemeState(
    activePlayerSchemePair: ColorSchemePair?,
    isDarkTheme: Boolean,
    playerThemePreference: String,
    currentSong: Song?,
    themedAlbumArtUri: String?,
    preparingSongId: String?,
    systemColorScheme: ColorScheme,
    /**
     * Whether anything on screen wears the crossfade between one cover's colours and the next: the
     * queue and the song sheet, the only readers of [SheetThemeState.albumColorScheme] besides the
     * graphite player. Hidden, the scheme settles at once (AUD-113).
     */
    animateAlbumScheme: Boolean = true,
): SheetThemeState {
    val isAlbumArtTheme = playerThemePreference == ThemePreference.ALBUM_ART
    val hasAlbumArt = !currentSong?.albumArtUriString.isNullOrBlank()

    val activePlayerScheme = remember(activePlayerSchemePair, isDarkTheme) {
        activePlayerSchemePair?.let { if (isDarkTheme) it.dark else it.light }
    }
    val currentSongActiveScheme = remember(
        activePlayerScheme,
        currentSong?.albumArtUriString,
        themedAlbumArtUri
    ) {
        if (
            activePlayerScheme != null &&
            hasAlbumArt &&
            currentSong.albumArtUriString == themedAlbumArtUri
        ) {
            activePlayerScheme
        } else {
            null
        }
    }

    var lastAlbumScheme by remember { mutableStateOf<ColorScheme?>(null) }
    var lastAlbumSchemeSongId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentSong?.id) {
        if (currentSong?.id != lastAlbumSchemeSongId) {
            lastAlbumSchemeSongId = currentSong?.id
        }
    }
    LaunchedEffect(currentSongActiveScheme, currentSong?.id) {
        val currentSongId = currentSong?.id
        if (currentSongId != null && currentSongActiveScheme != null) {
            lastAlbumScheme = currentSongActiveScheme
            lastAlbumSchemeSongId = currentSongId
        }
    }

    val isPreparingPlayback = remember(preparingSongId, currentSong?.id) {
        preparingSongId != null && preparingSongId == currentSong?.id
    }

    val lastAlbumSchemeSnapshot = lastAlbumScheme

    // The colour on screen is the current song's once it is ready and the last cover's until then (never a flash of the app's accent
    // between two covers); a song with no cover, or the neutral setting, has none.
    val albumAccentScheme = if (isAlbumArtTheme && hasAlbumArt) currentSongActiveScheme ?: lastAlbumSchemeSnapshot else null
    val accentSurface = Dap.background
    val albumAccent = remember(albumAccentScheme, accentSurface) { AlbumAccent.fromScheme(albumAccentScheme, accentSurface) }

    val rawAlbumColorScheme = resolvePlayerSheetTargetScheme(
        isAlbumArtTheme = isAlbumArtTheme,
        hasAlbumArt = hasAlbumArt,
        currentSongActiveScheme = currentSongActiveScheme,
        lastAlbumScheme = lastAlbumSchemeSnapshot,
        systemColorScheme = systemColorScheme
    )

    val rawMiniPlayerScheme = resolvePlayerSheetTargetScheme(
        isAlbumArtTheme = isAlbumArtTheme,
        hasAlbumArt = hasAlbumArt,
        currentSongActiveScheme = currentSongActiveScheme,
        lastAlbumScheme = lastAlbumSchemeSnapshot,
        systemColorScheme = systemColorScheme
    )

    val albumColorScheme = rememberBatchAnimatedColorScheme(rawAlbumColorScheme, animate = animateAlbumScheme)
    // The docked shelf is graphite; artwork colour never bleeds onto it (see playerAreaBackground), so
    // nothing draws a crossfade of this scheme and it never animates.
    val miniPlayerScheme = rememberBatchAnimatedColorScheme(rawMiniPlayerScheme, animate = false)

    val miniAppearProgress = remember { Animatable(0f) }
    LaunchedEffect(currentSong?.id) {
        if (currentSong == null) {
            miniAppearProgress.snapTo(0f)
        } else if (miniAppearProgress.value < 1f) {
            miniAppearProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
            )
        }
    }

    val miniReadyAlpha = miniAppearProgress.value
    val miniAppearScale = lerp(0.985f, 1f, miniAppearProgress.value)
    // The mini-player is part of the device's chrome, so it is graphite like the rest of the
    // chrome. Artwork colour never bleeds onto it.
    val playerAreaBackground = Dap.surface

    return SheetThemeState(
        albumColorScheme = albumColorScheme,
        albumAccent = albumAccent,
        miniPlayerScheme = miniPlayerScheme,
        isPreparingPlayback = isPreparingPlayback,
        miniReadyAlpha = miniReadyAlpha,
        miniAppearScale = miniAppearScale,
        playerAreaBackground = playerAreaBackground
    )
}

/**
 * Animates a [ColorScheme] transition using a single [Animatable]<Float> progress value
 * instead of 34 independent [animateColorAsState] calls.
 *
 * When [target] changes, a spring from 0→1 is run once, and the interpolated scheme is
 * derived from it. This reduces the number of running Springs from 34 → 1 and eliminates
 * the 34 concurrent [State] reads that were triggering recomposition on every animation frame.
 *
 * Every frame of that spring still builds a whole new [ColorScheme] and hands it to whoever reads
 * it, so a track change was 20-60 recompositions of the sheet (AUD-113). With [animate] false the
 * scheme settles on [target] at once: no frames, no interpolated copies, and the caller reads
 * nothing that changes while the cover's colours would have been crossfading.
 */
@Composable
internal fun rememberBatchAnimatedColorScheme(target: ColorScheme, animate: Boolean = true): ColorScheme {
    val progress = remember { Animatable(1f) }
    var fromScheme by remember { mutableStateOf(target) }
    var toScheme by remember { mutableStateOf(target) }

    LaunchedEffect(target, animate) {
        if (!animate) {
            // Also ends an animation left half-way by a flip to false, so a later flip back starts clean.
            fromScheme = target
            toScheme = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        if (toScheme == target) return@LaunchedEffect
        fromScheme = lerpColorScheme(fromScheme, toScheme, progress.value)
        toScheme = target
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(stiffness = Spring.StiffnessLow)
        )
    }

    val interpolated by remember {
        // At rest it is the scheme itself, not a copy of it: no allocation per read once settled.
        derivedStateOf {
            if (progress.value >= 1f) toScheme else lerpColorScheme(fromScheme, toScheme, progress.value)
        }
    }
    // Not read when not animating, so this scope subscribes to nothing that moves per frame.
    return if (animate) interpolated else target
}

/**
 * Manually interpolates every field of two [ColorScheme]s by [t] ∈ [0, 1].
 * Called once per animation frame (inside [derivedStateOf]) — O(29) lerp ops, negligible CPU.
 */
private fun lerpColorScheme(from: ColorScheme, to: ColorScheme, t: Float): ColorScheme =
    to.copy(
        primary                = lerp(from.primary, to.primary, t),
        onPrimary              = lerp(from.onPrimary, to.onPrimary, t),
        primaryContainer       = lerp(from.primaryContainer, to.primaryContainer, t),
        onPrimaryContainer     = lerp(from.onPrimaryContainer, to.onPrimaryContainer, t),
        secondary              = lerp(from.secondary, to.secondary, t),
        onSecondary            = lerp(from.onSecondary, to.onSecondary, t),
        secondaryContainer     = lerp(from.secondaryContainer, to.secondaryContainer, t),
        onSecondaryContainer   = lerp(from.onSecondaryContainer, to.onSecondaryContainer, t),
        tertiary               = lerp(from.tertiary, to.tertiary, t),
        onTertiary             = lerp(from.onTertiary, to.onTertiary, t),
        tertiaryContainer      = lerp(from.tertiaryContainer, to.tertiaryContainer, t),
        onTertiaryContainer    = lerp(from.onTertiaryContainer, to.onTertiaryContainer, t),
        surface                = lerp(from.surface, to.surface, t),
        onSurface              = lerp(from.onSurface, to.onSurface, t),
        surfaceVariant         = lerp(from.surfaceVariant, to.surfaceVariant, t),
        onSurfaceVariant       = lerp(from.onSurfaceVariant, to.onSurfaceVariant, t),
        background             = lerp(from.background, to.background, t),
        onBackground           = lerp(from.onBackground, to.onBackground, t),
        inverseSurface         = lerp(from.inverseSurface, to.inverseSurface, t),
        inverseOnSurface       = lerp(from.inverseOnSurface, to.inverseOnSurface, t),
        inversePrimary         = lerp(from.inversePrimary, to.inversePrimary, t),
        surfaceContainerLowest = lerp(from.surfaceContainerLowest, to.surfaceContainerLowest, t),
        surfaceContainerLow    = lerp(from.surfaceContainerLow, to.surfaceContainerLow, t),
        surfaceContainer       = lerp(from.surfaceContainer, to.surfaceContainer, t),
        surfaceContainerHigh   = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, t),
        surfaceContainerHighest = lerp(from.surfaceContainerHighest, to.surfaceContainerHighest, t),
        outline                = lerp(from.outline, to.outline, t),
        outlineVariant         = lerp(from.outlineVariant, to.outlineVariant, t),
        surfaceTint            = lerp(from.surfaceTint, to.surfaceTint, t),
        error                  = lerp(from.error, to.error, t),
        onError                = lerp(from.onError, to.onError, t),
        errorContainer         = lerp(from.errorContainer, to.errorContainer, t),
        onErrorContainer       = lerp(from.onErrorContainer, to.onErrorContainer, t),
        scrim                  = lerp(from.scrim, to.scrim, t),
        surfaceBright          = lerp(from.surfaceBright, to.surfaceBright, t),
        surfaceDim             = lerp(from.surfaceDim, to.surfaceDim, t),
    )
