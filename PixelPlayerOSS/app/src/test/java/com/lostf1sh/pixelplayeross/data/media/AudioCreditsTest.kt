package com.lostf1sh.pixelplayeross.data.media

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for credits read from the file itself.
 *
 * The screen this feeds omits absent rows rather than printing "Unknown", so these tests pin two
 * things: that a present tag is found under any of the names a real file might use, and that an
 * absent tag stays genuinely absent.
 */
class AudioCreditsTest {

    private fun credits(vararg pairs: Pair<String, Array<String>>) =
        AudioMetadataReader.creditsFrom(mapOf(*pairs))

    @Test
    fun `a file with no credit tags yields nothing to show`() {
        val empty = credits("TITLE" to arrayOf("Carla's Song"))

        assertTrue(empty.isEmpty)
        assertNull(empty.composer)
        assertNull(empty.producer)
        assertNull(empty.conductor)
        assertNull(empty.writer)
    }

    @Test
    fun `absent fields stay absent so the screen can omit the row`() {
        val populated = credits("COMPOSER" to arrayOf("Harry Styles"))

        assertTrue(!populated.isEmpty)
        assertNull(populated.producer, "an absent producer must not become 'Unknown'")
        assertNull(populated.conductor)
        assertNull(populated.label)
        assertNull(populated.copyright)
    }

    @Test
    fun `a populated file reports every field it actually carries`() {
        val full = credits(
            "ARTIST" to arrayOf("Harry Styles"),
            "ALBUMARTIST" to arrayOf("Harry Styles"),
            "COMPOSER" to arrayOf("Harry Styles", "Kid Harpoon"),
            "WRITER" to arrayOf("Kid Harpoon"),
            "PRODUCER" to arrayOf("Kid Harpoon"),
            "CONDUCTOR" to arrayOf("Someone"),
            "LABEL" to arrayOf("Columbia"),
            "COPYRIGHT" to arrayOf("(C) 2022 Columbia")
        )

        assertEquals("Harry Styles", full.performingArtist)
        assertEquals("Harry Styles", full.albumArtist)
        assertEquals("Harry Styles, Kid Harpoon", full.composer)
        assertEquals("Kid Harpoon", full.writer)
        assertEquals("Kid Harpoon", full.producer)
        assertEquals("Someone", full.conductor)
        assertEquals("Columbia", full.label)
        assertEquals("(C) 2022 Columbia", full.copyright)
        assertTrue(!full.isEmpty)
    }

    @Test
    fun `multiple values in one field are joined rather than truncated`() {
        val multi = credits("COMPOSER" to arrayOf("Harry Styles", "Kid Harpoon", "Tyler Johnson"))

        assertEquals("Harry Styles, Kid Harpoon, Tyler Johnson", multi.composer)
    }

    @Test
    fun `duplicate values are collapsed`() {
        val duplicated = credits("ARTIST" to arrayOf("Harry Styles", "Harry Styles"))

        assertEquals("Harry Styles", duplicated.performingArtist)
    }

    @Test
    fun `blank values are skipped in favour of a later real one`() {
        val mixed = credits("COMPOSER" to arrayOf("", "   ", "Kid Harpoon"))

        assertEquals("Kid Harpoon", mixed.composer)
    }

    @Test
    fun `a field of only blanks reads as absent`() {
        val blanks = credits("PRODUCER" to arrayOf("", "  "))

        assertNull(blanks.producer, "a blank tag is not a credit")
        assertTrue(blanks.isEmpty)
    }

    @Test
    fun `alternative tag names are accepted`() {
        assertEquals("A Band", credits("BAND" to arrayOf("A Band")).albumArtist)
        assertEquals("A Writer", credits("LYRICIST" to arrayOf("A Writer")).writer)
        assertEquals("A Publisher", credits("PUBLISHER" to arrayOf("A Publisher")).label)
        assertEquals("A Writer", credits("TEXT_WRITER" to arrayOf("A Writer")).writer)
    }

    @Test
    fun `tag keys are matched without regard to case`() {
        assertEquals("Columbia", credits("label" to arrayOf("Columbia")).label)
        assertEquals("Harry Styles", credits("Composer" to arrayOf("Harry Styles")).composer)
    }

    @Test
    fun `the first recognised key wins so a value is never duplicated across fields`() {
        val both = credits(
            "ARTIST" to arrayOf("Performer Name"),
            "PERFORMER" to arrayOf("Different Name")
        )

        assertEquals("Performer Name", both.performingArtist)
    }

    @Test
    fun `a performer is reported from either of its two tag names`() {
        assertEquals("Solo", credits("PERFORMER" to arrayOf("Solo")).performingArtist)
        assertEquals("Solo", credits("ARTIST" to arrayOf("Solo")).performingArtist)
    }

    @Test
    fun `reading only local tags never requires a network`() {
        // creditsFrom is a pure function over an in-memory tag map; there is no path from here to
        // a network call, which is what makes the Credits view work with the radio off.
        val offline = credits("COMPOSER" to arrayOf("Harry Styles"))

        assertEquals("Harry Styles", offline.composer)
    }
}
