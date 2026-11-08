package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.presentation.B59Renders
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B59 4 (#82): the album page's "Disc N" headers with each disc's cover small beside them (the composable the page uses, [DiscHeader], over drawn colour squares: not the owner's covers).
 * An album with no disc covers keeps the plain header, as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class B59DiscHeadersRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private fun page(dark: Boolean, covers: Boolean) = compose.setContent {
        DapTheme(darkTheme = dark) {
            Surface(Modifier.fillMaxSize(), color = Dap.background) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    for ((disc, color) in listOf(1 to Color(0xFF8E3B2F), 2 to Color(0xFF2F6F8E))) {
                        DiscHeader(
                            text = "DISC $disc",
                            cover = if (covers) ({ androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(color)) }) else null,
                            coverTag = "disc_cover_$disc",
                        )
                        repeat(2) { DapMediaRow(title = "Track ${disc}-${it + 1}", subtitle = "Alvaro Diaz", duration = "3:0$it", onClick = {}) }
                    }
                }
            }
        }
    }

    @Test fun `with a cover per disc each header shows its own, small, beside the text`() {
        page(dark = true, covers = true)
        compose.onAllNodesWithText("DISC 1").assertCountEquals(1)
        compose.onAllNodesWithText("DISC 2").assertCountEquals(1)
        compose.onAllNodesWithTag("disc_cover_1").assertCountEquals(1)
        compose.onAllNodesWithTag("disc_cover_2").assertCountEquals(1)
        RenderChecks.assertInsideWindow(compose, "disc headers with covers")
        RenderChecks.assertNoCutText(compose, "disc headers with covers")
        B59Renders.save(compose, "album-disc-headers-dark")
    }

    @Test fun `the same page in the light theme`() {
        page(dark = false, covers = true)
        compose.onAllNodesWithTag("disc_cover_2").assertCountEquals(1)
        RenderChecks.assertNoCutText(compose, "disc headers with covers, light")
        B59Renders.save(compose, "album-disc-headers-light")
    }

    @Test fun `an album with no cover of its own per disc keeps the plain header`() {
        page(dark = true, covers = false)
        compose.onAllNodesWithText("DISC 1").assertCountEquals(1)
        compose.onAllNodesWithTag("disc_cover_1").assertCountEquals(0)
        compose.onAllNodesWithTag("disc_cover_2").assertCountEquals(0)
        B59Renders.save(compose, "album-disc-headers-plain-dark")
    }
}
