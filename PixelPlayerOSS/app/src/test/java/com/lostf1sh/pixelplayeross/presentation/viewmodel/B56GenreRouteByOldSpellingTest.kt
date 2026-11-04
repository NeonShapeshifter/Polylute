package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** B56 phase 5.2: a genre route that names an older spelling ("hip-hop", from a Stats row or an old shortcut) opens the merged genre, and its page fills the top artists and albums. */
@OptIn(ExperimentalCoroutinesApi::class)
class B56GenreRouteByOldSpellingTest {
    @BeforeEach fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    private val hipHop = Genre(id = "hip_hop", name = "Hip Hop", lightColorHex = "#000000", onLightColorHex = "#FFFFFF", darkColorHex = "#000000", onDarkColorHex = "#FFFFFF")

    private fun song(id: Int, artistId: Long, albumId: Long) = Song.emptySong().copy(
        id = "$id", libraryItemId = "item-$id", title = "Song $id", artist = "Artist $artistId", artistId = artistId,
        artists = listOf(ArtistRef(artistId, "Artist $artistId", true)), album = "Album $albumId", albumId = albumId,
    )

    @Test
    fun `the old spelling's id finds the merged genre and its songs`() {
        val repository = mockk<MusicRepository>()
        every { repository.getGenres() } returns flowOf(listOf(hipHop))
        every { repository.getMusicByGenre("Hip Hop") } returns flowOf(listOf(song(1, 1, 10), song(2, 1, 10), song(3, 2, 11)))
        val viewModel = GenreDetailViewModel(repository, SavedStateHandle(mapOf("genreId" to "hip-hop")), TestDispatcherProvider(Dispatchers.Unconfined), mockk(relaxed = true), mockk(relaxed = true))

        val state = viewModel.uiState.value

        assertThat(state.genre?.name).isEqualTo("Hip Hop")
        assertThat(state.songs).hasSize(3)
        assertThat(state.error).isNull()
        assertThat(GenreBrowse.topArtists(state.artists).map { it.artistId }).containsExactly(1L, 2L).inOrder()   // 2 songs, then 1
        assertThat(GenreBrowse.albumsRanked(state.albums).map { it.albumId }).containsExactly(10L, 11L).inOrder()
    }
}
