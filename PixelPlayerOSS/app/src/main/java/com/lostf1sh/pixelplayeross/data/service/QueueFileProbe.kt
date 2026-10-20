package com.lostf1sh.pixelplayeross.data.service

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException

/**
 * F-09: asks the platform whether the file behind a queue item is still there.
 *
 * Opens the URI the player would open and closes it again, which is the only check that agrees with
 * what playback will find: a MediaStore row can outlive its file (the index is only updated when the
 * scanner runs), so "the row exists" says nothing. A call per item and blocking; never on the main
 * thread.
 *
 * Only a certain "no such file" is reported as missing ([RestoreQueuePolicy.Presence.MISSING]).
 * Anything that could be a temporary condition is unknown, and the item is kept: a volume that is not
 * mounted (an SD card out of the DAP), a provider that refuses the open, a scheme that is not a local
 * file.
 *
 * B12a-8: `content://media/external/...` covers every volume, so a not-found is only a deleted file once the
 * item's **real** volume is known to be mounted — from its row (`VOLUME_NAME`), from the library's file path
 * ([filePath]), or from the removable storage's state ([QueueVolumePolicy]). An item on an unmounted card is
 * kept, never dropped.
 */
object QueueFileProbe {

    fun presence(context: Context, uri: Uri?, filePath: String? = null): RestoreQueuePolicy.Presence {
        if (uri == null) return RestoreQueuePolicy.Presence.UNKNOWN
        return when (uri.scheme?.lowercase()) {
            "file" -> fileState(uri)
            "content" -> contentState(context, uri, filePath)
            null -> uri.path?.let { fileState(Uri.fromFile(File(it))) } ?: RestoreQueuePolicy.Presence.UNKNOWN
            else -> RestoreQueuePolicy.Presence.UNKNOWN
        }
    }

    private fun fileState(uri: Uri): RestoreQueuePolicy.Presence {
        val file = uri.path?.let(::File) ?: return RestoreQueuePolicy.Presence.UNKNOWN
        if (file.exists()) return RestoreQueuePolicy.Presence.PRESENT
        // Gone, or its volume is: only the first is a deleted file.
        val volumeState = runCatching { Environment.getExternalStorageState(file) }.getOrNull()
        return if (volumeState == Environment.MEDIA_MOUNTED) {
            RestoreQueuePolicy.Presence.MISSING
        } else {
            RestoreQueuePolicy.Presence.UNKNOWN
        }
    }

    private fun contentState(context: Context, uri: Uri, filePath: String?): RestoreQueuePolicy.Presence {
        if (!volumeAvailable(context, uri)) return RestoreQueuePolicy.Presence.UNKNOWN
        // The row's own volume, when MediaStore still has the row: a row on a card that is not mounted is not opened.
        val rowVolume = rowVolumeName(context, uri)
        if (QueueVolumePolicy.rowVolumeUnavailable(rowVolume, mountedVolumeNames(context))) {
            return RestoreQueuePolicy.Presence.UNKNOWN
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { }
            RestoreQueuePolicy.Presence.PRESENT
        } catch (_: FileNotFoundException) {
            // Not found is a deleted file only if the item's volume is there (QueueVolumePolicy).
            QueueVolumePolicy.notFoundMeans(
                uriVolume = uri.pathSegments.firstOrNull()?.lowercase() ?: QueueVolumePolicy.VOLUME_EXTERNAL,
                rowVolume = rowVolume,
                mountedVolumes = mountedVolumeNames(context),
                pathVolumeMounted = pathVolumeMounted(filePath),
                removableUnmounted = removableVolumeUnmounted(context),
            )
        } catch (_: SecurityException) {
            RestoreQueuePolicy.Presence.UNKNOWN
        } catch (_: RuntimeException) {
            RestoreQueuePolicy.Presence.UNKNOWN
        } catch (_: java.io.IOException) {
            RestoreQueuePolicy.Presence.UNKNOWN
        }
    }

    /** `VOLUME_NAME` of the item's row, or null when MediaStore has no such row (or would not say). */
    private fun rowVolumeName(context: Context, uri: Uri): String? {
        if (uri.authority != MediaStore.AUTHORITY || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.VOLUME_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    /** The mounted volumes' names, lowercase; null when the platform would not say. */
    private fun mountedVolumeNames(context: Context): Set<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { MediaStore.getExternalVolumeNames(context).map { it.lowercase() }.toSet() }.getOrNull()
    }

    /** Whether the storage the library's [filePath] lives on is mounted; null when there is no path. */
    private fun pathVolumeMounted(filePath: String?): Boolean? {
        val path = filePath?.takeIf { it.isNotBlank() } ?: return null
        val state = runCatching { Environment.getExternalStorageState(File(path)) }.getOrNull() ?: return null
        return state == Environment.MEDIA_MOUNTED
    }

    /** Whether some removable storage volume is listed and not mounted; null when the platform would not say. */
    private fun removableVolumeUnmounted(context: Context): Boolean? = runCatching {
        val storage = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        storage.storageVolumes.any { it.isRemovable && it.state != Environment.MEDIA_MOUNTED }
    }.getOrNull()

    /**
     * A MediaStore URI names its volume in the first path segment (`external` for the primary one, the
     * volume's own name for an SD card). A volume that is not in the mounted set is not a deleted file.
     */
    private fun volumeAvailable(context: Context, uri: Uri): Boolean {
        if (uri.authority != MediaStore.AUTHORITY) return true
        val volume = uri.pathSegments.firstOrNull() ?: return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        if (volume.equals(MediaStore.VOLUME_EXTERNAL, ignoreCase = true) ||
            volume.equals(MediaStore.VOLUME_EXTERNAL_PRIMARY, ignoreCase = true)
        ) {
            return true
        }
        return runCatching { MediaStore.getExternalVolumeNames(context) }
            .getOrNull()
            ?.any { it.equals(volume, ignoreCase = true) }
            ?: true
    }
}
