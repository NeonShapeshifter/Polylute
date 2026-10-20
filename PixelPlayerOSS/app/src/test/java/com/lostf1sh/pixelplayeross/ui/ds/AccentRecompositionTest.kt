package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B29-L11: the accent changes with the song on the full player and the lock screen, so a change of it must cost the widgets that wear it and
 * nothing else. A static composition local invalidates everything under its provider; a dynamic one only the composables that read it.
 * `LocalDapAccent` is dynamic (it was static, which was free while the accent only changed in Settings).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AccentRecompositionTest {

    @get:Rule
    val compose = createComposeRule()

    private val accent = mutableStateOf(Color(0xFF93B9A0))
    private var surfacesThatDoNotWearIt = 0
    private var widgetsThatWearIt = 0

    @Composable
    private fun Cover() {
        SideEffect { surfacesThatDoNotWearIt++ }
    }

    @Composable
    private fun Dot() {
        val color = LocalDapAccent.current
        SideEffect { widgetsThatWearIt++ }
        @Suppress("UNUSED_EXPRESSION") color
    }

    @Test
    fun `a new accent recomposes the widgets that wear it, not the cover, the header or the rest`() {
        compose.setContent {
            CompositionLocalProvider(LocalDapAccent provides accent.value) {
                Cover()
                Cover()
                Dot()
                Dot()
            }
        }
        compose.waitForIdle()
        assertThat(surfacesThatDoNotWearIt).isEqualTo(2)
        assertThat(widgetsThatWearIt).isEqualTo(2)

        // the next song's cover gives another colour: five song changes in a row
        repeat(5) { n ->
            compose.runOnUiThread { accent.value = Color(0xFF000000 or (0x112233L * (n + 1)).toInt().toLong()); Snapshot.sendApplyNotifications() }
            compose.waitForIdle()
        }
        println("B29-L11 | five accent changes | surfaces that do not wear it recomposed ${surfacesThatDoNotWearIt - 2}x, widgets that wear it ${widgetsThatWearIt - 2}x")
        assertThat(surfacesThatDoNotWearIt).isEqualTo(2)
        assertThat(widgetsThatWearIt).isEqualTo(2 + 2 * 5)
    }
}
