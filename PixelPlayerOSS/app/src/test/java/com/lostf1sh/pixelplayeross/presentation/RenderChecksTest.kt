package com.lostf1sh.pixelplayeross.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B22: the clipping checks only mean something if they can fail. This puts a text in a box too narrow for it, and a
 * box past the window's edge, and asserts that [RenderChecks] says so; and that a text that fits is left alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderChecksTest {

    @get:Rule
    val compose = createComposeRule()

    private fun fails(block: () -> Unit): Boolean = try { block(); false } catch (e: IllegalStateException) { true }

    @Test
    fun `a text that lost its tail to an ellipsis is reported`() {
        compose.setContent {
            Text("Una frase demasiado larga para la caja en la que está", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(60.dp))
        }
        assertTrue(fails { RenderChecks.assertNoCutText(compose, "narrow") })
        // Unless the caller says that this text may end in an ellipsis (a song title).
        RenderChecks.assertNoCutText(compose, "narrow, allowed") { it.startsWith("Una frase") }
    }

    @Test
    fun `a text that fits is not reported`() {
        compose.setContent { Text("Sí", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(200.dp)) }
        RenderChecks.assertNoCutText(compose, "fits")
    }

    @Test
    fun `a node laid out past the window's edge is reported`() {
        compose.setContent {
            Row {
                Box(Modifier.size(10.dp))
                Box(Modifier.offset(x = 400.dp).size(40.dp).semantics { testTag = "out" })
            }
        }
        assertTrue(fails { RenderChecks.assertInsideWindow(compose, "past the edge") })
    }

    @Test
    fun `nodes inside the window are not reported`() {
        compose.setContent { Row { Box(Modifier.size(100.dp)); Box(Modifier.size(100.dp)) } }
        RenderChecks.assertInsideWindow(compose, "inside")
    }
}
