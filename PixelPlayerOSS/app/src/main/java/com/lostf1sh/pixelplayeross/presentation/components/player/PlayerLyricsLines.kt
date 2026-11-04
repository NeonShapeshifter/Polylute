package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * B41: which two lines of the lyrics the player shows under the cover: the line that is being sung and the one that comes next.
 *
 * Pure: the lyrics, the playback position and the sync offset in; two strings out. It reads **synced** lyrics only: lines that say when
 * they begin. Plain lyrics have no "now", and showing some of them as if they followed the song would be a lie, so they (and no lyrics at
 * all) give null and the player shows nothing and holds no room for it.
 *
 * An **instrumental gap** is a line whose text is blank (what LRC files and LRCLIB write for "no singing until here"), or the stretch
 * before the first line. During one there is nothing being sung, so [Shown.current] is null and the next line to come is still shown, dim:
 * the listener sees the song is about to start singing, and what.
 */
object CoverLyricsLines {
    /** What the two lines say. [current] is null while nothing is being sung. */
    @Immutable
    data class Shown(
        val current: String?,
        val next: String?,
        /** B57 2.2: the words of the line being sung with their times, when the synced lyrics carry them (two or more); null otherwise. */
        val currentWords: List<com.lostf1sh.pixelplayeross.data.model.SyncedWord>? = null,
        /** B57 2.2: when the line being sung ends (the next line's time, or a little after its last word), in the lyrics' own clock. */
        val currentLineEndMs: Long = 0L,
    )

    /** The words of [line] that can be filled one by one: at least two non-blank ones, in time order. */
    fun fillableWords(line: com.lostf1sh.pixelplayeross.data.model.SyncedLine?): List<com.lostf1sh.pixelplayeross.data.model.SyncedWord>? =
        line?.words?.filter { it.word.isNotBlank() }?.sortedBy { it.time }?.takeIf { it.size >= 2 }

    /** The text the words spell, and where each word starts in it: a space before a word that begins a new word, none inside a split one. */
    fun spell(words: List<com.lostf1sh.pixelplayeross.data.model.SyncedWord>): Pair<String, IntArray> {
        val starts = IntArray(words.size)
        val text = buildString {
            words.forEachIndexed { i, w ->
                if (i > 0 && w.startsNewWord) append(' ')
                starts[i] = length
                append(w.word.trim())
            }
        }
        return text to starts
    }

    /**
     * How many characters of the spelt line have been sung at [atMs] (fractional: the word being sung is filled in proportion to the time it has taken): every word whose
     * time has come is full, the one in progress is part full, the rest empty. A word lasts until the next begins (the last until [lineEndMs]).
     */
    fun filledChars(words: List<com.lostf1sh.pixelplayeross.data.model.SyncedWord>, starts: IntArray, textLength: Int, atMs: Long, lineEndMs: Long): Float {
        var filled = 0f
        for (i in words.indices) {
            val begin = words[i].time.toLong()
            if (atMs < begin) break
            val end = if (i + 1 < words.size) words[i + 1].time.toLong() else maxOf(lineEndMs, begin + 1)
            val wordEnd = if (i + 1 < words.size) starts[i + 1] else textLength
            val wordStart = starts[i]
            val fraction = ((atMs - begin).toFloat() / (end - begin).coerceAtLeast(1L)).coerceIn(0f, 1f)
            filled = wordStart + (wordEnd - wordStart) * fraction
            if (fraction < 1f) break
        }
        return filled
    }

    /**
     * The index of the line whose time has come at [positionMs] (plus [offsetMs], the song's own sync correction), or -1 before the first.
     * Lines are in time order; a line starts when the position reaches its time.
     */
    fun indexAt(lines: List<com.lostf1sh.pixelplayeross.data.model.SyncedLine>, positionMs: Long, offsetMs: Int): Int {
        val at = (positionMs + offsetMs.toLong()).coerceAtLeast(0L)
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].time.toLong() <= at) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** What to show at [positionMs], or null: no synced lyrics (nothing is shown, and no room is kept for it). */
    fun at(lyrics: Lyrics?, positionMs: Long, offsetMs: Int): Shown? {
        val lines = lyrics?.synced?.takeIf { synced -> synced.any { it.line.isNotBlank() } } ?: return null
        val index = indexAt(lines, positionMs, offsetMs)
        val currentLine = lines.getOrNull(index)
        val current = currentLine?.line?.trim()?.takeIf { it.isNotEmpty() }
        val next = lines.drop(index + 1).firstOrNull { it.line.isNotBlank() }?.line?.trim()
        // after the last sung line, with nothing to come
        if (current == null && next == null) return Shown(null, null)
        val words = if (current != null) fillableWords(currentLine) else null
        val lineEnd = lines.getOrNull(index + 1)?.time?.toLong() ?: ((words?.lastOrNull()?.time ?: currentLine?.time ?: 0).toLong() + 800L)
        return Shown(current, next, words, lineEnd)
    }
}

/**
 * B41-fix1 (B7): how far the line being sung may shrink so that it fits in two lines. A long line used to be cut at the end of its one line
 * ("Dice, qué bella camina esa muchacha cu..."); now it takes two, and only a line that two lines cannot hold shrinks, a little, down to
 * [MIN_SCALE] of its size (the text stays readable at 85 %); a line that even then does not fit is cut at the end of the second line.
 */
internal object LyricsFit {
    /** The smallest the line gets: about 85 % of its size. */
    const val MIN_SCALE = 0.85f

    private val STEPS = floatArrayOf(1f, 0.95f, 0.90f, MIN_SCALE)

    /** The largest of the scales from 1 down to [MIN_SCALE] at which [fitsInTwoLines] says the line fits; [MIN_SCALE] when none does. */
    fun scaleFor(fitsInTwoLines: (Float) -> Boolean): Float {
        for (scale in STEPS) if (fitsInTwoLines(scale)) return scale
        return MIN_SCALE
    }
}

/** The tag of the block under the cover: the tests measure its height. */
internal const val PLAYER_LYRICS_LINES_TAG = "playerLyricsLines"

/**
 * B41: the two lines under the cover. Reads the lyrics the player already loaded for the song and the position it already samples, and
 * nothing else: no request, no timer of its own. With the switch off it composes nothing and reads neither. A recomposition happens only
 * when the pair of lines changes (a `derivedStateOf` over the position), never per tick.
 *
 * A tap opens the lyrics view.
 */
@Composable
internal fun PlayerLyricsLines(
    lyrics: PlayerLyricsState,
    progress: PlayerProgressState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!lyrics.underCover) return
    val shown by remember(lyrics.lyricsProvider, progress.currentPositionProvider, lyrics.lyricsSyncOffset) {
        derivedStateOf { CoverLyricsLines.at(lyrics.lyricsProvider(), progress.currentPositionProvider(), lyrics.lyricsSyncOffset) }
    }
    val lines = shown ?: CoverLyricsLines.Shown(null, null)
    Column(
        modifier
            .fillMaxWidth()
            .testTag(PLAYER_LYRICS_LINES_TAG)
            .clickable(enabled = shown != null, onClickLabel = stringResource(R.string.player_lyrics_under_cover_cd), role = Role.Button, onClick = onOpen)
            .padding(bottom = 10.dp),
    ) {
        // B41-fix1 (B7): the line that is sung has room for two lines whatever it says (a short one, none at all in a gap), so the block
        // keeps its height from one line to the next and the title and the controls under it never move; the next line stays one, dim.
        SungLine(lines.current.orEmpty(), lines.currentWords, lines.currentLineEndMs) { progress.currentPositionProvider() + lyrics.lyricsSyncOffset }
        Text(
            text = lines.next.orEmpty(),
            style = DapType.Body,
            color = Dap.tertiaryText,
            maxLines = 1,
            minLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The line being sung: up to two lines, and shrunk a little (never under [LyricsFit.MIN_SCALE]) only when two lines at full size are not
 * enough. Its height is two lines of the full-size style whatever its text, so shrinking never changes the block's height: only the size of
 * the letters changes, not the leading.
 *
 * B57 2.2 (owner, 11-03): **no tonal backdrop** behind it (B56 added one; "looks horrible"). Without per-word times the line is bright and the lines around it dim, as before B56.
 * With them ([words]: the synced lyrics carry a time for each word) it fills word by word as it is sung: the words already sung are bright, the one being sung fills in proportion
 * to the time it takes, the rest of the line waits dim. The fill is drawn, not composed: [positionMs] is read in the draw phase, so a tick repaints the line and recomposes nothing.
 * The lyrics page is unchanged.
 */
@Composable
private fun SungLine(text: String, words: List<com.lostf1sh.pixelplayeross.data.model.SyncedWord>?, lineEndMs: Long, positionMs: () -> Long) {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val style = DapType.BodyLarge
    // Two lines of the style's own line height, whatever the text: `minLines` alone is not it (two real lines and one line padded to two
    // measure a few pixels apart, which is a jump of everything under the block each time the line changes length).
    val height = with(LocalDensity.current) { (style.lineHeight * 2).toDp() }
    val spelt = remember(words) { words?.let(CoverLyricsLines::spell) }
    // the words' own spelling when they are filled one by one, so the two layers (dim and bright) and the fill agree on every character
    val shown = spelt?.first ?: text
    BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
        val widthPx = constraints.maxWidth
        val scale = remember(shown, widthPx, style) {
            if (shown.isEmpty() || widthPx == Constraints.Infinity) 1f
            else LyricsFit.scaleFor { candidate ->
                !measurer.measure(
                    text = shown,
                    style = style.scaledTo(candidate),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    constraints = Constraints(maxWidth = widthPx),
                ).hasVisualOverflow
            }
        }
        if (spelt == null || words == null) {
            Text(text = shown, style = style.scaledTo(scale), color = Dap.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            var layout by remember(shown, scale, widthPx) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
            // the unsung part
            Text(text = shown, style = style.scaledTo(scale), color = Dap.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = { layout = it })
            // the sung part: the same text, bright, clipped to the characters sung so far
            Text(
                text = shown,
                style = style.scaledTo(scale),
                color = Dap.primaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.drawWithContent {
                    val measured = layout ?: return@drawWithContent
                    val filled = CoverLyricsLines.filledChars(words, spelt.second, shown.length, positionMs(), lineEndMs)
                    val end = kotlin.math.ceil(filled).toInt().coerceIn(0, shown.length)
                    if (end > 0) clipPath(measured.getPathForRange(0, end)) { this@drawWithContent.drawContent() }
                },
            )
        }
    }
}

/** [this] with its letters at [scale] of their size and the line height it had: the block does not change height when the letters do. */
private fun TextStyle.scaledTo(scale: Float): TextStyle = if (scale == 1f) this else copy(fontSize = fontSize * scale)
