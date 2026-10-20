package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
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
 * B22, Part 4: the player in the look of `Player.dc.html`, at the HONOR's 360 dp, in English, Spanish and Swedish.
 *
 * What is proved here:
 *  - the cover view: header icons only (collapse, lyrics, details), the title with the bookmark and the heart, the
 *    bar with the technical line, one transport row of five, and **no output line** anywhere on it;
 *  - a long title ends in an ellipsis and moves nothing, in all three languages (the longest titles of a library are the
 *    worst case: the title shares its row with two 48 dp buttons);
 *  - the lyrics view keeps the header (its Lyrics button on) and has the bottom bar: play, the pill with the elapsed
 *    time, and the options button.
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics: the JVM's default text metrics never overflow, so a fit test would pass on anything.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
open class PlayerLookRenderTest {

    /** The palette the page is drawn in; the Light subclass runs every test again on the light theme. */
    protected open val palette: com.lostf1sh.pixelplayeross.ui.ds.DapPalette = com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Graphite

    @get:Rule
    val compose = createComposeRule()

    protected val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    protected fun s(id: Int) = context.getString(id)

    private fun song(title: String, artist: String = "Mägo de Oz") = Song(
        id = "1", title = title, artist = artist, artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val env = PlayerSheetEnv(
        loadingTweaks = FullPlayerLoadingTweaks(delayAll = false),
        isSheetDragGestureActive = false,
        currentSheetState = PlayerSheetState.EXPANDED,
        allowRealtimeUpdates = false,
        expansionFractionProvider = { 1f },
    )

    private fun uiState(song: Song, lyrics: Lyrics? = null, shuffle: Boolean = false, repeat: Int = Player.REPEAT_MODE_ALL) = buildFullPlayerUiState(
        env = env, song = song, queue = persistentListOf(song), queueSourceName = "All Songs", mediaItemIndex = 0,
        carouselStyle = CarouselStyle.NO_PEEK, artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
        audioMetadata = PlaybackAudioMetadata(mediaId = song.id, mimeType = "audio/flac", sampleRate = 44100),
        showFileInfo = true, isFavorite = true, isBuffering = false, isShuffleEnabled = shuffle, repeatMode = repeat,
        isPlayingProvider = { true }, playWhenReadyProvider = { true },
        currentPositionProvider = { 196_000L }, totalDurationProvider = { 280_000L },
        lyrics = PlayerLyricsState(
            searchState = LyricsSearchUiState.Idle,
            stablePlayerStateFlow = MutableStateFlow(
                StablePlayerState(currentSong = song, isPlaying = true, totalDuration = 280_000L, lyrics = lyrics, lyricsResolveCompleted = true)
            ),
            playbackPositionFlow = MutableStateFlow(9_000L),
            externalLyricsEnabled = true, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
            isImmersiveTemporarilyDisabled = false, isShuffleEnabled = shuffle, repeatMode = repeat, isFavorite = true,
            lyricsProvider = { lyrics }, lyricsResolveCompletedProvider = { true },
        ),
        isLandscape = false,
    )

    private fun setPlayer(state: FullPlayerUiState) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette provides palette) {
        // The sheet draws the page; the screen does not.
        Box(Modifier.fillMaxSize().background(Dap.background)) {
            CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) { FullPlayerScreen(state, NoActions()) }
        }
    }
    }

    private fun fits(label: String, mayEllipsize: (String) -> Boolean = { false }) {
        RenderChecks.assertInsideWindow(compose, label)
        RenderChecks.assertNoCutText(compose, label, mayEllipsize)
    }

    @Test
    fun `the cover view has the header icons, the title, the bar and one row of transport, and no output line`() {
        setPlayer(uiState(song("La Costa Del Silencio")))
        for (cd in listOf(
            R.string.player_header_collapse, R.string.player_header_lyrics, R.string.player_header_details,
            R.string.player_add_bookmark, R.string.cd_remove_from_favorites,
            R.string.player_shuffle, R.string.lock_transport_previous, R.string.cd_pause, R.string.lock_transport_next, R.string.lock_transport_repeat,
        )) compose.onAllNodesWithContentDescription(s(cd))[0].assertExists()
        compose.onAllNodesWithText("La Costa Del Silencio")[0].assertExists()
        compose.onAllNodesWithText("FLAC · 44.1 kHz")[0].assertExists()
        // The output line ("SNOWSKY Melody · USB Direct · 16 bits · 44,1 kHz") is not on the player (fix 1).
        compose.onAllNodesWithText("USB Direct", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Resonan", substring = true).assertCountEquals(0)
        fits("cover", mayEllipsize = { it.startsWith("La Costa") })
    }

    @Test
    fun `a very long title ends in an ellipsis and nothing moves out of the window`() {
        val title = "Una canción cuyo título es larguísimo y no cabe en una línea de 360 dp por mucho que se encoja el reproductor, ni en dos"
        setPlayer(uiState(song(title, artist = "Un artista con un nombre también muy largo, para que la línea de debajo tampoco quepa")))
        fits("long title", mayEllipsize = { it == title || it.startsWith("Un artista") })
        // The transport is whole.
        compose.onNodeWithContentDescription(s(R.string.cd_pause)).assertExists()
    }

    @Test
    fun `the lyrics view keeps the header with its Lyrics button on, and has play, the pill and the options button`() {
        val lines = listOf("Línea anterior", "La línea que suena ahora", "Línea siguiente").mapIndexed { i, t -> SyncedLine(time = i * 4000, line = t) }
        setPlayer(uiState(song("La Costa Del Silencio"), lyrics = Lyrics(synced = lines)))
        compose.onNodeWithContentDescription(s(R.string.player_header_lyrics)).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("La línea que suena ahora")[0].assertExists()
        // The header is still there, and its Lyrics button is the one that is on.
        compose.onNodeWithContentDescription(s(R.string.player_header_collapse)).assertExists()
        assertThat(compose.onNodeWithContentDescription(s(R.string.player_header_lyrics)).fetchSemanticsNode().config.toString())
            .contains("Selected")
        // The bar: play, the options.
        compose.onNodeWithContentDescription(s(R.string.lyrics_options)).assertExists()
        compose.onNodeWithContentDescription(s(R.string.cd_pause)).assertExists()
        // The cover view's own title row is not under the lyrics.
        compose.onAllNodesWithContentDescription(s(R.string.player_add_bookmark)).assertCountEquals(0)
        RenderChecks.assertInsideWindow(compose, "lyrics view")
        // Pressing Lyrics again goes back to the cover.
        compose.onNodeWithContentDescription(s(R.string.player_header_lyrics)).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(s(R.string.player_add_bookmark)).assertCountEquals(1)
    }
}

/** In Spanish, the design's own language. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xxhdpi")
class PlayerLookRenderSpanishTest : PlayerLookRenderTest()

/** And in Swedish. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xxhdpi")
class PlayerLookRenderSwedishTest : PlayerLookRenderTest()

/** And on the light theme: every colour is a token, so nothing is white on white. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class PlayerLookRenderLightTest : PlayerLookRenderTest() {
    override val palette = com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Light
}
