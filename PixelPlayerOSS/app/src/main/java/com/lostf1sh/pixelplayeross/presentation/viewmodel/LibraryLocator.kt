package com.lostf1sh.pixelplayeross.presentation.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A song another page (Stats, a collection) wants shown in the Library (B32-Q2), held until the Library's Songs list can answer it.
 *
 * It is a *request* that waits, not an event: the Library may be on another tab and its Songs list not composed yet, so an event emitted
 * at once would be lost. The Songs list takes the request when it has rows ([take]), is told where the song is in the list as shown
 * (the same rule the "locate current song" button uses), and the row is marked for [markMs]. Nothing here plays anything.
 *
 * **The request stays published, and so stays the key of the effect that is serving it, until that effect has finished** ([complete]
 * after its scroll). It used to be cleared by [take] itself, before the query had answered: Compose saw the key go null and cancelled the
 * effect in the middle of its own query, and the cancellation was read as "the song is not in the list" (R32-01, seen on the phone as the
 * Library opening at the top with a false notice). Now:
 *  - the query is checked against the request again when it answers: a request that was cleared (Back, a tab pressed) or replaced by a
 *    newer one meanwhile publishes no mark and says nothing;
 *  - a cancellation is never an answer: it propagates, and the request stays for whoever serves it next (the Songs list leaving and
 *    coming back, say) until [clear] or [complete];
 *  - one the Songs list never takes (the Songs tab hidden, an empty library) is dropped by [clear] when the page that asked is left.
 */
class LibraryLocator(
    private val scope: CoroutineScope,
    private val markMs: Long = DEFAULT_MARK_MS,
    /** Where [songId] is in the Songs list as shown, or -1 when it is not in it. */
    private val resolveIndex: suspend (songId: String) -> Int,
    /** The song is not in the list: the listener is told. */
    private val onNotFound: () -> Unit,
) {
    data class Request(val songId: String, val token: Long)

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    private val _located = MutableStateFlow<String?>(null)

    /** The song the Library last scrolled to for another page, while it is marked. */
    val located: StateFlow<String?> = _located.asStateFlow()

    private var token = 0L
    private var markJob: Job? = null

    fun request(songId: String) {
        // the song asked for before is no longer what this page is showing: its mark does not outlive the new request
        markJob?.cancel()
        _located.value = null
        _request.value = Request(songId, ++token)
    }

    /**
     * The Songs list asks where the requested song is: its index (and the song is marked), or -1 (the song is not in the list, which is
     * said once and ends the request; or the request is not the current one any more, which is not said). The request stays published
     * until [complete]. A cancellation of the caller is not an answer and propagates.
     */
    suspend fun take(request: Request): Int {
        if (_request.value?.token != request.token) return -1
        val index = try {
            resolveIndex(request.songId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            -1
        }
        // the query may have been long: cleared (Back) or replaced (a newer song) while it ran means nothing is published and nothing said
        if (_request.value?.token != request.token) return -1
        if (index < 0) {
            _request.value = null
            onNotFound()
            return -1
        }
        _located.value = request.songId
        markJob?.cancel()
        markJob = scope.launch {
            delay(markMs)
            if (_located.value == request.songId) _located.value = null
        }
        return index
    }

    /** The list has scrolled to the song for [request]: the request is spent. A newer request, or none, is left as it is. */
    fun complete(request: Request) {
        if (_request.value?.token == request.token) _request.value = null
    }

    /** The page that asked is gone (Back, or a tab pressed): whatever was pending or marked for it goes too. */
    fun clear() {
        _request.value = null
        markJob?.cancel()
        _located.value = null
    }

    companion object {
        /** How long the Library keeps a song it scrolled to for another page marked. */
        const val DEFAULT_MARK_MS = 6_000L
    }
}
