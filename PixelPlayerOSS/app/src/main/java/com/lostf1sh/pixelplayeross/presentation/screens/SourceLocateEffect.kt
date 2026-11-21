package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SourceLocateRequests
import kotlinx.coroutines.delay

/** Same raised panel and lifetime as LibraryLocator; absence opens the source at its top. */
@Composable
internal fun rememberSourceLocate(route: String, listState: LazyListState, ready: Boolean = true,
    indexOf: suspend (String) -> Int): String? {
    val pending by SourceLocateRequests.request.collectAsStateWithLifecycle()
    val resolve by rememberUpdatedState(indexOf)
    var located by remember(route) { mutableStateOf<String?>(null) }
    // Do not key this effect by pending: consuming a request must not cancel its highlight timer.
    val token = pending?.takeIf { it.route == route }?.token
    LaunchedEffect(token, ready) {
        located = null
        val request = pending?.takeIf { it.token == token && it.route == route } ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (System.currentTimeMillis() - request.atMs !in 0..30_000L) {
            SourceLocateRequests.consume(request.token)
            return@LaunchedEffect
        }
        val index = resolve(request.songId)
        if (SourceLocateRequests.request.value?.token != request.token) return@LaunchedEffect
        located = request.songId.takeIf { index >= 0 }
        listState.scrollToItem(index.coerceAtLeast(0))
        // Consume after the mark's lifetime, keeping this effect stable across the scroll.
        delay(com.lostf1sh.pixelplayeross.presentation.viewmodel.LibraryLocator.DEFAULT_MARK_MS)
        located = null
        SourceLocateRequests.consume(request.token)
    }
    return located
}
