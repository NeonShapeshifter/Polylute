package com.lostf1sh.pixelplayeross.data.lyrics

import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.utils.LyricsUtils

/** One line of the sync editor: its words and the moment it starts, or null while nobody has stamped it. */
data class SyncEditLine(val text: String, val timeMs: Int?)

/** What leaving the editor does: nothing to lose, or the listener is asked first. */
enum class SyncLeave { LEAVE, ASK }

/**
 * B56 phase 3.2 (#31): the state of the lyrics sync editor, as plain data so every rule is a function of it.
 *
 * How it works: the song plays; one big button stamps the playing position on the line the cursor is on and moves the cursor to the next ([stamp]); "undo last" clears the
 * last stamp and puts the cursor back on that line ([undoLast]); a selected line can be moved by a tenth of a second either way ([nudge]). The lines keep the order of
 * their text: a stamp earlier than the line before it is raised to it, and a nudge never goes past a neighbour, so the saved file reads in the order it was written.
 *
 * Nothing here knows about playback or files: the position comes in, the LRC text goes out ([toLrc], written with the same writer as every other `.lrc` the app saves,
 * `[mm:ss.xx]`, minutes past 99 simply have more digits), and [savedTimes] is what the file on disk had when the editor was last in step with it.
 */
data class LrcSyncState(
    val lines: List<SyncEditLine>,
    /** The line the next stamp goes to; [lines].size when every line has been stamped. */
    val cursor: Int = 0,
    val selected: Int? = null,
    val savedTimes: List<Int?> = lines.map { it.timeMs },
) {
    val times: List<Int?> get() = lines.map { it.timeMs }

    val isComplete: Boolean get() = lines.isNotEmpty() && lines.all { it.timeMs != null }

    val stampedCount: Int get() = lines.count { it.timeMs != null }

    /** Whether there is anything to lose: what is on screen differs from what was last saved (or opened). */
    val isDirty: Boolean get() = times != savedTimes

    fun leave(): SyncLeave = if (isDirty) SyncLeave.ASK else SyncLeave.LEAVE

    /** Stamps [positionMs] on the cursor's line and moves on. Nothing to stamp once every line is. */
    fun stamp(positionMs: Long): LrcSyncState {
        if (cursor !in lines.indices) return this
        val floor = lines.take(cursor).lastNotNullTime() ?: 0
        val at = positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(floor)
        // a line that was timed before (the editor opened on timing that is wrong) and now falls before this stamp is raised with it, until its own turn comes
        val later = lines.mapIndexed { i, line ->
            when {
                i == cursor -> line.copy(timeMs = at)
                i > cursor && line.timeMs != null && line.timeMs < at -> line.copy(timeMs = at)
                else -> line
            }
        }
        return copy(lines = later, cursor = cursor + 1, selected = cursor)
    }

    /** Clears the last stamp before the cursor and puts the cursor back on its line. */
    fun undoLast(): LrcSyncState {
        val last = (cursor - 1 downTo 0).firstOrNull { lines[it].timeMs != null } ?: return this
        return copy(lines = lines.replaceTime(last, null), cursor = last, selected = last)
    }

    fun select(index: Int?): LrcSyncState = copy(selected = index?.takeIf { it in lines.indices })

    /** Moves the selected (or given) stamped line by [deltaMs], never before its neighbour, never below 0. */
    fun nudge(deltaMs: Int, index: Int? = selected): LrcSyncState {
        val at = index?.takeIf { it in lines.indices } ?: return this
        val current = lines[at].timeMs ?: return this
        val floor = lines.take(at).lastNotNullTime() ?: 0
        val ceiling = lines.drop(at + 1).firstNotNullTime() ?: Int.MAX_VALUE
        val moved = (current + deltaMs).coerceIn(floor, ceiling)
        return if (moved == current) this else copy(lines = lines.replaceTime(at, moved))
    }

    /** Starts the whole run again: every stamp cleared, the cursor on the first line. */
    fun restart(): LrcSyncState = copy(lines = lines.map { it.copy(timeMs = null) }, cursor = 0, selected = null)

    /** The file as it would be saved. Only complete runs make a file: a half-stamped song is not a synced lyric. */
    fun toLrc(): String? =
        if (!isComplete) null else LyricsUtils.syncedToLrcString(lines.map { SyncedLine(time = it.timeMs!!, line = it.text) })

    /** The lyrics the normal synced view draws, for the preview. */
    fun toLyrics(): Lyrics? = if (!isComplete) null else Lyrics(synced = lines.map { SyncedLine(time = it.timeMs!!, line = it.text) })

    /** The editor is in step with the file again (after a save). */
    fun saved(): LrcSyncState = copy(savedTimes = times)

    companion object {
        /** The editor for lyrics with no timing: one line per non-blank line of text, nothing stamped. */
        fun forPlain(text: List<String>): LrcSyncState =
            LrcSyncState(text.map { it.trim() }.filter { it.isNotEmpty() }.map { SyncEditLine(it, null) })

        /** The editor for lyrics whose timing is wrong: the lines as they are, to be stamped again from the first. */
        fun forTimed(lines: List<SyncedLine>): LrcSyncState =
            LrcSyncState(lines.sortedBy { it.time }.filter { it.line.isNotBlank() }.map { SyncEditLine(it.line.trim(), it.time) })

        /** Picks the editor for whatever the song has. */
        fun forLyrics(lyrics: Lyrics?): LrcSyncState? = when {
            lyrics == null -> null
            !lyrics.synced.isNullOrEmpty() -> forTimed(lyrics.synced)
            !lyrics.plain.isNullOrEmpty() -> forPlain(lyrics.plain)
            else -> null
        }
    }
}

private fun List<SyncEditLine>.replaceTime(index: Int, time: Int?): List<SyncEditLine> =
    mapIndexed { i, line -> if (i == index) line.copy(timeMs = time) else line }

private fun List<SyncEditLine>.lastNotNullTime(): Int? = lastOrNull { it.timeMs != null }?.timeMs

private fun List<SyncEditLine>.firstNotNullTime(): Int? = firstOrNull { it.timeMs != null }?.timeMs
