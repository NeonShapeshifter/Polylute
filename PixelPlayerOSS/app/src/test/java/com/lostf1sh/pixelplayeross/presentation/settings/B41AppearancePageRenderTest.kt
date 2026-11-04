package com.lostf1sh.pixelplayeross.presentation.settings

import com.lostf1sh.pixelplayeross.data.preferences.LyricsMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import com.lostf1sh.pixelplayeross.ui.theme.DapAppearanceChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B41: the new Appearance page, the whole of it, in English, Spanish and Swedish: Theme (with "Cover glass" chosen), Player (Moving
 * background and Lyrics under the cover), Lyrics (what Settings, Lyrics held besides the sources), Lock screen, and what was there before.
 * The PNGs go to `B41-evidence/` as `appearance-<language>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h3000dp-xhdpi")
open class B41AppearancePageRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val language get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources.configuration.locales[0].language

    private val actions = object : AppearanceSettingsActions {
        override fun onAppLanguageChange(value: String) {}
        override fun onAccentChange(value: DapAccent) {}
        override fun onAppColorSourceChange(value: String) {}
        override fun onCoverThemeChoiceChange(value: String) {}
        override fun onPlayerThemePreferenceChange(value: String) {}
        override fun onShowBookmarkButtonChange(value: Boolean) {}
        override fun onShowPlayerFileInfoChange(value: Boolean) {}
        override fun onAlbumArtQualityChange(value: AlbumArtQuality) {}
        override fun onLaunchTabChange(value: String) {}
        override fun onLibraryNavigationModeChange(value: String) {}
        override fun onThemeChoiceChange(choice: DapAppearanceChoice) {}
        override fun onOpenPaletteStyle() {}
        override fun onCoverGlassMovingChange(value: Boolean) {}
        override fun onLyricsModeChange(value: LyricsMode) {}
        override fun onImmersiveLyricsEnabledChange(value: Boolean) {}
        override fun onImmersiveLyricsTimeoutChange(value: Long) {}
        override fun onLyricsAlignmentChange(value: String) {}
        override fun onShowLyricsTranslationChange(value: Boolean) {}
        override fun onShowLyricsRomanizationChange(value: Boolean) {}
        override fun onUseAnimatedLyricsChange(value: Boolean) {}
        override fun onAnimatedLyricsBlurEnabledChange(value: Boolean) {}
        override fun onAnimatedLyricsBlurStrengthChange(value: Float) {}
        override fun onDapLockScreenEnabledChange(value: Boolean) {}
    }

    @Test
    fun `the new Appearance page, with Cover glass chosen`() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                Column(
                    Modifier.fillMaxSize().background(Dap.background).padding(start = 16.dp, end = 16.dp, top = 40.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    DapScreenTitle(text = androidx.compose.ui.res.stringResource(com.lostf1sh.pixelplayeross.R.string.settings_category_appearance_title))
                    AppearanceSettingsScreen(
                        AppearanceSettingsState.from(SettingsUiState(appColorSource = AppColorSource.COVER_GLASS, appLanguageTag = language)),
                        actions,
                    )
                }
            }
        }
        compose.waitForIdle()
        B41Renders.save(compose, "appearance-$language")
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h3000dp-xhdpi")
class B41AppearancePageRenderSpanishTest : B41AppearancePageRenderTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h3000dp-xhdpi")
class B41AppearancePageRenderSwedishTest : B41AppearancePageRenderTest()
