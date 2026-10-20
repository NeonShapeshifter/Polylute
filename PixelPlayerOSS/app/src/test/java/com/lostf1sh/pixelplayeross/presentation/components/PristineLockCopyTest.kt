package com.lostf1sh.pixelplayeross.presentation.components

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import org.junit.jupiter.api.Test

/**
 * B11-3 (F-02): on 2026-10-06 the Pure Direct lock silently froze ReplayGain. Each setting the lock
 * freezes has its own sentence, and it is truthful about whether Direct is carrying the stream.
 */
class PristineLockCopyTest {

    @Test
    fun `each frozen setting has its own sentence`() {
        val active = PristineFrozenFeature.entries.map { PristineLockCopy.notice(it, directActive = true) }
        assertThat(active).containsNoDuplicates()
        assertThat(PristineLockCopy.notice(PristineFrozenFeature.EQ, true)).isEqualTo(R.string.pristine_lock_notice_eq)
        assertThat(PristineLockCopy.notice(PristineFrozenFeature.REPLAY_GAIN, true))
            .isEqualTo(R.string.pristine_lock_notice_replaygain)
        assertThat(PristineLockCopy.notice(PristineFrozenFeature.DIGITAL_VOLUME, true))
            .isEqualTo(R.string.pristine_lock_notice_digital)
    }

    @Test
    fun `with nothing in USB Direct the notice says it will not apply there, not that it is off now`() {
        // Crossfade and speed (B15-F2) are held off on every route, so they have no "pending" form (PurityFreezeTest).
        val routeDependent = PristineFrozenFeature.entries - setOf(PristineFrozenFeature.CROSSFADE, PristineFrozenFeature.SPEED)
        for (feature in routeDependent) {
            val now = PristineLockCopy.notice(feature, directActive = true)
            val later = PristineLockCopy.notice(feature, directActive = false)
            assertThat(later).isNotEqualTo(now)
        }
        assertThat(PristineLockCopy.notice(PristineFrozenFeature.EQ, directActive = false))
            .isEqualTo(R.string.pristine_lock_notice_eq_pending)
    }
}
