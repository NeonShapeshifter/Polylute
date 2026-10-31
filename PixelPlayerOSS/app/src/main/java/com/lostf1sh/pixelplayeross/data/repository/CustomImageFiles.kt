package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import androidx.core.graphics.scale
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import timber.log.Timber

/** Shared bounded decoding and app-owned file handling for listener-picked images. */
internal object CustomImageFiles {
    private const val TAG = "CustomImageFiles"
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

    fun copy(context: Context, sourceUri: Uri, fileName: String): String? {
        val bitmap = decodeCustomArtistBitmap(context, sourceUri) ?: return null
        val scaled = scaleBitmapIfNeeded(bitmap)
        return try {
            write(context, fileName) { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }
        } finally {
            if (scaled !== bitmap) bitmap.recycle()
            scaled.recycle()
        }
    }

    fun restore(context: Context, bytes: ByteArray, fileName: String): String? {
        if (bytes.size > MAX_CUSTOM_IMAGE_SOURCE_BYTES) return null
        val temporary = File.createTempFile("custom-image-restore", ".img", context.cacheDir)
        return try {
            temporary.writeBytes(bytes)
            val decoded = decodeCustomArtistBitmap(context, Uri.fromFile(temporary)) ?: return null
            decoded.recycle()
            write(context, fileName) { it.write(bytes) }
        } finally {
            temporary.delete()
        }
    }

    private fun write(context: Context, name: String, block: (java.io.OutputStream) -> Unit): String {
        val file = File(context.filesDir, name)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            block(output)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        return file.absolutePath
    }

    fun remove(context: Context, path: String?, prefix: String) {
        if (path == null) return
        val file = File(path)
        if (file.canonicalFile.parentFile == context.filesDir.canonicalFile && file.name.startsWith(prefix)) {
            check(!file.exists() || file.delete()) { "Could not delete custom image" }
        }
    }

    private fun decodeCustomArtistBitmap(context: Context, sourceUri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(sourceUri)?.lowercase()
        if (mimeType != null && !mimeType.startsWith("image/")) {
            Timber.tag(TAG).w("Rejected custom image with unsupported MIME type: $mimeType")
            return null
        }

        runCatching { resolver.openAssetFileDescriptor(sourceUri, "r") }.getOrNull()?.use { descriptor ->
            val declaredLength = descriptor.length
            if (declaredLength > MAX_CUSTOM_IMAGE_SOURCE_BYTES) {
                Timber.tag(TAG)
                    .w("Rejected custom image larger than allowed source size: $declaredLength")
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
                .w("Rejected custom image with invalid bounds: ${width}x${height}")
            return null
        }
        if (width > MAX_CUSTOM_IMAGE_DIMENSION_PX || height > MAX_CUSTOM_IMAGE_DIMENSION_PX) {
            Timber.tag(TAG)
                .w("Rejected custom image with oversized bounds: ${width}x${height}")
            return null
        }
        if (width.toLong() * height.toLong() > MAX_CUSTOM_IMAGE_TOTAL_PIXELS) {
            Timber.tag(TAG)
                .w("Rejected custom image with excessive pixel count: ${width}x${height}")
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
            Timber.tag(TAG).e(oom, "Failed to decode custom image due to OOM")
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

}
