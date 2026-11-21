package com.lostf1sh.pixelplayeross.data.media

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import timber.log.Timber

/** A failed preparation/rename must never open the original for writing. */
internal class SafeMetadataWriteUnavailable(cause: Exception) : IOException("Safe metadata replacement unavailable", cause)

internal object MetadataFileReplacement {
    internal interface Operations {
        fun sibling(target: File): File
        fun copyAndSync(source: File, sibling: File)
        fun rename(sibling: File, target: File): Boolean
    }
    private object Disk : Operations {
        override fun sibling(target: File): File {
            val parent = target.parentFile ?: throw IOException("No parent directory")
            // MediaProvider rejects .tmp as application/octet-stream in an audio directory (HONOR log).
            return File.createTempFile(".polylute-edit-", ".${target.extension.ifBlank { "tmp" }}", parent)
        }
        override fun copyAndSync(source: File, sibling: File) {
            source.inputStream().use { input ->
                FileOutputStream(sibling).use { out -> input.copyTo(out); out.fd.sync() }
            }
        }
        override fun rename(sibling: File, target: File) = sibling.renameTo(target)
    }

    fun replace(source: File, target: File, operations: Operations = Disk) {
        var sibling: File? = null
        var stage = "create"
        try {
            sibling = operations.sibling(target)
            stage = "copy-sync"
            operations.copyAndSync(source, sibling)
            stage = "rename"
            if (!operations.rename(sibling, target)) throw IOException("Atomic rename refused")
            Timber.tag("SongMetadataEditor").i("METADATA_EDIT: write_path=sibling-rename result=saved")
        } catch (e: Exception) {
            Timber.tag("SongMetadataEditor").w(e, "METADATA_EDIT: write_path=sibling-rename result=aborted stage=$stage original=untouched")
            throw SafeMetadataWriteUnavailable(e)
        } finally {
            sibling?.let { if (it.exists() && !it.delete()) Timber.tag("SongMetadataEditor").w("METADATA_EDIT: sibling cleanup failed") }
        }
    }
}
