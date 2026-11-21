package com.lostf1sh.pixelplayeross.data.service

import androidx.media3.common.MediaItem

internal data class TrustedMediaItemsResolution(
    val mediaItems: MutableList<MediaItem>,
    val trustedArtworkGrantItems: List<MediaItem>,
)

internal fun resolveMediaItemsWithTrustedArtworkGrants(
    requestedItems: List<MediaItem>,
    preservePlaybackOrigin: Boolean = false,
    trustedItemResolver: (String) -> MediaItem?
): TrustedMediaItemsResolution {
    val resolvedItems = ArrayList<MediaItem>(requestedItems.size)
    val trustedArtworkGrantItems = ArrayList<MediaItem>()

    requestedItems.forEach { requestedItem ->
        val trustedItem = trustedItemResolver(requestedItem.mediaId)
        if (trustedItem != null) {
            // An ID may occur in several source blocks; copy from this exact request occurrence.
            // Only the app's own controller may supply provenance, never an external raw-id request.
            resolvedItems += if (preservePlaybackOrigin) {
                com.lostf1sh.pixelplayeross.data.model.PlaybackOrigin.attach(
                    trustedItem, com.lostf1sh.pixelplayeross.data.model.PlaybackOrigin.from(requestedItem)
                )
            } else trustedItem
            trustedArtworkGrantItems += trustedItem
        } else {
            resolvedItems += requestedItem
        }
    }

    return TrustedMediaItemsResolution(
        mediaItems = resolvedItems,
        trustedArtworkGrantItems = trustedArtworkGrantItems
    )
}
