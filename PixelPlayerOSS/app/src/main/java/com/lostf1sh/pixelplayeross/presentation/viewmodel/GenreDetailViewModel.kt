package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import javax.inject.Inject

/**
 * What the genre page holds (B33-U4): the songs once each and in the order they are shown (title, then artist, then album), and the albums
 * and artists they belong to (the counts line, the header's cover, and the Search screen's genre section read them). [songs] **is** the list
 * Play queues and a row starts from; Shuffle puts random on top of the same list.
 */
data class GenreDetailUiState(
    val genre: Genre? = null,
    /** The genre's songs, one row per library item, in the page's one order ([SourceOrder.genrePage]). */
    val songs: List<Song> = emptyList(),
    val albums: List<GenreAlbum> = emptyList(),
    val artists: List<GenreArtist> = emptyList(),
    val isLoadingGenreName: Boolean = false,
    val isLoadingSongs: Boolean = false,
    val error: String? = null,
    val imageError: Boolean = false,
)

@HiltViewModel
class GenreDetailViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val savedStateHandle: SavedStateHandle,
    private val dispatchers: DispatcherProvider,
    private val genreImages: com.lostf1sh.pixelplayeross.data.repository.GenreImageRepository,
    /** B56-5: plays, for the tie-break of the top artists. */
    private val ledgers: com.lostf1sh.pixelplayeross.data.stats.LedgerProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GenreDetailUiState())
    val uiState: StateFlow<GenreDetailUiState> = _uiState.asStateFlow()

    private var customImagePath: String? = null

    init {
        savedStateHandle.get<String>("genreId")?.let { genreId ->
            val decodedGenreId = java.net.URLDecoder.decode(genreId, "UTF-8")
            observeImage(decodedGenreId)
            loadGenreDetails(decodedGenreId)
        } ?: run {
            _uiState.update { it.copy(error = "Genre ID not found", isLoadingGenreName = false, isLoadingSongs = false) }
        }
    }

    /**
     * Re-reads this genre from the library.
     *
     * The songs on the genre screen are read once, not observed, so deleting a file from the device
     * leaves that snapshot stale. Reloading is enough — the row is already gone from the library.
     */
    fun reload() {
        savedStateHandle.get<String>("genreId")?.let { genreId ->
            loadGenreDetails(java.net.URLDecoder.decode(genreId, "UTF-8"))
        }
    }

    private var imageJob: kotlinx.coroutines.Job? = null

    /** The genre's chosen image, followed under [key]: the route's id first, the merged genre's own id once it is known (B56-5). */
    private fun observeImage(key: String) {
        imageJob?.cancel()
        imageJob = viewModelScope.launch {
            genreImages.observe(key).collect { path ->
                customImagePath = path
                _uiState.update { it.copy(genre = it.genre?.copy(imagePath = path)) }
            }
        }
    }

    fun setCustomImage(source: android.net.Uri) = changeImage { key ->
        check(genreImages.setImage(key, source) != null) { "Could not copy genre image" }
    }

    fun removeCustomImage() = changeImage { key -> genreImages.removeImage(key) }

    fun dismissImageError() {
        _uiState.update { it.copy(imageError = false) }
    }

    private fun changeImage(action: suspend (String) -> Unit) {
        val id = savedStateHandle.get<String>("genreId") ?: return
        viewModelScope.launch {
            try {
                action(_uiState.value.genre?.id ?: java.net.URLDecoder.decode(id, "UTF-8"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                timber.log.Timber.w(error, "Could not update genre image")
                _uiState.update { it.copy(imageError = true) }
            }
        }
    }

    /** A genre by its id, its name or any spelling of it: "hip-hop" from a Stats row opens the merged Hip Hop (B56-5). */
    private fun findGenre(genres: List<Genre>, idOrName: String): Genre? =
        genres.find { it.id.equals(idOrName, ignoreCase = true) }
            ?: genres.find { com.lostf1sh.pixelplayeross.data.model.GenreNames.key(it.name) == com.lostf1sh.pixelplayeross.data.model.GenreNames.key(idOrName) }

    /** Plays of each song as the listening ledger counts them, by locator (the song's id); nothing when the ledger cannot be read. */
    private suspend fun playsBySong(): Map<String, Int> = try {
        val ledger = ledgers.ledger()
        ledger.catalog.items.mapNotNull { item -> ledger.stats(item.identity)?.plays?.takeIf { it > 0 }?.let { item.locator to it } }.toMap()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Exception) {
        emptyMap()
    }

    private data class ProcessingResult(
        val genre: Genre,
        val songs: List<Song>,
        val albums: List<GenreAlbum>,
        val artists: List<GenreArtist>,
    )

    private fun loadGenreDetails(genreId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingGenreName = true, isLoadingSongs = true, error = null) }

            try {
                val initialGenre = withContext(dispatchers.default) {
                    val genres = musicRepository.getGenres().first()
                    findGenre(genres, genreId)
                }

                if (initialGenre != null) {
                    if (!initialGenre.id.equals(genreId, ignoreCase = true)) observeImage(initialGenre.id)
                    _uiState.update { it.copy(genre = initialGenre.copy(imagePath = customImagePath), isLoadingGenreName = false) }
                }

                val result = withContext(dispatchers.default) {
                    val genres = musicRepository.getGenres().first()
                    val genre = initialGenre ?: findGenre(genres, genreId)
                        ?: Genre(
                            id = genreId,
                            name = genreId.replace("_", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                            lightColorHex = "#9E9E9E", onLightColorHex = "#000000",
                            darkColorHex = "#616161", onDarkColorHex = "#FFFFFF"
                        )

                    val songs = GenreBrowse.distinctFiles(musicRepository.getMusicByGenre(genre.name).first())
                    ProcessingResult(
                        genre = genre,
                        songs = SourceOrder.genrePage(songs),
                        albums = GenreBrowse.albums(songs),
                        artists = GenreBrowse.artists(songs, playsBySong()),
                    )
                }

                _uiState.update {
                    it.copy(
                        genre = result.genre.copy(imagePath = customImagePath),
                        songs = result.songs,
                        albums = result.albums,
                        artists = result.artists,
                        isLoadingGenreName = false,
                        isLoadingSongs = false
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        error = "Failed to load genre details: ${e.message}",
                        isLoadingGenreName = false,
                        isLoadingSongs = false
                    )
                }
            }
        }
    }
}
