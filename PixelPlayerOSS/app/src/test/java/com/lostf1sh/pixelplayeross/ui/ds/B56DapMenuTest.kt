package com.lostf1sh.pixelplayeross.ui.ds

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B56 phase 2 (#42): the one overflow menu. Opens, closes, takes a tap, and the option being pressed lies on a pill (not a bar) of the accent's tone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B56DapMenuTest {
    @get:Rule val compose = createComposeRule()

    private var open by mutableStateOf(false)
    private val taps = mutableListOf<String>()

    private fun setMenu(dark: Boolean = true) {
        compose.setContent {
            DapTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 4.dp)) {
                        DapOverflowButton(onClick = { open = true }, modifier = Modifier.testTag("button"))
                        DapMenu(expanded = open, onDismissRequest = { open = false }) {
                            DapMenuItem(text = "Audio bookmarks", onClick = { taps += "bookmarks"; open = false })
                            DapMenuItem(text = "Settings", onClick = { taps += "settings"; open = false })
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the menu is closed until the button is pressed, then shows its options`() {
        setMenu()
        compose.onNodeWithText("Settings").let { runCatching { it.assertIsDisplayed() }.let { r -> assertThat(r.isFailure).isTrue() } }

        compose.onNodeWithTag("button").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Audio bookmarks").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun `tapping an option calls it and the menu goes`() {
        setMenu()
        compose.onNodeWithTag("button").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)

        assertThat(taps).containsExactly("settings")
        assertThat(open).isFalse()
        assertThat(runCatching { compose.onNodeWithText("Settings").assertIsDisplayed() }.isFailure).isTrue()
    }

    private fun pressedLook(dark: Boolean) {
        compose.setContent {
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light) {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    DapMenuSurface(Modifier.align(Alignment.TopStart).width(240.dp).testTag("menu")) {
                        DapMenuItem(text = "Audio bookmarks", onClick = {}, modifier = Modifier.testTag("item"))
                        DapMenuItem(text = "Settings", onClick = {})
                    }
                }
            }
        }
        val menu = compose.onNodeWithTag("menu")
        val item = compose.onNodeWithTag("item")
        fun px(dp: Float) = (dp * 2f).toInt()                                    // xhdpi
        fun pixels(): List<Int> {
            val image = menu.captureToImage().asAndroidBitmap()
            // the first option is 48 dp tall, below an 8 dp pad, and inset 8 dp from the menu's sides; its pill is fully round at both ends
            return listOf(
                image.getPixel(px(2f), px(32f)),                                 // between the menu's edge and the pill
                image.getPixel(px(14f), px(32f)),                                // inside the pill, left of the text
                image.getPixel(px(9f), px(9f)),                                  // the corner of the pill's box: cut away by the round end
            )
        }
        val resting = pixels()
        item.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)           // a press inside a scrollable column is shown after the platform's tap timeout (100 ms), as for any scrolling list
        compose.waitForIdle()
        val pressed = pixels()
        item.performTouchInput { up() }

        assertThat(pressed[1]).isNotEqualTo(resting[1])                          // the pill appears under a finger
        assertThat(pressed[0]).isEqualTo(resting[0])                             // not a bar to the menu's edge: the margin stays the container's
        assertThat(pressed[2]).isEqualTo(resting[2])                             // and its ends are round: the corner of its box is not painted
    }

    @Test fun `a pressed option lies on a pill of the accent in the dark theme`() = pressedLook(dark = true)

    @Test fun `a pressed option lies on a pill of the accent in the light theme`() = pressedLook(dark = false)
}
