package com.lostf1sh.pixelplayeross.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Service-owned state, including cancellation after a natural end or manual track change. */
object CountedPlayStateHolder {
    private val target = MutableStateFlow<Int?>(null)
    val activeTarget = target.asStateFlow()
    fun setTarget(count: Int?) { target.value = count }
}
