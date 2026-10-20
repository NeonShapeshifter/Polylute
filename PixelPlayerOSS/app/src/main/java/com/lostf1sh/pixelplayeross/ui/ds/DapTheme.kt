package com.lostf1sh.pixelplayeross.ui.ds

import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp


/**
 * The accent set. All at the same lightness and chroma; only hue moves.
 *
 * The design specifies this as a per-device choice, so it is a constant here rather than a
 * user preference.
 */
enum class DapAccent(val label: String, val color: Color, val storageKey: String) {
    Sage("Sage", Color(0xFF93B9A0), "sage"),
    Mint("Mint", Color(0xFF8FC2AE), "mint"),
    Teal("Teal", Color(0xFF8BBCBF), "teal"),
    Sky("Sky", Color(0xFF8FB3C4), "sky"),
    Blue("Blue", Color(0xFF94AECB), "blue"),
    Indigo("Indigo", Color(0xFFA0A8CC), "indigo"),
    Purple("Purple", Color(0xFFB4A8C9), "purple"),
    Lilac("Lilac", Color(0xFFB9AEC9), "lilac"),
    Pink("Pink", Color(0xFFC9A8B4), "pink"),
    Red("Red", Color(0xFFC99A9A), "red"),
    Clay("Clay", Color(0xFFC49A8F), "clay"),
    Amber("Amber", Color(0xFFC7B183), "amber"),
    Sand("Sand", Color(0xFFC9B48F), "sand"),
    Lime("Lime", Color(0xFFB4C08C), "lime"),
    Graphite("Graphite", Color(0xFFA8B0B8), "graphite");

    companion object {
        /**
         * The accent every install has always had. Anything unrecognised — a key written by a
         * newer build, a corrupted store, a value that was never an accent — resolves here rather
         * than throwing or silently landing on a different colour.
         */
        val DEFAULT = Sage

        fun fromStorageKey(key: String?): DapAccent =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/**
 * Spacing, radius, heights and thumbnails. The whole system, as numbers.
 *
 * 4 · 8 · 12 · 16 · 20 · 24 · 32 · 48 dp — screen gutter 20, section gap 24, group gap 32.
 */
object DapSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Gutter = 20.dp
    val SectionGap = 24.dp
    val GroupGap = 32.dp
    val Xl = 48.dp

    /** Content stops widening past this on large screens; rows read badly when stretched. */
    val MaxContentWidth = 600.dp
}

object DapRadius {
    /** Rows carry no radius. */
    val Row = 0.dp

    /** Everything rectangular: thumbnails, artwork, inputs, buttons, dialogs, undo bar. */
    val Surface = 4.dp

    /** Sheets only. */
    val SheetTop = 20.dp

    /** Play button, switches. */
    val Full = 1000.dp
}

object DapHeights {
    /** Technical value rows in Track Details, DAC Profile, Rules. */
    val ValueRow = 40.dp

    /** Settings rows. */
    val SettingsRow = 48.dp

    /** Media rows: songs, albums, artists, queue entries. */
    val MediaRow = 56.dp

    /** Two-line rows with a secondary state line. */
    val MediaRowTall = 56.dp

    /** Mini-player. */
    val MiniPlayer = 60.dp

    /** Bottom navigation. */
    val Nav = 72.dp

    /** Inputs: search field, buttons. */
    val Control = 44.dp
}

object DapSizes {
    val ThumbRow = 40.dp
    val ThumbFeature = 52.dp
    val ThumbGrid = 108.dp
    val Cover = 350.dp
    val CoverDedicated = 160.dp
    val ArtDetail = 132.dp
    val PlayButton = 72.dp
    val PlayButtonSmall = 44.dp

    /** Minimum touch target for important interactive controls. */
    val MinTouchTarget = 48.dp

    /** The state mark before a state word. */
    val StateMark = 6.dp
}

/**
 * The active accent. Accent means confirmed or applied — never decorative.
 *
 * **Dynamic, not static** (B29-L11). It was static, which is free while the accent only changes in Settings. The full player and the lock
 * screen now wear the cover's accent ([AlbumAccent]) and it changes with every song: a static local invalidates everything under its
 * provider on each change, a dynamic one only the composables that read it (the shuffle dot, the heart, the current row), so the cover, the
 * header and the pager are left alone (`AccentRecompositionTest`).
 */
val LocalDapAccent = compositionLocalOf { DapAccent.DEFAULT.color }

/**
 * The accent the app is set to, whatever scope a surface draws in (B31 P3.1).
 *
 * [LocalDapAccent] is overridden with the cover's colour on the full player, the queue and the lock surface. The signal mark (filled: the
 * signal reaches the DAC untouched; hollow: something processes it) says a fact about the signal, not a decoration, and in the cover's
 * colour it read like a sample-rate code. It reads this one, which only [DapTheme] provides, so it is one colour for every song.
 * Static: it changes only in Settings.
 */
val LocalDapAppAccent = staticCompositionLocalOf { DapAccent.DEFAULT.color }

/**
 * The height of the docked player shelf currently occupying the bottom of this surface, or `0.dp`
 * when the surface carries no shelf.
 *
 * The shell owns shelf visibility, so it owns this number too. A screen reserves exactly what the
 * shell is using instead of every screen assuming a shelf is present — which is how Settings
 * subpages ended up with 64 dp of dead space at the foot of the page once the shelf stopped being
 * drawn on them.
 *
 * The default is zero: a surface outside the shell reserves nothing rather than guessing.
 */
val LocalDapPlayerShelfHeight = androidx.compose.runtime.compositionLocalOf { 0.dp }

/**
 * Wraps a subtree in the DAP palette.
 *
 * The Material color scheme is built from the graphite palette rather than from the wallpaper, so
 * surfaces that have not yet been migrated to the DAP primitives still read as this product.
 *
 * [colorSchemeOverride] exists for the one case the design allows colour from content: the album
 * artwork tint on the Now Playing Artwork view, and the album/artist detail headers. Everything
 * else stays graphite.
 */
@Composable
fun DapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: DapAccent = DapAccent.Sage,
    baseTheme: DapBaseTheme = DapBaseTheme.DEFAULT,
    palette: DapPalette = baseTheme.palette,
    colorSchemeOverride: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    // Presentation, not identity: a light palette darkens the accent so it stays legible on an
    // off-white page. The stored accent is untouched, and dark palettes return it unchanged.
    val accentColor = palette.presentedAccent(accent.color)
    val scheme = colorSchemeOverride
        ?: dapColorScheme(accentColor, palette)
    CompositionLocalProvider(
        LocalDapAccent provides accentColor,
        LocalDapAppAccent provides accentColor,
        LocalDapPalette provides palette,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = DapMaterialTypography,
            shapes = DapMaterialShapes,
        ) {
            ProvideDapSharedPainters(content)
        }
    }
}

/**
 * The Material scheme, derived from the DAP palette and the presented accent.
 *
 * This is the reconciliation the previous pass left open: `lightScheme` used to hardcode its own
 * neutrals, so a Light `DapPalette` would have sat underneath a Material scheme describing a
 * different appearance — two authorities disagreeing on the same screen, wherever a surface is
 * still built from Material colours.
 *
 * There is now one builder. Every role it can fill comes from the palette; the family
 * ([darkColorScheme] vs [lightColorScheme]) follows [DapPalette.isLight] so the roles it *cannot*
 * fill — the inverse pair, surface tints, the scrim defaults — land on the right side.
 *
 * The accent arrives already presented (see [DapPalette.presentedAccent]), so Material never sees
 * a pastel it cannot draw legibly.
 */
internal fun dapColorScheme(accent: Color, palette: DapPalette) = baseDapColorScheme(accent, palette)
    .withDapUnmappedRoles(accent, palette)

/**
 * The roles the two builders below leave to Material, filled from the palette.
 *
 * Left unset, these fell back to Material's baseline purple-and-pink: a maroon
 * `tertiaryContainer` behind Stats' summary card and the queue's overflow action, a pink
 * `tertiaryFixedDim` behind the lyrics play control, and a lilac [ColorScheme.surfaceTint] wash
 * over any tonally elevated surface. None of that is this product.
 *
 * - Containers are the raised surface with primary ink, the same answer the primary and secondary
 *   containers already give.
 * - The fixed roles are the DS's one filled control: primary-text fill with [DapPalette.onFill] ink.
 * - Error containers stay error — tinted from [DapPalette.error], never grey — so a failure still
 *   reads as a failure wherever a Material component draws one.
 * - Surface tint is transparent: elevation is not expressed with colour here.
 */
private fun ColorScheme.withDapUnmappedRoles(accent: Color, palette: DapPalette): ColorScheme = copy(
    tertiaryContainer = palette.surfaceRaised,
    onTertiaryContainer = palette.primaryText,
    errorContainer = palette.error.copy(alpha = ERROR_CONTAINER_ALPHA).compositeOver(palette.surface),
    onErrorContainer = palette.primaryText,
    primaryFixed = palette.primaryText,
    primaryFixedDim = palette.primaryText,
    onPrimaryFixed = palette.onFill,
    onPrimaryFixedVariant = palette.onFill,
    secondaryFixed = palette.primaryText,
    secondaryFixedDim = palette.primaryText,
    onSecondaryFixed = palette.onFill,
    onSecondaryFixedVariant = palette.onFill,
    tertiaryFixed = palette.primaryText,
    tertiaryFixedDim = palette.primaryText,
    onTertiaryFixed = palette.onFill,
    onTertiaryFixedVariant = palette.onFill,
    surfaceBright = palette.surfaceRaised,
    surfaceDim = palette.background,
    inverseSurface = palette.primaryText,
    inverseOnSurface = palette.background,
    inversePrimary = accent,
    surfaceTint = Color.Transparent,
)

/** How strongly an error container is tinted: enough to read as a failure, not as an alarm. */
private const val ERROR_CONTAINER_ALPHA = 0.18f

private fun baseDapColorScheme(accent: Color, palette: DapPalette) = if (palette.isLight) {
    lightColorScheme(
        primary = accent,
        onPrimary = palette.onFill,
        primaryContainer = palette.surfaceRaised,
        onPrimaryContainer = palette.primaryText,
        secondary = palette.secondaryText,
        onSecondary = palette.onFill,
        secondaryContainer = palette.surfaceRaised,
        onSecondaryContainer = palette.primaryText,
        tertiary = accent,
        onTertiary = palette.onFill,
        background = palette.background,
        onBackground = palette.primaryText,
        surface = palette.background,
        onSurface = palette.primaryText,
        surfaceVariant = palette.surface,
        onSurfaceVariant = palette.secondaryText,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.surface,
        surfaceContainerHighest = palette.surfaceRaised,
        surfaceContainerLow = palette.background,
        surfaceContainerLowest = palette.background,
        outline = palette.tertiaryText,
        outlineVariant = palette.divider,
        error = palette.error,
        onError = palette.onFill,
        scrim = palette.scrim,
    )
} else {
    darkColorScheme(
        primary = accent,
        onPrimary = palette.onFill,
        primaryContainer = palette.surfaceRaised,
        onPrimaryContainer = palette.primaryText,
        secondary = palette.secondaryText,
        onSecondary = palette.onFill,
        secondaryContainer = palette.surfaceRaised,
        onSecondaryContainer = palette.primaryText,
        tertiary = accent,
        onTertiary = palette.onFill,
        background = palette.background,
        onBackground = palette.primaryText,
        surface = palette.background,
        onSurface = palette.primaryText,
        surfaceVariant = palette.surface,
        onSurfaceVariant = palette.secondaryText,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.surface,
        surfaceContainerHighest = palette.surfaceRaised,
        surfaceContainerLow = palette.background,
        surfaceContainerLowest = palette.background,
        outline = palette.tertiaryText,
        outlineVariant = palette.divider,
        // A dark theme's "error" role has always carried the attention colour here; the palette
        // keeps both, and the dark family keeps the existing choice so graphite does not shift.
        error = palette.warning,
        onError = palette.onFill,
        scrim = palette.scrim,
    )
}

// display* and headline* used to fall through to Material's defaults: the platform font at up to
// 57 sp, which is what made unmigrated screens read as a different product. Nothing in the DS is
// larger than a screen title, so the large roles resolve to Title and the smallest to Heading.
private val DapMaterialTypography = androidx.compose.material3.Typography(
    displayLarge = DapType.Title,
    displayMedium = DapType.Title,
    displaySmall = DapType.Title,
    headlineLarge = DapType.Title,
    headlineMedium = DapType.Title,
    headlineSmall = DapType.Heading,
    titleLarge = DapType.Title,
    titleMedium = DapType.Heading,
    titleSmall = DapType.Row,
    bodyLarge = DapType.Body,
    bodyMedium = DapType.Caption,
    bodySmall = DapType.Note,
    labelLarge = DapType.Button,
    labelMedium = DapType.CaptionMedium,
    labelSmall = DapType.SectionHeader,
)

private val DapMaterialShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(DapRadius.Surface),
    small = androidx.compose.foundation.shape.RoundedCornerShape(DapRadius.Surface),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(DapRadius.Surface),
    large = androidx.compose.foundation.shape.RoundedCornerShape(DapRadius.SheetTop),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(DapRadius.SheetTop),
)
