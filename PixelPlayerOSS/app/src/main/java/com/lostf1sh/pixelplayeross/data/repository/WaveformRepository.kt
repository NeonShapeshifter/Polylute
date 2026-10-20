package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.lostf1sh.pixelplayeross.data.media.Waveform
import com.lostf1sh.pixelplayeross.data.media.WaveformCache
import com.lostf1sh.pixelplayeross.data.media.WaveformDecoder
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces and caches [Waveform] summaries for local tracks.
 *
 * Cache-first and non-blocking: a hit is a few-KB read, a miss decodes on a background dispatcher
 * and writes the result back. Nothing here touches the player, the route or the queue, and no
 * screen ever has to wait for a waveform before it can render.
 *
 * Concurrent requests for the same track share a single decode, so opening and closing Track
 * Details quickly cannot queue up duplicate full-file decodes of the same song.
 *
 * **One decode per file (B31 P2.5).** The result is cached on disk under the track's identity, size and modification time
 * ([WaveformCache]), so the 7.4 s the first Details of a 4:13 FLAC took on the debug build is paid once: the next open of the same file,
 * in this process or the next, reads a few kilobytes. A file replaced on disk has another size or time and is decoded again.
 */
@Singleton
class WaveformRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) {

    private val cache = WaveformCache(File(context.cacheDir, CACHE_DIR_NAME))

    /** The decode of one track; a seam so the cache's behaviour (one decode per file) can be tested without a codec. */
    internal var decoder: suspend (Context, Uri?, File?) -> Waveform? = { c, uri, file -> WaveformDecoder.decode(context = c, uri = uri, file = file) }

    /** One mutex per cache key, so callers serialize per track rather than globally. */
    private val keyLocks = mutableMapOf<String, Mutex>()
    private val keyLocksGuard = Mutex()

    private val sweepGuard = Mutex()
    private var swept = false

    /**
     * Returns the waveform for [song], generating it if needed.
     *
     * Returns null when the track cannot be analysed — a remote/streamed song, an unsupported
     * codec, or a container without a duration. Null means "no waveform available" and callers
     * are expected to render around it rather than surfacing an error.
     */
    suspend fun waveformFor(song: Song): Waveform? {
        val identity = identityOf(song) ?: return null
        val file = localFileOrNull(song)
        val size = resolveSize(identity, file)
        val modified = resolveLastModified(identity, file, song)
        val key = cache.keyFor(identity, size, modified) ?: return null

        cache.read(key)?.let { return it }

        sweepOnce()

        val lock = lockFor(key)
        return lock.withLock {
            // A second caller may have finished the decode while we waited on the lock.
            cache.read(key)?.let { return@withLock it }

            val waveform = try {
                val uri = identity.takeIf { it.startsWith(CONTENT_SCHEME) }?.let(Uri::parse)
                decoder(context, uri, file ?: identity.takeIf { it.startsWith("/") }?.let(::File))
            } catch (e: CancellationException) {
                // Cancelling the view must not leave a half-written entry behind.
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Waveform generation failed for ${song.title}")
                null
            }

            if (waveform != null) {
                withContext(dispatcherProvider.io) { cache.write(key, waveform) }
            }
            waveform
        }
    }

    private suspend fun lockFor(key: String): Mutex = keyLocksGuard.withLock {
        // The map only ever holds tracks someone opened Track Details for. Trim it if a very
        // long session touches many tracks; a dropped lock at worst costs one redundant decode.
        if (keyLocks.size > MAX_TRACKED_LOCKS) keyLocks.clear()
        keyLocks.getOrPut(key) { Mutex() }
    }

    /** Clears crash residue and enforces the size cap, once per process. */
    private suspend fun sweepOnce() {
        sweepGuard.withLock {
            if (swept) return
            swept = true
        }
        withContext(dispatcherProvider.io) { cache.evictIfNeeded() }
    }

    private fun identityOf(song: Song): String? =
        song.contentUriString.takeIf { it.isNotBlank() }
            ?: song.path.takeIf { it.isNotBlank() }

    private fun localFileOrNull(song: Song): File? =
        song.path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

    private fun resolveSize(identity: String, file: File?): Long? {
        file?.let { return it.length() }
        return queryLong(identity, MediaStore.MediaColumns.SIZE)
    }

    /**
     * Last-modified time as cache identity material. The real file wins; the library's
     * `dateModified` is the fallback for sources we cannot stat, and it is refreshed on every
     * scan, so replacing a file on disk still changes the key.
     */
    private fun resolveLastModified(identity: String, file: File?, song: Song): Long? {
        file?.let { return it.lastModified() }
        return queryLong(identity, MediaStore.MediaColumns.DATE_MODIFIED)?.times(1000L)
            ?: song.dateModified.takeIf { it > 0 }
    }

    private fun queryLong(identity: String, column: String): Long? = runCatching {
        context.contentResolver.query(Uri.parse(identity), null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(column)
            if (index < 0 || cursor.isNull(index)) return@use null
            cursor.getLong(index)
        }
    }.getOrNull()

    private companion object {
        const val TAG = "WaveformRepository"
        const val CACHE_DIR_NAME = "waveforms"
        const val CONTENT_SCHEME = "content://"
        const val MAX_TRACKED_LOCKS = 64
    }
}
