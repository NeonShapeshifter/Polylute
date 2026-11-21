package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.collections.auto.*
import com.lostf1sh.pixelplayeross.data.model.*
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.presentation.components.DailyMixSection
import com.lostf1sh.pixelplayeross.presentation.navigation.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.*
import com.lostf1sh.pixelplayeross.ui.ds.*
import io.mockk.*
import java.io.File
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B64SongListsRenderTest {
    @get:Rule val compose = createComposeRule()
    private val songs = (1L..3L).map { Kit.song(it, title = "Track $it", artist = "Synthetic artist", album = "Synthetic album", albumId = 9) }
    private val catalog = Kit.catalog(songs)
    private val collection = AutoCollection(AutoCollectionId.FORGOTTEN_FAVORITES, songs.map { catalog.item(it.libraryItemId)!! })
    private val playerState = MutableStateFlow(StablePlayerState(currentSong = songs[1], isPlaying = true))
    private val player = mockk<PlayerViewModel>(relaxed = true).also { p ->
        every { p.stablePlayerState } returns playerState
        every { p.favoriteSongIds } returns MutableStateFlow(emptySet())
        every { p.selectedSongForInfo } returns MutableStateFlow(null)
        every { p.navBarCompactMode } returns MutableStateFlow(false)
    }
    private var kind by mutableStateOf("collection")
    private var dark by mutableStateOf(true)
    private val taps = mutableListOf<String>()
    @Test fun playingAndPausedInEveryListKeepTheirTapsAndOverflow() {
        val out = File(System.getenv("B64_RENDERS") ?: "build/tmp/B64-renders").apply { mkdirs() }
        compose.setContent {
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher()), LocalDapAccent provides Color(0xffb0b0b0)) {
                    val playback = rememberLibraryPlaybackStates(player)
                    Box(Modifier.fillMaxSize().background(Dap.background)) {
                        key(kind) {
                            when(kind) {
                                "collection" -> CollectionPage(AutoCollectionId.FORGOTTEN_FAVORITES, collection, false, "2",
                                    onHide = {}, onShow = {}, onPlay = {}, onShuffle = {}, onPlaySong = { taps += "song:${it.id}" }, onMore = { taps += "more:${it.id}" },
                                    playback = playback, // REVIEWER_EXISTING
                                )
                                "genre" -> GenrePage(title = "Rock", state = GenreDetailUiState(genre = Genre("rock", "Rock", songCount = 3), songs = songs),
                                    currentSongId = "2", isUnknownGenre = false, onBack = {}, onRetry = {}, onPlay = {}, onShuffle = {}, onQuickFill = {},
                                    onPlaySong = { taps += "song:${it.id}" }, onMoreSong = { taps += "more:${it.id}" }, startOnAllSongs = true,
                                    playback = playback, // AFTER_ONLY
                                )
                                "composer" -> ComposerPage(name = "Synthetic composer", songs = songs, currentSongId = "2", onBack = {}, onPlay = {}, onShuffle = {}, onPlaySong = { taps += "song:${it.id}" },
                                    playback = playback, // AFTER_ONLY
                                )
                                "daily" -> DailyMixSection(songs.toImmutableList(), player)
                            }
                        }
                    }
                }
            }
        }
        for (list in listOf("collection", "genre", "composer", "daily")) {
            for (isDark in listOf(true, false)) for (playing in listOf(true, false)) {
                compose.runOnIdle { kind = list; dark = isDark; playerState.value = playerState.value.copy(isPlaying = playing) }
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithText("Track 2").assertExists()
                val image = compose.onRoot().captureToImage().asAndroidBitmap()
                File(out, "$list-${if(isDark) "dark" else "light"}-${if(playing) "playing" else "paused"}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            taps.clear()
            compose.onNodeWithText("Track 2").performClick()
            if(list == "daily") {
                verify { player.showAndPlaySong(song = songs[1], contextSongs = songs, queueName = any(), updatesLastPlaylistShortcut = false, cancelPendingQueueBuild = true, playlistId = null, indexInQueue = null, source = any()) }
            } else assertThat(taps).containsExactly("song:2")
            if(list == "collection" || list == "genre") {
                compose.onAllNodes(hasContentDescription("more options", substring = true, ignoreCase = true))[1].performClick()
                assertThat(taps).contains("more:2")
            }
        }
    }
}
