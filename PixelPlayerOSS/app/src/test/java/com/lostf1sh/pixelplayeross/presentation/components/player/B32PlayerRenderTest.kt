package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.B32Renders
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

/**
 * B32-Q4: the full player at the HONOR's size (720 x 1610 px at 2x), in en / es / sv. The same source runs against the tree as it was at
 * the start of the batch (`B32_PREFIX=before`) and as it is now (`after`): it uses only what both have, so the pair differ in the page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
open class B32PlayerRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val language get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources.configuration.locales[0].language

    private fun song(n: Int, title: String) = Song(
        id = "$n", title = title, artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$n.flac",
        contentUriString = "content://$n", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    @Test
    fun `the full player with a song after this one`() {
        val queue = persistentListOf(song(1, "La Costa Del Silencio"), song(2, "Fiesta Pagana"), song(3, "Molinos de Viento"))
        val current = queue[0]
        val state = buildFullPlayerUiState(
            env = PlayerSheetEnv(
                loadingTweaks = FullPlayerLoadingTweaks(delayAll = false), isSheetDragGestureActive = false,
                currentSheetState = PlayerSheetState.EXPANDED, allowRealtimeUpdates = false, expansionFractionProvider = { 1f },
            ),
            song = current, queue = queue, queueSourceName = "All Songs", mediaItemIndex = 0,
            carouselStyle = CarouselStyle.NO_PEEK, artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
            audioMetadata = PlaybackAudioMetadata(mediaId = current.id, mimeType = "audio/flac", sampleRate = 44100),
            showFileInfo = true, isFavorite = false, isBuffering = false, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF,
            isPlayingProvider = { true }, playWhenReadyProvider = { true },
            currentPositionProvider = { 100_000L }, totalDurationProvider = { 280_000L },
            lyrics = PlayerLyricsState(
                searchState = LyricsSearchUiState.Idle,
                stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = current, isPlaying = true, totalDuration = 280_000L, lyrics = null, lyricsResolveCompleted = true)),
                playbackPositionFlow = MutableStateFlow(9_000L),
                externalLyricsEnabled = true, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
                isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF, isFavorite = false,
                lyricsProvider = { null }, lyricsResolveCompletedProvider = { true },
            ),
            isLandscape = false,
        )
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Dap.background)) {
                CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) { FullPlayerScreen(state, NoActions()) }
            }
        }
        compose.waitForIdle()
        B32Renders.save(compose, "q4-player-$language")
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
class B32PlayerRenderSpanishTest : B32PlayerRenderTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xhdpi")
class B32PlayerRenderSwedishTest : B32PlayerRenderTest()
