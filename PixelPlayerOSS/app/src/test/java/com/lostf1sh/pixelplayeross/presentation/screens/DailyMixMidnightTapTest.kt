package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.presentation.components.DailyMixSection
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner 11-22, just after midnight: Home's Daily Mix had refreshed, its first row showed "Te Gustan Villeros", and a tap played
 * "Ella", yesterday's first row. The rows are reused in place when the mix changes, and the row's tap gesture kept the click
 * handler of the song it was first composed with. A tap must play the song the row shows, in the mix the box shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h805dp-xhdpi")
class DailyMixMidnightTapTest {
    @get:Rule val compose = createComposeRule()

    private val yesterday = (1L..4L).map { Kit.song(it, title = "Yesterday $it", artist = "Synthetic artist", album = "Synthetic album", albumId = 9) }
    private val today = (11L..14L).map { Kit.song(it, title = "Today $it", artist = "Synthetic artist", album = "Synthetic album", albumId = 9) }
    private val player = mockk<PlayerViewModel>(relaxed = true).also { p ->
        every { p.stablePlayerState } returns MutableStateFlow(StablePlayerState(currentSong = yesterday[1], isPlaying = true))
        every { p.favoriteSongIds } returns MutableStateFlow(emptySet())
        every { p.selectedSongForInfo } returns MutableStateFlow(null)
        every { p.navBarCompactMode } returns MutableStateFlow(false)
    }
    private var mix by mutableStateOf(yesterday)

    @Test fun after_the_mix_changes_a_tap_plays_the_song_the_row_shows() {
        compose.setContent {
            DapTheme(darkTheme = true, baseTheme = DapBaseTheme.Graphite) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher()), LocalDapAccent provides Color(0xffb0b0b0)) {
                    Box(Modifier.fillMaxSize().background(Dap.background)) {
                        DailyMixSection(mix.toImmutableList(), player)
                    }
                }
            }
        }
        compose.onNodeWithText("Yesterday 1").assertExists()
        compose.runOnIdle { mix = today }
        compose.waitForIdle()

        compose.onNodeWithText("Today 11").performClick()

        verify(exactly = 1) {
            player.showAndPlaySong(song = today[0], contextSongs = today, queueName = any(), updatesLastPlaylistShortcut = false,
                cancelPendingQueueBuild = true, playlistId = null, indexInQueue = null, source = any())
        }
        verify(exactly = 0) {
            player.showAndPlaySong(song = match<Song> { it.id == yesterday[0].id }, contextSongs = any(), queueName = any(), updatesLastPlaylistShortcut = any(),
                cancelPendingQueueBuild = any(), playlistId = any(), indexInQueue = any(), source = any())
        }
    }
}
