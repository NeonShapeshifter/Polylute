package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapPalette
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/** The two cover modes, and "Theme and accent" for the screens that must render exactly as they did. */
internal enum class Fix3Mode(val label: String) { FROM_ALBUM_ART("art"), COVER_GLASS("glass"), THEME("theme") }

/**
 * B42-fix3: the stage the renders and the surface measurements share, in the shape that is the same in the tree as it was before this batch and in
 * the tree after it (the "before" copy has its own version of this file, written against what it had), so the very same tests run on both.
 *
 * AFTER: both cover modes are a backdrop (the glass, or the two colours' gradient) with the ink always light on Graphite; "Theme and accent" is the theme's own
 * palette with nothing behind.
 */
internal class Fix3Stage(val cover: B41Renders.Cover, val mode: Fix3Mode) {
    private val kind = if (mode == Fix3Mode.FROM_ALBUM_ART) GlassKind.GRADIENT else GlassKind.GLASS
    val setup = B42Setup(cover, DapBaseTheme.Graphite, kind = kind)

    /** Whether this mode draws a backdrop behind the app. */
    val hasBackdrop: Boolean get() = mode != Fix3Mode.THEME

    /** The field on screen (null for "Theme and accent"). */
    val field: GlassField? get() = if (hasBackdrop) setup.field else null

    /** The palette the app is painted with in this mode. */
    val palette: DapPalette get() = if (hasBackdrop) setup.palette else DapBaseTheme.Graphite.palette

    @Composable
    fun App(rootPhase: Float = ROOT_PHASE, expansion: Float = 0f, content: @Composable () -> Unit) {
        if (hasBackdrop) {
            val scene = rememberB42Scene(setup, rootPhase)
            B42GlassApp(setup, scene, expansion = expansion, content = content)
        } else {
            // "Theme and accent": the theme's own palette, its opaque page, and the root surface that gives every text its content colour
            DapTheme(darkTheme = true, baseTheme = DapBaseTheme.Graphite) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    companion object {
        /** Where the root's glass is when the pages are drawn (the clock stops where the player left it). */
        const val ROOT_PHASE = 14f
    }
}

internal fun settleFix3(compose: ComposeContentTestRule) {
    compose.waitForIdle()
    compose.mainClock.advanceTimeBy(1_500)
    compose.waitForIdle()
}
