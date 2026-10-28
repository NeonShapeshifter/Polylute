package com.lostf1sh.pixelplayeross.data.musicinfo

import android.graphics.BitmapFactory
import java.io.File

/** Bounds decoding rejects HTML and corrupt downloads without allocating a full image. */
internal object DownloadedImage {
    fun decodes(data: ByteArray): Boolean = BitmapFactory.Options().let { bounds ->
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        bounds.outWidth > 0 && bounds.outHeight > 0
    }
    fun decodes(file: File): Boolean = BitmapFactory.Options().let { bounds ->
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        bounds.outWidth > 0 && bounds.outHeight > 0
    }
}
