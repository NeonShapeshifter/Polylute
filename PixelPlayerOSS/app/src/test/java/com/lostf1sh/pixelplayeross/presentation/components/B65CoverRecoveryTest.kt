package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.scoped.ParkedWhileCollapsed
import com.lostf1sh.pixelplayeross.presentation.components.scoped.rememberHeldWhile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class,
    qualifiers = "w360dp-h805dp-xxhdpi",
    // Use a separate instrumented sandbox for the Coil painter and Compose v2's queued clock.
    // The rest of the Robolectric suite uses the immediate clock and its own paging dispatchers.
    instrumentedPackages = ["coil.compose"],
)
class B65CoverRecoveryTest {
    @get:Rule val compose = createComposeRule()

    private var queue: ImmutableList<Song> by mutableStateOf(songs(0))
    private var index by mutableIntStateOf(3)
    private var timelineOverride by mutableStateOf<Int?>(null)
    private var visible by mutableStateOf(true)
    private var parked by mutableStateOf(false)
    private var answerSwipe = false
    private val selected = mutableListOf<Int>()
    private val lines = mutableListOf<String>()
    private lateinit var scope: CoroutineScope
    private lateinit var host: View
    private lateinit var pagerSemantics: Any
    private var downTime = 0L
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val loader by lazy {
        val delegate = ImageLoader.Builder(context).build()
        object : ImageLoader by delegate {
            override suspend fun execute(request: ImageRequest): ImageResult {
                return SuccessResult(ColorDrawable(0xff003366.toInt()), request, DataSource.MEMORY)
            }
        }
    }
    private val logger = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (tag == "CoverBind") lines += message
        }
    }

    @Before fun record() = Timber.plant(logger)
    @After fun stopRecording() {
        compose.mainClock.autoAdvance = true
        Timber.uproot(logger)
        loader.shutdown()
    }

    @Test
    fun `an overflowed drag Stop cannot block three successive skips`() {
        mount()
        val pager = pager()
        val source = pager.interactionSource as MutableInteractionSource
        val start = DragInteraction.Start()
        // The Compose test dispatcher queues delivery while this producer writes its burst.
        compose.runOnUiThread { source.tryEmit(start) }
        frame()
        compose.runOnUiThread {
            source.tryEmit(DragInteraction.Stop(start))
            overflow(source)
        }
        frame()
        assertNextThree(pager)
        assertThat(selected).isEmpty()
    }

    @Test
    fun `parking a real drag and releasing it outside recovers on show and follows Next`() {
        mount()
        val pager = pager()
        val recording = loseDragEnd(pager)
        startDrag()
        assertThat(recording.any { it is DragInteraction.Start }).isTrue()
        parked = true
        visible = false
        frame(96)
        touch(MotionEvent.ACTION_UP, 1400f)
        frame(96)
        index = 6
        frame(96)
        assertThat(pager.currentPage).isEqualTo(3)
        parked = false
        visible = true
        frame(1400)
        println("Parked drag interactions: ${recording.map { it.javaClass.simpleName }}")
        assertCover(pager, 6)
        assertNextThree(pager)
        assertThat(selected).isEmpty()
    }

    @Test
    fun `a replaced pager starts at the new song even with an older drag still down`() {
        mount()
        val oldPager = pager()
        val recording = loseDragEnd(oldPager)
        startDrag()
        assertThat(recording.any { it is DragInteraction.Start }).isTrue()
        queue = songs(100)
        index = 7
        frame(32)
        val newPager = pager()
        val installed = observe(newPager, queue[7])
        touch(MotionEvent.ACTION_UP, 1400f)
        frame(1400)
        assertNextThree(newPager)
        assertThat(installed).isEqualTo(expectedCover(7))
        assertThat(newPager).isNotSameInstanceAs(oldPager)
        assertThat(selected).isEmpty()
    }

    @Test fun `replacement on screen starts on the playing page`() = replaceQueue(Host.VISIBLE, false)
    @Test fun `shuffled replacement on screen resolves the playing occurrence`() = replaceQueue(Host.VISIBLE, true)
    @Test fun `replacement off screen starts on the playing page`() = replaceQueue(Host.HIDDEN, false)
    @Test fun `shuffled replacement off screen resolves the playing occurrence`() = replaceQueue(Host.HIDDEN, true)
    @Test fun `replacement while parked starts on the playing page at show`() = replaceQueue(Host.PARKED, false)
    @Test fun `shuffled replacement while parked resolves the playing occurrence at show`() = replaceQueue(Host.PARKED, true)

    @Test
    fun `a real swipe back is acknowledged and the next skip moves forward`() {
        mount()
        val pager = pager()
        answerSwipe = true
        touch(MotionEvent.ACTION_DOWN, 100f)
        for (x in 200..1000 step 100) touch(MotionEvent.ACTION_MOVE, x.toFloat())
        touch(MotionEvent.ACTION_UP, 1000f)
        frame(1400)
        assertThat(selected).containsExactly(2)
        assertCover(pager, 2)
        index = 3
        frame(1400)
        assertCover(pager, 3)
        assertThat(selected).containsExactly(2)
    }

    @Test
    fun `after six interrupted animations the cover still binds to the playing song`() {
        mount()
        val pager = pager()
        index = 7
        frame(32)
        repeat(6) { interruption ->
            println("interruption ${interruption + 1} at page ${pager.currentPage}, scrolling=${pager.isScrollInProgress}")
            assertThat(pager.isScrollInProgress).isTrue()
            compose.runOnUiThread {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    pager.scroll(MutatePriority.PreventUserInput) {}
                }
            }
            frame(16)
        }
        frame(1400)
        println(lines.joinToString("\n"))
        assertCover(pager, 7)
        assertThat(lines.any { it.startsWith("COVER_BIND_GAVE_UP song=b65-7 ") && it.endsWith("attempts=6") }).isTrue()
        assertThat(selected).isEmpty()
    }

    @Test
    fun `a bounded wait keeps a real finger in control until it releases`() {
        mount()
        val pager = pager()
        startDrag()
        index = 6
        frame(1400)
        assertThat(pager.currentPage).isEqualTo(3)
        val timedOut = lines.contains("COVER_BIND_RESET song=b65-6 target=6 reason=wait-timeout")
        touch(MotionEvent.ACTION_UP, 470f)
        frame(1400)
        println(lines.joinToString("\n"))
        assertCover(pager, 6)
        assertThat(timedOut).isTrue()
        assertThat(selected).isEmpty()
    }

    private enum class Host { VISIBLE, HIDDEN, PARKED }

    private fun replaceQueue(mode: Host, shuffle: Boolean) {
        mount()
        val oldPager = pager()
        if (mode != Host.VISIBLE) visible = false
        if (mode == Host.PARKED) parked = true
        frame(96)

        queue = if (shuffle) songs(100).reversed().toImmutableList() else songs(100)
        index = 7
        if (shuffle) timelineOverride = 1
        if (mode == Host.PARKED) frame(96)
        if (mode == Host.PARKED) {
            assertThat(pager().currentPage).isEqualTo(3)
            parked = false
            visible = true
        }
        frame(32)
        val newPager = pager()
        val installed = observe(newPager, queue[7])
        assertNextThree(newPager)
        assertThat(installed).isEqualTo(expectedCover(7))
        assertThat(newPager).isNotSameInstanceAs(oldPager)
        assertThat(selected).isEmpty()
    }

    @Suppress("DEPRECATION") // Isolates synthetic artwork from Coil's application-wide loader.
    private fun mount() {
        compose.setContent {
            scope = rememberCoroutineScope()
            host = LocalView.current
            val heldQueue = rememberHeldWhile(parked, queue)
            val song = rememberHeldWhile(parked, queue[index])
            val row = rememberHeldWhile(parked, timelineOverride ?: index)
            CompositionLocalProvider(LocalImageLoader provides loader) {
                ParkedWhileCollapsed(parked = parked, modifier = Modifier.size(360.dp)) {
                    AlbumCarouselSection(
                        currentSong = song,
                        queue = heldQueue,
                        expansionFraction = 0f,
                        currentMediaItemIndex = row,
                        onSongSelected = { _, page ->
                            selected += page
                            if (answerSwipe) index = page
                        },
                        modifier = Modifier.size(360.dp),
                        isOnScreen = { visible },
                    )
                }
            }
        }
        compose.waitForIdle()
        val node = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode()
        val getter = node.config[SemanticsProperties.HorizontalScrollAxisRange].value
        val nodeField = getter.javaClass.declaredFields.single { it.type.simpleName == "LazyLayoutSemanticsModifierNode" }
        nodeField.isAccessible = true
        pagerSemantics = checkNotNull(nodeField.get(getter))
        compose.mainClock.autoAdvance = false
    }

    private fun assertNextThree(pager: PagerState) {
        val next = (index + 1..index + 3).toList()
        val observed = next.map { page ->
            index = page
            frame(1400)
            val reports = lines.filter { it.startsWith("COVER_BIND song=${queue[page].id} ") }
            println("Next to ${queue[page].id}, terminal CoverBind=$reports")
            observe(pager, queue[page])
        }
        assertThat(observed).containsExactlyElementsIn(next.map(::expectedCover)).inOrder()
    }

    private data class Cover(val page: Int, val centered: Boolean, val model: String?)

    private fun expectedCover(page: Int) = Cover(page, true, queue[page].albumArtUriString)

    private fun observe(pager: PagerState, expectedSong: Song): Cover {
        val current = pager.currentPage
        val centered = kotlin.math.abs(pager.currentPageOffsetFraction) <= 0.001f
        val model = compose.runOnUiThread {
            val root = (host as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
            val center = Offset(host.width / 2f, host.height / 2f)
            visibleImageModel(root, center).orEmpty()
        }.ifEmpty { null }
        return Cover(current, centered, model).also { println("playing ${expectedSong.id}, observed $it") }
    }

    // Read the request off the visible page's actual Coil painter without asking Espresso to idle
    // a pager that is deliberately interrupted or running on a paused Compose clock.
    private fun visibleImageModel(node: SemanticsNode, center: Offset): String? {
        if (center !in node.boundsInRoot) return null
        val image = node.layoutInfo.getModifierInfo().map { it.modifier }
            .firstOrNull { it.javaClass.simpleName == "ContentPainterElement" }
        if (image != null) {
            val painter = image.javaClass.getDeclaredField("painter").apply { isAccessible = true }.get(image)
            if (painter is AsyncImagePainter) return painter.request.data.toString()
        }
        return node.children.firstNotNullOfOrNull { visibleImageModel(it, center) }
    }

    private fun assertCover(pager: PagerState, page: Int) {
        assertThat(observe(pager, queue[page])).isEqualTo(expectedCover(page))
    }

    private fun frame(ms: Long = 32) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
    }

    private fun startDrag() {
        touch(MotionEvent.ACTION_DOWN, 540f)
        touch(MotionEvent.ACTION_MOVE, 470f)
        frame()
    }

    private fun touch(action: Int, x: Float) {
        compose.runOnUiThread {
            val now = compose.mainClock.currentTime
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, x, 540f, 0)
            host.dispatchTouchEvent(event)
            event.recycle()
        }
        frame(16)
    }

    // Keep the pager's real Start, then force its real Stop or Cancel to fall out of the shared buffer.
    private fun loseDragEnd(pager: PagerState): List<Interaction> {
        val source = pager.interactionSource as MutableInteractionSource
        val interactions = mutableListOf<Interaction>()
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            source.interactions.collect {
                if (it is DragInteraction.Start || it is DragInteraction.Stop || it is DragInteraction.Cancel) {
                    interactions += it
                }
                if (it is DragInteraction.Stop || it is DragInteraction.Cancel) overflow(source)
            }
        }
        return interactions
    }

    private fun overflow(source: MutableInteractionSource) {
        repeat(32) { source.tryEmit(PressInteraction.Press(Offset.Zero)) }
    }

    // Read the real pager from the lazy layout semantics so the before and after share a fixture.
    private fun pager(): PagerState {
        val state = pagerSemantics.javaClass.getDeclaredField("state")
            .apply { isAccessible = true }.get(pagerSemantics)
        return state.javaClass.declaredFields.single { PagerState::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }.get(state) as PagerState
    }

    private fun songs(start: Int): ImmutableList<Song> = (start until start + 12).map { id ->
        Song(
            id = "b65-$id", title = "Fixture $id", artist = "Fixture artist", artistId = 1,
            album = "Fixture album $id", albumId = id.toLong(), path = "/fixture/$id.flac",
            contentUriString = "content://fixture/song/$id", albumArtUriString = "content://fixture/art/$id",
            duration = 180_000, mimeType = "audio/flac", bitrate = 900, sampleRate = 44100,
        )
    }.toImmutableList()
}
