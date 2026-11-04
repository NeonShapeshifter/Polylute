package com.lostf1sh.pixelplayeross.presentation.lockscreen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A lock-surface deadline, independent of playback, page, timeout preference and wall clock. */
class LockSurfaceIdleTimer(private val scope:CoroutineScope,private val onTimeout:()->Unit) {
    private var visible=false
    private var deadline:Job?=null
    fun setVisible(value:Boolean) {
        if(visible==value) return
        visible=value
        if(value) touch() else { deadline?.cancel();deadline=null }
    }
    fun touch() {
        if(!visible) return
        deadline?.cancel()
        deadline=scope.launch { delay(TIMEOUT_MS);onTimeout() }
    }
    companion object { const val TIMEOUT_MS=30_000L }
}
