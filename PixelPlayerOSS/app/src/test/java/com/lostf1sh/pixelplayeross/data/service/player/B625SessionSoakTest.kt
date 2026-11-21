package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.host.QueuePlayer
import com.lostf1sh.pixelplayeross.host.queueItem
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B625SessionSoakTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + dispatcher)
    private val clock = TestScope(dispatcher)
    private val remote = object : RemoteDeviceVolume {
        override val state = MutableStateFlow<RemoteVolumeState?>(null)
        override fun setLevel(level: Int) = Unit
    }
    private val retired = mutableListOf<WeakReference<Any>>()

    private fun wrap(player: Player) = MappingPlayer(FadingPlayer(player, appScope), context, remote, appScope)
    private fun collectGarbage() {
        repeat(4) { System.gc(); System.runFinalization(); Thread.sleep(20) }
    }
    private fun usedHeap(): Long {
        collectGarbage()
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }
    // Same post-publication retirement as MusicService. Reflection lets this regression run on
    // the before tree, which had no explicit retirement at all.
    private fun retire(old: MappingPlayer) {
        MappingPlayer::class.java.methods.firstOrNull { it.name == "detach" }?.invoke(old)
    }
    private fun session(id: String) = MediaLibrarySession.Builder(
        context, wrap(QueuePlayer(Looper.getMainLooper(), List(5000, ::queueItem))),
        object : MediaLibrarySession.Callback {},
    ).setId(id).build()

    private fun changeSongsAndSwap(session: MediaLibrarySession, generation: Int) {
        val current = (session.player as MappingPlayer).innerPlayer.let { (it as FadingPlayer).innerPlayer } as QueuePlayer
        repeat(200) { current.skipTo((generation * 200 + it) % 5000); current.publish() }
        swap(session, generation, realExo = false)
    }
    private fun swap(session: MediaLibrarySession, generation: Int, realExo: Boolean, duplicateRegistration: Boolean = true) {
        val old = session.player as MappingPlayer
        val fading = old.innerPlayer as FadingPlayer
        val inner = fading.innerPlayer
        retired += WeakReference(old)
        retired += WeakReference(fading)
        retired += WeakReference(inner)
        retired += WeakReference(inner.currentTimeline)
        if (inner.mediaItemCount > 0) retired += WeakReference(inner.getMediaItemAt(0))
        // Duplicate registration is legal under Player's set semantics. Before B62.5 it left
        // a remote-volume collector alive even after the consumer removed its listener.
        val listener = object : Player.Listener {}
        old.addListener(listener)
        if (duplicateRegistration) old.addListener(listener)
        old.removeListener(listener)
        fading.pause() // A session swap must retire a transport ramp too.
        val items = List(5000) { queueItem(generation * 10000 + it) }
        val next: Player = if (realExo) ExoPlayer.Builder(context).build().also { it.setMediaItems(items) }
            else QueuePlayer(Looper.getMainLooper(), items)
        inner.release() // Engine releases first; session then publishes the replacement.
        session.player = wrap(next)
        retire(old)
        clock.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun twentyOrdinarySwapsAlsoReleaseOldGraphsAfterTransportRampsFinish() {
        Dispatchers.setMain(dispatcher)
        val session = session("B625-ordinary")
        try {
            repeat(20) { swap(session, it + 1, realExo = false, duplicateRegistration = false) }
            clock.testScheduler.advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
            collectGarbage()
            println("B625 ordinary swaps=20 queue=5000 reachableOldGraphs=${retired.count { it.get() != null }}")
            assertThat(retired.count { it.get() != null }).isEqualTo(0)
        } finally { val old = session.player as MappingPlayer; session.release(); retire(old); appScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun twentyFiveLargeQueueRebuildsAndSongChangesReleaseOldSessionGraphs() {
        Dispatchers.setMain(dispatcher)
        val session = session("B625-soak")
        try {
            var baseline = 0L
            val samples = mutableListOf<Long>()
            for (generation in 1..25) {
                changeSongsAndSwap(session, generation)
                val heap = usedHeap()
                if (generation == 5) baseline = heap
                if (generation >= 5) samples += heap
            }
            val finalHeap = samples.last()
            println("B625 soak rebuilds=25 songChanges=5000 queue=5000 retainedHeapBytes=$samples deltaBytes=${finalHeap - baseline} reachableOldGraphs=${retired.count { it.get() != null }} activeJobs=${appJob.children.count()}")
            assertThat(retired.count { it.get() != null }).isEqualTo(0)
            assertThat(finalHeap - baseline).isLessThan(12L * 1024 * 1024)
            // Only the current wrapper's remote collector is allowed to remain.
            assertThat(appJob.children.count()).isAtMost(1)
        } finally { val old = session.player as MappingPlayer; session.release(); retire(old); appScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun twentyUnpreparedExoPlayersAreCollectibleAfterTheRealSessionSwap() {
        Dispatchers.setMain(dispatcher)
        val first = ExoPlayer.Builder(context).build().also { it.setMediaItems(List(5000, ::queueItem)) }
        val session = MediaLibrarySession.Builder(context, wrap(first), object : MediaLibrarySession.Callback {}).setId("B625-exo").build()
        try {
            repeat(20) { swap(session, it + 1, realExo = true) }
            clock.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            collectGarbage()
            // The first local is deliberately still in this method; all later old ExoPlayers,
            // wrappers, timelines and their unique first items must be gone.
            val remaining = retired.drop(5).count { it.get() != null }
            println("B625 real ExoPlayer rebuilds=20 queue=5000 reachableOldGraphs=$remaining")
            assertThat(remaining).isEqualTo(0)
        } finally {
            val old = session.player as MappingPlayer
            val player = (old.innerPlayer as FadingPlayer).innerPlayer
            session.release(); retire(old); player.release(); appScope.cancel(); Dispatchers.resetMain()
        }
    }
}
