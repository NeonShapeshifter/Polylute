package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The DAP's neutral palette, as semantic roles.
 *
 * This replaces a static `object` of raw colour constants. Constants cannot vary per theme, do not
 * participate in Compose invalidation, and left every consumer reading a value the theme could not
 * change — which is why the app could not offer more than one appearance.
 *
 * Roles are named for what a surface *is*, not for what colour it happens to be today. That is what
 * lets a second palette exist later without a second set of names.
 *
 * The accent is deliberately **not** here. It is a separate authority
 * ([LocalDapAccent][androidx.compose.runtime.CompositionLocal]) because it is a user preference
 * that moves independently of the base palette, and because accent must never be asked to carry a
 * warning or error meaning.
 *
 * Every field maps 1:1 onto a token of the palette this replaced, with the same value, so the
 * migration is provably appearance-neutral. Where two roles hold the same value today they are kept
 * separate anyway when a light or black palette would need to distinguish them — a hairline and a
 * border are not the same idea just because graphite draws them at similar alphas.
 */
@Immutable
data class DapPalette(
    // --- Surfaces, in stacking order -------------------------------------------------------

    /** The page itself. */
    val background: Color,
    /** Docked or overlaid panels: the mini player, sheets, the segmented track. */
    val surface: Color,
    /** One step above [surface]: secondary buttons, inputs, placeholder art wells. */
    val surfaceRaised: Color,

    // --- Lines -----------------------------------------------------------------------------

    /** The faintest separation; available for surfaces that need a whisper of an edge. */
    val dividerFaint: Color,
    /** The row separator. */
    val divider: Color,
    /** Outlines around a real boundary — inputs, selected containers. */
    val border: Color,
    /** The unfilled part of a progress track. */
    val trackStroke: Color,
    /** Slider and switch tracks: interactive, so slightly stronger than a plain divider. */
    val interactiveTrack: Color,
    /** A guide line drawn inside technical readouts, such as the EQ editor's zero line. */
    val dividerStrong: Color,

    // --- Text, in descending emphasis --------------------------------------------------------

    /** Titles and values the user reads. */
    val primaryText: Color,
    /** Supporting lines: descriptions, artist names, state words. */
    val secondaryText: Color,
    /** Lowest-emphasis live text: section labels, axis captions. */
    val tertiaryText: Color,
    /** Unavailable or disabled. Not a dimmer [tertiaryText] — it means "not applicable". */
    val disabledText: Color,

    // --- Meaning -----------------------------------------------------------------------------

    /** Attention, not failure: over-ceiling, recalibration needed, destructive-but-intended. */
    val warning: Color,
    /** Failure. Kept as a role so no consumer has to invent a red. */
    val error: Color,

    // --- Consumed content --------------------------------------------------------------------

    /** The two tones of the generated artwork placeholder. */
    val artPlaceholderHigh: Color,
    val artPlaceholderLow: Color,
    /** Placeholder for artwork that is known to be absent rather than merely unloaded. */
    val artPlaceholderDisabled: Color,

    /** Ink placed on top of an accent or primary-text filled control. */
    val onFill: Color,

    /** The dimming layer behind a modal. A dark theme dims with black; a light one must not. */
    val scrim: Color,

    /**
     * Whether this palette is a light appearance.
     *
     * Drives the Material scheme family and the accent presentation below; it is not a colour.
     */
    val isLight: Boolean,
) {
    /**
     * The accent as it should be *presented* on this palette.
     *
     * The curated accents are light pastels chosen to read against graphite. On a light palette
     * that same pastel sits at roughly 1.9:1 against an off-white page — legible enough to notice,
     * nowhere near enough to read or to aim at. The lightest accent of the fifteen, Lime, needed a
     * blend of 0.48 to clear 4.5:1 against the raised surface; 0.45 left it at 4.43:1.
     *
     * The fix is presentation, not identity: the stored accent is untouched, and a light palette
     * presents a hue-preserving darkened variant of it. The accent the user chose is still the
     * accent they chose; it is simply rendered at the lightness this background needs.
     *
     * Dark palettes present the accent unchanged, so graphite is bit-for-bit what it always was.
     */
    fun presentedAccent(raw: Color): Color =
        if (!isLight) raw else blend(raw, Color.Black, LIGHT_ACCENT_BLEND)

    private fun blend(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha,
    )

    companion object {

        /**
         * The appearance the product has always had.
         *
         * Every value here is byte-for-byte the constant it replaces. Changing one changes the app
         * for every existing install, so this table is pinned by test.
         */
        val Graphite = DapPalette(
            background = Color(0xFF0E0F11),
            surface = Color(0xFF16181B),
            surfaceRaised = Color(0xFF1E2125),

            dividerFaint = Color(0x0FFFFFFF),
            divider = Color(0x14FFFFFF),
            border = Color(0x1AFFFFFF),
            trackStroke = Color(0x1FFFFFFF),
            interactiveTrack = Color(0x24FFFFFF),
            dividerStrong = Color(0x29FFFFFF),

            primaryText = Color(0xFFEDEEF0),
            secondaryText = Color(0xFFA0A2A6),
            tertiaryText = Color(0xFF7B7D82),
            disabledText = Color(0xFF5A5C61),

            warning = Color(0xFFD9A066),
            error = Color(0xFFD32F2F),

            artPlaceholderHigh = Color(0xFF2A2E33),
            artPlaceholderLow = Color(0xFF22262A),
            artPlaceholderDisabled = Color(0xFF3A3E44),

            onFill = Color(0xFF0E0F11),
            scrim = Color(0xCC000000),
            isLight = false,
        )

        /**
         * OLED-friendly black.
         *
         * The page is true black so an OLED panel can switch those pixels off, but the surfaces
         * above it are deliberately *not* black: a black page with black sheets has no depth at
         * all, which is the failure mode of a theme that only changes one value. Text, warning and
         * error are the graphite values — they were never the problem.
         */
        val Black = DapPalette(
            background = Color(0xFF000000),
            surface = Color(0xFF0B0C0E),
            surfaceRaised = Color(0xFF191B1E),

            dividerFaint = Color(0x0FFFFFFF),
            divider = Color(0x14FFFFFF),
            border = Color(0x1AFFFFFF),
            trackStroke = Color(0x1FFFFFFF),
            interactiveTrack = Color(0x24FFFFFF),
            dividerStrong = Color(0x29FFFFFF),

            primaryText = Color(0xFFEDEEF0),
            secondaryText = Color(0xFFA0A2A6),
            tertiaryText = Color(0xFF7B7D82),
            disabledText = Color(0xFF5A5C61),

            warning = Color(0xFFD9A066),
            error = Color(0xFFD32F2F),

            artPlaceholderHigh = Color(0xFF1C1F23),
            artPlaceholderLow = Color(0xFF141719),
            artPlaceholderDisabled = Color(0xFF2A2D31),

            onFill = Color(0xFF000000),
            scrim = Color(0xCC000000),
            isLight = false,
        )

        /**
         * Restrained light.
         *
         * Off-white rather than white, and cool rather than warm, so it reads as this product's
         * light appearance instead of a default Material app. The stacking direction is inverted
         * relative to graphite — on a light page, a raised container is *darker* than the page, not
         * lighter — which is Material's own light convention and the one the previous hardcoded
         * light scheme used.
         *
         * The hairlines invert with it: dark at low alpha instead of white at low alpha, because
         * white-on-white has no hairline to give.
         */
        val Light = DapPalette(
            background = Color(0xFFF7F7F8),
            surface = Color(0xFFEFEFF1),
            surfaceRaised = Color(0xFFE3E3E6),

            dividerFaint = Color(0x0F000000),
            divider = Color(0x14000000),
            border = Color(0x1F000000),
            trackStroke = Color(0x24000000),
            interactiveTrack = Color(0x2B000000),
            dividerStrong = Color(0x33000000),

            primaryText = Color(0xFF1A1C1E),
            secondaryText = Color(0xFF4A4D52),
            tertiaryText = Color(0xFF6B6E74),
            disabledText = Color(0xFF9A9DA3),

            warning = Color(0xFF8A5A22),
            error = Color(0xFFB3261E),

            artPlaceholderHigh = Color(0xFFDDDEE1),
            artPlaceholderLow = Color(0xFFE7E8EA),
            artPlaceholderDisabled = Color(0xFFCFD1D5),

            // Ink for a filled control. A light palette presents a *darkened* accent, so the ink on
            // it must be light — the opposite of graphite, where the accent is a light pastel.
            onFill = Color(0xFFFAFAFB),
            scrim = Color(0x66000000),
            isLight = true,
        )

        /** How far a light palette darkens an accent before presenting it. */
        const val LIGHT_ACCENT_BLEND = 0.48f

        /** The palette the app uses when no theme has provided one. */
        val Default: DapPalette = Graphite
    }
}

/**
 * The three curated base appearances.
 *
 * Persisted by [storageKey], so the keys are part of the data contract: renaming one silently
 * resets every install that had chosen it back to the default.
 */
enum class DapBaseTheme(val label: String, val storageKey: String) {
    Graphite("Graphite", "graphite"),
    Black("Black", "black"),
    Light("Light", "light");

    /** The palette this choice selects. */
    val palette: DapPalette
        get() = when (this) {
            Graphite -> DapPalette.Graphite
            Black -> DapPalette.Black
            Light -> DapPalette.Light
        }

    companion object {
        /** What every install that has never chosen a base theme gets. */
        val DEFAULT = Graphite

        /**
         * Anything unrecognised — a key from a newer build, a corrupted store, a value that was
         * never a theme — resolves to the default rather than throwing or landing on another
         * appearance.
         */
        fun fromStorageKey(key: String?): DapBaseTheme =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/**
 * The active base palette.
 *
 * A missing provider yields [DapPalette.Default] rather than failing, so a preview, a test, or a
 * stray surface outside the theme still renders this product's colours.
 */
val LocalDapPalette = staticCompositionLocalOf { DapPalette.Default }

/**
 * Read the active palette: `Dap.primaryText`, `Dap.background`.
 *
 * Composable-only by construction. A non-composable site — a `drawBehind`, a `Canvas` scope, a
 * `graphicsLayer` — cannot read this and must instead capture the values it needs during
 * composition and close over them. That restriction is the point: it is what stops a drawn surface
 * from silently keeping a stale colour when the palette changes.
 */
val Dap: DapPalette
    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = LocalDapPalette.current
