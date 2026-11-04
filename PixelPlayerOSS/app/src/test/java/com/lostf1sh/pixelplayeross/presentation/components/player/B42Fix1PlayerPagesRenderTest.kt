package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import com.lostf1sh.pixelplayeross.presentation.B42Fix1Renders
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.components.SongInfoBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.SongOptionsPresentation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputDetails
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OutputRoute
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SongInfoBottomSheetViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TrackDetailsUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette
import com.lostf1sh.pixelplayeross.ui.ds.dapColorScheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42GlassApp
import com.lostf1sh.pixelplayeross.ui.glass.B42Setup
import com.lostf1sh.pixelplayeross.ui.glass.rememberB42Scene
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42-fix1, items 1 and 2: the four pages of the full player, drawn: **Menu · Player · Lyrics · Details**, on the cover glass (a saturated cover)
 * and without it, on Graphite here and on Light in the subclass. The Menu is the options sheet's own body ([SongInfoBottomSheet] asked for as a
 * page), the Lyrics the lyrics view, the Details the track-details body, each under the player's header and over the player's glass.
 * `B42-fix1-evidence/pages-<glass|plain>-<graphite|light>-<menu|player|lyrics|details>.png`: renders at 720 x 1610 px, not phone screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
open class B42Fix1PlayerPagesRenderTest {
    @get:Rule
    val compose = createComposeRule()

    protected open val theme: DapBaseTheme = DapBaseTheme.Graphite
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)

    private fun song(n: Int, title: String) = Song(
        id = "$n", title = title, artist = "Gemini Sound", artistId = 7L, album = "Noche", albumId = 9L,
        path = "/storage/emulated/0/Music/Noche/$title.flac", contentUriString = "content://$n", albumArtUriString = null,
        duration = 245_000L, mimeType = "audio/flac", bitrate = 1000, sampleRate = 44100, genre = "Pop",
    )

    private val songs = persistentListOf(song(1, "Cine"), song(2, "Fiesta Pagana"), song(3, "Molinos de Viento"))
    private val lyrics = Lyrics(
        synced = (0 until 30).map { SyncedLine(it * 8_000, listOf("Abre la puerta, que estoy afuera", "Estás grande ya, pero tú eres mi nena", "Mira cómo brilla todo por la noche")[it % 3]) },
    )

    private val infoViewModel = mockk<SongInfoBottomSheetViewModel>(relaxed = true).also {
        every { it.audioMeta } returns MutableStateFlow(null)
        every { it.resolvedArtists } returns MutableStateFlow(persistentListOf())
        every { it.musicBrainzState } returns MutableStateFlow(SongInfoBottomSheetViewModel.MusicBrainzUiState.Idle)
        every { it.getSongLocationInfo(any()) } returns SongInfoBottomSheetViewModel.SongLocationInfo("Folder", "Music/Noche")
        every { it.isSongEditable(any()) } returns true
    }

    private fun uiState(): FullPlayerUiState {
        val current = songs[0]
        return buildFullPlayerUiState(
            env = PlayerSheetEnv(FullPlayerLoadingTweaks(delayAll = false), false, PlayerSheetState.EXPANDED, false, { 1f }),
            song = current, queue = songs, queueSourceName = "All Songs", mediaItemIndex = 0, carouselStyle = CarouselStyle.NO_PEEK,
            artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
            audioMetadata = PlaybackAudioMetadata(mediaId = current.id, mimeType = "audio/flac", sampleRate = 44100), showFileInfo = true,
            isFavorite = false, isBuffering = false, isShuffleEnabled = true, repeatMode = Player.REPEAT_MODE_OFF,
            isPlayingProvider = { true }, playWhenReadyProvider = { true }, currentPositionProvider = { 22_000L }, totalDurationProvider = { 245_000L },
            lyrics = PlayerLyricsState(
                searchState = LyricsSearchUiState.Idle,
                stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = current, isPlaying = true, totalDuration = 245_000L, lyrics = lyrics, lyricsResolveCompleted = true)),
                playbackPositionFlow = MutableStateFlow(22_000L), externalLyricsEnabled = false, lyricsSyncOffset = 0, immersiveLyricsEnabled = false,
                immersiveLyricsTimeout = 4000L, isImmersiveTemporarilyDisabled = false, isShuffleEnabled = true, repeatMode = Player.REPEAT_MODE_OFF, isFavorite = false,
                lyricsProvider = { lyrics }, lyricsResolveCompletedProvider = { true },
            ),
            isLandscape = false,
        )
    }

    private val slots = PlayerPageSlots(
        menu = {
            SongInfoBottomSheet(
                song = songs[0], isFavorite = false, onToggleFavorite = {}, onDismiss = {}, onPlaySong = {}, onAddToQueue = {}, onAddNextToQueue = {},
                onAddToPlayList = {}, onDeleteFromDevice = { _, _, _ -> }, onNavigateToAlbum = {}, onNavigateToArtist = {}, onNavigateToGenre = {},
                onViewTrackDetails = {}, onViewCredits = {},
                onEditSong = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                removeFromListTrigger = {}, songInfoViewModel = infoViewModel,
                presentation = SongOptionsPresentation.PAGE,
            )
        },
        details = {
            TrackDetailsBody(
                state = TrackDetailsUiState(song = songs[0], output = OutputDetails(route = OutputRoute.SPEAKER)),
                currentPositionProvider = { 22_000L }, totalDurationMs = 245_000L, onSeek = {},
            )
        },
    )

    @Composable
    private fun Player() {
        CompositionLocalProvider(LocalMaterialTheme provides dapColorScheme(LocalDapAccent.current, LocalDapPalette.current)) {
            FullPlayerScreen(uiState(), NoActions(), slots)
        }
    }

    private fun show(glass: Boolean, cover: B41Renders.Cover) {
        compose.mainClock.autoAdvance = true
        compose.setContent {
            if (glass) {
                val setup = androidx.compose.runtime.remember(cover, theme) { B42Setup(cover, theme) }
                val scene = rememberB42Scene(setup, rootPhase = 14f)
                B42GlassApp(setup, scene, expansion = 1f) { Player() }
            } else {
                DapTheme(baseTheme = theme, accent = DapAccent.Sage) {
                    Box(Modifier.fillMaxSize().background(Dap.background)) { Player() }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun goToMenu() {
        val action = compose.onNodeWithTag(PlayerPagesTags.Pager).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)
            .orEmpty().first { it.label == s(R.string.player_action_options) }
        compose.runOnUiThread { action.action() }
        settle()
    }

    private fun renderPages(glass: Boolean, cover: B41Renders.Cover) {
        val kind = if (glass) "glass" else "plain"
        val name = "pages-$kind-${theme.name.lowercase()}"
        show(glass, cover)
        RenderChecks.assertInsideWindow(compose, "$name-player")
        B42Fix1Renders.save(compose, "$name-player")

        goToMenu()
        compose.onNodeWithTag(PlayerPagesTags.Menu).assertExists()
        RenderChecks.assertInsideWindow(compose, "$name-menu")
        B42Fix1Renders.save(compose, "$name-menu")

        compose.playerPageAction(s(R.string.player_header_lyrics), toggle = true)
        settle()
        compose.onNodeWithTag(PlayerPagesTags.Lyrics).assertExists()
        RenderChecks.assertInsideWindow(compose, "$name-lyrics")
        B42Fix1Renders.save(compose, "$name-lyrics")

        compose.playerPageAction(s(R.string.player_header_details), toggle = true)
        settle()
        compose.onNodeWithTag(PlayerPagesTags.Details).assertExists()
        RenderChecks.assertInsideWindow(compose, "$name-details")
        B42Fix1Renders.save(compose, "$name-details")
    }

    @Test
    fun the_four_pages_on_the_cover_glass() = renderPages(glass = true, cover = B41Renders.saturated())

    @Test
    fun the_four_pages_without_the_glass() = renderPages(glass = false, cover = B41Renders.saturated())

    @Test
    fun the_menu_page_is_the_options_body_with_its_groups() {
        show(glass = true, cover = B41Renders.grey())
        goToMenu()
        for (text in listOf("Play", "Add to queue", "Go to album", "Track details")) {
            assertThat(compose.onAllNodesWithTextCount(text)).isAtLeast(1)
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().size
}

/** The same pages on Light. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B42Fix1PlayerPagesRenderLightTest : B42Fix1PlayerPagesRenderTest() {
    override val theme: DapBaseTheme = DapBaseTheme.Light
}
