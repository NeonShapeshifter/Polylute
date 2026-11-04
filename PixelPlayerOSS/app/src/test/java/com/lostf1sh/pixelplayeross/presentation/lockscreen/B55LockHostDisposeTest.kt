package com.lostf1sh.pixelplayeross.presentation.lockscreen

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class B55LockHostDisposeTest {
    @get:Rule val compose=createComposeRule()
    private val trace=object:timber.log.Timber.DebugTree() {
        override fun log(priority:Int,tag:String?,message:String,t:Throwable?) { println("B55 host $tag $message") }
    }
    @org.junit.Before fun traceHost() { timber.log.Timber.plant(trace) }
    @org.junit.After fun stopTrace() { timber.log.Timber.uproot(trace) }
    @Test fun `disposing a visible lock host releases its surface visibility callback`() {
        var hostContext:Context?=null
        val present=mutableStateOf(true)
        val visible=mutableListOf<Boolean>()
        compose.setContent {
            hostContext=androidx.compose.ui.platform.LocalContext.current
            DapTheme {
            if(present.value) DapLockScreenHost(StablePlayerState(currentSong=Kit.song(1)),true,true,{true},{0L},
                {},{},{},{},{},null,null,{visible+=it})
        } }
        val context=hostContext!!
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        val power=shadowOf(context.getSystemService(PowerManager::class.java))
        val receiver=shadowOf(ApplicationProvider.getApplicationContext<Application>()).getReceiversForIntent(Intent(Intent.ACTION_SCREEN_OFF))
            .first { it.javaClass.name.contains("DapLockScreenHost") }
        compose.runOnIdle {
            power.setIsInteractive(false)
            receiver.onReceive(context,Intent(Intent.ACTION_SCREEN_OFF))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            power.setIsInteractive(true)
            receiver.onReceive(context,Intent(Intent.ACTION_SCREEN_ON))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertThat(visible.lastOrNull()).isEqualTo(true) }
        compose.runOnIdle { present.value=false }
        compose.waitForIdle()
        assertThat(visible.last()).isFalse()
    }
}
