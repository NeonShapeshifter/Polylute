package com.lostf1sh.pixelplayeross.data.repository

import com.lostf1sh.pixelplayeross.utils.LyricsUtils
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the local, embedded-lyrics path.
 *
 * This is the pipeline that a FLAC carrying its own lyrics travels: the file's tag map goes in,
 * and the lyrics the UI should show come out — with real timing when the file supplies it and
 * without invented timing when it does not. Nothing here touches the network.
 */
class EmbeddedLyricsSourceTest {

    private val syncedLrc = """
        [00:12.30]First line
        [00:15.80]Second line
    """.trimIndent()

    private val plainText = """
        First line
        Second line
    """.trimIndent()

    @Test
    fun `a plain LYRICS tag is surfaced as lyrics`() {
        val lyrics = parseBestEmbeddedLyricsField(mapOf("LYRICS" to arrayOf(plainText)))

        assertNotNull(lyrics)
        assertEquals(listOf("First line", "Second line"), lyrics.plain)
    }

    @Test
    fun `embedded lyrics are never marked as coming from a remote source`() {
        val lyrics = parseBestEmbeddedLyricsField(mapOf("LYRICS" to arrayOf(plainText)))

        assertNotNull(lyrics)
        assertTrue(!lyrics.areFromRemote, "a tag read from the file is local by definition")
    }

    @Test
    fun `a SYNCEDLYRICS tag yields real timestamps`() {
        val lyrics = parseBestEmbeddedLyricsField(mapOf("SYNCEDLYRICS" to arrayOf(syncedLrc)))

        val synced = assertNotNull(lyrics?.synced)
        assertEquals(2, synced.size)
        assertEquals(12_300, synced[0].time)
        assertEquals("First line", synced[0].line)
        assertEquals(15_800, synced[1].time)
    }

    @Test
    fun `a file with both tags prefers the one that carries timing`() {
        val lyrics = parseBestEmbeddedLyricsField(
            mapOf(
                "LYRICS" to arrayOf(plainText),
                "SYNCEDLYRICS" to arrayOf(syncedLrc)
            )
        )

        assertNotNull(lyrics)
        assertTrue(!lyrics.synced.isNullOrEmpty(), "timed lyrics must win over untimed ones")
        assertEquals("First line", lyrics.synced!![0].line)
    }

    @Test
    fun `a file with both tags in one field prefers the timed lines`() {
        val lyrics = parseBestEmbeddedLyricsField(mapOf("LYRICS" to arrayOf(syncedLrc)))

        assertNotNull(lyrics)
        assertTrue(!lyrics.synced.isNullOrEmpty())
    }

    @Test
    fun `an untimed file produces no timestamps at all`() {
        val lyrics = parseBestEmbeddedLyricsField(mapOf("LYRICS" to arrayOf(plainText)))

        assertNotNull(lyrics)
        // The whole point: no synchronisation may be invented for untimed lyrics.
        assertTrue(
            lyrics.synced.isNullOrEmpty(),
            "untimed lyrics must not acquire timestamps on the way to the UI"
        )
    }

    @Test
    fun `tag keys are matched without regard to case`() {
        val lowercase = parseBestEmbeddedLyricsField(mapOf("lyrics" to arrayOf(plainText)))
        val mixed = parseBestEmbeddedLyricsField(mapOf("Lyrics" to arrayOf(plainText)))

        assertNotNull(lowercase)
        assertNotNull(mixed)
        assertEquals(lowercase.plain, mixed.plain)
    }

    @Test
    fun `alternative local tag names are all recognised`() {
        listOf("LYRICS", "SYNCEDLYRICS", "UNSYNCEDLYRICS", "LYRIC").forEach { key ->
            val lyrics = parseBestEmbeddedLyricsField(mapOf(key to arrayOf(plainText)))
            assertNotNull(lyrics, "expected $key to be recognised")
            assertEquals(listOf("First line", "Second line"), lyrics.plain)
        }
    }

    @Test
    fun `a missing tag map produces nothing rather than an empty lyric`() {
        assertNull(parseBestEmbeddedLyricsField(null))
        assertNull(parseBestEmbeddedLyricsField(emptyMap()))
    }

    @Test
    fun `a blank tag is ignored rather than shown as empty lyrics`() {
        assertNull(parseBestEmbeddedLyricsField(mapOf("LYRICS" to arrayOf("   "))))
        assertNull(parseBestEmbeddedLyricsField(mapOf("LYRICS" to arrayOf(""))))
    }

    @Test
    fun `an unrelated tag map yields nothing`() {
        assertNull(
            parseBestEmbeddedLyricsField(
                mapOf("TITLE" to arrayOf("Carla's Song"), "ARTIST" to arrayOf("Harry Styles"))
            )
        )
    }

    @Test
    fun `an empty tag among populated ones does not mask the real lyrics`() {
        val lyrics = parseBestEmbeddedLyricsField(
            mapOf(
                "LYRICS" to arrayOf("", "   ", plainText)
            )
        )

        assertNotNull(lyrics)
        assertEquals(listOf("First line", "Second line"), lyrics.plain)
    }

    @Test
    fun `a multi valued tag falls back past an unparsable entry`() {
        val lyrics = parseBestEmbeddedLyricsField(
            mapOf("LYRICS" to arrayOf("   ", plainText))
        )

        assertNotNull(lyrics)
        assertEquals(2, lyrics.plain?.size)
    }

    @Test
    fun `only the first plain tag is used so the UI does not concatenate duplicates`() {
        val lyrics = parseBestEmbeddedLyricsField(
            mapOf(
                "LYRICS" to arrayOf("[00:01.00]Tagged line"),
                "UNSYNCEDLYRICS" to arrayOf("Other text")
            )
        )

        assertNotNull(lyrics)
        assertEquals(1, lyrics.plain?.size)
    }

    @Test
    fun `a real world lrc body keeps its line order`() {
        val lrc = """
            [00:00.00]Intro
            [00:05.00]Verse one
            [00:10.00]Verse two
            [00:15.00]Chorus
        """.trimIndent()

        val lyrics = parseBestEmbeddedLyricsField(mapOf("SYNCEDLYRICS" to arrayOf(lrc)))
        val synced = assertNotNull(lyrics?.synced)

        assertEquals(listOf("Intro", "Verse one", "Verse two", "Chorus"), synced.map { it.line })
        assertEquals(listOf(0, 5_000, 10_000, 15_000), synced.map { it.time })
    }

    @Test
    fun `timed lines are presented in ascending time order`() {
        val lyrics = parseBestEmbeddedLyricsField(mapOf("LYRICS" to arrayOf(syncedLrc)))
        val synced = assertNotNull(lyrics?.synced)

        assertEquals(synced.sortedBy { it.time }, synced, "the highlight logic assumes ordered lines")
    }

    @Test
    fun `the raw parser leaves untimed text untimed`() {
        // Same guarantee one layer down, in case a future caller reaches for the parser directly.
        val parsed = LyricsUtils.parseLyrics(plainText)

        assertTrue(parsed.synced.isNullOrEmpty())
        assertEquals(listOf("First line", "Second line"), parsed.plain)
    }

    @Test
    fun `the local cache source names are distinct so provenance can be asserted`() {
        assertTrue(LYRIC_SOURCE_EMBEDDED != LYRIC_SOURCE_SIDECAR)
    }
}
