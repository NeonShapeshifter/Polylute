package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.musicinfo.Description
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicInfoAboutInteractionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `text expands and collapses and credits never launch an external activity`() {
        var externalCalls = 0
        val text = (1..70).joinToString(" ") { "Description$it" }
        compose.setContent {
            DapTheme {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { externalCalls++; error("No browser installed") }
                }) { MusicInfoAbout(MusicInfoAboutState(Description(text, "Artist", "https://en.wikipedia.org/wiki/Artist", "en")), mountInputBridge = false) }
            }
        }
        fun height() = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.height
        val collapsed = height()
        compose.onNodeWithText(text).performClick()
        val expanded = height()
        assertThat(expanded).isGreaterThan(collapsed)
        compose.onNodeWithText(text).performClick()
        assertThat(height()).isWithin(1f).of(collapsed)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(R.string.music_info_from_wikipedia)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_show_metadata_info)).performClick()
        compose.onNodeWithText("https://en.wikipedia.org/wiki/Artist").assertExists()
        assertThat(externalCalls).isEqualTo(0)
    }
}
