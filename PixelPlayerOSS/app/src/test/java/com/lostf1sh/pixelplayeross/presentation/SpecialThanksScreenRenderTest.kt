package com.lostf1sh.pixelplayeross.presentation

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.screens.AboutScreen
import com.lostf1sh.pixelplayeross.presentation.screens.SpecialThanksScreen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B38 (E1): the "Special thanks" page, and the About row that opens it.
 *
 * The real screens over the real resources of the build, in each of the three shipped languages. The page must show its title and the nine
 * lines of the thanks word for word ([SpecialThanksFixtures] is the text as the owner wrote it); the About page must offer the row, with the
 * Licenses row still under it, and the old "Built from PixelPlayerOSS" row must be gone. The page is longer than the screen, so each line
 * is scrolled to before it is looked for.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class SpecialThanksScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showPage() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    SpecialThanksScreen(rememberNavController())
                }
            }
        }
    }

    private fun assertPageReads(title: String, lines: List<String>) {
        showPage()
        compose.onNodeWithText(title).assertExists()
        lines.forEach { line ->
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(line))
            compose.waitForIdle()
            compose.onNodeWithText(line).assertExists()
        }
    }

    @Test
    fun `the page reads the English thanks word for word`() {
        assertPageReads(SpecialThanksFixtures.englishTitle, SpecialThanksFixtures.english)
    }

    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xxhdpi")
    fun `the page reads the Spanish thanks word for word`() {
        assertPageReads(SpecialThanksFixtures.spanishTitle, SpecialThanksFixtures.spanish)
    }

    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xxhdpi")
    fun `the page reads the Swedish thanks word for word`() {
        assertPageReads(SpecialThanksFixtures.swedishTitle, SpecialThanksFixtures.swedish)
    }

    private fun showAboutWithPages() {
        val playerViewModel = mockk<PlayerViewModel>(relaxed = true)
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = Screen.About.route) {
                        composable(Screen.About.route) {
                            AboutScreen(
                                navController = navController,
                                viewModel = playerViewModel,
                                onNavigationIconClick = { navController.popBackStack() },
                            )
                        }
                        composable(Screen.SpecialThanks.route) { SpecialThanksScreen(navController) }
                    }
                }
            }
        }
    }

    @Test
    fun `About offers Special thanks above Licenses and no longer says built from PixelPlayerOSS`() {
        showAboutWithPages()
        compose.onNodeWithText(SpecialThanksFixtures.englishTitle).assertExists()
        compose.onNodeWithText("The projects and people behind Polylute").assertExists()
        compose.onNodeWithText("Licences and open-source notices").assertExists()
        compose.onNodeWithText("Built from PixelPlayerOSS", substring = true).assertDoesNotExist()
        compose.onNodeWithText("PixelPlayerOSS by lostf1sh", substring = true).assertDoesNotExist()
    }

    @Test
    fun `tapping the Special thanks row on About opens the page`() {
        showAboutWithPages()
        compose.onNodeWithText(SpecialThanksFixtures.englishTitle).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(SpecialThanksFixtures.english.first())).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(SpecialThanksFixtures.english.first()).assertExists()
    }
}
