package com.lostf1sh.pixelplayeross.data.image

import android.content.Context
import coil.imageLoader
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import com.lostf1sh.pixelplayeross.data.media.ArtworkCacheKeys
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import java.util.UUID

/** Persistent per-song generations keep late Coil results out of subsequent requests, including after restart. */
// KTX edit discards the commit result; durable invalidation must detect write failures.
@android.annotation.SuppressLint("UseKtx")
internal object LocalArtworkCache {
    private fun prefs(context: Context) = context.getSharedPreferences("artwork_generations", Context.MODE_PRIVATE)

    fun generation(context: Context, songId: Long): String = prefs(context).getString(songId.toString(), "0") ?: "0"

    fun invalidate(context: Context, songId: Long) {
        check(prefs(context).edit().putString(songId.toString(), UUID.randomUUID().toString()).commit())
        val loader = context.imageLoader
        val matching = loader.memoryCache?.keys.orEmpty().filter { ArtworkCacheKeys.belongsToSong(it.key, songId) }
        matching.forEach { loader.memoryCache?.remove(it) }
        val uri = LocalArtworkUri.buildSongUri(songId)
        (ArtworkCacheKeys.keysFor(uri) + matching.map { it.key }).distinct().forEach { loader.diskCache?.remove(it) }
    }
}

/** Applies to explicit size keys, default Coil keys and prefetched artwork alike. */
internal class LocalArtworkGenerationInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val original = chain.request
        val songId = LocalArtworkUri.parseSongId(original.data.toString()) ?: return chain.proceed(original)
        while (true) {
            val generation = LocalArtworkCache.generation(original.context, songId)
            val base = original.memoryCacheKey?.key ?: "${original.data}_${chain.size}"
            val request = original.newBuilder()
                .memoryCacheKey(coil.memory.MemoryCache.Key("${base}_rev_$generation", original.memoryCacheKey?.extras.orEmpty()))
                .placeholderMemoryCacheKey(null as coil.memory.MemoryCache.Key?)
                // LocalArtworkCoilFetcher owns disk storage; never serve an older Coil disk copy.
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
            val result = chain.proceed(request)
            if (generation == LocalArtworkCache.generation(original.context, songId)) return result
            // A decode that finished after invalidation may just have inserted its old bitmap.
            // Remove that exact key (including Coil extras) before retrying the new generation.
            if (result is coil.request.SuccessResult) {
                result.memoryCacheKey?.let { original.context.imageLoader.memoryCache?.remove(it) }
            }
        }
    }
}
