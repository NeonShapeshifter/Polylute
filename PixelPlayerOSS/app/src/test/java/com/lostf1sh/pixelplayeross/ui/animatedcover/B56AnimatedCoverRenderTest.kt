package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
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

/**
 * B56 phase 7.4: the cover page with its still cover and with the animated cover over it (left, right), light and dark. **A JVM decodes no video, GIF or WebP**, so the animated
 * pane shows a frame of the same generated pattern the fixtures are made of, put on the cover through the real chain (finder, controller, host, layer, the page's rounded mask): what
 * it proves is where the animation is drawn, how it is clipped and that it lies exactly over the still cover. How it plays is the device's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w720dp-h400dp-xhdpi")
class B56AnimatedCoverRenderTest {
    @get:Rule val compose = createComposeRule()

    private val out: File
        get() = File(System.getenv("B56_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B56-gates/renders").apply { mkdirs() }

    private fun pattern(colour: Int, box: Int): Bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it)
        c.drawColor(colour)
        c.drawRect(60f, 80f, 140f, 160f, Paint().apply { color = 0xFFE0C060.toInt() })
        c.drawRect(box.toFloat(), 190f, box + 60f, 250f, Paint().apply { color = 0xFF80A0FF.toInt() })
    }

    private class Dir : AnimatedCoverDirectory {
        override fun names(dir: File) = listOf("cover.mp4")
        override fun stamp(dir: File) = 1L
        override fun sizeOf(file: File) = 4_000L
        override fun modifiedOf(file: File) = 9L
    }

    private fun render(name: String, dark: Boolean) {
        val still = pattern(0xFF3A5F4A.toInt(), 30)
        val frame = pattern(0xFF3A5F4A.toInt(), 150)
        val finder = AnimatedCoverFinder(Dispatchers.Unconfined, Dir(), AlbumArtUtils::shouldTrustDirectoryArtwork) { 0L }
        compose.setContent {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) {
                Row(Modifier.fillMaxSize().background(Dap.background).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // left: the still cover, as every list and the lock screen show it
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Image(still.asImageBitmap(), null, Modifier.size(240.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Fit)
                    }
                    Spacer(Modifier.width(16.dp))
                    // right: the full player's cover page, the animation over the still cover, inside the page's rounded mask
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        CompositionLocalProvider(
                            LocalAnimatedCoverEnv provides AnimatedCoverEnv(finder, true, 0L),
                            LocalAnimatedCoverEngines provides { _, _ ->
                                AnimatedCoverEngineFactory { _, _, _ ->
                                    object : AnimatedCoverEngine {
                                        override val view: View = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(frame) }
                                        override val firstFrame = MutableStateFlow(true)
                                        override fun setRunning(running: Boolean) = Unit
                                        override fun release() = Unit
                                    }
                                }
                            },
                        ) {
                            val host = rememberAnimatedCoverHost("/music/Gaia/01.flac", { true }, true, true)
                            Box(Modifier.size(240.dp).clip(RoundedCornerShape(18.dp))) {
                                Image(still.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                Box(Modifier.fillMaxSize().onSizeChanged { host?.onSize(it.width) }) { AnimatedCoverLayer(host) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "animated-cover-$name-still-left-animated-right.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `the animated cover lies over the still one in the dark theme`() = render("dark", dark = true)

    @Test fun `the animated cover lies over the still one in the light theme`() = render("light", dark = false)
}
