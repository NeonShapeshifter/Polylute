package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.library.LibraryFolders
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FolderProposalState
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** B39-A9: the proposal is every folder checked, one tap from done; any can be unchecked first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class FolderProposalDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = MutableStateFlow(
        FolderProposalState(
            listOf(
                LibraryFolders.Proposal("/storage/emulated/0/Music/DAP music", 21),
                LibraryFolders.Proposal("/storage/emulated/0/Download/Albums", 1),
            ),
        ),
    )
    private var added = 0
    private var dismissed = 0

    /** The dialog is a window of its own and mounts the modal input bridge, which asks Hilt for the volume authority; Hilt answers with doubles. */
    @Before
    fun hiltAnswers() {
        val repository = mockk<UserPreferencesRepository>(relaxed = true)
        every { repository.backNavigationModeFlow } returns flowOf(BackNavigationMode.DEFAULT)
        val entryPoint = object : ModalInputEntryPoint {
            override fun safetyVolumeController(): SafetyVolumeController = mockk(relaxed = true)
            override fun userPreferencesRepository(): UserPreferencesRepository = repository
        }
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(ApplicationProvider.getApplicationContext<android.content.Context>(), ModalInputEntryPoint::class.java)
        } returns entryPoint
    }

    @After
    fun hiltIsBack() = unmockkStatic(EntryPointAccessors::class)

    private fun show() {
        compose.setContent {
            val current = state.collectAsState().value
            FolderProposalDialog(
                state = current,
                onToggle = { path -> state.value = state.value.toggle(path) },
                onAdd = { added++ },
                onDismiss = { dismissed++ },
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `every folder comes checked and one tap adds them all`() {
        show()
        compose.onNodeWithText("Folders with music").assertExists()
        compose.onNodeWithText("DAP music").assertExists()
        compose.onNodeWithText("21 songs").assertExists()
        compose.onNodeWithText("1 song").assertExists()
        val boxes = compose.onAllNodes(isToggleable())
        assertThat(boxes.fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithText("Add 22 songs").assertIsEnabled().performClick()
        assertThat(added).isEqualTo(1)
        assertThat(state.value.chosen).hasSize(2)
    }

    @Test
    fun `unchecking a folder takes its songs off the button and unchecking all disables it`() {
        show()
        compose.onNodeWithText("Albums").performClick()
        compose.onNodeWithText("Add 21 songs").assertIsEnabled()
        assertThat(state.value.chosen).containsExactly("/storage/emulated/0/Music/DAP music")
        compose.onNodeWithText("DAP music").performClick()
        compose.onNodeWithText("Add").assertIsNotEnabled()
        assertThat(state.value.chosen).isEmpty()
    }

    @Test
    fun `not now closes it and adds nothing`() {
        show()
        compose.onNodeWithText("Not now").performClick()
        assertThat(dismissed).isEqualTo(1)
        assertThat(added).isEqualTo(0)
    }

    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
    fun `the dialog speaks Spanish`() {
        show()
        compose.onNodeWithText("Carpetas con música").assertExists()
        compose.onNodeWithText("21 canciones").assertExists()
        compose.onNodeWithText("1 canción").assertExists()
        compose.onNodeWithText("Añadir 22 canciones").assertExists()
        compose.onNodeWithText("Ahora no").assertExists()
    }
}
