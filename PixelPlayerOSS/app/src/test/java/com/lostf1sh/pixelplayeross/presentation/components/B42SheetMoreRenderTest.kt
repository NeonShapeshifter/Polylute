package com.lostf1sh.pixelplayeross.presentation.components

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.PagingData
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCapability
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCurve
import com.lostf1sh.pixelplayeross.data.dac.fiio.AttachedFiio
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqReadout
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqVerdict
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteAvailability
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioFilterType
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfiles
import com.lostf1sh.pixelplayeross.data.headphones.DacEqFilterDef
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPreset
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.StorageFilter
import com.lostf1sh.pixelplayeross.presentation.components.player.CreditsSheet
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsSheet
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.DacEqFilterSheet
import com.lostf1sh.pixelplayeross.presentation.screens.DacEqPresetsSheet
import com.lostf1sh.pixelplayeross.presentation.screens.DacEqScreenActions
import com.lostf1sh.pixelplayeross.presentation.screens.QuickFillDialog
import com.lostf1sh.pixelplayeross.presentation.settings.MaintenanceToolsActions
import com.lostf1sh.pixelplayeross.presentation.settings.MaintenanceToolsSection
import com.lostf1sh.pixelplayeross.presentation.settings.MaintenanceToolsState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.CreditsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DirectoryEntry
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputDetails
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputRoute
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SelectedPresetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TrackDetailsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.glass.GlassWindowBackdrop
import com.lostf1sh.pixelplayeross.ui.glass.LocalCoverGlass
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42: the sheets that need a view model or a DAC, and the windows that are a whole screen (the folders, "save the queue as a playlist", the
 * track details, the credits, quick fill): the real ones over the real glass, saved and measured like the others.
 *
 * Track details, credits and quick fill are hosted by private composables of the player (a `Dialog` with the app's input bridge); the test
 * hosts them the same way, with the glass behind them as the hosts put it, and a source test pins that the hosts do.
 */
@OptIn(ExperimentalMaterial3Api::class)
open class B42SheetMoreRenderTest : B42SheetHarness() {

    private val songs = (1..8).map { n ->
        Song(
            id = "$n", title = listOf("Cine", "Ventana roja", "Luces de la ciudad", "Madrugada", "Calle vacía", "Noche", "Rojo", "Amanecer")[n - 1],
            artist = "Gemini Sound", artistId = 7L, album = "Noche", albumId = 9L, path = "/m/$n.flac", contentUriString = "content://$n",
            albumArtUriString = null, duration = 200_000L + n * 1000L, mimeType = "audio/flac", bitrate = 1000, sampleRate = 44100,
        )
    }

    // ── a player view model that holds a few songs ──────────────────────────────────────────────────

    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.playlistPickerStorageFilter } returns MutableStateFlow(StorageFilter.ALL)
        every { it.playlistPickerSongs } returns flowOf(PagingData.from(songs))
        every { it.playlistPickerFavoriteSongs } returns flowOf(PagingData.from(songs.take(2)))
        every { it.favoriteSongIds } returns MutableStateFlow(setOf("1", "3"))
        every { it.searchSongs(any()) } returns flowOf(emptyList())
    }

    @Test
    fun `the song picker`() = shows("song-picker") {
        SongPickerBottomSheet(initiallySelectedSongIds = setOf("1", "2"), onDismiss = {}, onConfirm = {}, playerViewModel = playerViewModel)
    }.let { }

    @Test
    fun `add to a playlist`() = shows("add-to-playlist") {
        PlaylistBottomSheet(
            playlistUiState = PlaylistUiState(
                playlists = persistentListOf(
                    com.lostf1sh.pixelplayeross.data.model.Playlist("1", "Noche", listOf("1", "2", "3")),
                    com.lostf1sh.pixelplayeross.data.model.Playlist("2", "Calle", listOf("4")),
                ),
            ),
            songs = songs.take(2).toImmutableList(), onDismiss = {}, bottomBarHeight = androidx.compose.ui.unit.Dp(0f),
            playerViewModel = playerViewModel, playlistViewModel = mockk<PlaylistViewModel>(relaxed = true),
        )
    }.let { }

    // ── a DAC's EQ ──────────────────────────────────────────────────────────────────────────────────

    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val capability = DacEqCapability.of(melody)
    private val attached = AttachedFiio("p", "id", "dac-melody", "SNOWSKY Melody", "p", melody)

    private fun curve(vararg gains: Pair<Int, Double>): List<DacEqBand> {
        val map = gains.toMap()
        val freqs = intArrayOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, freqs[it], map[it] ?: 0.0, 0.71) }
    }

    private val warm = curve(0 to 3.0, 1 to 2.0, 2 to 1.0, 4 to -1.5, 5 to -1.5, 7 to 1.0, 8 to 2.0, 9 to 3.0)

    private fun preset(id: String, name: String, bands: List<DacEqBand>) =
        DacEqPreset(id, name, "dac-melody", "snowsky-melody", "SNOWSKY Melody", DacEqCurve.preGainTenthsFor(bands), bands.map { DacEqFilterDef.of(it) }, null, 1L, 1L)

    private fun dacState(): DacEqUiState {
        val pre = DacEqCurve.preGainTenthsFor(warm)
        return DacEqUiState(
            profileName = "TYGR", dacName = "SNOWSKY Melody", dacProfileId = "dac-melody", availability = DacEqWriteAvailability.VERIFIED,
            capability = capability, readout = DacEqReadout.Pending(attached), verdict = DacEqVerdict.ON, bands = warm, preGainTenthsDb = pre,
            appSlotName = "USER 3", activePresetName = "USER 3", frozenByPurity = false, conflict = null, error = null, directActive = true,
            presets = listOf(preset("p1", "Warm", warm), preset("p2", "Reference", curve())), selectedPresetId = "p1",
            selectedPresetState = SelectedPresetState.MATCHES_DAC, reusable = emptyList(), netPeakDb = DacEqCurve.netPeakDb(warm, pre), debugOverride = false,
        )
    }

    private class NoDacActions : DacEqScreenActions {
        override fun onSetEnabled(on: Boolean) {}
        override fun onEditBand(index: Int, change: (DacEqBand) -> DacEqBand) {}
        override fun onResetFlat() {}
        override fun onSelectPreset(presetId: String) {}
        override fun onReusePreset(presetId: String) {}
        override fun onSavePreset(name: String, updatePresetId: String?) {}
        override fun onRenamePreset(presetId: String, name: String) {}
        override fun onDeletePreset(presetId: String) {}
        override fun onRefresh() {}
        override fun onDismissError() {}
        override fun onTakeOver() {}
        override fun onRestoreFactory() {}
        override fun onReadDacPresets() {}
        override fun onSelectDacPreset(presetId: Int, confirmed: Boolean) {}
        override fun onDismissConfirmSelect() {}
        override fun onOpenAudioSettings() {}
    }

    @Test
    fun `a DAC EQ filter`() = shows("dac-eq-filter") {
        DacEqFilterSheet(DacEqBand.of(3, FiioFilterType.PEAK, 1000, 2.0, 1.0), capability, {}, {})
    }.let { }

    @Test
    fun `the presets of a DAC EQ`() = shows("dac-eq-presets") {
        DacEqPresetsSheet(dacState(), NoDacActions(), {}, {}, {})
    }.let { }

    // ── the maintenance palette sheet, opened by a tap ──────────────────────────────────────────────

    @Test
    fun `the maintenance palette sheet`() = shows(
        "maintenance-palette",
        open = { compose.onNodeWithText(context().getString(R.string.setcat_choose_song)).performClick() },
    ) {
        MaintenanceToolsSection(
            MaintenanceToolsState(paletteTargets = songs.toImmutableList()),
            object : MaintenanceToolsActions {
                override suspend fun onRegeneratePalette(song: Song) = true
                override fun onRegenerateDailyMix() {}
                override fun onRegenerateStats() {}
                override fun onTriggerTestCrash() {}
            },
            offersTestCrash = false,
        )
    }.let { }

    // ── windows that are a whole screen ─────────────────────────────────────────────────────────────

    private val root = File("/storage/emulated/0")

    @Test
    fun `the folders window`() = shows("folders-window") {
        FileExplorerDialog(
            visible = true, currentPath = root,
            directoryChildren = persistentListOf(
                DirectoryEntry(File(root, "Music"), 12, 340, "/storage/emulated/0/Music"),
                DirectoryEntry(File(root, "Podcasts"), 0, 25, "/storage/emulated/0/Podcasts", isBlocked = true),
                DirectoryEntry(File(root, "Downloads"), 3, 3, "/storage/emulated/0/Downloads"),
            ),
            availableStorages = persistentListOf(), selectedStorageIndex = 0, isLoading = false, isAtRoot = true, rootDirectory = root,
            onNavigateTo = {}, onNavigateUp = {}, onNavigateHome = {}, onToggleAllowed = {}, onRefresh = {}, onStorageSelected = {}, onDone = {}, onDismiss = {},
        )
    }.let { }

    @Test
    fun `save the queue as a playlist`() = shows("save-queue") {
        SaveQueueAsPlaylistSheet(songs = songs.toImmutableList(), defaultName = "Queue", onDismiss = {}, onConfirm = { _, _ -> })
    }.let { }

    @Test
    fun `quick fill`() = shows("quick-fill") {
        CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
            QuickFillDialog(
                visible = true, songs = songs.toImmutableList(), customGenres = emptySet(), customGenreIcons = emptyMap(), onDismiss = {}, onApply = { _, _ -> },
                onAddCustomGenre = { _, _ -> },
            )
        }
    }.let { }

    /** The two hosts of the player's secondary views: a `Dialog` over the sheet below it, with the glass behind the page, as they put it. */
    @Composable
    private fun SecondaryHost(content: @Composable () -> Unit) {
        Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            GlassWindowBackdrop(LocalCoverGlass.current)
            content()
        }
    }

    @Test
    fun `the track details window`() = shows("track-details") {
        SecondaryHost {
            CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                TrackDetailsSheet(
                    state = TrackDetailsUiState(song = songs[0], output = OutputDetails(route = OutputRoute.SPEAKER)),
                    currentPositionProvider = { 22_000L }, totalDurationMs = 245_000L, onSeek = {}, onBackClick = {},
                )
            }
        }
    }.let { }

    @Test
    fun `the credits window`() = shows("credits") {
        SecondaryHost {
            CreditsSheet(
                state = CreditsUiState(song = songs[0], performingArtist = "Gemini Sound", albumArtist = "Gemini Sound", composer = "A. Compositor", label = "Disco"),
                onBackClick = {},
            )
        }
    }.let { }
}

/** With Light saved: the cover mode ignores it and still paints the cover with light ink (B42-fix3). */
@OptIn(ExperimentalMaterial3Api::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B42SheetMoreRenderLightTest : B42SheetMoreRenderTest() {
    override val theme: DapBaseTheme = DapBaseTheme.Light
}
