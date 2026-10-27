package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch.Delivery
import com.lostf1sh.pixelplayeross.presentation.components.scoped.MiniSeekPolicy
import com.lostf1sh.pixelplayeross.presentation.components.scoped.miniPlayerSwipeGesture
import com.lostf1sh.pixelplayeross.presentation.components.scoped.rememberMiniPlayerSwipeHandler
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B40-F6: the mini player's progress line, with real touches on the real [MiniPlayerContentInternal], wrapped as the player's sheet wraps
 * it (the song swipe, a vertical drag standing for the sheet's own, a tap that expands) at 360 x 805 dp (xhdpi, 2 px per dp). The question
 * every test asks is who owns the touch: the line's drag seeks, the rest of the mini player is what it was.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class MiniPlayerSeekTest {
    @get:Rule
    val compose = createComposeRule()

    private val counter = CompositionCounter()

    @Before
    fun install() = counter.install()

    @After
    fun uninstall() = counter.uninstall()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private val touch by lazy { SyntheticTouch(compose) }
    private val px = 2f

    /** The position is sought where the finger last moved, which the synthetic touch leaves a few pixels (1 px = 0.14 % of the song) short of the lift. */
    private val SOUGHT_TOLERANCE_MS = 4_200f

    private val calls = mutableListOf<String>()
    private val seeks = mutableListOf<Long>()
    private var positionMs by mutableLongStateOf(70_000L)
    private var offset: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>? = null
    private var shelfArgb = 0
    private var activeArgb = 0
    private var coveredByFullPlayer = false

    private val song = Song(
        id = "1", title = "Porque Ahora", artist = "Vitin Aviles", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private fun show(durationMs: Long = 280_000L, preparing: Boolean = false, withLine: Boolean = true) {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                shelfArgb = Dap.surface.toArgb()
                activeArgb = Dap.primaryText.toArgb()
                Box(Modifier.fillMaxSize().background(Dap.background), contentAlignment = Alignment.BottomCenter) {
                    val animatable = androidx.compose.runtime.remember { Animatable(0f) }
                    offset = animatable
                    val density = LocalDensity.current
                    val handler = rememberMiniPlayerSwipeHandler(
                        scope = rememberCoroutineScope(), density = density, hapticFeedback = LocalHapticFeedback.current,
                        offsetAnimatable = animatable, onNext = { calls += "next" }, onPrevious = { calls += "previous" },
                    )
                    val lineThere = withLine && MiniSeekPolicy.available(durationMs, preparing)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(MiniPlayerHeight)
                            .miniPlayerSwipeGesture(
                                enabled = !preparing,
                                handler = handler,
                                seekZoneHeightPx = if (lineThere) with(density) { MiniSeekPolicy.ZONE_DP.dp.toPx() } else 0f,
                            )
                            // stands for the sheet's own vertical drag, which expands the player
                            .pointerInput(Unit) { detectVerticalDragGestures(onDragStart = { calls += "verticalDrag" }) { _, _ -> } }
                            .clickable { calls += "expand" }
                    ) {
                        MiniPlayerContentInternal(
                            song = song, isPlaying = true, isPreparingPlayback = preparing,
                            onPlayPause = { calls += "playPause" }, onPrevious = { calls += "buttonPrevious" }, onNext = { calls += "buttonNext" },
                            modifier = Modifier.fillMaxSize().graphicsLayer { translationX = animatable.value },
                            seekPositionProvider = if (withLine) ({ positionMs }) else null,
                            seekDurationMs = durationMs,
                            onSeek = if (withLine) ({ seeks += it }) else null,
                            seekActiveProvider = { !coveredByFullPlayer },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private val title get() = compose.onNodeWithText("Porque Ahora").fetchSemanticsNode().boundsInRoot

    /** The top edge of the mini player, in px: it sits at the bottom of the screen, [MiniPlayerHeight] (65 dp) tall. */
    private val top get() = (805f - 65f) * px

    private val line get() = compose.onNodeWithContentDescription(s(R.string.cd_playback_position))

    private fun pixel(x: Float, y: Float) = compose.onRoot().captureToImage().asAndroidBitmap().getPixel(x.toInt(), y.toInt())

    // ── who owns the touch ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a sideways drag along the line seeks to where the finger lifts, and nothing else happens`() {
        show()
        touch.swipe(Offset(72f, top + 10f), dyPx = 0f, millis = 400, delivery = Delivery.FRAMED, dxPx = 504f)
        // 72 px + 504 px = 576 px of 720: four fifths of 280 s, to the last movement the finger made before it lifted (a few pixels)
        assertThat(seeks).hasSize(1)
        assertThat(seeks.single().toFloat()).isWithin(SOUGHT_TOLERANCE_MS).of(224_000f)
        assertThat(calls).isEmpty()
        assertThat(offset!!.value).isEqualTo(0f)
    }

    @Test
    fun `a drag to the left seeks backwards, to the edge it is clamped`() {
        show()
        touch.swipe(Offset(360f, top + 10f), dyPx = 0f, millis = 400, delivery = Delivery.FRAMED, dxPx = -500f)
        assertThat(seeks).containsExactly(0L)
        assertThat(calls).isEmpty()
        seeks.clear()
        touch.swipe(Offset(360f, top + 10f), dyPx = 0f, millis = 400, delivery = Delivery.FRAMED, dxPx = 500f)
        assertThat(seeks).containsExactly(280_000L)
    }

    @Test
    fun `the same drag a little lower, below the line, is still the song swipe`() {
        show()
        touch.swipe(Offset(title.left + 20f, title.center.y), dyPx = 0f, millis = 300, delivery = Delivery.FRAMED, dxPx = -300f)
        assertThat(calls).containsExactly("next")
        assertThat(seeks).isEmpty()
        calls.clear()
        // just under the zone's edge (16 dp = 32 px): the song swipe's
        touch.swipe(Offset(500f, top + 34f), dyPx = 0f, millis = 300, delivery = Delivery.FRAMED, dxPx = 300f)
        assertThat(calls).containsExactly("previous")
        assertThat(seeks).isEmpty()
    }

    @Test
    fun `the last pixel of the zone is the line's and the first one under it is the swipe's`() {
        show()
        touch.swipe(Offset(500f, top + 30f), dyPx = 0f, millis = 300, delivery = Delivery.FRAMED, dxPx = -300f)
        assertThat(seeks).hasSize(1)
        assertThat(calls).isEmpty()
        seeks.clear()
        touch.swipe(Offset(500f, top + 34f), dyPx = 0f, millis = 300, delivery = Delivery.FRAMED, dxPx = -300f)
        assertThat(seeks).isEmpty()
        assertThat(calls).containsExactly("next")
    }

    @Test
    fun `a vertical drag that begins on the line is the sheet's, it opens the player and seeks nothing`() {
        show()
        touch.swipe(Offset(360f, top + 10f), dyPx = -300f, millis = 250, delivery = Delivery.FRAMED)
        assertThat(calls).contains("verticalDrag")
        assertThat(calls).doesNotContain("next")
        assertThat(seeks).isEmpty()
    }

    @Test
    fun `a mostly vertical diagonal drag on the line is the sheet's too`() {
        show()
        touch.swipe(Offset(360f, top + 10f), dyPx = -300f, millis = 300, delivery = Delivery.FRAMED, dxPx = -120f)
        assertThat(calls).doesNotContain("next")
        assertThat(seeks).isEmpty()
    }

    @Test
    fun `a tap on the line opens the player like a tap anywhere on the mini player, it does not seek`() {
        show()
        touch.tap(Offset(540f, top + 6f))
        assertThat(calls).containsExactly("expand")
        assertThat(seeks).isEmpty()
    }

    @Test
    fun `the buttons are the buttons' and a tap on the title still expands`() {
        show()
        touch.tap(title.center)
        assertThat(calls).containsExactly("expand")
        calls.clear()
        touch.tap(compose.onNodeWithContentDescription(s(R.string.next_track)).fetchSemanticsNode().boundsInRoot.center)
        assertThat(calls).containsExactly("buttonNext")
        assertThat(seeks).isEmpty()
    }

    // ── what it draws ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the line shows how far the song is, a quarter of it here`() {
        show()
        // 70 s of 280 s: the first 180 px of 720 are the song so far
        assertThat(pixel(100f, top + 1f)).isEqualTo(activeArgb)
        assertThat(pixel(400f, top + 1f)).isNotEqualTo(activeArgb)
        // two dp tall: the row under it is the shelf's own
        assertThat(pixel(100f, top + 8f)).isEqualTo(shelfArgb)
    }

    @Test
    fun `the line follows the song as the position moves`() {
        show()
        positionMs = 210_000L
        compose.waitForIdle()
        assertThat(pixel(500f, top + 1f)).isEqualTo(activeArgb)
        assertThat(pixel(600f, top + 1f)).isNotEqualTo(activeArgb)
    }

    @Test
    fun `while the finger is down the line is where the finger is, thicker, and nothing is sought until it lifts`() {
        show()
        var during = 0
        var thickRow = 0
        var seekedWhileDown = true
        touch.swipeAndHold(Offset(72f, top + 10f), dyPx = 0f, millis = 300, dxPx = 288f, settleMs = 0) {
            // the finger is at 360 px: halfway
            during = pixel(300f, top + 1f)
            thickRow = pixel(300f, top + 6f)
            seekedWhileDown = seeks.isNotEmpty()
        }
        assertThat(during).isEqualTo(activeArgb)
        assertThat(seekedWhileDown).isFalse()
        assertThat(seeks).hasSize(1)
        assertThat(seeks.single().toFloat()).isWithin(SOUGHT_TOLERANCE_MS).of(140_000f)
        assertThat(thickRow).isNotEqualTo(shelfArgb)
    }

    @Test
    fun `it draws nothing and reads nothing while the full player covers the mini one`() {
        coveredByFullPlayer = true
        show()
        assertThat(pixel(100f, top + 1f)).isEqualTo(shelfArgb)
    }

    // ── when there is no line ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a song with no known length has no line, and the top of the mini player is the swipe's`() {
        show(durationMs = 0L)
        line.assertDoesNotExist()
        touch.swipe(Offset(500f, top + 10f), dyPx = 0f, millis = 300, delivery = Delivery.FRAMED, dxPx = -300f)
        assertThat(calls).containsExactly("next")
        assertThat(seeks).isEmpty()
    }

    @Test
    fun `a song still being prepared has no line`() {
        show(preparing = true)
        line.assertDoesNotExist()
    }

    @Test
    fun `a mini player given no way to seek is the hairline it was`() {
        show(withLine = false)
        line.assertDoesNotExist()
        touch.swipe(Offset(500f, top + 10f), dyPx = 0f, millis = 300, delivery = Delivery.FRAMED, dxPx = -300f)
        assertThat(calls).containsExactly("next")
    }

    // ── accessibility ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the line tells accessibility where the song is and can be set by it`() {
        show()
        val info = line.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertThat(info.current).isWithin(0.001f).of(0.25f)
        line.performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertThat(seeks).containsExactly(140_000L)
    }

    // ── cheap ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tick of the position recomposes nothing, it only redraws the line`() {
        show()
        compose.waitForIdle()
        val before = counter.counts()
        positionMs = 71_000L
        compose.waitForIdle()
        positionMs = 72_000L
        compose.waitForIdle()
        assertThat(counter.since(before)).isEmpty()
    }

    // ── the rule, pure ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a touch belongs to the line only if it goes down inside the zone, and there is a zone`() {
        assertThat(MiniSeekPolicy.ownerOf(downY = 0f, zonePx = 32f)).isEqualTo(MiniSeekPolicy.Owner.SEEK)
        assertThat(MiniSeekPolicy.ownerOf(downY = 31.9f, zonePx = 32f)).isEqualTo(MiniSeekPolicy.Owner.SEEK)
        assertThat(MiniSeekPolicy.ownerOf(downY = 32f, zonePx = 32f)).isEqualTo(MiniSeekPolicy.Owner.SWIPE)
        assertThat(MiniSeekPolicy.ownerOf(downY = 80f, zonePx = 32f)).isEqualTo(MiniSeekPolicy.Owner.SWIPE)
        assertThat(MiniSeekPolicy.ownerOf(downY = 0f, zonePx = 0f)).isEqualTo(MiniSeekPolicy.Owner.SWIPE)
    }

    @Test
    fun `the finger's place is the position, to the edges`() {
        assertThat(MiniSeekPolicy.fractionAt(0f, 720f)).isEqualTo(0f)
        assertThat(MiniSeekPolicy.fractionAt(360f, 720f)).isEqualTo(0.5f)
        assertThat(MiniSeekPolicy.fractionAt(-40f, 720f)).isEqualTo(0f)
        assertThat(MiniSeekPolicy.fractionAt(900f, 720f)).isEqualTo(1f)
        assertThat(MiniSeekPolicy.fractionAt(10f, 0f)).isEqualTo(0f)
        assertThat(MiniSeekPolicy.positionMs(0.5f, 280_000L)).isEqualTo(140_000L)
        assertThat(MiniSeekPolicy.positionMs(1.5f, 280_000L)).isEqualTo(280_000L)
        assertThat(MiniSeekPolicy.positionMs(-1f, 280_000L)).isEqualTo(0L)
        assertThat(MiniSeekPolicy.fractionOf(70_000L, 280_000L)).isEqualTo(0.25f)
        assertThat(MiniSeekPolicy.fractionOf(5L, 0L)).isEqualTo(0f)
    }

    @Test
    fun `it clicks every twentieth of the song, as the full player's bar does`() {
        assertThat(MiniSeekPolicy.hapticStep(0f)).isEqualTo(0)
        assertThat(MiniSeekPolicy.hapticStep(0.049f)).isEqualTo(0)
        assertThat(MiniSeekPolicy.hapticStep(0.05f)).isEqualTo(1)
        assertThat(MiniSeekPolicy.hapticStep(1f)).isEqualTo(20)
    }

    @Test
    fun `the length is the player's once it has one, else the library's, and the line needs one`() {
        assertThat(MiniSeekPolicy.durationMs(playerDurationMs = 281_000L, songDurationMs = 280_000L)).isEqualTo(281_000L)
        assertThat(MiniSeekPolicy.durationMs(playerDurationMs = 0L, songDurationMs = 280_000L)).isEqualTo(280_000L)
        assertThat(MiniSeekPolicy.durationMs(playerDurationMs = -1L, songDurationMs = -5L)).isEqualTo(0L)
        assertThat(MiniSeekPolicy.available(durationMs = 0L, preparing = false)).isFalse()
        assertThat(MiniSeekPolicy.available(durationMs = 280_000L, preparing = true)).isFalse()
        assertThat(MiniSeekPolicy.available(durationMs = 280_000L, preparing = false)).isTrue()
    }
}
