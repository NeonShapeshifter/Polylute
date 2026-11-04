package com.lostf1sh.pixelplayeross.presentation.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * B57 2.4: the title tap in the full player opens **the song's album page with that song highlighted and scrolled into view**.
 *
 * Why the second tap used to do nothing (B56 phase 4, found on the HONOR, no log line): the Library destination cleared its locate request when it left composition
 * (`DisposableEffect { onDispose { clearLibraryLocate() } }`). Opening the same destination again composes the new instance first (it asks), and the old one leaves afterwards
 * (it clears): the request was gone before the Songs tab saw it. Here a request is **never cleared by a destination leaving**: it carries a unique [Request.token], the page that
 * shows it consumes exactly that token once it has scrolled, and a request nobody took within [TTL_MS] is stale and ignored.
 */
object AlbumLocateRequests {
    data class Request(val albumId: Long, val songId: String, val token: Long, val atMs: Long)

    const val TTL_MS = 5_000L

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()
    private var counter = 0L

    @Synchronized
    fun request(albumId: Long, songId: String, nowMs: Long = System.currentTimeMillis()) {
        _request.value = Request(albumId, songId, ++counter, nowMs)
    }

    /** Takes the request away only if it is still the one the caller handled (a newer one is left for its own page). */
    @Synchronized
    fun consume(token: Long) {
        if (_request.value?.token == token) _request.value = null
    }

    fun isFresh(request: Request, nowMs: Long = System.currentTimeMillis()) = nowMs - request.atMs in 0..TTL_MS

    /**
     * Where [songId] is in the album page's list: the page shows an optional description item, then each disc's songs, each disc preceded by a header when the album has more than
     * one. Returns the item index, or -1 when the song is not in the album.
     */
    fun listIndexOf(songId: String, discs: List<List<String>>, hasAbout: Boolean): Int {
        var index = if (hasAbout) 1 else 0
        for (disc in discs) {
            if (discs.size > 1) index++
            val at = disc.indexOf(songId)
            if (at >= 0) return index + at
            index += disc.size
        }
        return -1
    }
}

/** A title tap in the player: the song, and the album it belongs to (0 when unknown, which falls back to the Library's Songs list). */
data class SongLocateRequest(val songId: String, val albumId: Long)
