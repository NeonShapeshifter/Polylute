package com.lostf1sh.pixelplayeross.data.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueItemSnapshot
import com.lostf1sh.pixelplayeross.data.service.player.PlayPauseDecision
import com.lostf1sh.pixelplayeross.ui.glancewidget.widgetInfoFromSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class B62PlaybackRestoreTest {
    @Test fun playBeforeRestorePlaysTheSavedItemAtItsPosition() = runTest {
        var items = emptyList<MediaItem>()
        var position = 0L
        var playing = false
        var restores = 0
        val player = mockk<Player>(relaxed = true)
        every { player.mediaItemCount } answers { items.size }
        every { player.playbackState } returns Player.STATE_IDLE
        every { player.playWhenReady } answers { playing }
        every { player.play() } answers { playing = items.isNotEmpty() }
        every { player.currentPosition } answers { position }
        every { player.currentMediaItemIndex } returns 1
        every { player.getMediaItemAt(any()) } answers { items[firstArg()] }
        val restore = PlaybackRestoreBarrier(this) {
            restores++
            delay(100)
            if (RestoreQueuePolicy.mayApply(player.mediaItemCount)) {
                items = listOf(MediaItem.fromUri("file:///a.flac"), MediaItem.fromUri("file:///b.flac"))
                position = 23_456L
            }
        }
        val startup = async { restore.await() }
        val command = async {
            assertThat(restore.await()).isTrue()
            PlayPauseDecision.apply(player)
        }
        assertThat(playing).isFalse()
        startup.await(); command.await()
        assertThat(playing).isTrue()
        assertThat(position).isEqualTo(23_456L)
        assertThat(restores).isEqualTo(1)
        val resume = resumptionFromPlayer(player)
        assertThat(resume.mediaItems).containsExactlyElementsIn(items).inOrder()
        assertThat(resume.startIndex).isEqualTo(1)
        assertThat(resume.startPositionMs).isEqualTo(23_456L)
    }

    @Test fun timeoutDropsTheWaitButTheRestoreCanInstallAQueueLater() = runTest {
        var applied = false
        val restore = PlaybackRestoreBarrier(this, timeoutMs = 100) { delay(5_000); applied = true }
        assertThat(restore.await()).isFalse()
        delay(6_000)
        assertThat(applied).isTrue()
        assertThat(restore.await()).isTrue()
    }

    @Test fun cancellingOneWaiterDoesNotCancelTheSingleRestore() = runTest {
        var restores = 0
        val restore = PlaybackRestoreBarrier(this) { restores++; delay(100) }
        val waiter = launch { restore.await() }
        delay(1); waiter.cancel()
        assertThat(restore.await()).isTrue()
        assertThat(restores).isEqualTo(1)
    }

    @Test fun aNewQueueLoadedDuringDiskReadWins() = runTest {
        var queue = emptyList<String>()
        val restore = PlaybackRestoreBarrier(this) {
            delay(100)
            if (RestoreQueuePolicy.mayApply(queue.size)) queue = listOf("old")
        }
        val startup = async { restore.await() }
        delay(10); queue = listOf("new")
        startup.await()
        assertThat(queue).containsExactly("new")
    }

    @Test fun widgetUsesTheSavedOccurrenceMetadataCoverAndPositionWhilePaused() {
        val snapshot = PlaybackQueueSnapshot(
            items = listOf(
                PlaybackQueueItemSnapshot("a", "file:///a", "A", "Artist A", artworkUri = "file:///a.png"),
                PlaybackQueueItemSnapshot("b", "file:///b", "B", "Artist B", artworkUri = "file:///b.png", durationMs = 300_000),
            ), currentIndex = 1, currentMediaId = "b", currentPositionMs = 45_678, playWhenReady = true,
        )
        val info = widgetInfoFromSnapshot(snapshot)!!
        assertThat(info.songTitle).isEqualTo("B")
        assertThat(info.artistName).isEqualTo("Artist B")
        assertThat(info.albumArtUri).isEqualTo("file:///b.png")
        assertThat(info.currentPositionMs).isEqualTo(45_678)
        assertThat(info.isPlaying).isFalse()
        assertThat(widgetInfoFromSnapshot(null)).isNull()
        assertThat(widgetInfoFromSnapshot(snapshot.copy(items = emptyList()))).isNull()
    }
    @Test fun coldWidgetKeepsTheSavedQueuePreviewsAndTheirShuffleOrder() {
        val items = (0..5).map { PlaybackQueueItemSnapshot("$it", "file:///$it", "Song $it", artworkUri = "file:///$it.png") }
        val snapshot = PlaybackQueueSnapshot(items, currentIndex = 1)
        val ordinary = widgetInfoFromSnapshot(snapshot)!!
        assertThat(ordinary.queue.map { it.mediaId }).containsExactly("2", "3", "4", "5").inOrder()
        assertThat(ordinary.queue.first().albumArtUri).isEqualTo("file:///2.png")
        val shuffled = widgetInfoFromSnapshot(snapshot.copy(shuffleEnabled = true, shuffleOrder = listOf(3, 1, 5, 2, 0, 4)))!!
        assertThat(shuffled.queue.map { it.mediaId }).containsExactly("5", "2", "0", "4").inOrder()
    }

    @Test fun aColdDisplayUsesTheSnapshotAndThenHandsBackToTheLiveWidget() {
        val lifetime = com.lostf1sh.pixelplayeross.ui.glancewidget.WidgetPlaybackLifetime
        val live = com.lostf1sh.pixelplayeross.data.model.PlayerInfo(songTitle = "older", isPlaying = true)
        val saved = widgetInfoFromSnapshot(PlaybackQueueSnapshot(listOf(PlaybackQueueItemSnapshot("new", "file:///new", "Saved song"))))!!
        try {
            lifetime.serviceAlive = false
            val cold = com.lostf1sh.pixelplayeross.ui.glancewidget.widgetInfoForDisplay(live, saved)
            assertThat(cold.songTitle).isEqualTo("Saved song")
            assertThat(cold.isPlaying).isFalse()
            lifetime.serviceAlive = true
            assertThat(com.lostf1sh.pixelplayeross.ui.glancewidget.widgetInfoForDisplay(live, saved)).isEqualTo(live)
        } finally { lifetime.serviceAlive = false }
    }

    @Test fun theRealMedia3RecentResultAcceptsRestoredPlaybackMetadata() {
        val item = MediaItem.Builder().setMediaId("saved")
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                .setTitle("Saved title").setArtist("Saved artist").build()).build()
        val result = recentItemsForLibrary(item, 0, null)
        assertThat(result.resultCode).isEqualTo(androidx.media3.session.LibraryResult.RESULT_SUCCESS)
        val recent = result.value!!.single()
        assertThat(recent.mediaId).isEqualTo("saved")
        assertThat(recent.mediaMetadata.title.toString()).isEqualTo("Saved title")
        assertThat(recent.mediaMetadata.isPlayable).isTrue()
        assertThat(recent.mediaMetadata.isBrowsable).isFalse()
        assertThat(recentItemsForLibrary(item, 1, null).value).isEmpty()
        assertThat(recentItemsForLibrary(null, 0, null).value).isEmpty()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun theCommandWaitIsBoundedEvenWhenStorageDoesNotCooperateWithCancellation() = runTest {
        var applied = false
        val restore = PlaybackRestoreBarrier(this, timeoutMs = 100) {
            // A provider can keep its IO open after cancellation; only a live continuation may commit.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(5_000) }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            applied = true
        }
        val command = async { restore.await() }
        runCurrent()
        advanceTimeBy(101); runCurrent()
        try {
            assertThat(command.isCompleted).isTrue()
            assertThat(command.await()).isFalse()
        } finally { advanceUntilIdle() }
        assertThat(applied).isTrue()
    }

}
