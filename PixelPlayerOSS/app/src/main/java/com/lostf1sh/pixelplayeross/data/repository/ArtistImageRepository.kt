package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.utils.NetworkRetryUtils
import com.lostf1sh.pixelplayeross.utils.isRetryableNetworkError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import androidx.core.graphics.scale

/**
 * Artist pictures: the listener's own, else one downloaded from Wikimedia Commons (B40-F4, when the switch is on), else none.
 *
 * Deezer is gone (B40-F4): its client, its switch and its requests are removed, and **a picture address stored from Deezer is not shown or
 * kept**: the app clears every stored address that points at the internet at its start ([retireDeezerOnce]). A picture
 * downloaded now is a file in the app's music-info folder and its path is what is stored; "Delete downloaded data" removes both.
 * Uses an in-memory LRU cache and Room for the stored path.
 */
@Singleton
class ArtistImageRepository @Inject constructor(
    private val musicInfo: MusicInfoRepository,
    private val musicDao: MusicDao,
    private val userPreferencesRepository: UserPreferencesRepository
) {
    companion object {
        private const val TAG = "ArtistImageRepository"
        private const val CACHE_SIZE = 100
        private const val PREFETCH_CONCURRENCY = 3
        private const val NETWORK_RETRY_ATTEMPTS = 3
        private const val NETWORK_RETRY_INITIAL_DELAY_MS = 500L
        private const val MAX_CUSTOM_IMAGE_SOURCE_BYTES = 24L * 1024L * 1024L
        private const val MAX_CUSTOM_IMAGE_DIMENSION_PX = 16_384
        private const val MAX_CUSTOM_IMAGE_TOTAL_PIXELS = 80_000_000L
        private const val TARGET_CUSTOM_IMAGE_MAX_DIMENSION_PX = 2_048
        private const val TARGET_CUSTOM_IMAGE_MAX_PIXELS = 4_194_304L

        internal fun calculateCustomImageSampleSize(width: Int, height: Int): Int {
            var sampleSize = 1
            while (
                width / sampleSize > TARGET_CUSTOM_IMAGE_MAX_DIMENSION_PX ||
                height / sampleSize > TARGET_CUSTOM_IMAGE_MAX_DIMENSION_PX ||
                (width.toLong() / sampleSize) * (height.toLong() / sampleSize) > TARGET_CUSTOM_IMAGE_MAX_PIXELS
            ) {
                sampleSize = sampleSize shl 1
            }
            return sampleSize.coerceAtLeast(1)
        }
    }

    private val memoryCache = LruCache<String, String>(CACHE_SIZE)
    
    private val fetchMutex = Mutex()
    private val pendingFetches = mutableSetOf<String>()
    
    private val prefetchSemaphore = Semaphore(PREFETCH_CONCURRENCY)
    
    private val failedFetches = mutableSetOf<String>()

    @Volatile
    private var deezerRetired = false

    /**
     * The artist's downloaded picture, from the phone if there is one, else (with the switch on) from Wikimedia Commons.
     * @param artistName Name of the artist
     * @param artistId Room database ID of the artist (for caching)
     * @return The picture's file path, or null if there is none
     */
    suspend fun getArtistImageUrl(artistName: String, artistId: Long): String? {
        if (artistName.isBlank()) return null
        retireDeezerOnce()

        val normalizedName = artistName.trim().lowercase()

        memoryCache.get(normalizedName)?.let { cachedUrl ->
            if (File(cachedUrl).isFile) { musicInfo.noteArtistPhotoUse(artistName, cachedUrl); return cachedUrl }
            memoryCache.remove(normalizedName)
        }

        val (resolvedArtistId, dbCachedUrl) = withContext(Dispatchers.IO) {
            val canonicalArtistId = musicDao.getArtistIdByNormalizedName(artistName) ?: artistId
            val cachedUrl = musicDao.getArtistImageUrl(canonicalArtistId)
                ?: musicDao.getArtistImageUrlByNormalizedName(artistName)
            canonicalArtistId to cachedUrl
        }
        // A stored address that points at the internet is Deezer's and is not shown (B40-F4); a stored path is shown if the file is there.
        if (!dbCachedUrl.isNullOrEmpty() && !isRemote(dbCachedUrl) && File(dbCachedUrl).isFile) {
            memoryCache.put(normalizedName, dbCachedUrl)
            musicInfo.noteArtistPhotoUse(artistName, dbCachedUrl)
            return dbCachedUrl
        }

        // Offline first: a picture downloaded earlier is used whatever the switch says now.
        musicInfo.service.cachedArtistPhoto(artistName)?.let { photo ->
            memoryCache.put(normalizedName, photo.path)
            withContext(Dispatchers.IO) { musicDao.updateArtistImageUrl(resolvedArtistId, photo.path) }
            return photo.path
        }

        if (failedFetches.contains(normalizedName)) return null
        if (!userPreferencesRepository.musicInfoArtistPhotosEnabledFlow.first()) return null
        return fetchAndCacheArtistImage(artistName, resolvedArtistId, normalizedName)
    }

    /**
     * Stored picture addresses that point at the internet (Deezer's) are cleared: on the app's first start of this version they all go
     * (the update is a no-op afterwards), and the read path above never shows one in the meantime.
     */
    suspend fun retireDeezerOnce() {
        musicInfo.repairBrokenImagesOnce()
        if (deezerRetired) return
        withContext(Dispatchers.IO) {
            val cleared = musicDao.clearRemoteArtistImages()
            if (cleared > 0) Timber.tag(TAG).i("Deezer is gone: %d stored artist picture address(es) cleared", cleared)
        }
        deezerRetired = true
    }

    fun invalidateDownloaded(name: String) {
        val key = name.trim().lowercase()
        memoryCache.remove(key)
        failedFetches.remove(key)
    }

    fun notePlayingSong(song: com.lostf1sh.pixelplayeross.data.model.Song) { musicInfo.requestSongInfo(song) }

    private fun isRemote(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

    /**
     * Prefetch artist images for a list of artists in background.
     * Useful for batch loading when displaying artist lists.
     */
    suspend fun prefetchArtistImages(artists: List<Pair<Long, String>>) = withContext(Dispatchers.IO) {
        if (!userPreferencesRepository.musicInfoArtistPhotosEnabledFlow.first()) return@withContext

        artists.chunked(PREFETCH_CONCURRENCY * 4).forEach { chunk ->
            chunk.map { (artistId, artistName) ->
                async {
                    try {
                        val normalizedName = artistName.trim().lowercase()
                        if (memoryCache.get(normalizedName) == null && !failedFetches.contains(normalizedName)) {
                            prefetchSemaphore.withPermit {
                                getArtistImageUrl(artistName, artistId)
                            }
                        } else {
                            Timber.tag(TAG).d("Skipping prefetch for $artistName")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w("Failed to prefetch image for $artistName: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }
    
    private suspend fun fetchAndCacheArtistImage(
        artistName: String,
        artistId: Long,
        normalizedName: String
    ): String? {
        fetchMutex.withLock {
            if (pendingFetches.contains(normalizedName)) {
                return null
            }
            pendingFetches.add(normalizedName)
        }

        return try {
            withContext(Dispatchers.IO) {
                val photo = musicInfo.service.fetchArtistPhoto(artistName)
                if (photo != null) {
                    memoryCache.put(normalizedName, photo.path)
                    musicDao.updateArtistImageUrl(artistId, photo.path)
                    Timber.tag(TAG).d("Downloaded and cached a picture for $artistName")
                    photo.path
                } else {
                    Timber.tag(TAG).d("No picture found for: $artistName")
                    failedFetches.add(normalizedName)
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e("Error fetching artist image for $artistName: ${e.message}")
            if(e !is java.net.SocketTimeoutException) {
                failedFetches.add(normalizedName)
            }
            null
        } finally {
            fetchMutex.withLock {
                pendingFetches.remove(normalizedName)
            }
        }
    }

    private suspend fun <T> withNetworkRetry(
        operationName: String,
        maxAttempts: Int = NETWORK_RETRY_ATTEMPTS,
        initialDelayMs: Long = NETWORK_RETRY_INITIAL_DELAY_MS,
        block: suspend () -> T
    ): T {
        return NetworkRetryUtils.withNetworkRetry(
            operationName = operationName,
            maxAttempts = maxAttempts,
            initialDelayMs = initialDelayMs,
            shouldRetry = { throwable -> throwable.isRetryableNetworkError() },
            onRetry = { attempt, attempts, throwable ->
                Timber.tag(TAG)
                    .d("Retrying $operationName after failure ($attempt/$attempts): ${throwable.message}")
            },
            block = block
        )
    }
    
    /**
     * Clear all cached images. Useful for debugging or forced refresh.
     */
    fun clearCache() {
        memoryCache.evictAll()
        failedFetches.clear()
    }

    /**
     * Returns the effective image URL for an artist:
     * - If a custom (user-set) image exists in DB → returns that path
     * - Otherwise falls back to the downloaded picture (fetching it if the switch is on)
     */
    suspend fun getEffectiveArtistImageUrl(artistId: Long, artistName: String): String? {
        val customUri = withContext(Dispatchers.IO) { musicDao.getArtistCustomImage(artistId) }
        if (!customUri.isNullOrBlank()) return customUri
        return getArtistImageUrl(artistName, artistId)
    }

    /**
     * Saves a user-selected image as the artist's custom image.
     *
     * The content URI is resolved immediately and the bitmap is written to
     * internal storage (filesDir/artist_art_<id>.jpg). This avoids depending
     * on a content URI that may expire once the photo-picker dismisses.
     *
     * @param context Application context (used for contentResolver and filesDir)
     * @param artistId The artist's database row ID
     * @param sourceUri URI returned by the system photo-picker
     * @return The internal file path on success, null on failure
     */
    suspend fun setCustomArtistImage(context: Context, artistId: Long, sourceUri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = decodeCustomArtistBitmap(context, sourceUri) ?: return@withContext null
                val scaledBitmap = scaleBitmapIfNeeded(bitmap)
                try {
                    val destFile = File(context.filesDir, "artist_art_${artistId}.jpg")
                    FileOutputStream(destFile).use { out ->
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }

                    val internalPath = destFile.absolutePath

                    musicDao.updateArtistCustomImage(artistId, internalPath)

                    Timber.tag(TAG).d("Custom artist image saved: $internalPath")
                    internalPath
                } finally {
                    if (scaledBitmap !== bitmap) {
                        bitmap.recycle()
                    }
                    scaledBitmap.recycle()
                }
            } catch (e: Exception) {
                Timber.tag(TAG)
                    .e("Failed to save custom artist image for id=$artistId: ${e.message}")
                null
            }
        }
    }

    private fun decodeCustomArtistBitmap(context: Context, sourceUri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(sourceUri)?.lowercase()
        if (mimeType != null && !mimeType.startsWith("image/")) {
            Timber.tag(TAG).w("Rejected custom artist image with unsupported MIME type: $mimeType")
            return null
        }

        runCatching { resolver.openAssetFileDescriptor(sourceUri, "r") }.getOrNull()?.use { descriptor ->
            val declaredLength = descriptor.length
            if (declaredLength > MAX_CUSTOM_IMAGE_SOURCE_BYTES) {
                Timber.tag(TAG)
                    .w("Rejected custom artist image larger than allowed source size: $declaredLength")
                return null
            }
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        
        resolver.openInputStream(sourceUri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, bounds)
        }

        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            Timber.tag(TAG)
                .w("Rejected custom artist image with invalid bounds: ${width}x${height}")
            return null
        }
        if (width > MAX_CUSTOM_IMAGE_DIMENSION_PX || height > MAX_CUSTOM_IMAGE_DIMENSION_PX) {
            Timber.tag(TAG)
                .w("Rejected custom artist image with oversized bounds: ${width}x${height}")
            return null
        }
        if (width.toLong() * height.toLong() > MAX_CUSTOM_IMAGE_TOTAL_PIXELS) {
            Timber.tag(TAG)
                .w("Rejected custom artist image with excessive pixel count: ${width}x${height}")
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateCustomImageSampleSize(width, height)
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return try {
            resolver.openInputStream(sourceUri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            }
        } catch (oom: OutOfMemoryError) {
            Timber.tag(TAG).e(oom, "Failed to decode custom artist image due to OOM")
            null
        }
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val longestEdge = max(bitmap.width, bitmap.height)
        if (longestEdge <= TARGET_CUSTOM_IMAGE_MAX_DIMENSION_PX) {
            return bitmap
        }

        val scale = TARGET_CUSTOM_IMAGE_MAX_DIMENSION_PX.toFloat() / longestEdge.toFloat()
        val scaledWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return bitmap.scale(scaledWidth, scaledHeight)
    }

    /**
     * Removes the user's custom artist image, reverting to the downloaded picture (if any).
     *
     * @param context Application context
     * @param artistId The artist's database row ID
     */
    suspend fun clearCustomArtistImage(context: Context, artistId: Long) {
        withContext(Dispatchers.IO) {
            try {
                val destFile = File(context.filesDir, "artist_art_${artistId}.jpg")
                if (destFile.exists()) {
                    destFile.delete()
                    Timber.tag(TAG).d("Deleted custom artist image file: ${destFile.absolutePath}")
                }
                musicDao.updateArtistCustomImage(artistId, null)
            } catch (e: Exception) {
                Timber.tag(TAG)
                    .e("Failed to clear custom artist image for id=$artistId: ${e.message}")
            }
        }
    }
}
