package com.lostf1sh.pixelplayeross.dap.admin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Process-local bridge for the official DeviceAdmin Lock Task lifecycle callbacks. */
object DapLockTaskStateEvents {
    private val observedState = MutableStateFlow<Int?>(null)

    fun prepareForTransition() {
        observedState.value = null
    }

    fun onStateChanged(state: Int) {
        observedState.value = state
    }

    suspend fun await(expectedState: Int) {
        observedState.first { it == expectedState }
    }
}
