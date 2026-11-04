package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class B55ScreenPolicyTest {
    private fun keep(dap:Boolean,playing:Boolean,lock:Boolean=false):Boolean {
        val policy=DapDeviceControlPolicy
        val method=policy.javaClass.methods.single { it.name=="shouldKeepScreenOn" }
        return if(method.parameterCount==4) method.invoke(policy,-1L,playing,dap,lock) as Boolean
            else method.invoke(policy,-1L,playing,!dap && !lock) as Boolean
    }
    @Test fun `a DAP Library page keeps the display on while music plays`():Unit {
        assertThat(keep(true,true)).isTrue()
        assertThat(keep(true,false)).isFalse()
        assertThat(keep(true,true,true)).isFalse()
    }
    @Test fun `a stored minus one never holds the display in app mode`():Unit {
        assertThat(keep(false,true)).isFalse()
        assertThat(keep(false,false)).isFalse()
    }
    @Test fun `the lyrics session toggle cannot bypass Android display policy in app mode`():Unit {
        val policy=DapDeviceControlPolicy
        val method=policy.javaClass.methods.single { it.name=="shouldKeepScreenOnForLyrics" }
        val result=if(method.parameterCount==4) method.invoke(policy,true,true,false,false) else method.invoke(policy,true,true,false)
        assertThat(result).isEqualTo(false)
    }
}
