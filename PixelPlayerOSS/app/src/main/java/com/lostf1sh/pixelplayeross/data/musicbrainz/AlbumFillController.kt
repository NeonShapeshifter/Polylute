package com.lostf1sh.pixelplayeross.data.musicbrainz

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the "Fill from MusicBrainz" flow is (B57 1.3). */
sealed interface AlbumFillState {
    data object Idle : AlbumFillState
    data object Searching : AlbumFillState

    /** The files carry no release id: the listener chooses which release is theirs. */
    data class PickRelease(val candidates: List<MusicBrainzReleaseCandidate>) : AlbumFillState

    /** The release against the form, field by field; [accepted] are the checked boxes. */
    data class Diff(val release: MusicBrainzRelease, val proposals: List<FieldProposal>, val accepted: Set<String>) : AlbumFillState

    data object NoMatch : AlbumFillState
    data class Failed(val message: String) : AlbumFillState
}

/**
 * Drives the album editor's MusicBrainz fill. The MBIDs the files already carry (B52, `songs.mb_release_id`) are used when present; otherwise the album is
 * searched by album + artist and the listener picks the release. Each step is one rate-limited request ([MusicBrainzApiService]); nothing here writes a file.
 */
class AlbumFillController(private val source: MusicBrainzReleaseSource, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<AlbumFillState>(AlbumFillState.Idle)
    val state: StateFlow<AlbumFillState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(form: AlbumFormState, releaseIdsOfSongs: List<String?>, artistForSearch: String) {
        job?.cancel()
        _state.value = AlbumFillState.Searching
        job = scope.launch {
            try {
                val known = AlbumFill.knownReleaseId(releaseIdsOfSongs)
                if (known != null) {
                    showDiff(form, source.lookupRelease(known))
                } else {
                    val found = source.searchReleases(form.album, artistForSearch)
                    _state.value = if (found.isEmpty()) AlbumFillState.NoMatch else AlbumFillState.PickRelease(found)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.value = AlbumFillState.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                _state.value = AlbumFillState.Failed(e.message.orEmpty())
            }
        }
    }

    fun choose(candidate: MusicBrainzReleaseCandidate, form: AlbumFormState) {
        job?.cancel()
        _state.value = AlbumFillState.Searching
        job = scope.launch {
            try {
                showDiff(form, source.lookupRelease(candidate.releaseId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = AlbumFillState.Failed(e.message.orEmpty())
            }
        }
    }

    private fun showDiff(form: AlbumFormState, release: MusicBrainzRelease?) {
        if (release == null) { _state.value = AlbumFillState.NoMatch; return }
        val proposals = AlbumFill.propose(form, release)
        // Nothing is checked until the listener checks it: the diff is a suggestion.
        _state.value = if (proposals.isEmpty()) AlbumFillState.NoMatch else AlbumFillState.Diff(release, proposals, accepted = emptySet())
    }

    fun toggle(id: String) {
        val current = _state.value as? AlbumFillState.Diff ?: return
        _state.value = current.copy(accepted = if (id in current.accepted) current.accepted - id else current.accepted + id)
    }

    fun setAll(checked: Boolean) {
        val current = _state.value as? AlbumFillState.Diff ?: return
        _state.value = current.copy(accepted = if (checked) current.proposals.mapTo(LinkedHashSet()) { it.id } else emptySet())
    }

    fun dismiss() {
        job?.cancel()
        _state.value = AlbumFillState.Idle
    }
}
