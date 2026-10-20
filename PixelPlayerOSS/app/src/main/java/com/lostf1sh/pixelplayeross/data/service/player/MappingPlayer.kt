package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.data.stats.TrackEndReason
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Rewrites app-internal artwork URIs on outgoing media items to shareable content URIs, so
 * external session consumers (system media surfaces, notification listeners, etc.) can load
 * the artwork instead of receiving an unreadable custom scheme.
 *
 * While [remoteVolume] reports a level, the player also presents it as remote playback: the
 * session then routes the hardware volume keys to it, including with the screen off (AUD-027).
 */
@OptIn(UnstableApi::class)
class MappingPlayer(
    val innerPlayer: Player,
    private val context: Context,
    private val remoteVolume: RemoteDeviceVolume? = null,
    private val scope: CoroutineScope? = null,
    private val onPositionSeek: ((targetMs: Long) -> Unit)? = null,
    private val queueLoadHold: QueueLoadSkipHold? = null,
    private val onPause: (() -> Unit)? = null,
    /**
     * What the listener asked of the transport (Next, Previous, start over, pick another track), told before it is done. The
     * player reports a skip as a transition with whatever reason it had, which is often not the listener's, so the listening
     * history takes the reason from here (B28).
     */
    private val onUserTransport: ((TrackEndReason) -> Unit)? = null,
) : ForwardingPlayer(innerPlayer) {

    override fun pause() {
        onPause?.invoke()
        super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!playWhenReady) onPause?.invoke()
        super.setPlayWhenReady(playWhenReady)
    }

    // ── Remote device volume (main thread, like every listener callback) ──────────────────

    private val remoteListeners = mutableListOf<Player.Listener>()
    private var remoteJob: Job? = null
    private var remote: RemoteVolumeState? = null

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        val source = remoteVolume ?: return
        val scope = scope ?: return
        remoteListeners += listener
        if (remoteJob == null) {
            remoteJob = scope.launch(Dispatchers.Main) { source.state.collect(::publishRemote) }
        }
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        remoteListeners -= listener
        if (remoteListeners.isEmpty()) {
            remoteJob?.cancel()
            remoteJob = null
        }
    }

    private fun publishRemote(next: RemoteVolumeState?) {
        val previous = remote
        if (previous == next) return
        remote = next
        val modeChanged = (previous == null) != (next == null)
        val rangeChanged = modeChanged || previous?.min != next?.min || previous?.max != next?.max
        remoteListeners.toList().forEach { listener ->
            if (modeChanged) listener.onAvailableCommandsChanged(availableCommands)
            if (rangeChanged) listener.onDeviceInfoChanged(deviceInfo)
            listener.onDeviceVolumeChanged(deviceVolume, isDeviceMuted)
        }
    }

    override fun getDeviceInfo(): DeviceInfo = remote?.let {
        DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(it.min)
            .setMaxVolume(it.max)
            .build()
    } ?: super.getDeviceInfo()

    override fun getDeviceVolume(): Int = remote?.level ?: super.getDeviceVolume()

    override fun isDeviceMuted(): Boolean = if (remote != null) false else super.isDeviceMuted()

    override fun getAvailableCommands(): Player.Commands =
        if (remote == null) super.getAvailableCommands()
        else super.getAvailableCommands().buildUpon().addAll(*REMOTE_VOLUME_COMMANDS).build()

    override fun isCommandAvailable(command: Int): Boolean =
        (remote != null && command in REMOTE_VOLUME_COMMANDS) || super.isCommandAvailable(command)

    @Deprecated("Deprecated in Player")
    override fun setDeviceVolume(volume: Int) = setDeviceVolume(volume, 0)

    override fun setDeviceVolume(volume: Int, flags: Int) {
        val source = remoteVolume
        if (remote != null && source != null) source.setLevel(volume) else super.setDeviceVolume(volume, flags)
    }

    @Deprecated("Deprecated in Player")
    override fun increaseDeviceVolume() = increaseDeviceVolume(0)

    override fun increaseDeviceVolume(flags: Int) {
        val current = remote
        val source = remoteVolume
        if (current != null && source != null) source.setLevel(current.level + 1) else super.increaseDeviceVolume(flags)
    }

    @Deprecated("Deprecated in Player")
    override fun decreaseDeviceVolume() = decreaseDeviceVolume(0)

    override fun decreaseDeviceVolume(flags: Int) {
        val current = remote
        val source = remoteVolume
        if (current != null && source != null) source.setLevel(current.level - 1) else super.decreaseDeviceVolume(flags)
    }

    @Deprecated("Deprecated in Player")
    override fun setDeviceMuted(muted: Boolean) = setDeviceMuted(muted, 0)

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        val current = remote
        val source = remoteVolume
        // A Feature Unit level has no separate mute here; muting goes to the bottom of the range.
        if (current != null && source != null) {
            if (muted) source.setLevel(current.min)
        } else {
            super.setDeviceMuted(muted, flags)
        }
    }

    private fun mapMediaItem(mediaItem: MediaItem?): MediaItem? {
        if (mediaItem == null) return null
        val artworkUri = mediaItem.mediaMetadata.artworkUri ?: return mediaItem
        val exposedArtworkUri = MediaItemBuilder.externalControllerArtworkUri(
            context = context,
            rawArtworkUri = artworkUri.toString()
        ) ?: return mediaItem
        if (exposedArtworkUri == artworkUri) return mediaItem

        val mappedMetadata = mediaItem.mediaMetadata.buildUpon()
            .setArtworkUri(exposedArtworkUri)
            .build()
        return mediaItem.buildUpon()
            .setMediaMetadata(mappedMetadata)
            .build()
    }

    override fun getCurrentMediaItem(): MediaItem? {
        return mapMediaItem(super.getCurrentMediaItem())
    }

    override fun getMediaItemAt(index: Int): MediaItem {
        return mapMediaItem(super.getMediaItemAt(index))!!
    }

    override fun getMediaMetadata(): MediaMetadata {
        val metadata = super.getMediaMetadata()
        val artworkUri = metadata.artworkUri ?: return metadata
        val exposedArtworkUri = MediaItemBuilder.externalControllerArtworkUri(
            context = context,
            rawArtworkUri = artworkUri.toString()
        ) ?: return metadata
        if (exposedArtworkUri == artworkUri) return metadata

        return metadata.buildUpon()
            .setArtworkUri(exposedArtworkUri)
            .build()
    }

    override fun getCurrentTimeline(): Timeline {
        val timeline = super.getCurrentTimeline()
        if (timeline.isEmpty) return timeline
        return MappedTimeline(timeline) { mapMediaItem(it) ?: MediaItem.EMPTY }
    }

    // ── Seek authority (AUD-117) ───────────────────────────────────────────
    //
    // Every position seek reaching the session — from this app's UI, the notification, the lock
    // screen, Bluetooth, a watch — is handed to the explicit authority first, so the Direct engine is
    // told the target instead of the sink having to infer it after a flush.

    override fun seekTo(positionMs: Long) {
        onPositionSeek?.invoke(positionMs.coerceAtLeast(0L))
        super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == currentMediaItemIndex) {
            onPositionSeek?.invoke(positionMs.coerceAtLeast(0L))
        } else {
            onUserTransport?.invoke(TrackEndReason.USER_SELECTED_OTHER)
        }
        // A destination chosen by index makes the skips that were waiting for a queue stale.
        queueLoadHold?.dropHeld()
        super.seekTo(mediaItemIndex, positionMs)
    }

    // ── Skips while a queue is loading (B18-R2) ────────────────────────────────────────────
    //
    // A next or previous that has nowhere to go because the queue is still being attached would be a silent no-op. Every
    // controller reaches the player through here, so this is the one place that holds it until the queue can take it.

    private fun heldSkip(direction: Int): Boolean {
        val hold = queueLoadHold?.takeIf { it.isLoading } ?: return false
        // The player's own timeline, not the mapped one: only the order matters, and repeat mode is ignored on purpose (B19-S3).
        return hold.hold(direction, innerPlayer.hasQueueNeighbour(direction), innerPlayer.repeatMode == Player.REPEAT_MODE_ALL)
    }

    override fun seekToNext() {
        if (heldSkip(+1)) return
        onUserTransport?.invoke(TrackEndReason.USER_NEXT)
        super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        if (heldSkip(+1)) return
        onUserTransport?.invoke(TrackEndReason.USER_NEXT)
        super.seekToNextMediaItem()
    }

    override fun seekToPreviousMediaItem() {
        if (heldSkip(-1)) return
        onUserTransport?.invoke(TrackEndReason.USER_PREVIOUS)
        super.seekToPreviousMediaItem()
    }

    /**
     * Media3's rule, decided here so a restart is an explicit seek to 0: past the threshold (or with
     * no previous item) restart the current item, otherwise move to the previous one.
     */
    override fun seekToPrevious() {
        if (isCurrentMediaItemLive) {
            super.seekToPrevious()
            return
        }
        // Early in a song "previous" means the previous song, so it waits for a queue that is still loading (a late one is a restart).
        if (currentPosition <= maxSeekToPreviousPosition && heldSkip(-1)) return
        if (!hasPreviousMediaItem() || currentPosition > maxSeekToPreviousPosition) {
            onUserTransport?.invoke(TrackEndReason.USER_REPLAY)
            onPositionSeek?.invoke(0L)
            super.seekTo(0L)
        } else {
            onUserTransport?.invoke(TrackEndReason.USER_PREVIOUS)
            super.seekToPreviousMediaItem()
        }
    }

    override fun seekBack() {
        val target = (currentPosition - seekBackIncrement).coerceAtLeast(0L)
        onPositionSeek?.invoke(target)
        super.seekTo(target)
    }

    override fun seekForward() {
        val target = currentPosition + seekForwardIncrement
        onPositionSeek?.invoke(target)
        super.seekTo(target)
    }

    private companion object {
        val REMOTE_VOLUME_COMMANDS = intArrayOf(
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )
    }
}

/**
 * [delegate] with every media item passed through [mapItem].
 *
 * **It forwards the navigation questions too** (B17-H1). A `Timeline` answers "which window follows this one" from its own
 * data, and the session sends controllers that answer baked into the timeline it publishes (`shuffledWindowIndices`). A
 * wrapper that only forwarded windows and periods answered those questions with the base class's, "the next index", so
 * with shuffle on every controller (the app's queue screen, a Bluetooth head unit) saw the base order as the play order.
 */
@OptIn(UnstableApi::class)
internal class MappedTimeline(
    private val delegate: Timeline,
    private val mapItem: (MediaItem) -> MediaItem,
) : Timeline() {
    override fun getWindowCount(): Int = delegate.windowCount

    /**
     * Two mapped timelines are the same when they map the same timeline *object*. The default `Timeline.equals` walks every window of both and, here,
     * maps every item twice (a URI rebuilt per song) to compare them: the controller does it on each publication to decide whether the
     * timeline changed, ~1.4 ms on the host for 4,870 songs against 0.2 ms for the player's own timeline, which on the device is a
     * large share of what is left of a song change once the platform queue is off (B19-S4). A timeline that changed is a new
     * delegate (ExoPlayer publishes only changes), so this answers the same question without the walk.
     */
    override fun equals(other: Any?): Boolean =
        this === other || (other is MappedTimeline && other.delegate === delegate)

    override fun hashCode(): Int = System.identityHashCode(delegate)

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        val w = delegate.getWindow(windowIndex, window, defaultPositionProjectionUs)
        w.mediaItem = mapItem(w.mediaItem)
        return w
    }

    override fun getPeriodCount(): Int = delegate.periodCount

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
        delegate.getPeriod(periodIndex, period, setIds)

    override fun getIndexOfPeriod(uid: Any): Int = delegate.getIndexOfPeriod(uid)

    override fun getUidOfPeriod(periodIndex: Int): Any = delegate.getUidOfPeriod(periodIndex)

    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int =
        delegate.getFirstWindowIndex(shuffleModeEnabled)

    override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int =
        delegate.getLastWindowIndex(shuffleModeEnabled)

    override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
        delegate.getNextWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)

    override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
        delegate.getPreviousWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
}
