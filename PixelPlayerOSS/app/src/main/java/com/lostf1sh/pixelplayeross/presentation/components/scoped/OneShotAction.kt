package com.lostf1sh.pixelplayeross.presentation.components.scoped

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Latches on its first invocation, so a control that starts a one-way side effect cannot start a
 * second one while the surface hosting it is still animating away.
 *
 * The case this exists for: a dialog that dismisses on success stays composed through its exit
 * animation, so a second tap a couple of hundred milliseconds after the first still lands on a
 * live button. For a save that writes to a file, "the UI is on its way out" is not soon enough.
 *
 * [isTaken] is observable so the control can render itself disabled for the same window.
 */
@Stable
class OneShotAction {

    var isTaken by mutableStateOf(false)
        private set

    fun invoke(block: () -> Unit) {
        if (isTaken) return
        isTaken = true
        block()
    }
}
