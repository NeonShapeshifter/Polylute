package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B24-Q2: the mini player on a song change, counted per composable (see [CompositionCounter]).
 *
 * The shelf is given the song as a parameter, so it cannot tell a tap on a row from a media-next: whatever starts the change, it
 * recomposes the same composables the same number of times. This pins that, and pins how much that is: one run of the shelf and of
 * the pieces that show the song (the cover, the title, the artist), none for the three transport buttons, which do not depend on
 * the song.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class MiniPlayerSongChangeTest {

    @get:Rule
    val compose = createComposeRule()

    private val counter = CompositionCounter()

    private fun song(i: Int) = Song(
        id = "id-$i", title = "Cancion $i", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$i.flac",
        contentUriString = "content://$i", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private var current by mutableStateOf(song(0))
    private var playing by mutableStateOf(true)

    @Before
    fun install() = counter.install()

    @After
    fun uninstall() = counter.uninstall()

    private fun show() {
        compose.setContent {
            MiniPlayerContentInternal(
                song = current,
                isPlaying = playing,
                isPreparingPlayback = false,
                onPlayPause = {},
                onPrevious = {},
                onNext = {},
            )
        }
        compose.waitForIdle()
    }

    private fun change(i: Int): Map<String, Int> {
        compose.waitForIdle()
        val before = counter.counts()
        current = song(i)
        compose.waitForIdle()
        return counter.since(before)
    }

    @Test
    fun `a tap on a row and a media-next recompose the shelf identically`() {
        show()
        compose.onNodeWithText("Cancion 0").assertExists()
        assertThat(counter.count("MiniPlayerContentInternal")).isAtLeast(1)

        val next = change(1)
        val tap = change(250)

        compose.onNodeWithText("Cancion 250").assertExists()
        assertThat(tap).isEqualTo(next)
        assertThat(next["MiniPlayerContentInternal"]).isEqualTo(1)
    }

    @Test
    fun `the transport buttons are not recomposed by a song change`() {
        show()

        val next = change(1)
        val tap = change(250)

        assertThat(next["MiniPlayerGlyphButton"] ?: 0).isEqualTo(0)
        assertThat(tap["MiniPlayerGlyphButton"] ?: 0).isEqualTo(0)
    }
}
