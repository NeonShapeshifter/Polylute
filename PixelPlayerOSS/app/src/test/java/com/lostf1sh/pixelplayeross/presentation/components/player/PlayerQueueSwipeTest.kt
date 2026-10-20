package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch
import com.lostf1sh.pixelplayeross.presentation.SyntheticTouch.Delivery
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B29-L7 (N-05): the queue opens by swiping up from the bottom of the full player, and it does not fight the seek bar, the cover's
 * pager or the sheet's own collapse drag. Real touch events on the real [FullPlayerScreen] at 360 x 805 dp (xxhdpi, 3 px per dp),
 * with a recording [FullPlayerActions].
 *
 * The queue's three calls are `onQueueDragStart`, `onQueueDrag` and `onQueueRelease`. (B33-U1: the header's Queue button is gone and so is
 * `onShowQueue`: the queue is the sheet folded at the foot of the player, which draws and opens itself.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class PlayerQueueSwipeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private val touch by lazy { SyntheticTouch(compose) }
    private val px = 3f

    private val calls = mutableListOf<String>()
    private var totalDrag = 0f
    private var releaseDrag = 0f
    private var releaseVelocity = 0f

    private val recording = object : FullPlayerActions by NoActions() {
        override fun onQueueDragStart() { calls += "queueStart" }
        override fun onQueueDrag(dragAmount: Float) { calls += "queueDrag"; totalDrag += dragAmount }
        override fun onQueueRelease(totalDrag: Float, velocity: Float) { calls += "queueRelease"; releaseDrag = totalDrag; releaseVelocity = velocity }
        override fun onPlayPause() { calls += "playPause" }
        override fun onSeek(positionMs: Long) { calls += "seek" }
        override fun onCollapse() { calls += "collapse" }
    }

    private val song = Song(
        id = "1", title = "La Costa Del Silencio", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private fun env(sheet: PlayerSheetState) = PlayerSheetEnv(
        loadingTweaks = FullPlayerLoadingTweaks(delayAll = false),
        isSheetDragGestureActive = false,
        currentSheetState = sheet,
        allowRealtimeUpdates = false,
        expansionFractionProvider = { 1f },
    )

    private fun uiState(sheet: PlayerSheetState) = buildFullPlayerUiState(
        env = env(sheet), song = song, queue = persistentListOf(song), queueSourceName = "All Songs", mediaItemIndex = 0,
        carouselStyle = CarouselStyle.NO_PEEK, artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
        audioMetadata = PlaybackAudioMetadata(mediaId = song.id, mimeType = "audio/flac", sampleRate = 44100),
        showFileInfo = true, isFavorite = false, isBuffering = false, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF,
        isPlayingProvider = { true }, playWhenReadyProvider = { true },
        currentPositionProvider = { 100_000L }, totalDurationProvider = { 280_000L },
        lyrics = PlayerLyricsState(
            searchState = LyricsSearchUiState.Idle,
            stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = song, isPlaying = true, totalDuration = 280_000L, lyrics = null, lyricsResolveCompleted = true)),
            playbackPositionFlow = MutableStateFlow(9_000L),
            externalLyricsEnabled = true, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
            isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF, isFavorite = false,
            lyricsProvider = { null }, lyricsResolveCompletedProvider = { true },
        ),
        isLandscape = false,
    )

    private fun show(sheet: PlayerSheetState = PlayerSheetState.EXPANDED) = compose.setContent {
        Box(Modifier.fillMaxSize().background(Dap.background)) {
            CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) { FullPlayerScreen(uiState(sheet), recording) }
        }
    }

    private fun boundsOf(description: String) = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

    private val seekBar get() = compose.onNode(
        SemanticsMatcher("the seek bar") {
            it.config.contains(SemanticsProperties.ProgressBarRangeInfo) || it.config.contains(SemanticsActions.SetProgress)
        },
        useUnmergedTree = true,
    ).fetchSemanticsNode().boundsInRoot

    private val queueCalls get() = calls.filter { it.startsWith("queue") }

    // ── from the bottom: opens ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a swipe up that starts on the transport controls pulls the queue up and releases it with the finger's speed`() {
        show()
        val play = boundsOf(s(R.string.cd_pause)).center
        touch.swipe(play, dyPx = -450f, millis = 200, delivery = Delivery.FRAMED)
        assertThat(queueCalls.first()).isEqualTo("queueStart")
        assertThat(queueCalls.last()).isEqualTo("queueRelease")
        assertThat(releaseDrag).isLessThan(-300f)
        assertThat(releaseVelocity).isLessThan(-520f)
        // the whole of the movement reached the queue (the first move is handed over in one piece)
        assertThat(totalDrag).isWithin(30f).of(releaseDrag)
        assertThat(calls).doesNotContain("playPause")
    }

    @Test
    fun `a swipe up that starts below the controls, in the spare room at the foot of the player, opens it too`() {
        show()
        val controls = boundsOf(s(R.string.cd_pause))
        // between the transport and the strip the system keeps for its own gestures
        val foot = Offset(540f, controls.bottom + 120f)
        touch.swipe(foot, dyPx = -400f, millis = 200, delivery = Delivery.FRAMED)
        assertThat(queueCalls.first()).isEqualTo("queueStart")
        assertThat(queueCalls.last()).isEqualTo("queueRelease")
    }

    @Test
    fun `a short quick flick from the controls opens it`() {
        show()
        touch.swipe(boundsOf(s(R.string.cd_pause)).center, dyPx = -160f, millis = 60, delivery = Delivery.FRAMED)
        assertThat(queueCalls.firstOrNull()).isEqualTo("queueStart")
        assertThat(queueCalls.lastOrNull()).isEqualTo("queueRelease")
    }

    @Test
    fun `a quick flick from the controls opens it when the whole gesture arrives in one message`() {
        show()
        touch.swipe(boundsOf(s(R.string.cd_pause)).center, dyPx = -450f, millis = 100, delivery = Delivery.STALLED)
        assertThat(queueCalls.firstOrNull()).isEqualTo("queueStart")
        assertThat(queueCalls.lastOrNull()).isEqualTo("queueRelease")
    }

    // ── what it must not do ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a seek scrub that drifts upward never opens the queue and the bar keeps the gesture`() {
        show()
        val bar = seekBar
        // a scrub of 100 dp sideways with 5 dp of upward drift, the way a thumb moves
        touch.swipe(Offset(bar.left + 60f, bar.center.y), dyPx = -15f, millis = 300, delivery = Delivery.FRAMED, dxPx = 300f)
        assertThat(queueCalls).isEmpty()
        // and the bar did seek: the gesture was its own
        assertThat(calls).contains("seek")
    }

    @Test
    fun `an upward drag that starts on the seek bar is not the queue's, because the bar is above the controls`() {
        show()
        val bar = seekBar
        touch.swipe(bar.center, dyPx = -400f, millis = 250, delivery = Delivery.FRAMED)
        assertThat(queueCalls).isEmpty()
    }

    @Test
    fun `a swipe up on the cover does not open the queue, the cover has its own horizontal pager and the sheet its own drag`() {
        show()
        touch.swipe(Offset(540f, 800f), dyPx = -450f, millis = 200, delivery = Delivery.FRAMED)
        assertThat(queueCalls).isEmpty()
    }

    @Test
    fun `a sideways swipe in the lower part is somebody else's, however far up it drifts`() {
        show()
        val play = boundsOf(s(R.string.cd_pause)).center
        touch.swipe(play, dyPx = -60f, millis = 200, delivery = Delivery.FRAMED, dxPx = 400f)
        assertThat(queueCalls).isEmpty()
    }

    @Test
    fun `a diagonal swipe, more sideways than a vertical drag allows, is not the queue's`() {
        show()
        val play = boundsOf(s(R.string.cd_pause)).center
        touch.swipe(play, dyPx = -250f, millis = 200, delivery = Delivery.FRAMED, dxPx = 200f)
        assertThat(queueCalls).isEmpty()
    }

    @Test
    fun `a downward drag in the lower part opens nothing`() {
        show()
        touch.swipe(boundsOf(s(R.string.cd_pause)).center, dyPx = 300f, millis = 150, delivery = Delivery.FRAMED)
        assertThat(queueCalls).isEmpty()
    }

    @Test
    fun `a swipe that starts in the strip the system keeps for its own gestures is never ours`() {
        show()
        // 12 dp from the bottom edge: inside the exclusion
        touch.swipe(Offset(540f, 2415f - 36f), dyPx = -450f, millis = 200, delivery = Delivery.FRAMED)
        assertThat(queueCalls).isEmpty()
    }

    @Test
    fun `nothing happens unless the player is fully expanded`() {
        show(sheet = PlayerSheetState.COLLAPSED)
        touch.swipe(Offset(540f, 2000f), dyPx = -450f, millis = 200, delivery = Delivery.FRAMED)
        assertThat(queueCalls).isEmpty()
    }

    // ── what still works ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tap on a transport control is a tap, not a swipe`() {
        show()
        touch.tap(boundsOf(s(R.string.cd_pause)).center)
        assertThat(calls).containsExactly("playPause")
    }
}
