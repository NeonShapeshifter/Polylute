package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B24: where the changes are wired, read at the source. What each one does is measured by its own test (`PlayerViewModelTest` shuffle
 * block, `ShuffleAllQueueTest`, `LibrarySongChangeRecompositionTest`, `MiniPlayerSongChangeTest`, `ForegroundCatchUpTest`,
 * `usb-glitch-test.cpp` and the native simulation); this pins that the real code goes through them and that what the batch must not
 * touch is where it was.
 */
class B24WiringBoundaryTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")
    private val jni = File("../../decent-player/libs/decent-usb-audio-driver/src/main/jni")

    private fun text(base: File, path: String) = File(base, path).readText().replace("\r\n", "\n")

    /** The source without its comments: what a scan for a call should read (a comment may name what it explains). */
    private fun code(source: String) = source.lines()
        .map { it.substringBefore("//") }
        .filterNot { it.trim().let { line -> line.startsWith("*") || line.startsWith("/*") } }
        .joinToString("\n")

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val indent = source.substring(source.lastIndexOf('\n', start) + 1, start).takeWhile { it == ' ' }
        return source.substring(start, source.indexOf("\n$indent}\n", start))
    }

    /** A function written as one expression has no closing brace: it runs to the next blank line. */
    private fun expression(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        return source.substring(start, source.indexOf("\n\n", start))
    }

    private val viewModel get() = code(text(main, "presentation/viewmodel/PlayerViewModel.kt"))

    // ── Q1: a queue is always its source list ────────────────────────────────────────────────────

    @Test
    fun `no button loads a random sample of the library any more`() {
        assertThat(viewModel).doesNotContain("getRandomSongs")
        assertThat(viewModel).doesNotContain("fun playRandomSong")
        for (path in listOf("data/repository/MusicRepository.kt", "data/repository/MusicRepositoryImpl.kt", "data/database/MusicDao.kt")) {
            val source = code(text(main, path))
            assertThat(source).doesNotContain("getRandomSongs")
            assertThat(source).doesNotContain("RANDOM()")
        }
    }

    @Test
    fun `shuffle all, the tile and Liked shuffle all go through the one function that loads the list as listed`() {
        val shuffleSorted = body(viewModel, "private fun shuffleSortedList(")
        // resolve every id in the order the query gave them, then hand the whole list to the shuffle that loads it as it is
        assertThat(shuffleSorted).contains("resolvePlaybackQueueFromSortedIds(sortedIds)")
        assertThat(shuffleSorted).contains("playSongsShuffled(songs, queueName, startAtZero = true, source = source, timeline = timeline)")
        assertThat(shuffleSorted).doesNotContain("take(")
        assertThat(shuffleSorted).doesNotContain("shuffled(")
        assertThat(body(viewModel, "fun shuffleAllSongs(")).contains("shuffleSortedList(")
        assertThat(body(viewModel, "fun shuffleAllSongs(")).contains("songIdsInLibraryOrder()")
        assertThat(body(viewModel, "fun triggerShuffleAllFromTile(")).contains("shuffleSortedList(")
        assertThat(body(viewModel, "fun triggerShuffleAllFromTile(")).contains("songIdsInLibraryOrder()")
        assertThat(body(viewModel, "fun triggerShuffleAllFromTile(")).contains("libraryStateHolder.awaitSelectionLoaded()")
        assertThat(body(viewModel, "fun shuffleFavoriteSongs(")).contains("songIdsInLikedOrder()")
        assertThat(viewModel).doesNotContain("getFavoriteSongsOnce")
    }

    @Test
    fun `there is one definition of the Songs list and one of the Liked list, and every play and shuffle of them asks it`() {
        assertThat(viewModel.split("musicRepository.getSongIdsSorted(").size - 1).isEqualTo(1)
        assertThat(viewModel.split("musicRepository.getFavoriteSongIdsSorted(").size - 1).isEqualTo(1)
        val library = expression(viewModel, "private suspend fun songIdsInLibraryOrder()")
        assertThat(library).contains("libraryStateHolder.currentSongSortOption.value")
        assertThat(library).contains("libraryStateHolder.currentStorageFilter.value")
        val liked = expression(viewModel, "private suspend fun songIdsInLikedOrder()")
        assertThat(liked).contains("libraryStateHolder.currentFavoriteSortOption.value")
        assertThat(liked).contains("libraryStateHolder.currentStorageFilter.value")
        // a tap on a row, Play from the top, the locate button and the selection all use them too
        assertThat(viewModel.split("songIdsInLibraryOrder()").size - 1).isAtLeast(6)
        assertThat(viewModel.split("songIdsInLikedOrder()").size - 1).isAtLeast(4)
    }

    @Test
    fun `shuffle loads the list it was given as it is and only the player's order is shuffled`() {
        val shuffled = body(viewModel, "fun playSongsShuffled(")
        // B31 P2.2: the draw is dealt as list indices and the player's play order is taken from them; the list itself is still loaded as given.
        assertThat(shuffled).contains("internalPlaySongs(\n                songsToPlay, deal.startSong, queueName, playlistId,")
        assertThat(shuffled).contains("shuffle = true,")
        assertThat(shuffled).contains("playOrder = deal.order,")
    }

    @Test
    fun `an album and an artist are shuffled and played in the order their page lists them`() {
        assertThat(body(viewModel, "fun shuffleRandomAlbum(")).contains("SourceOrder.albumPage(")
        assertThat(body(viewModel, "fun shuffleRandomArtist(")).contains("SourceOrder.artistPage(")
        assertThat(body(viewModel, "fun playAlbum(")).contains("SourceOrder.albumPage(")
        assertThat(body(viewModel, "fun playArtist(")).contains("SourceOrder.artistPage(")
        // the pages themselves use the same definitions
        assertThat(code(text(main, "presentation/viewmodel/AlbumDetailViewModel.kt"))).contains("SourceOrder.albumPage(songs)")
        assertThat(code(text(main, "presentation/viewmodel/ArtistDetailViewModel.kt"))).contains("private val songDisplayComparator = SourceOrder.albumPage")
    }

    @Test
    fun `the genre shuffle shuffles, and the Library's shuffle buttons still ask for these functions`() {
        val genre = code(text(main, "presentation/screens/GenreDetailScreen.kt"))
        assertThat(genre).contains("playerViewModel.playSongsShuffled(")
        // B33-U4: the page lists one list, and Play, Shuffle and a row all take it
        assertThat(genre).contains("val songs = uiState.songs")
        assertThat(genre).contains("songsToPlay = songs,")
        assertThat(genre).doesNotContain(".random()")
        val library = code(text(main, "presentation/screens/LibraryScreen.kt"))
        assertThat(library.split("playerViewModel.shuffleAllSongs()").size - 1).isEqualTo(2)
        assertThat(library.split("playerViewModel.shuffleFavoriteSongs()").size - 1).isEqualTo(2)
    }

    // ── Q2 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `composition tracing is a debug-only dependency, with the native library of the same version`() {
        val gradle = code(File("build.gradle.kts").readText())
        assertThat(gradle).contains("debugImplementation(libs.androidx.compose.runtime.tracing)")
        assertThat(gradle).contains("debugImplementation(libs.androidx.tracing.perfetto.binary)")
        // nowhere else: not in the main dependencies, not in release or benchmark
        assertThat(gradle.split("compose.runtime.tracing").size - 1).isEqualTo(1)
        assertThat(gradle.split("perfetto").size - 1).isEqualTo(1)
        val catalog = File("../gradle/libs.versions.toml").readText()
        assertThat(catalog).contains("tracingPerfetto = \"1.0.1\"")
        assertThat(catalog).contains("androidx-tracing-perfetto-binary = { group = \"androidx.tracing\", name = \"tracing-perfetto-binary\", version.ref = \"tracingPerfetto\" }")
        // the Java side of tracing-perfetto 1.0.1 refuses a native library of another version
        assertThat(text(File("src/main"), "AndroidManifest.xml")).doesNotContain("perfetto")
    }

    @Test
    fun `the song lists do not read the playing song in their body, and do not scan their rows for it`() {
        for (path in listOf("presentation/screens/LibrarySongsTab.kt", "presentation/screens/LibrarySongsAndFavoritesTabs.kt")) {
            val source = code(text(main, path))
            assertThat(source).doesNotContain("itemSnapshotList")
            assertThat(source).doesNotContain("currentSongListIndex")
            assertThat(source).contains("LocateCurrentSongEffects(")
            // the id is a State handed to the effects, never `by`-read in the list's own body
            assertThat(source).contains("val currentSongId = rememberCurrentSongIdState(playerViewModel)")
            assertThat(source).doesNotContain("val currentSongId by")
        }
        val effects = code(text(main, "presentation/screens/LibraryLocateCurrentSong.kt"))
        assertThat(effects).contains("snapshotFlow {")
        assertThat(effects).contains("it.key == id")
        assertThat(effects).contains("remember(hasCurrentSong)")
        assertThat(effects).doesNotContain("remember(currentSongId.value)")
    }

    @Test
    fun `a tap on a row compares the queue off the main thread and writes no state of its own`() {
        val reuse = body(viewModel, "private suspend fun reuseLoadedQueueIfSameList(")
        assertThat(reuse).contains("withContext(Dispatchers.Default)")
        assertThat(reuse.indexOf("LoadedQueueReuse.sameList(")).isGreaterThan(reuse.indexOf("withContext(Dispatchers.Default)"))
        assertThat(reuse).contains("timedSection(\"PlayerViewModel.tapReuseSeek\")")
        // what a tap writes to the view model's state flows: nothing, apart from what the controller reports back
        assertThat(reuse).doesNotContain("_playerUiState.update")
        assertThat(reuse).doesNotContain("_playerUiState.value =")
        assertThat(reuse).doesNotContain("updateStablePlayerState")
        assertThat(reuse).doesNotContain(".emit(")
        // still the same decision as before: a stale queue is never acted on
        assertThat(reuse).contains("if (queue !== _playerUiState.value.currentPlaybackQueue) return false")
    }

    // ── Q3 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the settle gate restarts its wait on every request, keeps one action and is capped`() {
        val gate = code(text(main, "data/worker/ForegroundSettleGate.kt"))
        val request = body(gate, "    fun request(action: suspend () -> Unit) {")
        assertThat(request).contains("if (waiting) pending?.cancel() else firstRequestAt = at")
        assertThat(request).contains("latest = action")
        assertThat(request).contains("minOf(settleMs, maxOf(0L, firstRequestAt + maxDeferMs - at))")
        assertThat(gate).contains("const val FOREGROUND_SETTLE_MAX_MS = 60_000L")
    }

    @Test
    fun `the writer's scheduler counters are read for the log line only, off the PCM path and outside the reap`() {
        val output = text(jni, "usb-audio-output.cpp")
        // the reap and the packet plan are as they were
        assertThat(body(output, "static int reapOldestUrb(")).doesNotContain("sched")
        assertThat(body(output, "static int reapOldestUrb(")).doesNotContain("outputGap")
        assertThat(body(output, "static bool submitPcmToUrbsImpl(")).doesNotContain("schedstat")
        assertThat(body(output, "static int submitRingUrb(")).doesNotContain("schedstat")
        // the one place that reads them, and how: through a descriptor, one small pread, never per URB
        assertThat(output.split("pread(").size - 1).isEqualTo(1)
        assertThat(output.split("readWriterSchedstat(ctx, &sched)").size - 1).isEqualTo(2)
        val observe = body(output, "static void observeOutputGap(")
        assertThat(observe).contains("usbSchedBaselineDue(&ctx->schedBaseline, nowUs)")
        assertThat(output).contains("open(\"/proc/thread-self/schedstat\", O_RDONLY | O_CLOEXEC)")
        assertThat(output.split("close(ctx->schedstatFd)").size - 1).isEqualTo(2)   // when another thread takes over, and at destroy
        assertThat(text(jni, "usb-glitch.h")).contains("#define USB_SCHED_BASELINE_EVERY_US 10000")
        // nothing about what is sent changed: the ring is as deep as it was
        assertThat(text(jni, "usb-audio-output.h")).contains("#define USB_AUDIO_NUM_URBS 80")
    }

    @Test
    fun `the OUTPUT_GAP comments say elapsed time and estimate, and no longer claim a stall`() {
        val header = text(jni, "usb-glitch.h")
        assertThat(header).contains("none proves a USB completion stall")
        assertThat(header).contains("`dry` is the model's estimate")
        assertThat(header).contains("elapsed around the reap call")
        val output = text(jni, "usb-audio-output.cpp")
        assertThat(output).contains("scheduling included, so it does not by itself show a USB completion stall")
        assertThat(output).doesNotContain("far longer is a host stall")
    }
}
