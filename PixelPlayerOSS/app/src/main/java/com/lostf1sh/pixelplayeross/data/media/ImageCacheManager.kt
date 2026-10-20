package com.lostf1sh.pixelplayeross.data.media

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.memory.MemoryCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @OptIn(ExperimentalCoilApi::class)
    fun invalidateCoverArtCaches(vararg uriStrings: String?) {
        val imageLoader = context.imageLoader
        val memoryCache = imageLoader.memoryCache
        val diskCache = imageLoader.diskCache

        uriStrings.mapNotNull { it?.takeIf(String::isNotBlank) }.forEach { baseUri ->
            if (com.lostf1sh.pixelplayeross.utils.LocalArtworkUri.isLocalArtworkUri(baseUri)) {
                com.lostf1sh.pixelplayeross.utils.LocalArtworkUri.parseSongId(baseUri)?.let { songId ->
                    com.lostf1sh.pixelplayeross.utils.AlbumArtUtils.clearCacheForSong(context, songId)
                }
            }

            // Every size variant, from the one place that knows what they are. The list used to be
            // written out here, and it had drifted behind the call sites: it was missing the 2048
            // default and the 96 compact-list size, so an edited cover kept its old picture in
            // exactly the places a user is most likely to be looking at.
            val metrics = context.resources.displayMetrics
            (ArtworkCacheKeys.keysFor(baseUri, listOf(metrics.widthPixels, metrics.heightPixels)) +
                memoryCache?.keys.orEmpty().filter { it.key == baseUri || it.key.startsWith("${baseUri}_") }.map { it.key }).distinct().forEach { cacheKey ->
                memoryCache?.remove(MemoryCache.Key(cacheKey))
                diskCache?.remove(cacheKey)
            }
        }
    }
}
