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

/** B29-L6: the genre page's state as the view model builds it from what the repository returns. */
@OptIn(ExperimentalCoroutinesApi::class)
class GenreDetailViewModelTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private val rock = Genre(id = "rock", name = "Rock", lightColorHex = "#000000", onLightColorHex = "#FFFFFF", darkColorHex = "#000000", onDarkColorHex = "#FFFFFF")

    private fun song(id: Int, title: String, artist: String, artistId: Long, album: String, albumId: Long, item: String = "item-$id", track: Int = id) =
        Song.emptySong().copy(
            id = "$id", libraryItemId = item, title = title, artist = artist, artistId = artistId,
            artists = listOf(ArtistRef(artistId, artist, true)), album = album, albumId = albumId, trackNumber = track, genre = "Rock",
        )

    private fun viewModel(returned: List<Song>): GenreDetailViewModel {
        val repository = mockk<MusicRepository>()
        every { repository.getGenres() } returns flowOf(listOf(rock))
        every { repository.getMusicByGenre("Rock") } returns flowOf(returned)
        return GenreDetailViewModel(repository, SavedStateHandle(mapOf("genreId" to "rock")), TestDispatcherProvider(Dispatchers.Unconfined))
    }

    @Test
    fun `a song the query returned twice is on the page once, and the albums and artists count it once`() {
        val one = song(1, "Alpha", "Amy", 1, "LP", 10)
        val two = song(2, "Beta", "Amy", 1, "LP", 10)
        val state = viewModel(listOf(one, two, one)).uiState.value
        assertThat(state.songs.map { it.id }).containsExactly("1", "2").inOrder()
        assertThat(state.albums.single().songCount).isEqualTo(2)
        assertThat(state.artists.single().songCount).isEqualTo(2)
        assertThat(state.error).isNull()
    }

    @Test
    fun `the page lists the songs in title order, then artist, then album, and that is the list Play queues`() {
        val songs = listOf(
            song(1, "Beta", "Zed", 3, "Z Album", 3),
            song(2, "Alpha", "Amy", 1, "B Album", 1),
            song(3, "Delta", "Bob", 2, "A Album", 2),
            song(4, "Alpha", "Aaron", 5, "C Album", 5),
        )
        val vm = viewModel(songs)
        assertThat(vm.uiState.value.songs.map { it.title }).containsExactly("Alpha", "Alpha", "Beta", "Delta").inOrder()
        // the two Alphas by artist
        assertThat(vm.uiState.value.songs.map { it.artist }.take(2)).containsExactly("Aaron", "Amy").inOrder()
        // what is listed is what plays: the one definition the screen, the buttons and a row all take
        assertThat(vm.uiState.value.songs).isEqualTo(SourceOrder.genrePage(songs))
    }

    @Test
    fun `the albums and the artists are kept for the counts line and the cover, not as lists of the page`() {
        val songs = listOf(song(1, "A", "Amy", 1, "LP", 10), song(2, "B", "Bob", 2, "EP", 11), song(3, "C", "Bob", 2, "EP", 11))
        val vm = viewModel(songs)
        assertThat(vm.uiState.value.albums.map { it.title }).containsExactly("EP", "LP").inOrder()
        assertThat(vm.uiState.value.artists.map { it.name }).containsExactly("Amy", "Bob").inOrder()
        assertThat(vm.uiState.value.albums.single { it.title == "EP" }.songCount).isEqualTo(2)
    }

    @Test
    fun `reload reads the genre again`() {
        val repository = mockk<MusicRepository>()
        every { repository.getGenres() } returns flowOf(listOf(rock))
        every { repository.getMusicByGenre("Rock") } returnsMany listOf(
            flowOf(listOf(song(1, "Alpha", "Amy", 1, "LP", 10), song(2, "Beta", "Amy", 1, "LP", 10))),
            flowOf(listOf(song(1, "Alpha", "Amy", 1, "LP", 10))),
        )
        val vm = GenreDetailViewModel(repository, SavedStateHandle(mapOf("genreId" to "rock")), TestDispatcherProvider(Dispatchers.Unconfined))
        assertThat(vm.uiState.value.songs).hasSize(2)
        vm.reload()
        assertThat(vm.uiState.value.songs.map { it.id }).containsExactly("1")
    }

    @Test
    fun `a genre the library does not list still gets a page named after its id`() {
        val repository = mockk<MusicRepository>()
        every { repository.getGenres() } returns flowOf(emptyList())
        every { repository.getMusicByGenre("Unknown") } returns flowOf(listOf(song(1, "Alpha", "Amy", 1, "LP", 10)))
        val vm = GenreDetailViewModel(repository, SavedStateHandle(mapOf("genreId" to "unknown")), TestDispatcherProvider(Dispatchers.Unconfined))
        assertThat(vm.uiState.value.genre?.name).isEqualTo("Unknown")
        assertThat(vm.uiState.value.songs).hasSize(1)
    }
}
