package com.lostf1sh.pixelplayeross.presentation.components

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SleepTimerSelectionTest {
    @Test fun `custom input is an interval with independently validated units`() {
        assertEquals(75, customTimerMinutes("1", "15"))
        assertEquals(1439, customTimerMinutes("23", "59"))
        for ((h, m) in listOf("0" to "0", "24" to "0", "1" to "60", "-1" to "5", "" to "30", "1.5" to "0", "1" to "abc")) {
            assertNull(customTimerMinutes(h, m), "$h:$m")
        }
    }
    @Test fun `invalid confirmed intervals and repeat counts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SleepTimerSelection.Duration(0) }
        assertThrows(IllegalArgumentException::class.java) { SleepTimerSelection.Duration(1440) }
        assertThrows(IllegalArgumentException::class.java) { SleepTimerSelection.RepeatCurrent(11) }
        assertEquals(1, SleepTimerSelection.RepeatCurrent(1).count)
    }
}
