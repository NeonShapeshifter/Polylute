package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.lostf1sh.pixelplayeross.ui.ds.AlbumArtColors
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapPalette
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File

/**
 * B42: the stage the sheets are drawn on for the renders and the measurements: one cover, one theme, the real glass of the app behind
 * (the root's backdrop, at [rootPhase]), the scene the root provides to everything under it ([LocalCoverGlass]) and the palette the app
 * paints with while the glass is on, exactly as `MainActivity` builds them (`GlassBands`, `GlassFieldMath`, `onGlass`).
 */
internal class B42Setup(
    val cover: B41Renders.Cover,
    val theme: DapBaseTheme,
    val accent: DapAccent = DapAccent.Sage,
    /** B42-fix3: which backdrop the cover is made into: the blurred cover ("Cover glass") or the two colours' gradient ("From album art"). */
    val kind: GlassKind = GlassKind.GLASS,
) {
    // A saved Light/Navy/etc. preference is ignored in both cover modes, as at the real app root.
    val drawnTheme = com.lostf1sh.pixelplayeross.ui.theme.resolveDrawnBaseTheme(
        com.lostf1sh.pixelplayeross.data.preferences.AppColorSource.COVER_GLASS,
        com.lostf1sh.pixelplayeross.data.preferences.AppThemeMode.DARK,
        theme,
        systemDark = true,
    )
    val look: AlbumArtColors.Look = AlbumArtColors.look(drawnTheme.palette, cover.dominant, accent)
    val glass: GlassLook = GlassBands.of(look.palette, look.accent)
    val field: GlassField = GlassFieldMath.field(
        when (kind) {
            GlassKind.GLASS -> GlassFieldMath.grid(cover.pixels, cover.width, cover.height)
            GlassKind.GRADIENT -> CoverColours.pick(cover.pixels, cover.width, cover.height).let { GlassFieldMath.gradientGrid(it.lighter, it.darker) }
        },
        glass.band,
        moves = kind.moves,
    )
    val palette: DapPalette = look.palette.onGlass(glass)
    val dark: Boolean get() = !drawnTheme.palette.isLight
}

/** The root's scene for [setup], its field shown (the first cross-fade is run by the caller waiting for idle), its clock at [rootPhase]. */
@Composable
internal fun rememberB42Scene(setup: B42Setup, rootPhase: Float): CoverGlassScene {
    val crossfade = remember(setup) { GlassCrossfade() }
    val clock = remember(setup) { GlassClock().also { it.advance(rootPhase) } }
    LaunchedEffect(setup) { crossfade.show(setup.field) }
    return remember(crossfade, clock) { CoverGlassScene(crossfade, clock, setup.glass.band.base, setup.kind) }
}

/**
 * The app as the root paints it with the glass on: the theme and the palette for [setup], the scene for everything under it, and the real
 * backdrop behind [content]. [expansion] is how far the full player is open (the backdrop's scrim: 0 for the pages, 1 for the player).
 *
 * [glassOn] false is the same app with "Cover glass" turned off for a moment (the cover's own colours without the glass: no scene, the theme's
 * opaque background), which a test flips to measure the same sheet both ways.
 */
@Composable
internal fun B42GlassApp(setup: B42Setup, scene: CoverGlassScene, expansion: Float = 0f, glassOn: Boolean = true, content: @Composable () -> Unit) {
    DapTheme(
        darkTheme = setup.dark, baseTheme = setup.drawnTheme, accent = setup.accent,
        palette = if (glassOn) setup.palette else setup.look.palette,
        accentColorOverride = if (glassOn) setup.glass.ink.accent else setup.look.accent,
    ) {
        CompositionLocalProvider(LocalCoverGlass provides (if (glassOn) scene else null)) {
            // the root of the app: a Surface in the Material background, which is what gives every text with no colour of its own (and every
            // window under it) its content colour
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    if (glassOn) {
                        CoverGlassBackdrop(scene.crossfade, scene.clock, scene.base, expansion = { expansion })
                    } else {
                        Box(Modifier.fillMaxSize().background(com.lostf1sh.pixelplayeross.ui.ds.Dap.background))
                    }
                    content()
                }
            }
        }
    }
}

/** The same app without the glass: "Theme and accent" or "From album art" (the palette of [look]; no scene, no field). */
@Composable
internal fun B42PlainApp(theme: DapBaseTheme, palette: DapPalette, accent: DapAccent = DapAccent.Sage, content: @Composable () -> Unit) {
    DapTheme(darkTheme = !theme.palette.isLight, baseTheme = theme, accent = accent, palette = palette) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** Where B42's pictures go, and how a screen with sheets (each in a window of its own) is captured. */
internal object B42Renders {
    val root: File
        get() = File(System.getenv("B42_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B42-evidence").apply { mkdirs() }

    /** The platform dims what is behind a bottom sheet with its scrim colour at this strength (Material's `BottomSheetDefaults.ScrimColor`). */
    const val SHEET_SCRIM_ALPHA = 0.32f

    /** The dialog window of [node]'s composition, or null when it is the activity's. */
    fun dialogWindow(node: androidx.compose.ui.semantics.SemanticsNode): android.view.Window? {
        val view = (node.root as androidx.compose.ui.platform.ViewRootForTest).view
        var parent: android.view.ViewParent? = view.parent
        while (parent != null) {
            if (parent is androidx.compose.ui.window.DialogWindowProvider) return parent.window
            parent = parent.parent
        }
        return null
    }

    /** What a sheet's own window shows, as pixels (the platform copies the window onto an opaque white ground). */
    fun windowOf(window: android.view.Window): Bitmap {
        val decor = window.decorView
        val bitmap = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val latch = java.util.concurrent.CountDownLatch(1)
        android.view.PixelCopy.request(window, bitmap, { latch.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "the window was not copied" }
        return bitmap
    }

    /**
     * The screen as a person sees it: the activity's window, then each sheet's window over it. A sheet's window is copied with its alpha (the
     * platform's scrim is black at [SHEET_SCRIM_ALPHA] over nothing, the sheet's own surface is opaque), so drawing it over the activity is
     * what the phone does.
     */
    fun screen(compose: ComposeContentTestRule): Bitmap {
        settle(compose)
        val rootsFinder = compose.onAllNodes(isRoot())
        val nodes = rootsFinder.fetchSemanticsNodes()
        val activityIndex = nodes.indexOfFirst { dialogWindow(it) == null }
        check(activityIndex >= 0) { "no activity window" }
        val out = rootsFinder[activityIndex].captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        for ((index, node) in nodes.withIndex()) {
            if (index == activityIndex) continue
            val window = dialogWindow(node) ?: continue
            canvas.drawBitmap(windowOf(window), 0f, 0f, null)
        }
        return out
    }

    /** Where a pixel of a sheet window is only the platform's scrim (translucent), the sheet is not there: its own surface is opaque. */
    fun isScrimOnly(pixel: Int): Boolean = (pixel ushr 24) < 250

    fun save(bitmap: Bitmap, name: String) {
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** One screen, with its sheet windows, to `B42-evidence/<name>.png`. */
    fun save(compose: ComposeContentTestRule, name: String): Bitmap {
        val bitmap = screen(compose)
        save(bitmap, name)
        return bitmap
    }

    /** A sheet's window has drawn its first frame only after its opening has run: let the clock run a second, then the platform copy is of a drawn window. */
    fun settle(compose: ComposeContentTestRule) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** The first sheet window only (what the sheet's own surface and the platform's scrim show), after the clock has run: see [settle]. */
    fun sheetWindow(compose: ComposeContentTestRule): Bitmap {
        settle(compose)
        return sheetWindowNow(compose)
    }

    /** The first sheet window as it is, with the clock where it is (a test that holds the clock mid-way through a transition). */
    fun sheetWindowNow(compose: ComposeContentTestRule): Bitmap {
        val nodes = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        val window = nodes.firstNotNullOf { dialogWindow(it) }
        return windowOf(window)
    }
}

/**
 * B42: the ink of a sheet, measured on the sheet's own pixels. For every text in a sheet window it reads the colour the text is drawn with (from
 * its layout) and the pixels around it (the sheet's glass, or whatever the sheet puts under the text: a card, a button), and takes the contrast
 * against the worst of them. A sample that is nearly the text's own colour is a glyph, not ground, and is left out.
 */
object B42Ink {
    class Finding(val text: String, val color: androidx.compose.ui.graphics.Color, val contrast: Float, val needs: Float, val detail: String = "")

    private fun channel(p: Int, shift: Int) = ((p shr shift) and 0xFF) / 255f

    /**
     * Every text in the sheet windows of the composition, with its worst contrast on [window] (the capture of the sheet's window). With
     * [activityWindow] it is the texts of the activity's own window instead (B42-fix1: the player's pages are not sheets), from [belowPx] down.
     */
    fun measure(
        compose: ComposeContentTestRule,
        window: Bitmap,
        exempt: Set<androidx.compose.ui.graphics.Color> = emptySet(),
        activityWindow: Boolean = false,
        belowPx: Float = 0f,
    ): List<Finding> {
        val matcher = androidx.compose.ui.test.SemanticsMatcher("a text in a sheet window") {
            it.config.contains(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) &&
                if (activityWindow) B42Renders.dialogWindow(it) == null && it.boundsInWindow.top >= belowPx else B42Renders.dialogWindow(it) != null
        }
        val nodes = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
        val findings = mutableListOf<Finding>()
        for (node in nodes) {
            if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)) continue
            // a text field's node is the whole field (its outline and label are inside the box measured): its ink is checked on its fill instead
            if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.EditableText)) continue
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val layout = results.firstOrNull() ?: continue
            val style = layout.layoutInput.style
            val color = style.color
            if (color == androidx.compose.ui.graphics.Color.Unspecified || layout.layoutInput.text.text.isBlank()) continue
            // a text drawn in nothing is a stand-in for width (the lyrics keep a bold copy of every line, invisible, so a line does not jump when it is sung)
            if (color.alpha == 0f) continue
            // the theme's disabled ink is the role for "not applicable": it is not one of the glass's inks and WCAG exempts what is inactive
            if (color in exempt) continue
            val b = node.boundsInWindow
            if (b.width < 4f || b.height < 4f) continue
            // a text cut by the viewport it scrolls in (the last line, going under the bar) is on its way out: its visible part has glyph edges at the corners
            if (activityWindow && node.size.height > b.height + 2f) continue
            val l = b.left.toInt().coerceIn(0, window.width - 1) + 3
            val r = b.right.toInt().coerceIn(0, window.width - 1) - 3
            val t = b.top.toInt().coerceIn(0, window.height - 1) + 3
            val bt = b.bottom.toInt().coerceIn(0, window.height - 1) - 3
            if (r <= l || bt <= t) continue
            val points = listOf(l to t, r to t, l to bt, r to bt)
            val ink = color
            var worst = Float.MAX_VALUE
            val detail = StringBuilder("bounds=$l,$t,$r,$bt ink=${Integer.toHexString(ink.toArgb())}")
            // the sample's place too: a glyph's edge is told from the ground by what is around it
            val samples = points.map { (x, y) -> Triple(x.coerceIn(0, window.width - 1), y.coerceIn(0, window.height - 1), window.getPixel(x.coerceIn(0, window.width - 1), y.coerceIn(0, window.height - 1))) }
                .filter { (it.third ushr 24) >= 250 }
            val grounds = samples.map { it.third }
            if (grounds.isEmpty()) continue
            // the sample in the middle of the lot by luminance stands for the ground; a sample far from it and nearer the text's own colour is a glyph
            val median = grounds.sortedBy { GlassLuma.luminance(it) }[grounds.size / 2]
            fun distance(a: Int, b: Int) = maxOf(Math.abs(channel(a, 16) - channel(b, 16)), Math.abs(channel(a, 8) - channel(b, 8)), Math.abs(channel(a, 0) - channel(b, 0)))
            val inkPixel = ink.toArgb()
            // B42-fix1: the ground is smooth and a glyph's edge is not, so a sample that is far from the pixels 6 px around it (the glass changes slowly,
            // a stem of a letter in a line that wraps does not) is a glyph, whatever its colour is between the ground's and the ink's
            fun isolated(x: Int, y: Int, own: Int): Boolean {
                val around = listOf(x - 6 to y, x + 6 to y, x to y - 6, x to y + 6).map { (nx, ny) -> window.getPixel(nx.coerceIn(0, window.width - 1), ny.coerceIn(0, window.height - 1)) }
                    .sortedBy { GlassLuma.luminance(it) }
                return distance(own, around[around.size / 2]) > 0.08f
            }
            for ((sx, sy, ground) in samples) {
                if (distance(ground, median) > 0.08f && (distance(ground, inkPixel) < distance(ground, median) || isolated(sx, sy, ground))) continue
                val gr = channel(ground, 16)
                val gg = channel(ground, 8)
                val gb = channel(ground, 0)
                val a = ink.alpha
                val er = ink.red * a + gr * (1f - a)
                val eg = ink.green * a + gg * (1f - a)
                val eb = ink.blue * a + gb * (1f - a)
                val contrast = GlassLuma.contrast(GlassLuma.luminance(er, eg, eb), GlassLuma.luminance(gr, gg, gb))
                worst = minOf(worst, contrast)
                detail.append(" [${Integer.toHexString(ground)} %.1f]".format(contrast))
            }
            if (worst == Float.MAX_VALUE) continue
            val large = style.fontSize.isSp && style.fontSize.value >= 18f
            findings += Finding(layout.layoutInput.text.text.take(40), ink, worst, if (large) 3f else GlassBands.AA_TEXT, detail.toString())
        }
        return findings
    }

    /**
     * What the glass made worse: the texts under their floor on the glass **and** more than [slack] worse than the same text in the same sheet
     * without the glass. A text that is under AA in the opaque theme too (a disabled control, a dimmed "blocked" path, a field's outline in
     * the box measured) is the design's own, not the glass's; it is listed by [alsoBelowWithout] and not counted here.
     */
    fun regressions(glass: List<Finding>, without: List<Finding>, slack: Float = 0.25f): List<Finding> {
        val used = mutableSetOf<Int>()
        return glass.filter { g ->
            if (g.contrast >= g.needs) return@filter false
            val i = without.indices.firstOrNull { it !in used && without[it].text == g.text }
            if (i != null) used += i
            val baseline = i?.let { without[it] }
            baseline == null || g.contrast < baseline.contrast - slack
        }
    }

    fun alsoBelowWithout(glass: List<Finding>, without: List<Finding>, slack: Float = 0.25f): List<Finding> {
        val bad = regressions(glass, without, slack).toSet()
        return glass.filter { it.contrast < it.needs && it !in bad }
    }

    fun report(findings: List<Finding>): String =
        findings.filter { it.contrast < it.needs }.joinToString("; ") { "'${it.text}' %.2f < %.1f (${it.detail})".format(it.contrast, it.needs) }
}
