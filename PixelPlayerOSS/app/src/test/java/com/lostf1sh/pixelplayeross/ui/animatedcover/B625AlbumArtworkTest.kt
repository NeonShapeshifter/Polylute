package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import coil.size.Size
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.presentation.components.ArtworkViewer
import com.lostf1sh.pixelplayeross.presentation.components.ArtworkViewerContent
import com.lostf1sh.pixelplayeross.presentation.screens.SharedAlbumTopBarProbe
import com.lostf1sh.pixelplayeross.presentation.screens.SharedArtistTopBarProbe
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h800dp-xhdpi")
class B625AlbumArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Composable private fun TestTheme(content: @Composable () -> Unit) {
        CompositionLocalProvider(com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation provides
            remember { com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority(androidx.activity.OnBackPressedDispatcher()) }) {
            DapTheme(content = content)
        }
    }
    private val out get() = File((System.getenv("B625_UI_RENDERS") ?: "build/tmp/b625-ui-renders")).apply { mkdirs() }
    private fun pattern(shift: Int): Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply {
            drawColor(0xFF28554F.toInt())
            drawRect((40 + shift).toFloat(), 40f, (180 + shift).toFloat(), 190f, Paint().apply { color = 0xFFDFAD59.toInt() })
            drawCircle(280f, 280f, 80f, Paint().apply { color = 0xFF607CC5.toInt() })
        }
    }
    private val still = pattern(0)
    private class Dir(var names: List<String> = listOf("cover.mp4")) : AnimatedCoverDirectory {
        override fun names(dir: File) = names
        override fun stamp(dir: File) = 1L
        override fun sizeOf(file: File) = 4000L
        override fun modifiedOf(file: File) = 9L
    }
    private class Engine(val cover: AnimatedCover, override val view: View) : AnimatedCoverEngine {
        override val firstFrame = MutableStateFlow(true)
        var runningNow = false
        var released = false
        override fun setFit(fit: Boolean) { (view as ImageView).scaleType = if (fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP }
        override fun setRunning(running: Boolean) { this.runningNow = running }
        override fun release() { released = true }
    }
    private val dir = Dir()
    private val finder = AnimatedCoverFinder(Dispatchers.Unconfined, dir, AlbumArtUtils::shouldTrustDirectoryArtwork) { 0L }
    private val engines = mutableListOf<Engine>()
    private var enabled by mutableStateOf(true)
    private var headerVisible by mutableStateOf(true)
    private var shown by mutableStateOf(true)
    private var viewer by mutableStateOf(false)
    private var headerHeight by mutableStateOf(300.dp)
    private lateinit var controller: AnimatedCoverController
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(500); compose.waitForIdle() }
    private fun save(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> File(out, "$name-en.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    private fun showAlbum() {
        val stillFile = File(out, "synthetic-cover.png").apply { outputStream().use { still.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val album = Album(1, "Synthetic sky", "Synthetic ensemble", 2026, 0L, stillFile.toURI().toString(), 12)
        compose.setContent {
            val ctx = LocalContext.current
            TestTheme {
                CompositionLocalProvider(LocalAnimatedCoverEnv provides AnimatedCoverEnv(finder, enabled, 0L), LocalAnimatedCoverEngines provides { _, _ ->
                    AnimatedCoverEngineFactory { cover, _, _ -> Engine(cover, ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(pattern(100)) }).also { engines += it } }
                }) {
                    controller = rememberSharedAnimatedCoverController()
                    CompositionLocalProvider(LocalAnimatedCoverController provides controller) {
                        Column(Modifier.fillMaxSize().background(Dap.background)) {
                            if (shown) {
                                val host = rememberAnimatedCoverHost("/music/Gaia/01.flac", { true }, true, headerVisible || viewer, albumSurface = true)
                                if (viewer) ArtworkViewerContent(still, "Synthetic sky", { viewer = false }, host)
                                else SharedAlbumTopBarProbe(album, 12, collapseFraction = 0f, headerHeight = headerHeight,
                                    headerImageRequestSize = Size(800, 800), onBackPressed = {}, onPlayClick = {}, onShuffleClick = {},
                                    animatedCover = host, onArtworkClick = { viewer = true })
                            }
                        }
                    }
                }
            }
        }
        settle()
    }
    @Test fun `album header chooses animation and releases after scrolling away and leaving the page`() {
        showAlbum()
        assertThat(engines.single().cover.file.name).isEqualTo("cover.mp4")
        assertThat(engines.single().runningNow).isTrue()
        save("album-animated-first-frame")
        headerHeight = 400.dp; settle(); save("album-enlarged")
        headerVisible = false; settle()
        assertThat(engines.single().released).isTrue()
        assertThat(controller.alive).isEqualTo(0)
        headerVisible = true; settle()
        assertThat(controller.alive).isEqualTo(1)
        shown = false; settle()
        assertThat(controller.alive).isEqualTo(0)
        assertThat(controller.made).isEqualTo(controller.released)
        File(out, "album-controller-counts.txt").writeText("made=${controller.made} released=${controller.released} alive=${controller.alive}\n")
    }
    @Test fun `switch off and missing source show still artwork without a decoder`() {
        enabled = false; showAlbum()
        assertThat(engines).isEmpty(); save("album-still")
        dir.names = emptyList(); enabled = true; settle()
        assertThat(engines).isEmpty()
    }
    @Test fun `album tap transfers same decoder to viewer and close restores header`() {
        showAlbum()
        val engine = engines.single()
        compose.onNodeWithTag("detail-artwork").performClick(); settle()
        compose.onNodeWithTag("artwork-viewer").assertExists(); save("artwork-viewer")
        assertThat(engines).hasSize(1)
        assertThat(engine.released).isFalse()
        compose.onNodeWithTag("artwork-zoom").performTouchInput { doubleClick() }; settle(); save("artwork-viewer-zoom")
        compose.onNodeWithTag("artwork-zoom").performTouchInput { doubleClick() }; settle()
        compose.onNodeWithTag("artwork-zoom").performTouchInput { swipeDown() }; settle()
        compose.onNodeWithTag("artwork-viewer").assertDoesNotExist()
        compose.onNodeWithTag("detail-artwork").assertExists()
        assertThat(engines).hasSize(1)
    }
    @Test fun `artist photo opens same viewer and Android back dismisses it`() {
        compose.setContent { TestTheme {
            if (viewer) ArtworkViewer(still, "Synthetic artist", { viewer = false }, mountInputBridge = false)
            SharedArtistTopBarProbe(artist = Artist(1, "Synthetic artist", 12), effectiveImageUrl = "file:///synthetic.png",
                songsCount = 12, collapseFraction = 0f, headerHeight = 300.dp, headerImageRequestSize = Size(800, 600), hasCustomImage = false,
                onBackPressed = {}, onPlayClick = {}, onShuffleClick = {}, onChangeImage = {}, onClearCustomImage = {}, onArtworkClick = { viewer = true })
        } }
        settle(); compose.onNodeWithTag("detail-artwork").performClick(); settle()
        compose.onNodeWithTag("artwork-viewer").assertExists()
        compose.runOnIdle { (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed() }; settle()
        compose.onNodeWithTag("artwork-viewer").assertDoesNotExist()
    }
    @Test fun `viewer pinches portrait artwork and fits video without cropping`() {
        assertThat(CoverCropFrame.fitted(400, 800, 2f / 3f)).isEqualTo(400 to 600)
        assertThat(CoverCropFrame.fitted(400, 800, 2f)).isEqualTo(400 to 200)
        assertThat(CoverCropFrame.fitted(400, 800, 1f)).isEqualTo(400 to 400)
        compose.setContent { TestTheme { ArtworkViewerContent(still, "Synthetic sky", {}) } }
        settle()
        val before = compose.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        compose.onNodeWithTag("artwork-zoom").performTouchInput {
            pinch(Offset(250f, 650f), Offset(550f, 950f), Offset(100f, 500f), Offset(700f, 1100f))
        }
        settle()
        val after = compose.onRoot().captureToImage().asAndroidBitmap()
        assertThat(before.sameAs(after)).isFalse()
    }

    @Test fun `shared album host pauses in background and with screen off then releases on exit`() {
        showAlbum()
        val engine = engines.single()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        settle(); assertThat(engine.runningNow).isFalse()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        settle(); assertThat(engine.runningNow).isTrue()
        val power = compose.activity.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        shadowOf(power).setIsInteractive(false)
        compose.activity.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_SCREEN_OFF))
        settle(); assertThat(engine.runningNow).isFalse()
        shown = false; settle()
        assertThat(controller.alive).isEqualTo(0)
    }
}
