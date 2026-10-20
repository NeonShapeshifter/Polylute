package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.presentation.B32Renders
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
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
 * B32-Q7: the Lyrics page of Settings, as a listener who had immersive lyrics on sees it. The same source runs against the tree as it
 * was at the start of the batch (`B32_PREFIX=before`: the switch and its delay are on the page) and as it is now (`after`: neither).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h1700dp-xhdpi")
open class B32LyricsSettingsRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val language get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources.configuration.locales[0].language

    private val actions = object : LyricsSettingsActions {
        override fun onExternalLyricsEnabledChange(value: Boolean) {}
        override fun onLyricsSourcePreferenceChange(value: LyricsSourcePreference) {}
        override fun onAutoScanLrcFilesChange(value: Boolean) {}
        override fun onImmersiveLyricsEnabledChange(value: Boolean) {}
        override fun onImmersiveLyricsTimeoutChange(value: Long) {}
        override fun onLyricsAlignmentChange(value: String) {}
        override fun onShowLyricsTranslationChange(value: Boolean) {}
        override fun onShowLyricsRomanizationChange(value: Boolean) {}
        override fun onUseAnimatedLyricsChange(value: Boolean) {}
        override fun onAnimatedLyricsBlurEnabledChange(value: Boolean) {}
        override fun onAnimatedLyricsBlurStrengthChange(value: Float) {}
    }

    @Test
    fun `the Lyrics page of a listener who had immersive lyrics on`() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                Column(Modifier.fillMaxSize().background(Dap.background).padding(horizontal = 16.dp)) {
                    LyricsSettingsScreen(LyricsSettingsState.from(SettingsUiState(immersiveLyricsEnabled = true)), actions)
                }
            }
        }
        B32Renders.save(compose, "q7-lyrics-settings-$language")
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h1700dp-xhdpi")
class B32LyricsSettingsRenderSpanishTest : B32LyricsSettingsRenderTest()

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h1700dp-xhdpi")
class B32LyricsSettingsRenderSwedishTest : B32LyricsSettingsRenderTest()
