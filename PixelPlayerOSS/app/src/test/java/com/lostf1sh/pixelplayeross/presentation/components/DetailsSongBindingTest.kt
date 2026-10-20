package com.lostf1sh.pixelplayeross.presentation.components

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.scoped.SongInfoEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Which song an overlay describes, by the entry point it was opened from.
 *
 * This is the rule the live-rebind fix turns on. The details were going stale because both entries
 * took the song captured at open time; the player's DETAILS sibling has to follow playback instead,
 * while a song's action menu has to stay on the song it was opened for.
 */
class DetailsSongBindingTest {

    private fun song(id: String) = Song.emptySong().copy(id = id, title = "Track $id")

    private val songA = song("A")
    private val songB = song("B")
    private val songC = song("C")

    @Test
    fun `the player's details follow playback from one track to the next`() {
        // Opened on A, then the player moves on — twice — with the sheet still open.
        assertEquals(
            songA,
            describedSongFor(SongInfoEntry.DETAILS, playingSong = songA, selectedSong = songA),
        )
        assertEquals(
            songB,
            describedSongFor(SongInfoEntry.DETAILS, playingSong = songB, selectedSong = songA),
        )
        assertEquals(
            songC,
            describedSongFor(SongInfoEntry.DETAILS, playingSong = songC, selectedSong = songA),
        )
    }

    @Test
    fun `the described song moves exactly when the playing song moves`() {
        // What makes closing and reopening unnecessary: for the player's DETAILS entry the answer
        // is a function of what is playing, and of nothing else — not of the song captured when
        // the sheet was opened.
        val opened = describedSongFor(SongInfoEntry.DETAILS, songA, selectedSong = songA)
        val afterSkip = describedSongFor(SongInfoEntry.DETAILS, songB, selectedSong = songA)

        assertNotEquals(opened, afterSkip)
        assertEquals(songB, afterSkip)
    }

    @Test
    fun `a song's action menu stays on the song it was opened for`() {
        // The menu's rows act on one track, so the details reached from it describe that track —
        // even once the player has moved on to something else.
        assertEquals(
            songA,
            describedSongFor(SongInfoEntry.ACTIONS, playingSong = songA, selectedSong = songA),
        )
        assertEquals(
            songA,
            describedSongFor(SongInfoEntry.ACTIONS, playingSong = songB, selectedSong = songA),
        )
        assertEquals(
            songA,
            describedSongFor(SongInfoEntry.ACTIONS, playingSong = songC, selectedSong = songA),
        )
    }

    @Test
    fun `details opened with nothing playing still describe the selected song`() {
        assertEquals(
            songA,
            describedSongFor(SongInfoEntry.DETAILS, playingSong = null, selectedSong = songA),
        )
        assertEquals(
            songA,
            describedSongFor(SongInfoEntry.ACTIONS, playingSong = null, selectedSong = songA),
        )
    }
}
