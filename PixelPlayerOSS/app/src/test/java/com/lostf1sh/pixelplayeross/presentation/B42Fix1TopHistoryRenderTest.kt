package com.lostf1sh.pixelplayeross.presentation

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.StatsEngine
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.StatsContent
import com.lostf1sh.pixelplayeross.presentation.stats.TopActions
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsLayer
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TopUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42-fix1, item 4: "Top won't go back to earlier months, and I understand why: those months didn't exist." The Top face says it in one line
 * under its month pickers, where the history starts, in each of the three languages, so a missing earlier month does not look like a bug.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class B42Fix1TopHistoryRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("UTC")
    private val now = at(2026, 10, 26)
    private val catalog = Kit.catalog((1..3).map { Kit.song(it.toLong()) })
    // the first listen is on 5 October 2026: that is where the history starts
    private val ledger = ListeningLedger.build(
        listOf(Kit.play(1, at(2026, 10, 5, 12)), Kit.play(2, at(2026, 10, 20, 12)), Kit.play(1, at(2026, 10, 21, 12))),
        catalog,
    )
    private val period = StatsPeriod.Months(YearMonth.of(2026, 1), YearMonth.of(2026, 12))
    private val noTopActions = TopActions(onSize = {}, onYear = {}, onMonths = { _, _ -> }, onPlay = {}, onShuffle = {}, onSave = {})

    private fun topFace(withReport: Boolean) = compose.setContent {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                Box(Modifier.fillMaxSize().background(Dap.background)) {
                    StatsContent(
                        ui = StatsUiState(
                            layer = StatsLayer.TOP, isLoading = false, month = YearMonth.of(2026, 10),
                            top = TopUiState(
                                size = 50, from = period.from, to = period.to,
                                report = if (withReport) StatsEngine.report(ledger, period, now, zone) else null,
                            ),
                        ),
                        seeAll = null, zone = zone, onSeeAll = {}, onRange = {}, onMode = {}, onLayer = {}, onRetry = {},
                        topActions = noTopActions,
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "en-w360dp-h1600dp-xhdpi")
    fun the_top_face_says_where_the_history_starts_in_english() {
        topFace(withReport = true)
        compose.onNodeWithText("Your history starts in October 2026").assertIsDisplayed()
        B42Fix1Renders.save(compose, "item4-top-history-en")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h1600dp-xhdpi")
    fun the_top_face_says_where_the_history_starts_in_spanish() {
        topFace(withReport = true)
        compose.onNodeWithText("Tu historial empieza en octubre 2026").assertIsDisplayed()
        B42Fix1Renders.save(compose, "item4-top-history-es")
    }

    @Test
    @Config(qualifiers = "sv-w360dp-h1600dp-xhdpi")
    fun the_top_face_says_where_the_history_starts_in_swedish() {
        topFace(withReport = true)
        compose.onNodeWithText("Din historik börjar i oktober 2026").assertIsDisplayed()
        B42Fix1Renders.save(compose, "item4-top-history-sv")
    }

    @Test
    @Config(qualifiers = "en-w360dp-h1600dp-xhdpi")
    fun nothing_is_said_while_the_history_is_not_known() {
        topFace(withReport = false)
        compose.onAllNodesWithText("Your history starts in", substring = true).assertCountEquals(0)
    }
}
