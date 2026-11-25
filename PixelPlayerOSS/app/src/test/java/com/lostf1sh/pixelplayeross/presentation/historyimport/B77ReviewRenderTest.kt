package com.lostf1sh.pixelplayeross.presentation.historyimport

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.lostf1sh.pixelplayeross.data.historyimport.ImportCounts
import com.lostf1sh.pixelplayeross.data.historyimport.ImportFileDataKind
import com.lostf1sh.pixelplayeross.data.historyimport.ImportFileSkipReason
import com.lostf1sh.pixelplayeross.data.historyimport.ImportFileSummary
import com.lostf1sh.pixelplayeross.data.historyimport.ImportLikesPreview
import com.lostf1sh.pixelplayeross.data.historyimport.ImportPlaylistPreview
import com.lostf1sh.pixelplayeross.data.historyimport.ImportPreview
import com.lostf1sh.pixelplayeross.data.historyimport.ImportSourceKind
import com.lostf1sh.pixelplayeross.data.historyimport.ImportedTrackRef
import com.lostf1sh.pixelplayeross.data.historyimport.PreparedImport
import com.lostf1sh.pixelplayeross.data.historyimport.UnmatchedArtistCount
import com.lostf1sh.pixelplayeross.data.historyimport.UnmatchedTrackRef
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.HistoryImportContent
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportStep
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import com.lostf1sh.pixelplayeross.data.stats.Kit
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h1600dp-xhdpi")
class B77ReviewRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val openedPlaylistId = "pl-road-trip"

    private val syntheticPreview = ImportPreview(
        sourceKind = ImportSourceKind.SPOTIFY_ACCOUNT,
        fileNames = listOf("spotify_account.zip", "Liked Music.csv"),
        dateRangeStartMs = 1_577_836_800_000L, // 2020
        dateRangeEndMs = 1_735_603_200_000L,   // 2024
        counts = ImportCounts(
            rowsRead = 4_250,
            plays = 3_980,
            skips = 180,
            duplicatesSkipped = 90,
            matchedExact = 2_800,
            matchedFolded = 640,
            matchedPartial = 140,
            unmatched = 400,
            totalListenedMs = 796_000_000L,
        ),
        topUnmatchedArtists = listOf(
            UnmatchedArtistCount("Synthetic Horizon", 120),
            UnmatchedArtistCount("Fjord Echo", 85),
        ),
        rejectionsSample = emptyList(),
        hasDurationData = true,
        prepared = PreparedImport(
            sourceKind = ImportSourceKind.SPOTIFY_ACCOUNT,
            fileNames = listOf("spotify_account.zip"),
            tracks = emptyList(),
            trackIndices = IntArray(0),
            timestampsMs = LongArray(0),
            listenedMs = LongArray(0),
            estimated = BooleanArray(0),
            sourceListenedMs = LongArray(0),
            dedupHi = LongArray(0),
            dedupLo = LongArray(0),
            dateRangeStartMs = 1_577_836_800_000L,
            dateRangeEndMs = 1_735_603_200_000L,
            counts = ImportCounts(),
            topUnmatchedArtists = emptyList(),
            rejectionsSample = emptyList(),
        ),
        hasHistory = true,
        fileSummaries = listOf(
            ImportFileSummary("StreamingHistory_music_0.json", ImportFileDataKind.SPOTIFY_ACCOUNT_HISTORY),
            ImportFileSummary("YourLibrary.json", ImportFileDataKind.SPOTIFY_LIKES),
            ImportFileSummary("Playlist1.json", ImportFileDataKind.SPOTIFY_PLAYLISTS),
            ImportFileSummary("Liked Music.csv", ImportFileDataKind.SKIPPED, ImportFileSkipReason.YOUTUBE_VIDEO_ID_ONLY),
        ),
        likes = ImportLikesPreview(
            totalRead = 42,
            matchedTracks = listOf(
                ImportedTrackRef("1", "Northern Lights", "Klara Sol", "Winter Drift", 210_000L),
                ImportedTrackRef("2", "Paper Boat", "Vega Trio", "Coast", 185_000L),
            ),
            alreadyFavoriteCount = 1,
            unmatchedTracks = listOf(UnmatchedTrackRef("Missing Song", "Ghost Band")),
        ),
        playlists = listOf(
            ImportPlaylistPreview(
                targetId = openedPlaylistId,
                name = "Night Drive",
                totalItems = 18,
                matchedTracks = (1..12).map { i ->
                    ImportedTrackRef("$i", "Synthetic Track $i", "Klara Sol", "Winter Drift", 200_000L)
                },
                unmatchedTracks = (1..6).map { i ->
                    UnmatchedTrackRef("Unmatched Track $i", "Synth Artist $i")
                },
                included = true,
            ),
            ImportPlaylistPreview(
                targetId = "pl-morning",
                name = "Morning Coffee",
                totalItems = 15,
                matchedTracks = (5..16).map { i ->
                    ImportedTrackRef("$i", "Sunrise $i", "Vega Trio", "Coast", 190_000L)
                },
                unmatchedTracks = (1..3).map { i ->
                    UnmatchedTrackRef("Absent $i", "Other Band")
                },
                included = true,
            ),
        ),
        hiddenZeroMatchPlaylists = 2,
        playlistEpisodesSkipped = 3,
        playlistLocalTracksSkipped = 1,
    )

    private fun showReview(baseTheme: DapBaseTheme, opened: Boolean) {
        val openPlaylist = mutableStateOf(openedPlaylistId.takeIf { opened })
        val included = mutableStateOf<Map<String, Boolean>>(emptyMap())
        compose.setContent {
            DapTheme(baseTheme = baseTheme, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    HistoryImportContent(
                        ui = HistoryImportUiState(
                            step = HistoryImportStep.Preview(
                                syntheticPreview,
                                openPlaylistId = openPlaylist.value,
                                playlistIncluded = included.value,
                            ),
                        ),
                        onPickFiles = {},
                        onCancel = {},
                        onUpdateCsvMapping = {},
                        onUpdateCsvDelimiter = {},
                        onConfirmCsv = {},
                        onConfirmImport = {},
                        onRemoveImport = {},
                        onOpenPlaylist = { openPlaylist.value = it },
                        onTogglePlaylist = { id, checked -> included.value = included.value + (id to checked) },
                        observeSongs = { ids ->
                            // Simulate a repository emitting a different order from the import.
                            flowOf(ids.reversed().map { id ->
                                Kit.song(id = id.toLong(), title = "Synthetic Track $id", artist = "Klara Sol", album = "Winter Drift", albumId = 1L, track = id.toInt())
                            })
                        },
                    )
                }
            }
        }
    }

    private fun renderAndSave(name: String, baseTheme: DapBaseTheme, opened: Boolean = false) {
        showReview(baseTheme, opened)
        compose.waitForIdle()
        val outDir = File(System.getenv("B78_RENDERS") ?: "build/test-artifacts/B78", "after/import").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun render_b78_review_all_sections_dark() {
        renderAndSave("b78-review-all-sections-dark", DapBaseTheme.Graphite)
    }

    @Test
    fun render_b78_review_all_sections_light() {
        renderAndSave("b78-review-all-sections-light", DapBaseTheme.Light)
    }

    @Test
    fun render_b78_review_opened_playlist_dark() {
        renderAndSave("b78-review-opened-playlist-dark", DapBaseTheme.Graphite, opened = true)
    }

    @Test
    fun render_b78_review_opened_playlist_light() {
        renderAndSave("b78-review-opened-playlist-light", DapBaseTheme.Light, opened = true)
    }

    @Test
    fun opened_playlist_uses_passive_rows_in_source_order_and_returns_to_review() {
        showReview(DapBaseTheme.Graphite, opened = true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText("12 of 18 songs").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.presentation_batch_b_more_options)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.presentation_batch_b_play_it)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.presentation_batch_g_list_cd_more_for_title, "Synthetic Track 1")).assertDoesNotExist()
        val first = compose.onNodeWithText("Synthetic Track 1").assert(!hasClickAction())
        val second = compose.onNodeWithText("Synthetic Track 2").assert(!hasClickAction())
        assertThat(first.fetchSemanticsNode().boundsInRoot.top)
            .isLessThan(second.fetchSemanticsNode().boundsInRoot.top)
        val toggle = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        toggle.assertIsOn().performClick().assertIsOff()

        val missing = context.resources.getQuantityString(R.plurals.import_playlist_unmatched_count, 6, "6")
        compose.onNodeWithText("Unmatched Track 1").assertDoesNotExist()
        compose.onNodeWithText(missing).performClick()
        compose.onNodeWithText("Unmatched Track 1").assert(!hasClickAction())
        compose.onNodeWithContentDescription(context.getString(R.string.auth_cd_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.import_history_title)).assertIsDisplayed()
        compose.onNodeWithText("Night Drive").performClick()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOff()
    }

    @Test
    fun render_b77_universal_export_dialog_dark() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    com.lostf1sh.pixelplayeross.presentation.settings.UniversalExportSelectionContent(
                        inProgress = false,
                        onDismiss = {},
                        onSelectKind = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val outDir = File(System.getenv("B77_RENDERS") ?: "build/test-artifacts/B77/renders").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "b77-universal-export-dark.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun render_b77_pxpl_restore_mode_dark() {
        val sections = setOf(
            com.lostf1sh.pixelplayeross.data.backup.model.BackupSection.FAVORITES,
            com.lostf1sh.pixelplayeross.data.backup.model.BackupSection.PLAYLISTS,
            com.lostf1sh.pixelplayeross.data.backup.model.BackupSection.PLAYBACK_HISTORY,
        )
        val plan = com.lostf1sh.pixelplayeross.data.backup.model.RestorePlan(
            manifest = com.lostf1sh.pixelplayeross.data.backup.model.BackupManifest(
                appVersion = "1.0-synthetic",
                createdAt = 1_735_603_200_000L,
            ),
            backupUri = "content://synthetic/backup.pxpl",
            availableModules = sections,
            selectedModules = sections,
            moduleDetails = mapOf(
                com.lostf1sh.pixelplayeross.data.backup.model.BackupSection.FAVORITES to
                    com.lostf1sh.pixelplayeross.data.backup.model.ModuleRestoreDetail(42, 1024),
                com.lostf1sh.pixelplayeross.data.backup.model.BackupSection.PLAYLISTS to
                    com.lostf1sh.pixelplayeross.data.backup.model.ModuleRestoreDetail(7, 2048),
                com.lostf1sh.pixelplayeross.data.backup.model.BackupSection.PLAYBACK_HISTORY to
                    com.lostf1sh.pixelplayeross.data.backup.model.ModuleRestoreDetail(3980, 32768),
            ),
            restoreMode = com.lostf1sh.pixelplayeross.data.backup.restore.BackupRestoreMode.MERGE,
        )
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    com.lostf1sh.pixelplayeross.presentation.components.BackupModuleSelectionContent(
                        plan = plan,
                        inProgress = false,
                        onBack = {},
                        onSelectionChanged = {},
                        onRestoreModeChanged = {},
                        onConfirm = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val outDir = File(System.getenv("B77_RENDERS") ?: "build/test-artifacts/B77/renders").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "b77-pxpl-restore-mode-dark.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
