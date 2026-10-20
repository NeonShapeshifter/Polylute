package com.lostf1sh.pixelplayeross.presentation.lockscreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.presentation.screens.LibrarySongsTab
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B31 P2.4: **what a pre-layout of the covered app would save at the reveal, on the host.**
 *
 * The phone's first moving frame of a flick took 111 ms, 84 ms of it in draw: [FreezeWhileCovered] lifts `hidden` at the first pixel the
 * surface moves, and the covered app is measured, placed and drawn for the first time since it was covered. Two candidates for doing
 * it earlier are timed here over the real Songs tab (400 songs) composed hidden, fresh each round:
 *
 *  - **today**: lift `hidden`, and measure, place and draw in one frame;
 *  - **measure only**: a pass that measures the content and does not place it (so nothing is drawn), then the lift: the reveal frame
 *    only places and draws (Compose does not measure a node again whose constraints are the same).
 *
 * **The host does not draw in its frame loop** (a draw counter stayed at zero through `waitForIdle`; the first version of this bench
 * failed on exactly that), so the draw phase is timed as a capture of the root to a bitmap, which draws the whole tree: it includes the
 * bitmap copy, so it is an upper bound on the draw. The table has three columns per variant: the lift (measure and place, host frame
 * loop), the capture right after it (the draw), and both.
 *
 * Opt-in (`B31_BENCH=1`), writes `$B31_BENCH_OUT`. It is a measurement, not a check; the host's numbers are for the *split* (how much of
 * the frame a measure-only pass could take), not for how long the HONOR takes (cold host ×21–24 on the debug build). The one real thing
 * it asserts, when it runs, is that drawing happened at all (a draw counter), so a zero-draw host cannot pass for a result.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class RevealFrameBench {
    @get:Rule
    val compose = createComposeRule()

    private fun song(i: Int) = Song(
        id = "id-$i", title = "Cancion numero $i de la biblioteca", artist = "Artista $i", artistId = 7L, album = "Album ${i / 12}", albumId = 9L,
        path = "/m/$i.flac", contentUriString = "content://$i", albumArtUriString = null, duration = 280_000L + i, mimeType = "audio/flac",
        bitrate = 1003, sampleRate = 44100,
    )

    private val songs = (0 until 400).map(::song)
    private val player = MutableStateFlow(StablePlayerState(currentSong = songs[300], isPlaying = true))
    private val playerViewModel = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.stablePlayerState } returns player
        every { it.scrollToIndexEvent } returns MutableSharedFlow()
        every { it.libraryLocateRequest } returns MutableStateFlow(null)
        every { it.locatedSongId } returns MutableStateFlow(null)
    }

    private var round by mutableIntStateOf(0)
    private var hidden by mutableStateOf(true)
    private var measureOnly by mutableStateOf(false)
    private var draws = 0

    @Composable
    private fun TheApp() {
        val items = flowOf(PagingData.from(songs)).collectAsLazyPagingItems()
        Box(Modifier.fillMaxSize().drawBehind { draws++ }) {
            LibrarySongsTab(
                songs = items, isLoading = false, playerViewModel = playerViewModel, bottomBarHeight = 80.dp, onMoreOptionsClick = {},
                isRefreshing = false, onRefresh = {}, sortOption = SortOption.SongTitleAZ,
                onLocateCurrentSongVisibilityChanged = {}, onRegisterLocateCurrentSongAction = {}, hasCurrentSong = true,
            )
        }
    }

    /** [FreezeWhileCovered]'s layout, plus the candidate: measured while hidden, but not placed. */
    @Composable
    private fun Cover(content: @Composable () -> Unit) {
        Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            if (hidden && !measureOnly) {
                layout(constraints.maxWidth, constraints.maxHeight) {}
            } else {
                val placeables = measurables.map { it.measure(constraints) }
                layout(constraints.maxWidth, constraints.maxHeight) { if (!hidden) placeables.forEach { it.place(0, 0) } }
            }
        }
    }

    private class Sample(val label: String, val lift: Double, val draw: Double, val prepass: Double)

    private class Timing(val prepass: Double, val lift: Double, val draw: Double)

    private fun reveal(prepared: Boolean): Timing {
        hidden = true
        measureOnly = false
        round++ // a fresh subtree, composed while hidden
        compose.waitForIdle()
        var prepass = 0.0
        if (prepared) {
            val p0 = System.nanoTime()
            measureOnly = true
            compose.waitForIdle()
            prepass = (System.nanoTime() - p0) / 1e6
        }
        val t0 = System.nanoTime()
        hidden = false
        compose.waitForIdle()
        val t1 = System.nanoTime()
        compose.onRoot().captureToImage()
        val t2 = System.nanoTime()
        return Timing(prepass, (t1 - t0) / 1e6, (t2 - t1) / 1e6)
    }

    private fun median(values: List<Double>) = values.sorted()[values.size / 2]

    @Test
    fun `the reveal frame of the Songs tab over the host, today and with a measure-only pass before it`() {
        assumeTrue(System.getenv("B31_BENCH") == "1")
        compose.setContent { key(round) { Cover { TheApp() } } }
        compose.waitForIdle()
        val rows = ArrayList<Sample>()
        for ((label, prepared) in listOf("today (measure + place + draw in the reveal frame)" to false, "measure-only pass first (the frame places and draws)" to true)) {
            val cold = reveal(prepared)
            rows += Sample("$label, cold", cold.lift, cold.draw, cold.prepass)
            repeat(WARMUP) { reveal(prepared) }
            val warm = List(RUNS) { reveal(prepared) }
            rows += Sample("$label, warm", median(warm.map { it.lift }), median(warm.map { it.draw }), median(warm.map { it.prepass }))
        }
        check(draws > 0) { "the host drew nothing, so the table says nothing about the draw phase" }
        val table = buildString {
            appendLine("Reveal frame over the real Songs tab on the host (ms; cold = first of its kind, warm = median of $RUNS after $WARMUP), draws=$draws")
            appendLine("%-72s %9s %9s %9s %9s".format("", "pre-pass", "lift", "draw", "frame"))
            for (row in rows) appendLine("%-72s %9.2f %9.2f %9.2f %9.2f".format(row.label, row.prepass, row.lift, row.draw, row.lift + row.draw))
        }
        println(table)
        val out = File(System.getenv("B31_BENCH_OUT") ?: "../../../analysis/audit-2026-09-27/batches/B31-evidence/logs/reveal-frame-host.txt")
        out.parentFile?.mkdirs()
        out.writeText(table)
    }

    private companion object {
        const val WARMUP = 5
        const val RUNS = 15
    }
}
