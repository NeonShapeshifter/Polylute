package com.lostf1sh.pixelplayeross.host

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.PlaylistTimelineHolder
import androidx.media3.exoplayer.playlistTimeline
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.service.player.FadingPlayer
import com.lostf1sh.pixelplayeross.data.service.player.MappingPlayer
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.robolectric.Shadows.shadowOf

/**
 * What the host measurement of a timeline publication is built from (B19-S4): the app's real session player stack
 * (`MappingPlayer` over `FadingPlayer`), a real Media3 `MediaSession` with the platform session it makes, in-process `MediaController`s
 * like the app's, and a player whose timeline is the very class ExoPlayer publishes (`PlaylistTimeline` over `SinglePeriodTimeline`s)
 * holding items built by the app's own `MediaItemBuilder`.
 */

/** A song as the library holds it: realistic strings, a local artwork URI, a FLAC path (the owner's library is ~all FLAC). */
internal fun librarySong(index: Int): Song = Song(
    id = (1_000_000L + index).toString(),
    title = "Track number $index of the long listening queue",
    artist = "Artist ${index % 311}",
    artistId = (index % 311).toLong(),
    album = "Album ${index % 523} - the special edition",
    albumId = (index % 523).toLong(),
    path = "/storage/emulated/0/Music/Artist ${index % 311}/Album ${index % 523}/${index % 17 + 1} - Track number $index.flac",
    contentUriString = "content://media/external/audio/media/${1_000_000L + index}",
    albumArtUriString = LocalArtworkUri.buildSongUri(1_000_000L + index) + "?t=1760000000000",
    duration = 180_000L + (index % 97) * 1_000L,
    genre = "Genre ${index % 23}",
    trackNumber = index % 17 + 1,
    year = 1970 + index % 55,
    dateAdded = 1_700_000_000L + index,
    mimeType = "audio/flac",
    bitrate = 900_000 + index % 400,
    sampleRate = 44_100,
)

/** The item the app puts in the player for a song of a library list (`PlayerViewModel.buildPlaybackMediaItem`, no playlist). */
internal fun queueItem(index: Int): MediaItem = MediaItemBuilder.build(librarySong(index))

/**
 * A player that is only a queue: its timeline is a `PlaylistTimeline` (what ExoPlayer publishes) and it publishes a new one when a
 * duration, the queue or the order changes, with the reason SimpleBasePlayer derives (a duration alone is a source update).
 */
@OptIn(UnstableApi::class)
internal class QueuePlayer(looper: Looper, items: List<MediaItem>) : SimpleBasePlayer(looper) {
    private val holders = ArrayList<PlaylistTimelineHolder>(items.size)
    private var shuffleOrder: ShuffleOrder = ShuffleOrder.DefaultShuffleOrder(0)
    private var timeline: Timeline = Timeline.EMPTY
    var current = 0
        private set
    private var shuffle = false
    private var uidCounter = 0

    init {
        replaceAll(items, current = 0)
    }

    val size: Int get() = holders.size

    private fun holderFor(item: MediaItem, durationUs: Long, uid: Any = uidCounter++): PlaylistTimelineHolder =
        PlaylistTimelineHolder(uid, SinglePeriodTimeline(durationUs, /* isSeekable= */ true, /* isDynamic= */ false, /* useLiveConfiguration= */ false, /* manifest= */ null, item))

    private fun durationUsOf(item: MediaItem): Long = (item.mediaMetadata.extras?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION) ?: 180_000L) * 1_000L

    private fun rebuild() {
        timeline = playlistTimeline(holders, shuffleOrder)
    }

    fun replaceAll(items: List<MediaItem>, current: Int) {
        holders.clear()
        items.forEach { holders += holderFor(it, durationUsOf(it)) }
        shuffleOrder = ShuffleOrder.DefaultShuffleOrder(holders.size, 7L)
        this.current = current
        rebuild()
    }

    /** `addMediaItems(index, items)`: the new items get random places in the order, as `DefaultShuffleOrder.cloneAndInsert`. */
    fun insert(index: Int, items: List<MediaItem>) {
        holders.addAll(index, items.map { holderFor(it, durationUsOf(it)) })
        shuffleOrder = shuffleOrder.cloneAndInsert(index, items.size)
        if (current >= index) current += items.size
        rebuild()
    }

    /** The duration of one item becomes known: same items, same order, one window changes. */
    fun setDuration(index: Int, durationUs: Long) {
        val old = holders[index]
        val item = old.timeline.getWindow(0, Timeline.Window()).mediaItem
        holders[index] = holderFor(item, durationUs, old.uid)
        rebuild()
    }

    /** A new random order over the same items (what `setShuffleOrder` does in the classic redraw). */
    fun reshuffle(seed: Long) {
        shuffleOrder = ShuffleOrder.DefaultShuffleOrder(holders.size, seed)
        rebuild()
    }

    fun skipTo(index: Int) {
        current = index
    }

    /** `removeMediaItem(index)`. */
    fun remove(index: Int) {
        holders.removeAt(index)
        shuffleOrder = shuffleOrder.cloneAndRemove(index, index + 1)
        if (current > index) current--
        rebuild()
    }

    /** `moveMediaItem(from, to)`: the item leaves and goes back in at [to]. */
    fun move(from: Int, to: Int) {
        val held = holders.removeAt(from)
        holders.add(to, held)
        shuffleOrder = shuffleOrder.cloneAndRemove(from, from + 1).cloneAndInsert(to, 1)
        current = when {
            current == from -> to
            from < current && to >= current -> current - 1
            from > current && to <= current -> current + 1
            else -> current
        }
        rebuild()
    }

    fun setShuffle(on: Boolean) {
        shuffle = on
    }

    /** The timeline indices in the order the player plays them with shuffle on. */
    fun playOrder(): List<Int> {
        val order = ArrayList<Int>(holders.size)
        var index = shuffleOrder.firstIndex
        while (index != C.INDEX_UNSET) {
            order += index
            index = shuffleOrder.getNextIndex(index)
        }
        return order
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex != C.INDEX_UNSET) current = mediaItemIndex
        return Futures.immediateVoidFuture()
    }

    fun publish() = invalidateState()

    override fun getState(): State = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA, Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_SHUFFLE_MODE,
                    Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_CHANGE_MEDIA_ITEMS,
                )
                .build()
        )
        .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(Player.STATE_READY)
        .setPlaylist(timeline, Tracks.EMPTY, /* currentMetadata= */ null)
        .setCurrentMediaItemIndex(current.coerceIn(0, (holders.size - 1).coerceAtLeast(0)))
        .setShuffleModeEnabled(shuffle)
        .setContentPositionMs(0L)
        .build()
}

/** Which controllers connect, and what the session lets each of them have. */
internal enum class Controllers {
    /** The session alone (its platform session is always there). */
    NONE,

    /** The app's controller (the queue screen reads the whole timeline). */
    APP,

    /** The app's controller and the media notification controller, both with every command (B18 as installed). */
    APP_AND_NOTIFICATION,
}

/**
 * The session stack of the app over a [QueuePlayer]: `MappingPlayer(FadingPlayer(player))` in a `MediaSession`, in-process controllers.
 * [onConnect] decides what every controller may do, like `MusicService`'s callback; the default is "everything", as B18 had it.
 */
@OptIn(UnstableApi::class)
internal class SessionRig(
    val context: Context,
    val player: QueuePlayer,
    controllers: Controllers,
    sessionId: String,
    private val onConnect: (MediaSession, MediaSession.ControllerInfo) -> MediaSession.ConnectionResult = { session, _ ->
        MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
    },
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val sessionPlayer: Player = MappingPlayer(FadingPlayer(player, scope), context)
    val session: MediaSession
    var appController: MediaController? = null
        private set
    var notificationController: MediaController? = null
        private set

    init {
        session = MediaSession.Builder(context, sessionPlayer)
            .setId(sessionId)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    this@SessionRig.onConnect(session, controller)
            })
            .build()
        idle()
        if (controllers != Controllers.NONE) appController = connect(Bundle.EMPTY)
        if (controllers == Controllers.APP_AND_NOTIFICATION) {
            notificationController = connect(Bundle().apply { putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true) })
        }
    }

    private fun connect(hints: Bundle): MediaController {
        val future: ListenableFuture<MediaController> = MediaController.Builder(context, session.token)
            .setApplicationLooper(Looper.getMainLooper())
            .setConnectionHints(hints)
            .buildAsync()
        idle()
        return Futures.getDone(future)
    }

    fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** One publication: [change] alters the player, which tells the session; then the main looper delivers what was posted. Returns ns for both parts. */
    fun publish(change: QueuePlayer.() -> Unit): Pair<Long, Long> {
        player.change()
        val t0 = System.nanoTime()
        player.publish()
        val t1 = System.nanoTime()
        idle()
        val t2 = System.nanoTime()
        return (t1 - t0) to (t2 - t1)
    }

    fun release() {
        appController?.release()
        notificationController?.release()
        idle()
        session.release()
        idle()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        fun nanosToMs(ns: Long): Double = ns / 1_000_000.0
    }
}
