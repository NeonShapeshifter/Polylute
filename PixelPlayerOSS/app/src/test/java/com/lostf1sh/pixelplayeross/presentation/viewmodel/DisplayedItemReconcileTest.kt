package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DisplayedItemReconcile.Source
import org.junit.jupiter.api.Test

/**
 * B29-L2: which player the song on screen is brought in line with. 2026-10-17 20:40:31: the engine went on to 6312
 * (STORM II) by itself (`reason=1`, an automatic transition) and the mini player and the session went on showing 6311
 * (STORM I). The listener is an edge; this is the level.
 */
class DisplayedItemReconcileTest {

    @Test
    fun `the 20-40-31 shape, shown and controller on STORM I while the engine is on STORM II, follows the engine`() {
        assertThat(DisplayedItemReconcile.followFrom(displayedId = "6311", controllerId = "6311", engineId = "6312"))
            .isEqualTo(Source.ENGINE)
    }

    @Test
    fun `what is shown is what the engine plays, nothing to do, whatever the controller says`() {
        assertThat(DisplayedItemReconcile.followFrom("6312", "6312", "6312")).isEqualTo(Source.NONE)
        // the controller is the one that is behind: the screen is already right
        assertThat(DisplayedItemReconcile.followFrom("6312", "6311", "6312")).isEqualTo(Source.NONE)
    }

    @Test
    fun `the controller agrees with the engine but the screen is behind, the ordinary sync from the controller`() {
        assertThat(DisplayedItemReconcile.followFrom("6311", "6312", "6312")).isEqualTo(Source.CONTROLLER)
    }

    @Test
    fun `nothing shown while the engine plays follows the controller if it agrees, else the engine`() {
        assertThat(DisplayedItemReconcile.followFrom(null, "6312", "6312")).isEqualTo(Source.CONTROLLER)
        assertThat(DisplayedItemReconcile.followFrom(null, null, "6312")).isEqualTo(Source.ENGINE)
    }

    @Test
    fun `an engine with nothing on it is never a reason to change the screen`() {
        assertThat(DisplayedItemReconcile.followFrom("6311", "6311", null)).isEqualTo(Source.NONE)
        assertThat(DisplayedItemReconcile.followFrom(null, null, null)).isEqualTo(Source.NONE)
    }
}
