package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.MainCoroutineExtension
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * B13-6. [PlayPauseCommandGuard] holds, for 700 ms, a report that contradicts a tap. If another controller really resumes
 * inside that window (the notification, a Bluetooth headset), its report is the only thing that will ever say "playing",
 * it is held, and when the window ends nothing re-reads the player: the button stayed on "paused" while the music played.
 * The tests send no event after the window, because that is exactly the case that was lost (the earlier tests sent a second
 * report after the window and so hid it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class PlayPauseResyncTest {

    private val dualPlayerEngine: DualPlayerEngine = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)
    private val powerManager: PowerManager = mockk(relaxed = true)

    init {
        every { appContext.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { powerManager.isInteractive } returns true
    }

    private class Rig(val holder: PlaybackStateHolder, val scope: TestScope) {
        /** What the player really says; the test changes it the way the device would. */
        var real: Pair<Boolean, Boolean>? = true to true
        var reads = 0
        val shown get() = holder.stablePlayerState.value

        init {
            holder.guardClockMs = { scope.currentTime }
            holder.realPlaybackState = { reads++; real }
            holder.updateStablePlayerState { it.copy(isPlaying = true, playWhenReady = true) }
        }

        fun report(isPlaying: Boolean, playWhenReady: Boolean) =
            holder.updateStablePlayerState("controller.onIsPlayingChanged") { it.copy(isPlaying = isPlaying, playWhenReady = playWhenReady) }
    }

    private fun TestScope.rig(): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val holder = PlaybackStateHolder(
            dualPlayerEngine = dualPlayerEngine,
            userPreferencesRepository = mockk<UserPreferencesRepository>(relaxed = true),
            queueStateHolder = mockk(relaxed = true),
            appContext = appContext,
            appScope = CoroutineScope(SupervisorJob() + dispatcher),
            dispatchers = TestDispatcherProvider(dispatcher),
        )
        return Rig(holder, this)
    }

    @Test
    fun `a tap on pause and a real resume from another controller, with no further event, shows playing when the window ends`() = runTest {
        val r = rig()
        r.holder.noteUserPlayPause(playbackWanted = false)                // t = 0: the button shows paused at once
        r.real = false to false                                           // the pause lands
        assertThat(r.shown.isPlaying).isFalse()

        advanceTimeBy(300)                                                // t = 300
        r.real = true to true                                             // the notification resumes it
        r.report(isPlaying = true, playWhenReady = true)                  // ... and its report arrives inside the window
        assertThat(r.shown.isPlaying).isFalse()                           // held: indistinguishable from an echo, 300 ms after the tap

        advanceTimeBy(399)                                                // t = 699: still inside the window
        runCurrent()
        assertThat(r.shown.isPlaying).isFalse()

        advanceTimeBy(101)                                                // t = 800: the window is over, and no event has arrived since 300
        runCurrent()
        assertThat(r.shown.isPlaying).isTrue()                            // the player's real state is shown
        assertThat(r.shown.playWhenReady).isTrue()
    }

    @Test
    fun `a pause that stands is still shown as paused after the window, the read changes nothing`() = runTest {
        val r = rig()
        r.holder.noteUserPlayPause(false)
        r.real = false to false

        advanceTimeBy(800)
        runCurrent()

        assertThat(r.reads).isEqualTo(1)                                  // the read did happen
        assertThat(r.shown.isPlaying).isFalse()
        assertThat(r.shown.playWhenReady).isFalse()
    }

    @Test
    fun `a second tap starts its own window and the first read is dropped`() = runTest {
        val r = rig()
        r.holder.noteUserPlayPause(false)                                 // t = 0, would read at ~750
        advanceTimeBy(400)
        r.real = true to true
        r.holder.noteUserPlayPause(true)                                  // t = 400: play, reads at ~1150

        advanceTimeBy(500)                                                // t = 900: past the first read's time
        runCurrent()
        assertThat(r.reads).isEqualTo(0)

        advanceTimeBy(300)                                                // t = 1200
        runCurrent()
        assertThat(r.reads).isEqualTo(1)
        assertThat(r.shown.isPlaying).isTrue()
    }

    @Test
    fun `with no player to ask nothing is written`() = runTest {
        val r = rig()
        r.real = null
        r.holder.noteUserPlayPause(false)

        advanceTimeBy(800)
        runCurrent()

        assertThat(r.shown.isPlaying).isFalse()                           // what the tap showed
    }
}
