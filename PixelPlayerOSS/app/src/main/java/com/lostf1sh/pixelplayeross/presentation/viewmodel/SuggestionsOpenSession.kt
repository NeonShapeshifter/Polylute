package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** An open is a new process, or foreground after at least 30 minutes away. Rotations and short interruptions keep the token. */
@Singleton
class SuggestionsOpenSession @Inject constructor() {
    private val value=MutableStateFlow(Random.nextLong())
    val token=value.asStateFlow()
    private var backgroundAt: Long? = null
    fun foreground(now: Long = SystemClock.elapsedRealtime()) {
        if(backgroundAt?.let { now-it >= LONG_ABSENCE_MS } == true) value.value=value.value+1
        backgroundAt=null
    }
    fun background(now: Long = SystemClock.elapsedRealtime()) { backgroundAt=now }
    companion object { const val LONG_ABSENCE_MS = 30*60_000L }
}
