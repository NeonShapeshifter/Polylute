package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.presentation.model.methodLabelRes
import com.lostf1sh.pixelplayeross.presentation.screens.DapLibraryActionRow
import com.lostf1sh.pixelplayeross.ui.ds.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h800dp-xhdpi")
open class B625LibraryUiTest {
    @get:Rule val compose = createComposeRule()
    protected open val lang = "en"
    private fun save(name: String) {
        val file = File((System.getenv("B625_UI_RENDERS") ?: "build/tmp/b625-ui-renders"), "$name-$lang.png").apply { parentFile.mkdirs() }
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun `row opens edits and clears inline search`() {
        var open by mutableStateOf(false)
        var query by mutableStateOf("")
        compose.setContent {
            DapTheme { Column(Modifier.fillMaxSize().background(Dap.background)) {
                DapLibraryActionRow({}, true, SortOption.SongTitleAZ, false, false,
                    searchOpen = open, searchQuery = query, onSearchOpen = { open = true }, onSearchQuery = { query = it }, onSearchClose = { open = false; query = "" })
            } }
        }
        save("library-row-closed")
        compose.onNodeWithTag("library-search-open").performClick()
        compose.onNodeWithTag("library-search-field").assertExists()
        save("library-row-open")
        compose.onNodeWithTag("library-search-field").performTextInput("Beyoncé")
        assertThat(query).isEqualTo("Beyoncé")
        compose.onNodeWithTag("library-search-close").performClick()
        compose.onNodeWithTag("library-search-field").assertDoesNotExist()
        assertThat(query).isEmpty()
    }
    @Test fun `a method without direction keeps the original order label`() {
        compose.setContent { DapTheme { Column(Modifier.fillMaxSize().background(Dap.background)) {
            LibrarySortChoices(SortOption.SONGS, SortOption.SongDefaultOrder, {}, {})
        } } }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(com.lostf1sh.pixelplayeross.R.string.presentation_batch_g_sort_original_order)).assertExists()
        compose.onNodeWithText(context.getString(com.lostf1sh.pixelplayeross.R.string.presentation_batch_g_sort_order)).assertExists()
    }
    @Test fun `all sort methods and directions preserve their callbacks`() {
        val sets = listOf(SortOption.SONGS, SortOption.ALBUMS, SortOption.ARTISTS, SortOption.PLAYLISTS, SortOption.FOLDERS, SortOption.LIKED)
        var options by mutableStateOf(sets.first())
        var selected by mutableStateOf<SortOption>(SortOption.SongTitleAZ)
        var chosen: SortOption? = null
        var direction: SortOption? = null
        compose.setContent { DapTheme { Column(Modifier.fillMaxSize().background(Dap.background).verticalScroll(rememberScrollState())) {
            LibrarySortChoices(options, selected, { chosen = it }, { direction = it })
        } } }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        for (set in sets) for (option in set) {
            // Exercise both directions, including methods whose natural direction is descending.
            compose.runOnIdle { options = set; selected = option }
            compose.onNodeWithText(context.getString(option.methodLabelRes())).performScrollTo().performClick()
            assertThat(chosen?.storageKey).isEqualTo(option.storageKey)
            if (option.canFlipDirection) {
                // B63: the direction is a two-way picker with fixed words; tap the other one.
                val other = if (option.direction == com.lostf1sh.pixelplayeross.data.model.SortDirection.Descending) com.lostf1sh.pixelplayeross.R.string.presentation_batch_g_sort_ascending
                    else com.lostf1sh.pixelplayeross.R.string.presentation_batch_g_sort_descending
                compose.onNodeWithText(context.getString(other)).performScrollTo().performClick()
                assertThat(direction?.storageKey).isEqualTo(option.flipDirection().storageKey)
            }
        }
    }
}

@Config(sdk = [35], application = Application::class, qualifiers = "es-w400dp-h800dp-xhdpi")
class B625LibraryUiSpanishTest : B625LibraryUiTest() { override val lang = "es" }
