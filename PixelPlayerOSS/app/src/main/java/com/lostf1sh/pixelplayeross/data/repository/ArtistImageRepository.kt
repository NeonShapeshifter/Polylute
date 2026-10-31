package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

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
        internal fun calculateCustomImageSampleSize(width: Int, height: Int): Int =
            CustomImageFiles.calculateCustomImageSampleSize(width, height)
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
     * Fetch missing pictures for the credited artists of the song on screen.
     * Library lists never call this; catalog downloads belong to the downloader.
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
                val internalPath = CustomImageFiles.copy(context, sourceUri, "artist_art_${artistId}.jpg")
                    ?: return@withContext null
                musicDao.updateArtistCustomImage(artistId, internalPath)
                internalPath
            } catch (e: Exception) {
                Timber.tag(TAG)
                    .e("Failed to save custom artist image for id=$artistId: ${e.message}")
                null
            }
        }
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
                CustomImageFiles.remove(context, File(context.filesDir, "artist_art_${artistId}.jpg").absolutePath, "artist_art_")
                musicDao.updateArtistCustomImage(artistId, null)
            } catch (e: Exception) {
                Timber.tag(TAG)
                    .e("Failed to clear custom artist image for id=$artistId: ${e.message}")
            }
        }
    }
}
