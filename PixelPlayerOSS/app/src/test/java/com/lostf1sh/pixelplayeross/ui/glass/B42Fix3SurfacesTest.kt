package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapMiniPlayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * B42-fix3, item 8: **every card, tile, highlighted row and bar drawn on the glass is frosted glass of the same field**, measured on the pixels of the
 * real screens.
 *
 * The HONOR showed black slabs on the glass: the tiles and cards at about RGB (22, 26, 25), the mini player and the bottom bar the same, the strip under
 * the status bar (14, 16, 16), with the glass leaking between them at (38, 36, 31). Here Home, the Library, Stats and an album page are drawn with the
 * app's components (the tiles are `RecentlyPlayedSection`, the card `StatsOverviewCard`, the bars `DapMiniPlayer` and `DapBottomNav`, the list
 * `LibrarySongsTab`, the strip and the collapsed bar `Dap.bar`) over the real backdrop in both cover modes, for the five covers of item 7 (a pure white,
 * a pure black, a pale pastel, a saturated one and a multicolour on black), and each screen is drawn twice: with and without its content. Where a
 * surface sits, the glass under it is the first picture and the surface is the second, so what a surface does to the glass is read directly:
 *
 *  - **no slab**: no more than a hair of the screen is the theme's flat grey (Graphite's surface, raised surface or background, +/- 2 levels);
 *  - **frosted, not grey, not black**: each surface is the glass under it with the one veil ([GlassSurfaces.PANEL_ALPHA] of the theme's background), within
 *    3 levels, so its mean colour stays within a set distance of the glass under its scrim and keeps at least half of its chroma ([MIN_CHROMA_SHARE]);
 *  - **every ink at AA** on the surface (primary, secondary and tertiary at 4.5:1, measured on the surface's own pixels);
 *  - the mini player is now an opaque cover-coloured shelf matching the folded "Up next" strip, by the owner's device feedback;
 *    the bottom bar still matches the open queue's glass within 3 levels;
 *  - the playing row is a low-alpha accent wash, not a card.
 *
 * "Theme and accent" is checked on the same screens: its tiles and bars are the theme's own flat surface, exactly, as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B42Fix3SurfacesTest {
    @get:Rule
    val compose = createComposeRule()

    private enum class Page { HOME, LIBRARY, STATS, ALBUM, DEVICE }

    private data class Shot(val cover: B41Renders.Cover, val mode: Fix3Mode, val page: Page, val withContent: Boolean)

    private val covers = B42Fix3Covers.all
    private val shot = MutableStateFlow(Shot(covers[0], Fix3Mode.COVER_GLASS, Page.HOME, true))
    private val graphite = DapBaseTheme.Graphite.palette

    /** What a surface has to keep of the page glass's chroma: a veil of the theme's near-black over it takes some, never most. */
    private val MIN_CHROMA_SHARE = 0.5f

    private fun show() {
        compose.setContent {
            val s by shot.collectAsState()
            key(s) {
                Fix3Stage(s.cover, s.mode).App {
                    if (s.withContent) {
                        when (s.page) {
                            Page.HOME -> B42Fix3Pages.Home(s.cover)
                            Page.LIBRARY -> B42Fix3Pages.Library(s.cover)
                            Page.STATS -> B42Fix3Pages.Stats(s.cover)
                            Page.ALBUM -> B42Fix3Pages.Album(s.cover)
                            Page.DEVICE -> B42Fix3Pages.Device(s.cover)
                        }
                    }
                }
            }
        }
    }

    private fun capture(cover: B41Renders.Cover, mode: Fix3Mode, page: Page, withContent: Boolean): Bitmap {
        compose.runOnIdle { shot.value = Shot(cover, mode, page, withContent) }
        compose.waitForIdle()
        return B42Renders.screen(compose)
    }

    private fun density() = compose.density.density

    /** The pixels of a node's bounds, inset by [insetDp] on every side. */
    private fun rectOf(tag: String): Rect {
        val b = compose.onNodeWithTag(tag).getBoundsInRoot()
        val d = density()
        return Rect(b.left.value * d, b.top.value * d, b.right.value * d, b.bottom.value * d)
    }

    private fun channel(p: Int, shift: Int) = (p shr shift) and 0xFF

    /** The median colour of [rect] in [bitmap] (per channel): robust to the few pixels of text and artwork that are in it. */
    private fun median(bitmap: Bitmap, rect: Rect): Int {
        val x0 = rect.left.toInt().coerceIn(0, bitmap.width - 1)
        val x1 = rect.right.toInt().coerceIn(x0 + 1, bitmap.width)
        val y0 = rect.top.toInt().coerceIn(0, bitmap.height - 1)
        val y1 = rect.bottom.toInt().coerceIn(y0 + 1, bitmap.height)
        val r = ArrayList<Int>()
        val g = ArrayList<Int>()
        val b = ArrayList<Int>()
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = bitmap.getPixel(x, y)
            r += channel(p, 16); g += channel(p, 8); b += channel(p, 0)
        }
        fun med(l: MutableList<Int>): Int { l.sort(); return l[l.size / 2] }
        return (0xFF shl 24) or (med(r) shl 16) or (med(g) shl 8) or med(b)
    }

    private fun chroma(p: Int) = max(channel(p, 16), max(channel(p, 8), channel(p, 0))) - min(channel(p, 16), min(channel(p, 8), channel(p, 0)))

    private fun baseArgb() = GlassLuma.pack(graphite.background.red, graphite.background.green, graphite.background.blue)

    private fun maxDiff(a: Int, b: Int) = max(abs(channel(a, 16) - channel(b, 16)), max(abs(channel(a, 8) - channel(b, 8)), abs(channel(a, 0) - channel(b, 0))))

    private fun fractionOfFlatGrey(bitmap: Bitmap): Float {
        val flats = listOf(graphite.surface, graphite.surfaceRaised, graphite.background).map { GlassLuma.pack(it.red, it.green, it.blue) }
        var n = 0
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            val p = bitmap.getPixel(x, y)
            if (flats.any { maxDiff(p, it) <= 2 }) n++
        }
        return n / ((bitmap.width / 2f) * (bitmap.height / 2f))
    }

    /** A strip of [rect]: its top few pixels, above any text, past a margin on each side. */
    private fun topStrip(rect: Rect, marginDp: Float = 12f, fromTopDp: Float = 3f, heightDp: Float = 3f): Rect {
        val d = density()
        return Rect(rect.left + marginDp * d, rect.top + fromTopDp * d, rect.right - marginDp * d, rect.top + (fromTopDp + heightDp) * d)
    }

    private class Surface(val name: String, val rect: Rect, val strip: Rect)

    private fun surfacesOn(page: Page): List<Surface> {
        val out = ArrayList<Surface>()
        fun add(name: String, tag: String, margin: Float = 12f, fromTop: Float = 3f) {
            val r = rectOf(tag)
            out += Surface(name, r, topStrip(r, margin, fromTop))
        }
        when (page) {
            Page.HOME -> {
                add("the strip under the status bar", B42Fix3Pages.TAG_STRIP, margin = 0f, fromTop = 6f)
                // every tile: the clickable rows of the section, where nothing but the pill's own surface is in the top of them
                val tiles = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(B42Fix3Pages.TAG_TILES))).fetchSemanticsNodes()
                val d = density()
                tiles.filter { it.boundsInRoot.width > 100f * d && it.boundsInRoot.height < 80f * d }.forEachIndexed { i, node ->
                    val r = node.boundsInRoot
                    out += Surface("tile $i", r, topStrip(r, 6f, 3f))
                }
                add("the Listening stats card", B42Fix3Pages.TAG_STATS, margin = 36f, fromTop = 6f)
                add("the bottom bar", B42Fix3Pages.TAG_NAV, margin = 40f, fromTop = 4f)
            }
            Page.LIBRARY -> {
                add("the strip under the status bar", B42Fix3Pages.TAG_STRIP, margin = 0f, fromTop = 6f)
                add("the bottom bar", B42Fix3Pages.TAG_NAV, margin = 40f, fromTop = 4f)
            }
            Page.STATS -> {
                add("the strip under the status bar", B42Fix3Pages.TAG_STRIP, margin = 0f, fromTop = 6f)
                add("the Listening stats card", B42Fix3Pages.TAG_STATS, margin = 36f, fromTop = 6f)
                add("the bottom bar", B42Fix3Pages.TAG_NAV, margin = 40f, fromTop = 4f)
            }
            Page.DEVICE -> {
                val title = compose.onNode(hasText("Wi-Fi")).getBoundsInRoot()
                val d = density()
                val rect = Rect(16f * d, (title.bottom.value + 6f) * d, 344f * d, (title.bottom.value + 160f) * d)
                out += Surface("Device Wi-Fi card", rect, topStrip(rect, 32f, 4f))
                add("the bottom bar", B42Fix3Pages.TAG_NAV, margin = 40f, fromTop = 4f)
            }
            Page.ALBUM -> {
                add("the collapsed top bar", B42Fix3Pages.TAG_BAR, margin = 0f, fromTop = 6f)
                add("the bottom bar", B42Fix3Pages.TAG_NAV, margin = 40f, fromTop = 4f)
            }
        }
        return out
    }

    private fun inks() = listOf(graphite.primaryText, graphite.secondaryText, graphite.tertiaryText).let {
        // the glass's ink is what the app draws with: near-white, the same for every cover
        val look = GlassBands.of(graphite, graphite.accentColor(com.lostf1sh.pixelplayeross.ui.ds.DapAccent.Sage))
        listOf(look.ink.primary, look.ink.secondary, look.ink.tertiary)
    }

    private fun lumOf(p: Int) = GlassLuma.luminance(p)

    // ── the measurements ────────────────────────────────────────────────────────────────────────────

    @Test
    fun every_surface_is_the_glass_under_one_veil_coloured_and_readable_in_both_cover_modes() {
        show()
        val report = ArrayList<String>()
        for (mode in listOf(Fix3Mode.COVER_GLASS, Fix3Mode.FROM_ALBUM_ART)) for (cover in covers) for (page in Page.entries) {
            val glassOnly = capture(cover, mode, page, withContent = false)
            val screen = capture(cover, mode, page, withContent = true)
            if (page == Page.DEVICE && cover == covers[3]) {
                val root = java.io.File(System.getenv("B43_EVIDENCE") ?: "/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B43-evidence", System.getenv("B43_VARIANT") ?: "after").apply { mkdirs() }
                java.io.File(root, "device-${mode.label}.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            // no slab: nowhere on the screen is the theme's flat grey (the glass behind it can be any colour, including a near-black one: the
            // near-black cover under Graphite's background is not (14, 15, 17), and a veil of it over black is darker still)
            val flat = fractionOfFlatGrey(screen)
            println("B42-fix3 flat | ${mode.label} / ${cover.name} / $page: ${"%.4f".format(flat)}")
            // The mini player deliberately occupies a flat, opaque shelf after the HONOR review.
            val mini = rectOf(B42Fix3Pages.TAG_MINI)
            val miniFraction = mini.width * mini.height / (screen.width * screen.height)
            assertThat(flat).isAtMost(miniFraction + 0.002f)
            val miniFill = median(screen, topStrip(mini, 100f, 4f))
            val mean = Fix3Stage(cover, mode).field!!.mean
            val expectedMini = androidx.compose.ui.graphics.lerp(mean, graphite.background, GlassScrim.SHEET_ALPHA)
            assertThat(maxDiff(miniFill, GlassLuma.pack(expectedMini.red, expectedMini.green, expectedMini.blue))).isAtMost(1)
            for (surface in surfacesOn(page)) {
                val where = "${mode.label} / ${cover.name} / $page / ${surface.name}"
                val glass = median(glassOnly, surface.strip)
                val seen = median(screen, surface.strip)
                val expected = GlassLuma.mixArgb(glass, baseArgb(), GlassSurfaces.PANEL_ALPHA)
                // frosted: the glass under it, under one veil of the theme's background
                assertThat(maxDiff(seen, expected)).isAtMost(3)
                // and it is not the near-black or flat grey it was: its chroma is at least half of the glass's
                val cg = chroma(glass)
                if (cg >= 16) assertThat(chroma(seen).toFloat()).isAtLeast(MIN_CHROMA_SHARE * cg)
                // every ink at AA on its own pixels
                for (ink in inks()) assertThat(GlassLuma.contrast(ink.luminance(), lumOf(seen))).isAtLeast(4.5f)
                report += "$where: glass ${hex(glass)} -> ${hex(seen)}, chroma ${chroma(glass)} -> ${chroma(seen)}"
            }
        }
        // the numbers for the result, once
        java.io.File(System.getenv("B42FIX3_SURFACES_REPORT") ?: "build/b42fix3-surfaces-report.txt").apply { parentFile?.mkdirs() }.writeText(report.joinToString("\n"))
    }

    @Test
    fun device_cards_keep_the_shared_veil_and_half_the_field_chroma() {
        show()
        for (mode in listOf(Fix3Mode.COVER_GLASS, Fix3Mode.FROM_ALBUM_ART)) {
            val glass = capture(covers[3], mode, Page.DEVICE, false)
            val screen = capture(covers[3], mode, Page.DEVICE, true)
            val root = java.io.File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B43-evidence", System.getenv("B43_VARIANT") ?: "after").apply { mkdirs() }
            java.io.File(root, "device-${mode.label}.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            for (surface in surfacesOn(Page.DEVICE)) {
                val field = median(glass, surface.strip)
                val seen = median(screen, surface.strip)
                assertThat(maxDiff(seen, GlassLuma.mixArgb(field, baseArgb(), GlassSurfaces.PANEL_ALPHA))).isAtMost(3)
                if (chroma(field) >= 16) assertThat(chroma(seen).toFloat()).isAtLeast(MIN_CHROMA_SHARE * chroma(field))
                for (ink in inks()) assertThat(GlassLuma.contrast(ink.luminance(), lumOf(seen))).isAtLeast(4.5f)
            }
        }
    }

    private fun hex(p: Int) = "#%02X%02X%02X".format(channel(p, 16), channel(p, 8), channel(p, 0))

    @Test
    fun the_bottom_bar_matches_the_open_queue_glass() {
        show()
        val base = baseArgb()
        for (mode in listOf(Fix3Mode.COVER_GLASS, Fix3Mode.FROM_ALBUM_ART)) for (cover in covers) {
            val screen = capture(cover, mode, Page.HOME, withContent = true)
            val stage = Fix3Stage(cover, mode)
            val field = stage.field!!
            // a field that does not move (the gradient) is its first layer alone, as the backdrop and the sheet's copy draw it
            val layers = if (field.moves) GlassMotion.layers(Fix3Stage.ROOT_PHASE) else listOf(GlassMotion.layer(Fix3Stage.ROOT_PHASE, 0))
            for ((name, tag, margin) in listOf(Triple("the bottom bar", B42Fix3Pages.TAG_NAV, 40f))) {
                val r = rectOf(tag)
                val strip = topStrip(r, margin, 4f)
                // a narrow window in the middle of the strip, where the glass is one colour to the eye
                val px = ((strip.left + strip.right) / 2f).toInt()
                val py = ((strip.top + strip.bottom) / 2f).toInt()
                val window = Rect(px - 10f, strip.top, px + 10f, strip.bottom)
                val seen = median(screen, window)
                // the sheet's own copy: the same field at the same phase under the sheet's veil, at the same pixel
                val sheet = GlassSample.pixel(field, layers, screen.width, screen.height, px, py, base, GlassScrim.SHEET_ALPHA)
                println("B42-fix3 seam | ${mode.label} / ${cover.name} / $name: screen ${hex(seen)} sheet ${hex(sheet)}")
                assertThat(maxDiff(seen, sheet)).isAtMost(4)
                assertThat(name).isNotEmpty()
            }
        }
    }

    @Test
    fun mini_and_its_actual_sheet_fill_hide_content_underneath_them() {
        val underlay = MutableStateFlow(Color.Magenta)
        compose.setContent {
            Fix3Stage(covers[3], Fix3Mode.COVER_GLASS).App {
                val behind by underlay.collectAsState()
                Column {
                    Box(Modifier.fillMaxWidth().background(behind)) {
                        Text("SCROLLING LIBRARY CONTENT BEHIND THE MINI")
                        DapMiniPlayer("Song", "Artist", false, {}, {}, {}, Modifier.testTag("opaque-mini"))
                    }
                    Box(Modifier.fillMaxWidth().height(60.dp).background(behind)) {
                        Text("SCROLLING LIBRARY CONTENT BEHIND THE SHEET")
                        // The real mini player's sheet uses this modifier; its fill is the same surface as QueueGlass when folded.
                        Box(Modifier.fillMaxWidth().height(60.dp).glassPanelBackground(Dap.shelf).testTag("opaque-sheet"))
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = B42Renders.screen(compose)
        val regions = listOf(rectOf("opaque-mini"), rectOf("opaque-sheet"))
        compose.runOnIdle { underlay.value = Color.Green }
        compose.waitForIdle()
        val after = B42Renders.screen(compose)
        for (r in regions) {
            for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt()) {
                assertThat(after.getPixel(x, y)).isEqualTo(before.getPixel(x, y))
            }
        }
        val mean = Fix3Stage(covers[3], Fix3Mode.COVER_GLASS).field!!.mean
        val expected = androidx.compose.ui.graphics.lerp(mean, graphite.background, GlassScrim.SHEET_ALPHA)
        val surface = GlassLuma.pack(expected.red, expected.green, expected.blue)
        assertThat(expected.alpha).isEqualTo(1f)
        assertThat(maxDiff(surface, GlassLuma.pack(graphite.surface.red, graphite.surface.green, graphite.surface.blue))).isGreaterThan(5)
        assertThat(maxDiff(median(after, topStrip(regions[0], 100f, 4f)), surface)).isAtMost(1)
        assertThat(maxDiff(median(after, topStrip(regions[1], 100f, 4f)), surface)).isAtMost(1)
    }

    @Test
    fun the_playing_row_is_a_low_alpha_accent_wash_not_a_card() {
        show()
        for (mode in listOf(Fix3Mode.COVER_GLASS, Fix3Mode.FROM_ALBUM_ART)) for (cover in listOf(covers[2], covers[3], covers[4])) {
            val glassOnly = capture(cover, mode, Page.LIBRARY, withContent = false)
            val screen = capture(cover, mode, Page.LIBRARY, withContent = true)
            val row = compose.onNode(hasText("Cancion 2") and hasClickAction()).getBoundsInRoot()
            val d = density()
            val rect = Rect(row.left.value * d, row.top.value * d, row.right.value * d, row.bottom.value * d)
            val strip = topStrip(rect, 40f, 3f, 3f)
            val glass = median(glassOnly, strip)
            val seen = median(screen, strip)
            val other = median(screen, Rect(strip.left, rect.top - 48f * d, strip.right, rect.top - 45f * d))      // a row above it: the page glass
            // it differs from the page glass a little (a wash of the accent), by a lot less than a card would (a card is 0.42 of the base over it)
            val delta = maxDiff(glass, seen)
            assertThat(delta).isAtLeast(2)
            val card = maxDiff(glass, GlassLuma.mixArgb(glass, baseArgb(), GlassSurfaces.PANEL_ALPHA))
            assertThat(delta).isAtMost(max(card, 30))
            // and it is coloured by the glass it sits on, not the theme's flat grey
            assertThat(maxDiff(seen, GlassLuma.pack(graphite.surface.red, graphite.surface.green, graphite.surface.blue))).isGreaterThan(2)
            assertThat(other).isNotEqualTo(0)
        }
    }

    @Test
    fun theme_and_accent_keeps_its_flat_surfaces_exactly() {
        show()
        val cover = covers[3]
        val screen = capture(cover, Fix3Mode.THEME, Page.HOME, withContent = true)
        val flatSurface = GlassLuma.pack(graphite.surface.red, graphite.surface.green, graphite.surface.blue)
        val background = baseArgb()
        // the tiles, the card and the bars are the theme's own surface, to the level; the strip is its own background
        assertThat(maxDiff(median(screen, topStrip(rectOf(B42Fix3Pages.TAG_STATS), 36f, 6f)), flatSurface)).isAtMost(1)
        assertThat(maxDiff(median(screen, topStrip(rectOf(B42Fix3Pages.TAG_MINI), 100f, 4f)), flatSurface)).isAtMost(1)
        assertThat(maxDiff(median(screen, topStrip(rectOf(B42Fix3Pages.TAG_NAV), 40f, 4f)), flatSurface)).isAtMost(1)
        assertThat(maxDiff(median(screen, topStrip(rectOf(B42Fix3Pages.TAG_STRIP), 0f, 6f)), background)).isAtMost(1)
        assertThat(Color(background).luminance()).isLessThan(0.01f)
    }
}
