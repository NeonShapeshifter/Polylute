package com.lostf1sh.pixelplayeross.utils

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object AlbumArtUtils {
    private const val CACHE_VERSION_SUFFIX = "_v4"

    // Cached covers are display-only; the source audio file keeps its original art untouched.
    // Embedded covers can be multi-megabyte full-resolution scans, and re-decoding such a blob
    // on every cold artwork load is the dominant avoidable artwork cost. Bounding the cached
    // copy to 1536 px JPEG is invisible on phone-class displays (the full player tops out at
    // 2048 px) while cutting heavy covers to a few hundred KB — far cheaper to decode and
    // lighter on RAM/IPC.
    private const val MAX_CACHED_ART_DIMENSION_PX = 1536
    private const val CACHED_ART_JPEG_QUALITY = 90
    // Cache entries larger than this are treated as legacy/oversized and shrunk in the
    // background on first access (one-time migration for art cached before bounding existed).
    private const val OVERSIZED_CACHED_ART_BYTES = 900L * 1024

    // Thumbnails for list rows. A row shows a cover ~50 dp wide (about 100 px), and the first time a
    // fling reaches a song the whole cover has to be pulled out of the audio file. Doing the full
    // job for that (decode up to 1536 px, scale, re-encode as JPEG, write ~470 KB: measured on the
    // owner's phone, B13) took most of the CPU of every fling over songs not shown before. A list
    // request now gets a small cover instead, made straight from the embedded picture with a
    // subsampled decode, and the 1536 px copy is only made when something asks for a large cover.
    const val THUMBNAIL_EDGE_PX = 256
    private const val THUMBNAIL_JPEG_QUALITY = 85
    private const val THUMBNAIL_CACHE_SUFFIX = "_t1"
    private const val THUMBNAIL_DIR_NAME = "album_art_thumbs"
    private const val THUMBNAIL_TRIM_EVERY_WRITES = 256
    private const val THUMBNAIL_MAX_BYTES = 128L * 1024 * 1024
    private const val THUMBNAIL_TRIM_TO_BYTES = 96L * 1024 * 1024
    private val thumbnailWritesSinceTrim = java.util.concurrent.atomic.AtomicInteger()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Tracks cache files currently being shrunk so rapid repeated loads of the same oversized
    // cover don't read the large blob into memory more than once concurrently.
    private val artworkShrinkInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Suffix of the staging file a cache write lands in before it is moved onto the real path. */
    private const val STAGING_SUFFIX = ".writing"

    /**
     * How long a "this file has no artwork" marker is believed.
     *
     * The marker is written whenever extraction produces nothing, which includes failures that say
     * nothing about the file — the metadata retriever documents intermittent nulls on valid local
     * files. Treating that verdict as permanent means a single bad read costs the track its cover
     * for good, so it is a cache entry with an age rather than a fact.
     */
    internal const val NEGATIVE_ART_MARKER_TTL_MS = 24L * 60L * 60L * 1000L

    /**
     * Whether a negative marker has aged out and extraction should be attempted again.
     *
     * A marker with no usable timestamp is treated as expired: the cost of trying is one failed
     * read, and the cost of believing an unreadable timestamp is a cover that never comes back.
     */
    internal fun isNegativeArtMarkerExpired(markerLastModified: Long, now: Long): Boolean {
        if (markerLastModified <= 0L) return true
        return now - markerLastModified >= NEGATIVE_ART_MARKER_TTL_MS
    }

    // Stripes rather than a map keyed on the song: the coordination has no state to grow with the
    // library and nothing to leak. Two songs that hash to the same stripe simply take turns.
    private const val ART_LOCK_STRIPES = 64
    private val artLockStripes = Array(ART_LOCK_STRIPES) { Any() }

    private fun artLockFor(songId: Long): Any =
        artLockStripes[(songId.hashCode() % ART_LOCK_STRIPES + ART_LOCK_STRIPES) % ART_LOCK_STRIPES]
    private val commonArtworkFileNames = listOf(
        "cover.jpg", "cover.png", "cover.jpeg",
        "folder.jpg", "folder.png", "folder.jpeg",
        "album.jpg", "album.png", "album.jpeg",
        "albumart.jpg", "albumart.png", "albumart.jpeg",
        "artwork.jpg", "artwork.png", "artwork.jpeg",
        "front.jpg", "front.png", "front.jpeg",
        ".folder.jpg", ".albumart.jpg",
        "thumb.jpg", "thumbnail.jpg",
        "scan.jpg", "scanned.jpg"
    )
    private val genericMixedDirectoryNames = setOf(
        "download",
        "downloads",
        "music",
        "songs",
        "audio",
        "studio",
        "gallery",
        "pictures",
        "photos",
        "images",
        "dcim",
        "camera",
        "screenshots"
    )

    /**
     * Main function to get album art for local songs.
     *
     * Local artwork is intentionally embedded-only. Falling back to folder images such as
     * cover.jpg/thumb.jpg can pick unrelated Gallery files when music is stored in mixed
     * directories, and can duplicate the same image across unrelated tracks.
     */
    fun getAlbumArtUri(
        appContext: Context,
        path: String,
        songId: Long,
        forceRefresh: Boolean
    ): String? {
        return synchronized(artLockFor(songId)) {
            if (forceRefresh) clearCacheForSong(appContext, songId)
            if (hasLocalAlbumArt(appContext, path, songId, forceRefresh)) {
                LocalArtworkUri.buildSongUri(songId)
            } else {
                null
            }
        }
    }

    /**
     * Lightweight scan-time artwork resolution.
     *
     * Full embedded-art extraction can allocate large ByteArrays per song. During a library scan
     * we only store the stable lazy URI; the normal image-loading path extracts/caches artwork
     * later for visible rows.
     */
    fun getAlbumArtUriForLibraryScan(
        appContext: Context,
        songId: Long,
        forceRefresh: Boolean
    ): String? = synchronized(artLockFor(songId)) {
        val cachedFile = getCachedAlbumArtFile(appContext, songId)
        val noArtFile = noArtMarkerFile(appContext, songId)

        if (forceRefresh) clearCacheForSong(appContext, songId)

        val hasCachedArtwork = cachedFile.exists() && cachedFile.length() > 0
        if (hasCachedArtwork) {
            cachedFile.setLastModified(System.currentTimeMillis())
        }

        resolveAlbumArtUriForLibraryScan(
            songId = songId,
            hasCachedArtwork = hasCachedArtwork,
            hasNoArtworkMarker = noArtFile.exists(),
            forceRefresh = forceRefresh
        )
    }

    fun getCachedAlbumArtUri(
        appContext: Context,
        songId: Long
    ): Uri? {
        val cachedFile = getCachedAlbumArtFile(appContext, songId)
        if (!cachedFile.exists()) return null

        cachedFile.setLastModified(System.currentTimeMillis())
        return shareableCacheUri(appContext, cachedFile)
    }

    fun hasCachedAlbumArt(
        appContext: Context,
        songId: Long
    ): Boolean {
        return getCachedAlbumArtFile(appContext, songId).exists()
    }

    /**
     * Enhanced album art detection without eagerly persisting the whole library to cache.
     */
    fun getEmbeddedAlbumArtUri(
        appContext: Context,
        filePath: String,
        songId: Long,
        deepScan: Boolean
    ): Uri? {
        ensureAlbumArtCachedFile(appContext, songId, filePath, deepScan)?.let { cachedFile ->
            return shareableCacheUri(appContext, cachedFile)
        }
        return null
    }

    fun ensureAlbumArtCachedFile(
        appContext: Context,
        songId: Long,
        filePath: String? = null,
        forceRefresh: Boolean = false
    ): File? {
        // One extraction per song at a time.
        //
        // Without this, two requests for the same cover both find the cache empty, both decode the
        // embedded picture, and both write the same path — and, before the guard at the end of this
        // function, whichever one failed last could delete the file the other had just produced.
        // The stripes are fixed rather than a map keyed on the song so that the coordination has no
        // state to grow or to leak; two songs sharing a stripe simply take turns.
        return synchronized(artLockFor(songId)) {
            ensureAlbumArtCachedFileLocked(appContext, songId, filePath, forceRefresh)
        }
    }

    /**
     * The cover to draw for a list row of at most [THUMBNAIL_EDGE_PX] px.
     *
     * Order of preference, cheapest first: a thumbnail already made; the full-size cached cover (the
     * image decoder subsamples it, nothing is written); otherwise the embedded picture is read once
     * and a thumbnail is made from it. The full-size copy is deliberately not made here, see
     * [THUMBNAIL_EDGE_PX]. Same "no artwork" marker and expiry as [ensureAlbumArtCachedFile].
     */
    fun ensureAlbumArtThumbnailFile(
        appContext: Context,
        songId: Long,
        filePath: String? = null
    ): File? {
        return synchronized(artLockFor(songId)) {
            ensureAlbumArtThumbnailFileLocked(appContext, songId, filePath)
        }
    }

    private fun ensureAlbumArtThumbnailFileLocked(
        appContext: Context,
        songId: Long,
        filePath: String?
    ): File? {
        val thumbnail = getCachedThumbnailFile(appContext, songId)
        if (thumbnail.exists() && thumbnail.length() > 0) {
            return thumbnail
        }
        val full = getCachedAlbumArtFile(appContext, songId)
        if (full.exists() && full.length() > 0) {
            return ensureAlbumArtCachedFileLocked(appContext, songId, filePath, forceRefresh = false)
        }

        val noArtFile = noArtMarkerFile(appContext, songId)
        if (noArtFile.exists()) {
            if (!isNegativeArtMarkerExpired(noArtFile.lastModified(), System.currentTimeMillis())) {
                return null
            }
            noArtFile.delete()
        }

        val resolvedPath = filePath ?: resolveSongMediaStoreInfo(appContext, songId)?.path ?: return null
        if (!File(resolvedPath).exists()) {
            return null
        }
        val bytes = extractEmbeddedAlbumArtBytes(resolvedPath)
        if (bytes == null) {
            if (!full.exists() || full.length() <= 0) {
                noArtFile.createNewFile()
            }
            return null
        }

        val encoded = encodeThumbnail(bytes)
        if (encoded == null) {
            // Not decodable as a picture here: keep the old behaviour for this cover.
            cacheAlbumArtBytes(appContext, bytes, songId)
            return full.takeIf { it.exists() && it.length() > 0 }
        }
        val staging = File(thumbnail.parentFile, thumbnail.name + STAGING_SUFFIX)
        staging.outputStream().use { it.write(encoded) }
        if (!staging.renameTo(thumbnail)) {
            staging.copyTo(thumbnail, overwrite = true)
            staging.delete()
        }
        noArtFile.delete()
        if (thumbnailWritesSinceTrim.incrementAndGet() % THUMBNAIL_TRIM_EVERY_WRITES == 0) {
            appScope.launch { trimThumbnails(appContext) }
        }
        return thumbnail
    }

    /**
     * [bytes] as a JPEG of at most [THUMBNAIL_EDGE_PX] on its longest edge, or the picture itself when it
     * is already that small, or null when it cannot be decoded.
     */
    internal fun encodeThumbnail(bytes: ByteArray): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val srcWidth = bounds.outWidth
            val srcHeight = bounds.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) return null
            if (maxOf(srcWidth, srcHeight) <= THUMBNAIL_EDGE_PX && bytes.size <= THUMBNAIL_SMALL_PICTURE_BYTES) {
                return bytes
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateArtworkInSampleSize(srcWidth, srcHeight, THUMBNAIL_EDGE_PX)
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
            val scaled = scaleArtworkDownTo(decoded, THUMBNAIL_EDGE_PX)
            val encoded = ByteArrayOutputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, stream)
                stream.toByteArray()
            }
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()
            encoded.takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            Timber.tag("AlbumArtUtils").w(e, "Failed to make a thumbnail")
            null
        }
    }

    private const val THUMBNAIL_SMALL_PICTURE_BYTES = 64 * 1024

    fun getThumbnailDir(appContext: Context): File {
        val dir = File(appContext.filesDir, THUMBNAIL_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCachedThumbnailFile(appContext: Context, songId: Long): File {
        return File(getThumbnailDir(appContext), "song_thumb_${songId}${THUMBNAIL_CACHE_SUFFIX}.jpg")
    }

    /** Keeps the thumbnail folder under [THUMBNAIL_MAX_BYTES], oldest first. */
    private fun trimThumbnails(appContext: Context) {
        val files = getThumbnailDir(appContext).listFiles { f -> f.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= THUMBNAIL_MAX_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= THUMBNAIL_TRIM_TO_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun ensureAlbumArtCachedFileLocked(
        appContext: Context,
        songId: Long,
        filePath: String?,
        forceRefresh: Boolean
    ): File? {
        val cachedFile = getCachedAlbumArtFile(appContext, songId)
        val noArtFile = noArtMarkerFile(appContext, songId)

        if (!forceRefresh) {
            if (cachedFile.exists() && cachedFile.length() > 0) {
                cachedFile.setLastModified(System.currentTimeMillis())
                scheduleOversizedArtworkShrink(cachedFile, songId)
                return cachedFile
            }
            if (noArtFile.exists()) {
                // A marker records that extraction failed once, and extraction can fail for reasons
                // that are not about the file: the retriever pool documents that reads intermittently
                // return null for valid local files. Honouring that permanently turns one bad moment
                // into a track that never shows a cover again, so the marker ages out and the next
                // request after it does is allowed to try properly.
                if (!isNegativeArtMarkerExpired(noArtFile.lastModified(), System.currentTimeMillis())) {
                    return null
                }
                noArtFile.delete()
            }
        } else {
            clearCacheForSong(appContext, songId)
        }

        val resolvedPath = filePath ?: resolveSongMediaStoreInfo(appContext, songId)?.path ?: return null
        if (!File(resolvedPath).exists()) {
            return null
        }

        extractEmbeddedAlbumArtBytes(resolvedPath)?.let { bytes ->
            cacheAlbumArtBytes(appContext, bytes, songId)
            return cachedFile.takeIf { it.exists() && it.length() > 0 }
        }

        // Extraction gave us nothing. Record that, but never at the cost of a cover that exists:
        // a concurrent request — or an earlier one whose result landed while this one was failing —
        // may have produced a valid file, and its output is not this request's to delete.
        if (!cachedFile.exists() || cachedFile.length() <= 0) {
            cachedFile.delete()
            noArtFile.createNewFile()
        }
        return null
    }

    fun openArtworkInputStream(
        appContext: Context,
        uri: Uri
    ): InputStream? {
        val uriString = uri.toString()
        return when {
            LocalArtworkUri.isLocalArtworkUri(uriString) -> {
                val songId = LocalArtworkUri.parseSongId(uriString) ?: return null
                val resolvedPath = resolveSongMediaStoreInfo(appContext, songId)?.path
                ensureAlbumArtCachedFile(
                    appContext = appContext,
                    songId = songId,
                    filePath = resolvedPath
                )?.inputStream()
            }
            uri.scheme.isNullOrBlank() && uri.toString().startsWith("/") -> File(uri.toString()).inputStream()
            else -> appContext.contentResolver.openInputStream(uri)
        }
    }

    private fun hasLocalAlbumArt(
        appContext: Context,
        filePath: String,
        songId: Long,
        deepScan: Boolean
    ): Boolean {
        val audioFile = File(filePath)
        if (!audioFile.exists() || !audioFile.canRead()) {
            return false
        }

        val cachedFile = getCachedAlbumArtFile(appContext, songId)
        val noArtFile = noArtMarkerFile(appContext, songId)

        if (!deepScan) {
            // The positive cache wins over the marker.
            //
            // These two used to be the other way round, which made this — the hot path, reached from
            // the song repository and the paging source on every list load — destructive: a marker
            // written by an extraction that failed for reasons unrelated to the file having no cover
            // would delete a perfectly good cached JPEG, and then report that there was no artwork.
            // It also destroyed the only evidence that would have contradicted it, so the state could
            // never correct itself.
            if (cachedFile.exists() && cachedFile.length() > 0) {
                return true
            }

            if (noArtFile.exists()) {
                return false
            }
        } else {
            noArtFile.delete()
        }

        val hasEmbeddedArt = extractEmbeddedAlbumArtBytes(filePath)?.isNotEmpty() == true
        if (hasEmbeddedArt) {
            noArtFile.delete()
            return true
        }

        cachedFile.delete()
        noArtFile.createNewFile()
        return false
    }

    /**
     * Look for external album art files in the same directory.
     *
     * This is kept for explicit, controlled callers only. The default local-song artwork path
     * must remain embedded-only so the app does not pull unrelated personal Gallery files.
     */
    fun getExternalAlbumArtUri(filePath: String): Uri? {
        return runCatching {
            findExternalAlbumArtFile(filePath)?.let(Uri::fromFile)
        }.getOrNull()
    }

    internal fun findExternalAlbumArtFile(filePath: String): File? {
        val audioFile = File(filePath)
        val directory = audioFile.parentFile ?: return null
        if (!directory.exists() || !directory.isDirectory) return null
        if (!shouldTrustDirectoryArtwork(directory.name)) return null

        return commonArtworkFileNames
            .asSequence()
            .map { name -> File(directory, name) }
            .firstOrNull { artFile ->
                artFile.exists() && artFile.isFile && artFile.length() > 1024
            }
    }

    internal fun shouldTrustDirectoryArtwork(directoryName: String): Boolean {
        val normalized = directoryName.trim().lowercase()
        if (normalized.isBlank()) return false
        return normalized !in genericMixedDirectoryNames
    }

    /**
     * MediaStore's album-art cache can alias unrelated local songs when album metadata is weak or
     * collapsed into "Unknown Album". Keep this helper available for controlled callers, but do
     * not use it as an automatic per-song fallback.
     */
    fun getMediaStoreAlbumArtUri(appContext: Context, albumId: Long): Uri? {
        if (albumId <= 0) return null

        val potentialUri = ContentUris.withAppendedId(
            "content://media/external/audio/albumart".toUri(),
            albumId
        )

        return try {
            appContext.contentResolver.openFileDescriptor(potentialUri, "r")?.use {
                potentialUri
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Save embedded art to cache with unique naming
     */
    fun saveAlbumArtToCache(appContext: Context, bytes: ByteArray, songId: Long): Uri {
        return synchronized(artLockFor(songId)) {
            clearCacheForSong(appContext, songId)
            shareableCacheUri(appContext, cacheAlbumArtBytes(appContext, bytes, songId))
        }
    }

    /**
     * Invalidate all artwork variants for one song, serialized with extraction and shrink writes.
     */
    fun clearCacheForSong(appContext: Context, songId: Long) = synchronized(artLockFor(songId)) {
        com.lostf1sh.pixelplayeross.data.image.LocalArtworkCache.invalidate(appContext, songId)
        listOf(
            getCachedAlbumArtFile(appContext, songId),
            getCachedThumbnailFile(appContext, songId),
            noArtMarkerFile(appContext, songId),
            legacyCachedAlbumArtFile(appContext, songId, "_v3"),
            legacyNoArtMarkerFile(appContext, songId, "_v3"),
            legacyCachedAlbumArtFile(appContext, songId, "_v2"),
            legacyNoArtMarkerFile(appContext, songId, "_v2"),
            legacyCachedAlbumArtFile(appContext, songId),
            legacyNoArtMarkerFile(appContext, songId)
        ).flatMap { file ->
            // Startup may still be moving artwork out of the old cacheDir location.
            listOf(file, File(appContext.cacheDir, file.name))
        }.forEach { file ->
            file.delete()
            File(file.parentFile, file.name + STAGING_SUFFIX).delete()
            File(file.parentFile, file.name + ".shrink.tmp").delete()
        }
    }

    private const val ALBUM_ART_DIR_NAME = "album_art"

    fun getAlbumArtDir(appContext: Context): File {
        val dir = File(appContext.filesDir, ALBUM_ART_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCachedAlbumArtFile(appContext: Context, songId: Long): File {
        return File(getAlbumArtDir(appContext), "song_art_${songId}${CACHE_VERSION_SUFFIX}.jpg")
    }

    /**
     * Moves any legacy album-art files from cacheDir (old location, wipeable by the OS)
     * into filesDir/album_art/. Idempotent — safe to call on every startup. Runs quickly
     * because it only lists files matching the `song_art_` prefix.
     */
    fun migrateLegacyCacheLocation(appContext: Context) {
        val oldDir = appContext.cacheDir
        val newDir = getAlbumArtDir(appContext)
        val legacyFiles = oldDir.listFiles { f ->
            f.isFile && f.name.startsWith("song_art_")
        } ?: return

        for (file in legacyFiles) {
            val songId = file.name.removePrefix("song_art_").substringBefore('_').substringBefore('.').toLongOrNull() ?: continue
            synchronized(artLockFor(songId)) {
                if (!file.exists()) return@synchronized
                val target = File(newDir, file.name)
                if (target.exists()) {
                    file.delete()
                    return@synchronized
                }
                if (!file.renameTo(target)) {
                    runCatching {
                        file.copyTo(target, overwrite = false)
                        file.delete()
                    }
                }
            }
        }
    }

    private fun cacheAlbumArtBytes(appContext: Context, bytes: ByteArray, songId: Long): File {
        val file = getCachedAlbumArtFile(appContext, songId)

        val boundedBytes = boundArtworkForCache(bytes)
        // Written beside the real path and then moved onto it.
        //
        // `file.outputStream()` truncates the destination before streaming into it, so a reader that
        // opened the file inside that window — the Coil fetcher and the shared-artwork provider both
        // can — would decode a half-written JPEG. A rename inside the same directory is atomic, so a
        // reader sees either the whole previous file or the whole new one, never a fraction of each.
        val staging = File(file.parentFile, file.name + STAGING_SUFFIX)
        staging.outputStream().use { outputStream ->
            outputStream.write(boundedBytes)
        }
        if (!staging.renameTo(file)) {
            staging.copyTo(file, overwrite = true)
            staging.delete()
        }
        noArtMarkerFile(appContext, songId).delete()

        appScope.launch {
            AlbumArtCacheManager.cleanCacheIfNeeded(appContext, AlbumArtCacheManager.configuredCacheLimitMb)
        }

        return file
    }

    /**
     * Schedules a one-time, background re-compression of an oversized cached cover. The current
     * load still returns the existing file; subsequent loads get the bounded one. De-duplicated
     * per file so rapid repeated loads don't read the same large blob into memory twice.
     */
    private fun scheduleOversizedArtworkShrink(file: File, songId: Long) {
        if (file.length() <= OVERSIZED_CACHED_ART_BYTES) return
        val key = file.absolutePath
        if (!artworkShrinkInFlight.add(key)) return
        appScope.launch {
            try {
                synchronized(artLockFor(songId)) {
                    if (file.length() <= OVERSIZED_CACHED_ART_BYTES) return@launch
                    val raw = runCatching { file.readBytes() }.getOrNull() ?: return@launch
                    val bounded = boundArtworkForCache(raw)
                    if (bounded.size >= raw.size) return@launch
                    val tmp = File(file.parentFile, "${file.name}.shrink.tmp")
                    runCatching {
                        tmp.outputStream().use { it.write(bounded) }
                        if (!tmp.renameTo(file)) {
                            file.outputStream().use { it.write(bounded) }
                            tmp.delete()
                        }
                    }
                }
            } finally {
                artworkShrinkInFlight.remove(key)
            }
        }
    }

    /**
     * Returns artwork bytes bounded for the display cache: longest edge at most
     * [MAX_CACHED_ART_DIMENSION_PX], re-encoded as JPEG when the source is oversized by
     * dimension or by on-disk size. Returns the original bytes unchanged when they are already
     * small enough or when decoding fails, so artwork is never dropped or needlessly re-encoded.
     */
    private fun boundArtworkForCache(bytes: ByteArray): ByteArray {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val srcWidth = bounds.outWidth
            val srcHeight = bounds.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) return bytes

            val oversizedByDimension = maxOf(srcWidth, srcHeight) > MAX_CACHED_ART_DIMENSION_PX
            val oversizedBySize = bytes.size > OVERSIZED_CACHED_ART_BYTES
            if (!oversizedByDimension && !oversizedBySize) return bytes

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateArtworkInSampleSize(srcWidth, srcHeight, MAX_CACHED_ART_DIMENSION_PX)
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return bytes
            val scaled = scaleArtworkDownTo(decoded, MAX_CACHED_ART_DIMENSION_PX)
            val encoded = ByteArrayOutputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, CACHED_ART_JPEG_QUALITY, stream)
                stream.toByteArray()
            }
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()
            if (encoded.isNotEmpty() && encoded.size < bytes.size) encoded else bytes
        } catch (e: Throwable) {
            Timber.tag("AlbumArtUtils").w(e, "Failed to bound artwork for cache; keeping original bytes")
            bytes
        }
    }

    /** Largest power-of-two subsample that keeps the decoded longest edge >= [maxDimensionPx]. */
    private fun calculateArtworkInSampleSize(srcWidth: Int, srcHeight: Int, maxDimensionPx: Int): Int {
        var inSampleSize = 1
        val longestEdge = maxOf(srcWidth, srcHeight)
        while (longestEdge / (inSampleSize * 2) >= maxDimensionPx) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    /** Scales [src] so its longest edge is [maxDimensionPx]; returns [src] unchanged if already smaller. */
    private fun scaleArtworkDownTo(src: Bitmap, maxDimensionPx: Int): Bitmap {
        val longestEdge = maxOf(src.width, src.height)
        if (longestEdge <= maxDimensionPx) return src
        val scale = maxDimensionPx.toFloat() / longestEdge
        val targetWidth = (src.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (src.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
    }

    /**
     * PHASE 14.0Z3 — true when this song is authoritatively known to have no artwork right now.
     *
     * Reads the same marker `ensureAlbumArtCachedFileLocked` writes on a failed extraction,
     * with the same expiry `resolveAlbumArtUriForLibraryScan` honours. Without it the two
     * disagree: the scan path returns null for a marked song while the song model handed to
     * Home still advertised a synthesized `pixelplayer_local_art://` uri, so every
     * composition of the item holding it built a painter and issued a request that could
     * only fail. Measured: songs 290 and 631, two of Home's three mix covers.
     *
     * Read-only. It never writes, deletes or ages a marker, so absence of a live marker is
     * always "unknown" and never "absent"; the 24 h TTL and the forceRefresh delete remain
     * the only ways a marker clears.
     */
    fun isArtworkAbsent(appContext: Context, songId: Long): Boolean {
        val marker = noArtMarkerFile(appContext, songId)
        if (!marker.exists()) return false
        return !isNegativeArtMarkerExpired(marker.lastModified(), System.currentTimeMillis())
    }

    private fun noArtMarkerFile(appContext: Context, songId: Long): File {
        return File(getAlbumArtDir(appContext), "song_art_${songId}${CACHE_VERSION_SUFFIX}_no.jpg")
    }

    private fun legacyCachedAlbumArtFile(
        appContext: Context,
        songId: Long,
        versionSuffix: String = ""
    ): File {
        return File(getAlbumArtDir(appContext), "song_art_${songId}${versionSuffix}.jpg")
    }

    private fun legacyNoArtMarkerFile(
        appContext: Context,
        songId: Long,
        versionSuffix: String = ""
    ): File {
        return File(getAlbumArtDir(appContext), "song_art_${songId}${versionSuffix}_no.jpg")
    }

    private data class MediaStoreSongInfo(
        val path: String,
        val albumId: Long?
    )

    private fun resolveSongMediaStoreInfo(
        appContext: Context,
        songId: Long
    ): MediaStoreSongInfo? {
        val selection = "${MediaStore.Audio.Media._ID} = ?"
        val selectionArgs = arrayOf(songId.toString())
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )

        return runCatching {
            appContext.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
                val albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID))
                MediaStoreSongInfo(
                    path = path,
                    albumId = albumId.takeIf { it > 0L }
                )
            }
        }.getOrNull()
    }

    private fun extractEmbeddedAlbumArtBytes(filePath: String): ByteArray? {
        val retrieverArtwork = MediaMetadataRetrieverPool.withRetriever { retriever ->
            try {
                retriever.setDataSource(filePath)
            } catch (e: IllegalArgumentException) {
                try {
                    FileInputStream(filePath).use { fis ->
                        retriever.setDataSource(fis.fd)
                    }
                } catch (e2: Exception) {
                    return@withRetriever null
                }
            }

            retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
        }

        if (retrieverArtwork != null) {
            return retrieverArtwork
        }

        return runCatching {
            AudioMetadataReader.read(File(filePath))?.artwork?.bytes?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun shareableCacheUri(appContext: Context, file: File): Uri {
        return try {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.provider",
                file
            )
        } catch (e: Exception) {
            Uri.fromFile(file)
        }
    }
}

internal fun resolveAlbumArtUriForLibraryScan(
    songId: Long,
    hasCachedArtwork: Boolean,
    hasNoArtworkMarker: Boolean,
    forceRefresh: Boolean
): String? {
    if (hasCachedArtwork) {
        return LocalArtworkUri.buildSongUri(songId)
    }
    if (hasNoArtworkMarker && !forceRefresh) {
        return null
    }
    return LocalArtworkUri.buildSongUri(songId)
}
