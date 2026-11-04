package com.lostf1sh.pixelplayeross.presentation.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.model.SyncedWord
import com.lostf1sh.pixelplayeross.presentation.B57Renders
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.components.player.CoverLyricsLines
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerLyricsLines
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerLyricsState
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerProgressState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42GlassApp
import com.lostf1sh.pixelplayeross.ui.glass.B42Setup
import com.lostf1sh.pixelplayeross.ui.glass.rememberB42Scene
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


/**
 * B57 2.2: the lines under the cover. No tonal backdrop behind the line being sung; when the synced lyrics carry per-word times the line fills word by word as it is sung, and
 * without them it is bright with the others dim, as before B56. The lyrics page is untouched. Generated covers, made-up lyrics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B57CoverLyricsRenderTest {
    @get:Rule val compose = createComposeRule()

    private val lines = listOf(
        "Nos vemos en el cielo de Madrid", "Cuando llegue la mañana", "Y el sol se vaya a dormir",
        "Dime si todavía me esperas", "Bajo la luz de la ventana", "Cada noche vuelvo a ti",
    )

    private fun synced(withWords: Boolean) = lines.mapIndexed { i, text ->
        val start = i * 4_000
        val words = if (withWords) text.split(' ').mapIndexed { w, word -> SyncedWord(start + w * 650, word, startsNewWord = true) } else null
        SyncedLine(start, text, words)
    }

    // the fourth line (12 000 ms) is being sung; at 13 900 ms the third word of it is part filled
    private val position = 13_900L

    private fun render(name: String, cover: B41Renders.Cover, dark: Boolean, withWords: Boolean) {
        val setup = B42Setup(cover, if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light)
        val lyricsList = synced(withWords)
        compose.setContent {
            val scene = rememberB42Scene(setup, 14f)
            B42GlassApp(setup, scene, expansion = 1f) {
                CompositionLocalProvider(
                    LocalMaterialTheme provides com.lostf1sh.pixelplayeross.ui.ds.dapColorScheme(
                        com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent.current, com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette.current,
                    ),
                ) {
                    Box(Modifier.fillMaxSize().padding(top = 220.dp, start = 24.dp, end = 24.dp)) {
                        PlayerLyricsLines(
                            lyrics = PlayerLyricsState(
                                searchState = LyricsSearchUiState.Idle,
                                stablePlayerStateFlow = MutableStateFlow(StablePlayerState()),
                                playbackPositionFlow = MutableStateFlow(position),
                                externalLyricsEnabled = false, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
                                isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = 0, isFavorite = false,
                                lyricsProvider = { Lyrics(synced = lyricsList) }, lyricsResolveCompletedProvider = { true }, underCover = true,
                            ),
                            progress = PlayerProgressState.of(
                                song = Song(
                                    id = "1", title = "Cine", artist = "Gemini Sound", artistId = 7L, album = "Noche", albumId = 9L, path = "/m/1.flac",
                                    contentUriString = "content://1", albumArtUriString = null, duration = 245_000L, mimeType = "audio/flac", bitrate = 1000, sampleRate = 44100,
                                ),
                                metadata = PlaybackAudioMetadata(), showFileInfo = false,
                                currentPositionProvider = { position }, totalDurationProvider = { 245_000L }, isPlayingProvider = { true },
                            ),
                            onOpen = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        B57Renders.save(compose, "lyrics-under-$name")
    }

    @Test fun `per-word times over a light cover`() = render("words-light", B41Renders.veryLight(), dark = false, withWords = true)
    @Test fun `per-word times over a dark cover`() = render("words-dark", B41Renders.veryDark(), dark = true, withWords = true)
    @Test fun `no per-word times over a light cover`() = render("plain-light", B41Renders.veryLight(), dark = false, withWords = false)
    @Test fun `no per-word times over a dark cover`() = render("plain-dark", B41Renders.veryDark(), dark = true, withWords = false)

    // ── the model ────────────────────────────────────────────────────────────────────────────────

    private val words = listOf(SyncedWord(1_000, "Dime"), SyncedWord(1_600, "si"), SyncedWord(2_000, "todavía"), SyncedWord(3_000, "me", startsNewWord = true))

    @Test fun `the words spell the line and say where each starts`() {
        val (text, starts) = CoverLyricsLines.spell(words)
        assertThat(text).isEqualTo("Dime si todavía me")
        assertThat(starts.toList()).containsExactly(0, 5, 8, 16).inOrder()
    }

    @Test fun `a word fills in proportion to the time it has taken and the sung ones are full`() {
        val (text, starts) = CoverLyricsLines.spell(words)
        fun at(ms: Long) = CoverLyricsLines.filledChars(words, starts, text.length, ms, lineEndMs = 4_000)
        assertThat(at(900)).isEqualTo(0f)                        // nothing yet
        assertThat(at(1_000)).isEqualTo(0f)                      // the first word begins
        assertThat(at(1_300)).isWithin(0.01f).of(2.5f)           // half of "Dime " (5 characters)
        assertThat(at(1_600)).isWithin(0.01f).of(5f)             // "Dime " is full, "si" begins
        assertThat(at(2_000)).isWithin(0.01f).of(8f)
        assertThat(at(3_500)).isWithin(0.01f).of(17f)            // the last word, half of its 2 characters + its lead-in
        assertThat(at(9_000)).isWithin(0.01f).of(text.length.toFloat()) // past the end: all of it
    }

    @Test fun `the lines carry the words only when the synced lyrics have per-word times`() {
        val withWords = CoverLyricsLines.at(Lyrics(synced = synced(true)), 13_900L, 0)!!
        assertThat(withWords.current).isEqualTo("Dime si todavía me esperas")
        assertThat(withWords.currentWords).hasSize(5)
        assertThat(withWords.currentLineEndMs).isEqualTo(16_000L) // the next line begins
        val without = CoverLyricsLines.at(Lyrics(synced = synced(false)), 13_900L, 0)!!
        assertThat(without.currentWords).isNull()
        assertThat(without.current).isEqualTo("Dime si todavía me esperas")
        // one timed word is not a fill: it needs two
        assertThat(CoverLyricsLines.fillableWords(SyncedLine(0, "x", listOf(SyncedWord(0, "x"))))).isNull()
    }

    @Test fun `the backdrop is gone from the lines under the cover and the lyrics page still has it`() {
        val under = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/player/PlayerLyricsLines.kt").readText()
        assertThat(under).doesNotContain("ActiveBackdrop")
        assertThat(under).doesNotContain("DapLyricsHighlight")
        assertThat(under).doesNotContain("drawBehind")
        val page = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/LyricsSheet.kt").readText()
        assertThat(page).contains("DapLyricsHighlight")
    }
}
