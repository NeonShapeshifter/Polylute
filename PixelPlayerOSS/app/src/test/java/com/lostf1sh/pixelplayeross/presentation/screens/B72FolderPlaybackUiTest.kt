package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.library.folderDiscSongsInOrder
import com.lostf1sh.pixelplayeross.data.library.folderSongsInOrder
import com.lostf1sh.pixelplayeross.data.model.LibraryTabId
import com.lostf1sh.pixelplayeross.data.model.MusicFolder
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class B72FolderPlaybackUiTest {
    @get:Rule val compose = createComposeRule()

    @Before fun provideModalAuthority() {
        val prefs = mockk<UserPreferencesRepository>()
        every { prefs.backNavigationModeFlow } returns flowOf(BackNavigationMode.DEFAULT)
        val point = object : ModalInputEntryPoint {
            override fun safetyVolumeController() = mockk<SafetyVolumeController>(relaxed = true)
            override fun userPreferencesRepository() = prefs
        }
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(
                ApplicationProvider.getApplicationContext<Context>(),
                ModalInputEntryPoint::class.java,
            )
        } returns point
    }

    @After fun restoreModalAuthority() = unmockkStatic(EntryPointAccessors::class)

    private fun sampleTree(): MusicFolder {
        val sub = MusicFolder(
            path = "/storage/emulated/0/Music/Root/Sub",
            name = "Sub",
            songs = listOf(Kit.song(2, title = "Sub Song")).toImmutableList(),
        )
        return MusicFolder(
            path = "/storage/emulated/0/Music/Root",
            name = "Root",
            songs = listOf(Kit.song(1, title = "Root Song")).toImmutableList(),
            subFolders = listOf(sub).toImmutableList(),
        )
    }

    @Test
    fun `floating Play and Shuffle discs are hidden at folder root and shown inside a folder`() {
        val atRoot = libraryFolderDiscsState(LibraryTabId.FOLDERS, currentFolder = null, folderHasSongs = false)
        assertThat(atRoot.showPlay).isFalse()
        assertThat(atRoot.showShuffle).isFalse()

        val folder = sampleTree()
        val insideFolder = libraryFolderDiscsState(LibraryTabId.FOLDERS, currentFolder = folder, folderHasSongs = true)
        assertThat(insideFolder.showPlay).isTrue()
        assertThat(insideFolder.showShuffle).isTrue()
        assertThat(insideFolder.enabled).isTrue()

        var playedOrder = emptyList<String>()
        var shuffledOrder = emptyList<String>()
        compose.setContent {
            DapTheme {
                LibraryFloatingActions(
                    showPlay = insideFolder.showPlay,
                    onPlayClick = {
                        playedOrder = folderDiscSongsInOrder(folder, SortOption.FolderNameAZ).map { it.title }
                    },
                    showShuffle = insideFolder.showShuffle,
                    onShuffleClick = {
                        shuffledOrder = folderDiscSongsInOrder(folder, SortOption.FolderNameAZ).map { it.title }
                    },
                    showLocate = false,
                    onLocateClick = {},
                    playEnabled = insideFolder.enabled,
                    shuffleEnabled = insideFolder.enabled,
                )
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithContentDescription(context.getString(R.string.play_playback))
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_shuffle_play))
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertThat(playedOrder).containsExactly("Root Song").inOrder()
        assertThat(shuffledOrder).containsExactly("Root Song").inOrder()
    }

    @Test
    fun `floating discs are disabled for an empty folder tree`() {
        val empty = MusicFolder(path = "/storage/emulated/0/Music/Empty", name = "Empty")
        val state = libraryFolderDiscsState(LibraryTabId.FOLDERS, currentFolder = empty, folderHasSongs = false)
        assertThat(state.showPlay).isTrue()
        assertThat(state.showShuffle).isTrue()
        assertThat(state.enabled).isFalse()

        compose.setContent {
            DapTheme {
                LibraryFloatingActions(
                    showPlay = state.showPlay,
                    onPlayClick = {},
                    showShuffle = state.showShuffle,
                    onShuffleClick = {},
                    showLocate = false,
                    onLocateClick = {},
                    playEnabled = state.enabled,
                    shuffleEnabled = state.enabled,
                )
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithContentDescription(context.getString(R.string.play_playback)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_shuffle_play)).assertIsNotEnabled()
    }

    @Test
    fun `FolderActionsSheet shows Play, Shuffle, Play next, Add to queue above Open and Actions and invokes callbacks`() {
        val folder = sampleTree()
        val called = mutableListOf<String>()
        compose.setContent {
            DapTheme {
                FolderActionsSheet(
                    folder = folder,
                    onPlay = { called += "play:${folderSongsInOrder(folder, SortOption.FolderNameAZ).map { it.id }}" },
                    onShuffle = { called += "shuffle:${folderSongsInOrder(folder, SortOption.FolderNameAZ).map { it.id }}" },
                    onPlayNext = { called += "next:${folderSongsInOrder(folder, SortOption.FolderNameAZ).map { it.id }}" },
                    onAddToQueue = { called += "queue:${folderSongsInOrder(folder, SortOption.FolderNameAZ).map { it.id }}" },
                    onOpen = { called += "open" },
                    onActions = { called += "actions" },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Play").performClick()
        compose.onNodeWithText("Shuffle").performClick()
        compose.onNodeWithText("Play next").performClick()
        compose.onNodeWithText("Add to queue").performClick()
        compose.onNodeWithText("Open folder").performClick()
        compose.onNodeWithText("Folder actions").performClick()

        assertThat(called).containsExactly(
            "play:[2, 1]",
            "shuffle:[2, 1]",
            "next:[2, 1]",
            "queue:[2, 1]",
            "open",
            "actions",
        ).inOrder()
    }

    @Test
    fun `FolderActionsSheet disables playback and queue actions for empty folder tree`() {
        val empty = MusicFolder(path = "/storage/emulated/0/Music/Empty", name = "Empty")
        var tapped = 0
        compose.setContent {
            DapTheme {
                FolderActionsSheet(
                    folder = empty,
                    onPlay = { tapped++ },
                    onShuffle = { tapped++ },
                    onPlayNext = { tapped++ },
                    onAddToQueue = { tapped++ },
                    onOpen = {},
                    onActions = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Play").performClick()
        compose.onNodeWithText("Shuffle").performClick()
        compose.onNodeWithText("Play next").performClick()
        compose.onNodeWithText("Add to queue").performClick()
        assertThat(tapped).isEqualTo(0)
    }

    @Test @Config(qualifiers = "es")
    fun `FolderActionsSheet strings in Spanish`() {
        compose.setContent {
            DapTheme {
                FolderActionsSheet(
                    folder = sampleTree(),
                    onOpen = {},
                    onActions = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Reproducir").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Aleatorio").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reproducir a continuación").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Añadir a la cola").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Abrir carpeta").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Acciones de la carpeta").performScrollTo().assertIsDisplayed()
    }

    @Test @Config(qualifiers = "sv")
    fun `FolderActionsSheet strings in Swedish`() {
        compose.setContent {
            DapTheme {
                FolderActionsSheet(
                    folder = sampleTree(),
                    onOpen = {},
                    onActions = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Spela").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Blanda").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Spela härnäst").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lägg till i kön").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Öppna mapp").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mappåtgärder").performScrollTo().assertIsDisplayed()
    }
}
