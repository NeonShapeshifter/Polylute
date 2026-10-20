package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.B29Renders
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapPalette
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * B29-fix1 F7.1: the current row of the queue wears the cover's colour.
 *
 * The queue sheet is a sibling of the full player, so the accent the player wears (B29-L11) did not reach it: its round buttons took the
 * cover's colour from the Material scheme the sheet is wrapped in, while the current row's title and playing mark read the app's accent
 * (`LocalDapAccent`). [QueueSurface] now provides the cover's accent to the sheet (held while the sheet is hidden, the app's with no cover
 * or with the player colour neutral). The first test reads the real row's pixels under a provided accent; the second pins the wiring.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class QueueCurrentRowAccentTest {

    @get:Rule
    val compose = createComposeRule()

    private val brown = Color(0xFFB5651D)

    private fun song(title: String) = Song(
        id = title, title = title, artist = "Dillom", artistId = 7L, album = "Por cesárea", albumId = 9L, path = "/m/$title.flac",
        contentUriString = "content://$title", albumArtUriString = null, duration = 180_000L, mimeType = "audio/flac", bitrate = 1523, sampleRate = 48000,
    )

    private fun show(accent: Color?) = compose.setContent {
        CompositionLocalProvider(LocalDapPalette provides DapPalette.Graphite) {
            val provided = accent ?: LocalDapAccent.current
            CompositionLocalProvider(LocalDapAccent provides provided) {
                Column(Modifier.fillMaxSize().background(Dap.surface)) {
                    listOf("Current song" to true, "Another song" to false).forEach { (title, current) ->
                        QueuePlaylistSongItem(
                            modifier = Modifier.fillMaxWidth(), onClick = {}, song = song(title), isCurrentSong = current, isPlaying = true,
                            isDragging = false, onRemoveClick = {}, dragHandle = {}, isReorderModeEnabled = false, onMoreOptionsClick = {},
                            isDragHandleVisible = false, isRemoveButtonVisible = false, isFromPlaylist = true,
                        )
                    }
                }
            }
        }
    }

    /** Pixels inside [text]'s bounds that are close to [target]. */
    private fun pixelsLike(text: String, target: Color): Int {
        val bounds = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val t = target.toArgb()
        var n = 0
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt()) {
            val p = bitmap.getPixel(x, y)
            if (abs(((p shr 16) and 0xFF) - ((t shr 16) and 0xFF)) < 28 && abs(((p shr 8) and 0xFF) - ((t shr 8) and 0xFF)) < 28 &&
                abs((p and 0xFF) - (t and 0xFF)) < 28
            ) n++
        }
        return n
    }

    @Test
    fun `the current row's title wears the accent it is given, and the other rows keep the ordinary text colour`() {
        show(brown)
        compose.waitForIdle()
        B29Renders.saveFix1(compose, "F7-queue-current-row-cover-accent")
        assertThat(pixelsLike("Current song", brown)).isGreaterThan(20)
        assertThat(pixelsLike("Another song", brown)).isEqualTo(0)
    }

    @Test
    fun `the queue sheet is given the cover's accent by its surface, held while hidden, and the app's with no cover`() {
        val root = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components")
        fun code(name: String) = File(root, name).readText().replace("\r\n", "\n").lines()
            .map { it.substringBefore("//") }.joinToString("\n")
        val surface = code("QueueSurface.kt")
        assertThat(surface).contains("val queueAccent = rememberHeldWhile(!visible, albumAccent) ?: appAccent")
        assertThat(surface).contains("CompositionLocalProvider(LocalDapAccent provides queueAccent)")
        // the provider is around the sheet itself
        assertThat(surface.substringAfter("provides queueAccent)").substringBefore("QueueBottomSheet(")).isNotEmpty()
        // and the value comes from the sheet that already holds it for the player
        val host = code("UnifiedPlayerOverlaysLayer.kt")
        assertThat(host).contains("albumAccent = albumAccent")
        assertThat(code("UnifiedPlayerSheetV2.kt")).contains("albumAccent = albumAccent,\n                queueScrimAlpha = queueScrimAlpha")
    }
}
