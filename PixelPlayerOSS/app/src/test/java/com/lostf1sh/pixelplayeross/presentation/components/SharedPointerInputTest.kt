package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B41-fix1 (B9): the scrollbar's input lies over the end of a list. An ordinary pointer input takes the hit from the sibling under it (the control
 * of a row would get no tap), [sharedPointerInput] does not: it hears the touch first and leaves what it does not consume to the sibling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class SharedPointerInputTest {
    @get:Rule
    val compose = createComposeRule()

    private var underneath = 0
    private var heardOnTop = 0

    private fun show(shared: Boolean, nested: Boolean = false, plainNested: Boolean = false) {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(100.dp).background(Color.Red).clickable { underneath++ }.testTag("under"))
                val onTop = Modifier.size(100.dp).testTag("top")
                val handler: suspend androidx.compose.ui.input.pointer.PointerInputScope.() -> Unit = {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false); heardOnTop++ }
                }
                val input = if (shared) Modifier.sharedPointerInput(handler) else Modifier.pointerInput(Unit, handler)
                if (plainNested) {
                    Box(Modifier.align(Alignment.TopStart).size(100.dp).testTag("top")) { Box(Modifier.fillMaxSize().then(input)) }
                } else if (nested) {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.align(Alignment.TopStart).size(100.dp).testTag("top")) {
                        // (the scope is used: this is the bar's own structure, a box sized from its constraints)
                        Box(Modifier.size(maxWidth, maxHeight).then(input))
                    }
                } else {
                    Box(Modifier.align(Alignment.TopStart).then(onTop).then(input))
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun a_shared_input_on_top_hears_the_touch_and_the_sibling_under_it_gets_its_tap_too() {
        show(shared = true)
        compose.onNodeWithTag("top").performTouchInput { click(center) }
        compose.waitForIdle()
        assertThat(heardOnTop).isEqualTo(1)
        assertThat(underneath).isEqualTo(1)
    }

    @Test
    fun an_ordinary_input_on_top_takes_the_hit_from_the_sibling_under_it() {
        show(shared = false)
        compose.onNodeWithTag("top").performTouchInput { click(center) }
        compose.waitForIdle()
        assertThat(heardOnTop).isEqualTo(1)
        assertThat(underneath).isEqualTo(0)
    }

    @Test
    fun a_shared_input_nested_inside_another_layout_does_not_share_so_the_bar_keeps_it_on_its_outer_node() {
        // what ExpressiveScrollBar relies on: the share is the sibling's own node's, not one inside it (Compose, 1.11)
        show(shared = true, nested = true)
        compose.onNodeWithTag("top").performTouchInput { click(center) }
        compose.waitForIdle()
        assertThat(heardOnTop).isEqualTo(1)
        assertThat(underneath).isEqualTo(0)
    }
}
