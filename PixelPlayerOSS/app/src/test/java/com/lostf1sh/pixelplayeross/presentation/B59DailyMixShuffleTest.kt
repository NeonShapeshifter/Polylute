package com.lostf1sh.pixelplayeross.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.DailyMixHeader
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B59 3 (#61): the Daily Mix header's shuffle is a quiet icon, like the other icon buttons of the card (the songs' overflow glyphs), not the white circle of "Your mix". The owner
 * (11-04): "the button must not be a white circle: a subtle shuffle icon". Built from `ui/ds` ([com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton]). The check is on pixels: inside the
 * button's own area (where a disc would be) the colour is the card's own panel, in light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class B59DailyMixShuffleTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun render(dark: Boolean, onShuffle: () -> Unit = {}) = compose.setContent {
        DapTheme(darkTheme = dark) {
            Box(Modifier.fillMaxWidth().background(Dap.panel).padding(8.dp)) {
                DailyMixHeader(thumbnails = persistentListOf(), onShuffle = onShuffle)
            }
        }
    }

    private fun assertQuiet(dark: Boolean) {
        render(dark)
        val description = context.getString(R.string.cd_shuffle_play)
        compose.onAllNodes(hasContentDescription(description)).assertCountEquals(1)
        val bounds = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val panel = image.getPixel(2, 2) // the card's own background, at the corner of the render
        // the left edge of the button's area, halfway down: inside a disc of the button's size, outside its glyph
        val x = (bounds.left + bounds.width * 0.08f).toInt()
        val y = bounds.center.y.toInt()
        B59Renders.save(compose, "daily-mix-header-${if (dark) "dark" else "light"}")
        assertThat(image.getPixel(x, y)).isEqualTo(panel)
    }

    @Test fun `in the dark theme the shuffle is an icon with no disc behind it`() = assertQuiet(dark = true)

    @Test fun `in the light theme the shuffle is an icon with no disc behind it`() = assertQuiet(dark = false)

    @Test fun `it is still one control and a tap shuffles`() {
        var shuffled = 0
        render(dark = true) { shuffled++ }
        compose.onNodeWithContentDescription(context.getString(R.string.cd_shuffle_play)).performClick()
        assertThat(shuffled).isEqualTo(1)
    }
}
