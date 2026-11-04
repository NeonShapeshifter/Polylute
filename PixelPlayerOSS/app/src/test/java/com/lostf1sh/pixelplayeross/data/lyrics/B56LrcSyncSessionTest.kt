package com.lostf1sh.pixelplayeross.data.lyrics

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.utils.LyricsUtils
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Test

/** B56 phase 3.2 (#31): the rules of the lyrics sync editor, with no screen. */
class B56LrcSyncSessionTest {
    private val text = listOf("Hola", "", "  Adiós  ", "Hasta luego")

    private fun plain() = LrcSyncState.forPlain(text)

    @Test
    fun `plain lines open the editor with every non blank line unstamped and the cursor on the first`() {
        val state = plain()

        assertThat(state.lines.map { it.text }).containsExactly("Hola", "Adiós", "Hasta luego").inOrder()
        assertThat(state.times).containsExactly(null, null, null)
        assertThat(state.cursor).isEqualTo(0)
        assertThat(state.toLrc()).isNull()
    }

    @Test
    fun `stamping puts the position on the next line and moves on`() {
        val state = plain().stamp(1_000).stamp(4_520)

        assertThat(state.times).containsExactly(1_000, 4_520, null).inOrder()
        assertThat(state.cursor).isEqualTo(2)
        assertThat(state.selected).isEqualTo(1)
    }

    @Test
    fun `a stamp earlier than the line before is raised to it so the file keeps the text order`() {
        val state = plain().stamp(5_000).stamp(3_000)

        assertThat(state.times).containsExactly(5_000, 5_000, null).inOrder()
    }

    @Test
    fun `stamp to LRC text and back, with a line past ten minutes and one past an hour`() {
        val state = LrcSyncState.forPlain(listOf("one", "two", "three", "four"))
            .stamp(1_240).stamp(65_030).stamp(12 * 60_000L + 3_450).stamp(101 * 60_000L + 7_005)

        val lrc = state.toLrc()!!

        assertThat(lrc.lines()).containsExactly("[00:01.24]one", "[01:05.03]two", "[12:03.45]three", "[101:07.00]four").inOrder()
        val back = LyricsUtils.parseLyrics(lrc).synced!!
        assertThat(back.map { it.line }).containsExactly("one", "two", "three", "four").inOrder()
        assertThat(back.map { it.time }).containsExactly(1_240, 65_030, 723_450, 6_067_000).inOrder()     // to the hundredth: 7_005 ms is written as 7.00 s
    }

    @Test
    fun `a half stamped song is not a file`() {
        assertThat(plain().stamp(1_000).toLrc()).isNull()
        assertThat(plain().stamp(1_000).isComplete).isFalse()
        assertThat(plain().stamp(1).stamp(2).stamp(3).isComplete).isTrue()
    }

    @Test
    fun `undo last clears the last stamp and puts the cursor back on it`() {
        val state = plain().stamp(1_000).stamp(2_000).undoLast()

        assertThat(state.times).containsExactly(1_000, null, null).inOrder()
        assertThat(state.cursor).isEqualTo(1)
        assertThat(state.undoLast().times).containsExactly(null, null, null)
        assertThat(state.undoLast().undoLast().cursor).isEqualTo(0)                       // nothing left to undo: it stays
    }

    @Test
    fun `undo then stamp again replaces the stamp`() {
        val state = plain().stamp(1_000).stamp(2_000).undoLast().stamp(2_600)

        assertThat(state.times).containsExactly(1_000, 2_600, null).inOrder()
    }

    @Test
    fun `a nudge moves the selected line by a tenth of a second and stops at its neighbours`() {
        val state = plain().stamp(1_000).stamp(1_150).stamp(5_000)

        assertThat(state.nudge(+100, index = 1).times[1]).isEqualTo(1_250)
        assertThat(state.nudge(-100, index = 1).times[1]).isEqualTo(1_050)
        assertThat(state.nudge(-100, index = 1).nudge(-100, index = 1).times[1]).isEqualTo(1_000)     // not before the line above
        assertThat(state.nudge(+100_000, index = 1).times[1]).isEqualTo(5_000)                       // not past the line below
        assertThat(state.nudge(-100, index = 0).nudge(-100, index = 0).times[0]).isEqualTo(800)
        assertThat(plain().stamp(40).nudge(-100, index = 0).times[0]).isEqualTo(0)                   // never below zero
    }

    @Test
    fun `a nudge uses the selected line, and a line with no stamp cannot be nudged`() {
        val state = plain().stamp(1_000).stamp(2_000)

        assertThat(state.select(0).nudge(+100).times).containsExactly(1_100, 2_000, null).inOrder()
        assertThat(state.select(2).nudge(+100)).isEqualTo(state.select(2))
    }

    @Test
    fun `leaving an editor with changes asks, a clean one leaves, and saving makes it clean again`() {
        val opened = plain()
        assertThat(opened.leave()).isEqualTo(SyncLeave.LEAVE)

        val stamped = opened.stamp(1_000)
        assertThat(stamped.isDirty).isTrue()
        assertThat(stamped.leave()).isEqualTo(SyncLeave.ASK)

        val full = stamped.stamp(2_000).stamp(3_000)
        assertThat(full.saved().leave()).isEqualTo(SyncLeave.LEAVE)
        assertThat(full.saved().nudge(+100, index = 2).leave()).isEqualTo(SyncLeave.ASK)
        assertThat(stamped.undoLast().leave()).isEqualTo(SyncLeave.LEAVE)                  // back to what was opened: nothing to lose
    }

    @Test
    fun `lyrics with wrong timing open on their lines and times, and stamping again raises the later ones`() {
        val timed = LrcSyncState.forTimed(listOf(SyncedLine(1_000, "a"), SyncedLine(2_000, "b"), SyncedLine(3_000, "c")))
        assertThat(timed.isComplete).isTrue()
        assertThat(timed.isDirty).isFalse()
        assertThat(timed.cursor).isEqualTo(0)

        val restamped = timed.stamp(2_500)

        assertThat(restamped.times).containsExactly(2_500, 2_500, 3_000).inOrder()           // "b" is raised until its own turn
        assertThat(restamped.isDirty).isTrue()
    }

    @Test
    fun `the editor for a song is chosen from what it has`() {
        assertThat(LrcSyncState.forLyrics(null)).isNull()
        assertThat(LrcSyncState.forLyrics(Lyrics())).isNull()
        assertThat(LrcSyncState.forLyrics(Lyrics(plain = listOf("x")))!!.times).containsExactly(null)
        assertThat(LrcSyncState.forLyrics(Lyrics(synced = listOf(SyncedLine(900, "x"))))!!.times).containsExactly(900)
    }

    @Test
    fun `the saved file is read back as synced lyrics with the same lines and times`() {
        val dir = Files.createTempDirectory("b56-sync").toFile()
        try {
            val state = LrcSyncState.forPlain(listOf("uno", "dos", "tres")).stamp(2_000).stamp(6_500).stamp(11_250)
            val file = File(dir, "Song.lrc").apply { writeText(state.toLrc()!!, Charsets.UTF_8) }

            val read = LyricsUtils.parseLyrics(file.readText(Charsets.UTF_8))

            assertThat(read.synced).isNotEmpty()
            assertThat(read.synced!!.map { it.line to it.time }).containsExactly("uno" to 2_000, "dos" to 6_500, "tres" to 11_250).inOrder()
            assertThat(state.toLyrics()!!.synced!!.map { it.time }).isEqualTo(read.synced!!.map { it.time })
        } finally {
            dir.deleteRecursively()
        }
    }
}
