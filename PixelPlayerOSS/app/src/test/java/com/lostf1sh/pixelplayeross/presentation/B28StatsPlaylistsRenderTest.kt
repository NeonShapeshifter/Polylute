package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.google.common.truth.Truth.assertThat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollection
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionEngine
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionId
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionRepository
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionsState
import com.lostf1sh.pixelplayeross.data.collections.auto.EmptyReason
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.StatsEngine
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.data.stats.SyntheticHistory
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistsList
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.CollectionPage
import com.lostf1sh.pixelplayeross.presentation.screens.StatsContent
import com.lostf1sh.pixelplayeross.presentation.stats.TopActions
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsLayer
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TopUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import java.io.File
import java.time.YearMonth
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B28: the new Stats and Playlists screens rendered on the JVM over made-up data, at the DAP's 360 dp width (720 x 1610 px at 2x), in
 * Spanish and Swedish (the long languages). Each render must keep every node inside the window and cut no text that is not a name that
 * is allowed to end in an ellipsis; the PNGs go to `/tmp/b28-renders` for the design kit.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
class B28StatsPlaylistsRenderTest {
    @get:Rule val compose = createComposeRule()

    private val now = Kit.at(2026, 10, 15, 20)
    private val zone = Kit.zone

    // --- the made-up library and history -----------------------------------------------------------------------------

    private val songs = SyntheticHistory.songs(800).mapIndexed { index, song ->
        when (index) {
            0 -> song.copy(title = "!!!!!!!", artist = "Artista Uno")
            1 -> song.copy(title = "\$AD BOYZ II", artist = "\$AD Crew")
            2 -> song.copy(title = "Una canción con un título larguísimo que no cabe en una sola fila de ninguna pantalla de un reproductor")
            else -> song
        }
    }
    private val catalog = Kit.catalog(
        songs.map { it },
        firstSeen = songs.associate { it.libraryItemId to if (it.id.toInt() % 40 == 0) now - 6 * Kit.DAY else now - 300 * Kit.DAY },
    )
    private val events = run {
        val raw = SyntheticHistory.events(3_000, 800, now, days = 70)
        val legacy = raw.take(1_300).map {
            it.copy(libraryItemId = null, occurrenceId = null, outcome = null, endReason = null, audio = null, context = null, itemDurationMs = null)
        }
        legacy + raw.drop(1_300) +
            listOf(Kit.event(99999, now - 5 * Kit.DAY, 40 * Kit.MIN), Kit.event(99998, now - 9 * Kit.DAY, 25 * Kit.MIN))
    }
    private val ledger = ListeningLedger.build(events.sortedBy { it.timestamp }, catalog)

    private fun report(period: StatsPeriod) = StatsEngine.report(ledger, period, now, zone)

    private fun content(body: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                Box(Modifier.fillMaxSize().background(Dap.background)) { body() }
            }
        }
    }

    private fun save(name: String) {
        val root = File(System.getProperty("b28.renders", "/tmp/b28-renders")).apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val mayEllipsize = { text: String ->
        text.startsWith("Song ") || text.startsWith("Artist") || text.startsWith("Artista") || text.contains("!!!") ||
            text.contains("AD BOYZ") || text.startsWith("Una canción") || text.startsWith("Para correr") || text.startsWith("Album ") || text.startsWith("Genre ")
    }

    private fun check(label: String) {
        RenderChecks.assertInsideWindow(compose, label)
        RenderChecks.assertNoCutText(compose, label, mayEllipsize)
        save(label)
    }

    private val noTopActions = TopActions(onSize = {}, onYear = {}, onMonths = { _, _ -> }, onPlay = {}, onShuffle = {}, onSave = {})

    private fun stats(ui: StatsUiState) = content {
        StatsContent(
            ui = ui, seeAll = null, zone = zone, onSeeAll = {}, onRange = {}, onMode = {}, onLayer = {}, onRetry = {},
            topActions = noTopActions,
        )
    }

    private fun summaryState(range: StatsTimeRange = StatsTimeRange.ALL, mode: com.lostf1sh.pixelplayeross.data.stats.StatsRankMode = com.lostf1sh.pixelplayeross.data.stats.StatsRankMode.PLAYS) =
        StatsUiState(
            period = StatsPeriod.Preset(range), rankMode = mode, isLoading = false,
            report = report(StatsPeriod.Preset(range)), top = TopUiState(from = YearMonth.of(2026, 1), to = YearMonth.of(2026, 12)),
        )

    private fun topState(mode: com.lostf1sh.pixelplayeross.data.stats.StatsRankMode = com.lostf1sh.pixelplayeross.data.stats.StatsRankMode.PLAYS): StatsUiState {
        val period = StatsPeriod.Months(YearMonth.of(2026, 1), YearMonth.of(2026, 12))
        return StatsUiState(
            rankMode = mode, layer = StatsLayer.TOP, isLoading = false,
            top = TopUiState(size = 50, from = period.from, to = period.to, report = report(period)),
        )
    }

    // --- Stats ------------------------------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "es-w360dp-h805dp-xhdpi")
    fun stats_first_screen_es() {
        stats(summaryState(StatsTimeRange.ALL))
        check("stats-01-first-screen-es")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h6200dp-xhdpi")
    fun stats_whole_summary_es() {
        stats(summaryState(StatsTimeRange.ALL))
        check("stats-02-summary-es")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h2400dp-xhdpi")
    fun stats_week_and_time_mode_es() {
        stats(summaryState(StatsTimeRange.WEEK, com.lostf1sh.pixelplayeross.data.stats.StatsRankMode.TIME))
        check("stats-03-week-time-es")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h3600dp-xhdpi")
    fun stats_top_es() {
        stats(topState())
        check("stats-04-top-es")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h805dp-xhdpi")
    fun stats_states_es() {
        stats(StatsUiState(isLoading = true, top = TopUiState(from = YearMonth.of(2026, 1), to = YearMonth.of(2026, 12))))
        check("stats-05-loading-es")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h805dp-xhdpi")
    fun stats_no_history_es() {
        val empty = StatsEngine.report(ListeningLedger.Empty, StatsPeriod.Week, now, zone)
        stats(StatsUiState(isLoading = false, report = empty, top = TopUiState(from = YearMonth.of(2026, 1), to = YearMonth.of(2026, 12))))
        check("stats-06-no-history-es")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h805dp-xhdpi")
    fun stats_range_without_listening_es() {
        // History exists, but nothing in Today.
        val quiet = ListeningLedger.build(listOf(Kit.play(1, now - 20 * Kit.DAY, occurrence = "q")), Kit.catalog(listOf(Kit.song(1))))
        val day = StatsEngine.report(quiet, StatsPeriod.Preset(StatsTimeRange.DAY), now, zone)
        stats(StatsUiState(period = StatsPeriod.Preset(StatsTimeRange.DAY), isLoading = false, report = day, top = TopUiState(from = YearMonth.of(2026, 1), to = YearMonth.of(2026, 12))))
        check("stats-07-range-empty-es")
    }

    @Test
    @Config(qualifiers = "sv-w360dp-h6200dp-xhdpi")
    fun stats_whole_summary_sv() {
        stats(summaryState(StatsTimeRange.ALL))
        check("stats-08-summary-sv")
    }

    @Test
    @Config(qualifiers = "sv-w360dp-h3600dp-xhdpi")
    fun stats_top_sv() {
        stats(topState())
        check("stats-09-top-sv")
    }

    @Test
    @Config(qualifiers = "en-w360dp-h2400dp-xhdpi")
    fun stats_week_en() {
        stats(summaryState(StatsTimeRange.WEEK))
        check("stats-10-week-en")
    }

    // --- Playlists --------------------------------------------------------------------------------------------------

    private val sources = AutoCollectionEngine.compute(ledger, now, zone, songs.sortedBy { it.title }.map { it.id })
    private val epochDay = AutoCollectionRepository.localEpochDay(now, zone)
    private val daily = AutoCollectionEngine.dailyMix(ledger, sources, epochDay)
    private val allCollections = buildList {
        add(daily)
        AutoCollectionId.entries.filter { it != AutoCollectionId.DAILY_MIX }.forEach { id -> add(sources.getValue(id)) }
    }

    private fun playlists(hidden: Set<AutoCollectionId>, userPlaylists: Int = 2) = content {
        PlaylistsList(
            collections = AutoCollectionsState(true, allCollections, hidden),
            playlists = (1..userPlaylists).map {
                Playlist(id = "p$it", name = if (it == 1) "Top 50 · 2026" else "Para correr de noche, con un nombre bastante largo", songIds = emptyList())
            }.toImmutableList(),
            playlistsLoading = false,
            currentSortOption = null,
            isRefreshing = false,
            onRefresh = {},
            listBottom = 60.dp,
            scrollBarBottom = 16.dp,
            isSelectionMode = false,
            selectedPlaylistIds = emptySet(),
            observeSongs = { flowOf(emptyList()) },
            onOpenCollection = {},
            onHideCollection = {},
            onShowCollection = {},
            onOpenPlaylist = {},
            onPlaylistLongPress = {},
            onOpenRuleCollections = {},
        )
    }

    @Test
    @Config(qualifiers = "es-w360dp-h1700dp-xhdpi")
    fun playlists_es() {
        playlists(hidden = setOf(AutoCollectionId.NIGHT))
        check("playlists-01-es")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h805dp-xhdpi")
    fun playlists_first_screen_es() {
        playlists(hidden = emptySet(), userPlaylists = 0)
        check("playlists-02-first-screen-no-user-lists-es")
    }

    @Test
    @Config(qualifiers = "sv-w360dp-h1700dp-xhdpi")
    fun playlists_sv() {
        playlists(hidden = setOf(AutoCollectionId.NIGHT))
        check("playlists-03-sv")
    }

    // --- one collection ---------------------------------------------------------------------------------------------

    private fun page(id: AutoCollectionId, collection: AutoCollection?, hidden: Boolean = false) = content {
        CollectionPage(
            id = id, collection = collection, isHidden = hidden, currentSongId = songs[3].id,
            onHide = {}, onShow = {}, onPlay = {}, onShuffle = {}, onOpenSong = {}, onMore = {},
        )
    }

    @Test
    @Config(qualifiers = "es-w360dp-h1610dp-xhdpi")
    fun collection_on_repeat_es() {
        page(AutoCollectionId.ON_REPEAT, sources.getValue(AutoCollectionId.ON_REPEAT))
        check("collection-01-on-repeat-es")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h805dp-xhdpi")
    fun collection_forgotten_favorites_empty_es() {
        page(AutoCollectionId.FORGOTTEN_FAVORITES, AutoCollection(AutoCollectionId.FORGOTTEN_FAVORITES, emptyList(), days = 30, emptyReason = EmptyReason.NOTHING_MATCHES))
        check("collection-02-forgotten-empty-es")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h805dp-xhdpi")
    fun collection_high_resolution_no_data_es() {
        page(AutoCollectionId.HIGH_RESOLUTION, sources.getValue(AutoCollectionId.HIGH_RESOLUTION).let { AutoCollection(it.id, emptyList(), emptyReason = EmptyReason.NO_RESOLUTION_DATA) })
        check("collection-03-high-resolution-no-data-es")
    }

    @Test
    @Config(qualifiers = "sv-w360dp-h805dp-xhdpi")
    fun collection_a_year_ago_too_little_history_sv() {
        page(AutoCollectionId.A_YEAR_AGO, AutoCollection(AutoCollectionId.A_YEAR_AGO, emptyList(), days = 3, emptyReason = EmptyReason.TOO_LITTLE_HISTORY, needDays = 365, haveDays = 70))
        check("collection-04-year-ago-sv")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h805dp-xhdpi")
    fun collection_hidden_es() {
        page(AutoCollectionId.NIGHT, sources.getValue(AutoCollectionId.NIGHT), hidden = true)
        check("collection-05-hidden-es")
    }

    // --- B28-fix2 -----------------------------------------------------------------------------------------------------------

    /** 27 days in a row with a play, the last one today: a current streak of 27, and a week that holds only the last four of them. */
    private val streakLedger = ListeningLedger.build(
        (0 until 27).map { Kit.play(1, now - it * Kit.DAY, occurrence = "streak$it") },
        Kit.catalog(listOf(Kit.song(1))),
    )

    private fun streakState(range: StatsTimeRange): StatsUiState {
        val period = StatsPeriod.Preset(range)
        return StatsUiState(
            period = period, isLoading = false, report = StatsEngine.report(streakLedger, period, now, zone),
            top = TopUiState(from = YearMonth.of(2026, 1), to = YearMonth.of(2026, 12)),
        )
    }

    @Test
    @Config(qualifiers = "es-w360dp-h6200dp-xhdpi")
    fun the_streak_pair_of_a_week_says_the_longest_is_the_weeks_es() {
        stats(streakState(StatsTimeRange.WEEK))

        // "Actual 27 días" runs back over the whole history; the longest is the week's, and says so, so it can be shorter.
        compose.onNodeWithText("27 días").assertIsDisplayed()
        compose.onNodeWithText("Más larga en este periodo").assertIsDisplayed()
        compose.onAllNodesWithText("Más larga").assertCountEquals(0)
        check("fix2-streak-week-es")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h6200dp-xhdpi")
    fun the_streak_pair_of_all_time_needs_no_period_label_es() {
        stats(streakState(StatsTimeRange.ALL))

        compose.onNodeWithText("Más larga").assertIsDisplayed()
        compose.onAllNodesWithText("Más larga en este periodo").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "en-w360dp-h6200dp-xhdpi")
    fun the_streak_pair_of_a_month_says_the_longest_is_the_months_en() {
        stats(streakState(StatsTimeRange.MONTH))

        compose.onNodeWithText("Longest in this period").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "sv-w360dp-h6200dp-xhdpi")
    fun the_streak_pair_of_a_year_says_the_longest_is_the_years_sv() {
        stats(streakState(StatsTimeRange.YEAR))

        compose.onNodeWithText("Längsta under perioden").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "es-w360dp-h1000dp-xhdpi")
    fun a_hidden_collection_row_keeps_its_count_apart_from_its_action_es() {
        // Three hidden: one with nothing in it ("0"), one with a hundred, and one of the rest. (A long why-line, like "Alta resolución"'s, is cut
        // at two lines in a hidden row, at HEAD as well: the action is wider than the overflow button. Those lines are left out of this render.)
        playlists(hidden = setOf(AutoCollectionId.FORGOTTEN_FAVORITES, AutoCollectionId.DEEP_CUTS, AutoCollectionId.NIGHT), userPlaylists = 0)
        compose.onNodeWithText("Ocultas (3)").performClick()
        compose.waitForIdle()
        check("fix2-hidden-rows-es")

        // On every hidden row the action starts clear of the count: about as far as the visible rows keep it from their button, never touching.
        // The unmerged tree: a row is one clickable that merges its texts, and the count is one of them.
        val actions = compose.onAllNodesWithText("Mostrar", useUnmergedTree = true).fetchSemanticsNodes()
        assertThat(actions).hasSize(3)
        val counts = compose.onAllNodes(
            SemanticsMatcher("a count") { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("")?.matches(Regex("\\d+")) == true },
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val minGapPx = with(compose.density) { 12.dp.toPx() }
        actions.forEach { action ->
            val row = action.boundsInRoot
            val count = counts.filter { it.boundsInRoot.right <= row.left && it.boundsInRoot.top < row.bottom && it.boundsInRoot.bottom > row.top }
                .maxByOrNull { it.boundsInRoot.right }
            assertThat(count).isNotNull()
            assertThat(row.left - count!!.boundsInRoot.right).isAtLeast(minGapPx)
        }
    }

    // --- the bottom of a pushed screen --------------------------------------------------------------------------------------

    /**
     * What the shell gives a pushed screen at the bottom: a navigation bar of [navigationBar] and, over the content and flush above it,
     * the opaque mini player of [miniPlayer] (the shell publishes its height as the shelf). There is no bottom bar on these screens, so
     * the strip the navigation bar occupies is the screen's own: whatever the content draws there is seen between the mini player and
     * the gesture bar. A made-up shell for the render, not a screenshot of the phone.
     */
    @Composable
    private fun PushedShell(navigationBar: androidx.compose.ui.unit.Dp, miniPlayer: androidx.compose.ui.unit.Dp, body: @Composable () -> Unit) {
        // The view the insets are handed to once the screen has registered for them (it does so in an effect of its own, after this one).
        val view = LocalView.current
        SideEffect { hostView = view }
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalDapPlayerShelfHeight provides miniPlayer) { body() }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = navigationBar).fillMaxWidth().height(miniPlayer).background(Color(0xFF2B312E)),
                contentAlignment = Alignment.Center,
            ) { Text("Mini player", color = Color.White) }
        }
    }

    private var hostView: android.view.View? = null

    private fun scrolledUnderTheMiniPlayer(body: @Composable () -> Unit) {
        content { PushedShell(navigationBar = 24.dp, miniPlayer = 64.dp, body = body) }
        compose.waitForIdle()
        // A navigation bar of 24 dp at the bottom, handed over the way the system does, after the screen is listening for it.
        val navPx = with(compose.density) { 24.dp.roundToPx() }
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                hostView!!,
                WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx)).build(),
            )
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "es-w360dp-h805dp-xhdpi")
    fun stats_scrolled_under_the_mini_player_es() {
        scrolledUnderTheMiniPlayer {
            StatsContent(
                ui = summaryState(StatsTimeRange.ALL), seeAll = null, zone = zone, onSeeAll = {}, onRange = {}, onMode = {}, onLayer = {}, onRetry = {},
                topActions = noTopActions,
            )
        }
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToIndex(9)
        compose.waitForIdle()
        save("fix2-stats-under-mini-player-es")
    }

    @Test
    @Config(qualifiers = "es-w360dp-h805dp-xhdpi")
    fun a_collection_page_scrolled_under_the_mini_player_es() {
        scrolledUnderTheMiniPlayer {
            CollectionPage(
                id = AutoCollectionId.NEVER_PLAYED, collection = sources.getValue(AutoCollectionId.NEVER_PLAYED), isHidden = false,
                currentSongId = songs[3].id, onHide = {}, onShow = {}, onPlay = {}, onShuffle = {}, onOpenSong = {}, onMore = {},
            )
        }
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToIndex(20)
        compose.waitForIdle()
        save("fix2-collection-under-mini-player-es")
    }
}
