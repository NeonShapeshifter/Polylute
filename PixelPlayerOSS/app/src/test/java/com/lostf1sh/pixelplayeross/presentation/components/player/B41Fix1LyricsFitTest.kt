package com.lostf1sh.pixelplayeross.presentation.components.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** B41-fix1 (B7): the rule for how far the sung line may shrink, apart from any text: full size first, then down to 85 % and no further. */
class B41Fix1LyricsFitTest {
    @Test
    fun `a line that fits at full size is not shrunk`() {
        assertEquals(1f, LyricsFit.scaleFor { true })
    }

    @Test
    fun `a line that needs a little less takes the largest scale that fits`() {
        assertEquals(0.95f, LyricsFit.scaleFor { it <= 0.95f })
        assertEquals(0.90f, LyricsFit.scaleFor { it <= 0.90f })
        assertEquals(0.85f, LyricsFit.scaleFor { it <= 0.85f })
    }

    @Test
    fun `a line that fits at no scale gets the smallest and never less`() {
        assertEquals(0.85f, LyricsFit.scaleFor { false })
        assertEquals(0.85f, LyricsFit.MIN_SCALE)
    }

    @Test
    fun `every scale it tries is between the bound and full size`() {
        val tried = mutableListOf<Float>()
        LyricsFit.scaleFor { tried += it; false }
        assertEquals(listOf(1f, 0.95f, 0.90f, 0.85f), tried)
    }
}
