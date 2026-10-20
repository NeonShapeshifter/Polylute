package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.MainCoroutineExtension
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.utils.OrderedTestTimeline
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * B17-H1: turning shuffle on or off on a 5,000-song queue publishes nothing.
 *
 * The owner's log (B15, 2026-10-12): `SET_SHUFFLE_STATE` at 17:34:26.136, then `Timeline changed (reason=0)` at 27.203, 33.441 and
 * 41.743 (three full rebuilds of the ~4,870-song queue over 15 s) and three different songs given a producer within 50 ms. The old
 * toggle reordered the timeline; this one flips Media3's shuffle mode. The holder's side of that is pinned here against players that
 * fail the test if anything is published; the engine's side (the flag, and nothing else) is pinned in the boundary tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class ShuffleToggleTest {

    private val queueSize = 5_000

    private var flag = false
    private val engine: DualPlayerEngine = mockk(relaxed = true) {
        every { isShuffleEnabled } answers { flag }
        // No request waits in these tests, so what shuffle is going to be is what it is (B19-S2).
        every { shuffleIntent } answers { flag }
        every { setShuffleEnabled(any()) } answers { flag = firstArg() }
    }
    private val controller: MediaController = mockk(relaxed = true) {
        every { mediaItemCount } returns queueSize
        every { currentMediaItemIndex } returns 1_234
    }
    private val master: Player = mockk(relaxed = true) {
        every { mediaItemCount } returns queueSize
        every { currentMediaItemIndex } returns 1_234
    }
    private val appContext: Context = mockk(relaxed = true)
    private val powerManager: PowerManager = mockk(relaxed = true)

    init {
        every { appContext.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { powerManager.isInteractive } returns true
        every { engine.masterPlayer } returns master
    }

    private fun holder() = PlaybackStateHolder(
        dualPlayerEngine = engine,
        userPreferencesRepository = mockk<UserPreferencesRepository>(relaxed = true),
        queueStateHolder = mockk(relaxed = true),
        appContext = appContext,
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        dispatchers = TestDispatcherProvider(Dispatchers.Unconfined),
    ).also { it.setMediaController(controller) }

    private fun assertNothingPublished(player: Player) {
        verify(exactly = 0) { player.setMediaItems(any<List<MediaItem>>()) }
        verify(exactly = 0) { player.setMediaItems(any<List<MediaItem>>(), any<Boolean>()) }
        verify(exactly = 0) { player.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) }
        verify(exactly = 0) { player.setMediaItem(any<MediaItem>()) }
        verify(exactly = 0) { player.addMediaItems(any<List<MediaItem>>()) }
        verify(exactly = 0) { player.addMediaItems(any<Int>(), any<List<MediaItem>>()) }
        verify(exactly = 0) { player.replaceMediaItems(any<Int>(), any<Int>(), any<List<MediaItem>>()) }
        verify(exactly = 0) { player.replaceMediaItem(any<Int>(), any<MediaItem>()) }
        verify(exactly = 0) { player.moveMediaItem(any<Int>(), any<Int>()) }
        verify(exactly = 0) { player.moveMediaItems(any<Int>(), any<Int>(), any<Int>()) }
        verify(exactly = 0) { player.removeMediaItem(any<Int>()) }
        verify(exactly = 0) { player.removeMediaItems(any<Int>(), any<Int>()) }
        verify(exactly = 0) { player.clearMediaItems() }
        // The song that is playing is not touched either: no seek, no stop, no re-prepare.
        verify(exactly = 0) { player.seekTo(any<Long>()) }
        verify(exactly = 0) { player.seekTo(any<Int>(), any<Long>()) }
        verify(exactly = 0) { player.stop() }
        verify(exactly = 0) { player.prepare() }
        verify(exactly = 0) { player.pause() }
    }

    @Test
    fun `turning shuffle on publishes nothing and leaves the playing song alone`() {
        val holder = holder()

        holder.toggleShuffle()

        verify(exactly = 1) { engine.setShuffleEnabled(true) }
        assertThat(holder.stablePlayerState.value.isShuffleEnabled).isTrue()
        assertNothingPublished(controller)
        assertNothingPublished(master)
    }

    @Test
    fun `turning it off publishes nothing either, and the queue is the one that was loaded`() {
        val holder = holder()
        holder.toggleShuffle()

        holder.toggleShuffle()

        verify(exactly = 1) { engine.setShuffleEnabled(false) }
        assertThat(holder.stablePlayerState.value.isShuffleEnabled).isFalse()
        assertNothingPublished(controller)
        assertNothingPublished(master)
    }

    @Test
    fun `there is no cooldown and no job to race a second tap`() {
        val holder = holder()

        repeat(5) { holder.toggleShuffle() }

        // Five taps are five flips (on, off, on, off, on), not one flip and four swallowed (B16 dropped taps for 400 ms and
        // while its rebuild ran, which is also how a tap during the rebuild raced a skip).
        verify(exactly = 5) { engine.setShuffleEnabled(any()) }
        assertThat(holder.stablePlayerState.value.isShuffleEnabled).isTrue()
    }

    @Test
    fun `asking for the state it already has changes nothing`() {
        val holder = holder()
        holder.setShuffleEnabled(false)
        holder.setShuffleEnabled(false)
        assertThat(holder.stablePlayerState.value.isShuffleEnabled).isFalse()
        assertNothingPublished(controller)
    }

    @Test
    fun `the holder never reads the queue to toggle it`() {
        val holder = holder()
        holder.toggleShuffle()
        verify(exactly = 0) { controller.getMediaItemAt(any()) }
        verify(exactly = 0) { master.getMediaItemAt(any()) }
        verify(exactly = 0) { controller.currentTimeline }
    }

    // --- the position the queue screen highlights -----------------------------------------------------

    private fun playerWith(timeline: Timeline, index: Int, shuffle: Boolean): Player = mockk(relaxed = true) {
        every { currentTimeline } returns timeline
        every { currentMediaItemIndex } returns index
        every { shuffleModeEnabled } returns shuffle
    }

    @Test
    fun `without shuffle the queue position is the player's index`() {
        every { engine.isUsingWindowedQueue() } returns false
        val player = playerWith(OrderedTestTimeline.of(2, 0, 3, 1), index = 3, shuffle = false)
        assertThat(holder().queuePositionOf(player)).isEqualTo(3)
    }

    @Test
    fun `with shuffle the queue position is the row the song sits in, in play order`() {
        every { engine.isUsingWindowedQueue() } returns false
        // Played in the order 2, 0, 3, 1: the song at timeline index 3 is the third row.
        val player = playerWith(OrderedTestTimeline.of(2, 0, 3, 1), index = 3, shuffle = true)
        assertThat(holder().queuePositionOf(player)).isEqualTo(2)
    }

    @Test
    fun `a window the engine holds is listed whole by the engine, and its position is the engine's`() {
        every { engine.isUsingWindowedQueue() } returns true
        every { engine.getCurrentAbsoluteIndex() } returns 3_017
        val player = playerWith(OrderedTestTimeline.of(0, 1), index = 1, shuffle = true)
        assertThat(holder().queuePositionOf(player)).isEqualTo(3_017)
    }

    @Test
    fun `the order is read once per timeline object`() {
        every { engine.isUsingWindowedQueue() } returns false
        val timeline = OrderedTestTimeline.of(2, 0, 3, 1)
        val player = playerWith(timeline, index = 0, shuffle = true)
        val holder = holder()
        assertThat(holder.playOrderOf(player)).isSameInstanceAs(holder.playOrderOf(player))

        // A new timeline (the queue or its order changed) is read again.
        every { player.currentTimeline } returns OrderedTestTimeline.of(0, 1, 2, 3)
        assertThat(holder.playOrderOf(player).toList()).containsExactly(0, 1, 2, 3).inOrder()
    }
}
