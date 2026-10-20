package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * What the live-rebind fix is allowed to touch.
 *
 * The details now follow playback, which means one more thing in that view can change while it is
 * open. These assertions pin the parts that must not: the runtime account of how the track is being
 * played keeps its existing authority, the list-hosted details stay on the song they were opened
 * for, and every song-scoped read is guarded rather than merely cancelled.
 */
class TrackDetailsLiveRebindBoundaryTest {

    private fun source(relative: String) =
        root.resolve("src/main/java/com/lostf1sh/pixelplayeross/$relative").readText()

    private val viewModel get() = source("presentation/viewmodel/TrackDetailsViewModel.kt")
    private val overlays get() = source("presentation/components/UnifiedPlayerOverlaysLayer.kt")
    private val listHost get() = source("presentation/components/player/TrackDetailsHost.kt")

    /** The body of a top-level function or property, from its name to the next blank-line brace. */
    private fun bodyOf(text: String, name: String): String {
        val start = text.indexOf("$name(")
        assertThat(start).isAtLeast(0)
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
        }
        return text.substring(open)
    }

    @Test
    fun `output and processing keep their runtime authority`() {
        // How the track is being played is a property of the runtime, not of the song. The song
        // changing must not disturb it, and the song-scoped read must not write it.
        assertThat(viewModel).contains("dualPlayerEngine.audioRouteState")
        assertThat(viewModel).contains("headphoneEqualizerCoordinator.effectiveStateFlow")
        assertThat(viewModel).contains("dualPlayerEngine.replayGainRuntimeState")

        listOf("loadTechnical", "loadWaveform", "loadMusicBrainzIds").forEach { loader ->
            val body = bodyOf(viewModel, "private fun $loader")
            assertThat(body).doesNotContain("output =")
            assertThat(body).doesNotContain("equalizer =")
            assertThat(body).doesNotContain("replayGain =")
        }
    }

    @Test
    fun `every song-scoped read is guarded, not merely cancelled`() {
        // Cancelling a job does not stop work that has already finished, so each of the three reads
        // checks the song it was started for before it writes.
        listOf("loadTechnical", "loadWaveform", "loadMusicBrainzIds").forEach { loader ->
            val body = bodyOf(viewModel, "private fun $loader")
            assertThat(body).contains("isSongScopedResultCurrent(state.song?.id, song.id)")
        }
    }

    @Test
    fun `the player's details are bound to the described song, not the captured one`() {
        // The fix: the song the overlay describes is resolved from the entry point, and the sheet is
        // bound to that — never to the song captured when the overlay opened.
        assertThat(overlays).contains("describedSongFor(")
        assertThat(overlays).contains("entry = songInfoEntry")
        assertThat(overlays).contains("trackDetailsViewModel.bind(")
        assertThat(overlays).contains("song = liveSong")
    }

    @Test
    fun `list-hosted details stay on the song they were opened for`() {
        // The other half of the rule. A details view opened for a song from a list describes that
        // song and does not follow playback, so it never asks the player which song is playing.
        //
        // It does read the player for a *reading* — and for the duration that reading carries — but
        // a reading only exists for the track the player is actually rendering, so a list-opened
        // view simply gets none rather than borrowing the playing track's numbers.
        assertThat(listHost).contains("trackDetailsViewModel.bind(song = song")
        assertThat(listHost).contains("LiveAudioFormat.from(")
        assertThat(listHost).doesNotContain("currentSong")
        assertThat(listHost).doesNotContain("describedSongFor")
    }

    @Test
    fun `the reading is still only ever produced for the song it describes`() {
        // The invariant the fix depends on and must not weaken: a reading carries the song it was
        // taken for, and one for another track is refused rather than borrowed.
        assertThat(viewModel).contains("if (metadata.mediaId != songId) return null")
        assertThat(viewModel).contains("live?.takeIf { it.songId == song.id }")
    }

    private companion object {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
    }
}
