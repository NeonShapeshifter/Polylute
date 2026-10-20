package com.lostf1sh.pixelplayeross.presentation.screens

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxCalibrationResult
import org.junit.jupiter.api.Test

/** B35 (RT-01): each reason a calibration saved nothing has its own words, and a level nobody could read is never "raise the volume above zero". */
class SafetyMaxNotSavedMessageTest {

    @Test
    fun `a saved calibration says nothing and every refusal says something of its own`() {
        assertThat(safetyMaxNotSavedMessage(null)).isNull()
        assertThat(safetyMaxNotSavedMessage(SafetyMaxCalibrationResult.SAVED)).isNull()
        val refusals = SafetyMaxCalibrationResult.values().filter { it != SafetyMaxCalibrationResult.SAVED }
        val messages = refusals.map { safetyMaxNotSavedMessage(it) }
        assertThat(messages).doesNotContain(null)
        assertThat(messages.toSet()).hasSize(refusals.size)
    }

    @Test
    fun `an unreadable level is not reported as a level of zero`() {
        assertThat(safetyMaxNotSavedMessage(SafetyMaxCalibrationResult.LEVEL_UNCONFIRMED)).isEqualTo(R.string.safety_max_not_saved_unconfirmed)
        assertThat(safetyMaxNotSavedMessage(SafetyMaxCalibrationResult.LEVEL_ZERO)).isEqualTo(R.string.safety_max_zero_rejected)
    }
}
