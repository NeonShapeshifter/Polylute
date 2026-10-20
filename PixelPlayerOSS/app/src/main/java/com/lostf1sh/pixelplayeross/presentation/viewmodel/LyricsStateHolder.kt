package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.media.CoverArtUpdate
import com.lostf1sh.pixelplayeross.data.media.SongMetadataEditor
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.LyricsSearchResult
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.di.MainAppScope
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import com.lostf1sh.pixelplayeross.data.repository.NoLyricsFoundException
import com.lostf1sh.pixelplayeross.utils.LyricsImportSecurity
import com.lostf1sh.pixelplayeross.utils.LyricsImportValidationResult
import com.lostf1sh.pixelplayeross.utils.LyricsUtils
import com.lostf1sh.pixelplayeross.utils.ValidatedLyricsImport
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.collections.immutable.toImmutableList

/**
 * The terminal outcome of a lyrics request. Every request ends in exactly one of these, and the
 * holder never leaves a request unresolved — that guarantee is what stops the UI from being able
 * to sit in Loading forever.
 */
sealed interface LyricsOutcome {
    /** The request finished. [lyrics] is null when the song genuinely has no lyrics. */
    data class Resolved(val lyrics: Lyrics?) : LyricsOutcome

    /** The request failed. [message] is displayable, and may be null. */
    data class Failed(val message: String?) : LyricsOutcome

    /**
     * The request was cancelled by its owner rather than superseded by a newer one. This says the
     * owner stopped asking; it says nothing about whether the song has lyrics, so it must not be
     * treated as "no lyrics".
     */
    data object Cancelled : LyricsOutcome
}

/**
 * Callback interface for lyrics loading results.
 * Used to update StablePlayerState in PlayerViewModel.
 *
 * The request id identifies one resolve attempt. The holder only ever invokes these for the
 * request that currently owns the state, so an implementation does not have to defend against
 * stale results from a superseded request — but it must not assume the id is contiguous.
 * [onLyricsSettled] is called exactly once for every request that was started and not superseded
 * by a newer one, which is what makes a permanently loading surface impossible.
 */
interface LyricsLoadCallback {
    fun onLoadingStarted(requestId: Long, songId: String)
    fun onLyricsSettled(requestId: Long, songId: String, outcome: LyricsOutcome)
}

/**
 * Manages lyrics loading, search state, and sync offset.
 * Extracted from PlayerViewModel to improve modularity.
 */
@Singleton
class LyricsStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val songMetadataEditor: SongMetadataEditor,
    /**
     * Process-lifetime scope, injected rather than borrowed from a ViewModel.
     *
     * Lyrics work is started by playback, not by a screen, so it must not be tied to a
     * screen's lifetime. Injected at application scope (see AppModule) and a test seam:
     * a JVM test can install a TestScope and keep deterministic virtual time.
     */
    @MainAppScope private val appScope: CoroutineScope,
    /**
     * Injected rather than a literal [Dispatchers], so a JVM test can drive the holder on its own
     * scheduler and keep virtual time deterministic.
     */
    private val dispatchers: DispatcherProvider
) {
    private var loadingJob: Job? = null
    private var loadCallback: LyricsLoadCallback? = null

    /**
     * Identity of the resolve that currently owns the player's lyrics state.
     *
     * A request may only publish while it still holds this. Starting a new resolve moves ownership
     * before the old coroutine can reach its terminal line, so a superseded request can never
     * write over the newer one's state — including when the old one had already finished its I/O
     * and was only waiting to publish.
     */
    private var requestSequence = 0L
    private var activeRequestId = 0L
    private var activeRequestSongId: String? = null

    private val _currentSongSyncOffset = MutableStateFlow(0)
    val currentSongSyncOffset: StateFlow<Int> = _currentSongSyncOffset.asStateFlow()

    private val _searchUiState = MutableStateFlow<LyricsSearchUiState>(LyricsSearchUiState.Idle)
    val searchUiState: StateFlow<LyricsSearchUiState> = _searchUiState.asStateFlow()

    private val _songUpdates = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Song, Lyrics?>>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val songUpdates = _songUpdates.asSharedFlow()

    private val _messageEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val messageEvents = _messageEvents.asSharedFlow()

    /**
     * Wire the callback and the playback-state observer that keeps the per-song sync offset
     * current. Work runs on the injected application scope, so it survives any screen.
     */
    fun initialize(
        callback: LyricsLoadCallback,
        stablePlayerState: StateFlow<com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState>
    ) {
        loadCallback = callback

        appScope.launch {
            stablePlayerState
                .map { it.currentSong?.id }
                .distinctUntilChanged()
                .collect { songId ->
                    if (songId != null) {
                        updateSyncOffsetForSong(songId)
                    }
                }
        }
    }

    /**
     * Load lyrics for a song.
     *
     * Any resolve already in flight is superseded: it is cancelled, and it loses ownership before
     * it can publish, so the request started here is the only one that can write state. This
     * request is guaranteed to end in a [LyricsOutcome] — including when the caller cancels it
     * through [cancelLoading] or [abandonActiveRequest].
     *
     * @param song The song to load lyrics for
     * @param sourcePreference The preferred source for lyrics
     */
    fun loadLyricsForSong(song: Song, sourcePreference: LyricsSourcePreference) {
        loadingJob?.cancel()
        val requestId = beginRequest(song.id)
        val targetSongId = song.id

        loadingJob = appScope.launch {
            loadCallback?.onLoadingStarted(requestId, targetSongId)

            val outcome: LyricsOutcome = try {
                val fetchedLyrics = withContext(dispatchers.io) {
                    musicRepository.getLyrics(
                        song = song,
                        sourcePreference = sourcePreference
                    )
                }
                LyricsOutcome.Resolved(fetchedLyrics)
            } catch (cancellation: CancellationException) {
                // Cancellation is not an outcome: either a newer request has already taken
                // ownership, or whoever cancelled us publishes a terminal outcome itself.
                throw cancellation
            } catch (error: Exception) {
                LyricsOutcome.Failed(error.message)
            }

            settle(requestId, outcome)
        }
    }

    /**
     * Cancel any ongoing lyrics loading.
     *
     * Because this is an owner-initiated stop with no successor, it must leave the request in a
     * terminal state — otherwise the surface it was feeding is left in Loading with nothing alive
     * that could ever end it.
     */
    fun cancelLoading() {
        abandonActiveRequest()
    }

    /**
     * Ends the active request without starting a successor, publishing [LyricsOutcome.Cancelled]
     * so nothing is left loading. No-op when no request is in flight.
     */
    private fun abandonActiveRequest() {
        val requestId = activeRequestId
        if (requestId == 0L) return
        loadingJob?.cancel()
        loadingJob = null
        settle(requestId, LyricsOutcome.Cancelled)
    }

    private fun beginRequest(songId: String): Long {
        requestSequence += 1
        activeRequestId = requestSequence
        activeRequestSongId = songId
        return requestSequence
    }

    /**
     * Publishes [outcome] if [requestId] still owns the state, then releases ownership.
     * A superseded request is silently dropped: the newer request owns the surface now.
     */
    private fun settle(requestId: Long, outcome: LyricsOutcome) {
        if (requestId != activeRequestId) return
        val songId = activeRequestSongId
        activeRequestId = 0L
        activeRequestSongId = null
        if (songId == null) return
        loadCallback?.onLyricsSettled(requestId, songId, outcome)
    }

    /**
     * Set sync offset for a song.
     */
    fun setSyncOffset(songId: String, offsetMs: Int) {
        appScope.launch {
            userPreferencesRepository.setLyricsSyncOffset(songId, offsetMs)
            _currentSongSyncOffset.value = offsetMs
        }
    }

    /**
     * Update sync offset from song ID (called when song changes).
     */
    suspend fun updateSyncOffsetForSong(songId: String) {
        val offset = userPreferencesRepository.getLyricsSyncOffset(songId)
        _currentSongSyncOffset.value = offset
    }

    /**
     * Reset the lyrics search state to idle.
     */
    fun resetSearchState() {
        _searchUiState.value = LyricsSearchUiState.Idle
    }

    /**
     * Fetch lyrics for the given song, respecting the user's source preference.
     */
    fun fetchLyricsForSong(
        song: Song,
        forcePickResults: Boolean,
        sourcePreference: LyricsSourcePreference,
        contextHelper: (Int) -> String
    ) {
        // This takes over from any resolve in flight. It must end that resolve rather than just
        // cancel it — a cancelled resolve would otherwise leave the player state in Loading with
        // no owner, which is exactly the permanent spinner this replaces.
        abandonActiveRequest()
        loadingJob = appScope.launch {
            _searchUiState.value = LyricsSearchUiState.Loading

            if (!forcePickResults) {
                val storedLyrics = withContext(dispatchers.io) {
                    musicRepository.getStoredLyrics(song)
                }
                if (storedLyrics != null) {
                    val (lyrics, rawLyrics) = storedLyrics
                    _searchUiState.value = LyricsSearchUiState.Success(lyrics)
                    _songUpdates.emit(song.withPersistedLyrics(rawLyrics, refreshedAlbumArtUri = null) to lyrics)
                    _messageEvents.emit(contextHelper(R.string.lyrics_already_available))
                    return@launch
                }
            }

            val localSourceChecks: List<suspend () -> Pair<String, Int>?> = when (sourcePreference) {
                LyricsSourcePreference.API_FIRST -> emptyList()
                LyricsSourcePreference.EMBEDDED_FIRST -> listOf(
                    { readEmbeddedLyricsFromFile(song)?.let { it to R.string.lyrics_embedded_already_available } },
                    { readLocalLyricsFile(song)?.let { it to R.string.local_lrc_already_available } }
                )
                LyricsSourcePreference.LOCAL_FIRST -> listOf(
                    { readLocalLyricsFile(song)?.let { it to R.string.local_lrc_already_available } },
                    { readEmbeddedLyricsFromFile(song)?.let { it to R.string.lyrics_embedded_already_available } }
                )
            }

            for (sourceCheck in localSourceChecks) {
                val result = withContext(dispatchers.io) { sourceCheck() }
                if (result != null) {
                    val (rawLyrics, messageResId) = result
                    val parsed = LyricsUtils.parseLyrics(rawLyrics)
                    if (hasValidLyrics(parsed)) {
                        val lyrics = parsed.copy(areFromRemote = false)
                        _searchUiState.value = LyricsSearchUiState.Success(lyrics)

                        val songId = song.id.toLongOrNull()
                        if (songId != null) {
                            musicRepository.updateLyrics(songId, rawLyrics)
                        }

                        _songUpdates.emit(song.copy(lyrics = rawLyrics) to lyrics)
                        _messageEvents.emit(contextHelper(messageResId))
                        return@launch
                    }
                }
            }

            if (forcePickResults) {
                musicRepository.searchRemoteLyrics(song)
                    .onSuccess { (query, results) ->
                        _searchUiState.value = LyricsSearchUiState.PickResult(query, results.toImmutableList())
                    }
                    .onFailure { error ->
                        handleError(error)
                    }
            } else {
                musicRepository.getLyricsFromRemote(song)
                    .onSuccess { (lyrics, rawLyrics) ->
                        _searchUiState.value = LyricsSearchUiState.Success(lyrics)
                        val refreshedAlbumArtUri = persistLyricsToFileMetadataIfPossible(song, rawLyrics)
                        val updatedSong = song.withPersistedLyrics(rawLyrics, refreshedAlbumArtUri)
                        _songUpdates.emit(updatedSong to lyrics)
                    }
                    .onFailure { error ->
                        if (error is NoLyricsFoundException) {
                            musicRepository.searchRemoteLyrics(song)
                                .onSuccess { (query, results) ->
                                    _searchUiState.value = LyricsSearchUiState.PickResult(query, results.toImmutableList())
                                }
                                .onFailure { searchError -> handleError(searchError) }
                        } else {
                            handleError(error)
                        }
                    }
            }
        }
    }

    /**
     * Manual search by query.
     */
    fun searchLyricsManually(title: String, artist: String?) {
        if (title.isBlank()) return
        // Same contract as fetchLyricsForSong: end the in-flight resolve, do not merely cancel it.
        abandonActiveRequest()
        loadingJob = appScope.launch {
            _searchUiState.value = LyricsSearchUiState.Loading
            musicRepository.searchRemoteLyricsByQuery(title, artist)
                .onSuccess { (q, results) ->
                    _searchUiState.value = LyricsSearchUiState.PickResult(q, results.toImmutableList())
                }
                .onFailure { error -> handleError(error) }
        }
    }

    /**
     * Accept a search result.
     */
    fun acceptLyricsSearchResult(result: LyricsSearchResult, currentSong: Song) {
        appScope.launch {
            _searchUiState.value = LyricsSearchUiState.Success(result.lyrics)

            currentSong.id.toLongOrNull()?.let { songId ->
                musicRepository.updateLyrics(songId, result.rawLyrics)
            }

            val refreshedAlbumArtUri = persistLyricsToFileMetadataIfPossible(currentSong, result.rawLyrics)
            val updatedSong = currentSong.withPersistedLyrics(result.rawLyrics, refreshedAlbumArtUri)

            _songUpdates.emit(updatedSong to result.lyrics)
        }
    }

    /**
     * Import from file.
     */
    fun importLyricsFromFile(songId: Long, validatedImport: ValidatedLyricsImport, currentSong: Song?) {
        appScope.launch {
            val sanitizedContent = validatedImport.sanitizedContent
            val parsedLyrics = validatedImport.parsedLyrics

            musicRepository.updateLyrics(songId, sanitizedContent)

            if (currentSong != null && currentSong.id.toLongOrNull() == songId) {
                val refreshedAlbumArtUri = persistLyricsToFileMetadataIfPossible(currentSong, sanitizedContent)
                val updatedSong = currentSong.withPersistedLyrics(sanitizedContent, refreshedAlbumArtUri)
                _songUpdates.emit(updatedSong to parsedLyrics.takeIf(::hasValidLyrics))
            }

            _messageEvents.emit("Lyrics imported successfully!")
        }
    }

    fun resetLyrics(songId: Long) {
        resetSearchState()
        appScope.launch {
            musicRepository.resetLyrics(songId)
            _songUpdates.emit(Song.emptySong().copy(id = songId.toString()) to null)
        }
    }

    fun resetAllLyrics() {
        resetSearchState()
        appScope.launch {
            musicRepository.resetAllLyrics()
        }
    }

    private fun handleError(error: Throwable) {
        _searchUiState.value = if (error is NoLyricsFoundException) {
            LyricsSearchUiState.NotFound("Lyrics not found")
        } else {
            LyricsSearchUiState.Error(error.message ?: "Unknown error")
        }
    }

    private fun hasValidLyrics(lyrics: Lyrics?): Boolean {
        if (lyrics == null) return false
        return !lyrics.synced.isNullOrEmpty() || !lyrics.plain.isNullOrEmpty()
    }

    private fun readEmbeddedLyricsFromFile(song: Song): String? {
        song.lyrics
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return runCatching {
            AudioMetadataReader.read(File(song.path))
                ?.lyrics
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun readLocalLyricsFile(song: Song): String? {
        return runCatching {
            val songFile = File(song.path)
            val directory = songFile.parentFile ?: return@runCatching null
            for (extension in LyricsImportSecurity.supportedFileExtensions()) {
                val lyricsFile = File(directory, "${songFile.nameWithoutExtension}.$extension")
                if (!lyricsFile.exists() || !lyricsFile.canRead()) continue

                when (val validation = LyricsImportSecurity.validateLocalLyricsFile(lyricsFile)) {
                    is LyricsImportValidationResult.Valid -> return@runCatching validation.value.sanitizedContent
                    is LyricsImportValidationResult.Invalid -> continue
                }
            }
            null
        }.getOrNull()
    }

    private suspend fun persistLyricsToFileMetadataIfPossible(song: Song, rawLyrics: String): String? {
        val songId = song.id.toLongOrNull() ?: return null
        val normalizedLyrics = rawLyrics.trim()
        if (normalizedLyrics.isBlank()) return null

        return withContext(dispatchers.io) {
            val existingArtwork = runCatching {
                AudioMetadataReader.read(File(song.path))?.artwork
            }.getOrNull()

            val coverArtUpdate = existingArtwork?.let { artwork ->
                CoverArtUpdate(
                    bytes = artwork.bytes,
                    mimeType = artwork.mimeType ?: "image/jpeg"
                )
            }

            runCatching {
                songMetadataEditor.editSongMetadata(
                    songId = songId,
                    newTitle = song.title,
                    newArtist = song.artist,
                    newAlbum = song.album,
                    newGenre = song.genre ?: "",
                    newLyrics = normalizedLyrics,
                    newTrackNumber = song.trackNumber,
                    newDiscNumber = song.discNumber,
                    coverArtUpdate = coverArtUpdate
                )
            }.getOrNull()?.updatedAlbumArtUri
        }
    }

}

internal fun Song.withPersistedLyrics(rawLyrics: String, refreshedAlbumArtUri: String?): Song {
    return copy(
        lyrics = rawLyrics,
        albumArtUriString = refreshedAlbumArtUri ?: albumArtUriString
    )
}
