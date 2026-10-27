package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.lang.reflect.Proxy
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B41-fix1 (B1), the two checks that compile against the tree as it was before the batch (they name nothing the batch added), so that
 * "fails before" is an assertion that fails there:
 *  - **App colours is above Theme**, and Theme above the accent, in Theme and accent;
 *  - B42-fix3: the cover modes have no Theme row; App colours is above the fallback accent, and the stored Navy is not offered there.
 * The page's actions are a recording proxy, so nothing here depends on the interface's newer methods. The rest of B1 is in
 * `B42Fix3AppearanceThemeRowTest` and `CoverThemeChoiceTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h2400dp-xxhdpi")
class B41Fix1AppearanceProbeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)

    private val actions: AppearanceSettingsActions = Proxy.newProxyInstance(
        AppearanceSettingsActions::class.java.classLoader,
        arrayOf(AppearanceSettingsActions::class.java),
    ) { _, _, _ -> null } as AppearanceSettingsActions

    private fun show(source: String) {
        val state = AppearanceSettingsState.from(
            SettingsUiState(appColorSource = source, baseTheme = DapBaseTheme.Navy, appThemeMode = "dark", accent = DapAccent.Amber),
        )
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                    AppearanceSettingsScreen(state, actions)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun topOf(text: String): Float = compose.onAllNodesWithText(text)[0].fetchSemanticsNode().boundsInRoot.top

    private fun assertOrder(source: String, accentLabel: String) {
        show(source)
        val colours = topOf(s(R.string.setcat_app_colors_label))
        val theme = topOf(s(R.string.setcat_theme_label))
        val accent = topOf(accentLabel)
        assertTrue("$source: App colours ($colours) is not above Theme ($theme)", colours < theme)
        assertTrue("$source: Theme ($theme) is not above the accent ($accent)", theme < accent)
    }

    @Test
    fun app_colours_comes_first_then_theme_then_accent_with_theme_and_accent() = assertOrder(AppColorSource.THEME, s(R.string.setcat_accent_label))

    private fun assertCoverOrder(source: String) {
        show(source)
        compose.onAllNodesWithText(s(R.string.setcat_theme_label)).assertCountEquals(0)
        assertTrue(topOf(s(R.string.setcat_app_colors_label)) < topOf(s(R.string.setcat_accent_desc_album_art)))
    }

    @Test
    fun app_colours_comes_before_the_fallback_accent_without_theme_from_album_art() = assertCoverOrder(AppColorSource.ALBUM_ART)

    @Test
    fun app_colours_comes_before_the_fallback_accent_without_theme_with_cover_glass() = assertCoverOrder(AppColorSource.COVER_GLASS)

    @Test
    fun the_theme_row_of_a_cover_mode_does_not_show_the_explicit_theme() {
        show(AppColorSource.COVER_GLASS)
        compose.onAllNodesWithText(s(R.string.setcat_theme_option_navy)).assertCountEquals(0)
    }

    @Test
    fun with_theme_and_accent_the_theme_row_shows_the_explicit_theme() {
        show(AppColorSource.THEME)
        compose.onAllNodesWithText(s(R.string.setcat_theme_option_navy)).assertCountEquals(1)
    }
}
