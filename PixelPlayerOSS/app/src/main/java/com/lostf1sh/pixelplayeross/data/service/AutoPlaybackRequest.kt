package com.lostf1sh.pixelplayeross.data.service

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession

/** null delegates an ordinary raw-id request to the existing trusted resolver. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun resolveAutoPlaybackRequest(requests: List<MediaItem>, catalog: AutoMediaCatalog,
    resume: suspend () -> MediaSession.MediaItemsWithStartPosition): MediaSession.MediaItemsWithStartPosition? {
    val request = requests.singleOrNull() ?: return null
    val query = request.requestMetadata.searchQuery
    if (query != null && query.isBlank()) return resume()
    val id = if (query != null) AutoMediaId("search", query.trim()).toString()
        else request.mediaId.takeIf { AutoMediaId.parse(it) != null } ?: return null
    val queue = catalog.expand(id)
    return MediaSession.MediaItemsWithStartPosition(queue?.items.orEmpty(), queue?.startIndex ?: 0, 0L)
}
