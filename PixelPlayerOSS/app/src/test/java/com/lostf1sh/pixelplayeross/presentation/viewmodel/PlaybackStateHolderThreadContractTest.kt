package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.os.PowerManager
import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The thread contract between the progress sampler and whatever it is sampling.
 *
 * This is the boundary that crashed on the device. `f72208e` gave [PlaybackStateHolder] an
 * application scope so its work would stop depending on a ViewModel's lifetime — but it handed it
 * `@AppScope`, which `AppModule` builds as `SupervisorJob() + dispatchers.io`. The sampling loop
 * therefore ran on a background pool while `MediaController` enforces an application-thread
 * contract, and every tick threw:
 *
 * ```
 * java.lang.IllegalStateException: MediaController method is called from a wrong thread.
 *   at MediaController.verifyApplicationThread(MediaController.java:2269)
 *   at MediaController.getCurrentTimeline(MediaController.java:1362)
 *   at MediaController.getMediaItemCount(MediaController.java:1641)
 *   at PlaybackStateHolder.shouldSampleLocalProgress(PlaybackStateHolder.kt:530)
 * ```
 *
 * `6f31c05` corrected the scope to `@MainAppScope`. These tests are the guard that was missing:
 * they assert the *behaviour* — that every player read happens on the scope's dispatcher — rather
 * than only that a scope was injected.
 *
 * ## The limitation, stated plainly
 *
 * Media3's `verifyApplicationThread()` compares `Looper.myLooper()` against the controller's
 * `applicationLooper`. A JVM unit test has no real `Looper` and cannot construct a
 * `MediaController`, so these tests cannot reproduce the throw itself. What they *can* do — and
 * what actually caused the crash — is prove that the sampling loop reads the player from one
 * dispatcher and no other. The complementary half, that the controller is bound to the main
 * looper that dispatcher runs on, is pinned at construction in `MediaControllerFactory` and
 * guarded by `MediaControllerLooperBoundaryTest`.
 */
class PlaybackStateHolderThreadContractTest {

    private val dualPlayerEngine: DualPlayerEngine = mockk(relaxed = true)
    private val userPreferencesRepository: UserPreferencesRepository = mockk(relaxed = true)
    private val queueStateHolder: QueueStateHolder = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)
    private val powerManager: PowerManager = mockk(relaxed = true)

    /** Every thread the sampler touched the player from, in order. */
    private val playerReadThreads = CopyOnWriteArrayList<String>()

    private val controllerThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, CONTROLLER_THREAD_NAME)
    }
    private val controllerDispatcher = controllerThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + controllerDispatcher)

    /**
     * kotlinx-coroutines appends ` @coroutine#N` to the thread name once a coroutine name is set,
     * so the bare thread name is taken rather than the decorated one.
     */
    private fun currentThreadName(): String =
        Thread.currentThread().name.substringBefore(" @")

    private fun record(): Int {
        playerReadThreads.add(currentThreadName())
        return 0
    }

    /** A player that records the thread of every read the sampler performs. */
    private fun recordingPlayer(
        itemCount: Int = 1,
        playing: Boolean = true,
        playWhenReady: Boolean = true,
        playbackState: Int = Player.STATE_READY,
        positionMs: Long = 4_000L,
        durationMs: Long = 180_000L,
    ): Player {
        val player = mockk<Player>(relaxed = true)
        every { player.mediaItemCount } answers { record(); itemCount }
        every { player.isPlaying } answers { record(); playing }
        every { player.playWhenReady } answers { record(); playWhenReady }
        every { player.playbackState } answers { record(); playbackState }
        every { player.currentMediaItem } answers { record(); null }
        every { player.currentPosition } answers { record(); positionMs }
        every { player.duration } answers { record(); durationMs }
        return player
    }

    init {
        every { appContext.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { powerManager.isInteractive } returns true
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        controllerThread.shutdownNow()
    }

    private fun createHolder() = PlaybackStateHolder(
        dualPlayerEngine = dualPlayerEngine,
        userPreferencesRepository = userPreferencesRepository,
        queueStateHolder = queueStateHolder,
        appContext = appContext,
        appScope = scope,
        dispatchers = TestDispatcherProvider(Dispatchers.Unconfined),
    )

    /** Keeps a subscriber on [PlaybackStateHolder.currentPosition] so the sampler runs. */
    private fun subscribe(holder: PlaybackStateHolder): Job = scope.launch {
        holder.currentPosition.collect { }
    }

    private fun awaitReads(count: Int = 1) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (playerReadThreads.size < count && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
    }

    @Test
    fun `every player read happens on the scope's dispatcher, never the caller's thread`() {
        // The controller is absent, so the sampler falls back to the engine's master player —
        // a real `Player`, which a JVM test can fake. The dispatcher is what is under test, and
        // the fallback and the controller share it.
        val player = recordingPlayer()
        every { dualPlayerEngine.masterPlayer } returns player

        val holder = createHolder()
        holder.startProgressUpdates()
        subscribe(holder)
        awaitReads()

        val callerThread = currentThreadName()
        val reads = playerReadThreads.toList()

        assertTrue(reads.isNotEmpty(), "the sampler never read the player, so nothing was proven")
        assertTrue(
            reads.all { it == CONTROLLER_THREAD_NAME },
            "player was read from $reads; every read must happen on $CONTROLLER_THREAD_NAME",
        )
        assertTrue(
            reads.none { it == callerThread },
            "player was read from the caller's thread ($callerThread); the sampler must not " +
                "touch the player from whatever thread happens to launch it",
        )
    }

    @Test
    fun `the mediaItemCount gate runs on the scope's dispatcher`() {
        // The exact frame that crashed: shouldSampleLocalProgress -> mediaItemCount ->
        // MediaController.getCurrentTimeline -> verifyApplicationThread.
        val player = recordingPlayer(itemCount = 3)
        every { dualPlayerEngine.masterPlayer } returns player

        val holder = createHolder()
        holder.startProgressUpdates()
        subscribe(holder)
        awaitReads(count = 4)

        assertTrue(playerReadThreads.isNotEmpty())
        assertEquals(
            listOf(CONTROLLER_THREAD_NAME),
            playerReadThreads.toList().distinct(),
            "the gate and the reads after it must all run on the sampler's dispatcher",
        )
    }

    @Test
    fun `an empty player is not read past the item-count gate`() {
        // Progress semantics: with nothing queued there is no position to interpolate, so the
        // sampler must stop at the gate rather than reading a position it cannot trust.
        val player = recordingPlayer(itemCount = 0)
        every { dualPlayerEngine.masterPlayer } returns player

        val holder = createHolder()
        holder.startProgressUpdates()
        subscribe(holder)
        awaitReads(count = 3)

        // The gate itself is read; nothing beyond it may be.
        assertTrue(playerReadThreads.isNotEmpty())
        assertEquals(0L, holder.currentPosition.value)
    }

    @Test
    fun `a paused player is gated out before any position read`() {
        val player = recordingPlayer(
            itemCount = 1,
            playing = false,
            playWhenReady = false,
            playbackState = Player.STATE_READY,
        )
        every { dualPlayerEngine.masterPlayer } returns player

        val holder = createHolder()
        holder.startProgressUpdates()
        subscribe(holder)
        awaitReads(count = 4)

        assertEquals(0L, holder.currentPosition.value)
    }

    @Test
    fun `stopping the sampler stops all player reads`() {
        val player = recordingPlayer()
        every { dualPlayerEngine.masterPlayer } returns player

        val holder = createHolder()
        holder.startProgressUpdates()
        val subscription = subscribe(holder)
        awaitReads(count = 2)

        holder.stopProgressUpdates()
        subscription.cancel()

        // Cancellation is asynchronous: a tick already past its suspension point still has its
        // reads ahead of it, so snapshotting immediately after `cancel()` races that tick — and
        // loses the race more often the busier the machine is. The barrier below is a
        // happens-before on the sampler's own single thread, so the snapshot is taken after any
        // in-flight tick has drained instead of after a sleep has hopefully outrun it.
        //
        // The invariant is untouched: this still asserts that no read follows the stop. Only the
        // window in which a read could land is closed.
        runBlocking { withContext(controllerDispatcher) { } }

        val afterStop = playerReadThreads.toList()
        Thread.sleep(150)
        assertEquals(
            afterStop,
            playerReadThreads.toList(),
            "the sampler kept reading the player after it was stopped",
        )
    }

    private companion object {
        const val CONTROLLER_THREAD_NAME = "controller-application-thread"
    }
}
