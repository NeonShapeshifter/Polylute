package com.lostf1sh.pixelplayeross.presentation.lockscreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B25-S2 (a short flick opens the lock surface), S3 (its elapsed time ticks without waking the app under it) and S4 (the app
 * under it is not woken by the wake's first frame), on the real composables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class LockSurfaceBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun song() = Song(
        id = "1", title = "39", artist = "Queen", artistId = 7L, album = "A Night at the Opera", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    // ── S2: the gesture ──────────────────────────────────────────────────────────────────────────────

    private var dismissed = 0
    private val revealing = mutableListOf<Boolean>()

    private fun setLock() = compose.setContent {
        DapLockScreen(
            song = song(), isPlaying = true, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF,
            currentPositionProvider = { 0L }, totalDuration = 280_000L, outputLine = null, batteryPercent = null,
            onPrevious = {}, onPlayPause = {}, onNext = {}, onToggleShuffle = {}, onToggleRepeat = {},
            onDismiss = { dismissed++ }, onRevealingChange = { revealing += it },
        )
    }

    /** A vertical swipe of [travelDp] dp over [millis] ms from [startFraction] of the height; 3 px per dp (xxhdpi). */
    private fun swipeUp(travelDp: Float, millis: Long, startFraction: Float = 0.5f) {
        compose.onRoot().performTouchInput {
            val x = width / 2f
            val y0 = height * startFraction
            val y1 = y0 - travelDp * 3f
            down(Offset(x, y0))
            val steps = (millis / 8).toInt().coerceAtLeast(1)
            repeat(steps) { i -> moveTo(Offset(x, y0 + (y1 - y0) * (i + 1) / steps), delayMillis = millis / steps) }
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun `a short fast flick from the middle of the screen opens the app`() {
        setLock()
        swipeUp(travelDp = 70f, millis = 60)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a thumb flick of 60 dp in 100 ms, a short and quick one that is under the old thresholds, opens the app`() {
        // 600 dp/s over 60 dp: not 96 dp, not 1,200 dp/s, so the thresholds of B24 refused it
        setLock()
        swipeUp(travelDp = 60f, millis = 100)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a short fast flick that starts near the bottom, where the way-out button is, opens the app too`() {
        setLock()
        swipeUp(travelDp = 70f, millis = 60, startFraction = 0.93f)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a slow short drag springs back and does not open the app`() {
        setLock()
        swipeUp(travelDp = 50f, millis = 1_000)
        assertThat(dismissed).isEqualTo(0)
        // it was moved off the app while the finger was on it, and is back at rest now
        assertThat(revealing.last()).isFalse()
    }

    @Test
    fun `a slow drag past the travel threshold still opens the app`() {
        setLock()
        swipeUp(travelDp = 160f, millis = 1_500)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a pocket brush, short and quick, does not open the app`() {
        setLock()
        swipeUp(travelDp = 14f, millis = 40)
        assertThat(dismissed).isEqualTo(0)
    }

    @Test
    fun `a downward drag, however fast, never opens the app or moves the surface`() {
        setLock()
        compose.onRoot().performTouchInput {
            down(Offset(width / 2f, height * 0.4f))
            moveTo(Offset(width / 2f, height * 0.4f + 300f), delayMillis = 40)
            up()
        }
        compose.waitForIdle()
        assertThat(dismissed).isEqualTo(0)
        assertThat(revealing).doesNotContain(true)
    }

    // ── S3: the elapsed time ticks ───────────────────────────────────────────────────────────────────

    /** The player's position at the test clock's time, as a provider that counts how often it is asked. */
    private inner class FakePlayer {
        var started = 0L
        var calls = 0
        fun position(): Long {
            calls++
            return compose.mainClock.currentTime - started
        }
    }

    private fun setProgress(player: FakePlayer, playing: Boolean, host: Host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LockProgress(
                isPlaying = playing, songId = "1", currentPositionProvider = player::position, totalDuration = 280_000L,
                lifecycle = host.lifecycle,
            )
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `while playing the elapsed time advances every second`() {
        val player = FakePlayer()
        setProgress(player, playing = true)
        compose.onNodeWithText("00:00").assertExists()
        for (second in 1..5) {
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText("00:0$second").assertExists()
        }
    }

    @Test
    fun `paused, it does not advance and is read once`() {
        val player = FakePlayer()
        setProgress(player, playing = false)
        val calls = player.calls
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("00:00").assertExists()
        assertThat(player.calls).isEqualTo(calls)
    }

    @Test
    fun `with the screen off nothing is sampled, and the first read after it is the current position`() {
        val player = FakePlayer()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        setProgress(player, playing = true, host = host)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("00:02").assertExists()

        compose.runOnUiThread { host.registry.currentState = Lifecycle.State.CREATED } // stopped: the screen went off
        compose.mainClock.advanceTimeBy(100)
        val callsOff = player.calls
        compose.mainClock.advanceTimeBy(30_000)
        assertThat(player.calls).isEqualTo(callsOff)

        compose.runOnUiThread { host.registry.currentState = Lifecycle.State.RESUMED } // the screen is back
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("00:32").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("00:33").assertExists()
    }

    private var surfaceUp by androidx.compose.runtime.mutableStateOf(true)

    @Test
    fun `with the surface hidden nothing is sampled, and it samples again when it is back`() {
        val player = FakePlayer()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (surfaceUp) {
                LockProgress(
                    isPlaying = true, songId = "1", currentPositionProvider = player::position, totalDuration = 280_000L,
                    lifecycle = host.lifecycle,
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(player.calls).isGreaterThan(1)

        compose.runOnUiThread { surfaceUp = false; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        // the recomposition that removes it, and the cancellation that follows
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val callsHidden = player.calls
        compose.mainClock.advanceTimeBy(20_000)
        assertThat(player.calls).isEqualTo(callsHidden)

        compose.runOnUiThread { surfaceUp = true; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(100)
        assertThat(player.calls).isGreaterThan(callsHidden)
    }

    // ── S3 + S4: the app under the surface ───────────────────────────────────────────────────────────

    private val playing = MutableStateFlow("La Costa Del Silencio")
    private var appCompositions = 0

    @Composable
    private fun AppUnderneath() {
        val title by playing.collectAsStateWithLifecycle()
        SideEffect { appCompositions++ }
        Text(title)
    }

    @Test
    fun `with the covered app held the lock's time still ticks, and the app is never woken to make it tick`() {
        val player = FakePlayer()
        val screen = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screen) {
                Box(Modifier.fillMaxSize()) {
                    FreezeWhileCovered(frozen = true, hidden = true, modifier = Modifier.fillMaxSize()) { AppUnderneath() }
                    LockProgress(
                        isPlaying = true, songId = "1", currentPositionProvider = player::position, totalDuration = 280_000L,
                        lifecycle = screen.lifecycle,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        // the held app never started listening, and the lock still counts
        assertThat(playing.subscriptionCount.value).isEqualTo(0)
        for (second in 1..4) {
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText("00:0$second").assertExists()
            assertThat(playing.subscriptionCount.value).isEqualTo(0)
        }
    }

    @Test
    fun `the wake's first frame does not wake the covered app, because the cover was set when the screen went off`() {
        val screen = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val cover = CoverSignal()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screen) {
                FreezeWhileCovered(frozen = false, hidden = false, cover = cover, modifier = Modifier.fillMaxSize()) { AppUnderneath() }
            }
        }
        compose.waitForIdle()
        assertThat(playing.subscriptionCount.value).isEqualTo(1)

        // SCREEN_OFF arms the wake cycle: the app is held from here, then the activity stops
        compose.runOnUiThread { cover.covered = true }
        compose.runOnUiThread { screen.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        assertThat(playing.subscriptionCount.value).isEqualTo(0)
        for (n in 1..3) playing.value = "Canción $n"
        val before = appCompositions

        // SCREEN_ON: the activity starts and resumes. The surface is not on screen yet (it is reported a frame or two later).
        compose.runOnUiThread { screen.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertThat(appCompositions).isEqualTo(before)
        assertThat(playing.subscriptionCount.value).isEqualTo(0)

        // the cycle ends (dismissed): the app hears again and reads the latest, once
        compose.runOnUiThread { cover.covered = false }
        compose.waitForIdle()
        assertThat(playing.subscriptionCount.value).isEqualTo(1)
        compose.onNodeWithText("Canción 3").assertExists()
    }

    @Test
    fun `without the cover, which is how B24 worked, the same wake wakes the app before the surface can hold it`() {
        val screen = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        // `frozen` follows the surface's visibility, which arrives after the first frame: not yet at the wake
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screen) {
                FreezeWhileCovered(frozen = false, hidden = false, modifier = Modifier.fillMaxSize()) { AppUnderneath() }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { screen.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        for (n in 1..3) playing.value = "Canción $n"
        val before = appCompositions

        compose.runOnUiThread { screen.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()

        assertThat(appCompositions).isGreaterThan(before)
        assertThat(playing.subscriptionCount.value).isEqualTo(1)
    }
}
