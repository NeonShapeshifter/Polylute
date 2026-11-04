package com.lostf1sh.pixelplayeross.presentation.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.B57Renders
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B57-fix1 4 (#52): the Animated covers rows of Settings. The folder picker opens **only** when the listener turns the switch on and no folder is kept; cancelling keeps it off and calls nothing;
 * with a folder the rows show its name with Change and Remove; before Android 13 there is no picker and no folder row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h420dp-xhdpi")
class B57Fix1AnimatedCoversSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")

    private fun state(stored: Boolean? = null, folder: String? = null, needsFolder: Boolean = true) =
        AppearanceSettingsState.from(SettingsUiState(animatedCoversStored = stored, animatedCoversFolder = folder)).copy(animatedCoversNeedsFolder = needsFolder)

    private fun show(state: AppearanceSettingsState, actions: AppearanceSettingsActions, dark: Boolean = false) = compose.setContent {
        DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) {
            Column(Modifier.fillMaxWidth().background(Dap.background)) { AnimatedCoversRows(state, actions) }
        }
    }

    private val activity: Activity get() = compose.activity

    @Test fun off_by_default_and_turning_it_on_with_no_folder_opens_the_picker_and_nothing_else() {
        val actions = mockk<AppearanceSettingsActions>(relaxed = true)
        show(state(), actions)
        compose.onAllNodes(isToggleable())[0].assertIsOff().performClick()

        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("the system folder picker was opened", started)
        val intent = started.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertTrue("read access, persisted", intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 && intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertEquals("never write access", 0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        verify(exactly = 0) { actions.onAnimatedCoversChange(any()) }
        compose.onAllNodesWithText(s(R.string.setcat_animated_covers_folder_remove_title)).assertCountEquals(0)
    }

    @Test fun cancelling_the_picker_keeps_it_off_and_changes_nothing() {
        val actions = mockk<AppearanceSettingsActions>(relaxed = true)
        show(state(), actions)
        compose.onAllNodes(isToggleable())[0].performClick()
        val started = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()

        verify(exactly = 1) { actions.onAnimatedCoverFolderPicked(null, 0) }
        verify(exactly = 0) { actions.onAnimatedCoversChange(any()) }
        compose.onAllNodes(isToggleable())[0].assertIsOff()
    }

    @Test fun a_chosen_folder_is_handed_over_with_its_flags_and_only_then_can_the_feature_turn_on() {
        val actions = mockk<AppearanceSettingsActions>(relaxed = true)
        show(state(), actions)
        compose.onAllNodes(isToggleable())[0].performClick()
        val started = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        compose.waitForIdle()
        verify(exactly = 1) { actions.onAnimatedCoverFolderPicked(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    @Test fun with_a_folder_the_rows_show_its_name_and_the_switch_turns_on_without_the_picker() {
        val actions = mockk<AppearanceSettingsActions>(relaxed = true)
        show(state(stored = false, folder = "Music"), actions)
        compose.onNodeWithText("Music", substring = true).assertExists()
        compose.onNodeWithText(s(R.string.setcat_animated_covers_folder_change)).assertExists()
        compose.onNodeWithText(s(R.string.setcat_animated_covers_folder_remove_title)).assertExists()
        compose.onAllNodes(isToggleable())[0].assertIsOff().performClick()
        verify(exactly = 1) { actions.onAnimatedCoversChange(true) }
        assertNull("no picker was opened", shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun change_opens_the_picker_again_and_remove_calls_remove() {
        val actions = mockk<AppearanceSettingsActions>(relaxed = true)
        show(state(stored = true, folder = "Music"), actions)
        compose.onAllNodes(isToggleable())[0].assertIsOn()
        compose.onNodeWithText(s(R.string.setcat_animated_covers_folder_change)).performClick()
        assertNotNull(shadowOf(activity).nextStartedActivityForResult)
        compose.onNodeWithText(s(R.string.setcat_animated_covers_folder_remove_title)).performClick()
        verify(exactly = 1) { actions.onAnimatedCoverFolderRemove() }
        verify(exactly = 0) { actions.onAnimatedCoversChange(any()) } // the view model turns it off with the removal: one call, not two
    }

    @Test fun turning_it_off_never_opens_the_picker() {
        val actions = mockk<AppearanceSettingsActions>(relaxed = true)
        show(state(stored = true, folder = "Music"), actions)
        compose.onAllNodes(isToggleable())[0].performClick()
        verify(exactly = 1) { actions.onAnimatedCoversChange(false) }
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun a_stored_on_with_a_folder_that_is_gone_shows_off_until_a_folder_is_chosen_again() {
        val actions = mockk<AppearanceSettingsActions>(relaxed = true)
        show(state(stored = true, folder = null), actions)
        compose.onAllNodes(isToggleable())[0].assertIsOff()
    }

    @Test fun before_android_13_there_is_no_picker_and_no_folder_row() {
        val actions = mockk<AppearanceSettingsActions>(relaxed = true)
        show(state(needsFolder = false), actions)
        compose.onAllNodes(isToggleable())[0].assertIsOff().performClick()
        verify(exactly = 1) { actions.onAnimatedCoversChange(true) }
        assertNull("no picker", shadowOf(activity).nextStartedActivityForResult)
        compose.onAllNodesWithText(s(R.string.setcat_animated_covers_folder_remove_title)).assertCountEquals(0)
    }

    private fun shot(name: String, state: AppearanceSettingsState, dark: Boolean) {
        show(state, mockk(relaxed = true), dark)
        B57Renders.save(compose, name)
    }

    @Test fun render_without_a_folder_light() = shot("animated-covers-settings-nofolder-light", state(), dark = false)
    @Test fun render_without_a_folder_dark() = shot("animated-covers-settings-nofolder-dark", state(), dark = true)
    @Test fun render_with_a_folder_light() = shot("animated-covers-settings-folder-light", state(stored = true, folder = "Music"), dark = false)
    @Test fun render_with_a_folder_dark() = shot("animated-covers-settings-folder-dark", state(stored = true, folder = "Music"), dark = true)
}
