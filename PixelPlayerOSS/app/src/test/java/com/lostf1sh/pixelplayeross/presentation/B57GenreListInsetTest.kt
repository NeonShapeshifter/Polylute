package com.lostf1sh.pixelplayeross.presentation

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreListRow
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GENRE_ART_LETTER_TAG
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

/** The Genres list aligns all three thumbnails with the same gutter. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h300dp-xhdpi")
class B57GenreListInsetTest {
    @get:Rule val compose = createComposeRule()

    private fun render(name: String, dark: Boolean) {
        compose.setContent {
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) {
                Column(Modifier.fillMaxWidth().background(Dap.background).padding(16.dp).testTag("rows")) {
                    listOf("Rock", "Pop", "Hip Hop").forEach { GenreListRow(Genre(id = it, name = it, songCount = 12), null) {} }
                }
            }
        }
        compose.waitForIdle()
        B57Renders.save(compose, name)
    }

    @Test fun list_rows_dark() = render("genres-list-dark", dark = true)
    @Test fun list_rows_light() = render("genres-list-light", dark = false)

    @Test fun the_large_thumbnails_share_the_content_gutter() {
        render("genres-list-gutter", dark = true)
        val left = compose.onNodeWithTag("rows").fetchSemanticsNode().boundsInRoot.left
        val thumbnails = compose.onAllNodesWithTag(GENRE_ART_LETTER_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        assertThat(thumbnails).hasSize(3)
        thumbnails.forEach { assertThat(it.boundsInRoot.center.x).isWithin(1f).of(left + 68f) }
    }
}
