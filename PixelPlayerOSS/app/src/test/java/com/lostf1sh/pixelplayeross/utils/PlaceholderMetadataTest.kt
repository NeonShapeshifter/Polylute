package com.lostf1sh.pixelplayeross.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What counts as a missing value, and what does not.
 *
 * The product rule is that a storage placeholder must never reach ordinary UI. That only works if
 * "placeholder" is defined by something other than a blank check, because the value that actually
 * turns up on a device is the non-empty literal `<unknown>`, which passes every emptiness test ever
 * written.
 */
class PlaceholderMetadataTest {

    @Test
    fun `the sentinels a device actually produces are placeholders`() {
        // `<unknown>` is MediaStore's own convention; the others are what this app's sync has
        // written into the same columns.
        listOf(
            "<unknown>",
            "unknown",
            "Unknown Artist",
            "unknown artist",
            "Unknown Title",
            "Unknown Album",
            "null",
        ).forEach { sentinel ->
            assertTrue(sentinel.isPlaceholderMetadata(), "\"$sentinel\" names nothing")
        }
    }

    @Test
    fun `the decoration around a sentinel is not what makes it one`() {
        listOf("<unknown>", "(unknown)", "[unknown]", "  <unknown>  ", "<UNKNOWN>").forEach {
            assertTrue(it.isPlaceholderMetadata(), "\"$it\" is the same absence as `unknown`")
        }
    }

    @Test
    fun `nothing at all is a placeholder`() {
        assertTrue(null.isPlaceholderMetadata())
        assertTrue("".isPlaceholderMetadata())
        assertTrue("   ".isPlaceholderMetadata())
    }

    @Test
    fun `real metadata is not`() {
        // The set is closed deliberately: a band may be called Unknown, and a title may contain
        // angle brackets. Only the exact sentinels are rejected.
        listOf(
            "Harry Styles",
            "The Unknown",
            "Unknown Mortal Orchestra",
            "<I'm With You>",
            "A Perfect Circle",
            "unknown pleasures",
        ).forEach { real ->
            assertFalse(real.isPlaceholderMetadata(), "\"$real\" is a name, not an absence")
        }
    }

    @Test
    fun `a placeholder resolves to nothing rather than to itself`() {
        assertNull("<unknown>".withoutPlaceholderMetadata())
        assertEquals("Harry Styles", "Harry Styles".withoutPlaceholderMetadata())
        assertEquals("", "<unknown>".withoutPlaceholderMetadataOrEmpty())
    }

    @Test
    fun `a real value still gets its mojibake repaired`() {
        // The placeholder check is layered on top of normalisation, not a replacement for it.
        assertEquals("Björk", "BjÃ¶rk".withoutPlaceholderMetadata())
    }

    @Test
    fun `one sentinel in a credit does not survive into the joined line`() {
        assertEquals(
            listOf("Harry Styles", "Florence Welch"),
            listOf("Harry Styles", "<unknown>", "Florence Welch").withoutPlaceholderNames(),
        )
        assertEquals(emptyList(), listOf("<unknown>", "unknown").withoutPlaceholderNames())
    }
}
