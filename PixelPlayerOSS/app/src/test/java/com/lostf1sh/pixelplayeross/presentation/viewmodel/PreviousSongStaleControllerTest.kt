package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.MainCoroutineExtension
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
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
 * B29-L3: a Previous that goes back a track nobody asked to go back to.
 *
 * 2026-10-17 20:41:18: a `previous` (`reason=2`, to 6311) 47 s into STORM II (6312). Nothing on the lock surface presses it
 * ([com.lostf1sh.pixelplayeross.presentation.lockscreen.LockFlickGestureTest] pins that a flick presses no control), but
 * the decision "restart or go back" was taken with the position of whichever player the app's controller reported, and
 * that controller was still on STORM I (L2). Its position for the song it believed was current was a few seconds in, so
 * 47 s into the real song the answer was "within 3 s: go back".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class PreviousSongStaleControllerTest {

    private val engine: DualPlayerEngine = mockk(relaxed = true)
    private val enginePlayer = mockk<Player>(relaxed = true)
    private val controller = mockk<MediaController>(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)
    private val stormOne = MediaItem.Builder().setMediaId("6311").build()
    private val stormTwo = MediaItem.Builder().setMediaId("6312").build()

    init {
        every { appContext.getSystemService(Context.POWER_SERVICE) } returns mockk<PowerManager>(relaxed = true)
        every { engine.masterPlayer } returns enginePlayer
        every { engine.isTransitionRunning() } returns false
        every { controller.isConnected } returns true
        every { controller.hasPreviousMediaItem() } returns true
        every { enginePlayer.hasPreviousMediaItem() } returns true
    }

    private fun holder() = PlaybackStateHolder(
        dualPlayerEngine = engine,
        userPreferencesRepository = mockk<UserPreferencesRepository>(relaxed = true),
        queueStateHolder = mockk(relaxed = true),
        appContext = appContext,
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        dispatchers = TestDispatcherProvider(Dispatchers.Unconfined),
    ).also { it.setMediaController(controller) }

    @Test
    fun `47 s into the real song a stale controller that is 2 s into the old one does not send Previous back a track`() {
        every { enginePlayer.currentMediaItem } returns stormTwo
        every { enginePlayer.currentPosition } returns 47_000L
        every { controller.currentMediaItem } returns stormOne
        every { controller.currentPosition } returns 2_000L

        holder().previousSong()

        verify { controller.seekTo(0L) }
        verify(exactly = 0) { controller.seekToPreviousMediaItem() }
    }

    @Test
    fun `the full player predicts with the same position, so the carousel does not animate to the previous artwork`() {
        every { enginePlayer.currentMediaItem } returns stormTwo
        every { enginePlayer.currentPosition } returns 47_000L
        every { controller.currentMediaItem } returns stormOne
        every { controller.currentPosition } returns 2_000L

        assertThat(holder().previousDecisionPositionMs()).isEqualTo(47_000L)
    }

    @Test
    fun `during a crossfade the controller shows the incoming song and its own position is the one that counts`() {
        every { engine.isTransitionRunning() } returns true
        every { enginePlayer.currentMediaItem } returns stormOne
        every { enginePlayer.currentPosition } returns 230_000L
        every { controller.currentMediaItem } returns stormTwo
        every { controller.currentPosition } returns 1_500L

        holder().previousSong()

        verify { controller.seekToPreviousMediaItem() }
        verify(exactly = 0) { controller.seekTo(0L) }
    }

    @Test
    fun `a genuine Previous early in the real song still goes back, with the controller up to date`() {
        every { enginePlayer.currentMediaItem } returns stormTwo
        every { enginePlayer.currentPosition } returns 1_200L
        every { controller.currentMediaItem } returns stormTwo
        every { controller.currentPosition } returns 600L

        holder().previousSong()

        verify { controller.seekToPreviousMediaItem() }
    }
}
