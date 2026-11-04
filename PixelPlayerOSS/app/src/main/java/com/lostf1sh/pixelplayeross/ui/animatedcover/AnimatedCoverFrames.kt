package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.media.MediaMetadataRetriever
import androidx.core.graphics.scale
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import java.io.File
import java.security.MessageDigest
import timber.log.Timber

/**
 * B56 phase 7.2 (#48): an album whose folder has **only** an animated cover (no `cover.jpg`, no embedded picture) still needs a still one: it is the source of the accent, the
 * palette, the glass, and what every list, the mini player, the notification and the lock screen show. This takes **one fixed frame, the first, once**, saves it as a JPEG in the app's
 * cache (`cache/animated-cover-frames/<folder>/cover.jpg`) and hands that file to the same folder-art path a `cover.jpg` takes (`FolderArtworkSession`), so nothing else knows.
 *
 * It is made when the library is scanned (a worker thread), never from a list. The frame is cached by the file's path, size and time: a replaced `cover.mp4` makes a new frame, and
 * a file that cannot be read leaves a `.none` marker so that it is not tried on every scan.
 */
internal object AnimatedCoverFrames {
    private const val TAG = "AnimatedCover"

    /** The frame is no larger than this on its longer side: it only has to be a cover. */
    const val MAX_SIDE = 1024

    /** Decodes the first frame of an animated file; the platform's by default, the tests' own where the JVM has no decoder for GIF, WebP or MP4. */
    fun interface FirstFrameDecoder {
        fun decode(file: File, kind: AnimatedCoverKind): Bitmap?
    }

    @Volatile
    internal var decoder: FirstFrameDecoder = FirstFrameDecoder { file, kind -> firstFrame(file, kind) }

    /** The same for a cover read through the chosen folder (B57-fix1 4): by address, since there is no path to open. */
    fun interface UriFirstFrameDecoder {
        fun decode(context: Context, uri: Uri, kind: AnimatedCoverKind): Bitmap?
    }

    @Volatile
    internal var uriDecoder: UriFirstFrameDecoder = UriFirstFrameDecoder { context, uri, kind -> firstFrame(context, uri, kind) }

    /** The cached JPEG of the first frame of the animated cover in [songPath]'s folder, or null when there is none (or it cannot be read). */
    fun frameFor(context: Context, songPath: String, directory: AnimatedCoverDirectory = AnimatedCoverDirectory.Disk): File? {
        val dir = File(songPath).parentFile ?: return null
        if (!AlbumArtUtils.shouldTrustDirectoryArtwork(dir.name)) return null
        val names = directory.names(dir) ?: return null
        val (name, kind) = AnimatedCoverNames.pick(names) ?: return null
        val source = File(dir, name)
        val size = directory.sizeOf(source)
        if (size <= 0L) return null
        val folder = File(File(context.cacheDir, "animated-cover-frames"), key(source.absolutePath, directory.modifiedOf(source), size))
        val frame = File(folder, "cover.jpg")
        if (frame.isFile && frame.length() > 0L) return frame
        if (File(folder, ".none").exists()) return null
        folder.mkdirs()
        val document = directory.documentUriOf(source)
        // a plain file goes through the decoder seam as it always did; a document in the chosen folder is read by its address
        val bitmap = runCatching { if (document == null) decoder.decode(source, kind) else uriDecoder.decode(context, document, kind) }.onFailure { Timber.tag(TAG).i("no still frame from %s (%s)", source.name, it.javaClass.simpleName) }.getOrNull()
        if (bitmap == null) {
            runCatching { File(folder, ".none").createNewFile() }
            return null
        }
        val staging = File(folder, "cover.jpg.writing")
        val written = runCatching { staging.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } && staging.renameTo(frame) }.getOrDefault(false)
        bitmap.recycle()
        if (!written) {
            staging.delete()
            return null
        }
        return frame
    }

    /** What names the cached frame of one file: the path, the time and the size of it. */
    fun key(path: String, modifiedMs: Long, sizeBytes: Long): String =
        MessageDigest.getInstance("SHA-1").digest("$path|$modifiedMs|$sizeBytes".toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    private fun firstFrame(file: File, kind: AnimatedCoverKind): Bitmap? = when (kind) {
        AnimatedCoverKind.VIDEO -> MediaMetadataRetriever().let { retriever ->
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::capped)
            } finally {
                retriever.release()
            }
        }
        // the first frame of an animated picture is what the platform decodes for a still (the decoder is told to hand back software pixels so it can be compressed)
        AnimatedCoverKind.WEBP, AnimatedCoverKind.GIF -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val (w, h) = CoverImageEngine.decodeSize(info.size.width, info.size.height, MAX_SIDE)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun firstFrame(context: Context, uri: Uri, kind: AnimatedCoverKind): Bitmap? = when (kind) {
        AnimatedCoverKind.VIDEO -> MediaMetadataRetriever().let { retriever ->
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::capped)
            } finally {
                retriever.release()
            }
        }
        AnimatedCoverKind.WEBP, AnimatedCoverKind.GIF -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val (w, h) = CoverImageEngine.decodeSize(info.size.width, info.size.height, MAX_SIDE)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun capped(bitmap: Bitmap): Bitmap {
        val (w, h) = CoverImageEngine.decodeSize(bitmap.width, bitmap.height, MAX_SIDE)
        return if (w == bitmap.width && h == bitmap.height) bitmap else bitmap.scale(w, h).also { bitmap.recycle() }
    }
}
