package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.ArtistImageRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Holds the full UI state for ArtistDetailScreen.
 *
 * [effectiveImageUrl] is the resolved image to display (custom takes priority over a downloaded one).
 * It is updated after artist data loads and again whenever the user changes the custom image.
 */
data class ArtistDetailUiState(
    val artist: Artist? = null,
    val songs: List<Song> = emptyList(),
    val albumSections: List<ArtistAlbumSection> = emptyList(),
    val effectiveImageUrl: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

@Immutable
data class ArtistAlbumSection(
    val albumId: Long,
    val title: String,
    val year: Int?,
    val albumArtUriString: String?,
    val songs: List<Song>
)

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val artistImageRepository: ArtistImageRepository,
    private val musicInfo: com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository,
    val themeStateHolder: ThemeStateHolder,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    /** Artist information loads independently of local content and the remote image palette. */
    private val _about = MutableStateFlow(com.lostf1sh.pixelplayeross.presentation.components.MusicInfoAboutState())

    /**
     * B40-F4: the artist's Wikipedia description and the credit of a downloaded photo. What was downloaded before is shown at once, offline
     * included; with the switch on, what is missing is asked for in the background and fills in when it arrives.
     */
    val about: StateFlow<com.lostf1sh.pixelplayeross.presentation.components.MusicInfoAboutState> = _about.asStateFlow()

    private var aboutJob: Job? = null

    private fun loadAbout(artistName: String, shownImage: String?) {
        aboutJob?.cancel()
        aboutJob = viewModelScope.launch {
            val service = musicInfo.service
            // the service reads the cache and asks the network on its own IO dispatcher: this scope is the main thread's (B40-fix1)
            suspend fun current() = com.lostf1sh.pixelplayeross.presentation.components.MusicInfoAboutState(
                description = service.cachedAbout(com.lostf1sh.pixelplayeross.data.musicinfo.AboutKind.ARTIST, artistName, null),
                // the credit goes with the photo only while that photo is the one on screen
                photo = service.cachedArtistPhoto(artistName)?.takeIf { it.path == shownImage },
            )
            _about.value = current()
            // The service decides whether anything better than what is shown may be asked for (a language it prefers, not marked missing,
            // with the switch on): always asking is how a cached English fallback still gets upgraded to the app's language (B40-fix2).
            service.fetchAbout(com.lostf1sh.pixelplayeross.data.musicinfo.AboutKind.ARTIST, artistName, null)
            current().let { if (it != _about.value) _about.value = it }
        }
    }

    private val _artistColorScheme = MutableStateFlow<ColorSchemePair?>(null)
    val artistColorScheme: StateFlow<ColorSchemePair?> = _artistColorScheme.asStateFlow()

    init {
        savedStateHandle.getStateFlow<String?>("artistId", null)
            .onEach { idString ->
                if (idString != null) {
                    val artistId = idString.toLongOrNull()
                    if (artistId != null) {
                        loadArtistData(artistId)
                    } else {
                        _uiState.update { it.copy(error = context.getString(R.string.invalid_artist_id), isLoading = false) }
                    }
                } else {
                    _uiState.update { it.copy(error = context.getString(R.string.artist_id_not_found), isLoading = false) }
                }
            }
            .launchIn(viewModelScope)
    }

    private var imageJob: Job? = null
    private var currentLoadJob: Job? = null
    private var loadedArtistId: Long? = null

    private fun loadArtistData(id: Long) {
        loadedArtistId = id
        currentLoadJob?.cancel()
        imageJob?.cancel()
        currentLoadJob = viewModelScope.launch {
            Timber.tag("ArtistDebug").d("loadArtistData: id=$id")
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val artistDetailsFlow = musicRepository.getArtistById(id)
                val artistSongsFlow = musicRepository.getSongsForArtist(id)

                combine(artistDetailsFlow, artistSongsFlow) { artist, songs ->
                    Timber.tag("ArtistDebug").d("loadArtistData: id=$id found=${artist != null} songs=${songs.size}")
                    artist to songs
                }
                    .catch { e ->
                        _uiState.update {
                            it.copy(
                                error = context.getString(R.string.error_loading_artist, e.localizedMessage ?: ""),
                                isLoading = false
                            )
                        }
                    }
                    .collect { (artist, songs) ->
                        if (artist == null) {
                            _uiState.update {
                                it.copy(error = context.getString(R.string.could_not_find_artist), isLoading = false)
                            }
                            return@collect
                        }

                        val albumSections = buildAlbumSections(songs)
                        val orderedSongs = albumSections.flatMap { it.songs }

                        // Publish the library immediately. Online images and their palette never hold the page's loading state.
                        val localImage = artist.effectiveImageUrl?.takeUnless { it.startsWith("http://") || it.startsWith("https://") }
                        _uiState.value = ArtistDetailUiState(
                            artist = artist,
                            songs = orderedSongs,
                            albumSections = albumSections,
                            effectiveImageUrl = localImage,
                            isLoading = false,
                        )
                        loadAbout(artist.name, localImage)
                        imageJob?.cancel()
                        imageJob = viewModelScope.launch {
                            val effectiveUrl = try {
                                artistImageRepository.getEffectiveArtistImageUrl(artist.id, artist.name)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                Timber.tag("ArtistDebug").w("Failed to resolve artist image: %s", e.message)
                                localImage
                            }
                            val scheme = if (!effectiveUrl.isNullOrBlank()) {
                                try { themeStateHolder.getOrGenerateColorScheme(effectiveUrl) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (e: Exception) { null }
                            } else null
                            ensureActive()
                            if (loadedArtistId != artist.id) return@launch
                            _artistColorScheme.value = scheme
                            _uiState.update { current ->
                                if (current.artist?.id != artist.id || current.artist.customImageUri != artist.customImageUri) current
                                else current.copy(
                                    effectiveImageUrl = effectiveUrl,
                                    artist = current.artist.copy(imageUrl = if (artist.customImageUri.isNullOrBlank()) effectiveUrl else current.artist.imageUrl),
                                )
                            }
                            loadAbout(artist.name, effectiveUrl)
                        }
                    }

            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = context.getString(R.string.error_loading_artist, e.localizedMessage ?: ""),
                        isLoading = false
                    )
                }
            }
        }
    }

    /**
     * Called from the UI when the user selects a custom image from the system photo picker.
     * Copies the image to internal storage, persists the path to DB, and triggers palette regeneration.
     */
    /** Re-attempts loading the artist after a failure (wired to the error-state retry button). */
    fun retry() {
        loadedArtistId?.let { loadArtistData(it) }
    }

    fun setCustomImage(sourceUri: Uri) {
        val artistId = _uiState.value.artist?.id ?: return
        viewModelScope.launch {
            try {
                val internalPath = artistImageRepository.setCustomArtistImage(context, artistId, sourceUri)
                if (!internalPath.isNullOrBlank()) {
                    val oldEffectiveUrl = _uiState.value.effectiveImageUrl

                    if (!oldEffectiveUrl.isNullOrBlank() && oldEffectiveUrl != internalPath) {
                        themeStateHolder.forceRegenerateColorScheme(oldEffectiveUrl)
                    }
                    val newScheme = try {
                        themeStateHolder.forceRegenerateColorScheme(internalPath)
                        themeStateHolder.getOrGenerateColorScheme(internalPath)
                    } catch (e: Exception) {
                        Timber.tag("ArtistDebug").w("Failed to regenerate color scheme for custom image: ${e.message}")
                        null
                    }

                    _artistColorScheme.value = newScheme
                    _uiState.update { state ->
                        val effectiveUrlWithBust = "$internalPath?t=${System.currentTimeMillis()}"
                        state.copy(
                            effectiveImageUrl = effectiveUrlWithBust,
                            artist = state.artist?.copy(customImageUri = internalPath)
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.tag("ArtistDebug").e("Failed to set custom image: ${e.message}")
            }
        }
    }

    /**
     * Called when the user wants to revert to the downloaded image.
     */
    fun clearCustomImage() {
        val artist = _uiState.value.artist ?: return
        viewModelScope.launch {
            try {
                val oldEffectiveUrl = _uiState.value.effectiveImageUrl
                artistImageRepository.clearCustomArtistImage(context, artist.id)

                val deezerUrl = artistImageRepository.getArtistImageUrl(artist.name, artist.id)
                val newEffectiveUrl = deezerUrl.takeIf { !it.isNullOrBlank() }

                if (!oldEffectiveUrl.isNullOrBlank()) {
                    themeStateHolder.forceRegenerateColorScheme(oldEffectiveUrl)
                }

                val newScheme = if (!newEffectiveUrl.isNullOrBlank()) {
                    try {
                        themeStateHolder.getOrGenerateColorScheme(newEffectiveUrl)
                    } catch (e: Exception) {
                        Timber.tag("ArtistDebug").w("Failed to regenerate palette after clear: ${e.message}")
                        null
                    }
                } else null

                _artistColorScheme.value = newScheme
                _uiState.update { state ->
                    state.copy(
                        effectiveImageUrl = newEffectiveUrl,
                        artist = state.artist?.copy(customImageUri = null, imageUrl = deezerUrl)
                    )
                }

            } catch (e: Exception) {
                Timber.tag("ArtistDebug").e("Failed to clear custom image: ${e.message}")
            }
        }
    }

    fun removeSongFromAlbumSection(songId: String) {
        _uiState.update { currentState ->
            val updatedAlbumSections = currentState.albumSections.map { section ->
                val updatedSongs = section.songs.filterNot { it.id == songId }
                section.copy(songs = updatedSongs)
            }.filter { it.songs.isNotEmpty() }

            currentState.copy(
                albumSections = updatedAlbumSections,
                songs = currentState.songs.filterNot { it.id == songId }
            )
        }
    }
}

private val songDisplayComparator = SourceOrder.albumPage

// B24-Q1: internal, so the buttons that play an artist (Search, the Artists tab's shuffle) use the page's own order.
internal fun buildAlbumSections(songs: List<Song>): List<ArtistAlbumSection> {
    if (songs.isEmpty()) return emptyList()

    val sections = songs
        .groupBy { it.albumId to it.album }
        .map { (key, albumSongs) ->
            val sortedSongs = albumSongs.sortedWith(songDisplayComparator)
            val albumYear = albumSongs.mapNotNull { song -> song.year.takeIf { it > 0 } }.maxOrNull()
            val albumArtUri = albumSongs.firstNotNullOfOrNull { it.albumArtUriString }
            ArtistAlbumSection(
                albumId = key.first,
                title = (key.second.takeIf { it.isNotBlank() } ?: "Unknown Album"),
                year = albumYear,
                albumArtUriString = albumArtUri,
                songs = sortedSongs
            )
        }

    val (withYear, withoutYear) = sections.partition { it.year != null }
    val withYearSorted = withYear.sortedWith(
        compareByDescending<ArtistAlbumSection> { it.year ?: Int.MIN_VALUE }
            .thenBy { it.title.lowercase() }
    )
    val withoutYearSorted = withoutYear.sortedBy { it.title.lowercase() }

    return withYearSorted + withoutYearSorted
}
