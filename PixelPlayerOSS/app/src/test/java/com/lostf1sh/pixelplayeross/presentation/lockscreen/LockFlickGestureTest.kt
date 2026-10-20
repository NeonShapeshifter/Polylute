package com.lostf1sh.pixelplayeross.presentation.lockscreen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch.Delivery
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch.Sample
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B29-L1 / L3: the lock surface's flick, driven by synthetic touch input on the real composable (density 2.0, the
 * HONOR's: 360 × 805 dp, a screen of 720 × 1610 px).
 *
 * **How the gesture reaches the app.** `adb shell input swipe` (and a real thumb) produce a DOWN, a run of MOVEs and an UP.
 * The platform batches the MOVEs of one frame into one event (with the earlier samples as history) and hands over the UP
 * right behind the last batch, **in the same message**. So whatever the surface launched for the last batch (a coroutine
 * posted to the UI dispatcher) has not run when the release is handled. And when the main thread was busy for a few frames
 * (a debug build; the wake), the whole gesture waits in the queue and is handled in one message.
 *
 * Both are driven here, as real [MotionEvent]s delivered to the Compose view in one `runOnUiThread` block, with the
 * **standard** test dispatcher for composition (the `v2` rule): a coroutine launched from a pointer callback runs only
 * when the test lets the scheduler run, as it does after the message on a phone. (The `v1` rule resumes launched coroutines
 * on the spot, so it cannot show the problem: that is why `LockSurfaceBehaviourTest`, which uses it, always passed.)
 *
 *  - **FRAMED**: one batch of MOVEs per 16 ms frame, the scheduler runs between frames; the last batch and the UP together;
 *  - **STALLED**: the main thread was busy from the touch-down to the release: the whole gesture in one message.
 *
 * The decision depends on what the finger did, not on when the frames ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class LockFlickGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)

    private fun song() = Song(
        id = "1", title = "39", artist = "Queen", artistId = 7L, album = "A Night at the Opera", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val calls = mutableListOf<String>()
    private val revealing = mutableListOf<Boolean>()
    private val dismissed get() = calls.count { it == "dismiss" }
    private val controlCalls get() = calls.filter { it != "dismiss" }

    private fun setLock() = compose.setContent {
        DapLockScreen(
            song = song(), isPlaying = true, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF,
            currentPositionProvider = { 0L }, totalDuration = 280_000L, outputLine = null, batteryPercent = null,
            onPrevious = { calls += "previous" }, onPlayPause = { calls += "playPause" }, onNext = { calls += "next" },
            onToggleShuffle = { calls += "shuffle" }, onToggleRepeat = { calls += "repeat" },
            onDismiss = { calls += "dismiss" }, onRevealingChange = { revealing += it },
        )
    }

    private val touch by lazy { SyntheticTouch(compose) }
    private fun swipe(from: Offset, dyPx: Float, millis: Long, delivery: Delivery) = touch.swipe(from, dyPx, millis, delivery)
    private fun deliver(samples: List<Sample>, delivery: Delivery) = touch.deliver(samples, delivery)
    private fun path(from: Offset, via: List<Offset>, millis: Long) = touch.path(from, via, millis)
    private fun tap(at: Offset) = touch.tap(at)

    private fun centreOf(description: String): Offset =
        compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.center

    private fun centreOfText(text: String): Offset = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center

    private val midScreen = Offset(360f, 800f)

    // ── the reviewer's cases, both deliveries ─────────────────────────────────────────────────────────

    @Test
    fun `450 px in 100 ms from mid-screen opens it, framed`() {
        setLock()
        swipe(midScreen, -450f, 100, Delivery.FRAMED)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `450 px in 100 ms from mid-screen opens it when the whole gesture is handled in one message`() {
        setLock()
        swipe(midScreen, -450f, 100, Delivery.STALLED)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `600 px in 250 ms opens it, framed and in one message`() {
        setLock()
        swipe(midScreen, -600f, 250, Delivery.STALLED)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `700 px in 250 ms opens it, framed`() {
        setLock()
        swipe(midScreen, -700f, 250, Delivery.FRAMED)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a short honest flick, 70 dp in 60 ms, opens it, framed and in one message`() {
        setLock()
        swipe(midScreen, -140f, 60, Delivery.STALLED)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a short honest flick, 70 dp in 60 ms, opens it when it is framed`() {
        setLock()
        swipe(midScreen, -140f, 60, Delivery.FRAMED)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a slow long drag past the travel opens it in one message`() {
        setLock()
        swipe(midScreen, -360f, 1_500, Delivery.STALLED)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a slow long drag past the travel opens it, framed`() {
        setLock()
        swipe(midScreen, -360f, 1_500, Delivery.FRAMED)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a slow short drag springs back in one message too, and the surface ends at rest`() {
        setLock()
        swipe(midScreen, -100f, 1_000, Delivery.STALLED)
        assertThat(dismissed).isEqualTo(0)
        assertThat(revealing.lastOrNull() ?: false).isFalse()
    }

    @Test
    fun `a downward drag never opens it, in one message either`() {
        setLock()
        swipe(midScreen, 300f, 60, Delivery.STALLED)
        assertThat(dismissed).isEqualTo(0)
        assertThat(revealing).doesNotContain(true)
    }

    // ── starting on a control (L1: a child must not take the gesture; L3: a flick must press nothing) ──

    private val previous get() = s(R.string.lock_transport_previous)
    private val next get() = s(R.string.lock_transport_next)
    private val playPause get() = s(R.string.cd_pause)
    private val shuffle get() = s(R.string.player_shuffle)
    private val repeat get() = s(R.string.lock_transport_repeat)
    private val wayOut get() = s(R.string.lock_screen_open_app)

    private fun flickFrom(control: String, delivery: Delivery) {
        setLock()
        swipe(centreOf(control), -450f, 100, delivery)
        assertThat(controlCalls).isEmpty()
        assertThat(dismissed).isEqualTo(1)
    }

    @Test fun `a flick that starts on Previous opens the app and presses nothing, framed`() = flickFrom(previous, Delivery.FRAMED)
    @Test fun `a flick that starts on Previous opens the app and presses nothing, in one message`() = flickFrom(previous, Delivery.STALLED)
    @Test fun `a flick that starts on Next opens the app and presses nothing, framed`() = flickFrom(next, Delivery.FRAMED)
    @Test fun `a flick that starts on Next opens the app and presses nothing, in one message`() = flickFrom(next, Delivery.STALLED)
    @Test fun `a flick that starts on Play opens the app and presses nothing, framed`() = flickFrom(playPause, Delivery.FRAMED)
    @Test fun `a flick that starts on Play opens the app and presses nothing, in one message`() = flickFrom(playPause, Delivery.STALLED)
    @Test fun `a flick that starts on Shuffle opens the app and presses nothing, in one message`() = flickFrom(shuffle, Delivery.STALLED)
    @Test fun `a flick that starts on Repeat opens the app and presses nothing, in one message`() = flickFrom(repeat, Delivery.STALLED)

    private fun flickFromWayOut(delivery: Delivery) {
        setLock()
        swipe(centreOfText(wayOut), -450f, 100, delivery)
        // once: the flick opens it, and the button under the finger does not open it a second time
        assertThat(controlCalls).isEmpty()
        assertThat(dismissed).isEqualTo(1)
    }

    @Test fun `a flick that starts on the way-out button opens the app once, framed`() = flickFromWayOut(Delivery.FRAMED)
    @Test fun `a flick that starts on the way-out button opens the app once, in one message`() = flickFromWayOut(Delivery.STALLED)

    /** The reviewer's failing case: from the bottom, on "Abrir la aplicación" (y 1400-1500 of 1610 px). */
    @Test
    fun `a flick from the bottom, on the way-out button, opens the app in one message`() {
        setLock()
        swipe(Offset(360f, 1450f), -700f, 150, Delivery.STALLED)
        assertThat(dismissed).isEqualTo(1)
    }

    // A flick the surface rejects must not fall through to the control under the finger.

    private fun rejectedFrom(control: String, delivery: Delivery) {
        setLock()
        // 20 dp up over 600 ms: past the touch slop, short of the fling's minimum travel, and slow
        swipe(centreOf(control), -40f, 600, delivery)
        assertThat(dismissed).isEqualTo(0)
        assertThat(controlCalls).isEmpty()
    }

    @Test fun `a slow short drag that starts on Previous is rejected and presses nothing, framed`() = rejectedFrom(previous, Delivery.FRAMED)
    @Test fun `a slow short drag that starts on Previous is rejected and presses nothing, in one message`() = rejectedFrom(previous, Delivery.STALLED)
    @Test fun `a slow short drag that starts on Next is rejected and presses nothing, in one message`() = rejectedFrom(next, Delivery.STALLED)
    @Test fun `a slow short drag that starts on Play is rejected and presses nothing, in one message`() = rejectedFrom(playPause, Delivery.STALLED)

    @Test
    fun `a downward flick that starts on Previous presses nothing`() {
        setLock()
        swipe(centreOf(previous), 300f, 80, Delivery.STALLED)
        assertThat(calls).isEmpty()
    }

    @Test
    fun `a drag that goes up and comes back to the same button before the release presses nothing`() {
        setLock()
        val start = centreOf(previous)
        // 30 dp up and back to where it started, then lift: past the slop, so not a tap. Framed: the excursion is seen.
        deliver(path(start, listOf(Offset(start.x, start.y - 60f), start), 500), Delivery.FRAMED)
        assertThat(calls).isEmpty()
    }

    // Taps still work, and are not flicks.

    private fun tapOn(control: String, expected: String) {
        setLock()
        tap(centreOf(control))
        assertThat(calls).containsExactly(expected)
    }

    @Test fun `a tap on Previous calls previous, once, and does not open the app`() = tapOn(previous, "previous")
    @Test fun `a tap on Next calls next`() = tapOn(next, "next")
    @Test fun `a tap on Play calls playPause`() = tapOn(playPause, "playPause")
    @Test fun `a tap on Shuffle calls shuffle`() = tapOn(shuffle, "shuffle")
    @Test fun `a tap on Repeat calls repeat`() = tapOn(repeat, "repeat")

    @Test
    fun `a tap on the way-out button opens the app, once`() {
        setLock()
        tap(centreOfText(wayOut))
        assertThat(calls).containsExactly("dismiss")
    }

    @Test
    fun `a tap with a little wobble, under the touch slop, is still a tap on Previous`() {
        setLock()
        val at = centreOf(previous)
        // 5 px (2.5 dp) of drift in 80 ms: under the 8 dp slop
        deliver(listOf(Sample(0, at.x, at.y), Sample(40, at.x, at.y - 3f), Sample(80, at.x, at.y - 5f)), Delivery.FRAMED)
        assertThat(calls).containsExactly("previous")
    }
}
