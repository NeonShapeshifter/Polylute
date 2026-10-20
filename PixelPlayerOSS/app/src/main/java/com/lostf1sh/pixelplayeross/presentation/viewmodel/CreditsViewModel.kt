package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class CreditsUiState(
    val song: Song? = null,
    val performingArtist: String? = null,
    val albumArtist: String? = null,
    val composer: String? = null,
    val writer: String? = null,
    val producer: String? = null,
    val conductor: String? = null,
    val label: String? = null,
    val copyright: String? = null,
    val isLoading: Boolean = false
) {
    /** True when the file declared no credits at all, so the view shows its empty state. */
    val isEmpty: Boolean
        get() = listOf(
            performingArtist, albumArtist, composer, writer, producer, conductor, label, copyright
        ).all { it.isNullOrBlank() }
}

/**
 * Backs the Credits surface. Local-first by construction: it reads the file's own tags and never
 * makes a network call, so it works with the radio off.
 *
 * A field the file does not carry stays null and the row is omitted — the screen never prints
 * "Composer: Unknown".
 */
@HiltViewModel
class CreditsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreditsUiState())
    val uiState: StateFlow<CreditsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var observedSongId: String? = null

    fun bind(song: Song) {
        if (observedSongId == song.id) return
        observedSongId = song.id

        // Seed from the library so the screen is never blank while the tag read runs, then let
        // the file's own tags refine it.
        _uiState.value = CreditsUiState(
            song = song,
            performingArtist = song.displayArtist.takeIf { it.isNotBlank() },
            albumArtist = song.albumArtist?.takeIf { it.isNotBlank() },
            isLoading = true
        )

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val credits = withContext(dispatcherProvider.io) {
                runCatching {
                    val uri = song.contentUriString.takeIf { it.isNotBlank() }?.let(Uri::parse)
                    val file = song.path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
                    AudioMetadataReader.readCredits(context, uri, file)
                }.getOrNull()
            }
            _uiState.value = CreditsUiState(
                song = song,
                performingArtist = credits?.performingArtist
                    ?: song.displayArtist.takeIf { it.isNotBlank() },
                albumArtist = credits?.albumArtist ?: song.albumArtist?.takeIf { it.isNotBlank() },
                composer = credits?.composer,
                writer = credits?.writer,
                producer = credits?.producer,
                conductor = credits?.conductor,
                label = credits?.label,
                copyright = credits?.copyright,
                isLoading = false
            )
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        super.onCleared()
    }
}
