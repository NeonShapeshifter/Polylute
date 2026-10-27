package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.material3.ExperimentalMaterial3Api
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SongInfoBottomSheetViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42: the song's options sheet ([SongInfoBottomSheet], the "more" menu), the real one in its own window, in "Cover glass": four covers (very
 * light, very dark, grey, saturated), over the full player and over Home, with Graphite saved here and Light saved in the subclass (ignored in this cover mode).
 * `B42-evidence/options-<cover>-<theme>-<player|home>.png`; every text in it measured against the pixels under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
open class B42OptionsSheetRenderTest : B42SheetHarness() {

    private val song = Song(
        id = "1", title = "Cine", artist = "Gemini Sound", artistId = 7L, album = "Noche", albumId = 9L, path = "/storage/emulated/0/Music/Noche/Cine.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 245_000L, mimeType = "audio/flac", bitrate = 1000, sampleRate = 44100,
    )

    private val viewModel = mockk<SongInfoBottomSheetViewModel>(relaxed = true).also {
        every { it.audioMeta } returns MutableStateFlow(null)
        every { it.resolvedArtists } returns MutableStateFlow(persistentListOf())
        every { it.musicBrainzState } returns MutableStateFlow(SongInfoBottomSheetViewModel.MusicBrainzUiState.Idle)
        every { it.getSongLocationInfo(any()) } returns SongInfoBottomSheetViewModel.SongLocationInfo("Folder", "Music/Noche")
        every { it.isSongEditable(any()) } returns true
    }

    private fun options(cover: B41Renders.Cover, behind: Behind) = shows(
        "options", behind = behind, cover = cover, file = "options-${cover.name}-${theme.name.lowercase()}-${behind.name.lowercase()}",
    ) {
        SongInfoBottomSheet(
            song = song, isFavorite = false, onToggleFavorite = {}, onDismiss = {}, onPlaySong = {}, onAddToQueue = {}, onAddNextToQueue = {},
            onAddToPlayList = {}, onDeleteFromDevice = { _, _, _ -> }, onNavigateToAlbum = {}, onNavigateToArtist = {}, onNavigateToGenre = {},
            onViewTrackDetails = {}, onViewCredits = {},
            onEditSong = { _, _, _, _, _, _, _, _, _, _, _, _ -> },
            removeFromListTrigger = {}, songInfoViewModel = viewModel,
        )
    }.let { }

    @Test fun `a very light cover over the player`() = options(B41Renders.veryLight(), Behind.PLAYER)
    @Test fun `a very light cover over Home`() = options(B41Renders.veryLight(), Behind.HOME)
    @Test fun `a very dark cover over the player`() = options(B41Renders.veryDark(), Behind.PLAYER)
    @Test fun `a very dark cover over Home`() = options(B41Renders.veryDark(), Behind.HOME)
    @Test fun `a grey cover over the player`() = options(B41Renders.grey(), Behind.PLAYER)
    @Test fun `a grey cover over Home`() = options(B41Renders.grey(), Behind.HOME)
    @Test fun `a saturated cover over the player`() = options(B41Renders.saturated(), Behind.PLAYER)
    @Test fun `a saturated cover over Home`() = options(B41Renders.saturated(), Behind.HOME)
}

/** With Light saved: the cover mode ignores it and still paints the cover with light ink (B42-fix3). */
@OptIn(ExperimentalMaterial3Api::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B42OptionsSheetRenderLightTest : B42OptionsSheetRenderTest() {
    override val theme: DapBaseTheme = DapBaseTheme.Light
}
