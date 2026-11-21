package com.lostf1sh.pixelplayeross.data.service

import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** One restore per service lifetime. A cancelled waiter cannot cancel the shared restore. */
internal class PlaybackRestoreBarrier(
    scope: CoroutineScope,
    private val timeoutMs: Long = 4_000L,
    restore: suspend () -> Unit,
) {
    private var restoreSucceeded = false
    private val result = scope.async(start = CoroutineStart.LAZY) {
        try {
            restore()
            restoreSucceeded = true
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.tag("PlaybackRestore").w(error, "Playback restore failed")
            false
        }
    }

    val isComplete: Boolean get() = result.isCompleted

    /** Normal app startup waits for the single restore, however long storage takes. */
    suspend fun awaitCompletion(): Boolean = result.await()

    /** Only the command's wait expires; it neither cancels the restore nor owes a later play. */
    suspend fun await(): Boolean = withTimeoutOrNull(timeoutMs) { result.await() } ?: false

    /** An empty startup player is not a decision to erase disk. A real user queue may be saved. */
    fun mayPersistSnapshot(hasQueue: Boolean, applyingRestore: Boolean = false): Boolean =
        !applyingRestore && (hasQueue || (isComplete && restoreSucceeded))
}

/** Media3 owns prepare/play after this result; an existing queue always wins over disk. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun resumptionFromPlayer(player: Player): MediaSession.MediaItemsWithStartPosition =
    MediaSession.MediaItemsWithStartPosition(
        (0 until player.mediaItemCount).map(player::getMediaItemAt),
        player.currentMediaItemIndex.coerceAtLeast(0),
        player.currentPosition.coerceAtLeast(0L),
    )

/** The recent-card result is validated by Media3 itself. No player is accessed here. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun recentItemsForLibrary(
    item: androidx.media3.common.MediaItem?,
    page: Int,
    params: androidx.media3.session.MediaLibraryService.LibraryParams?,
): androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>> {
    val recent = item?.takeIf { it.mediaId.isNotBlank() }?.let {
        it.buildUpon().setMediaMetadata(it.mediaMetadata.buildUpon()
            .setIsBrowsable(false).setIsPlayable(true).build()).build()
    }
    return androidx.media3.session.LibraryResult.ofItemList(
        if (page == 0) listOfNotNull(recent) else emptyList(), params,
    )
}
