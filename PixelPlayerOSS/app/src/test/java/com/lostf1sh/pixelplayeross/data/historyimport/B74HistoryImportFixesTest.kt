package com.lostf1sh.pixelplayeross.data.historyimport

import android.app.Application
import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.HistoryImportContent
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportStep
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h1200dp-xhdpi")
class B74HistoryImportFixesTest {

    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = dispatcher
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
    }

    private val matchedSong = Kit.song(
        id = 1L,
        title = "Long Song",
        artist = "Known Artist",
        album = "Long Album",
        durationMs = 300_000L, // 5:00 -> PlayRule threshold = 150_000 ms (2:30)
    ).copy(artists = listOf(ArtistRef(1L, "Known Artist", true)))
    private val catalog = Kit.catalog(listOf(matchedSong))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `B2 unknown-length rules for Spotify Extended, Account, CSV, and matched PlayRule`() = runBlocking {
        val extendedJson = """
            [
              {"ts":"2024-03-01T10:00:00Z","ms_played":130000,"master_metadata_track_name":"Trackdone Unmatched","master_metadata_album_artist_name":"Ghost","reason_end":"trackdone","skipped":false},
              {"ts":"2024-03-01T10:05:00Z","ms_played":50000,"master_metadata_track_name":"Skipped Unmatched","master_metadata_album_artist_name":"Ghost","reason_end":"fwdbtn","skipped":true},
              {"ts":"2024-03-01T10:10:00Z","ms_played":45000,"master_metadata_track_name":"Other 45s","master_metadata_album_artist_name":"Ghost","reason_end":"endplay","skipped":false},
              {"ts":"2024-03-01T10:15:00Z","ms_played":20000,"master_metadata_track_name":"Other 20s","master_metadata_album_artist_name":"Ghost","reason_end":"endplay","skipped":false},
              {"ts":"2024-03-01T10:20:00Z","ms_played":60000,"master_metadata_track_name":"Long Song","master_metadata_album_artist_name":"Known Artist","reason_end":"fwdbtn","skipped":false}
            ]
        """.trimIndent().toByteArray()

        val extPreview = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("Streaming_History_Audio_2024.json") { ByteArrayInputStream(extendedJson) }),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )
        assertThat(extPreview.counts.plays).isEqualTo(2)
        assertThat(extPreview.counts.skips).isEqualTo(3)
        assertThat(extPreview.prepared.tracks.map { it.title })
            .containsExactly("Trackdone Unmatched", "Other 45s")

        val accountJson = """
            [
              {"endTime":"2024-03-02 12:00","artistName":"Ghost","trackName":"Account 45s","msPlayed":45000},
              {"endTime":"2024-03-02 12:05","artistName":"Ghost","trackName":"Account 20s","msPlayed":20000}
            ]
        """.trimIndent().toByteArray()

        val accPreview = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("StreamingHistory_music_0.json") { ByteArrayInputStream(accountJson) }),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )
        assertThat(accPreview.counts.plays).isEqualTo(1)
        assertThat(accPreview.counts.skips).isEqualTo(1)
        assertThat(accPreview.prepared.tracks.single().title).isEqualTo("Account 45s")

        val csv = """
            date,artist,title,ms_played
            2024-03-03T12:00:00Z,Ghost,CSV 45s,45000
            2024-03-03T12:05:00Z,Ghost,CSV 20s,20000
        """.trimIndent().toByteArray()

        val csvPreview = HistoryImportPipeline.prepareImport(
            sources = listOf(ImportInputSource("history.csv") { ByteArrayInputStream(csv) }),
            catalog = catalog,
            existingDedupKeys = { emptySet() },
        )
        assertThat(csvPreview.counts.plays).isEqualTo(1)
        assertThat(csvPreview.counts.skips).isEqualTo(1)
        assertThat(csvPreview.prepared.tracks.single().title).isEqualTo("CSV 45s")
    }

    private fun zeroNewPlaysPreview(): ImportPreview = ImportPreview(
        sourceKind = ImportSourceKind.SPOTIFY_EXTENDED,
        fileNames = listOf("history.json"),
        dateRangeStartMs = null,
        dateRangeEndMs = null,
        counts = ImportCounts(rowsRead = 2, plays = 0, duplicatesSkipped = 2),
        topUnmatchedArtists = emptyList(),
        rejectionsSample = emptyList(),
        hasDurationData = true,
        prepared = PreparedImport(
            sourceKind = ImportSourceKind.SPOTIFY_EXTENDED,
            fileNames = listOf("history.json"),
            tracks = emptyList(),
            trackIndices = IntArray(0),
            timestampsMs = LongArray(0),
            listenedMs = LongArray(0),
            estimated = BooleanArray(0),
            sourceListenedMs = LongArray(0),
            dedupHi = LongArray(0),
            dedupLo = LongArray(0),
            dateRangeStartMs = null,
            dateRangeEndMs = null,
            counts = ImportCounts(rowsRead = 2, plays = 0, duplicatesSkipped = 2),
            topUnmatchedArtists = emptyList(),
            rejectionsSample = emptyList(),
        ),
    )

    @Test
    fun `B3 zero new plays disables Import button and says nothing new to import in en`() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    HistoryImportContent(
                        ui = HistoryImportUiState(step = HistoryImportStep.Preview(zeroNewPlaysPreview())),
                        effectiveMode = com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode.APP,
                        onPickFiles = {},
                        onCancel = {},
                        onUpdateCsvMapping = {},
                        onUpdateCsvDelimiter = {},
                        onConfirmCsv = {},
                        onConfirmImport = {},
                        onRemoveImport = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Nothing new to import").assertExists()
        compose.onNodeWithText("Done").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "es-w360dp-h1200dp-xhdpi")
    fun `B3 zero new plays disables Import button in Spanish`() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    HistoryImportContent(
                        ui = HistoryImportUiState(step = HistoryImportStep.Preview(zeroNewPlaysPreview())),
                        effectiveMode = com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode.APP,
                        onPickFiles = {},
                        onCancel = {},
                        onUpdateCsvMapping = {},
                        onUpdateCsvDelimiter = {},
                        onConfirmCsv = {},
                        onConfirmImport = {},
                        onRemoveImport = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Nada nuevo que importar").assertExists()
        compose.onNodeWithText("Hecho").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "sv-w360dp-h1200dp-xhdpi")
    fun `B3 zero new plays disables Import button in Swedish`() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    HistoryImportContent(
                        ui = HistoryImportUiState(step = HistoryImportStep.Preview(zeroNewPlaysPreview())),
                        effectiveMode = com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode.APP,
                        onPickFiles = {},
                        onCancel = {},
                        onUpdateCsvMapping = {},
                        onUpdateCsvDelimiter = {},
                        onConfirmCsv = {},
                        onConfirmImport = {},
                        onRemoveImport = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Inget nytt att importera").assertExists()
        compose.onNodeWithText("Klar").assertIsNotEnabled()
    }

    @Test
    fun `B3 HistoryImportViewModel confirmImport ignores preview with 0 new plays`() = runTest(dispatcher) {
        val store = mockk<ImportedHistoryStore>(relaxed = true)
        every { store.records } returns MutableStateFlow(emptyList())
        every { store.inspectInput(any()) } returns null
        val preview = zeroNewPlaysPreview()
        io.mockk.coEvery { store.prepareImport(any(), any(), any(), any()) } returns preview

        val vm = HistoryImportViewModel(store, dispatchers, ApplicationProvider.getApplicationContext<Context>())
        vm.onSourcesPicked(listOf(ImportInputSource("history.json") { ByteArrayInputStream(ByteArray(0)) }))
        advanceUntilIdle()
        assertThat(vm.uiState.value.step).isInstanceOf(HistoryImportStep.Preview::class.java)

        vm.confirmImport()
        advanceUntilIdle()
        coVerify(exactly = 0) { store.commitImport(any()) }
        assertThat(vm.uiState.value.step).isInstanceOf(HistoryImportStep.Preview::class.java)
    }
}
