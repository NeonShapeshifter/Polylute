package com.lostf1sh.pixelplayeross.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemePair
import com.lostf1sh.pixelplayeross.ui.ds.AlbumArtColors
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.glass.CoverGlassFields
import com.lostf1sh.pixelplayeross.ui.glass.CoverGlassScene
import com.lostf1sh.pixelplayeross.ui.glass.GlassBands
import com.lostf1sh.pixelplayeross.ui.glass.GlassKind
import com.lostf1sh.pixelplayeross.ui.glass.GlassPainted
import com.lostf1sh.pixelplayeross.ui.glass.rememberCoverGlassScene
import com.lostf1sh.pixelplayeross.ui.glass.rememberGlassPainted
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How long a song's cover must stay the song's before the app's colours change to it: skipping through a queue re-themes the app once, where it stops. */
internal const val ALBUM_ART_THEME_SETTLE_MS = 350L

/** Which backdrop a colour source draws: the glass of "Cover glass", the gradient of "From album art", or none ("Theme and accent"). */
fun backdropKindOf(appColorSource: String?): GlassKind? = when (appColorSource) {
    AppColorSource.COVER_GLASS -> GlassKind.GLASS
    AppColorSource.ALBUM_ART -> GlassKind.GRADIENT
    else -> null
}

/**
 * What the root paints the app with for this colour source and this song.
 *
 * @property painted the theme, palette and accent to draw, and whether the backdrop is on screen.
 * @property scene the backdrop (what the root, the lock screen and the sheets draw behind the app).
 */
@Immutable
class AppPainting(val painted: GlassPainted, val scene: CoverGlassScene)

/**
 * B42-fix3 (items 1 and 7): the one place that decides how the app is painted, so the app and the tests that count what a song change costs
 * run the same code.
 *
 * **What a song change hands the theme.** With "Theme and accent" nothing: the colours are the listener's. In the cover modes the theme is
 * Graphite for every cover, the ink is the glass's (always light) and the see-through panels are a fixed veil, so the **palette is the same
 * for every song** and the only thing that follows the song is the accent, plus the backdrop drawn behind everything. A new palette would be a
 * new value for a static composition local and re-run the whole app under it; a new accent only reaches the composables that read it.
 *
 * The cover's colour settles for [settleMs] before it changes anything (the same 350 ms as the glass), so skipping through a queue re-themes
 * the app once, where it stops.
 *
 * @param albumArtColorScheme the scheme of the cover of the song that is playing (the theme holder's).
 * @param themedCoverUri the cover of the song that is playing, as the theme holder publishes it: null for a song with no cover.
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberAppPainting(
    appColorSource: String,
    appThemeMode: String,
    storedBaseTheme: DapBaseTheme,
    accent: DapAccent,
    systemDark: Boolean,
    albumArtColorScheme: Flow<ColorSchemePair?>,
    themedCoverUri: Flow<String?>,
    fields: CoverGlassFields,
    settleMs: Long = ALBUM_ART_THEME_SETTLE_MS,
): AppPainting {
    val drawnBaseTheme = resolveDrawnBaseTheme(appColorSource, appThemeMode, storedBaseTheme, systemDark)
    val useDarkTheme = !drawnBaseTheme.palette.isLight
    val coverColor by remember(useDarkTheme, albumArtColorScheme) {
        albumArtColorScheme
            .map { pair -> pair?.let { if (useDarkTheme) it.dark else it.light }?.primary }
            .distinctUntilChanged()
            .debounce(settleMs)
    }.collectAsStateWithLifecycle(initialValue = null)
    val albumArtLook = remember(appColorSource, drawnBaseTheme, accent, coverColor) {
        if (AppColorSource.takesColoursFromCover(appColorSource)) {
            AlbumArtColors.look(drawnBaseTheme.palette, coverColor, accent)
        } else {
            null
        }
    }
    val kind = backdropKindOf(appColorSource)
    // "Cover glass" and "From album art" are the look above plus a backdrop behind the whole app. It settles like the colours do (the same
    // 350 ms) and then cross-fades (400 ms); the ink is always light and the field is darkened only as far as AA needs (GlassBands).
    val glassLook = remember(kind, albumArtLook) {
        albumArtLook?.takeIf { kind != null }?.let { GlassBands.of(it.palette, it.accent) }
    }
    val band = remember(drawnBaseTheme) { GlassBands.of(drawnBaseTheme.palette, Color.White).band }
    val scene = rememberCoverGlassScene(kind = kind, band = band, coverUri = themedCoverUri, fields = fields, settleMs = settleMs)
    val painted = rememberGlassPainted(drawnBaseTheme, albumArtLook, glassLook, scene)
    return AppPainting(painted, scene)
}
