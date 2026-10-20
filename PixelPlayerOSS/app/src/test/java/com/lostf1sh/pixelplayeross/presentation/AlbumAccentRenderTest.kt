package com.lostf1sh.pixelplayeross.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.components.player.FullPlayerScreen
import com.lostf1sh.pixelplayeross.presentation.components.player.NoActions
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerLyricsState
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerSheetEnv
import com.lostf1sh.pixelplayeross.presentation.components.player.buildFullPlayerUiState
import com.lostf1sh.pixelplayeross.presentation.lockscreen.DapLockScreen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.AlbumAccent
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * B29-L11: the full player and the lock screen wear the accent they are given, which is the cover's, and the app's own when there is none.
 *
 * What is drawn in the accent on these two surfaces is the same set of marks as before (the shuffle and repeat dots, the heart, the play
 * state of a row); only the colour is the cover's. The renders are Robolectric images over made-up songs, not phone screenshots; each is also
 * read for the pixels of the accent it was given.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class AlbumAccentRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val coverAccent = Color(0xFFD9A441) // the amber of a made-up cover
    private val appAccent = DapAccent.Sage.color

    private val song = Song(
        id = "1", title = "La Costa Del Silencio", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private fun playerState() = buildFullPlayerUiState(
        env = PlayerSheetEnv(
            loadingTweaks = FullPlayerLoadingTweaks(delayAll = false), isSheetDragGestureActive = false,
            currentSheetState = PlayerSheetState.EXPANDED, allowRealtimeUpdates = false, expansionFractionProvider = { 1f },
        ),
        song = song, queue = persistentListOf(song), queueSourceName = "All Songs", mediaItemIndex = 0,
        carouselStyle = CarouselStyle.NO_PEEK, artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
        audioMetadata = PlaybackAudioMetadata(mediaId = song.id, mimeType = "audio/flac", sampleRate = 44100),
        showFileInfo = true, isFavorite = true, isBuffering = false, isShuffleEnabled = true, repeatMode = Player.REPEAT_MODE_ALL,
        isPlayingProvider = { true }, playWhenReadyProvider = { true },
        currentPositionProvider = { 100_000L }, totalDurationProvider = { 280_000L },
        lyrics = PlayerLyricsState(
            searchState = LyricsSearchUiState.Idle,
            stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = song, isPlaying = true, totalDuration = 280_000L, lyrics = null, lyricsResolveCompleted = true)),
            playbackPositionFlow = MutableStateFlow(9_000L),
            externalLyricsEnabled = true, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
            isImmersiveTemporarilyDisabled = false, isShuffleEnabled = true, repeatMode = Player.REPEAT_MODE_ALL, isFavorite = true,
            lyricsProvider = { null }, lyricsResolveCompletedProvider = { true },
        ),
        isLandscape = false,
    )

    private fun showPlayer(accent: Color?) = compose.setContent {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            CompositionLocalProvider(LocalDapAccent provides (accent ?: LocalDapAccent.current)) {
                Box(Modifier.fillMaxSize().background(Dap.background)) {
                    CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) { FullPlayerScreen(playerState(), NoActions()) }
                }
            }
        }
    }

    private fun showLock(accent: Color?) = compose.setContent {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            CompositionLocalProvider(LocalDapAccent provides (accent ?: LocalDapAccent.current)) {
                DapLockScreen(
                    song = song, isPlaying = true, isShuffleEnabled = true, repeatMode = Player.REPEAT_MODE_ALL,
                    currentPositionProvider = { 100_000L }, totalDuration = 280_000L, outputLine = "SNOWSKY Melody · USB Direct · 16 bits · 44,1 kHz",
                    batteryPercent = 61, onPrevious = {}, onPlayPause = {}, onNext = {}, onToggleShuffle = {}, onToggleRepeat = {}, onDismiss = {},
                )
            }
        }
    }

    /** How many pixels of the capture are within a small distance of [color]: the marks drawn in it (the dots, the heart). */
    private fun pixelsOf(color: Color): Int {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val want = intArrayOf((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            val d = abs(((p shr 16) and 0xFF) - want[0]) + abs(((p shr 8) and 0xFF) - want[1]) + abs((p and 0xFF) - want[2])
            if (d <= 24) count++
        }
        return count
    }

    @Test
    fun `the full player wears the cover's accent on its state marks, and the app's when it is given none`() {
        showPlayer(coverAccent)
        compose.waitForIdle()
        B29Renders.save(compose, "L11-player-album-accent")
        val withCover = pixelsOf(coverAccent)
        assertThat(pixelsOf(appAccent)).isEqualTo(0)
        assertThat(withCover).isGreaterThan(40)
    }

    @Test
    fun `the full player keeps the app's accent when there is no cover accent`() {
        showPlayer(null)
        compose.waitForIdle()
        B29Renders.save(compose, "L11-player-app-accent")
        assertThat(pixelsOf(appAccent)).isGreaterThan(40)
        assertThat(pixelsOf(coverAccent)).isEqualTo(0)
    }

    @Test
    fun `the lock screen wears the cover's accent, and the app's when it is given none`() {
        showLock(coverAccent)
        compose.waitForIdle()
        B29Renders.save(compose, "L11-lock-album-accent")
        assertThat(pixelsOf(coverAccent)).isGreaterThan(40)
        assertThat(pixelsOf(appAccent)).isEqualTo(0)
    }

    @Test
    fun `the lock screen keeps the app's accent when there is no cover accent`() {
        showLock(null)
        compose.waitForIdle()
        B29Renders.save(compose, "L11-lock-app-accent")
        assertThat(pixelsOf(appAccent)).isGreaterThan(40)
        assertThat(pixelsOf(coverAccent)).isEqualTo(0)
    }

    @Test
    fun `the accent a cover gives is readable on graphite, and what cannot be read is not used`() {
        val graphite = Color(0xFF0E0F12)
        assertThat(AlbumAccent.contrast(coverAccent, graphite)).isAtLeast(AlbumAccent.MIN_CONTRAST)
    }
}
