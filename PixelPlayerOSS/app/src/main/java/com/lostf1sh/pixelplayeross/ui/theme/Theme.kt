package com.lostf1sh.pixelplayeross.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemePair
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapPalette
import com.lostf1sh.pixelplayeross.ui.ds.dapColorScheme
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable

val LocalPixelPlayerDarkTheme = staticCompositionLocalOf { false }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Suppress("DEPRECATION")
@Composable
fun PixelPlayerStatusBarStyle(
    color: Color,
    useDarkIcons: Boolean = ColorUtils.calculateLuminance(color.toArgb()) > 0.55,
    navigationColor: Color? = null,
    useDarkNavigationIcons: Boolean = navigationColor
        ?.let { ColorUtils.calculateLuminance(it.toArgb()) > 0.55 }
        ?: useDarkIcons
) {
    val view = LocalView.current
    if (view.isInEditMode) return

    val updateNavigationBar = navigationColor != null
    // B41-fix1 (B2): this runs after every recomposition of the theme, and a theme change recomposes it. The bars are transparent and
    // un-contrasted whatever the theme is; only the icons' lightness follows it. Setting a window property is not free (on Android 15 a
    // colour goes to the activity's window-controller callback, and so to the system server, whether or not it changed), so a property
    // is written only when the window does not already have the value: between two dark themes nothing is written at all, and a switch
    // between a dark and a light one flips the icon appearance, which is what it needs and all it needs.
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: view.context.findActivity()?.window
            ?: return@SideEffect
        if (window.statusBarColor != android.graphics.Color.TRANSPARENT) window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && window.isStatusBarContrastEnforced) {
            window.isStatusBarContrastEnforced = false
        }

        WindowCompat.getInsetsController(window, view).run {
            if (isAppearanceLightStatusBars != useDarkIcons) isAppearanceLightStatusBars = useDarkIcons

            if (updateNavigationBar) {
                if (window.navigationBarColor != android.graphics.Color.TRANSPARENT) window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && window.isNavigationBarContrastEnforced) {
                    window.isNavigationBarContrastEnforced = false
                }
                if (isAppearanceLightNavigationBars != useDarkNavigationIcons) isAppearanceLightNavigationBars = useDarkNavigationIcons
            }
        }
    }
}

/**
 * B16-G3: the activity window's own background follows the app's. Wherever the window shows through (behind a system bar the
 * content does not reach, the first frame after a relaunch) it is then the app's colour, not the theme's default, which is
 * white in a light system mode while the app draws dark. Only the activity's window: a dialog keeps its own.
 *
 * B41-fix1 (B2): it is set on the first composition (the first frame of a created activity) and again when the activity stops, **not**
 * at every palette change while it is in front. A new background drawable on the decor view asks for a layout of the whole window, and
 * on the HONOR each theme switch showed as `VRI[MainActivity]: Visible with new config ... Applying new config to window` and a stutter;
 * the content covers the window entirely, so nothing of the window's own colour is seen while the app is up, and the next time it
 * starts or comes back it is the right one.
 */
@Composable
fun PixelPlayerWindowBackground(color: Color = MaterialTheme.colorScheme.background) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val argb = color.toArgb()
    val wanted = rememberUpdatedState(argb)
    val appliedOnce = remember(view) { BooleanArray(1) }
    SideEffect {
        if (appliedOnce[0]) return@SideEffect
        appliedOnce[0] = true
        applyWindowBackground(view, argb)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) applyWindowBackground(view, wanted.value)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

private fun applyWindowBackground(view: View, argb: Int) {
    if (view.parent is DialogWindowProvider) return
    val window = view.context.findActivity()?.window ?: return
    val current = (window.decorView.background as? ColorDrawable)?.color
    if (current != argb) window.setBackgroundDrawable(argb.toDrawable())
}

val DarkColorScheme = darkColorScheme(
    primary = PixelPlayerPurplePrimary,
    secondary = PixelPlayerPink,
    tertiary = PixelPlayerOrange,
    background = PixelPlayerPurpleDark,
    surface = PixelPlayerSurface,
    onPrimary = PixelPlayerWhite,
    onSecondary = PixelPlayerWhite,
    onTertiary = PixelPlayerWhite,
    onBackground = PixelPlayerWhite,
    onSurface = PixelPlayerLightPurple,
    error = Color(0xFFFF5252),
    onError = PixelPlayerWhite
)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = PixelPlayerWhite,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = PixelPlayerPink,
    onSecondary = PixelPlayerWhite,
    secondaryContainer = PixelPlayerPink.copy(alpha = 0.15f),
    onSecondaryContainer = PixelPlayerPink.copy(alpha = 0.85f),
    tertiary = PixelPlayerOrange,
    onTertiary = PixelPlayerBlack,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline.copy(alpha = 0.6f),
    surfaceTint = LightPrimary,
    error = Color(0xFFD32F2F),
    onError = PixelPlayerWhite
)

/**
 * The app theme.
 *
 * The house palette owns the app: graphite neutrals plus one curated accent. Wallpaper-derived
 * Material You colouring is deliberately not used — the screen recedes and artwork carries the
 * colour.
 *
 * [colorSchemePairOverride] is the one remaining content-derived path: an album or artist tint,
 * used only where the design allows it (album/artist detail headers and the Now Playing Artwork
 * view's wash). When it is null the graphite scheme applies.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PixelPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorSchemePairOverride: ColorSchemePair? = null,
    accent: DapAccent = DapAccent.DEFAULT,
    baseTheme: DapBaseTheme = DapBaseTheme.DEFAULT,
    palette: DapPalette = baseTheme.palette,
    /** B40-F1: album-art mode: the accent is the cover's, already readable on [palette]. */
    accentColorOverride: Color? = null,
    content: @Composable () -> Unit
) {
    val override = colorSchemePairOverride?.let { if (darkTheme) it.dark else it.light }
    // The accent is a user preference now, so it drives both the DAP token and the Material
    // scheme it mirrors — previously this read the constant directly and the preference could not
    // have had any effect on surfaces still built from Material colours.
    val finalColorScheme = override
        ?: dapColorScheme(accentColorOverride ?: palette.accentColor(accent), palette)

    PixelPlayerStatusBarStyle(
        color = finalColorScheme.background,
        navigationColor = finalColorScheme.background
    )

    CompositionLocalProvider(LocalPixelPlayerDarkTheme provides darkTheme) {
        DapTheme(
            darkTheme = darkTheme,
            accent = accent,
            baseTheme = baseTheme,
            palette = palette,
            colorSchemeOverride = override,
            accentColorOverride = accentColorOverride,
        ) {
            MaterialTheme(
                motionScheme = MotionScheme.expressive(),
                content = content
            )
        }
    }
}
