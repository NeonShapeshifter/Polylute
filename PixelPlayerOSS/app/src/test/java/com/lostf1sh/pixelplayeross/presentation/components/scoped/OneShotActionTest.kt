package com.lostf1sh.pixelplayeross.presentation.components.scoped

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The guard that stops a dismiss-on-success control from firing its side effect twice while it is
 * still animating away. A save writes to a file, so "the UI is leaving" is not soon enough.
 */
class OneShotActionTest {

    @Test
    fun `the first invocation runs`() {
        val action = OneShotAction()
        var runs = 0

        action.invoke { runs++ }

        assertThat(runs).isEqualTo(1)
        assertThat(action.isTaken).isTrue()
    }

    @Test
    fun `a second invocation in the same window does nothing`() {
        val action = OneShotAction()
        var runs = 0

        action.invoke { runs++ }
        action.invoke { runs++ }
        action.invoke { runs++ }

        assertThat(runs).isEqualTo(1)
    }

    @Test
    fun `the guard is taken before the block runs, so a reentrant call is refused`() {
        val action = OneShotAction()
        var runs = 0

        action.invoke {
            runs++
            // A control that re-triggers itself (a callback firing during the write) must be
            // refused rather than starting a nested second write.
            action.invoke { runs++ }
        }

        assertThat(runs).isEqualTo(1)
    }

    @Test
    fun `a fresh guard for a fresh surface is not already taken`() {
        // The editor keys the guard on the song, so opening it for another track must start clean.
        val first = OneShotAction()
        first.invoke { }

        val second = OneShotAction()

        assertThat(first.isTaken).isTrue()
        assertThat(second.isTaken).isFalse()
    }

    @Test
    fun `is observable so the control can disable itself`() {
        val action = OneShotAction()
        assertThat(action.isTaken).isFalse()
        action.invoke { }
        assertThat(action.isTaken).isTrue()
    }
}
