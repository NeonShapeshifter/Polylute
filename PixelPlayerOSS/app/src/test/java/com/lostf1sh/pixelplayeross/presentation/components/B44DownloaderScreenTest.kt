package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.offline.*
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B44DownloaderScreenTest {
    @get:Rule val compose = createComposeRule()
    private val libraryReference = mutableStateOf(false)
    private val paused = mutableStateOf(true)
    private val calls = mutableListOf<String>()
    private val artists = (0 until 700).map { InfoArtist("artist-$it", "Artist $it") }
    private val pages = flowOf(PagingData.from(artists))
    private val rows = artists.associate { artist ->
        artist.artistKey to (0 until 3).flatMap { album ->
            listOf(InfoChoice("${artist.artistKey}-$album", "COVER", "Album $album", artist.artistKey,
                "album-$album", "Album $album", "AVAILABLE", null, false, false, "IDLE"))
        }
    }
    private fun show() {
        compose.setContent {
            val items = pages.collectAsLazyPagingItems()
            val density = LocalDensity.current
            com.lostf1sh.pixelplayeross.ui.ds.DapTheme(darkTheme = true) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                if (libraryReference.value) {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(700) { index ->
                            com.lostf1sh.pixelplayeross.ui.ds.DapArtistRow("Artist $index", "3 albums")
                        }
                    }
                } else MusicInfoDownloadsContent(items, "", {}, InfoQueue(paused = paused.value), InfoProgress(3500, 3500, 0),
                    InfoProgress(10, 2, 0), InfoSelection(0, 0, null, 0), false, false,
                    { flowOf(rows.getValue(it)) }, {}, {}, {}, { paused.value = true }, {
                        calls += "resume"
                        paused.value = false
                    },
                    { _, _ -> }, { _, _ -> }, {})
            }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Choose by artist or album").performClick()
        compose.waitForIdle()
    }
    @Test fun `a reopened paused queue has resume without entering another mode`() {
        show()
        compose.onNodeWithText("Resume").assertIsDisplayed().performClick()
        assertThat(calls).containsExactly("resume")
        compose.onNodeWithText("Pause").assertIsDisplayed()
        compose.onNodeWithText("Download selected").assertDoesNotExist()
    }
    private fun medianPaintMs(): Double {
        val durations = mutableListOf<Long>()
        compose.runOnIdle {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
            val view = activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            repeat(23) { index ->
                val started = System.nanoTime()
                view.draw(canvas)
                if (index >= 3) durations += System.nanoTime() - started
            }
            bitmap.recycle()
        }
        return durations.sorted()[durations.size / 2] / 1_000_000.0
    }

    @Test fun `native host frame paint for seven hundred artists stays within the Library budget`() {
        show()
        val catalog = medianPaintMs()
        compose.runOnIdle { libraryReference.value = true }
        compose.waitForIdle()
        val library = medianPaintMs()
        println("B44 native host frame paint median: downloader=$catalog ms Library artist rows=$library ms; 700 artists x 3 albums, 60 Hz budget=16.67 ms")
        assertThat(catalog).isAtMost(16.67)
        assertThat(catalog).isAtMost(library * 2 + 2)
    }

    @Test fun `seven hundred artists with three albums compose only the visible page`() {
        show()
        val composed = compose.onAllNodes(hasText("Artist", substring = true)).fetchSemanticsNodes().size
        assertThat(composed).isAtMost(12)
        compose.onNodeWithText("Artist 0", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Album 0", useUnmergedTree = true).assertExists()
        // Visible composition stays bounded independently of catalog size; paint time is measured separately.
        println("B44 frame composition budget: $composed visible artist headers for 700 x 3 albums; limit 12, 12-header viewport budget")
        val bitmap = com.lostf1sh.pixelplayeross.ui.glass.B42Renders.screen(compose)
        val file = File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B44-fix1-evidence/regressions/downloader.png")
        file.parentFile.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
