package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.screens.HomeLayout
import com.lostf1sh.pixelplayeross.presentation.screens.YourMixHeader
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapAlbumGridItem
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapBrandHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapIconButton
import com.lostf1sh.pixelplayeross.ui.ds.DapIconButtonEdgeInset
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B32-Q1: Home's wordmark is about 15 % smaller and "Tu mix de hoy" is about a third closer to it. Drawn at the HONOR's own size
 * (720 x 1610 px, density 2.0), the same top of the page as Home builds it: the status bar's room, the title row with the gear, the
 * gap, the mix heading with its shuffle disc, and the first row of covers.
 *
 * The "gap" the owner sees is ink to ink: from the last row of the wordmark's pixels to the first row of the heading's. It is measured
 * on the image, not read off the layout, and compared with the same page laid out as it was (the 200 dp wordmark and the 24 dp
 * section gap, drawn in the test from the code that was there), so the claim "about a third" is tested against the real before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B32HomeRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val heading get() = context.getString(R.string.home_your_mix_heading)

    /** What the HONOR's status bar takes (the phone screenshot puts the wordmark's top 92 px down: 46 dp). */
    private val statusBar = 37.dp

    @Composable
    private fun Page(wordmark: @Composable () -> Unit, gap: androidx.compose.ui.unit.Dp) {
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            Column(
                Modifier.fillMaxSize().background(Dap.background).padding(top = statusBar + DapSpacing.Sm),
            ) {
                Row(Modifier.padding(start = DapSpacing.Gutter, end = DapSpacing.Gutter - DapIconButtonEdgeInset)) { wordmark() }
                Spacer(Modifier.height(gap))
                YourMixHeader(song = heading, isShuffleEnabled = true, onPlayShuffled = {})
                Spacer(Modifier.height(HomeLayout.SectionGap))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter),
                    horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
                ) {
                    listOf("Ni Fu Ni Fa" to "Ska-P", "7/8" to "WOS", "SR. SMITH" to "Peso Pluma").forEach { (title, artist) ->
                        DapAlbumGridItem(title = title, subtitle = artist, modifier = Modifier.weight(1f), artwork = {
                            Spacer(Modifier.fillMaxSize().background(Dap.surfaceRaised))
                        })
                    }
                }
            }
        }
    }

    private var showingBefore by mutableStateOf(false)

    /**
     * Both pages in one composition, switched by a field: the page as it is now (the real wordmark, the real gap) and the page as it
     * was until B31 (the same wordmark drawn 200 dp wide, 24 dp above the heading, drawn here from the code that was there).
     */
    private fun show() = compose.setContent {
        if (showingBefore) {
            Page(
                wordmark = {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Icon(
                            painter = painterResource(R.drawable.polylute_wordmark),
                            contentDescription = "POLYLUTE",
                            tint = LocalDapAccent.current,
                            modifier = Modifier.widthIn(max = 200.dp).weight(1f, fill = false).aspectRatio(1048f / 240f),
                        )
                        DapIconButton(kind = DapIconKind.Gear, contentDescription = "Settings", onClick = {})
                    }
                },
                gap = HomeLayout.SectionGap,
            )
        } else {
            Page(
                wordmark = {
                    DapBrandHeader(trailing = { DapIconButton(kind = DapIconKind.Gear, contentDescription = "Settings", onClick = {}) })
                },
                gap = HomeLayout.gapBefore("your_mix_header"),
            )
        }
    }

    private fun measureThe(before: Boolean): Ink {
        compose.runOnUiThread { showingBefore = before }
        compose.waitForIdle()
        return measure()
    }

    private class Ink(val logoLeft: Float, val logoWidth: Float, val logoBottom: Int, val headingTop: Int, val densityDpPerPx: Float) {
        val gapDp: Float get() = (headingTop - logoBottom) * densityDpPerPx
    }

    /** Ink to ink, from the image: the rows where the wordmark's pixels end and the heading's begin. */
    private fun measure(): Ink {
        val logo = compose.onNodeWithContentDescription("POLYLUTE").fetchSemanticsNode().boundsInRoot
        val head = compose.onNodeWithText(heading).fetchSemanticsNode().boundsInRoot
        val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val background = bitmap.getPixel(2, 2)
        fun differs(x: Int, y: Int): Boolean {
            val p = bitmap.getPixel(x, y)
            return Math.abs(AColor.red(p) - AColor.red(background)) + Math.abs(AColor.green(p) - AColor.green(background)) +
                Math.abs(AColor.blue(p) - AColor.blue(background)) > 60
        }
        fun rowHasInk(y: Int, from: Float, to: Float): Boolean =
            (from.toInt().coerceAtLeast(0) until to.toInt().coerceAtMost(bitmap.width)).any { differs(it, y) }
        var logoBottom = logo.bottom.toInt()
        while (logoBottom > logo.top.toInt() && !rowHasInk(logoBottom, logo.left, logo.right)) logoBottom--
        var headingTop = head.top.toInt()
        while (headingTop < head.bottom.toInt() && !rowHasInk(headingTop, head.left, head.right)) headingTop++
        return Ink(logo.left, logo.width, logoBottom, headingTop, densityDpPerPx = 0.5f)
    }

    @Test
    fun `the wordmark is 15 percent smaller, on the same left edge and in the same proportions`() {
        show()
        val before = measureThe(before = true)
        val after = measureThe(before = false)
        assertEquals("the mark's left edge does not move", before.logoLeft, after.logoLeft, 1f)
        assertEquals("15 % narrower", 0.85f, after.logoWidth / before.logoWidth, 0.01f)
        val bounds = compose.onNodeWithContentDescription("POLYLUTE").fetchSemanticsNode().boundsInRoot
        assertEquals("its proportions are the vector's own", 1048f / 240f, bounds.width / bounds.height, 0.02f)
    }

    @Test
    fun `the heading is about a third closer to the wordmark, not more`() {
        show()
        val before = measureThe(before = true)
        B32Renders.saveAs(compose, "q1-home-before")
        val after = measureThe(before = false)
        B32Renders.saveAs(compose, "q1-home-after")
        val ratio = after.gapDp / before.gapDp
        println("B32-Q1 ink gap before=${before.gapDp} dp after=${after.gapDp} dp ratio=$ratio")
        assertTrue("about a third shorter (0.62..0.72), was $ratio", ratio in 0.62f..0.72f)
    }

}
