package com.lostf1sh.pixelplayeross.presentation.stats

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.lostf1sh.pixelplayeross.data.historyimport.ImportedStatsInput
import com.lostf1sh.pixelplayeross.data.historyimport.ImportedStatsTrack
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.StatsEngine
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.StatsContent
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsLayer
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TopUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h1000dp-xhdpi")
class B74StatsYearChipsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun buildTenYearState(selectedYear: Int): StatsUiState {
        val now = Kit.at(2026, 10, 15, 20)
        val song = Kit.song(1, title = "Harbor Mist", artist = "Sora Linn", album = "Coast", durationMs = 200_000L)
            .copy(artists = listOf(ArtistRef(1, "Sora Linn", true)))
        val catalog = Kit.catalog(listOf(song))
        val ledger = ListeningLedger.build(
            listOf(Kit.play(1, Kit.at(2026, 3, 10, 12), occurrence = "n1")),
            catalog,
        )
        val years = (2017..2026).toList()
        val imported = ImportedStatsInput(
            tracks = arrayOf(
                ImportedStatsTrack("item-1", "Harbor Mist", "Sora Linn", listOf("Sora Linn"), "Coast", catalog.item("item-1"), null),
            ),
            trackIndices = IntArray(years.size) { 0 },
            timestampsMs = years.map { y -> Kit.at(y, 5, 10, 12) }.toLongArray(),
            listenedMs = LongArray(years.size) { 180_000L },
            estimated = BooleanArray(years.size) { false },
        )
        val report = StatsEngine.report(ledger, StatsPeriod.Preset(com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange.ALL), now, Kit.zone, imported = imported)
        val period = StatsPeriod.Months.year(selectedYear)
        return StatsUiState(
            period = period,
            layer = StatsLayer.TOP,
            isLoading = false,
            report = report,
            links = StatsLinks.of(catalog),
            top = TopUiState(
                from = YearMonth.of(selectedYear, 1),
                to = YearMonth.of(selectedYear, 12),
                report = StatsEngine.report(ledger, period, now, Kit.zone, imported = imported),
            ),
            hasImportedHistory = true,
            includeImportedHistory = true,
        )
    }

    @Test
    fun `10 years render full labels without truncation and scroll selected year into view`() {
        val ui = buildTenYearState(selectedYear = 2017)
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    StatsContent(
                        ui = ui,
                        seeAll = null,
                        zone = Kit.zone,
                        onSeeAll = {},
                        onRange = {},
                        onMode = {},
                        onLayer = {},
                        onRetry = {},
                        topActions = TopActions({}, {}, { _, _ -> }, {}, {}, {}),
                        onIncludeImportedChanged = {},
                        onOpen = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithText("2…").assertCountEquals(0)
        for (year in 2017..2026) {
            compose.onNodeWithText(year.toString()).assertExists()
        }
        // Selected off-screen year (2017) is scrolled into view automatically.
        compose.onNodeWithText("2017").assertIsDisplayed()

        val outDir = File(System.getenv("B74_RENDERS") ?: "build/test-artifacts/B74/renders").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "stats-top-10-years-en-dark.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // All 10 chips are reachable via horizontal scroll and show their full 4-digit year.
        for (year in 2017..2026) {
            compose.onNodeWithText(year.toString()).performScrollTo().assertIsDisplayed()
        }
    }
}
