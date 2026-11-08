package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.B61Renders
import com.lostf1sh.pixelplayeross.presentation.LocalDapMode
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.mockk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B61 item 5 (#70): the switch "Also on the lock screen" under Animated covers. Off by default; offered only where Polylute has a lock screen of its own (a DAP) and Animated covers is on; a tap
 * calls the action that writes the preference.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h560dp-xhdpi")
open class B61AnimatedCoversLockSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    protected open val tag: String = "en"
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)

    private fun show(state: AppearanceSettingsState, actions: AppearanceSettingsActions, dap: Boolean) = compose.setContent {
        DapTheme(darkTheme = false, baseTheme = DapBaseTheme.Light, accent = DapAccent.Sage) {
            CompositionLocalProvider(LocalDapMode provides dap) {
                Column(Modifier.fillMaxWidth().background(Dap.background)) { AnimatedCoversRows(state, actions) }
            }
        }
    }

    private fun state(stored: Boolean?, onLock: Boolean = false) =
        AppearanceSettingsState.from(SettingsUiState(animatedCoversStored = stored, animatedCoversOnLock = onLock, animatedCoversFolder = "Music")).copy(animatedCoversNeedsFolder = true)

    @Test
    fun `the preference is off until the listener turns it on`() {
        assertThat(SettingsUiState().animatedCoversOnLock).isFalse()
        assertThat(AppearanceSettingsState.from(SettingsUiState()).animatedCoversOnLock).isFalse()
    }

    @Test
    fun `on a DAP with Animated covers on the row is there, off by default, and a tap calls the action`() {
        val actions = mockk<AppearanceSettingsActions>(relaxed = true)
        show(state(stored = true), actions, dap = true)
        compose.onAllNodesWithText(s(R.string.setcat_animated_covers_lock_title)).assertCountEquals(1)
        compose.onAllNodesWithText(s(R.string.setcat_animated_covers_lock_subtitle)).assertCountEquals(1)
        B61Renders.save(compose, "animated-covers-lock-row-$tag")
        compose.onAllNodes(isToggleable())[1].performClick()   // the first switch is Animated covers, the second is this one
        verify(exactly = 1) { actions.onAnimatedCoversOnLockChange(true) }
    }

    @Test
    fun `a stored on is drawn on and a tap turns it off`() {
        val actions = mockk<AppearanceSettingsActions>(relaxed = true)
        show(state(stored = true, onLock = true), actions, dap = true)
        compose.onAllNodes(isToggleable())[1].performClick()   // the first switch is Animated covers, the second is this one
        verify(exactly = 1) { actions.onAnimatedCoversOnLockChange(false) }
    }

    @Test
    fun `in app mode there is no lock screen of Polylute's, so no row`() {
        show(state(stored = true), mockk(relaxed = true), dap = false)
        compose.onAllNodesWithText(s(R.string.setcat_animated_covers_lock_title)).assertCountEquals(0)
    }

    @Test
    fun `with Animated covers off there is nothing to animate, so no row`() {
        show(state(stored = false), mockk(relaxed = true), dap = true)
        compose.onAllNodesWithText(s(R.string.setcat_animated_covers_lock_title)).assertCountEquals(0)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h560dp-xhdpi")
class B61AnimatedCoversLockSettingsEsTest : B61AnimatedCoversLockSettingsTest() { override val tag = "es" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h560dp-xhdpi")
class B61AnimatedCoversLockSettingsSvTest : B61AnimatedCoversLockSettingsTest() { override val tag = "sv" }
