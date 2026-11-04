package com.lostf1sh.pixelplayeross.presentation

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreListRow
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreListRowInset
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B57 4.4: Search > genres in list view: the letter tile no longer sits on the card's rounded left edge; it has the grid tile's 8 dp inner margin. */
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

    @Test fun the_inset_is_the_grid_tiles_and_the_row_applies_it_inside_the_clip() {
        assertThat(GenreListRowInset).isEqualTo(8.dp)
        val source = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/search/components/GenreCategoriesGrid.kt").readText()
        // the tile's own padding(8.dp) and the row's inset are the same number, and the row's padding comes after clip + background
        assertThat(source).contains(".clickable(onClick = onClick).padding(8.dp)")
        assertThat(source).contains(".clip(RoundedCornerShape(DapRadius.Surface)).background(frostedPanelOr(Dap.surfaceRaised)).padding(GenreListRowInset)")
    }
}
