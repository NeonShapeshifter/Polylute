package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.B33Renders
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B33-U1 and U2: the mini player and the queue's rows at the HONOR's size (720 x 1610 px at 2x). The same
 * source runs against the tree as it was at the start of the batch (`B33_PREFIX=before`) and as it is now (`after`): it uses only what both
 * have, so the pair differ in nothing but the screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B33QueueMiniRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private fun song(n: Int, title: String, artist: String = "Mägo de Oz") = Song(
        id = "$n", title = title, artist = artist, artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$n.flac",
        contentUriString = "content://$n", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    // ── U2: the mini player ──────────────────────────────────────────────────────────────────────────

    private fun mini(playing: Boolean) {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                Box(Modifier.fillMaxSize().background(Dap.background), contentAlignment = Alignment.BottomCenter) {
                    MiniPlayerContentInternal(
                        song = song(1, "Porque Ahora", "Vitin Aviles"), isPlaying = playing, isPreparingPlayback = false,
                        onPlayPause = {}, onPrevious = {}, onNext = {}, modifier = Modifier.fillMaxWidth().height(MiniPlayerHeight),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `mini player playing`() {
        mini(playing = true)
        B33Renders.save(compose, "u2-mini-playing")
    }

    @Test
    fun `mini player paused`() {
        mini(playing = false)
        B33Renders.save(compose, "u2-mini-paused")
    }

    // ── U1: the queue's rows (the folded, half-way and open sheet are drawn by B33QueueFoldTest) ───────

    @Test
    fun `three queue rows, the first of them the song that is playing`() {
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                Column(Modifier.fillMaxWidth().background(Dap.background).padding(start = QueueListStartPadding, end = 8.dp)) {
                    listOf(true, false, false).forEachIndexed { index, current ->
                        QueuePlaylistSongItem(
                            onClick = {},
                            song = song(
                                index + 1,
                                if (current) "Porque Ahora" else if (index == 1) "\"The Take Over, The Breaks Over\"" else "2 Of Amerikaz Most Wanted Y Un Título Largo",
                                if (index == 2) "2Pac, Snoop Dogg" else "Fall Out Boy",
                            ),
                            isCurrentSong = current, isPlaying = false, isDragging = false, onRemoveClick = {},
                            dragHandle = {
                                IconButton(onClick = {}, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.DragIndicator, "Reordenar", tint = Dap.disabledText) }
                            },
                            isReorderModeEnabled = false, onMoreOptionsClick = {}, isDragHandleVisible = !current,
                            isRemoveButtonVisible = false, isFromPlaylist = true,
                        )
                    }
                }
            }
        }
        B33Renders.save(compose, "u1-rows")
    }
}
