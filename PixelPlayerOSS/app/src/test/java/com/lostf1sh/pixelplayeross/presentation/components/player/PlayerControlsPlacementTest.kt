package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
open class PlayerControlsPlacementTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)

    private fun song() = Song(
        id = "1", title = "La Costa Del Silencio", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )
    private val env = PlayerSheetEnv(
        loadingTweaks = FullPlayerLoadingTweaks(delayAll = false), isSheetDragGestureActive = false,
        currentSheetState = PlayerSheetState.EXPANDED, allowRealtimeUpdates = false, expansionFractionProvider = { 1f },
    )

    @Test
    fun `the controls sit high with the spare height below them, in the design's order`() {
        val song = song()
        val state = buildFullPlayerUiState(
            env = env, song = song, queue = persistentListOf(song), queueSourceName = "All Songs", mediaItemIndex = 0,
            carouselStyle = CarouselStyle.NO_PEEK, artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
            audioMetadata = PlaybackAudioMetadata(mediaId = song.id, mimeType = "audio/flac", sampleRate = 44100),
            showFileInfo = true, isFavorite = true, isBuffering = false, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_ALL,
            isPlayingProvider = { true }, playWhenReadyProvider = { true },
            currentPositionProvider = { 196_000L }, totalDurationProvider = { 280_000L },
            lyrics = PlayerLyricsState(
                searchState = LyricsSearchUiState.Idle,
                stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = song, isPlaying = true, totalDuration = 280_000L, lyrics = null, lyricsResolveCompleted = true)),
                playbackPositionFlow = MutableStateFlow(9_000L),
                externalLyricsEnabled = true, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
                isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_ALL, isFavorite = true,
                lyricsProvider = { null }, lyricsResolveCompletedProvider = { true },
            ),
            isLandscape = false,
        )
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette provides com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Graphite) {
                Box(Modifier.fillMaxSize().background(Dap.background)) {
                    CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) { FullPlayerScreen(state, NoActions()) }
                }
            }
        }
        compose.waitForIdle()
        val density = context.resources.displayMetrics.density
        fun top(n: androidx.compose.ui.semantics.SemanticsNode) = n.boundsInRoot.top / density
        fun bottom(n: androidx.compose.ui.semantics.SemanticsNode) = n.boundsInRoot.bottom / density
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot.let { it.height / density to it.width / density }
        val header = compose.onAllNodesWithContentDescription(s(R.string.player_header_collapse))[0].fetchSemanticsNode()
        val title = compose.onAllNodesWithText("La Costa Del Silencio")[0].fetchSemanticsNode()
        val bar = compose.onAllNodesWithText("FLAC · 44.1 kHz")[0].fetchSemanticsNode()
        val shuffle = compose.onAllNodesWithContentDescription(s(R.string.player_shuffle))[0].fetchSemanticsNode()
        val pause = compose.onAllNodesWithContentDescription(s(R.string.cd_pause))[0].fetchSemanticsNode()
        val repeat = compose.onAllNodesWithContentDescription(s(R.string.lock_transport_repeat))[0].fetchSemanticsNode()
        val controlsTop = minOf(top(shuffle), top(pause), top(repeat))
        val controlsBottom = maxOf(bottom(shuffle), bottom(pause), bottom(repeat))
        val (screenHeight, screenWidth) = screen

        // The order is the design's: header, cover, title, bar, controls. The controls row is below the seek bar's block.
        assertThat(top(title)).isGreaterThan(bottom(header))
        assertThat(bottom(title)).isLessThan(top(bar))
        assertThat(controlsTop).isGreaterThan(bottom(bar))

        // The spare height is under the controls: at least 100 dp of it (it was about the 24 dp spacer).
        val spaceBelow = screenHeight - controlsBottom
        assertThat(spaceBelow).isAtLeast(100f)

        // ...and not between the header and the cover: the title starts within one cover (the width less its two 24 dp margins)
        // plus its margins of the header, i.e. nothing floats above the block.
        val coverSide = screenWidth - 48f
        assertThat(top(title) - bottom(header)).isAtMost(coverSide + 48f)
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class PlayerControlsPlacement412Test : PlayerControlsPlacementTest()
