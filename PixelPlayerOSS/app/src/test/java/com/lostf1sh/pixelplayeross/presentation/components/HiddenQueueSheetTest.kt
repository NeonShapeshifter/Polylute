package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B23-P3: the queue sheet is retained composed while it is closed, and with the history off its rows are the suffix that starts at the
 * current song. Every song change used to re-reconcile its keys and re-base its hidden list (O(queue), a map over thousands of ids
 * on a backward jump). Closed, it now knows nothing of the current song; open, it shows the right one at once.
 *
 * The real `QueueBottomSheet`, with the holder `QueueSurface` uses in front of it, over a 300-song queue (the view models are
 * stubs: the sheet reads two flows from them). Its rows are what is observable: a row that is composed is a row that was laid out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class HiddenQueueSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(i: Int) = Song(
        id = "id-$i", title = "Cancion $i", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$i.flac",
        contentUriString = "content://$i", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val queue: ImmutableList<Song> = (0 until 300).map(::song).toImmutableList()

    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.playerUiState } returns MutableStateFlow(PlayerUiState())
    }
    private val settingsViewModel = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.uiState } returns MutableStateFlow(SettingsUiState(showQueueHistory = false))
    }
    private val playlistViewModel = mockk<PlaylistViewModel>(relaxed = true)

    // state the sheet reads, created once here and not in the composition
    private val timerDisplay = mutableStateOf<String?>(null)
    private val timerMinutes = mutableStateOf<Int?>(null)
    private val playCountState = mutableStateOf(0f)
    private val endOfTrackTimer = mutableStateOf(false)

    private var current by mutableIntStateOf(0)
    private var open by mutableStateOf(false)
    private var changes = 0

    /** [hold]: the holder `QueueSurface` puts in front of the sheet; without it the sheet is given the live song, as in B22. */
    private fun setSheet(hold: Boolean) {
        compose.setContent {
            val live = QueueSheetSong(queue[current].id, current, isPlaying = true, repeatMode = Player.REPEAT_MODE_OFF, isShuffleOn = false)
            val given = if (hold) {
                rememberQueueSheetSong(open, live.currentSongId, live.currentMediaItemIndex, live.isPlaying, live.repeatMode, live.isShuffleOn)
            } else {
                live
            }
            Box(Modifier.fillMaxSize()) {
                QueueBottomSheet(
                    viewModel = playerViewModel,
                    playlistViewModel = playlistViewModel,
                    settingsViewModel = settingsViewModel,
                    queue = queue,
                    currentQueueSourceName = "Biblioteca",
                    currentSongId = given.currentSongId,
                    currentMediaItemIndex = given.currentMediaItemIndex,
                    isVisible = open,
                    isPlaying = given.isPlaying,
                    repeatMode = given.repeatMode,
                    isShuffleOn = given.isShuffleOn,
                    onDismiss = {},
                    onSongInfoClick = { _, _ -> },
                    onPlaySong = { _, _ -> },
                    onRemoveSong = { _, _ -> },
                    onReorder = { _, _ -> changes++ },
                    onToggleRepeat = {},
                    onToggleShuffle = {},
                    onClearQueue = {},
                    activeTimerValueDisplay = timerDisplay,
                    activeTimerDurationMinutes = timerMinutes,
                    playCount = playCountState,
                    isEndOfTrackTimerActive = endOfTrackTimer,
                    onSetPredefinedTimer = {},
                    onSetEndOfTrackTimer = {},
                    onOpenCustomTimePicker = {},
                    onCancelTimer = {},
                    onCancelCountedPlay = {},
                    onPlayCounter = {},
                    onRequestSaveAsPlaylist = { _, _, _ -> },
                    onQueueDragStart = {},
                    onQueueDrag = {},
                    onQueueRelease = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun rowExists(i: Int) = compose.onAllNodesWithText("Cancion $i").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `without the holder, a song change re-bases the hidden list (the behaviour being removed)`() {
        setSheet(hold = false)
        assertThat(rowExists(0)).isTrue()
        // Five taps on distant rows with the sheet closed, then a backward jump.
        for (n in listOf(120, 40, 210, 90, 10)) { current = n; compose.waitForIdle() }
        assertThat(rowExists(10)).isTrue()      // the hidden list already starts at the new song: it was reconciled and re-based
        assertThat(rowExists(0)).isFalse()
    }

    @Test
    fun `closed, a song change reconciles nothing, scrolls nothing and re-bases nothing`() {
        setSheet(hold = true)
        assertThat(rowExists(0)).isTrue()
        for (n in listOf(120, 40, 210, 90, 10)) { current = n; compose.waitForIdle() }
        // the closed sheet still shows what it showed: not one row of the songs it was not asked about
        assertThat(rowExists(0)).isTrue()
        for (n in listOf(10, 40, 90, 120, 210)) assertThat(rowExists(n)).isFalse()
    }

    @Test
    fun `opened afterwards, it starts at the current song`() {
        setSheet(hold = true)
        for (n in listOf(120, 40, 210, 90, 150)) { current = n; compose.waitForIdle() }
        open = true
        compose.waitForIdle()
        assertThat(rowExists(150)).isTrue()
        assertThat(rowExists(0)).isFalse()
        // and keeps following the song while it is open
        current = 151
        compose.waitForIdle()
        assertThat(rowExists(151)).isTrue()
        assertThat(rowExists(150)).isFalse()
    }
}
