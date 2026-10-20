package com.lostf1sh.pixelplayeross.presentation.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.presentation.B33Renders
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreBrowse
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreDetailUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreDetailViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SourceOrder
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TestDispatcherProvider
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.theme.LocalPixelPlayerDarkTheme
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B33-U4: the genre page is a playlist-like page of its songs: a header (cover, name, counts line), Play and Shuffle, the songs in title order.
 * No tabs, no sort, the unknown genre's quick fill under the header on that page only. Real touches on the real page at 360 x 805 dp (xhdpi);
 * Play and Shuffle are checked through the **real screen** over a real view model, so what they hand the player is the list on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
open class B33GenrePageTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private val language get() = context.resources.configuration.locales[0].language
    private val tag get() = if (language == "en") "" else "-$language"

    private fun cover(name: String, from: Int, to: Int): String {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, from, to, Shader.TileMode.CLAMP) }
        Canvas(bitmap).drawRect(0f, 0f, 300f, 300f, paint)
        val file = File(context.cacheDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://${file.absolutePath}"
    }

    private fun song(id: Int, title: String, artist: String, artistId: Long, album: String, albumId: Long, art: String? = null) = Song.emptySong().copy(
        id = "$id", libraryItemId = "item-$id", title = title, artist = artist, artistId = artistId,
        artists = listOf(ArtistRef(artistId, artist, true)), album = album, albumId = albumId, trackNumber = id, duration = 200_000L + id * 1_000L,
        genre = "Hip Hop", albumArtUriString = art,
    )

    private fun songs(withArt: Boolean = true): List<Song> {
        val a = if (withArt) cover("b33-genre-a", AColor.rgb(18, 40, 80), AColor.rgb(200, 40, 40)) else null
        val b = if (withArt) cover("b33-genre-b", AColor.rgb(60, 60, 60), AColor.rgb(190, 190, 150)) else null
        return listOf(
            song(1, "Beta", "Zed", 3, "Z Album", 3, b),
            song(2, "Alpha", "Amy", 1, "B Album", 1, a),
            song(3, "(Irreversible)", "Dillom", 4, "Post Mortem", 4, a),
            song(4, "Delta", "Bob", 2, "A Album", 2, b),
            song(5, "Gamma", "Amy", 1, "B Album", 1, a),
            song(6, "1-800-273-8255", "Logic, Alessia Cara, Khalid", 5, "Everybody", 5, b),
            song(7, "Zeta", "Cat", 6, "C Album", 6, a),
        )
    }

    private val calls = mutableListOf<String>()
    private val played = mutableListOf<String>()

    private fun stateFor(all: List<Song>) = GenreDetailUiState(
        songs = SourceOrder.genrePage(all), albums = GenreBrowse.albums(all), artists = GenreBrowse.artists(all),
    )

    private fun show(title: String, state: GenreDetailUiState, isUnknown: Boolean = false, currentSongId: String? = "2") {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(
                    LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher()),
                    LocalPixelPlayerDarkTheme provides true,
                ) {
                    Box(Modifier.fillMaxSize().background(Dap.background)) {
                        GenrePage(
                            title = title, state = state, currentSongId = currentSongId, isUnknownGenre = isUnknown,
                            onBack = { calls += "back" }, onRetry = { calls += "retry" }, onPlay = { calls += "play" }, onShuffle = { calls += "shuffle" },
                            onQuickFill = { calls += "quickFill" }, onPlaySong = { played += it.id }, onMoreSong = { calls += "more:${it.id}" },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        // the covers are read on the image loader's own threads
        Thread.sleep(2500)
        compose.waitForIdle()
    }

    private fun titlesInOrder(all: List<Song>): List<String> = compose.onAllNodesWithText("", substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
        .filter { it in all.map(Song::title) }

    // ── the page ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the page shows songs only, once each, in title order`() {
        val all = songs()
        show("Hip Hop", stateFor(all))
        assertThat(titlesInOrder(all)).isEqualTo(SourceOrder.genrePage(all).map { it.title })
        assertThat(titlesInOrder(all)).containsExactly("(Irreversible)", "1-800-273-8255", "Alpha", "Beta", "Delta", "Gamma", "Zeta").inOrder()
        // a song's artist and album are not groups of the page: no album or artist headings, no list of either
        assertThat(compose.onAllNodesWithText("A Album").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Post Mortem").fetchSemanticsNodes()).isEmpty()
        RenderChecks.assertInsideWindow(compose, "genre page")
        B33Renders.save(compose, "u4-genre-hiphop$tag")
    }

    @Test
    fun `the header names the genre and says how many songs, albums and artists it holds`() {
        val all = songs()
        show("Hip Hop", stateFor(all))
        compose.onNodeWithText("Hip Hop").assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.genre_count_songs, 7, 7), substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.genre_count_albums, 6, 6), substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(s(R.string.presentation_batch_b_play_it)).assertIsDisplayed()
        compose.onNodeWithContentDescription(s(R.string.shortcut_shuffle_short)).assertIsDisplayed()
    }

    @Test
    fun `there is no tab strip and no sort control`() {
        show("Hip Hop", stateFor(songs()))
        val tabs = compose.onAllNodes(SemanticsMatcher("is a tab") { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }).fetchSemanticsNodes()
        assertThat(tabs).isEmpty()
        listOf(R.string.search_filter_albums, R.string.search_filter_artists, R.string.sort_method_title, R.string.sort_method_artist, R.string.sort_method_album)
            .forEach { id ->
                assertThat(compose.onAllNodesWithText(s(id), ignoreCase = true).fetchSemanticsNodes()).isEmpty()
            }
        assertThat(compose.onAllNodesWithText("↑").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `Play and Shuffle are the header's actions, a row plays, its overflow opens its options`() {
        show("Hip Hop", stateFor(songs()))
        compose.onNodeWithContentDescription(s(R.string.presentation_batch_b_play_it)).performClick()
        compose.onNodeWithContentDescription(s(R.string.shortcut_shuffle_short)).performClick()
        compose.onNodeWithText("Gamma").performClick()
        assertThat(calls).containsExactly("play", "shuffle").inOrder()
        assertThat(played).containsExactly("5")
    }

    @Test
    fun `the quick fill is offered under the header on the unknown genre`() {
        show("Unknown", stateFor(songs(withArt = false)), isUnknown = true)
        compose.onNodeWithText(s(R.string.genre_quick_fill)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.genre_quick_fill)).performClick()
        assertThat(calls).containsExactly("quickFill")
        // under the header, above the first song
        // with no cover the header is the title alone, below the Back chevron's row, never behind it
        val back = compose.onNodeWithContentDescription(s(R.string.auth_cd_back)).fetchSemanticsNode().boundsInRoot
        assertThat(compose.onNodeWithText("Unknown").fetchSemanticsNode().boundsInRoot.top).isAtLeast(back.bottom - 1f)
        val quick = compose.onNodeWithText(s(R.string.genre_quick_fill)).fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("(Irreversible)").fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription(s(R.string.presentation_batch_b_play_it)).fetchSemanticsNode().boundsInRoot
        assertThat(quick.top).isAtLeast(play.bottom)
        assertThat(quick.bottom).isAtMost(first.top)
        B33Renders.save(compose, "u4-genre-unknown$tag")
    }

    @Test
    fun `the quick fill shows on the unknown genre only`() {
        show("Hip Hop", stateFor(songs()), isUnknown = false)
        assertThat(compose.onAllNodesWithText(s(R.string.genre_quick_fill)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `an empty genre says so, and a failed one offers to try again`() {
        show("Hip Hop", GenreDetailUiState())
        compose.onNodeWithText(s(R.string.genre_empty_title)).assertIsDisplayed()
        assertThat(compose.onAllNodesWithText(s(R.string.genre_quick_fill)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `the genre route knows the unknown genre by both of its names`() {
        assertThat(isUnknownGenreId("unknown")).isTrue()
        assertThat(isUnknownGenreId("Unknown Genre")).isTrue()
        assertThat(isUnknownGenreId("rock")).isFalse()
    }

    // ── what Play and Shuffle hand the player, through the real screen ───────────────────────────────

    @Test
    fun `Play queues the list on screen from its first song, and Shuffle puts random on top of the same list`() {
        val all = songs(withArt = false)
        val repository = mockk<MusicRepository>()
        val hipHop = Genre(id = "hip_hop", name = "Hip Hop", lightColorHex = "#000000", onLightColorHex = "#FFFFFF", darkColorHex = "#000000", onDarkColorHex = "#FFFFFF")
        every { repository.getGenres() } returns flowOf(listOf(hipHop))
        every { repository.getMusicByGenre("Hip Hop") } returns flowOf(all.shuffled(java.util.Random(5)))
        val viewModel = GenreDetailViewModel(
            repository, androidx.lifecycle.SavedStateHandle(mapOf("genreId" to "hip_hop")), TestDispatcherProvider(Dispatchers.Unconfined),
        )
        val queued = mutableListOf<List<Song>>()
        val firstSongs = mutableListOf<Song>()
        val shuffled = mutableListOf<List<Song>>()
        val shuffledAtZero = mutableListOf<Boolean>()
        val player = mockk<PlayerViewModel>(relaxed = true)
        every { player.stablePlayerState } returns MutableStateFlow(StablePlayerState())
        every { player.favoriteSongIds } returns MutableStateFlow(emptySet())
        every { player.customGenres } returns MutableStateFlow(emptySet())
        every { player.customGenreIcons } returns MutableStateFlow(emptyMap())
        every { player.playSongs(any(), any(), any(), any(), any()) } answers {
            queued += firstArg<List<Song>>(); firstSongs += secondArg<Song>()
        }
        every { player.playSongsShuffled(any(), any(), any(), any(), any(), any()) } answers {
            shuffled += firstArg<List<Song>>(); shuffledAtZero += arg<Boolean>(3)
        }
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(
                    LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher()),
                    LocalPixelPlayerDarkTheme provides true,
                ) {
                    Box(Modifier.fillMaxSize().background(Dap.background)) {
                        GenreDetailScreen(
                            navController = rememberNavController(), genreId = "hip_hop", playerViewModel = player, viewModel = viewModel,
                            playlistViewModel = mockk<PlaylistViewModel>(relaxed = true),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val expected = SourceOrder.genrePage(all)
        assertThat(titlesInOrder(all)).isEqualTo(expected.map { it.title })
        compose.onNodeWithContentDescription(s(R.string.presentation_batch_b_play_it)).performClick()
        compose.onNodeWithContentDescription(s(R.string.shortcut_shuffle_short)).performClick()
        assertThat(queued.single()).isEqualTo(expected)
        assertThat(firstSongs.single()).isEqualTo(expected.first())
        assertThat(shuffled.single()).isEqualTo(expected)
        assertThat(shuffledAtZero.single()).isTrue()
    }

    // ── the bar under the status bar, once the header has scrolled away ──────────────────────────────

    @Test
    fun `the bar under the status bar is clear while the header is below it, solid once the header's foot reaches it`() {
        val bar = 100f
        // the header is 600 px tall: the bar starts to fade in when its foot is two bars (200 px) from the top, and is solid at one
        assertThat(heroBarAlpha(0, 0, 600, bar)).isEqualTo(0f)
        assertThat(heroBarAlpha(0, 399, 600, bar)).isEqualTo(0f)
        assertThat(heroBarAlpha(0, 450, 600, bar)).isWithin(0.001f).of(0.5f)
        assertThat(heroBarAlpha(0, 500, 600, bar)).isEqualTo(1f)
        assertThat(heroBarAlpha(0, 580, 600, bar)).isEqualTo(1f)
        // past the first item the rows are under it
        assertThat(heroBarAlpha(1, 0, 600, bar)).isEqualTo(1f)
        assertThat(heroBarAlpha(5, 30, 0, bar)).isEqualTo(1f)
        // a header shorter than two bars fades from the first pixel
        assertThat(heroBarAlpha(0, 0, 150, bar)).isEqualTo(0f)
        assertThat(heroBarAlpha(0, 50, 150, bar)).isWithin(0.001f).of(0.5f)
        // no bar, no alpha
        assertThat(heroBarAlpha(0, 400, 600, 0f)).isEqualTo(0f)
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
class B33GenrePageSpanishTest : B33GenrePageTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xhdpi")
class B33GenrePageSwedishTest : B33GenrePageTest()
