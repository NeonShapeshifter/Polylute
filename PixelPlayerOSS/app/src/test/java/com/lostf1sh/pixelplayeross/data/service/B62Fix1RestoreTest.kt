package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueItemSnapshot
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class B62Fix1RestoreTest {
    private val saved = PlaybackQueueSnapshot(
        items = listOf(PlaybackQueueItemSnapshot("a", "file:///a"), PlaybackQueueItemSnapshot("b", "file:///b")),
        currentIndex = 1, currentMediaId = "b", currentPositionMs = 45_678L, playWhenReady = true,
    )

    @Test fun `a six second restore keeps the saved queue index and position and installs paused`() = runTest {
        var installed: PlaybackQueueSnapshot? = null
        val restore = PlaybackRestoreBarrier(this) { delay(6_000); installed = saved.copy(playWhenReady = false) }
        val startup = async { restore.await() }
        runCurrent(); advanceTimeBy(6_001); runCurrent()
        startup.await()
        assertThat(installed).isEqualTo(saved.copy(playWhenReady = false))
        assertThat(restore.isComplete).isTrue()
    }

    @Test fun `a timed out widget never plays even when the restore finishes later`() = runTest {
        var installed: PlaybackQueueSnapshot? = null
        var plays = 0
        val restore = PlaybackRestoreBarrier(this) { delay(6_000); installed = saved.copy(playWhenReady = false) }
        val widget = async { if (restore.await()) plays++ }
        runCurrent(); advanceTimeBy(4_001); runCurrent()
        assertThat(widget.isCompleted).isTrue()
        widget.await()
        assertThat(plays).isEqualTo(0)
        assertThat(installed).isNull()
        advanceTimeBy(2_000); runCurrent()
        assertThat(installed).isEqualTo(saved.copy(playWhenReady = false))
        assertThat(plays).isEqualTo(0)
    }
}
