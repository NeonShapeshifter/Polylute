package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.B29Renders
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B29-L7 (N-05), the rows (B33-U1: the handle's whole 48 dp slot, a 12 dp gap to the artwork): the reorder handle at the far left (its glyph on the content margin, its touch area around it), the
 * song's title and artist after it and aligned to each other, the same place on the current row, whose handle is off.
 *
 * Measured on the real [QueuePlaylistSongItem] at 360 dp (xxhdpi: 3 px per dp), with the handle the sheet gives it (a 48 dp
 * button around the stock 24 dp glyph).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class QueueRowAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private val px = 3f

    private fun song(title: String, artist: String) = Song(
        id = title, title = title, artist = artist, artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$title.flac",
        contentUriString = "content://$title", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private fun show(handleOnCurrentRow: Boolean = false) = compose.setContent {
        CompositionLocalProvider(LocalDapPalette provides com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Graphite) {
            Column(Modifier.fillMaxSize().background(Dap.surface)) {
                listOf("Current song" to true, "Another song" to false).forEach { (title, current) ->
                    QueuePlaylistSongItem(
                        modifier = Modifier.fillMaxWidth(), onClick = {}, song = song(title, "Artist $title"), isCurrentSong = current, isPlaying = true,
                        isDragging = false, onRemoveClick = {},
                        dragHandle = {
                            IconButton(onClick = {}, modifier = Modifier.size(DapSizes.MinTouchTarget)) {
                                Icon(Icons.Rounded.DragIndicator, contentDescription = "Reorder $title", tint = Dap.disabledText)
                            }
                        },
                        isReorderModeEnabled = false, onMoreOptionsClick = {},
                        // the queue policy turns the current row's handle off; the other rows have it
                        isDragHandleVisible = handleOnCurrentRow || !current,
                        isRemoveButtonVisible = false, isFromPlaylist = true,
                    )
                }
            }
        }
    }

    private fun leftDp(description: String, unmerged: Boolean = true) =
        compose.onNodeWithContentDescription(description, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot.left / px

    private fun textLeftDp(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left / px

    @Test
    fun `the handle's glyph is on the content margin, with its 48 dp touch area around it`() {
        show()
        compose.waitForIdle()
        B29Renders.save(compose, "L7-queue-rows")
        val glyph = compose.onNodeWithContentDescription("Reorder Another song", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // the stock glyph is 24 dp inside the 48 dp button: the glyph on the margin puts the button 12 dp before it
        assertThat(glyph.left / px).isWithin(1f).of(DapSpacing.Gutter.value)
        assertThat(glyph.width / px).isWithin(0.5f).of(24f)
        val button = compose.onNodeWithContentDescription("Reorder Another song").fetchSemanticsNode().boundsInRoot
        assertThat(button.width / px).isAtLeast(48f)
        assertThat(button.left / px).isAtLeast(0f)
    }

    @Test
    fun `title and artist start at the same place, 116 dp in, and the current row starts them where the others do`() {
        show()
        compose.waitForIdle()
        val title = textLeftDp("Another song")
        val artist = textLeftDp("Artist Another song")
        assertThat(artist).isWithin(0.5f).of(title)
        // B33-U1: 8 dp row start, the handle's 48 dp slot, the artwork (48 dp) and 12 dp: the text starts at 116 dp. On the device it was 124 dp
        // (the queue's list added 8 dp of its own, which is gone); B32 had 104 by overlapping the handle over the artwork, which left no gap.
        assertThat(title).isWithin(1f).of(116f)
        // the current row has no handle and keeps its slot: its text is where the others' is
        assertThat(textLeftDp("Current song")).isWithin(0.5f).of(title)
    }

    @Test
    fun `the row keeps everything inside the window and cuts nothing but long titles`() {
        show()
        compose.waitForIdle()
        RenderChecks.assertInsideWindow(compose, "queue rows")
        RenderChecks.assertNoCutText(compose, "queue rows") { false }
    }
}
