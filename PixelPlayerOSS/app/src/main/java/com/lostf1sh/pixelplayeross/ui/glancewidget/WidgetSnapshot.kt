package com.lostf1sh.pixelplayeross.ui.glancewidget

import android.content.Context
import android.graphics.Bitmap
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot
import com.lostf1sh.pixelplayeross.data.model.PlayerInfo
import com.lostf1sh.pixelplayeross.data.model.QueueItem
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** A process-local lifetime marker, never persisted as a claim that playback is still running. */
object WidgetPlaybackLifetime {
    @Volatile var serviceAlive = false
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetSnapshotEntryPoint {
    fun preferences(): UserPreferencesRepository
}

/** No service, player or network is started to draw a launcher widget after reboot. */
internal suspend fun loadWidgetSnapshot(context: Context): PlayerInfo? = withContext(Dispatchers.IO) {
    if (WidgetPlaybackLifetime.serviceAlive) return@withContext null
    val preferences = EntryPointAccessors.fromApplication(
        context.applicationContext, WidgetSnapshotEntryPoint::class.java,
    ).preferences()
    val snapshot = preferences.getPlaybackQueueSnapshotOnce()
    val info = widgetInfoFromSnapshot(snapshot) ?: return@withContext null
    val bytes = info.albumArtUri?.let { uri ->
        runCatching {
            decodeWidgetAlbumArtBitmap(context, uri, 256, 256)?.let { bitmap ->
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            }
        }.getOrNull()
    }
    info.copy(albumArtBitmapData = bytes)
}

internal fun widgetInfoFromSnapshot(snapshot: PlaybackQueueSnapshot?): PlayerInfo? {
    if (snapshot == null || snapshot.items.isEmpty()) return null
    val index = snapshot.currentIndex.takeIf { it in snapshot.items.indices }
        ?: snapshot.items.indexOfFirst { it.mediaId == snapshot.currentMediaId }.coerceAtLeast(0)
    val item = snapshot.items[index]
    val size = snapshot.items.size
    val savedOrder = snapshot.shuffleOrder?.toIntArray()
    val order = savedOrder?.takeIf { PlayOrder.isPermutation(it, size) } ?: PlayOrder.identity(size)
    // Match the live widget's preview: shuffle follows play order; ordinary order wraps at the end.
    val upcoming = if (snapshot.shuffleEnabled) {
        PlayOrder.upcoming(order, index, 4)
    } else {
        val start = if (index + 1 < size) index + 1 else 0
        (start until minOf(start + 4, size)).toList()
    }
    return PlayerInfo(
        songTitle = item.title.orEmpty(), artistName = item.artist.orEmpty(),
        albumArtUri = item.artworkUri, isPlaying = false,
        currentPositionMs = snapshot.currentPositionMs.coerceAtLeast(0L),
        totalDurationMs = item.durationMs?.coerceAtLeast(0L) ?: 0L,
        isShuffleEnabled = snapshot.shuffleEnabled, repeatMode = snapshot.repeatMode,
        queue = upcoming.map { position ->
            val next = snapshot.items[position]
            QueueItem(
                id = next.mediaId.toLongOrNull() ?: next.mediaId.hashCode().toLong(),
                mediaId = next.mediaId,
                albumArtUri = next.artworkUri,
            )
        },
    )
}

internal fun widgetInfoForDisplay(live: PlayerInfo, saved: PlayerInfo?): PlayerInfo {
    if (WidgetPlaybackLifetime.serviceAlive) return live
    if (saved == null) return live.copy(isPlaying = false)
    return saved.copy(
        // Reuse the last widget's rendered cover and colours if it depicts this same item.
        albumArtBitmapData = saved.albumArtBitmapData ?: live.albumArtBitmapData.takeIf {
            live.albumArtUri == saved.albumArtUri
        },
        themeColors = live.themeColors,
        isFavorite = live.isFavorite && live.songTitle == saved.songTitle && live.artistName == saved.artistName,
    )
}
