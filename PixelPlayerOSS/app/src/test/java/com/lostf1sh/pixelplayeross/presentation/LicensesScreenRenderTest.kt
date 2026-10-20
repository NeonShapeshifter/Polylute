package com.lostf1sh.pixelplayeross.presentation

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.LicensesScreen
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B37 (C2): the Licenses screen lists the GPL as an entry of its own, next to the third-party notices.
 *
 * The real screen over the real assets of the build (`isIncludeAndroidResources`), not a copy of its text: the entry's heading is on screen,
 * the first words of the licence are under it, and scrolling reaches the third-party notices after it. The screen reads its documents on
 * `Dispatchers.IO`, which the compose clock does not wait for, so each step waits for the text it needs.
 *
 * The strings are looked up by name so that this class compiles against a tree that does not have the GPL entry yet and fails there on what
 * the screen shows, not on a missing resource id.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class LicensesScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun string(name: String): String {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        check(id != 0) { "no string resource $name" }
        return context.getString(id)
    }

    private fun show() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    LicensesScreen(rememberNavController())
                }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `the GPL has an entry of its own whose first words are the licence`() {
        show()
        // the licence text is on screen, and so is the heading it sits under, and that heading is not the third-party one
        waitForText("GNU GENERAL PUBLIC LICENSE")
        val gplHeading = string("about_licenses_gpl")
        compose.onNodeWithText(gplHeading).assertExists()
        assertThat(gplHeading).isNotEqualTo(string("about_licenses_third_party"))
        compose.onNodeWithText("Version 3, 29 June 2007", substring = true).assertExists()
    }

    @Test
    fun `the third-party notices are still on the screen after the GPL`() {
        show()
        waitForText("GNU GENERAL PUBLIC LICENSE")
        val thirdParty = string("about_licenses_third_party")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(thirdParty))
        compose.waitForIdle()
        compose.onNodeWithText(thirdParty).assertExists()
    }

    @Test
    fun `the notices show the statement that Polylute is a modified version`() {
        show()
        waitForText("GNU GENERAL PUBLIC LICENSE")
        val statement = "Polylute is a modified version of PixelPlayerOSS and of decent-player"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(statement, substring = true))
        compose.waitForIdle()
        compose.onNodeWithText(statement, substring = true).assertExists()
    }
}
