package com.lostf1sh.pixelplayeross.host

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.data.service.SessionQueuePolicy
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B19-S4: where the time of a song change goes, measured on the host with the real components (Media3 1.10.1 `MediaSession`, the app's
 * `MappingPlayer` over `FadingPlayer`, in-process `MediaController`s like the app's, the platform session the notification uses) and a
 * 4,870-item timeline of the class ExoPlayer publishes, whose items are built by the app's own `MediaItemBuilder`.
 *
 * What a song change publishes: ExoPlayer replaces its `Timeline` for a *source update* (the duration of the item that was just
 * prepared, `reason=1`), one or two times, and every replacement goes to the session, to every controller and to the platform session.
 * Each scenario below is one such publication as seen from the main thread, with who is listening varied; the numbers are printed
 * (`B19-S4 | ...`) for the result document. They are **host** milliseconds (a desktop CPU, Robolectric's framework, no binder to
 * system_server): good for *where* the time goes and *how it scales per item*, not for how long the HONOR takes, which is what the log
 * line `SongChange: session publication N ms (...) items=4870` says on the device.
 *
 * The rest of the class pins what the fix must not break: what a controller left without the queue still gets, and what the app's
 * controller still sees.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SessionPublicationCostTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val items = 4_870
    private val libraryItems by lazy { List(items) { queueItem(it) } }

    private data class Result(val name: String, val syncMs: Double, val idleMs: Double) {
        val totalMs get() = syncMs + idleMs
        fun perItemUs(n: Int) = totalMs * 1000.0 / n
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    /** What `MusicService.onConnect` does with [SessionQueuePolicy]. */
    private val policy: (MediaSession, MediaSession.ControllerInfo) -> MediaSession.ConnectionResult = { session, controller ->
        val base = MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
        val gets = SessionQueuePolicy.getsQueue(context.packageName, controller.packageName, session.isMediaNotificationController(controller))
        MediaSession.ConnectionResult.accept(base.availableSessionCommands, SessionQueuePolicy.playerCommands(base.availablePlayerCommands, gets))
    }

    /** B18 as installed: every controller is given every command. */
    private val everything: (MediaSession, MediaSession.ControllerInfo) -> MediaSession.ConnectionResult =
        { session, _ -> MediaSession.ConnectionResult.AcceptedResultBuilder(session).build() }

    private fun rigWith(
        size: Int,
        controllers: Controllers,
        id: String,
        onConnect: (MediaSession, MediaSession.ControllerInfo) -> MediaSession.ConnectionResult,
        current: Int = size / 2,
    ): SessionRig {
        val player = QueuePlayer(android.os.Looper.getMainLooper(), libraryItems.take(size))
        player.skipTo(current)
        return SessionRig(context, player, controllers, sessionId = id, onConnect = onConnect)
    }

    /** [rounds] publications of a source update on a window that is not the one playing; the median of each part. */
    private fun measureSourceUpdates(
        name: String,
        controllers: Controllers,
        onConnect: (MediaSession, MediaSession.ControllerInfo) -> MediaSession.ConnectionResult = everything,
        rounds: Int = 12,
        warmup: Int = 6,
    ): Result {
        val rig = rigWith(items, controllers, "cost-${name.hashCode()}", onConnect)
        try {
            val at = rig.player.current + 1
            var n = 0
            fun next() = rig.publish { setDuration(at, (200L + (n++ % 50)) * 1_000_000L) }
            repeat(warmup) { next() }
            val sync = ArrayList<Double>()
            val idle = ArrayList<Double>()
            repeat(rounds) {
                val (a, b) = next()
                sync += SessionRig.nanosToMs(a)
                idle += SessionRig.nanosToMs(b)
            }
            return Result(name, median(sync), median(idle))
        } finally {
            rig.release()
        }
    }

    /** The player's own publication with no session at all: building the new timeline and telling a listener. */
    private fun measurePlayerOnly(name: String, rounds: Int = 12, warmup: Int = 6): Result {
        val player = QueuePlayer(android.os.Looper.getMainLooper(), libraryItems)
        player.skipTo(2_435)
        player.addListener(object : Player.Listener {})
        var n = 0
        fun next(): Long {
            player.setDuration(2_436, (200L + (n++ % 50)) * 1_000_000L)
            val t0 = System.nanoTime()
            player.publish()
            return System.nanoTime() - t0
        }
        repeat(warmup) { next() }
        val times = List(rounds) { SessionRig.nanosToMs(next()) }
        return Result(name, median(times), 0.0)
    }

    private fun print(r: Result, n: Int = items) {
        println("B19-S4 | %-66s | session %8.2f ms | controllers %8.2f ms | total %8.2f ms | %6.1f us/item".format(r.name, r.syncMs, r.idleMs, r.totalMs, r.perItemUs(n)))
    }

    // ---- the measurement ------------------------------------------------------------------------------------

    @Test
    fun `where the time of one source update goes, with 4870 items`() {
        println("B19-S4 | host: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} cores, java ${System.getProperty("java.version")}, items=$items")
        print(measurePlayerOnly("the player alone, no session (what ExoPlayer itself pays)"))
        print(measureSourceUpdates("session + platform session, no controller", Controllers.NONE))
        print(measureSourceUpdates("+ the app's in-process controller", Controllers.APP))
        val b18 = measureSourceUpdates("+ the media notification controller, every command (B18 as installed)", Controllers.APP_AND_NOTIFICATION)
        print(b18)
        val policed = measureSourceUpdates("queue only for the app's controller (B19)", Controllers.APP_AND_NOTIFICATION, onConnect = policy)
        print(policed)

        // The queue of the platform session was the cost. The ratio, not the milliseconds, is what must hold on any machine.
        assertTrue("B18 ${b18.totalMs} ms vs B19 ${policed.totalMs} ms", policed.totalMs * 4 < b18.totalMs)
    }

    @Test
    fun `the attach and the classic redraw publish the same way, and cost the same per item`() {
        // R2: the rest of a 4,870-song list goes in around a block of 26 with two addMediaItems; classic random: one new order.
        fun attachAndRedraw(label: String, onConnect: (MediaSession, MediaSession.ControllerInfo) -> MediaSession.ConnectionResult) {
            val rig = rigWith(26, Controllers.APP_AND_NOTIFICATION, "attach-$label", onConnect, current = 5)
            try {
                val front = libraryItems.subList(26, 26 + 1_995)
                val back = libraryItems.subList(26 + 1_995, items)
                val (frontSync, frontIdle) = rig.publish { insert(0, front) }
                val (backSync, backIdle) = rig.publish { insert(size, back) }
                println(
                    "B19-S4 | attach (R2), %-4s | front +1995 -> %8.2f ms | back +%d -> %8.2f ms | both %8.2f ms".format(
                        label, SessionRig.nanosToMs(frontSync + frontIdle), back.size, SessionRig.nanosToMs(backSync + backIdle),
                        SessionRig.nanosToMs(frontSync + frontIdle + backSync + backIdle),
                    )
                )
                rig.publish { reshuffle(3L) }
                val times = List(7) { seed ->
                    val (a, b) = rig.publish { reshuffle(10L + seed) }
                    SessionRig.nanosToMs(a + b)
                }
                println("B19-S4 | classic redraw (one new order of $items), %-4s | one publication %8.2f ms".format(label, median(times)))
            } finally {
                rig.release()
            }
        }
        attachAndRedraw("B18", everything)
        attachAndRedraw("B19", policy)
    }

    @Test
    fun `the parts of the cost, one by one`() {
        val rig = rigWith(items, Controllers.APP, "parts", everything, current = 2_435)
        val player = rig.player
        try {
            val mapped = rig.sessionPlayer.currentTimeline
            val window = Timeline.Window()

            // 1. What the session's player (MappingPlayer) does per item whenever a Timeline is walked: map the artwork URI.
            fun walk(): Double {
                val t0 = System.nanoTime()
                for (i in 0 until mapped.windowCount) mapped.getWindow(i, window)
                return SessionRig.nanosToMs(System.nanoTime() - t0)
            }
            repeat(5) { walk() }
            val walkMs = median(List(9) { walk() })
            println("B19-S4 | walk every window of the session's MappedTimeline (artwork URI mapped per item)   | %.2f ms | %.1f us/item".format(walkMs, walkMs * 1000 / items))

            // 2. The raw timeline, for comparison: no mapping.
            val raw = player.currentTimeline
            fun walkRaw(): Double {
                val t0 = System.nanoTime()
                for (i in 0 until raw.windowCount) raw.getWindow(i, window)
                return SessionRig.nanosToMs(System.nanoTime() - t0)
            }
            repeat(5) { walkRaw() }
            val rawMs = median(List(9) { walkRaw() })
            println("B19-S4 | walk every window of the player's own timeline (no mapping)                        | %.2f ms | %.1f us/item".format(rawMs, rawMs * 1000 / items))

            // 3. What the controller asks on each publication: is the new timeline the old one? (MediaControllerImplBase: timeline.equals)
            player.setDuration(2_436, 222_000_000L)
            player.publish()
            rig.idle()
            val before = rig.sessionPlayer.currentTimeline
            player.setDuration(2_436, 223_000_000L)
            player.publish()
            rig.idle()
            val after = rig.sessionPlayer.currentTimeline
            fun equalsMs(a: Timeline, b: Timeline): Double {
                val t0 = System.nanoTime()
                val same = a == b
                val ms = SessionRig.nanosToMs(System.nanoTime() - t0)
                assertFalse(same)
                return ms
            }
            repeat(3) { equalsMs(before, after) }
            println("B19-S4 | Timeline.equals(old, new), a duration changed at window 2436 (B19: mapped, by identity) | %.4f ms".format(median(List(7) { equalsMs(before, after) })))
            val rawBefore = player.currentTimeline
            player.setDuration(2_436, 224_000_000L)
            player.publish()
            rig.idle()
            val rawAfter = player.currentTimeline
            repeat(3) { rawBefore == rawAfter }
            val rawEqualsMs = median(List(7) { val t0 = System.nanoTime(); rawBefore == rawAfter; SessionRig.nanosToMs(System.nanoTime() - t0) })
            println("B19-S4 | Timeline.equals(old, new), same change, the player's own timeline (structural)           | %.4f ms".format(rawEqualsMs))
            println("B19-S4 | (B18's MappedTimeline.equals compared structurally through the mapping: two walks of ~%.2f ms to the changed window)".format(walkMs))

            // 4. What a queue item weighs when it goes to the platform session: the bytes of a MediaDescriptionCompat with the item's extras.
            val sample = libraryItems[2_435]
            val extras = sample.mediaMetadata.extras
            val description = android.support.v4.media.MediaDescriptionCompat.Builder()
                .setMediaId(sample.mediaId)
                .setTitle(sample.mediaMetadata.title)
                .setSubtitle(sample.mediaMetadata.artist)
                .setDescription(sample.mediaMetadata.albumTitle)
                .setIconUri(sample.mediaMetadata.artworkUri)
                .setMediaUri(sample.localConfiguration?.uri)
                .setExtras(extras)
                .build()
            val queueItem = android.support.v4.media.session.MediaSessionCompat.QueueItem(description, 2_435L)
            val parcel = android.os.Parcel.obtain()
            queueItem.writeToParcel(parcel, 0)
            val bytes = parcel.dataSize()
            parcel.recycle()
            println("B19-S4 | one platform QueueItem (id, title, artist, album, artwork, uri, %d extras) = %d bytes parceled -> %d items = %.2f MB".format(extras?.size() ?: 0, bytes, items, bytes * items / 1_048_576.0))
        } finally {
            rig.release()
        }
    }

    // ---- what the fix must keep ------------------------------------------------------------------------------

    private class Counter : Player.Listener {
        var timelines = 0
        val reasons = ArrayList<Int>()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            timelines++
            reasons += reason
        }
    }

    @Test
    fun `a source update republishes no queue to the controllers that are left without it, and the current item's duration still arrives`() {
        val rig = rigWith(items, Controllers.APP_AND_NOTIFICATION, "reach", policy, current = 2_435)
        try {
            val app = checkNotNull(rig.appController)
            val notification = checkNotNull(rig.notificationController)
            assertTrue(app.isCommandAvailable(Player.COMMAND_GET_TIMELINE))
            assertFalse(notification.isCommandAvailable(Player.COMMAND_GET_TIMELINE))
            // The first publication after a controller connects replaces the one-window timeline it was handed at connect, once.
            rig.publish { setDuration(2_440, 205_000_000L) }
            val appEvents = Counter().also(app::addListener)
            val notificationEvents = Counter().also(notification::addListener)

            // Three source updates of songs that are not playing (what a prefetch of the next song does).
            repeat(3) { n -> rig.publish { setDuration(2_436 + n, (210L + n) * 1_000_000L) } }

            // The app's controller is told each time (its queue screen is the one place that reads it); the notification, 0 times.
            assertEquals(3, appEvents.timelines)
            assertEquals(0, notificationEvents.timelines)
            assertEquals(items, app.mediaItemCount)
            assertTrue(notification.currentTimeline.windowCount <= 1)

            // The song that is playing gets its duration: every controller hears it.
            rig.publish { setDuration(2_435, 241_000_000L) }
            assertEquals(241_000L, app.duration)
            assertEquals(241_000L, notification.duration)
            assertEquals(1, notificationEvents.timelines)
            assertEquals(libraryItems[2_435].mediaId, notification.currentMediaItem?.mediaId)
        } finally {
            rig.release()
        }
    }

    /**
     * Whether Media3 keeps a queue on the platform session: `MediaSessionLegacyStub.isQueueEnabled()`, which every timeline change asks
     * before it converts the whole timeline to `QueueItem`s. Private, so by reflection; Robolectric's platform session does not hand a
     * queue back to a controller, so the switch itself is what can be read.
     */
    private fun platformQueueEnabled(session: MediaSession): Boolean {
        fun field(target: Any, name: String): Any {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            return checkNotNull(f.get(target))
        }
        val stub = field(field(session, "impl"), "sessionLegacyStub")
        val method = stub.javaClass.getDeclaredMethod("isQueueEnabled")
        method.isAccessible = true
        return method.invoke(stub) as Boolean
    }

    @Test
    fun `the platform session keeps a queue of every song when every command is given, and none with the policy`() {
        fun enabled(onConnect: (MediaSession, MediaSession.ControllerInfo) -> MediaSession.ConnectionResult, id: String): Boolean {
            val rig = rigWith(300, Controllers.APP_AND_NOTIFICATION, id, onConnect, current = 150)
            try {
                rig.publish { setDuration(151, 233_000_000L) }
                return platformQueueEnabled(rig.session)
            } finally {
                rig.release()
            }
        }
        assertTrue(enabled(everything, "platform-all"))
        assertFalse(enabled(policy, "platform-policy"))
    }

    @Test
    fun `an item added, removed or moved still reaches the app's controller, with the whole queue in play order`() {
        val rig = rigWith(500, Controllers.APP_AND_NOTIFICATION, "edits", policy, current = 250)
        try {
            val app = checkNotNull(rig.appController)
            val events = Counter().also(app::addListener)

            rig.publish { insert(0, listOf(queueItem(9_000), queueItem(9_001))) }
            assertEquals(502, app.mediaItemCount)
            assertEquals(libraryItems[250].mediaId, app.currentMediaItem?.mediaId)

            rig.publish { remove(1) }
            assertEquals(501, app.mediaItemCount)

            // Index 10 now holds the song that was 9; it goes to index 400.
            rig.publish { move(10, 400) }
            assertEquals(libraryItems[9].mediaId, app.getMediaItemAt(400).mediaId)
            assertEquals(libraryItems[250].mediaId, app.currentMediaItem?.mediaId)
            assertEquals(3, events.timelines)
            assertEquals(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED, events.reasons.last())

            // With shuffle on, the queue screen lists in play order: the whole queue, in the order the player plays it.
            rig.player.setShuffle(true)
            rig.publish { reshuffle(5L) }
            val order = PlayOrder.of(app.currentTimeline, shuffleEnabled = true)
            assertEquals(501, order.size)
            assertEquals(rig.player.playOrder(), order.toList())
        } finally {
            rig.release()
        }
    }

    @Test
    fun `next and previous from a controller without the queue move the player, and it still has title, artwork, position and duration`() {
        val rig = rigWith(500, Controllers.APP_AND_NOTIFICATION, "skips", policy, current = 250)
        try {
            val notification = checkNotNull(rig.notificationController)
            assertTrue(notification.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            assertTrue(notification.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))

            notification.seekToNextMediaItem()
            rig.idle()
            assertEquals(251, rig.player.current)
            notification.seekToPreviousMediaItem()
            notification.seekToPreviousMediaItem()
            rig.idle()
            assertEquals(249, rig.player.current)

            val shown = checkNotNull(notification.currentMediaItem)
            assertEquals(libraryItems[249].mediaId, shown.mediaId)
            assertEquals(libraryItems[249].mediaMetadata.title, shown.mediaMetadata.title)
            assertEquals(libraryItems[249].mediaMetadata.artist, shown.mediaMetadata.artist)
            assertNotNull(shown.mediaMetadata.artworkUri)
            assertEquals(libraryItems[249].mediaMetadata.extras?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION), notification.duration)
            assertTrue(notification.currentPosition >= 0L)
        } finally {
            rig.release()
        }
    }

    // ---- who gets the queue ----------------------------------------------------------------------------------

    private val app = "app.polylute"
    private val everyCommand = Player.Commands.Builder().addAllCommands().build()

    @Test
    fun `the app's own controller gets the queue, the notification controller and every external one do not`() {
        assertTrue(SessionQueuePolicy.getsQueue(app, app, isMediaNotificationController = false))
        assertTrue(SessionQueuePolicy.playerCommands(everyCommand, getsQueue = true).contains(Player.COMMAND_GET_TIMELINE))
        assertFalse(SessionQueuePolicy.getsQueue(app, app, isMediaNotificationController = true))
        listOf("com.android.systemui", "com.google.android.projection.gearhead", "com.android.bluetooth", "com.example.remote")
            .forEach { assertFalse(SessionQueuePolicy.getsQueue(app, it, isMediaNotificationController = false)) }
    }

    @Test
    fun `without the queue a controller keeps everything else it was given`() {
        val without = SessionQueuePolicy.playerCommands(everyCommand, getsQueue = false)

        assertFalse(without.contains(Player.COMMAND_GET_TIMELINE))
        // The song that is playing, skipping, seeking and play/pause are what they still need.
        listOf(
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_STOP,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
        ).forEach { assertTrue("$it", without.contains(it)) }
    }
}
