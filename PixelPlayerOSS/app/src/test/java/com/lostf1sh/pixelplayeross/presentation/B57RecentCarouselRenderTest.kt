package com.lostf1sh.pixelplayeross.presentation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.presentation.components.RecentlyPlayedSection
import com.lostf1sh.pixelplayeross.presentation.model.RecentlyPlayedSongUiModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import kotlinx.collections.immutable.toImmutableList
import androidx.compose.foundation.background
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B57 3.1: the Recently played carousel, light and dark (made-up songs; B55's grid renders are `B55-gates/renders`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h300dp-xhdpi")
class B57RecentCarouselRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun render(name: String, dark: Boolean) {
        val items = (1L..20).map { RecentlyPlayedSongUiModel(Kit.song(it, title = "Synthetic song number $it"), 1000L - it) }.toImmutableList()
        compose.setContent {
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) {
                Box(Modifier.fillMaxWidth().height(260.dp).background(Dap.background).padding(top = 12.dp).testTag("section")) {
                    RecentlyPlayedSection(items, {}, {}, currentSongId = "2")
                }
            }
        }
        compose.waitForIdle()
        B57Renders.save(compose, name)
    }

    @Test fun carousel_dark() = render("home-recent-carousel-dark", dark = true)
    @Test fun carousel_light() = render("home-recent-carousel-light", dark = false)
}
