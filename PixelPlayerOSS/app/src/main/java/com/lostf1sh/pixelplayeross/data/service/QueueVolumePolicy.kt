package com.lostf1sh.pixelplayeross.data.service

/**
 * B12a-8 #2: what "no such file" means for a MediaStore item, given what is known about its **volume**.
 *
 * `content://media/external/...` is not "the primary storage": `external` names *every* volume, an SD card
 * included. With the card out of the DAP, a queue item that lives on it opens with `FileNotFoundException`
 * exactly like a deleted file does, and the restore dropped it. A not-found is only a deleted file when the volume
 * the item is on is **mounted**; otherwise the item is kept ([RestoreQueuePolicy.Presence.UNKNOWN]) and will play
 * when the card is back.
 *
 * What tells the volume, best first:
 * 1. the row's own `VOLUME_NAME` ([rowVolume]), when MediaStore still has the row;
 * 2. the file path the library holds for the song ([pathVolumeMounted]: that path's storage state), when the caller has one;
 * 3. the URI's own first segment ([uriVolume]): a named volume, or `external_primary`;
 * 4. nothing but `external`: then a not-found counts as a deleted file only when no removable storage is currently
 *    unmounted ([removableUnmounted] false). A card that has been physically taken out disappears from the volume
 *    list, so this last step can still be wrong for an item that is neither in MediaStore nor in the library — and such an
 *    item has nothing left to restore anyway.
 *
 * Pure: the platform's answers are arguments, so every case is testable without a device.
 */
object QueueVolumePolicy {

    const val VOLUME_EXTERNAL = "external"
    const val VOLUME_EXTERNAL_PRIMARY = "external_primary"

    /**
     * @param uriVolume the first path segment of the MediaStore URI (`external`, `external_primary` or a volume name), lowercase.
     * @param rowVolume `VOLUME_NAME` of the item's row, or null when MediaStore has no such row.
     * @param mountedVolumes `MediaStore.getExternalVolumeNames`, lowercase; null when it could not be read.
     * @param pathVolumeMounted whether the storage under the library's file path is mounted; null without a path.
     * @param removableUnmounted whether some removable storage volume is listed and not mounted; null when unknown.
     */
    fun notFoundMeans(
        uriVolume: String,
        rowVolume: String?,
        mountedVolumes: Set<String>?,
        pathVolumeMounted: Boolean?,
        removableUnmounted: Boolean?,
    ): RestoreQueuePolicy.Presence {
        val missing = RestoreQueuePolicy.Presence.MISSING
        val unknown = RestoreQueuePolicy.Presence.UNKNOWN
        // The library says where the file lives: if that storage is not there, the file is not deleted, its card is out.
        if (pathVolumeMounted == false) return unknown
        val volume = (rowVolume ?: uriVolume).lowercase()
        return when (volume) {
            VOLUME_EXTERNAL -> when {
                // The path says its storage is mounted and the open found nothing: gone.
                pathVolumeMounted == true -> missing
                removableUnmounted == false -> missing
                else -> unknown
            }
            else -> when {
                // A volume (the primary one or a named card) that is not in the mounted set is not a deleted file.
                mountedVolumes != null && volume !in mountedVolumes -> unknown
                else -> missing
            }
        }
    }

    /** An item whose row says it is on a volume that is not mounted is not opened at all. */
    fun rowVolumeUnavailable(rowVolume: String?, mountedVolumes: Set<String>?): Boolean {
        if (rowVolume == null || mountedVolumes == null) return false
        val volume = rowVolume.lowercase()
        return volume != VOLUME_EXTERNAL && volume !in mountedVolumes
    }
}
