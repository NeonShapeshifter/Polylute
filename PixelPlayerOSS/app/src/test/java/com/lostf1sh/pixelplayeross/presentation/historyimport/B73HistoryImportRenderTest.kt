package com.lostf1sh.pixelplayeross.presentation.historyimport

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.lostf1sh.pixelplayeross.data.backup.model.BackupHistoryEntry
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.historyimport.CsvColumnMapping
import com.lostf1sh.pixelplayeross.data.historyimport.CsvDurationUnit
import com.lostf1sh.pixelplayeross.data.historyimport.ImportCounts
import com.lostf1sh.pixelplayeross.data.historyimport.ImportPreview
import com.lostf1sh.pixelplayeross.data.historyimport.ImportRecord
import com.lostf1sh.pixelplayeross.data.historyimport.ImportSourceKind
import com.lostf1sh.pixelplayeross.data.historyimport.PreparedImport
import com.lostf1sh.pixelplayeross.data.historyimport.UnmatchedArtistCount
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.HistoryImportContent
import com.lostf1sh.pixelplayeross.presentation.settings.BackupSettingsActions
import com.lostf1sh.pixelplayeross.presentation.settings.BackupSettingsScreen
import com.lostf1sh.pixelplayeross.presentation.settings.BackupSettingsState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportStep
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HistoryImportUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h1200dp-xhdpi")
class B73HistoryImportRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val sampleRecords = listOf(
        ImportRecord(
            id = "imp-1",
            sourceKind = ImportSourceKind.SPOTIFY_EXTENDED,
            fileNames = listOf("my_spotify_data.zip"),
            importedAtMs = 1_760_000_000_000L,
            dateRangeStartMs = 1_483_228_800_000L, // 2017
            dateRangeEndMs = 1_735_603_200_000L,   // 2024
            counts = ImportCounts(
                rowsRead = 195_000,
                plays = 182_340,
                skips = 11_400,
                podcastsSkipped = 1_260,
                matchedExact = 120_000,
                matchedFolded = 35_000,
                matchedPartial = 10_929,
                unmatched = 16_411,
                totalListenedMs = 36_468_000_000L,
            ),
        ),
        ImportRecord(
            id = "imp-2",
            sourceKind = ImportSourceKind.YOUTUBE_MUSIC,
            fileNames = listOf("watch-history.json"),
            importedAtMs = 1_760_100_000_000L,
            dateRangeStartMs = 1_577_836_800_000L, // 2020
            dateRangeEndMs = 1_704_067_200_000L,   // 2024
            counts = ImportCounts(
                rowsRead = 24_500,
                plays = 12_400,
                nonMusicSkipped = 12_100,
                matchedExact = 8_000,
                matchedFolded = 2_100,
                unmatched = 2_300,
                estimatedPlays = 12_400,
                estimatedTimeMs = 2_100_000_000L,
                zeroDurationPlays = 2_300,
            ),
        ),
    )

    private val samplePreview = ImportPreview(
        sourceKind = ImportSourceKind.YOUTUBE_MUSIC,
        fileNames = listOf("watch-history.json"),
        dateRangeStartMs = 1_577_836_800_000L,
        dateRangeEndMs = 1_704_067_200_000L,
        counts = ImportCounts(
            rowsRead = 24_500,
            plays = 12_400,
            nonMusicSkipped = 12_100,
            duplicatesSkipped = 45,
            rejectedCount = 12,
            matchedExact = 8_000,
            matchedFolded = 2_100,
            matchedPartial = 320,
            unmatched = 1_980,
            estimatedPlays = 12_400,
            estimatedTimeMs = 2_100_000_000L,
            zeroDurationPlays = 1_980,
        ),
        topUnmatchedArtists = listOf(
            UnmatchedArtistCount("Synthetic Horizon", 412),
            UnmatchedArtistCount("Klara Sol", 285),
            UnmatchedArtistCount("Vega Trio", 190),
            UnmatchedArtistCount("Fjord Echo", 118),
            UnmatchedArtistCount("Lune Bleue", 94),
        ),
        rejectionsSample = emptyList(),
        hasDurationData = false,
        prepared = PreparedImport(
            sourceKind = ImportSourceKind.YOUTUBE_MUSIC,
            fileNames = listOf("watch-history.json"),
            tracks = emptyList(),
            trackIndices = IntArray(0),
            timestampsMs = LongArray(0),
            listenedMs = LongArray(0),
            estimated = BooleanArray(0),
            sourceListenedMs = LongArray(0),
            dedupHi = LongArray(0),
            dedupLo = LongArray(0),
            dateRangeStartMs = 1_577_836_800_000L,
            dateRangeEndMs = 1_704_067_200_000L,
            counts = ImportCounts(),
            topUnmatchedArtists = emptyList(),
            rejectionsSample = emptyList(),
        ),
    )

    private val sampleCsvMapping = CsvColumnMapping(
        delimiter = ';',
        hasHeader = true,
        headers = listOf("Fecha", "Artista", "Título", "Álbum", "Segundos"),
        sampleRows = listOf(
            listOf("2024-03-01T12:00:00Z", "Klara Sol", "Northern Lights", "Winter Drift", "210"),
            listOf("2024-03-02T14:30:00Z", "Vega Trio", "Paper Boat", "Coast", "185"),
        ),
        dateColumn = 0,
        artistColumn = 1,
        titleColumn = 2,
        albumColumn = 3,
        durationColumn = 4,
        durationUnit = CsvDurationUnit.SECONDS,
    )

    private fun renderAndSave(name: String, baseTheme: DapBaseTheme, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            DapTheme(baseTheme = baseTheme, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    content()
                }
            }
        }
        compose.waitForIdle()
        val outDir = File(System.getenv("B73_RENDERS") ?: "build/test-artifacts/B73/renders").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun importScreen(ui: HistoryImportUiState, mode: EffectiveProductMode = EffectiveProductMode.APP) =
        HistoryImportContent(
            ui = ui,
            effectiveMode = mode,
            onPickFiles = {},
            onCancel = {},
            onUpdateCsvMapping = {},
            onUpdateCsvDelimiter = {},
            onConfirmCsv = {},
            onConfirmImport = {},
            onRemoveImport = {},
        )

    // --- Import screen + Imported history list (en/es/sv, dark & light) ---
    @Test @Config(qualifiers = "en-w360dp-h1200dp-xhdpi")
    fun render_import_screen_en_dark() = renderAndSave("import-screen-en-dark", DapBaseTheme.Graphite) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.Idle, pastImports = sampleRecords))
    }

    @Test @Config(qualifiers = "en-w360dp-h1200dp-xhdpi")
    fun render_import_screen_en_light() = renderAndSave("import-screen-en-light", DapBaseTheme.Light) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.Idle, pastImports = sampleRecords))
    }

    @Test @Config(qualifiers = "es-w360dp-h1200dp-xhdpi")
    fun render_import_screen_es_dark() = renderAndSave("import-screen-es-dark", DapBaseTheme.Graphite) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.Idle, pastImports = sampleRecords))
    }

    @Test @Config(qualifiers = "es-w360dp-h1200dp-xhdpi")
    fun render_import_screen_es_light() = renderAndSave("import-screen-es-light", DapBaseTheme.Light) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.Idle, pastImports = sampleRecords))
    }

    @Test @Config(qualifiers = "sv-w360dp-h1200dp-xhdpi")
    fun render_import_screen_sv_dark() = renderAndSave("import-screen-sv-dark", DapBaseTheme.Graphite) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.Idle, pastImports = sampleRecords))
    }

    @Test @Config(qualifiers = "sv-w360dp-h1200dp-xhdpi")
    fun render_import_screen_sv_light() = renderAndSave("import-screen-sv-light", DapBaseTheme.Light) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.Idle, pastImports = sampleRecords))
    }

    // --- Preview summary (en/es/sv, dark & light) ---
    @Test @Config(qualifiers = "en-w360dp-h1600dp-xhdpi")
    fun render_preview_en_dark() = renderAndSave("preview-en-dark", DapBaseTheme.Graphite) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.Preview(samplePreview)))
    }

    @Test @Config(qualifiers = "en-w360dp-h1600dp-xhdpi")
    fun render_preview_en_light() = renderAndSave("preview-en-light", DapBaseTheme.Light) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.Preview(samplePreview)))
    }

    @Test @Config(qualifiers = "es-w360dp-h1600dp-xhdpi")
    fun render_preview_es_dark() = renderAndSave("preview-es-dark", DapBaseTheme.Graphite) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.Preview(samplePreview)))
    }

    @Test @Config(qualifiers = "es-w360dp-h1600dp-xhdpi")
    fun render_preview_es_light() = renderAndSave("preview-es-light", DapBaseTheme.Light) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.Preview(samplePreview)))
    }

    @Test @Config(qualifiers = "sv-w360dp-h1600dp-xhdpi")
    fun render_preview_sv_dark() = renderAndSave("preview-sv-dark", DapBaseTheme.Graphite) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.Preview(samplePreview)))
    }

    @Test @Config(qualifiers = "sv-w360dp-h1600dp-xhdpi")
    fun render_preview_sv_light() = renderAndSave("preview-sv-light", DapBaseTheme.Light) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.Preview(samplePreview)))
    }

    // --- CSV mapping (en/es/sv, dark & light) ---
    @Test @Config(qualifiers = "en-w360dp-h1200dp-xhdpi")
    fun render_csv_mapping_en_dark() = renderAndSave("csv-mapping-en-dark", DapBaseTheme.Graphite) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.CsvMapping(emptyList(), sampleCsvMapping)))
    }

    @Test @Config(qualifiers = "en-w360dp-h1200dp-xhdpi")
    fun render_csv_mapping_en_light() = renderAndSave("csv-mapping-en-light", DapBaseTheme.Light) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.CsvMapping(emptyList(), sampleCsvMapping)))
    }

    @Test @Config(qualifiers = "es-w360dp-h1200dp-xhdpi")
    fun render_csv_mapping_es_dark() = renderAndSave("csv-mapping-es-dark", DapBaseTheme.Graphite) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.CsvMapping(emptyList(), sampleCsvMapping)))
    }

    @Test @Config(qualifiers = "es-w360dp-h1200dp-xhdpi")
    fun render_csv_mapping_es_light() = renderAndSave("csv-mapping-es-light", DapBaseTheme.Light) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.CsvMapping(emptyList(), sampleCsvMapping)))
    }

    @Test @Config(qualifiers = "sv-w360dp-h1200dp-xhdpi")
    fun render_csv_mapping_sv_dark() = renderAndSave("csv-mapping-sv-dark", DapBaseTheme.Graphite) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.CsvMapping(emptyList(), sampleCsvMapping)))
    }

    @Test @Config(qualifiers = "sv-w360dp-h1200dp-xhdpi")
    fun render_csv_mapping_sv_light() = renderAndSave("csv-mapping-sv-light", DapBaseTheme.Light) {
        importScreen(HistoryImportUiState(step = HistoryImportStep.CsvMapping(emptyList(), sampleCsvMapping)))
    }

    // --- Backup settings Imported history list (en/es/sv, dark & light) ---
    private val noBackupActions = object : BackupSettingsActions {
        override fun onDismissInfo() {}
        override fun onExport(uri: Uri, sections: Set<BackupSection>, passphrase: String?) {}
        override fun onInspectBackup(uri: Uri, passphrase: String?) {}
        override fun onDismissEncryptedBackupPrompt() {}
        override fun onClearRestorePlan() {}
        override fun onUpdateRestorePlanSelection(selected: Set<BackupSection>) {}
        override fun onRestore(uri: Uri) {}
        override fun onRemoveBackupHistoryEntry(entry: BackupHistoryEntry) {}
    }

    @ComposableOrHelper
    private fun renderBackupImportedList(name: String, baseTheme: DapBaseTheme) = renderAndSave(name, baseTheme) {
        Surface(Modifier.fillMaxSize(), color = Dap.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BackupSettingsScreen(
                    BackupSettingsState(
                        infoDismissed = true,
                        importedHistory = persistentListOf(sampleRecords[0], sampleRecords[1]),
                    ),
                    noBackupActions,
                )
            }
        }
    }

    @Test @Config(qualifiers = "en-w360dp-h1200dp-xhdpi")
    fun render_imported_history_settings_en_dark() = renderBackupImportedList("imported-history-settings-en-dark", DapBaseTheme.Graphite)

    @Test @Config(qualifiers = "en-w360dp-h1200dp-xhdpi")
    fun render_imported_history_settings_en_light() = renderBackupImportedList("imported-history-settings-en-light", DapBaseTheme.Light)

    @Test @Config(qualifiers = "es-w360dp-h1200dp-xhdpi")
    fun render_imported_history_settings_es_dark() = renderBackupImportedList("imported-history-settings-es-dark", DapBaseTheme.Graphite)

    @Test @Config(qualifiers = "es-w360dp-h1200dp-xhdpi")
    fun render_imported_history_settings_es_light() = renderBackupImportedList("imported-history-settings-es-light", DapBaseTheme.Light)

    @Test @Config(qualifiers = "sv-w360dp-h1200dp-xhdpi")
    fun render_imported_history_settings_sv_dark() = renderBackupImportedList("imported-history-settings-sv-dark", DapBaseTheme.Graphite)

    @Test @Config(qualifiers = "sv-w360dp-h1200dp-xhdpi")
    fun render_imported_history_settings_sv_light() = renderBackupImportedList("imported-history-settings-sv-light", DapBaseTheme.Light)

    @Test
    @Config(qualifiers = "en-w360dp-h1200dp-xhdpi")
    fun `pinned mode disables picker and shows unpin note while dedicated allows picker`() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    importScreen(HistoryImportUiState(), EffectiveProductMode.PINNED)
                }
            }
        }
        compose.onNodeWithText("Unpin Polylute first to open Android's file picker.").assertExists()
        compose.onNodeWithText("Choose files").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "en-w360dp-h1200dp-xhdpi")
    fun `dedicated mode enables picker`() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    importScreen(HistoryImportUiState(), EffectiveProductMode.DEDICATED_ACTIVE)
                }
            }
        }
        compose.onNodeWithText("Choose files").assertIsEnabled()
    }
}

private annotation class ComposableOrHelper
