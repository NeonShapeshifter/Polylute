package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.MappingPlayer
import com.lostf1sh.pixelplayeross.data.service.player.QueueLoadSkipHold
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * B29-fix1 F2 (auditor R29-01): Previous, through a real Media3 `MediaSession` and an in-process `MediaController`, checking **which
 * song the player ends up on**, not which method a mock saw called.
 *
 * The session's player is the app's `MappingPlayer` over a player that keeps its commands the way ExoPlayer keeps them (Previous
 * item only while there is one, Previous always). The controller is the real thing, on a thread of its own like the app's is on the
 * main thread while the session and the player are elsewhere, so it behaves as Media3's controller does: it keeps the **state and the
 * commands it last heard** and **drops** a command that is not among them, without telling anyone. A lagging controller is built the
 * way it happens: the player moves on and the news is on its way (queued behind what the controller's thread is doing) when the press
 * arrives.
 *
 * The engine of the app is a stand-in that reads the same player (its master *is* the session's inner player).
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PreviousSessionIntegrationTest {

    /** A player that is only a queue of [ids] with a playing item and a position, and ExoPlayer's rule for which commands are there. */
    private class ExoLikePlayer(looper: Looper, val ids: List<String>) : SimpleBasePlayer(looper) {
        @Volatile var index = 0
        @Volatile var position = 0L
        val seeks = ArrayList<String>()

        fun moveTo(index: Int, position: Long) {
            this.index = index
            this.position = position
            invalidateState()
        }

        fun publish() = invalidateState()

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            seeks += "seek(item=$mediaItemIndex, pos=$positionMs)"
            if (mediaItemIndex != C.INDEX_UNSET) index = mediaItemIndex
            position = if (positionMs == C.TIME_UNSET) 0L else positionMs
            return Futures.immediateVoidFuture()
        }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_METADATA,
                    )
                    .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, index > 0)
                    .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, index < ids.size - 1)
                    .build(),
            )
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .setPlaylist(
                ids.map { id ->
                    MediaItemData.Builder(id)
                        .setMediaItem(MediaItem.Builder().setMediaId(id).build())
                        .setDurationUs(200_000_000L)
                        .build()
                },
            )
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(position)
            .build()
    }

    /** What the controller held at the moment of the press. */
    private data class Heard(val index: Int, val hasPrevious: Boolean, val previousCommand: Boolean)

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val mainLooper = Looper.getMainLooper()
    private val controllerThread = HandlerThread("app-controller").apply { start() }
    private val controllerHandler by lazy { Handler(controllerThread.looper) }

    private lateinit var player: ExoLikePlayer
    private lateinit var session: MediaSession
    private lateinit var controller: MediaController
    private lateinit var holder: PlaybackStateHolder
    private val engine: DualPlayerEngine = mockk(relaxed = true)
    private var crossfade = false

    @Before
    fun mainDispatcher() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        if (this::controller.isInitialized) onControllerThread { controller.release() }
        idle()
        if (this::session.isInitialized) session.release()
        idle()
        controllerThread.quitSafely()
        Dispatchers.resetMain()
    }

    private fun idle() = shadowOf(mainLooper).idle()

    /** Runs [done] until it holds, letting the main looper (the session and the player) work meanwhile. */
    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            idle()
            check(System.nanoTime() < until) { "timed out waiting for $what" }
            Thread.sleep(1)
        }
    }

    private fun <T> onControllerThread(block: () -> T): T {
        val task = FutureTask(block)
        controllerHandler.post(task)
        waitFor("the controller's thread") { task.isDone }
        return task.get()
    }

    /** Lets the main looper and the controller's thread deliver everything that is on its way, both ways. */
    private fun settle() {
        repeat(3) {
            idle()
            onControllerThread { }
        }
        idle()
    }

    /** The session over the app's `MappingPlayer`, the controller connected while the player is at [index], [position]. */
    private fun connected(index: Int, position: Long, ids: List<String> = listOf("a", "b", "c")) {
        player = ExoLikePlayer(mainLooper, ids)
        player.index = index
        player.position = position
        player.publish()
        session = MediaSession.Builder(context, MappingPlayer(player, context, queueLoadHold = QueueLoadSkipHold()))
            .setId("previous-${System.nanoTime()}")
            .build()
        idle()
        val future = MediaController.Builder(context, session.token).setApplicationLooper(controllerThread.looper).buildAsync()
        waitFor("the controller to connect") { future.isDone }
        controller = future.get()
        // the engine's master: the session's inner player, read from any thread
        val master = mockk<Player>(relaxed = true)
        every { master.currentMediaItem } answers { MediaItem.Builder().setMediaId(player.ids[player.index]).build() }
        every { master.currentPosition } answers { player.position }
        every { master.hasPreviousMediaItem() } answers { player.index > 0 }
        every { engine.masterPlayer } returns master
        every { engine.isTransitionRunning() } answers { crossfade }
        holder = onControllerThread {
            PlaybackStateHolder(
                dualPlayerEngine = engine,
                userPreferencesRepository = mockk<UserPreferencesRepository>(relaxed = true),
                queueStateHolder = mockk(relaxed = true),
                appContext = context,
                appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                dispatchers = TestDispatcherProvider(Dispatchers.Unconfined),
            ).also { it.setMediaController(controller) }
        }
        settle()
    }

    /** The press, handled on the controller's thread, the way the app's main thread handles it. */
    private fun press() {
        onControllerThread { holder.previousSong() }
        settle()
    }

    /**
     * The player moves on to [index], [position] and the controller has not heard yet when the press comes: the controller's thread is
     * held at a gate, the news is queued behind it, and the press is the next thing it does. Returns what the controller held then.
     */
    private fun pressWhileBehind(index: Int, position: Long): Heard {
        val atGate = CountDownLatch(1)
        val open = CountDownLatch(1)
        var heard: Heard? = null
        val task = FutureTask {
            atGate.countDown()
            open.await()
            heard = Heard(
                controller.currentMediaItemIndex,
                controller.hasPreviousMediaItem(),
                controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM),
            )
            holder.previousSong()
        }
        controllerHandler.post(task)
        atGate.await()
        player.moveTo(index, position)
        idle()
        open.countDown()
        waitFor("the press") { task.isDone }
        task.get()
        settle()
        return heard!!
    }

    // ── the lagging controller ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the controller is really behind in these tests, and its own previous is dropped`() {
        connected(index = 0, position = 4_000)
        var heard: Heard? = null
        val atGate = CountDownLatch(1)
        val open = CountDownLatch(1)
        val task = FutureTask {
            atGate.countDown()
            open.await()
            heard = Heard(
                controller.currentMediaItemIndex,
                controller.hasPreviousMediaItem(),
                controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM),
            )
            // Media3's controller drops this call without a word: its commands, the ones it last heard, have no previous
            controller.seekToPreviousMediaItem()
        }
        controllerHandler.post(task)
        atGate.await()
        player.moveTo(1, 1_200)
        idle()
        open.countDown()
        waitFor("the call") { task.isDone }
        settle()

        // what the controller had heard: still the first song, no previous, and the commands of that state
        assertThat(heard).isEqualTo(Heard(index = 0, hasPrevious = false, previousCommand = false))
        // so swapping the neighbour's boolean would not be a fix: the player never moved
        assertThat(player.index).isEqualTo(1)
        assertThat(player.seeks).isEmpty()
    }

    @Test
    fun `early press with the controller still on the first song goes back to the song before the one that plays`() {
        connected(index = 0, position = 4_000)

        val heard = pressWhileBehind(index = 1, position = 1_200)

        assertThat(heard).isEqualTo(Heard(index = 0, hasPrevious = false, previousCommand = false))
        assertThat(player.index).isEqualTo(0)
    }

    @Test
    fun `late press with the controller behind restarts the song that plays, it does not go back`() {
        connected(index = 0, position = 2_000)

        pressWhileBehind(index = 1, position = 47_000)

        assertThat(player.index).isEqualTo(1)
        assertThat(player.position).isEqualTo(0L)
    }

    @Test
    fun `early press on the first song restarts it even when the controller still thinks there is a song before`() {
        // the controller heard the second song (it has a previous, and the command), then the player went back to the first
        connected(index = 1, position = 4_000)

        val heard = pressWhileBehind(index = 0, position = 1_200)

        assertThat(heard).isEqualTo(Heard(index = 1, hasPrevious = true, previousCommand = true))
        assertThat(player.index).isEqualTo(0)
        assertThat(player.position).isEqualTo(0L)
    }

    @Test
    fun `early press with the controller behind and no song before on the player restarts, in the middle of three`() {
        // the controller heard the last song; the player is now on the first with 2.5 s
        connected(index = 2, position = 9_000)

        pressWhileBehind(index = 0, position = 2_500)

        assertThat(player.index).isEqualTo(0)
        assertThat(player.position).isEqualTo(0L)
    }

    // ── the controller in step: the explicit actions as before ───────────────────────────────────────────

    @Test
    fun `early press with the controller up to date goes back, explicitly`() {
        connected(index = 1, position = 1_200)

        press()

        assertThat(player.index).isEqualTo(0)
    }

    @Test
    fun `early press on the first song with the controller up to date restarts it`() {
        connected(index = 0, position = 1_200)

        press()

        assertThat(player.index).isEqualTo(0)
        assertThat(player.position).isEqualTo(0L)
    }

    @Test
    fun `late press with the controller up to date restarts`() {
        connected(index = 2, position = 47_000)

        press()

        assertThat(player.index).isEqualTo(2)
        assertThat(player.position).isEqualTo(0L)
    }

    @Test
    fun `at the last of three an early press steps back to the second, one step`() {
        connected(index = 2, position = 600)

        press()

        assertThat(player.index).isEqualTo(1)
    }

    // ── the position and the neighbour are read from the same player ─────────────────────────────────────

    @Test
    fun `the decision and the prediction read the same position`() {
        connected(index = 0, position = 4_000)
        pressWhileBehindPosition()
    }

    private fun pressWhileBehindPosition() {
        val atGate = CountDownLatch(1)
        val open = CountDownLatch(1)
        var read = -1L
        val task = FutureTask {
            atGate.countDown()
            open.await()
            read = holder.previousDecisionPositionMs()
        }
        controllerHandler.post(task)
        atGate.await()
        player.moveTo(1, 1_200)
        idle()
        open.countDown()
        waitFor("the read") { task.isDone }
        assertThat(read).isEqualTo(1_200L)
    }

    @Test
    fun `during a crossfade the controller, which shows the incoming song, is the one asked`() {
        connected(index = 1, position = 1_500)
        crossfade = true
        // the engine's master is on the outgoing song, 230 s in; the controller shows the incoming one (the second of three)
        val outgoing = mockk<Player>(relaxed = true)
        every { outgoing.currentMediaItem } returns MediaItem.Builder().setMediaId("x").build()
        every { outgoing.currentPosition } returns 230_000L
        every { outgoing.hasPreviousMediaItem() } returns false
        every { engine.masterPlayer } returns outgoing

        press()

        // the controller's own song has a previous and 1.5 s: Previous goes back
        assertThat(player.index).isEqualTo(0)
    }
}
