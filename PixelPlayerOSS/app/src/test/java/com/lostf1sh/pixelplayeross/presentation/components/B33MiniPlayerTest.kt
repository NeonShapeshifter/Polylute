package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch.Delivery
import com.lostf1sh.pixelplayeross.presentation.components.scoped.MiniSwipe
import com.lostf1sh.pixelplayeross.presentation.components.scoped.MiniSwipePolicy
import com.lostf1sh.pixelplayeross.presentation.components.scoped.miniPlayerSwipeGesture
import com.lostf1sh.pixelplayeross.presentation.components.scoped.rememberMiniPlayerSwipeHandler
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PreviousTrackPolicy
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B33-U2: the mini player. A plain play/pause glyph (no filled circle), and a sideways swipe that changes the song: to the left goes to the
 * next, to the right to the previous, with the actions the buttons send. It no longer discards the queue. Real touches on the real
 * [MiniPlayerContentInternal], wrapped as the player's sheet wraps it (the swipe, a vertical drag standing for the sheet's own, a tap that
 * expands), at 360 x 805 dp (xhdpi, 2 px per dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B33MiniPlayerTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private val touch by lazy { SyntheticTouch(compose) }
    private val px = 2f

    private val calls = mutableListOf<String>()
    private var offset: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>? = null
    /** The shelf's colour, read inside the theme (it is the palette's, not a constant). */
    private var shelfArgb = 0

    private val song = Song(
        id = "1", title = "Porque Ahora", artist = "Vitin Aviles", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private fun show(playing: Boolean = true, swipeEnabled: Boolean = true) {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                shelfArgb = Dap.surface.toArgb()
                Box(Modifier.fillMaxSize().background(Dap.background), contentAlignment = Alignment.BottomCenter) {
                    val animatable = androidx.compose.runtime.remember { Animatable(0f) }
                    offset = animatable
                    val handler = rememberMiniPlayerSwipeHandler(
                        scope = rememberCoroutineScope(), density = LocalDensity.current, hapticFeedback = LocalHapticFeedback.current,
                        offsetAnimatable = animatable, onNext = { calls += "next" }, onPrevious = { calls += "previous" },
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(MiniPlayerHeight)
                            .miniPlayerSwipeGesture(enabled = swipeEnabled, handler = handler)
                            // stands for the sheet's own vertical drag, which expands the player
                            .pointerInput(Unit) { detectVerticalDragGestures(onDragStart = { calls += "verticalDrag" }) { _, _ -> } }
                            .clickable { calls += "expand" }
                    ) {
                        MiniPlayerContentInternal(
                            song = song, isPlaying = playing, isPreparingPlayback = false,
                            onPlayPause = { calls += "playPause" }, onPrevious = { calls += "buttonPrevious" }, onNext = { calls += "buttonNext" },
                            // the sheet carries the mini's content, not the box the gesture is on
                            modifier = Modifier.fillMaxSize().graphicsLayer { translationX = animatable.value },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private val title get() = compose.onNodeWithText("Porque Ahora").fetchSemanticsNode().boundsInRoot

    // ── the glyph ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `play and pause are a plain glyph with a 48 dp target and their description, no filled circle`() {
        show(playing = false)
        val play = compose.onNodeWithContentDescription(s(R.string.cd_play)).fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithContentDescription(s(R.string.next_track)).fetchSemanticsNode().boundsInRoot
        val previous = compose.onNodeWithContentDescription(s(R.string.previous_track)).fetchSemanticsNode().boundsInRoot
        listOf(play, next, previous).forEach {
            assertThat(it.width / px).isWithin(0.5f).of(48f)
            assertThat(it.height / px).isWithin(0.5f).of(48f)
        }
        // inside the target but outside the glyph, the pixel is the shelf's own: there is no circle behind the glyph (the old one was white)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val shelf = bitmap.getPixel(2, play.center.y.toInt())
        assertThat(shelf).isEqualTo(shelfArgb)
        assertThat(bitmap.getPixel((play.left + 4f).toInt(), play.center.y.toInt())).isEqualTo(shelf)
        assertThat(bitmap.getPixel((play.left + 4f).toInt(), (play.top + 6f).toInt())).isEqualTo(shelf)
    }

    @Test
    fun `playing, the glyph is the pause one, with its own description`() {
        show(playing = true)
        compose.onNodeWithContentDescription(s(R.string.cd_pause)).assertExists()
        touch.tap(compose.onNodeWithContentDescription(s(R.string.cd_pause)).fetchSemanticsNode().boundsInRoot.center)
        assertThat(calls).containsExactly("playPause")
    }

    // ── the swipe ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a swipe to the left goes to the next song, with the action the button sends`() {
        show()
        touch.swipe(Offset(title.left + 20f, title.center.y), dyPx = 0f, millis = 300, delivery = Delivery.FRAMED, dxPx = -300f)
        assertThat(calls).containsExactly("next")
        // the content went with the finger and sprang back
        assertThat(offset!!.value).isWithin(0.5f).of(0f)
    }

    @Test
    fun `a swipe to the right goes to the previous song, with the action the button sends`() {
        show()
        touch.swipe(Offset(title.left + 20f, title.center.y), dyPx = 0f, millis = 300, delivery = Delivery.FRAMED, dxPx = 300f)
        assertThat(calls).containsExactly("previous")
        assertThat(offset!!.value).isWithin(0.5f).of(0f)
    }

    @Test
    fun `previous is the button's previous, it restarts the song at the first item or past three seconds, else goes back`() {
        // the swipe sends `previous` and the policy behind it is the button's: nothing before the song, or more than 3 s in, is a restart
        assertThat(PreviousTrackPolicy.restartsCurrent(positionMs = 1_000L, hasPrevious = false)).isTrue()
        assertThat(PreviousTrackPolicy.restartsCurrent(positionMs = 10_000L, hasPrevious = true)).isTrue()
        assertThat(PreviousTrackPolicy.restartsCurrent(positionMs = 1_000L, hasPrevious = true)).isFalse()
    }

    @Test
    fun `a short drag, in either direction, changes nothing and the content springs back`() {
        show()
        touch.swipe(Offset(title.left + 20f, title.center.y), dyPx = 0f, millis = 600, delivery = Delivery.FRAMED, dxPx = -60f)
        touch.swipe(Offset(title.left + 20f, title.center.y), dyPx = 0f, millis = 600, delivery = Delivery.FRAMED, dxPx = 60f)
        assertThat(calls).isEmpty()
        assertThat(offset!!.value).isWithin(0.5f).of(0f)
    }

    @Test
    fun `a quick flick of 50 dp goes through, a slow one of the same length does not`() {
        show()
        touch.swipe(Offset(title.left + 20f, title.center.y), dyPx = 0f, millis = 60, delivery = Delivery.FRAMED, dxPx = -100f)
        assertThat(calls).containsExactly("next")
        calls.clear()
        touch.swipe(Offset(title.left + 20f, title.center.y), dyPx = 0f, millis = 900, delivery = Delivery.FRAMED, dxPx = -100f)
        assertThat(calls).isEmpty()
    }

    @Test
    fun `the content follows the finger while it is down, and goes back when it lifts`() {
        show()
        var held = 0f
        touch.swipeAndHold(Offset(title.left + 20f, title.center.y), dyPx = 0f, millis = 300, dxPx = -120f, settleMs = 0) { held = offset!!.value }
        // 120 px = 60 dp, less the touch slop: with the finger, a little behind it
        assertThat(held).isLessThan(-60f)
        assertThat(held).isGreaterThan(-130f)
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertThat(offset!!.value).isWithin(0.5f).of(0f)
        // 60 dp is under the 72 dp that commits: nothing changed song
        assertThat(calls).isEmpty()
    }

    @Test
    fun `a vertical drag is the sheet's, it expands the player and is not a swipe`() {
        show()
        touch.swipe(Offset(title.left + 20f, title.center.y), dyPx = -300f, millis = 250, delivery = Delivery.FRAMED)
        assertThat(calls).contains("verticalDrag")
        assertThat(calls).doesNotContain("next")
        assertThat(calls).doesNotContain("previous")
        assertThat(offset!!.value).isEqualTo(0f)
    }

    @Test
    fun `a diagonal drag that is mostly vertical is the sheet's too`() {
        show()
        touch.swipe(Offset(title.left + 20f, title.center.y), dyPx = -300f, millis = 300, delivery = Delivery.FRAMED, dxPx = -120f)
        assertThat(calls).doesNotContain("next")
        assertThat(calls).doesNotContain("previous")
    }

    @Test
    fun `a tap on the title still expands the player, and a tap on a button is the button's`() {
        show()
        touch.tap(title.center)
        assertThat(calls).containsExactly("expand")
        calls.clear()
        touch.tap(compose.onNodeWithContentDescription(s(R.string.next_track)).fetchSemanticsNode().boundsInRoot.center)
        touch.tap(compose.onNodeWithContentDescription(s(R.string.previous_track)).fetchSemanticsNode().boundsInRoot.center)
        assertThat(calls).containsExactly("buttonNext", "buttonPrevious").inOrder()
    }

    @Test
    fun `a swipe that starts on a button is still a swipe`() {
        show()
        val next = compose.onNodeWithContentDescription(s(R.string.next_track)).fetchSemanticsNode().boundsInRoot
        touch.swipe(next.center, dyPx = 0f, millis = 300, delivery = Delivery.FRAMED, dxPx = -300f)
        assertThat(calls).containsExactly("next")
    }

    @Test
    fun `with the swipe off, a drag does nothing`() {
        show(swipeEnabled = false)
        touch.swipe(Offset(title.left + 20f, title.center.y), dyPx = 0f, millis = 300, delivery = Delivery.FRAMED, dxPx = -300f)
        assertThat(calls).isEmpty()
        assertThat(offset!!.value).isEqualTo(0f)
    }

    // ── the policy, and where the queue is closed instead ────────────────────────────────────────────

    @Test
    fun `the swipe decides once, past the touch slop, and only a clearly horizontal movement is its own`() {
        val slop = 16f
        assertThat(MiniSwipePolicy.intentAfterSlop(5f, 5f, slop)).isNull()
        assertThat(MiniSwipePolicy.intentAfterSlop(-40f, 10f, slop)).isEqualTo(MiniSwipePolicy.Intent.HORIZONTAL)
        assertThat(MiniSwipePolicy.intentAfterSlop(40f, -20f, slop)).isEqualTo(MiniSwipePolicy.Intent.HORIZONTAL)
        assertThat(MiniSwipePolicy.intentAfterSlop(30f, -20f, slop)).isEqualTo(MiniSwipePolicy.Intent.OTHER)
        assertThat(MiniSwipePolicy.intentAfterSlop(0f, -40f, slop)).isEqualTo(MiniSwipePolicy.Intent.OTHER)
        assertThat(MiniSwipePolicy.intentAfterSlop(-5f, 40f, slop)).isEqualTo(MiniSwipePolicy.Intent.OTHER)
    }

    @Test
    fun `a release goes through from 72 dp, or from 32 dp at 700 dp per second, and left is next, right is previous`() {
        val d = 2f
        assertThat(MiniSwipePolicy.outcome(-72f * d, 0f, d)).isEqualTo(MiniSwipe.NEXT)
        assertThat(MiniSwipePolicy.outcome(72f * d, 0f, d)).isEqualTo(MiniSwipe.PREVIOUS)
        assertThat(MiniSwipePolicy.outcome(-71f * d, 0f, d)).isNull()
        assertThat(MiniSwipePolicy.outcome(-33f * d, -800f * d, d)).isEqualTo(MiniSwipe.NEXT)
        assertThat(MiniSwipePolicy.outcome(33f * d, 800f * d, d)).isEqualTo(MiniSwipe.PREVIOUS)
        assertThat(MiniSwipePolicy.outcome(-31f * d, -2000f * d, d)).isNull()
        assertThat(MiniSwipePolicy.outcome(-33f * d, -600f * d, d)).isNull()
    }

    @Test
    fun `the content follows the finger one for one, then at a resistance, and never past its cap`() {
        val d = 2f
        assertThat(MiniSwipePolicy.followOffset(-50f * d, d)).isWithin(0.01f).of(-50f * d)
        assertThat(MiniSwipePolicy.followOffset(96f * d, d)).isWithin(0.01f).of(96f * d)
        assertThat(MiniSwipePolicy.followOffset(-146f * d, d)).isWithin(0.01f).of(-(96f + 50f * 0.35f) * d)
        assertThat(MiniSwipePolicy.followOffset(-1000f * d, d)).isWithin(0.01f).of(-160f * d)
        assertThat(MiniSwipePolicy.followOffset(0f, d)).isEqualTo(0f)
    }

    @Test
    fun `no code path from the mini player clears the queue or dismisses the player`() {
        val sources = File("src/main/java/com/lostf1sh/pixelplayeross")
        assertThat(File(sources, "presentation/components/scoped/MiniPlayerDismissGestureHandler.kt").exists()).isFalse()
        val swipe = File(sources, "presentation/components/scoped/MiniPlayerSwipe.kt").readText()
        assertThat(swipe).doesNotContain("clearQueue")
        assertThat(swipe).doesNotContain("dismiss")
        val sheet = File(sources, "presentation/components/UnifiedPlayerSheetV2.kt").readText()
        // the swipe is handed the two actions the buttons send, and nothing else
        assertThat(sheet).contains("onNext = { playerViewModel.nextSong() }")
        assertThat(sheet).contains("onPrevious = { playerViewModel.previousSong() }")
        assertThat(sheet).doesNotContain("dismissPlaylist")
        assertThat(sheet).doesNotContain("setMiniPlayerDismissing")
        val viewModel = File(sources, "presentation/viewmodel/PlayerViewModel.kt").readText()
        assertThat(viewModel).doesNotContain("fun dismissPlaylistAndShowUndo")
        assertThat(viewModel).doesNotContain("isMiniPlayerDismissing")
        // closing the player and clearing the queue are elsewhere: the notification's Close, and the queue sheet's menu
        assertThat(File(sources, "data/service/MusicNotificationProvider.kt").readText()).contains("CUSTOM_COMMAND_CLOSE_PLAYER")
        assertThat(File(sources, "presentation/components/QueueBottomSheet.kt").readText()).contains("onClearQueue()")
    }
}
