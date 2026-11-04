package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreCategoriesGrid
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B56 phase 5.2: the Genres tab ends with the genres that have fewer than three songs, under one header "Other"; they are not hidden. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B56GenresTabOtherTest {
    @get:Rule val compose = createComposeRule()
    private val out: File get() = File(System.getenv("B56_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B56-gates/renders").apply { mkdirs() }

    private val genres = persistentListOf(
        Genre("jazz", "Jazz", songCount = 40), Genre("pop", "Pop", songCount = 120), Genre("rock", "Rock", songCount = 300),
        Genre("polka", "Polka", songCount = 2), Genre("zydeco", "Zydeco", songCount = 1), Genre("unknown", "Unknown", songCount = 2),
    )

    private fun show(dark: Boolean, grid: Boolean) {
        val player = mockk<PlayerViewModel>(relaxed = true)
        every { player.isGenreGridView } returns MutableStateFlow(grid)
        every { player.navBarCompactMode } returns MutableStateFlow(false)
        every { player.customGenreIcons } returns MutableStateFlow(emptyMap())
        compose.setContent {
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light) {
                Box(Modifier.fillMaxSize().background(Dap.background).padding(top = 40.dp)) {
                    GenreCategoriesGrid(genres, {}, player, bottomContentPadding = 24.dp)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the small genres follow the others under one Other header`() {
        show(dark = true, grid = false)

        val y = { text: String -> compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        assertThat(listOf(y("Jazz"), y("Pop"), y("Rock"), y("Other"), y("Polka"), y("Zydeco"), y("Unknown"))).isInOrder()
        compose.onNodeWithText("Other").assertExists()
    }

    @Test
    fun `in the grid view too`() {
        show(dark = true, grid = true)

        val y = { text: String -> compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        assertThat(y("Other")).isGreaterThan(y("Rock"))
        assertThat(y("Polka")).isGreaterThan(y("Other"))
    }

    private fun render(name: String, dark: Boolean, grid: Boolean) {
        show(dark, grid)
        val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `render the Genres tab in the dark theme`() = render("genres-tab-other-dark", dark = true, grid = false)

    @Test fun `render the Genres tab in the light theme`() = render("genres-tab-other-light", dark = false, grid = false)
}
