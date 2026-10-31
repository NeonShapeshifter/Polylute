package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.presentation.screens.search.components.GenreCategoriesGrid
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.glass.*
import io.mockk.*
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B44GenreRowsTest {
    @get:Rule val compose = createComposeRule()
    private val grid = MutableStateFlow(false)
    private val mode = mutableStateOf(Fix3Mode.FROM_ALBUM_ART)
    private val vm = mockk<PlayerViewModel>(relaxed = true)
    private val clicks = mutableListOf<String>()
    private fun genre(id: String): Genre = Genre(
        id = id,
        name = id,
        darkColorHex = "#654321",
        lightColorHex = "#ffcc99",
        songCount = 12,
    )
    private fun show() {
        every { vm.isGenreGridView } returns grid
        every { vm.navBarCompactMode } returns MutableStateFlow(false)
        every { vm.customGenreIcons } returns MutableStateFlow(emptyMap())
        every { vm.toggleGenreViewMode() } answers { grid.value = !grid.value }
        val genres = persistentListOf(genre("Latin America"), genre("Unknown"), genre("Rock"))
        compose.setContent {
            Fix3Stage(B42Fix3Covers.all[3], mode.value).App {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    GenreCategoriesGrid(genres, { clicks += it.id }, vm)
                }
            }
        }
        compose.waitForIdle()
    }
    private fun save(name: String) {
        val bitmap = B42Renders.screen(compose)
        val root = File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B44-evidence", System.getenv("B44_VARIANT") ?: "after")
        root.mkdirs()
        File(root, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun `both genre modes follow the shared switch and have before and after glass renders`() {
        show()
        for (theme in listOf(Fix3Mode.FROM_ALBUM_ART, Fix3Mode.COVER_GLASS)) {
            compose.runOnIdle { mode.value = theme }
            for (isGrid in listOf(false, true)) {
                compose.runOnIdle { grid.value = isGrid }
                compose.waitForIdle()
                save("genres-${theme.label}-${if (isGrid) "grid" else "list"}.png")
            }
        }
        compose.runOnIdle { grid.value = false }
        compose.onNodeWithText("Latin America").performClick()
        assertThat(clicks).containsExactly("Latin America")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val count = context.resources.getQuantityString(com.lostf1sh.pixelplayeross.R.plurals.genre_count_songs, 12, 12)
        compose.onAllNodesWithText(count).assertCountEquals(3)
    }
}
