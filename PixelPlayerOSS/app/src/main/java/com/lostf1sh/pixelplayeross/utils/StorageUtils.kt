package com.lostf1sh.pixelplayeross.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * Represents the type of storage device
 */
enum class StorageType {
    INTERNAL,
    SD_CARD,
    USB
}

/**
 * Data class representing storage information
 */
data class StorageInfo(
    val path: File,
    val displayName: String,
    val storageType: StorageType,
    val isRemovable: Boolean
)

/**
 * Utility object for detecting and managing storage devices
 */
object StorageUtils {

    /**
     * Get all available storage devices (Internal Storage, SD Card, USB OTG)
     * @param context Application context
     * @return List of available StorageInfo objects
     */
    fun getAvailableStorages(context: Context): List<StorageInfo> {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val storageVolumes = storageManager.storageVolumes
        val storages = mutableListOf<StorageInfo>()

        var usbCounter = 0

        for (volume in storageVolumes) {
            if (volume.state != Environment.MEDIA_MOUNTED) continue

            val path = getVolumePath(context, storageManager, volume) ?: continue

            val storageType = determineStorageType(volume)
            val displayName = when (storageType) {
                StorageType.INTERNAL -> "Internal Storage"
                StorageType.SD_CARD -> "SD Card"
                StorageType.USB -> {
                    usbCounter++
                    if (usbCounter > 1) "USB Storage $usbCounter" else "USB Storage"
                }
            }

            storages.add(
                StorageInfo(
                    path = path,
                    displayName = displayName,
                    storageType = storageType,
                    isRemovable = volume.isRemovable
                )
            )
        }

        return storages.sortedBy { it.storageType.ordinal }
    }

    /**
     * Get the file path for a StorageVolume
     */
    private fun getVolumePath(context: Context, manager: StorageManager, volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.directory
        if (volume.isPrimary) return Environment.getExternalStorageDirectory()
        // API 29 has no public volume-directory getter. Match the public app directory to
        // its volume, then remove only the documented Android/data/<package>/files suffix.
        for (directory in context.getExternalFilesDirs(null).filterNotNull()) {
            val mounted = manager.getStorageVolume(directory) ?: continue
            deriveLegacyVolumeRoot(
                directory.absolutePath, context.packageName,
                volume.uuid, volume.isPrimary, mounted.uuid, mounted.isPrimary
            )?.let { return File(it) }
        }
        return null
    }

    /**
     * Determine the storage type based on StorageVolume properties
     */
    internal fun deriveLegacyVolumeRoot(
        directory: String, packageName: String,
        expectedUuid: String?, expectedPrimary: Boolean,
        actualUuid: String?, actualPrimary: Boolean
    ): String? {
        if (expectedPrimary != actualPrimary) return null
        if (!expectedPrimary && (expectedUuid == null || !expectedUuid.equals(actualUuid, ignoreCase = true))) return null
        val suffix = "/Android/data/$packageName/files"
        return directory.takeIf { it.endsWith(suffix) }?.removeSuffix(suffix)?.takeIf { it.isNotBlank() }
    }

    private fun determineStorageType(volume: StorageVolume): StorageType {
        if (volume.isPrimary) {
            return StorageType.INTERNAL
        }

        if (!volume.isRemovable) {
            return StorageType.INTERNAL
        }

        val description = volume.getDescription(null)?.lowercase() ?: ""
        
        return when {
            description.contains("usb") -> StorageType.USB
            description.contains("otg") -> StorageType.USB
            description.contains("sd") -> StorageType.SD_CARD
            else -> StorageType.SD_CARD
        }
    }

    fun getSdCardStorage(context: Context): StorageInfo? {
        val storages = getAvailableStorages(context)
        return storages.firstOrNull { it.storageType == StorageType.SD_CARD }
            ?: storages.firstOrNull { it.isRemovable && it.storageType != StorageType.INTERNAL }
    }

    /**
     * Check if any external storage (SD Card or USB) is available
     */
    fun hasExternalStorage(context: Context): Boolean {
        return getAvailableStorages(context).any { it.storageType != StorageType.INTERNAL }
    }

    /**
     * Get internal storage only
     */
    fun getInternalStorage(): StorageInfo {
        return StorageInfo(
            path = Environment.getExternalStorageDirectory(),
            displayName = "Internal Storage",
            storageType = StorageType.INTERNAL,
            isRemovable = false
        )
    }
}
