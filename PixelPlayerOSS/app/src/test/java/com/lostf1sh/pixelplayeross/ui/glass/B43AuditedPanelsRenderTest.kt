package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.Bitmap
import DelimiterConfigScreen
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.AudioBookmarkEntity
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ArtistSettingsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ArtistSettingsViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Real panels from every screen in the B43 audit, rather than duplicate Surface implementations.
 * Reflection reaches file-private composables in the untouched before tree as well. It adds no
 * production test API and invokes the same compiled panel, with its Composer and explicit defaults.
 */
@OptIn(InternalComposeApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class B43AuditedPanelsRenderTest {
    @get:Rule val compose = createComposeRule()
    private enum class Page { BOOKMARKS, CREATE_PLAYLIST, DELIMITERS, WORD_DELIMITERS, CAPABILITIES, TRANSITIONS,
        HEADPHONES, QUICK_FILL, RECENT, DEVICE, MAINTENANCE, PLAYLIST, MAINTENANCE_TOOLS }
    private data class Shot(val page: Page, val mode: Fix3Mode, val content: Boolean)
    private val shot = MutableStateFlow(Shot(Page.BOOKMARKS, Fix3Mode.COVER_GLASS, true))
    private val settings = mockk<ArtistSettingsViewModel>(relaxed = true).also {
        every { it.uiState } returns MutableStateFlow(ArtistSettingsUiState())
    }
    private val cover = B42Fix3Covers.all[3]
    private val graphite = DapBaseTheme.Graphite.palette
    private val noClick: () -> Unit = {}

    @Composable
    private fun Panel(file: String, name: String, vararg arguments: Any?) {
        val method = remember(file, name) {
            Class.forName("com.lostf1sh.pixelplayeross.$file").declaredMethods.single { it.name == name || it.name.startsWith(name + "-") }
                .apply { isAccessible = true }
        }
        val parameters = arguments.toMutableList()
        parameters += currentComposer
        repeat(method.parameterCount - parameters.size) { parameters += 0 }
        method.invoke(null, *parameters.toTypedArray())
    }
    @Composable
    private fun PageContent(page: Page) {
        val nav = rememberNavController()
        val text: @Composable () -> Unit = { Box(Modifier.fillMaxWidth().height(80.dp)) { Text("Panel content") } }
        val columnText: @Composable ColumnScope.() -> Unit = { Box(Modifier.fillMaxWidth().height(80.dp)) { Text("Capabilities") } }
        when (page) {
            Page.BOOKMARKS -> Panel("presentation.screens.AudioBookmarksScreenKt", "BookmarkMomentCard",
                AudioBookmarkEntity(songId = "1", songTitle = "Song", artistName = "Artist", albumArtUri = null,
                    title = "Bookmark", timestampMs = 1000), noClick, noClick, Modifier)
            Page.CREATE_PLAYLIST -> ShapeParameterCard("Shape", 0.5f, 0f..1f, {}, { "50%" })
            Page.DELIMITERS -> DelimiterConfigScreen(nav, settings)
            Page.WORD_DELIMITERS -> WordDelimiterConfigScreen(nav, settings)
            Page.CAPABILITIES -> Panel("presentation.screens.DeviceCapabilitiesScreenKt", "CapabilityCard",
                Modifier, "Capability", Icons.Outlined.Info, false, 10f, columnText)
            Page.TRANSITIONS -> Panel("presentation.screens.EditTransitionScreenKt", "TransitionSummaryCard",
                true, true, false, noClick, noClick, true)
            Page.HEADPHONES -> Panel("presentation.screens.HeadphoneProfileScreensKt", "HeadphoneProfileSection", "Headphones", text)
            Page.QUICK_FILL -> QuickFillContent(persistentListOf(Song.emptySong().copy(id = "1", title = "Song")),
                emptySet(), emptyMap(), {}, { _, _ -> }, { _, _ -> })
            Page.RECENT -> Panel("presentation.screens.RecentlyPlayedScreenKt", "RecentlyPlayedEmptyState", StatsTimeRange.ALL, Modifier)
            Page.DEVICE -> B42Fix3Pages.Device(cover)
            Page.MAINTENANCE -> MaintenanceSection("Maintenance", text)
            Page.PLAYLIST -> Panel("presentation.screens.PlaylistDetailScreenKt", "PlaylistActionItem", ColorPainter(Color.White), "Playlist", noClick)
            Page.MAINTENANCE_TOOLS -> Panel("presentation.settings.MaintenanceToolsKt", "PaletteRegenerateSongSheetContent",
                persistentListOf<Song>(), false, "", { _: String -> }, noClick, { _: Song -> })
        }
    }
    private fun capture(page: Page, mode: Fix3Mode, content: Boolean): Bitmap {
        compose.runOnIdle { shot.value = Shot(page, mode, content) }
        settleFix3(compose)
        return B42Renders.screen(compose)
    }
    private fun distance(a: Int, b: Int) = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)),
        abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))
    private fun chroma(a: Int) = maxOf(a shr 16 and 255, a shr 8 and 255, a and 255) - minOf(a shr 16 and 255, a shr 8 and 255, a and 255)
    @Test fun `every audited screen panel keeps the common veil chroma and AA in both cover modes`() {
        val authority = DapBackAuthority(OnBackPressedDispatcher())
        compose.setContent {
            val current by shot.collectAsState()
            key(current) {
                Fix3Stage(cover, current.mode).App {
                    CompositionLocalProvider(LocalDapBackNavigation provides authority) {
                        if (current.content) Box(Modifier.fillMaxSize().padding(16.dp)) { PageContent(current.page) }
                    }
                }
            }
        }
        val errors = mutableListOf<String>()
        val report = mutableListOf<String>()
        val base = GlassLuma.pack(graphite.background.red, graphite.background.green, graphite.background.blue)
        val ink = GlassBands.of(graphite, Color.Cyan).ink
        for (mode in listOf(Fix3Mode.COVER_GLASS, Fix3Mode.FROM_ALBUM_ART)) for (page in Page.entries) {
            val field = capture(page, mode, false)
            val screen = capture(page, mode, true)
            val dir = File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B43-evidence", System.getenv("B43_VARIANT") ?: "after").apply { mkdirs() }
            File(dir, "panel-${page.name.lowercase()}-${mode.label}.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            var matching = 0
            // An actual panel must occupy at least 1,000 dp² of the common veil, not merely a
            // coincidental antialiased pixel. Check every second pixel, excluding text/controls.
            for (y in 0 until screen.height step 2) for (x in 0 until screen.width step 2) {
                val under = field.getPixel(x, y)
                val expected = GlassLuma.mixArgb(under, base, GlassSurfaces.PANEL_ALPHA)
                val seen = screen.getPixel(x, y)
                if (distance(expected, under) > 4 && distance(seen, expected) <= 3) {
                    if (chroma(under) >= 16 && chroma(seen) < chroma(under) * 0.5f) continue
                    if (listOf(ink.primary, ink.secondary, ink.tertiary).any { GlassLuma.contrast(it.luminance(), GlassLuma.luminance(seen)) < 4.5f }) continue
                    matching++
                }
            }
            val areaDp = matching * 4f / (compose.density.density * compose.density.density)
            report += "$page / ${mode.label}: $areaDp dp² of real panel with shared veil, chroma >=50%, all inks AA"
            if (areaDp < 1000f) errors += "$page / ${mode.label}: $areaDp dp²"
        }
        File("build/b43-audited-panels.txt").writeText(report.joinToString("\n"))
        println(report.joinToString("\n"))
        assertThat(errors).isEmpty()
    }
}
