package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.LibraryTabId
import com.lostf1sh.pixelplayeross.data.model.StorageFilter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** B39-A9: an empty library offers the way back, "Find folders with music", when the shell provides it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class LibraryEmptyFindFoldersTest {
    @get:Rule
    val compose = createComposeRule()

    private var asked = 0

    @Test
    fun `the empty songs tab offers to find folders and the button asks for the search`() {
        compose.setContent {
            CompositionLocalProvider(LocalFindFolders provides { asked++ }) {
                LibraryExpressiveEmptyState(LibraryTabId.SONGS, StorageFilter.ALL, bottomBarHeight = 0.dp)
            }
        }
        compose.onNodeWithText("Nothing is in the library yet.", substring = true).assertExists()
        compose.onNodeWithText("Find folders with music").performClick()
        assertThat(asked).isEqualTo(1)
    }

    @Test
    fun `without the shell's action there is no button and the other tabs never have one`() {
        compose.setContent {
            LibraryExpressiveEmptyState(LibraryTabId.SONGS, StorageFilter.ALL, bottomBarHeight = 0.dp)
        }
        compose.onNodeWithText("Find folders with music").assertDoesNotExist()
    }

    @Test
    fun `the playlists tab has no such button`() {
        compose.setContent {
            CompositionLocalProvider(LocalFindFolders provides { asked++ }) {
                LibraryExpressiveEmptyState(LibraryTabId.PLAYLISTS, StorageFilter.ALL, bottomBarHeight = 0.dp)
            }
        }
        compose.onNodeWithText("Find folders with music").assertDoesNotExist()
    }
}
