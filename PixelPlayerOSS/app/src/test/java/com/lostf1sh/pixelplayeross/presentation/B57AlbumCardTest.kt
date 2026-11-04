package com.lostf1sh.pixelplayeross.presentation

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapAlbumGridItem
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.utils.AlbumInfoLine
import com.lostf1sh.pixelplayeross.utils.albumInfoLine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B57 4.3: an album card is three lines (name, artist, "year · N songs · duration"); a missing year is left out (the count word is the app's own plural, `song_count`); the length is the songs' sum, no file is read. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h260dp-xhdpi")
class B57AlbumCardTest {
    @get:Rule val compose = createComposeRule()
    private val minute = 60_000L

    @Test fun `minutes round to the nearest and an album is never zero minutes`() {
        assertThat(AlbumInfoLine.totalMinutes(38 * minute + 20_000)).isEqualTo(38)
        assertThat(AlbumInfoLine.totalMinutes(38 * minute + 40_000)).isEqualTo(39)
        assertThat(AlbumInfoLine.totalMinutes(10_000)).isEqualTo(1)
        assertThat(AlbumInfoLine.totalMinutes(0)).isEqualTo(0)
        assertThat(AlbumInfoLine.yearText(2019)).isEqualTo("2019")
        assertThat(AlbumInfoLine.yearText(0)).isNull()
        assertThat(AlbumInfoLine.join("2019", null, "38 min")).isEqualTo("2019 · 38 min")
    }

    @Test fun `the data line reads year, songs and minutes, hours when long, and drops what is missing`() {
        val lines = mutableListOf<String>()
        compose.setContent {
            DapTheme {
                lines += albumInfoLine(2019, 9, 38 * minute)
                lines += albumInfoLine(2019, 14, 72 * minute)
                lines += albumInfoLine(0, 9, 38 * minute)
                lines += albumInfoLine(2019, 1, 0)
            }
        }
        compose.waitForIdle()
        assertThat(lines.take(4)).containsExactly("2019 · 9 Songs · 38 min", "2019 · 14 Songs · 1 h 12 min", "9 Songs · 38 min", "2019 · 1 Song").inOrder()
    }

    private fun render(name: String, dark: Boolean) {
        compose.setContent {
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) {
                Row(Modifier.fillMaxWidth().background(Dap.background).padding(16.dp)) {
                    DapAlbumGridItem("Noche (Deluxe)", "Gemini Sound", Modifier.width(150.dp), detail = albumInfoLine(2019, 9, 38 * minute))
                    androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
                    DapAlbumGridItem("Sin año", "Gemini Sound", Modifier.width(150.dp), detail = albumInfoLine(0, 14, 72 * minute))
                }
            }
        }
        compose.waitForIdle()
        B57Renders.save(compose, name)
    }

    @Test fun cards_dark() = render("album-cards-dark", dark = true)
    @Test fun cards_light() = render("album-cards-light", dark = false)
}
