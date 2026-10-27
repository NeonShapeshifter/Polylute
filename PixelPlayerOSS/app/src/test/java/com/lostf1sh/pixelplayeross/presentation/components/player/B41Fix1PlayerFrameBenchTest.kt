package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.components.CompositionCounter
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.glass.CoverGlassHost
import com.lostf1sh.pixelplayeross.ui.glass.CoverGlassScene
import com.lostf1sh.pixelplayeross.ui.glass.GlassBands
import com.lostf1sh.pixelplayeross.ui.glass.GlassClock
import com.lostf1sh.pixelplayeross.ui.glass.GlassCrossfade
import com.lostf1sh.pixelplayeross.ui.glass.GlassEnvironment
import com.lostf1sh.pixelplayeross.ui.glass.GlassField
import com.lostf1sh.pixelplayeross.ui.glass.GlassFieldMath
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B41-fix1 (B4): what the full player composes, per second, while a song plays with the glass moving behind it: the real player surfaces,
 * synced lyrics under the cover, the position sampled four times a second as the app does, and the real glass host stepping on every frame of
 * the display. Counted from the Compose runtime's own tracing hook ([CompositionCounter]).
 *
 * The question is the HONOR's 10 s of the player: 176 frames, 24 janky, 20 "Slow UI thread". The glass is meant to run on the render thread
 * only, so no composable may run once per frame because of it. The table is printed (`B41-fix1 B4 table`) and what it shows is pinned below.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h900dp-xxhdpi")
class B41Fix1PlayerFrameBenchTest {
    @get:Rule
    val compose = createComposeRule()

    private val counter = CompositionCounter()

    @Before
    fun install() = counter.install()

    @After
    fun uninstall() = counter.uninstall()

    private val palette = DapBaseTheme.Graphite.palette
    private val look = GlassBands.of(palette, palette.accentColor(DapAccent.Sage))!!
    private val field: GlassField by lazy {
        val px = IntArray(96 * 96) { i -> (0xFF shl 24) or (((i % 96) * 2) shl 16) or ((40 + i / 96) shl 8) or (200 - i % 96) }
        GlassFieldMath.field(GlassFieldMath.grid(px, 96, 96), look.band)
    }

    private val lyrics = Lyrics(synced = (0 until 100).map { SyncedLine(time = 1_000 + it * 3_000, line = "Dice qué bella camina esa muchacha $it") })

    private val song = Song(
        id = "1", title = "La Costa Del Silencio", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val env = PlayerSheetEnv(
        loadingTweaks = FullPlayerLoadingTweaks(delayAll = false),
        isSheetDragGestureActive = false,
        currentSheetState = PlayerSheetState.EXPANDED,
        allowRealtimeUpdates = true,
        expansionFractionProvider = { 1f },
    )

    private var measurePasses = 0
    private var position by mutableLongStateOf(1_500L)
    private val positionFlow = MutableStateFlow(1_500L)

    private fun state() = buildFullPlayerUiState(
        env = env, song = song, queue = persistentListOf(), queueSourceName = "All Songs", mediaItemIndex = 0,
        carouselStyle = CarouselStyle.NO_PEEK, artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
        audioMetadata = PlaybackAudioMetadata(mediaId = song.id, mimeType = "audio/flac", sampleRate = 44100),
        showFileInfo = true, isFavorite = false, isBuffering = false, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF,
        isPlayingProvider = { true }, playWhenReadyProvider = { true },
        currentPositionProvider = { position }, totalDurationProvider = { 280_000L },
        lyrics = PlayerLyricsState(
            searchState = LyricsSearchUiState.Idle,
            stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = song, isPlaying = true)),
            playbackPositionFlow = positionFlow,
            externalLyricsEnabled = false, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
            isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF, isFavorite = false,
            lyricsProvider = { lyrics }, lyricsResolveCompletedProvider = { true }, underCover = true,
        ),
        isLandscape = false,
    )

    /** The composables that ran per second over [seconds] of the player playing, with or without the glass moving behind it. */
    private fun measure(withGlass: Boolean, seconds: Int = 6): Map<String, Float> {
        val crossfade = GlassCrossfade()
        val clock = GlassClock()
        val ok = GlassEnvironment(mutableStateOf(true), mutableStateOf(true), mutableStateOf(true))
        val ui = state()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LaunchedEffect(Unit) { if (withGlass) crossfade.show(field) }
            LaunchedEffect(Unit) {
                // what the app samples: the position four times a second
                while (true) {
                    delay(250)
                    position += 250
                    positionFlow.value = position
                }
            }
            CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) {
                DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                    Box(Modifier.fillMaxSize()) {
                        if (withGlass) {
                            CoverGlassHost(
                                scene = CoverGlassScene(crossfade, clock, look.band.base),
                                playing = flowOf(true),
                                playerExpansion = { 1f },
                                movingBackgroundOn = true,
                                lockSurfaceVisible = false,
                                lockCovers = false,
                                environment = ok,
                            )
                        }
                        // the player's own box: how often it is measured and placed (a layout pass anywhere under it that reaches it)
                        Box(
                            Modifier
                                .fillMaxSize()
                                .layout { measurable, constraints ->
                                    measurePasses++
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                                },
                        ) { FullPlayerScreen(ui, NoActions()) }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)       // settled: the first composition, the glass's field, the cross-fade
        if (withGlass) assertThat(crossfade.current).isNotNull()
        val before = counter.counts()
        val phaseBefore = clock.phase
        val passesBefore = measurePasses
        compose.mainClock.advanceTimeBy(seconds * 1_000L)
        println("B41-fix1 B4 | measure passes of the player's box in $seconds s (glass ${if (withGlass) "moving" else "off"}): ${measurePasses - passesBefore}")
        assertThat(measurePasses - passesBefore).isAtMost(seconds)
        if (withGlass) assertThat(clock.phase - phaseBefore).isWithin(0.3f).of(seconds.toFloat())
        return counter.since(before).mapValues { it.value / seconds.toFloat() }
    }

    private fun table(title: String, runs: Map<String, Float>) {
        val rows = runs.entries.sortedByDescending { it.value }.take(14).joinToString("\n") { "  %6.1f /s  %s".format(it.value, it.key) }
        println("B41-fix1 B4 table | $title\n$rows")
    }

    @Test
    fun the_player_playing_composes_a_few_times_a_second_at_most() {
        val still = measure(withGlass = false)
        table("player playing, no glass", still)
        assertThat(still.maxOfOrNull { it.value } ?: 0f).isAtMost(8f)
    }

    @Test
    fun the_player_with_the_glass_moving_composes_nothing_per_frame() {
        val moving = measure(withGlass = true)
        table("player playing, glass moving on every frame", moving)
        // nothing runs once per frame: the busiest composable runs a few times a second (the position is sampled four times a second)
        val busiest = moving.maxOfOrNull { it.value } ?: 0f
        println("B41-fix1 B4 | busiest composable with the glass moving: %.1f runs a second".format(busiest))
        assertThat(busiest).isAtMost(8f)
    }
}
