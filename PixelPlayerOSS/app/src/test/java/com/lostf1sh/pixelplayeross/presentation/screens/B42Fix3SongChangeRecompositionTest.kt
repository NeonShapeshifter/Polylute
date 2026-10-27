package com.lostf1sh.pixelplayeross.presentation.screens

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import coil.Coil
import coil.ImageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.request.ImageResult
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.presentation.components.CompositionCounter
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemePair
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.glass.CoverGlassFields
import com.lostf1sh.pixelplayeross.ui.glass.CoverGlassScene
import com.lostf1sh.pixelplayeross.ui.glass.LocalCoverGlass
import com.lostf1sh.pixelplayeross.ui.theme.AppPainting
import com.lostf1sh.pixelplayeross.ui.theme.PixelPlayerTheme
import com.lostf1sh.pixelplayeross.ui.theme.rememberAppPainting
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B42-fix3, items 1 and 2: **a song change must not recompose the Library, and its artwork must survive it.**
 *
 * The reviewer's trace of the HONOR (9 song changes in "Cover glass") showed a 370-440 ms frame about 0.6 s after each one, with
 * `rememberAsyncImagePainter` composed 1,764 times (about 52 per long frame, every visible Library row) and `Compose:recompose` at 6.1 s of the 9.3 s
 * in the long frames. (Coil's own composables carry no trace markers in the host build, so they are not counted here: `SmartImage`, which holds the
 * painter, and the loader's requests stand for them.) The main cause was Material3 1.5's single static theme local: the Material scheme carried
 * the song's accent, and a changed static local gives the whole content under its provider no group that can skip. `LocalDapPalette` was already
 * unchanged across songs. Every row, every
 * `SmartImage`, every `AsyncImage` ran again, and so did `rememberAsyncImagePainter` inside it (the request itself was always `remember`ed on the cover and
 * the size: it did not depend on a colour).
 *
 * Here a Library of 200 songs is composed under the app's own theme (the root's `rememberAppPainting`, `PixelPlayerTheme`, the scene it provides),
 * the song that is playing changes together with its cover, its accent and its glass, the settle passes and the 400 ms fade ends, and what ran is counted
 * with the Compose runtime's own tracing hook ([CompositionCounter]) and a counting image loader:
 *  - the rows that are not the one that was playing or the one that is: **0** recompositions of a row's artwork (`SmartImage`), **0** image requests, **0** recompositions of the list; the two rows that change are the only rows that run;
 *  - the same in "Theme and accent" (nothing about the colours changes), "From album art" (a gradient behind, a new accent) and "Cover glass".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class B42Fix3SongChangeRecompositionTest {

    @get:Rule
    val compose = createComposeRule()

    private val counter = CompositionCounter()

    /** The requests the list's images made. */
    private class CountingLoader(private val real: ImageLoader, val requests: AtomicInteger) : ImageLoader by real {
        override fun enqueue(request: ImageRequest): Disposable {
            requests.incrementAndGet()
            return real.enqueue(request)
        }

        override suspend fun execute(request: ImageRequest): ImageResult {
            requests.incrementAndGet()
            return real.execute(request)
        }
    }

    private val requests = AtomicInteger(0)

    private fun song(i: Int) = Song(
        id = "id-$i", title = "Cancion $i", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$i.flac",
        contentUriString = "content://$i", albumArtUriString = "content://art/$i", duration = 280_000L, mimeType = "audio/flac", bitrate = 1003,
        sampleRate = 44100,
    )

    private val songs = (0 until 200).map(::song)

    private val player = MutableStateFlow(StablePlayerState(currentSong = songs[3], isPlaying = true))
    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.stablePlayerState } returns player
        every { it.scrollToIndexEvent } returns MutableSharedFlow()
        every { it.libraryLocateRequest } returns MutableStateFlow(null)
        every { it.locatedSongId } returns MutableStateFlow(null)
    }

    private fun scheme(primary: Color) = ColorSchemePair(light = lightColorScheme(primary = primary), dark = darkColorScheme(primary = primary))

    private val coverScheme = MutableStateFlow<ColorSchemePair?>(scheme(Color(0xFFD08020)))
    private val coverUri = MutableStateFlow<String?>("content://art/3")

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A cover with something in it, per song, so a field made from it is not another's. */
    private val fields = CoverGlassFields(mockk<Context>(relaxed = true)).also { f ->
        f.decoder = { uri -> Triple(96, 96, IntArray(96 * 96) { i -> rgb((i % 96 * 2 + uri.hashCode() * 40) and 255, (i / 96 * 2 + uri.hashCode() * 70) and 255, (uri.hashCode() * 90) and 255) }) }
    }

    private var appPainting: AppPainting? = null

    @Before
    fun install() {
        counter.install()
        Coil.setImageLoader(CountingLoader(ImageLoader.Builder(androidx.test.core.app.ApplicationProvider.getApplicationContext()).build(), requests))
    }

    @After
    fun uninstall() {
        counter.uninstall()
    }

    /** The paging source is the screen's own and outlives its recompositions (a new flow per recomposition would reload every row, whatever the theme did). */
    private val pagingFlow = flowOf(PagingData.from(songs))

    /** The app's root as `MainActivity` builds it: how it is painted for this colour source, the theme that gets it, the scene everything under it can reach. */
    private fun show(source: String) {
        compose.setContent {
            val painting = rememberAppPainting(
                appColorSource = source,
                appThemeMode = "dark",
                storedBaseTheme = DapBaseTheme.Graphite,
                accent = DapAccent.Sage,
                systemDark = true,
                albumArtColorScheme = coverScheme,
                themedCoverUri = coverUri,
                fields = fields,
            )
            appPainting = painting
            val painted = painting.painted
            PixelPlayerTheme(
                darkTheme = painted.useDark,
                accent = DapAccent.Sage,
                baseTheme = painted.baseTheme,
                palette = painted.look?.palette ?: painted.baseTheme.palette,
                accentColorOverride = painted.look?.accent,
            ) {
                CompositionLocalProvider(LocalCoverGlass provides painting.scene.takeIf { painted.glassShown }) {
                    Surface(Modifier, color = Color.Transparent) { TheLibrary(pagingFlow, playerViewModel) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun scene(): CoverGlassScene = appPainting!!.scene

    /** Waits (real time: the field is made on another thread) until the backdrop of this source is on screen and settled; "Theme and accent" has none. */
    private fun settle(source: String, previous: Any? = null) {
        if (AppColorSource.hasBackdrop(source)) {
            compose.waitUntil(15_000) {
                val x = scene().crossfade
                x.current != null && x.current !== previous && x.previous == null && x.blend == 1f
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    /** One song change: the playing song, its cover, its colour scheme (so its accent) and (in the cover modes) its glass; and what ran for it. */
    private fun change(source: String, index: Int, primary: Color): Map<String, Int> {
        compose.waitForIdle()
        val before = counter.counts()
        val requestsBefore = requests.get()
        val fieldBefore = if (AppColorSource.hasBackdrop(source)) scene().crossfade.current else null
        player.value = player.value.copy(currentSong = songs[index], currentMediaItemIndex = index)
        coverUri.value = "content://art/$index"
        coverScheme.value = scheme(primary)
        settle(source, fieldBefore)
        lastRequests = requests.get() - requestsBefore
        return counter.since(before)
    }

    private var lastRequests = 0

    private fun assertNothingElseRan(label: String, ran: Map<String, Int>) {
        println("B42-fix3 items 1-2 | $label: ran ${ran.filterKeys { it in watched }.toSortedMap()}, image requests $lastRequests")
        // the two rows that change (the one that was playing and the one that plays now) are the only rows that run
        assertThat(ran["LibraryPlaybackAwareSongItem"] ?: 0).isAtLeast(1)
        assertThat(ran["LibraryPlaybackAwareSongItem"] ?: 0).isAtMost(2)
        // nothing at list level
        assertThat(ran["LibrarySongsTab"] ?: 0).isEqualTo(0)
        // and no row's artwork: not the composable that builds the request and holds Coil's painter, and not one image request (a rebuilt painter asks again)
        assertThat(ran["SmartImage"] ?: 0).isEqualTo(0)
        assertThat(lastRequests).isEqualTo(0)
    }

    private val watched = setOf("LibraryPlaybackAwareSongItem", "EnhancedSongListItem", "SmartImage", "LibrarySongsTab")

    private fun run(source: String, label: String) {
        show(source)
        settle(source)
        // the counter sees the rows and their artwork, or the zeros below would mean nothing
        assertThat(counter.count("LibraryPlaybackAwareSongItem")).isAtLeast(5)
        assertThat(counter.count("SmartImage")).isAtLeast(5)
        if (AppColorSource.takesColoursFromCover(source)) {
            assertThat(appPainting!!.painted.look).isNotNull()
        }
        val accents = ArrayList<Color?>()
        for ((index, colour) in listOf(4 to Color(0xFF2090D0), 7 to Color(0xFF40B060), 5 to Color(0xFFD04070), 6 to Color(0xFFB0A020))) {
            accents += appPainting!!.painted.look?.accent
            assertNothingElseRan("$label, song $index", change(source, index, colour))
            if (AppColorSource.hasBackdrop(source)) assertThat(scene().crossfade.current).isNotNull()
        }
        accents += appPainting!!.painted.look?.accent
        if (AppColorSource.takesColoursFromCover(source)) {
            // the colours did change with the song (or the zeros above would be a theme that never moved)
            assertThat(accents.filterNotNull().toSet().size).isAtLeast(3)
        }
    }

    private val themeFlow = MutableStateFlow(DapBaseTheme.Graphite)

    /**
     * Item 2 in its strongest form: even a change of **theme** (a new palette, which does run everything under it) asks for no image again. A row's image
     * request depends on the cover, its size and Coil's cache policy and on nothing else: not a colour, not a palette, not a theme.
     */
    @Test
    fun a_change_of_theme_runs_the_rows_again_but_asks_for_no_image_again() {
        compose.setContent {
            val t by themeFlow.collectAsState()
            PixelPlayerTheme(darkTheme = true, accent = DapAccent.Sage, baseTheme = t) {
                Surface(Modifier, color = Color.Transparent) { TheLibrary(pagingFlow, playerViewModel) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val before = counter.counts()
        val requestsBefore = requests.get()
        themeFlow.value = DapBaseTheme.Navy
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val ran = counter.since(before)
        println("B42-fix3 item 2 | a theme change: SmartImage ran ${ran["SmartImage"] ?: 0}, image requests ${requests.get() - requestsBefore}")
        assertThat(ran["SmartImage"] ?: 0).isAtLeast(5)          // a palette is a static change: the rows do run
        assertThat(requests.get() - requestsBefore).isEqualTo(0) // and not one of them asks for its picture again
    }

    @Test
    fun a_song_change_recomposes_no_row_and_no_artwork_in_cover_glass() = run(AppColorSource.COVER_GLASS, "Cover glass")

    @Test
    fun a_song_change_recomposes_no_row_and_no_artwork_in_from_album_art() = run(AppColorSource.ALBUM_ART, "From album art")

    @Test
    fun a_song_change_recomposes_no_row_and_no_artwork_in_theme_and_accent() = run(AppColorSource.THEME, "Theme and accent")
}

/** The Library's songs list as a screen composes it: a composable of its own with the sources it reads as parameters. */
@Composable
private fun TheLibrary(pagingFlow: kotlinx.coroutines.flow.Flow<PagingData<Song>>, playerViewModel: PlayerViewModel) {
    val items = pagingFlow.collectAsLazyPagingItems()
    LibrarySongsTab(
        songs = items,
        isLoading = false,
        playerViewModel = playerViewModel,
        bottomBarHeight = 0.dp,
        onMoreOptionsClick = {},
        isRefreshing = false,
        onRefresh = {},
        sortOption = SortOption.SongTitleAZ,
        onLocateCurrentSongVisibilityChanged = {},
        onRegisterLocateCurrentSongAction = {},
        hasCurrentSong = true,
    )
}
