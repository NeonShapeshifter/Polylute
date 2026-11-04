package com.lostf1sh.pixelplayeross.presentation.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SongInfoBottomSheetViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.glass.B42HiltShim
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Menu body and edit dialog. Current song is supplied as the new FullPlayerContent Menu supplies it. All writes are recorded, never executed. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B42Fix2MenuEditTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val bridge = B42HiltShim()
    private val first = Song(id = "11", title = "Song A", artist = "Artist A", artistId = 7L, album = "Album A", albumId = 9L,
        path = "", contentUriString = "", albumArtUriString = null, duration = 245_000L, mimeType = "audio/flac", bitrate = 1000, sampleRate = 44100)
    private val second = first.copy(id = "12", title = "Song B", artist = "Artist B", album = "Album B")
    private var current by mutableStateOf(first)
    private val saved = mutableListOf<Pair<String, String>>()
    private val vm = mockk<SongInfoBottomSheetViewModel>(relaxed = true).also {
        every { it.audioMeta } returns MutableStateFlow(null)
        every { it.resolvedArtists } returns MutableStateFlow(persistentListOf())
        every { it.musicBrainzState } returns MutableStateFlow(SongInfoBottomSheetViewModel.MusicBrainzUiState.Idle)
        every { it.getSongLocationInfo(any()) } returns SongInfoBottomSheetViewModel.SongLocationInfo("Folder", "Music")
        every { it.isSongEditable(any()) } returns true
    }
    private fun s(id: Int) = compose.activity.getString(id)

    private fun showEditorAndType() {
        compose.setContent {
            val song = current
            DapTheme(darkTheme = true, baseTheme = DapBaseTheme.Graphite) {
                CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) {
                    Box(Modifier.fillMaxSize()) {
                        SongInfoBottomSheet(
                            song = song, isFavorite = false, onToggleFavorite = {}, onDismiss = {}, onPlaySong = {}, onAddToQueue = {}, onAddNextToQueue = {},
                            onAddToPlayList = {}, onDeleteFromDevice = { _, _, _ -> }, onNavigateToAlbum = {}, onNavigateToArtist = {}, onNavigateToGenre = {},
                            onViewTrackDetails = {}, onViewCredits = {},
                            onEditSong = { title, _, _, _, _, _, _, _, _, _, _, _, _ -> saved += song.id to title },
                            removeFromListTrigger = {}, songInfoViewModel = vm, presentation = SongOptionsPresentation.PAGE,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.edit_song_toolbar_title)).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)[0].performTextReplacement("Unsaved A")
        assertThat(editorValues().first()).isEqualTo("Unsaved A")
    }

    private fun editorValues(): List<String> = compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }

    @Test
    fun advancing_playback_must_not_replace_an_open_edit_with_another_songs_fields() {
        showEditorAndType()
        compose.runOnIdle { current = second }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        val fields = editorValues()
        assertThat(fields.first()).isEqualTo("Unsaved A")
        compose.onNodeWithText(s(R.string.action_save)).performClick()
        assertThat(saved).containsExactly(first.id to "Unsaved A")
    }

    @Test
    fun positive_control_without_a_track_change_the_edit_is_saved_for_its_song() {
        showEditorAndType()
        compose.onNodeWithText(s(R.string.action_save)).performClick()
        assertThat(saved).containsExactly(first.id to "Unsaved A")
    }

    @Test
    fun metadata_updates_do_not_replace_unsaved_fields() {
        showEditorAndType()
        compose.runOnIdle { current = first.copy(title = "Updated library title") }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertThat(editorValues().first()).isEqualTo("Unsaved A")
        compose.onNodeWithText(s(R.string.action_save)).performClick()
        assertThat(saved).containsExactly(first.id to "Unsaved A")
    }

    @Test
    fun a_later_edit_uses_the_new_current_song() {
        showEditorAndType()
        compose.runOnIdle { current = second }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.action_save)).performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.edit_song_toolbar_title)).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertThat(editorValues().first()).isEqualTo(second.title)
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)[0].performTextReplacement("Edited B")
        compose.onNodeWithText(s(R.string.action_save)).performClick()
        assertThat(saved).containsExactly(first.id to "Unsaved A", second.id to "Edited B").inOrder()
    }

}
