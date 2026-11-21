package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only the matching page consumes a token, after its rows have loaded and scrolling has finished. */
object SourceLocateRequests {
    data class Request(val route: String, val songId: String, val source: ListenSource?, val token: Long, val atMs: Long)
    private val mutable = MutableStateFlow<Request?>(null)
    val request = mutable.asStateFlow()
    private var counter = 0L
    @Synchronized fun request(route: String, songId: String, source: ListenSource?) {
        mutable.value = Request(route, songId, source, ++counter, System.currentTimeMillis())
    }
    @Synchronized fun consume(token: Long) { if (mutable.value?.token == token) mutable.value = null }
}
