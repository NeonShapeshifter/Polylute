package com.lostf1sh.pixelplayeross.presentation.stats

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.historyimport.ImportCounts
import com.lostf1sh.pixelplayeross.data.historyimport.ImportRecord
import com.lostf1sh.pixelplayeross.data.historyimport.ImportSourceKind
import com.lostf1sh.pixelplayeross.data.historyimport.ImportedStatsInput
import com.lostf1sh.pixelplayeross.data.historyimport.ImportedStatsProvider
import com.lostf1sh.pixelplayeross.data.historyimport.ImportedStatsTrack
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.ClockChanges
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.StatsEngine
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsRankMode
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.StatsContent
import com.lostf1sh.pixelplayeross.presentation.viewmodel.InMemoryIncludeImportedPreference
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsLayer
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TopUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.mockk
import java.time.YearMonth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h1400dp-xhdpi")
class B73StatsIntegrationTest {

    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val now = Kit.at(2026, 10, 15, 20)
    private val song1 = Kit.song(1, title = "Harbor Mist", artist = "Sora Linn", album = "Coast", durationMs = 200_000L)
        .copy(artists = listOf(ArtistRef(1, "Sora Linn", true)))
    private val catalog = Kit.catalog(listOf(song1))
    private val ledger = ListeningLedger.build(
        listOf(Kit.play(1, Kit.at(2026, 3, 10, 12), occurrence = "n1")),
        catalog,
    )

    private val importedTracks = arrayOf(
        ImportedStatsTrack("item-1", "Harbor Mist", "Sora Linn", listOf("Sora Linn"), "Coast", catalog.item("item-1"), null),
        ImportedStatsTrack("imported:track:sora_linn:lost_pier:NONE", "Lost Pier", "Sora Linn", listOf("Sora Linn"), "Unmatched Album", null, "imported:album:unmatched_album:sora_linn"),
        ImportedStatsTrack("imported:track:vespera:far_beacon:NONE", "Far Beacon", "Vespera", listOf("Vespera"), "", null, null),
    )

    private val importedInput = ImportedStatsInput(
        tracks = importedTracks,
        trackIndices = intArrayOf(0, 1, 2),
        timestampsMs = longArrayOf(
            Kit.at(2021, 5, 10, 12),
            Kit.at(2022, 6, 10, 12),
            Kit.at(2023, 7, 10, 12),
        ),
        listenedMs = longArrayOf(180_000L, 210_000L, 190_000L),
        estimated = booleanArrayOf(false, false, true),
    )

    private val fakeImportedProvider = object : ImportedStatsProvider {
        override val records: StateFlow<List<ImportRecord>> = MutableStateFlow(
            listOf(
                ImportRecord(
                    id = "imp-1",
                    sourceKind = ImportSourceKind.SPOTIFY_EXTENDED,
                    fileNames = listOf("history.json"),
                    importedAtMs = now,
                    dateRangeStartMs = importedInput.firstMs,
                    dateRangeEndMs = importedInput.lastMs,
                    counts = ImportCounts(rowsRead = 3, plays = 3, matchedExact = 1, unmatched = 2, totalListenedMs = 580_000L),
                ),
            ),
        )
        override val changes: Flow<Long> = MutableStateFlow(1L)
        override suspend fun statsInput(catalog: LibraryCatalog): ImportedStatsInput = importedInput
    }

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = dispatcher
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `switch ON includes 3 imported years and merges matched and name-only artists while switch OFF matches HEAD ledger`() = runTest(dispatcher) {
        val pref = InMemoryIncludeImportedPreference(true)
        val vm = StatsViewModel(
            ledgers = object : LedgerProvider {
                override val changes: Flow<Long> = emptyFlow()
                override suspend fun ledger() = ledger
            },
            playlists = mockk<PlaylistPreferencesRepository>(relaxed = true),
            librarySignal = LibraryChangeSignal(),
            dispatchers = dispatchers,
            clockChanges = object : ClockChanges { override val changes: Flow<Unit> = emptyFlow() },
            importedStats = fakeImportedProvider,
            includeImportedPref = pref,
        ).also {
            it.clock = { now }
            it.zone = { Kit.zone }
        }

        vm.onRangeSelected(StatsTimeRange.ALL)
        advanceUntilIdle()

        val onReport = vm.uiState.value.current!!
        assertThat(vm.uiState.value.hasImportedHistory).isTrue()
        assertThat(vm.uiState.value.includeImportedHistory).isTrue()
        assertThat(onReport.totals.plays).isEqualTo(4)
        assertThat(onReport.totals.listenedMs).isEqualTo(180_000L + 580_000L)
        assertThat(onReport.historyStartMs).isEqualTo(importedInput.firstMs)
        // Matched and name-only plays for Sora Linn merge
        val sora = onReport.rankings.artists(StatsRankMode.PLAYS).first { it.name == "Sora Linn" }
        assertThat(sora.plays).isEqualTo(3)
        // Home overview never includes imported plays
        assertThat(vm.homeOverview.value!!.totals.plays).isEqualTo(1)

        // Toggle OFF: report becomes identical to native-only ledger
        vm.onIncludeImportedHistoryChanged(false)
        advanceUntilIdle()
        val offReport = vm.uiState.value.current!!
        val nativeBaseline = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), now, Kit.zone)
        assertThat(offReport.totals.plays).isEqualTo(nativeBaseline.totals.plays)
        assertThat(offReport.totals.listenedMs).isEqualTo(nativeBaseline.totals.listenedMs)
        assertThat(offReport.historyStartMs).isEqualTo(nativeBaseline.historyStartMs)
    }

    @Test
    fun `Stats screen shows imported years in Top face, Include imported switch, and name-only rows`() {
        val report = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), now, Kit.zone, imported = importedInput)
        var toggledTo: Boolean? = null
        val ui = StatsUiState(
            period = StatsPeriod.Months.year(2022),
            layer = StatsLayer.TOP,
            isLoading = false,
            report = report,
            links = StatsLinks.of(catalog),
            top = TopUiState(
                from = YearMonth.of(2022, 1),
                to = YearMonth.of(2022, 12),
                report = StatsEngine.report(ledger, StatsPeriod.Months.year(2022), now, Kit.zone, imported = importedInput),
            ),
            hasImportedHistory = true,
            includeImportedHistory = true,
        )

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
                        onIncludeImportedChanged = { toggledTo = it },
                        onOpen = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Include imported history").assertExists()
        compose.onNodeWithText("2021").assertExists()
        compose.onNodeWithText("2022").assertExists()
        compose.onNodeWithText("2026").assertExists()
        compose.onNodeWithText("Lost Pier").assertExists()

        compose.onAllNodes(isToggleable())[0].performClick()
        assertThat(toggledTo).isEqualTo(false)
        compose.onAllNodesWithText("Harbor Mist").assertCountEquals(0)
    }
}
