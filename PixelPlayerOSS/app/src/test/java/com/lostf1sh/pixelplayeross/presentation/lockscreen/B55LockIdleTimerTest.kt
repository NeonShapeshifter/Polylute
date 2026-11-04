package com.lostf1sh.pixelplayeross.presentation.lockscreen

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class B55LockIdleTimerTest {
    private fun timer(scope:CoroutineScope,action:()->Unit):Any = Class.forName("com.lostf1sh.pixelplayeross.presentation.lockscreen.LockSurfaceIdleTimer")
        .getConstructor(CoroutineScope::class.java,kotlin.jvm.functions.Function0::class.java).newInstance(scope,action)
    private fun visible(timer:Any,value:Boolean) { timer.javaClass.getMethod("setVisible",Boolean::class.javaPrimitiveType).invoke(timer,value) }
    private fun touch(timer:Any) { timer.javaClass.getMethod("touch").invoke(timer) }
    @Test fun `lock surface expires after thirty seconds regardless of playback or the stored timeout`():Unit = runTest {
        var sleeps=0
        val timer=timer(backgroundScope) { sleeps++ }
        visible(timer,true)
        advanceTimeBy(29999);runCurrent();assertThat(sleeps).isEqualTo(0)
        advanceTimeBy(1);runCurrent();assertThat(sleeps).isEqualTo(1)
    }
    @Test fun `a touch restarts the full thirty seconds and leaving cancels the deadline`():Unit = runTest {
        var sleeps=0
        val timer=timer(backgroundScope) { sleeps++ }
        visible(timer,true);advanceTimeBy(20000);touch(timer)
        advanceTimeBy(29999);runCurrent();assertThat(sleeps).isEqualTo(0)
        advanceTimeBy(1);runCurrent();assertThat(sleeps).isEqualTo(1)
        touch(timer);advanceTimeBy(10000);visible(timer,false)
        advanceTimeBy(60000);runCurrent();assertThat(sleeps).isEqualTo(1)
    }
}
