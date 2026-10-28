package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailUiState(
    val album: Album? = null,
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val musicInfo: com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    private var loadedAlbumId: Long? = null

    private val _about = MutableStateFlow(com.lostf1sh.pixelplayeross.presentation.components.MusicInfoAboutState())

    /** B40-F4: the album's Wikipedia description, from what was downloaded before, and filled in when the switch is on and it arrives. */
    val about: StateFlow<com.lostf1sh.pixelplayeross.presentation.components.MusicInfoAboutState> = _about.asStateFlow()

    private var aboutFor: String? = null

    private fun loadAbout(album: Album) {
        val artist = album.albumArtist?.takeIf { it.isNotBlank() }
            ?: _uiState.value.songs.firstOrNull()?.artists?.firstOrNull { it.isPrimary }?.name
            ?: com.lostf1sh.pixelplayeross.data.musicinfo.MusicBrainzClient.firstCreditedArtist(album.artist)
        val key = "${album.title}|$artist"
        if (aboutFor == key) return
        aboutFor = key
        _uiState.value.songs.firstOrNull()?.let { song ->
            musicInfo.requestSongInfo(song)
            viewModelScope.launch { musicRepository.getLyrics(song) }
        }
        viewModelScope.launch {
            val service = musicInfo.service
            val kind = com.lostf1sh.pixelplayeross.data.musicinfo.AboutKind.ALBUM
            // the service reads the cache and asks the network on its own IO dispatcher: this scope is the main thread's (B40-fix1)
            suspend fun current() = com.lostf1sh.pixelplayeross.presentation.components.MusicInfoAboutState(
                description = service.cachedAbout(kind, album.title, artist),
            )
            _about.value = current()
            // The service decides whether anything better than what is shown may be asked for (a language it prefers, not marked missing,
            // with the switch on): always asking is how a cached English fallback still gets upgraded to the app's language (B40-fix2).
            service.fetchAbout(kind, album.title, artist)
            current().let { if (it != _about.value) _about.value = it }
        }
    }

    init {
        val albumIdString: String? = savedStateHandle.get("albumId")
        if (albumIdString != null) {
            val albumId = albumIdString.toLongOrNull()
            if (albumId != null) {
                loadedAlbumId = albumId
                loadAlbumData(albumId)
            } else {
                _uiState.update { it.copy(error = context.getString(R.string.invalid_album_id), isLoading = false) }
            }
        } else {
            _uiState.update { it.copy(error = context.getString(R.string.album_id_not_found), isLoading = false) }
        }
    }

    private fun loadAlbumData(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val albumDetailsFlow = musicRepository.getAlbumById(id)
                val albumSongsFlow = musicRepository.getSongsForAlbum(id)

                combine(albumDetailsFlow, albumSongsFlow) { album, songs ->
                    if (album != null) {
                        AlbumDetailUiState(
                            album = album,
                            songs = SourceOrder.albumPage(songs),
                            isLoading = false
                        )
                    } else {
                        AlbumDetailUiState(
                            error = context.getString(R.string.album_not_found),
                            isLoading = false
                        )
                    }
                }
                    .catch { e ->
                        emit(
                            AlbumDetailUiState(
                                error = context.getString(R.string.error_loading_album, e.localizedMessage ?: ""),
                                isLoading = false
                            )
                        )
                    }
                    .collect { newState ->
                        _uiState.value = newState
                        newState.album?.let(::loadAbout)
                    }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = context.getString(R.string.error_loading_album, e.localizedMessage ?: ""),
                        isLoading = false
                    )
                }
            }
        }
    }

    fun downloadedInfoChanged() {
        viewModelScope.launch {
            val album = _uiState.value.album ?: return@launch
            val artist = album.albumArtist?.takeIf { it.isNotBlank() }
                ?: com.lostf1sh.pixelplayeross.data.musicinfo.MusicBrainzClient.firstCreditedArtist(album.artist)
            _about.value = com.lostf1sh.pixelplayeross.presentation.components.MusicInfoAboutState(
                description = musicInfo.service.cachedAbout(com.lostf1sh.pixelplayeross.data.musicinfo.AboutKind.ALBUM, album.title, artist))
        }
    }

    /** Re-attempts loading the album after a failure (wired to the error-state retry button). */
    fun retry() {
        loadedAlbumId?.let { loadAlbumData(it) }
    }

    fun update(songs: List<Song>) {
        _uiState.update {
            it.copy(
                isLoading = false,
                songs = songs
            )
        }
    }
}
