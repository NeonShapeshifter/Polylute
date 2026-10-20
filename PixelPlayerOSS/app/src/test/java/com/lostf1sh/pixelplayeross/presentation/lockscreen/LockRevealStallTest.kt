package com.lostf1sh.pixelplayeross.presentation.lockscreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch.Sample
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * B29-fix1 F1: the lock flick on the real phone, where B29's composable-alone tests passed and `adb shell input swipe` still failed
 * (450 px in 100 ms opened 3 times in 10).
 *
 * **What the phone does that the composable alone does not.** The surface is revealed at the first pixel it moves
 * (`onRevealingChange(true)` lifts `FreezeWhileCovered`'s `hidden`), and the whole covered app is then measured, laid out and drawn for
 * the first time since it was covered: the gesture's first frame took 111 ms (84 ms of it in draw), frames F2 and F3 each handled one
 * more input event, and no frame after that handled any. The shell here is the same shape: [FreezeWhileCovered] over an app whose layout
 * blocks for 110 ms (wall time) the first time it is placed, in the same composition as the surface, with the reveal wired as
 * `MainActivity` wires it.
 *
 * **Where the moves went.** Two things reach the surface across that frame, and the test drives both:
 *
 *  - a real thumb: the moves that happened during the frame wait in the queue and arrive as **one batch with the earlier samples as
 *    history**, and the lift right behind. That arrives whole (the first two tests; they passed before this batch as well).
 *  - `adb shell input swipe`: it injects each event and waits for the app to finish it, so the frame holds the injector; when it wakes
 *    its clock is past the end of the swipe, and what it sends next is **the lift, at the swipe's end point**, with nothing between. There
 *    were no moves to lose; and the drag detector drops the movement an UP carries (it ends the drag instead of reporting it), while the
 *    velocity tracker forgets samples older than a 40 ms gap. The release read 40 dp of travel at no speed, and sprang back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class LockRevealStallTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private var revealing by mutableStateOf(false)
    private var heavyMeasures = 0
    private val calls = mutableListOf<String>()
    private val revealLog = mutableListOf<Boolean>()
    private val dismissed get() = calls.count { it == "dismiss" }
    private val controlCalls get() = calls.filter { it != "dismiss" }

    /** What the surface logs on a release (one line per gesture): the test reads it to see what the release was given. */
    private val lockLog = mutableListOf<String>()

    @Before
    fun plantLog() {
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == "DapLockScreen") lockLog += message
            }
        })
    }

    @After
    fun uprootLog() = Timber.uprootAll()

    private fun song() = Song(
        id = "1", title = "39", artist = "Queen", artistId = 7L, album = "A Night at the Opera", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private fun setShell() = compose.setContent {
        Box(Modifier.fillMaxSize()) {
            FreezeWhileCovered(frozen = true, hidden = !revealing, modifier = Modifier.fillMaxSize()) {
                Layout(content = {}, modifier = Modifier.fillMaxSize()) { _, c ->
                    heavyMeasures++
                    val end = System.nanoTime() + 110_000_000L
                    while (System.nanoTime() < end) { /* the covered app, laid out for the first time since it was covered */ }
                    layout(c.maxWidth, c.maxHeight) {}
                }
            }
            DapLockScreen(
                song = song(), isPlaying = true, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF,
                currentPositionProvider = { 0L }, totalDuration = 280_000L, outputLine = null, batteryPercent = null,
                onPrevious = { calls += "previous" }, onPlayPause = { calls += "playPause" }, onNext = { calls += "next" },
                onToggleShuffle = { calls += "shuffle" }, onToggleRepeat = { calls += "repeat" },
                onDismiss = { calls += "dismiss" },
                onRevealingChange = { revealing = it; revealLog += it },
            )
        }
    }

    private val touch by lazy { SyntheticTouch(compose) }
    private val x = 360f
    private val midScreen = Offset(x, 800f)

    private fun titleTop() = compose.onNodeWithText("39").fetchSemanticsNode().boundsInRoot.top

    private fun centreOf(description: String): Offset =
        compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.center

    private fun centreOfText(text: String): Offset = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center

    // ── a real thumb: the moves arrive in one batch after the long frame ──────────────────────────────────

    private fun thumb(from: Offset, dyPx: Float, millis: Long): List<Sample> = touch.path(from, listOf(Offset(from.x, from.y + dyPx)), millis)

    @Test
    fun `the moves handled after the long frame reach the surface, it sits where the finger took it`() {
        setShell()
        val restTop = titleTop()
        // down, a first move past the slop, the long frame (the reveal), then one batch to 450 px and the finger stays down
        touch.deliverInBatches(thumb(midScreen, -450f, 100), cutsMs = listOf(8L, 83L), settleMs = 0, lift = false)
        assertThat(heavyMeasures).isAtLeast(1)
        // the surface follows the finger from the first pixel past the 8 dp slop
        assertThat(restTop - titleTop()).isGreaterThan(300f)
    }

    @Test
    fun `a real thumb flicks 450 px in 100 ms across a long first frame and opens it`() {
        setShell()
        touch.deliverInBatches(thumb(midScreen, -450f, 100), cutsMs = listOf(8L, 83L))
        assertThat(heavyMeasures).isAtLeast(1)
        assertThat(revealLog).containsAtLeast(false, true).inOrder()
        assertThat(dismissed).isEqualTo(1)
    }

    // ── adb shell input swipe: the injector is held by the long frame, the lift follows the early moves ───

    /**
     * What `input swipe` hands the app when the first frame of the gesture is long. The tool waits for the app to finish each event, and a
     * 60 Hz app finishes a MOVE at its next frame, so before the stall it has sent the touch-down and the moves of the first two frames
     * (16 and 32 ms in); the frame is long (the reveal); its clock is then past the end of the swipe, and the next thing it sends is the
     * lift, at the swipe's end point.
     */
    private fun injected(from: Offset, dyPx: Float, millis: Long): List<Sample> {
        val frame = 16L
        val early = listOf(frame, 2 * frame).filter { it < millis }
        return listOf(Sample(0, from.x, from.y)) +
            early.map { t -> Sample(t, from.x, from.y + dyPx * t / millis) } +
            Sample(millis + 7, from.x, from.y + dyPx)
    }

    private fun injectedFlick(from: Offset, dyPx: Float, millis: Long) {
        setShell()
        touch.deliverOnePerFrame(injected(from, dyPx, millis))
    }

    @Test
    fun `the reviewer's 450 px in 100 ms swipe opens it when the long frame holds the injector`() {
        injectedFlick(midScreen, -450f, 100)
        assertThat(heavyMeasures).isAtLeast(1)
        assertThat(dismissed).isEqualTo(1)
        // the line the phone will print: the lift carried most of the travel, the tracker saw no speed, the gesture as a whole did
        assertThat(lockLog.single()).contains("release open=true")
        assertThat(lockLog.single()).contains("lift past drag")
    }

    @Test
    fun `700 px in 120 ms opens it when the long frame holds the injector`() {
        injectedFlick(Offset(x, 1000f), -700f, 120)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `600 px in 250 ms opens it when the long frame holds the injector`() {
        injectedFlick(Offset(x, 1000f), -600f, 250)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a short honest flick, 90 dp in 80 ms, opens it on speed when the lift is all that comes after the stall`() {
        // 180 px: past the slop in the first frame, 72 px in two frames, and the lift 55 ms after the last move: the tracker keeps only
        // the lift (a gap over 40 ms ends its window) and the detector drops its movement; the gesture as a whole was 2,000 px/s
        injectedFlick(midScreen, -180f, 80)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a flick that starts on the way-out button opens it once and presses nothing else`() {
        setShell()
        val from = centreOfText(context.getString(R.string.lock_screen_open_app))
        touch.deliverOnePerFrame(injected(from, -700f, 150))
        assertThat(controlCalls).isEmpty()
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a flick that starts on Previous opens it and presses nothing`() {
        setShell()
        val from = centreOf(context.getString(R.string.lock_transport_previous))
        touch.deliverOnePerFrame(injected(from, -450f, 100))
        assertThat(controlCalls).isEmpty()
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a short slow drag still springs back, in the same shape`() {
        setShell()
        // 20 dp up in the first 200 ms (past the slop, so the surface is revealed), the long frame, then the lift at 50 dp after 1 s:
        // far under the 96 dp, and at 50 dp/s far under the speed
        touch.deliverOnePerFrame(listOf(Sample(0, x, 800f), Sample(200, x, 760f), Sample(1_000, x, 700f)))
        assertThat(revealLog).containsAtLeast(false, true).inOrder()
        assertThat(dismissed).isEqualTo(0)
        assertThat(revealLog.last()).isFalse()
    }

    @Test
    fun `a downward swipe never opens it, in the same shape`() {
        injectedFlick(midScreen, 450f, 100)
        assertThat(dismissed).isEqualTo(0)
        assertThat(revealLog).doesNotContain(true)
    }

    @Test
    fun `a swipe that goes up and ends back where it started does not open it, in the same shape`() {
        setShell()
        // 50 dp up in two early moves, then the lift at the starting point: the finger's own net displacement is nil
        touch.deliverOnePerFrame(
            listOf(Sample(0, x, 800f), Sample(8, x, 760f), Sample(20, x, 700f), Sample(107, x, 800f)),
        )
        assertThat(dismissed).isEqualTo(0)
    }
}
