package com.lostf1sh.pixelplayeross.data.service

/**
 * Which queue entry a widget queue tap names (AUD-103).
 *
 * The widget used to carry only a `Long` song id and the service matched `mediaId.toLongOrNull()`,
 * so `external:` items (non-numeric media ids) could never be played from it. The tap now carries
 * the media id as a string, matched exactly; the numeric id is still accepted from a widget laid
 * out by an older version.
 */
object WidgetQueueSelection {
    fun indexOf(queueMediaIds: List<String>, mediaId: String?, legacySongId: Long): Int {
        if (!mediaId.isNullOrBlank()) return queueMediaIds.indexOf(mediaId)
        if (legacySongId == -1L) return -1
        return queueMediaIds.indexOfFirst { it.toLongOrNull() == legacySongId }
    }
}
