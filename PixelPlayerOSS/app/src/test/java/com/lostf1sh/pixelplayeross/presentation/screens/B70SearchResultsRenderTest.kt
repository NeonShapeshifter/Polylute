package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.SearchFilterType
import com.lostf1sh.pixelplayeross.data.model.SearchResultItem
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h1100dp-xhdpi")
class B70SearchResultsRenderTest {
    @get:Rule val compose = createComposeRule()

    private val relatedTitles = listOf("Amber", "Birch", "Cedar", "Dune", "Elm", "Fern")
    private val relatedSongs = relatedTitles.mapIndexed { index, title ->
        SearchResultItem.SongItem(Song.emptySong().copy(id = "related-$index", title = title, artist = "Danz Artist"))
    }
    private val namedSong = SearchResultItem.SongItem(
        Song.emptySong().copy(id = "direct", title = "Danza Song", artist = "Other Artist")
    )
    private val album = SearchResultItem.AlbumItem(
        Album.empty().copy(id = 12L, title = "Northern Lights", artist = "Danz Artist")
    )
    private val artist = SearchResultItem.ArtistItem(Artist.empty().copy(id = 13L, name = "Danz Artist"))
    private val played = mutableListOf<Pair<String, List<String>>>()

    private fun show(
        filter: SearchFilterType = SearchFilterType.ALL,
        results: List<SearchResultItem> = relatedSongs.reversed() + listOf(namedSong, album, artist, namedSong)
    ) {
        val player = mockk<PlayerViewModel>(relaxed = true)
        every { player.stablePlayerState } returns MutableStateFlow(StablePlayerState())
        every { player.observeSongs(any()) } returns flowOf(emptyList())
        every { player.showAndPlaySong(any<Song>(), any(), any(), any(), any(), any(), any(), any()) } answers {
            played += firstArg<Song>().id to secondArg<List<Song>>().map(Song::id)
        }
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                Box(Modifier.fillMaxSize().background(Dap.background)) {
                    SearchResultsList(
                        results = results.toImmutableList(),
                        searchQuery = "danz",
                        selectedFilter = filter,
                        playerViewModel = player,
                        onItemSelected = {},
                        currentPlayingSongId = null,
                        isPlaying = false,
                        onSongMoreOptionsClick = {},
                        navController = rememberNavController(),
                        trailingContent = { Text("Genre preview") }
                    )
                }
            }
        }
    }

    @Test
    fun allRendersBestRowsThenRelatedSectionsAndGenresWithUniqueKeys() {
        show()

        val expectedTexts = listOf("BEST MATCHES", "Danz Artist", "Danza Song", "SONGS", "6") +
            relatedTitles + listOf("ALBUMS", "1", "Northern Lights", "Genre preview")
        val positions = expectedTexts.map { text ->
            compose.onAllNodesWithText(text).fetchSemanticsNodes().minOf { it.boundsInRoot.top }
        }
        // A header and its count sit on the same line. All other labels are below the preceding one.
        expectedTexts.zipWithNext().zip(positions.zipWithNext()).forEach { (texts, tops) ->
            if (texts.second in listOf("6", "1")) {
                assertThat(kotlin.math.abs(tops.first - tops.second)).isLessThan(12f)
            } else {
                assertThat(tops.first).isLessThan(tops.second)
            }
        }
        compose.onNodeWithText("ARTISTS").assertDoesNotExist()

        val expectedKeys = listOf("header_best_matches", "artist_13", "song_direct", "header_SONGS") +
            relatedSongs.map { "song_${it.song.id}" } + listOf("header_ALBUMS", "album_12", "genre_sections")
        val lazyList = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.IndexForKey)).fetchSemanticsNode()
        val indexForKey = lazyList.config[SemanticsProperties.IndexForKey]
        assertThat(expectedKeys.map(indexForKey)).containsExactlyElementsIn(expectedKeys.indices.toList()).inOrder()

        System.getenv("B70_GATES")?.let { directory ->
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(directory, "after-danz.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    @Test
    fun overflowRendersAllFiveSectionHeadersInOrder() {
        val bestSongs = (1..5).map { index ->
            SearchResultItem.SongItem(Song.emptySong().copy(id = "best-$index", title = "Danz $index"))
        }
        show(results = listOf(
            SearchResultItem.PlaylistItem(Playlist(id = "mix", name = "Danz Mix", songIds = emptyList())),
            SearchResultItem.ComposerItem("Danz Composer", 2),
            artist,
            SearchResultItem.AlbumItem(album.album.copy(title = "Danz Album")),
            namedSong
        ) + bestSongs)

        val headings = listOf("BEST MATCHES", "SONGS", "ALBUMS", "ARTISTS", "COMPOSERS", "PLAYLISTS")
        val positions = headings.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertThat(positions.zipWithNext().all { (first, second) -> first < second }).isTrue()
    }

    @Test
    fun bothSongBlocksPlayTheSameQueueInDisplayOrder() {
        show()
        compose.onNodeWithText("Danza Song").performClick()
        compose.onNodeWithText("Amber").performClick()

        val queue = listOf(namedSong.song.id) + relatedSongs.map { it.song.id }
        assertThat(played).containsExactly("direct" to queue, "related-0" to queue).inOrder()
    }

    @Test
    fun songsFilterHasNoBestBlock() {
        show(SearchFilterType.SONGS)
        compose.onNodeWithText("SONGS").assertIsDisplayed()
        compose.onNodeWithText("Danza Song").assertIsDisplayed()
        compose.onNodeWithText("BEST MATCHES").assertDoesNotExist()
        compose.onNodeWithText("ALBUMS").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "es-w360dp-h1100dp-xhdpi")
    fun spanishBestHeading() {
        show()
        compose.onNodeWithText("MEJORES RESULTADOS").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "sv-w360dp-h1100dp-xhdpi")
    fun swedishBestHeading() {
        show()
        compose.onNodeWithText("BÄSTA TRÄFFAR").assertIsDisplayed()
    }
}
