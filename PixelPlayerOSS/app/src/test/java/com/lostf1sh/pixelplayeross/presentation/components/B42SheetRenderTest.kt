package com.lostf1sh.pixelplayeross.presentation.components

import android.graphics.Bitmap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.database.AudioBookmarkEntity
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerArtistPickerBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.LyricsOptionsSheet
import com.lostf1sh.pixelplayeross.presentation.screens.BookmarkMomentActionsBottomSheet
import com.lostf1sh.pixelplayeross.presentation.screens.LibraryTabSwitcherSheet
import com.lostf1sh.pixelplayeross.presentation.screens.PlaylistOptionsSheet
import com.lostf1sh.pixelplayeross.presentation.screens.ThemeSelectorItem
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42GlassApp
import com.lostf1sh.pixelplayeross.ui.glass.B42HiltShim
import com.lostf1sh.pixelplayeross.ui.glass.B42Ink
import com.lostf1sh.pixelplayeross.ui.glass.B42Pages
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42Setup
import com.lostf1sh.pixelplayeross.ui.glass.rememberB42Scene
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42: every bottom sheet of the app, the real one, in its own window over the real glass, drawn for the owner and measured.
 *
 * Each test opens one sheet over a Home page in "Cover glass" (the saturated cover, with Graphite saved here and Light saved in the subclass (ignored in this cover mode)), saves
 * `sheet-<name>-<theme>.png` to `B42-evidence/`, and measures every text in the sheet's window against the pixels under it: a text whose
 * contrast on the lightest or darkest ground around it is under AA (4.5, or 3 for large text) fails the test and is named.
 */
@OptIn(ExperimentalMaterial3Api::class)
open class B42SheetRenderTest : B42SheetHarness() {

    private val songs = (1..6).map { n ->
        com.lostf1sh.pixelplayeross.data.model.Song(
            id = "$n", title = listOf("Cine", "Ventana roja", "Luces de la ciudad", "Madrugada", "Calle vacía", "Noche")[n - 1], artist = "Gemini Sound",
            artistId = 7L, album = "Noche", albumId = 9L, path = "/m/$n.flac", contentUriString = "content://$n", albumArtUriString = null,
            duration = 200_000L + n * 1000L, mimeType = "audio/flac", bitrate = 1000, sampleRate = 44100,
        )
    }

    private val albums = listOf(
        Album(1, "Noche", "Gemini Sound", 2021, 0L, null, 9),
        Album(2, "Calle", "Amaia", 2019, 0L, null, 12),
        Album(3, "Rojo", "Nao", 2023, 0L, null, 7),
    )

    // ── the sheets with nothing to look up ──────────────────────────────────────────────────────────

    @Test
    fun `lyrics options`() = shows("lyrics-options") {
        LyricsOptionsSheet(
            onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            syncOffsetMs = 0, onSyncOffsetChange = {}, lyricsAlignment = "center", onLyricsAlignmentChange = {}, hasTranslatedLyrics = true,
            showTranslation = false, onShowTranslationChange = {}, hasRomanizedLyrics = false, showRomanization = false, onShowRomanizationChange = {},
            keepScreenOn = false, onKeepScreenOnChange = {}, canSearch = true, onSearchLyrics = {}, onImportLyrics = {}, canSave = true,
            onSaveLyrics = {}, canReset = true, onResetLyrics = {},
        )
    }.let { }

    @Test
    fun `sleep timer`() = shows("timer") {
        TimerOptionsBottomSheet(
            onPlayCounter = {}, activeTimerValueDisplay = "12:30", activeTimerDurationMinutes = 15, playCount = 0f, isEndOfTrackTimerActive = false,
            onDismiss = {}, onCancelCountedPlay = {}, onSetPredefinedTimer = {}, onSetEndOfTrackTimer = {}, onOpenCustomTimePicker = {}, onCancelTimer = {},
        )
    }.let { }

    @Test
    fun `library sort`() = shows("library-sort") {
        LibrarySortBottomSheet(
            title = "Sort songs", options = persistentListOf(SortOption.SongTitleAZ, SortOption.SongTitleZA, SortOption.SongArtist, SortOption.SongAlbum),
            selectedOption = SortOption.SongTitleAZ, onDismiss = {}, onOptionSelected = {},
        )
    }.let { }

    @Test
    fun `custom equalizer presets`() = shows("equalizer-presets") {
        CustomPresetsSheet(
            presets = persistentListOf(
                EqualizerPreset("mine", "Mine", listOf(2, 3, 1, 0, -1, 0, 1, 2, 3, 2), isCustom = true),
                EqualizerPreset("night", "Night", listOf(4, 3, 1, 0, 0, 0, 0, 1, 2, 2), isCustom = true),
            ),
            pinnedPresetsNames = persistentListOf("mine"), onPresetSelected = {}, onPinToggled = {}, onRename = {}, onDelete = {}, onDismiss = {},
        )
    }.let { }

    @Test
    fun `library tabs order`() = shows("reorder-tabs") {
        ReorderTabsSheet(
            orderedTabs = persistentListOf("SONGS", "ALBUMS", "ARTISTS", "PLAYLISTS", "FOLDERS"), hiddenTabs = setOf("FOLDERS"),
            onSave = { _, _ -> }, onReset = {}, onDismiss = {},
        )
    }.let { }

    @Test
    fun `songs selected`() = shows("songs-selected") {
        MultiSelectionBottomSheet(
            selectedSongs = songs.take(3).toImmutableList(), favoriteSongIds = setOf("1"), onDismiss = {}, onPlayAll = {}, onAddToQueue = {},
            onPlayNext = {}, onAddToPlaylist = {}, onToggleLikeAll = {}, onShareAll = {}, onDeleteAll = { _, _ -> }, onBatchEdit = {},
        )
    }.let { }

    @Test
    fun `playlists selected`() = shows("playlists-selected") {
        PlaylistMultiSelectionBottomSheet(
            selectedPlaylists = persistentListOf(Playlist("1", "Noche", listOf("1", "2")), Playlist("2", "Calle", listOf("3"))),
            onDismiss = {}, onDeleteAll = {}, onExportAll = {}, onMergeAll = {}, onShareAll = {},
        )
    }.let { }

    @Test
    fun `albums selected`() = shows("albums-selected") {
        AlbumMultiSelectionOptionSheet(
            selectedAlbums = albums.toImmutableList(), maxSelection = 10, onDismiss = {}, onPlay = {}, onPlayNext = {}, onAddToQueue = {},
        )
    }.let { }

    @Test
    fun `artist picker`() = shows("artist-picker") {
        PlayerArtistPickerBottomSheet(
            song = songs[0], artists = persistentListOf(Artist(1, "Gemini Sound", 12), Artist(2, "Amaia", 8)),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismiss = {}, onArtistClick = {},
        )
    }.let { }

    @Test
    fun `library tabs switcher`() = shows("library-tabs") {
        LibraryTabSwitcherSheet(
            tabs = persistentListOf("Songs", "Albums", "Artists", "Playlists"), currentIndex = 1, onTabSelected = {}, onEditClick = {}, onDismiss = {},
        )
    }.let { }

    @Test
    fun `bookmark actions`() = shows("bookmark-actions") {
        BookmarkMomentActionsBottomSheet(
            bookmark = AudioBookmarkEntity(
                id = 1, songId = "1", songTitle = "Cine", artistName = "Gemini Sound", albumArtUri = null, title = "The chorus", timestampMs = 83_000L,
            ),
            onDismiss = {}, onRename = {}, onCopyCue = {}, onDelete = {},
        )
    }.let { }

    @Test
    fun `playlist options`() = shows("playlist-options") {
        PlaylistOptionsSheet(
            title = "Playlist options", playlistName = "Noche", editLabel = "Edit playlist", deleteLabel = "Delete playlist",
            transitionLabel = "Default transition", exportLabel = "Export as M3U", onDismiss = {}, onEdit = {}, onDelete = {}, onTransition = {}, onExport = {},
        )
    }.let { }

    @Test
    fun `an option picker in settings`() = shows(
        "settings-option-picker",
        open = { compose.onNodeWithText("Alignment").performClick() },
    ) {
        ThemeSelectorItem(
            label = "Alignment", description = "Where the lyrics sit", options = mapOf("left" to "Left", "center" to "Centre", "right" to "Right"),
            selectedKey = "center", onSelectionChanged = {}, leadingIcon = {},
        )
    }.let { }

}

/** With Light saved: the cover mode ignores it and still paints the cover with light ink (B42-fix3). */
@OptIn(ExperimentalMaterial3Api::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B42SheetRenderLightTest : B42SheetRenderTest() {
    override val theme: DapBaseTheme = DapBaseTheme.Light
}
