package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.animation.core.snap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import org.junit.After
import org.junit.Before
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.player.AnimatedPlaybackControls
import com.lostf1sh.pixelplayeross.ui.ds.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // A dedicated-device task cannot destroy its test Activity while task locking is active.
    @Before fun prepareTestWindow() { TestScreen.keepAwake(compose.activity) }
    @After fun releaseTestTask() { TestScreen.release(compose.activity) }
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun miniPlayerHasOneNamePerTransportAndUpdatesPlayAction() {
        val playing = mutableStateOf(false)
        compose.setContent {
            DapTheme {
                MiniPlayerContentInternal(Song.emptySong().copy(title = "Test track", artist = "Test artist"),
                    playing.value, false, { playing.value = !playing.value }, {}, {})
            }
        }
        compose.onAllNodesWithContentDescription(label(R.string.previous_track)).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(label(R.string.next_track)).assertCountEquals(1)
        compose.onNodeWithContentDescription(label(R.string.cd_play)).performClick()
        compose.onAllNodesWithContentDescription(label(R.string.cd_pause)).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(label(R.string.cd_play)).assertCountEquals(0)
    }

    @Test fun fullTransportHasOneNamePerActionAndUpdatesPlayAction() {
        val playing = mutableStateOf(false)
        compose.setContent {
            DapTheme {
                CompositionLocalProvider(LocalMaterialTheme provides MaterialTheme.colorScheme) {
                    AnimatedPlaybackControls({ playing.value }, {}, { playing.value = !playing.value }, {},
                        pressAnimationSpec = snap())
                }
            }
        }
        compose.onAllNodesWithContentDescription(label(R.string.previous_track)).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(label(R.string.next_track)).assertCountEquals(1)
        compose.onNodeWithContentDescription(label(R.string.cd_play)).performClick()
        compose.onAllNodesWithContentDescription(label(R.string.cd_pause)).assertCountEquals(1)
    }

    @Test fun playerHeaderIsThreeNamedIconsAndTheLyricsButtonExposesItsState() {
        val lyrics = mutableStateOf(false)
        var collapsed = 0
        compose.setContent { DapTheme { DapPlayerHeader(lyrics.value, { collapsed++ }, { lyrics.value = !lyrics.value }, {}) } }
        compose.onNodeWithContentDescription(label(R.string.player_header_lyrics)).assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription(label(R.string.player_header_lyrics)).assertIsSelected()
        compose.onNodeWithContentDescription(label(R.string.player_header_collapse)).performClick()
        compose.runOnIdle { assertEquals(1, collapsed) }
        compose.onAllNodesWithContentDescription(label(R.string.player_header_details)).assertCountEquals(1)
    }

    @Test fun disabledSaveDoesNotInvokeItsActionAndMenuHasOneName() {
        val enabled = mutableStateOf(false)
        var saves = 0
        compose.setContent { DapTheme {
            androidx.compose.foundation.layout.Column {
                DapPrimaryButton(stringResource(R.string.action_save), { saves++ }, enabled = enabled.value)
                DapOverflowButton({}, description = stringResource(R.string.library_menu_options))
            }
        } }
        compose.onNodeWithText(label(R.string.action_save)).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, saves); enabled.value = true }
        compose.onNodeWithText(label(R.string.action_save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onAllNodesWithContentDescription(label(R.string.library_menu_options)).assertCountEquals(1)
    }
}
