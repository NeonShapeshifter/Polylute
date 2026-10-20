package com.lostf1sh.pixelplayeross.presentation.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.*

class MiniPlayerLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun prepare() { TestScreen.keepAwake(compose.activity) }
    @After fun cleanup() { TestScreen.release(compose.activity) }
    @Test fun measuredHeightContainsMetadataAndThreeSeparateTouchTargetsAtBothWidthsAndScales() {
        val song = Song.emptySong().copy(title = "A long song title that must leave space for the transport", artist = "An artist with a long name")
        var width by mutableStateOf(360.dp)
        var scale by mutableFloatStateOf(1f)
        var measuredHeight = 0.dp
        var actualDensity = 1f
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, scale)) {
                DapTheme {
                    val height = rememberMiniPlayerHeight(song, false, width)
                    measuredHeight = height; actualDensity = LocalDensity.current.density
                    Box(Modifier.width(width)) {
                        MiniPlayerContentInternal(song, false, false, {}, {}, {}, Modifier.testTag("mini"))
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (w in listOf(360.dp, 320.dp)) for (s in listOf(1f, 1.5f)) {
            compose.runOnIdle { width = w; scale = s }
            val shelf = compose.onNodeWithTag("mini").fetchSemanticsNode().boundsInRoot
            val previous = compose.onNodeWithContentDescription(context.getString(R.string.previous_track)).fetchSemanticsNode().boundsInRoot
            val play = compose.onNodeWithContentDescription(context.getString(R.string.cd_play)).fetchSemanticsNode().boundsInRoot
            val next = compose.onNodeWithContentDescription(context.getString(R.string.next_track)).fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithText(song.title).fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                Assert.assertEquals(measuredHeight.value * actualDensity, shelf.height, 2f)
                Assert.assertTrue(title.right <= previous.left)
                Assert.assertTrue(previous.right <= play.left && play.right <= next.left)
                Assert.assertTrue(title.width > 0f)
                Assert.assertTrue(shelf.height < 100f * actualDensity)
                Assert.assertEquals(previous.center.y, play.center.y, 1f)
                Assert.assertEquals(play.center.y, next.center.y, 1f)
                Assert.assertTrue(next.bottom <= shelf.bottom)
                Assert.assertEquals(48f * actualDensity, play.height, 1f)
                Assert.assertEquals(48f * actualDensity, play.width, 1f)
            }
        }
    }
}
