package com.lostf1sh.pixelplayeross.data.service

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.media.ReplayGainManager
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.ReplayGainRuntimeState
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class B62ReplayGainStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val events = Channel<ReplayGainRuntimeState>(Channel.UNLIMITED)
    private var transition = false
    private var current: MediaItem? = null
    private var latest = ReplayGainRuntimeState()
    private val player = mockk<Player>(relaxed = true)
    private val engine = mockk<DualPlayerEngine>(relaxed = true)
    private val manager = mockk<ReplayGainManager>()
    private lateinit var processor: ReplayGainProcessor

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { engine.masterPlayer } returns player
        every { engine.isTransitionRunning() } answers { transition }
        every { player.currentMediaItem } answers { current }
        every { engine.publishReplayGainState(any()) } answers {
            latest = firstArg(); events.trySend(latest); Unit
        }
        every { manager.readReplayGain(any()) } returns ReplayGainManager.ReplayGainValues(trackGainDb = -6f)
        every { manager.getVolumeMultiplier(any(), any(), any()) } returns 0.5f
        processor = ReplayGainProcessor(engine, manager, scope) { current }
    }
    @After fun teardown() { scope.cancel(); Dispatchers.resetMain() }

    private fun item(id: String, path: Boolean = true): MediaItem = MediaItem.Builder().setMediaId(id)
        .setUri("file:///$id.opus").setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            if (path) putString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH, "/$id.opus")
        }).build()).build()

    private suspend fun awaitState(predicate: (ReplayGainRuntimeState) -> Boolean): ReplayGainRuntimeState =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { var event = events.receive(); while (!predicate(event)) event = events.receive(); event }
        }

    @Test fun enablingThenPlayingPublishesAppliedForThisTrack() = runBlocking {
        current = item("a"); processor.setEnabled(true); processor.apply(current)
        val state = awaitState { it.applied }
        assertThat(state.mediaId).isEqualTo("a")
        assertThat(state.multiplier).isEqualTo(0.5f)
    }

    @Test fun repeatingTheSameEnabledSettingCannotLeaveAnAppliedTrackNotApplied() = runBlocking {
        current = item("a"); processor.setEnabled(true); processor.apply(current)
        awaitState { it.applied }
        processor.setEnabled(true)
        assertThat(latest.applied).isTrue()
        assertThat(latest.mediaId).isEqualTo("a")
    }

    @Test fun incomingCrossfadeFactorIsPublishedAndThenApplied() = runBlocking {
        processor.setEnabled(true)
        current = item("incoming"); transition = true
        processor.apply(current)
        val pending = awaitState { it.multiplier == 0.5f }
        assertThat(pending.mediaId).isEqualTo("incoming")
        transition = false; processor.onTransitionFinished()
        assertThat(latest.applied).isTrue()
        assertThat(latest.mediaId).isEqualTo("incoming")
    }

    @Test fun noTagsDoesNotMeanTheGainFailed() = runBlocking {
        current = item("no-path", path = false)
        processor.setEnabled(true); processor.apply(current)
        assertThat(latest.applied).isTrue()
        assertThat(latest.multiplier).isEqualTo(1f)
    }
    @Test fun missingTagsWithAReadablePathPublishesUnity() = runBlocking {
        every { manager.readReplayGain(any()) } returns null
        every { manager.getVolumeMultiplier(null, any(), any()) } returns 1f
        current = item("untagged"); processor.setEnabled(true); processor.apply(current)
        val state = awaitState { it.applied }
        assertThat(state.mediaId).isEqualTo("untagged")
        assertThat(state.multiplier).isEqualTo(1f)
    }

    @Test fun aLateResultForASongNoLongerShownTriggersTheCurrentSong() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        every { manager.readReplayGain("/old.opus") } answers {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            ReplayGainManager.ReplayGainValues(trackGainDb = -6f)
        }
        current = item("old"); processor.setEnabled(true); processor.apply(current)
        check(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
        current = item("new")
        release.countDown()
        val state = awaitState { it.applied && it.mediaId == "new" }
        assertThat(state.mediaId).isEqualTo("new")
    }

    @Test fun aSupersededTagReadCannotOverwriteTheNewTrack() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val left = java.util.concurrent.CountDownLatch(1)
        every { manager.readReplayGain("/old.opus") } answers {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            left.countDown()
            ReplayGainManager.ReplayGainValues(trackGainDb = -12f)
        }
        current = item("old"); processor.setEnabled(true); processor.apply(current)
        check(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
        current = item("new"); processor.apply(current)
        awaitState { it.applied && it.mediaId == "new" }
        release.countDown()
        check(withContext(Dispatchers.IO) { left.await(5, java.util.concurrent.TimeUnit.SECONDS) })
        scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertThat(latest.mediaId).isEqualTo("new")
        assertThat(latest.applied).isTrue()
    }

    @Test fun aPlayerSwapForTheSameIncomingTrackKeepsItsComputedFactorUntilFadeCompletion() = runBlocking {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val release = java.util.concurrent.CountDownLatch(1)
        every { manager.readReplayGain(any()) } answers {
            if (reads.incrementAndGet() > 1) check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            ReplayGainManager.ReplayGainValues(trackGainDb = -6f)
        }
        try {
            current = item("incoming"); transition = true; processor.setEnabled(true); processor.apply(current)
            awaitState { it.multiplier == 0.5f }
            // Real engine order: display incoming, later publish the same player as master,
            // then notify completion. The new tag read is deliberately held behind the assertion.
            processor.apply(current)
            transition = false; processor.onTransitionFinished()
            assertThat(latest.applied).isTrue()
            assertThat(latest.mediaId).isEqualTo("incoming")
            assertThat(latest.multiplier).isEqualTo(0.5f)
        } finally {
            release.countDown()
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        }
    }

    @Test fun destroyingTheProcessorClearsTheIdentityOfTheAppliedTrack() = runBlocking {
        current = item("a"); processor.setEnabled(true); processor.apply(current)
        awaitState { it.applied }
        processor.cancel()
        assertThat(latest.applied).isFalse()
        assertThat(latest.mediaId).isNull()
    }

    @Test fun theDiagnosticFactorAlsoPublishesTheFactorActuallyWritten() = runBlocking {
        current = item("a"); processor.setEnabled(true); processor.apply(current)
        awaitState { it.applied }
        processor.setDiagnosticReplayGainFactor(0.25f)
        assertThat(latest.applied).isTrue()
        assertThat(latest.mediaId).isEqualTo("a")
        assertThat(latest.multiplier).isEqualTo(0.25f)
    }

}
