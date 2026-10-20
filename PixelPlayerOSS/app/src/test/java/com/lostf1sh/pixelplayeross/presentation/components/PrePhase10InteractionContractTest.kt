package com.lostf1sh.pixelplayeross.presentation.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The contracts the pre-Phase-10 interaction correctness pass has to keep.
 *
 * These are source assertions rather than UI tests, for the same reason [LyricsChromeContractTest]
 * is: the claims are about *which* gesture owner is attached to which surface, *which* callback a
 * control is wired to, and whether a second authority still exists. A Compose test would need the
 * whole player graph, a live media session and a real system back gesture to ask the same
 * questions.
 *
 * A path that cannot be found fails loudly rather than passing vacuously.
 */
class PrePhase10InteractionContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app", relative),
            File("src/main/java/com/lostf1sh/pixelplayeross/$relative"),
            File("app/src/main/java/com/lostf1sh/pixelplayeross/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    private val edgeGesture by lazy { source("presentation/components/scoped/SystemBackEdgeGesture.kt") }
    private val lyricsSheet by lazy { source("presentation/components/LyricsSheet.kt") }
    private val trackDetails by lazy { source("presentation/components/player/TrackDetailsSheet.kt") }
    // B21: the skip dispatch moved out of FullPlayerContent with the rest of the surfaces; every file of the player is read.
    private val fullPlayer by lazy {
        listOf(
            "FullPlayerContent", "FullPlayerScreen", "PlayerSkipCoordinator", "PlayerCoverSurface", "PlayerTrackInfoSurface",
            "PlayerProgressSurface", "PlayerControlsSurface", "PlayerLyricsEntry",
        ).joinToString("\n") { source("presentation/components/player/$it.kt") }
    }
    private val libraryScreen by lazy { source("presentation/screens/LibraryScreen.kt") }
    private val playerViewModel by lazy { source("presentation/viewmodel/PlayerViewModel.kt") }
    private val songInfoSheet by lazy { source("presentation/components/SongInfoBottomSheet.kt") }
    private val settingsRows by lazy { source("ui/ds/DapSettingsRows.kt") }
    private val editSongSheet by lazy { source("presentation/components/EditSongSheet.kt") }

    // --- 1/2: the reserved Back strip has exactly one owner on each colliding surface -------------

    @Test
    fun `the edge strip is claimed only through the shared mechanism`() {
        // Guard against a vacuous pass: the mechanism must still exist and still be inset-driven.
        assertThat(edgeGesture).contains("WindowInsets.systemGestures")
        assertThat(edgeGesture).contains("fun Modifier.respectSystemBackEdgesForHorizontalDrag()")
        // No hand-rolled edge width may sneak in beside it.
        assertThat(edgeGesture).contains("coerceIn(20.dp, 48.dp)")
    }

    @Test
    fun `lyrics claims the back edge for horizontal drags, ahead of its own swipe detector`() {
        assertThat(lyricsSheet).contains("respectSystemBackEdgesForHorizontalDrag()")

        // Order is the mechanism: the claim runs in the Initial pass, so it has to be applied
        // outside the detector it is protecting.
        val claim = lyricsSheet.indexOf("respectSystemBackEdgesForHorizontalDrag()")
        val detector = lyricsSheet.indexOf("detectDragGestures(", claim)
        assertThat(claim).isGreaterThan(-1)
        assertThat(detector).isGreaterThan(claim)
    }

    @Test
    fun `lyrics keeps its ordinary centre swipe and its vertical scrolling`() {
        // The swipe detector is still there, still committing prev/next...
        assertThat(lyricsSheet).contains("if (dragOffset > 0) onPrev() else onNext()")
        // ...and the claim is conditional on horizontal intent, which is what lets a vertical
        // drag from the same strip through to the scroller.
        assertThat(edgeGesture).contains("dy >= touchSlop && dy > dx -> break")
    }

    @Test
    fun `track details claims the back edge over the waveform, ahead of its seek detectors`() {
        val claim = trackDetails.indexOf("respectSystemBackEdgesForHorizontalDrag()")
        assertThat(claim).isGreaterThan(-1)

        val tap = trackDetails.indexOf("detectTapGestures", claim)
        val drag = trackDetails.indexOf("detectHorizontalDragGestures", claim)
        assertThat(tap).isGreaterThan(claim)
        assertThat(drag).isGreaterThan(claim)
    }

    @Test
    fun `a waveform tap inside the reserved strip does not seek`() {
        // The drag is handled by the claim; a tap reports its true down position, so it is guarded
        // directly. Both halves of "an edge gesture must not seek" are therefore covered.
        assertThat(trackDetails).contains("if (!backEdge.owns(offset.x, size.width.toFloat()))")
    }

    @Test
    fun `a waveform drag outside the reserved strip still seeks`() {
        assertThat(trackDetails).contains("onDragEnd = {")
        assertThat(trackDetails).contains("scrubFraction?.let(seekToFraction)")
    }

    // --- 3: the skip dispatch path has no pre-dispatch wait ---------------------------------------

    @Test
    fun `the skip collector dispatches before it does anything else`() {
        val collector = fullPlayer.indexOf("LaunchedEffect(skipRequests)")
        assertThat(collector).isGreaterThan(-1)

        val next = fullPlayer.indexOf("SkipDirection.NEXT -> latestOnNext()", collector)
        assertThat(next).isGreaterThan(collector)

        // Nothing may sit between entering the collector and dispatching: no guard, no timer.
        val between = fullPlayer.substring(collector, next)
        assertThat(between).doesNotContain("delay(")
    }

    @Test
    fun `no fixed skip cooldown survives anywhere in the player`() {
        assertThat(fullPlayer).doesNotContain("SKIP_COMMAND_GUARD_MS")
        val collector = fullPlayer.substring(fullPlayer.indexOf("LaunchedEffect(skipRequests)"))
        assertThat(collector.substring(0, collector.indexOf("fun predictSkipCarouselIndex")))
            .doesNotContain("delay(")
    }

    @Test
    fun `rapid skips stay ordered because one collector drains an ordered channel`() {
        // Ordering is a property of the construction, not of a timer: a single collector over a
        // buffered SharedFlow, with non-suspending tryEmit so the tap path never blocks.
        assertThat(fullPlayer).contains("extraBufferCapacity = 16")
        assertThat(fullPlayer).contains("skipRequests.tryEmit(direction)")
        assertThat(fullPlayer).contains("skipRequests.collect { direction ->")
        // Exactly one collector, or two dispatchers would race each other.
        assertThat(fullPlayer.split("skipRequests.collect").size - 1).isEqualTo(1)
    }

    // --- 4: one explicit Play/Shuffle rule ---------------------------------------------------------

    @Test
    fun `play establishes shuffle off and shuffle establishes it on`() {
        val play = playerViewModel.indexOf("fun playSongs(")
        val shuffled = playerViewModel.indexOf("fun playSongsShuffled(")
        assertThat(play).isGreaterThan(-1)
        assertThat(shuffled).isGreaterThan(play)

        // B17-H1: shuffle is the player's own mode now, so the rule is carried by the `shuffle` argument of the load
        // (applied right after the queue is replaced, as a flag) instead of a `copy(isShuffleEnabled = ...)` of the app's state.
        val playBody = playerViewModel.substring(play, shuffled)
        assertThat(playBody).contains("shuffle = if (keepShuffle) null else false")
        // ...but only when the user has asked shuffle to persist (and it was on).
        assertThat(playBody).contains("val keepShuffle = isPersistent && isShuffleOn")

        val shuffleBody = playerViewModel.substring(shuffled, shuffled + 1600)
        assertThat(shuffleBody).contains("shuffle = true")

        val internal = playerViewModel.substringAfter("private suspend fun internalPlaySongs(")
            .substringBefore("private suspend fun buildResolvedPlaybackMediaItem")
        assertThat(internal).contains("shuffle?.let { setShuffleEnabled(it) }")
    }

    @Test
    fun `the rule is stated where it is implemented, not left to be inferred`() {
        val play = playerViewModel.indexOf("fun playSongs(")
        val doc = playerViewModel.substring((play - 1400).coerceAtLeast(0), play)
        assertThat(doc).contains("**Play** starts the requested context")
        assertThat(doc).contains("**Shuffle**")
    }

    @Test
    fun `the library shuffle action no longer presents as a toggle it is not`() {
        assertThat(libraryScreen).doesNotContain("color = if (isShuffleEnabled && !isPlaylistTab)")
        // Its action is unchanged: still a one-shot shuffle of the current tab.
        assertThat(libraryScreen).contains("else -> playerViewModel.shuffleAllSongs()")
    }

    // --- 5: unavailable rows are honest about being unavailable ------------------------------------

    @Test
    fun `an unavailable row is a disabled control, not inert text`() {
        val row = settingsRows.indexOf("fun DapUnavailableRow(")
        assertThat(row).isGreaterThan(-1)
        val body = settingsRows.substring(row, row + 700)
        assertThat(body).contains("semantics { disabled() }")
        // It must not become tappable: the whole point is that it never swallows a tap.
        assertThat(body).contains("onClick = null")
    }

    @Test
    fun `the song actions sheet still routes its two unavailable rows through that row`() {
        assertThat(songInfoSheet).contains("more_album_information")
        assertThat(songInfoSheet).contains("more_artist_information")
        assertThat(songInfoSheet).contains("more_information_unavailable")
        // Any other treatment of these two labels would be a second, divergent rule.
        assertThat(songInfoSheet.split("more_information_unavailable").size - 1).isEqualTo(2)
    }

    // --- 6: Edit Song save cannot run twice --------------------------------------------------------

    @Test
    fun `edit song save is latched and the button reflects the latch`() {
        assertThat(editSongSheet).contains("OneShotAction()")
        assertThat(editSongSheet).contains("saveAction.invoke {")
        assertThat(editSongSheet).contains("enabled = !saveAction.isTaken")
        // The guard is per song, so a different track starts clean.
        assertThat(editSongSheet).contains("remember(song.id) { OneShotAction() }")
    }

    @Test
    fun `the save payload is unchanged by the guard`() {
        // The pass added a latch, not a new write path: the same fields, the same fallbacks.
        assertThat(editSongSheet).contains("trackNumber.toIntOrNull() ?: song.trackNumber")
        assertThat(editSongSheet).contains("editedCoverArt")
    }

    @Test
    fun `cancel stays live while a save is in flight`() {
        val cancel = editSongSheet.indexOf("label = stringResource(R.string.cancel)")
        assertThat(cancel).isGreaterThan(-1)
        val block = editSongSheet.substring(cancel, cancel + 220)
        assertThat(block).doesNotContain("enabled =")
    }
}
