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
 * B56 phase 3.3: the synced highlight over a light cover and a dark cover (generated covers, the real cover glass), on the lyrics page and under the cover.
 * The same file runs against the tree before this batch (it uses nothing the batch added) and writes `...-before.png`; here it writes `...-after.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B56LyricsHighlightRenderTest {
    @get:Rule val compose = createComposeRule()

    private val phase = if (File("").absoluteFile.path.contains("B56-before")) "before" else "after"
    private val out: File
        get() = File(System.getenv("B56_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B56-gates/renders").apply { mkdirs() }

    private val lines = listOf(
        "Nos vemos en el cielo de Madrid", "Cuando llegue la mañana", "Y el sol se vaya a dormir",
        "Dime si todavía me esperas", "Bajo la luz de la ventana", "Cada noche vuelvo a ti",
        "Aunque el tiempo nos separe", "Siempre habrá un lugar para mí",
    )
    private val synced = lines.mapIndexed { i, text -> SyncedLine(i * 4_000, text) }
    private val position = 13_500L                          // the fourth line is being sung: three are read, four are still to come

    private fun render(name: String, cover: B41Renders.Cover, dark: Boolean, underCover: Boolean) {
        val theme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light
        val setup = B42Setup(cover, theme)
        compose.setContent {
            val scene = rememberB42Scene(setup, 14f)
            B42GlassApp(setup, scene, expansion = 1f) {
                CompositionLocalProvider(
                    LocalMaterialTheme provides com.lostf1sh.pixelplayeross.ui.ds.dapColorScheme(
                        com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent.current, com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette.current,
                    ),
                ) {
                    if (underCover) {
                        Box(Modifier.fillMaxSize().padding(top = 220.dp, start = 24.dp, end = 24.dp)) {
                            PlayerLyricsLines(
                                lyrics = PlayerLyricsState(
                                    searchState = LyricsSearchUiState.Idle,
                                    stablePlayerStateFlow = MutableStateFlow(StablePlayerState()),
                                    playbackPositionFlow = MutableStateFlow(position),
                                    externalLyricsEnabled = false, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
                                    isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = 0, isFavorite = false,
                                    lyricsProvider = { Lyrics(synced = synced) }, lyricsResolveCompletedProvider = { true }, underCover = true,
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
                    } else {
                        val colors = lyricsSheetColors(MaterialTheme.colorScheme)
                        SyncedLyricsList(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                            contentPadding = PaddingValues(top = 120.dp, bottom = 24.dp),
                            lines = synced.toImmutableList(),
                            listState = rememberLazyListState(),
                            playbackPositionFlow = MutableStateFlow(position),
                            lyricsSyncOffset = 0,
                            accentColor = colors.lyricHighlight,
                            textStyle = MaterialTheme.typography.titleLarge,
                            onLineClick = {},
                            highlightZoneFraction = 0.08f,
                            highlightOffsetDp = 32.dp,
                            autoscrollAnimationSpec = tween(300),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "lyrics-highlight-$name-$phase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `lyrics page over a light cover`() = render("page-light-cover", B41Renders.veryLight(), dark = false, underCover = false)

    @Test fun `lyrics page over a dark cover`() = render("page-dark-cover", B41Renders.veryDark(), dark = true, underCover = false)

    @Test fun `lines under the cover over a light cover`() = render("under-light-cover", B41Renders.veryLight(), dark = false, underCover = true)

    @Test fun `lines under the cover over a dark cover`() = render("under-dark-cover", B41Renders.veryDark(), dark = true, underCover = true)
}
