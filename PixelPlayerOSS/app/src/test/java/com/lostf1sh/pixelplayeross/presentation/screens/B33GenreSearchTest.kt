package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.SearchFilterType
import com.lostf1sh.pixelplayeross.data.model.SearchResultItem
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.B33Renders
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.rememberSettledQuery
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SEARCH_DEBOUNCE_MS
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.theme.LocalPixelPlayerDarkTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B33-U5: a query that names a genre adds a folded "Genre · X" section at the very bottom of Search's All tab, and nowhere else. The real
 * [SearchScreen] over a stubbed view model (the ordinary search is the view model's, and is given here as the results it would publish),
 * at 360 x 805 dp (xhdpi), with real clicks and a real navigation host.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
open class B33GenreSearchTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private val language get() = context.resources.configuration.locales[0].language
    private val tag get() = if (language == "en") "" else "-$language"

    private fun genre(name: String) = Genre(id = Genre.idFor(name), name = name)
    private val bachata = genre("Bachata")
    private val salsa = genre("Salsa")
    private val rock = genre("Rock")
    private val hardRock = genre("Hard Rock")
    private val allGenres = listOf(bachata, salsa, rock, hardRock, genre("Hip Hop"))

    private fun song(id: Int, title: String, artist: String, artistId: Long, album: String, albumId: Long) = Song.emptySong().copy(
        id = "$id", libraryItemId = "item-$id", title = title, artist = artist, artistId = artistId,
        artists = listOf(ArtistRef(artistId, artist, true)), album = album, albumId = albumId, genre = "Bachata", duration = 200_000L,
    )

    private val bachataSongs = listOf(
        song(1, "Obsesión", "Aventura", 1, "Generation Next", 10),
        song(2, "Amorcito Enfermito", "Aventura", 1, "Generation Next", 10),
        song(3, "Propuesta Indecente", "Romeo Santos", 2, "Golden", 20),
        song(4, "Hilito", "Xtreme", 3, "Chosen Few", 30),
        song(5, "Dile al Amor", "Aventura", 1, "Kings of Bachata", 11),
    )

    /** What the ordinary search finds for the query: a song, an album and an artist, as the search publishes them. */
    private val ordinary: ImmutableList<SearchResultItem> = persistentListOf(
        SearchResultItem.SongItem(song(90, "La Bachata Perdida", "Los Amigos", 9, "Raros", 90)),
        SearchResultItem.AlbumItem(Album(id = 91, title = "Bachata Rosa", artist = "Juan Luis", year = 1990, dateAdded = 0L, albumArtUriString = null, songCount = 10)),
        SearchResultItem.ArtistItem(Artist(id = 92, name = "Bachata Brothers", songCount = 4)),
    )

    private val uiState = MutableStateFlow(PlayerUiState(searchResults = ordinary, selectedSearchFilter = SearchFilterType.ALL))
    private val genres = MutableStateFlow<ImmutableList<Genre>>(allGenres.toImmutableList())
    private val loads = mutableListOf<String>()
    private var navController: NavHostController? = null
    private val played = mutableListOf<Pair<String, List<String>>>()

    private fun newPlayer(initialQuery: String) = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.playerUiState } returns uiState
        every { it.genres } returns genres
        every { it.stablePlayerState } returns MutableStateFlow(StablePlayerState())
        every { it.favoriteSongIds } returns MutableStateFlow(emptySet())
        every { it.selectedSongForInfo } returns MutableStateFlow(null)
        every { it.navBarCompactMode } returns MutableStateFlow(false)
        every { it.searchNavDoubleTapEvents } returns MutableSharedFlow<Unit>()
        every { it.searchQuery } returns initialQuery
        every { it.observeSongs(any()) } returns flowOf(emptyList())
        every { it.updateSearchFilter(any()) } answers { uiState.value = uiState.value.copy(selectedSearchFilter = firstArg()) }
        coEvery { it.songsOfGenreOnce(any()) } coAnswers {
            loads += firstArg<String>()
            if (firstArg<String>() == "Bachata") bachataSongs else emptyList()
        }
        every { it.showAndPlaySong(any<Song>(), any(), any(), any(), any(), any(), any(), any()) } answers {
            played += firstArg<Song>().id to secondArg<List<Song>>().map { s -> s.id }
        }
    }

    private lateinit var player: PlayerViewModel

    private fun show(query: String = "bachata") {
        player = newPlayer(query)
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(
                    LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher()),
                    LocalPixelPlayerDarkTheme provides true,
                ) {
                    val nav = rememberNavController()
                    navController = nav
                    Box(Modifier.fillMaxSize().background(Dap.background)) {
                        NavHost(nav, startDestination = "search") {
                            composable("search") {
                                SearchScreen(
                                    paddingValues = PaddingValues(), playerViewModel = player,
                                    playlistViewModel = mockk<PlaylistViewModel>(relaxed = true), navController = nav,
                                )
                            }
                            composable(Screen.GenreDetail.route) { Text("GENRE PAGE ${it.arguments?.getString("genreId")}") }
                            composable(Screen.AlbumDetail.route) { Text("ALBUM PAGE ${it.arguments?.getString("albumId")}") }
                            composable(Screen.ArtistDetail.route) { Text("ARTIST PAGE ${it.arguments?.getString("artistId")}") }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS + 100)
        compose.waitForIdle()
    }

    private fun title(genre: Genre) = s(R.string.search_genre_section_title, genre.name)
    private fun header(genre: Genre) = compose.onNode(hasContentDescription(title(genre)))
    private fun sections() = compose.onAllNodes(hasContentDescription(s(R.string.search_genre_section_title, ""), substring = true))
    private fun tab(id: Int) = compose.onNode(
        hasText(s(id).uppercase(context.resources.configuration.locales[0])) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)
    )

    /** The text of every node, top to bottom: what the list shows, in the order it shows it. */
    private fun textsTopDown(): List<String> = compose.onAllNodesWithText("", substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.let { node.boundsInRoot.top to it } }
        .sortedBy { it.first }
        .map { it.second }

    /** The unfold loads on the library's thread and the groups are built on another: give them real time. */
    private fun waitForGroups() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (compose.onAllNodesWithText(s(R.string.search_genre_see_all), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithContentDescription(s(R.string.search_genre_see_all_cd, "Bachata")).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(50)
        }
    }

    // ── where it is, and what it does not touch ──────────────────────────────────────────────────────

    @Test
    fun `a query that names a genre shows its folded section last in All, below every ordinary result`() {
        show()
        header(bachata).assertExists()
        val last = compose.onNodeWithText("Bachata Brothers").fetchSemanticsNode().boundsInRoot
        val head = header(bachata).fetchSemanticsNode().boundsInRoot
        assertThat(head.top).isAtLeast(last.bottom)
        // folded: it says so, and its groups are not there
        assertThat(header(bachata).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo(s(R.string.search_genre_section_folded))
        assertThat(compose.onAllNodesWithText(s(R.string.search_genre_see_all)).fetchSemanticsNodes()).isEmpty()
        B33Renders.save(compose, "u5-search-folded$tag")
    }

    @Test
    fun `the ordinary results are the same items in the same order with and without the section`() {
        genres.value = persistentListOf() // the query names no genre the library has
        show()
        val without = textsTopDown()
        sections().assertCountEquals0()
        genres.value = allGenres.toImmutableList()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS + 100)
        compose.waitForIdle()
        header(bachata).assertExists()
        val with = textsTopDown()
        // every text of the ordinary results is still there, in the same order; the folded section adds one line, its own, at the very end
        assertThat(with).isEqualTo(without + title(bachata).uppercase(context.resources.configuration.locales[0]))
        // (the ordinary search puts the best-matching kind first: here the album, the artist, then the song)
        assertThat(without).containsAtLeast("Bachata Rosa", "Bachata Brothers", "La Bachata Perdida").inOrder()
    }

    @Test
    fun `there is no section for a query that names no genre`() {
        show(query = "zzzz")
        sections().assertCountEquals0()
        assertThat(compose.onAllNodesWithContentDescription(s(R.string.search_genre_section_title, "Salsa")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `it is for All only, no other tab has it`() {
        show()
        header(bachata).assertExists()
        for (tabLabel in listOf(R.string.search_filter_songs, R.string.search_filter_albums, R.string.search_filter_artists, R.string.search_filter_playlists)) {
            tab(tabLabel).performClick()
            compose.waitForIdle()
            sections().assertCountEquals0()
        }
        tab(R.string.search_filter_all).performClick()
        compose.waitForIdle()
        header(bachata).assertExists()
    }

    @Test
    fun `accents and case in the typed query find the same genre`() {
        show(query = "salsa")
        header(salsa).assertExists()
        for (typed in listOf("SALSA", "Salsá", "  salsa ")) {
            compose.onNode(hasSetTextAction()).performTextReplacement(typed)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS + 100)
            compose.waitForIdle()
            header(salsa).assertExists()
        }
    }

    @Test
    fun `several genres show one folded section each, best first, at most three`() {
        genres.value = listOf(genre("Hard Rock"), genre("Punk Rock"), genre("Rock"), genre("Rock & Roll"), genre("Pop Rock")).toImmutableList()
        show(query = "rock")
        val titles = sections().fetchSemanticsNodes().map { it.config[SemanticsProperties.ContentDescription].first() }
        assertThat(titles).containsExactly(s(R.string.search_genre_section_title, "Rock"), s(R.string.search_genre_section_title, "Rock & Roll"), s(R.string.search_genre_section_title, "Pop Rock")).inOrder()
    }

    @Test
    fun `the section reads the query after the search's own debounce, and follows it`() {
        show(query = "bachata")
        header(bachata).assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("salsa")
        compose.waitForIdle()
        // not yet: the query has not rested
        compose.mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS - 100)
        compose.waitForIdle()
        header(bachata).assertExists()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        header(salsa).assertExists()
        assertThat(compose.onAllNodesWithContentDescription(title(bachata)).fetchSemanticsNodes()).isEmpty()
    }

    // ── cheap: nothing is read from the library until it is unfolded ─────────────────────────────────

    @Test
    fun `typing and matching read nothing from the library, only unfolding does, and only once`() {
        show()
        compose.onNode(hasSetTextAction()).performTextReplacement("bachat")
        compose.onNode(hasSetTextAction()).performTextReplacement("bachata")
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS + 100)
        compose.waitForIdle()
        coVerify(exactly = 0) { player.songsOfGenreOnce(any()) }

        header(bachata).performClick()
        waitForGroups()
        assertThat(loads).containsExactly("Bachata")
        // fold, unfold again: what was loaded is kept
        header(bachata).performClick()
        compose.waitForIdle()
        header(bachata).performClick()
        waitForGroups()
        assertThat(loads).containsExactly("Bachata")
        coVerify(exactly = 1) { player.songsOfGenreOnce("Bachata") }
    }

    // ── unfolded ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `unfolded, it shows the genre's artists, albums and songs in compact groups and a See all`() {
        show()
        header(bachata).performClick()
        waitForGroups()
        assertThat(header(bachata).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo(s(R.string.search_genre_section_unfolded))
        // artists, most songs first; albums likewise; songs in the genre page's order
        compose.onNodeWithText("Romeo Santos").performScrollTo().assertExists()
        compose.onNodeWithText("Generation Next").performScrollTo().assertExists()
        compose.onNodeWithText("Amorcito Enfermito").performScrollTo().assertExists()
        compose.onNodeWithText("Dile al Amor").assertExists()
        assertThat(compose.onAllNodesWithText("Aventura", useUnmergedTree = true).fetchSemanticsNodes().size).isAtLeast(2)
        // only a few rows of each: three songs of the five, three artists, three albums
        assertThat(compose.onAllNodesWithText("Propuesta Indecente").fetchSemanticsNodes()).isEmpty()
        compose.onNode(hasContentDescription(s(R.string.search_genre_see_all_cd, "Bachata"))).assertExists()
        B33Renders.save(compose, "u5-search-unfolded$tag")
    }

    @Test
    fun `See all opens the genre page, an artist row its artist, an album row its album`() {
        show()
        header(bachata).performClick()
        waitForGroups()
        compose.onNode(hasContentDescription(s(R.string.search_genre_see_all_cd, "Bachata"))).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(navController!!.currentBackStackEntry?.destination?.route).isEqualTo(Screen.GenreDetail.route)
        assertThat(navController!!.currentBackStackEntry?.arguments?.getString("genreId")).isEqualTo("bachata")
        compose.onNodeWithText("GENRE PAGE bachata").assertExists()
    }

    @Test
    fun `an artist row opens that artist and an album row that album`() {
        show()
        header(bachata).performClick()
        waitForGroups()
        compose.onNodeWithText("Romeo Santos").performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(navController!!.currentBackStackEntry?.destination?.route).isEqualTo(Screen.ArtistDetail.route)
        assertThat(navController!!.currentBackStackEntry?.arguments?.getString("artistId")).isEqualTo("2")
    }

    @Test
    fun `an album row opens that album`() {
        show()
        header(bachata).performClick()
        waitForGroups()
        compose.onNodeWithText("Chosen Few").performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(navController!!.currentBackStackEntry?.destination?.route).isEqualTo(Screen.AlbumDetail.route)
        assertThat(navController!!.currentBackStackEntry?.arguments?.getString("albumId")).isEqualTo("30")
    }

    @Test
    fun `a song row plays from its place in the genre's whole list`() {
        show()
        header(bachata).performClick()
        waitForGroups()
        compose.onNodeWithText("Dile al Amor").performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(played).hasSize(1)
        assertThat(played.single().first).isEqualTo("5")
        // the queue is the genre page's list (by title), not the three rows that were shown
        assertThat(played.single().second).containsExactly("2", "5", "4", "1", "3").inOrder()
    }

    @Test
    fun `a genre that could not be read says so instead of an empty section`() {
        show()
        coEvery { player.songsOfGenreOnce(any()) } throws java.io.IOException("disk")
        header(bachata).performClick()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            compose.onAllNodesWithText(s(R.string.search_genre_load_failed)).fetchSemanticsNodes().isEmpty()) {
            compose.waitForIdle(); Thread.sleep(50)
        }
        compose.onNodeWithText(s(R.string.search_genre_load_failed)).assertExists()
    }

    // ── the folded section as a screen reader meets it ───────────────────────────────────────────────

    @Test
    fun `the section header is one button that says where it is and how to open it`() {
        show()
        val node = header(bachata).fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsProperties.Role)).isEqualTo(androidx.compose.ui.semantics.Role.Button)
        assertThat(node.config.getOrNull(SemanticsActions.OnClick)?.label).isEqualTo(s(R.string.search_genre_section_unfold))
        assertThat(node.boundsInRoot.height).isAtLeast(48f * 2f)
    }

    // ── the debounce on its own ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a cleared field hides the section at once, a typed one waits for the debounce`() {
        var typed by mutableStateOf("bachata")
        var settled = ""
        compose.setContent { settled = rememberSettledQuery(typed) }
        compose.waitForIdle()
        assertThat(settled).isEqualTo("bachata")
        typed = "salsa"
        compose.waitForIdle()
        assertThat(settled).isEqualTo("bachata")
        compose.mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS + 10)
        compose.waitForIdle()
        assertThat(settled).isEqualTo("salsa")
        typed = ""
        compose.waitForIdle()
        assertThat(settled).isEqualTo("")
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() {
    assertThat(fetchSemanticsNodes()).isEmpty()
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
class B33GenreSearchSpanishTest : B33GenreSearchTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xhdpi")
class B33GenreSearchSwedishTest : B33GenreSearchTest()
