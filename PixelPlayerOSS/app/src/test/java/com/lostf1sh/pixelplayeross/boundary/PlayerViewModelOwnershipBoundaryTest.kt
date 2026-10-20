package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * One playback ViewModel, and holders that outlive it.
 *
 * Two habits were quietly breaking playback state, and neither showed up as a compile error:
 *
 * 1. `playerViewModel: PlayerViewModel = hiltViewModel()` on a composable that a NavHost
 *    destination renders. `LocalViewModelStoreOwner` there is the back-stack entry, not the
 *    Activity, so the default silently built a *second* playback ViewModel. When that entry was
 *    popped, its `onCleared` ran against the shared `@Singleton` holders.
 * 2. Those holders took their `CoroutineScope` from that ViewModel. Clearing it therefore stopped
 *    the position sampler, the seek bar, lyric sync and Shuffle for the rest of the process,
 *    while Repeat kept working because it needed no scope — which is why the failure looked like
 *    several unrelated bugs.
 *
 * The fixes are structural, so the guards here are negative and source-level, in the style of the
 * other boundary tests: the pattern must not be able to come back without a red test. These are
 * pure-JVM and read files rather than starting Android.
 */
class PlayerViewModelOwnershipBoundaryTest {

    private object Project {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("Could not locate the app module from ${File("").absolutePath}")

        fun text(relative: String): String {
            val file = File(root, relative)
            check(file.isFile) { "Missing expected file: $relative" }
            return file.readText().replace("\r\n", "\n")
        }

        fun mainSources(): List<File> =
            File(root, "src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
    }

    private val playbackHolder = "src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlaybackStateHolder.kt"
    private val lyricsHolder = "src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/LyricsStateHolder.kt"
    private val playerViewModel = "src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlayerViewModel.kt"

    // --- 1. Nothing builds a second playback ViewModel ---------------------------------------

    @Test
    fun `no composable defaults to constructing its own playback ViewModel`() {
        val offenders = Project.mainSources().filter { file ->
            val text = file.readText()
            text.contains("PlayerViewModel = hiltViewModel()") ||
                text.contains("PlayerViewModel = hiltViewModel(),")
        }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    @Test
    fun `the routes known to have built a second playback ViewModel pass the shared one`() {
        // These are the two that actually did it: a playlist destination reaching the song picker,
        // and the player overlay reaching the queue sheet.
        val playlistDetail =
            Project.text("src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/PlaylistDetailScreen.kt")
        assertThat(playlistDetail).contains("playerViewModel = playerViewModel,")

        // The queue sheet's own `viewModel = ` argument now lives inside the queue surface
        // boundary (Phase 14.0X2 moved the queue's hosting out of the overlay scene), so this
        // guard follows it and checks both hops rather than one: the scene must hand the shared
        // ViewModel to the boundary, and the boundary must hand it to the sheet. A second
        // playback ViewModel introduced at either hop still fails this test.
        val overlays =
            Project.text("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/UnifiedPlayerOverlaysLayer.kt")
        assertThat(overlays).contains("playerViewModel = playerViewModel,")

        val queueSurface =
            Project.text("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/QueueSurface.kt")
        assertThat(queueSurface).contains("viewModel = playerViewModel,")
    }

    // --- 2. The holders own their lifetime -----------------------------------------------------

    @Test
    fun `both singleton holders take an application scope`() {
        listOf(playbackHolder, lyricsHolder).forEach { path ->
            val text = Project.text(path)
            // An application-lifetime scope from the DI graph, not one handed in by a ViewModel.
            // @MainAppScope is the main-dispatcher variant: MediaController enforces main-thread
            // affinity, so the sampler cannot run on the IO scope.
            assertThat(text).contains("@MainAppScope")
            assertThat(text).contains("CoroutineScope")
        }
    }

    @Test
    fun `neither holder borrows a ViewModel scope`() {
        listOf(playbackHolder, lyricsHolder).forEach { path ->
            assertThat(Project.text(path)).doesNotContain("viewModelScope")
        }
    }

    @Test
    fun `the playback ViewModel no longer hands its own scope to a holder`() {
        val text = Project.text(playerViewModel)

        // The two singleton holders specifically, not every holder the ViewModel initialises.
        assertThat(text).doesNotContain("playbackStateHolder.initialize(viewModelScope")
        assertThat(text).doesNotContain("lyricsStateHolder.initialize(viewModelScope")
        assertThat(text).doesNotContain("initialize(coroutineScope = viewModelScope)")
        assertThat(text).contains("playbackStateHolder.initialize()")
    }

    // --- 3. The ownership contract is gone, not merely unused ----------------------------------

    @Test
    fun `neither holder exposes a ViewModel-scope ownership contract`() {
        listOf(playbackHolder, lyricsHolder).forEach { path ->
            val text = Project.text(path)
            assertThat(text).doesNotContain("scope = null")
            assertThat(text).doesNotContain("var scope: CoroutineScope?")
            assertThat(text).doesNotContain("fun onCleared()")
        }
    }

    @Test
    fun `the playback ViewModel no longer clears the singleton holders`() {
        val text = Project.text(playerViewModel)

        assertThat(text).doesNotContain("playbackStateHolder.onCleared()")
        assertThat(text).doesNotContain("lyricsStateHolder.onCleared()")
    }
}
