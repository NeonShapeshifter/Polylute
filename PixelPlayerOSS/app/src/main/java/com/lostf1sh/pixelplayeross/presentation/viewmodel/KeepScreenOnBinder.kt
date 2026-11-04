package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.view.Window
import android.view.WindowManager.LayoutParams
import com.lostf1sh.pixelplayeross.data.preferences.DAP_BRIGHTNESS_FOLLOW_SYSTEM
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import timber.log.Timber

/** The activity's only window flag owner. Page and player-sheet navigation do not enter this rule. */
class KeepScreenOnBinder(private val window:Window) {
    fun apply(dapMode:Boolean,timeoutMode:Long,playing:Boolean,lockSurfaceShowing:Boolean) {
        val keep=DapDeviceControlPolicy.shouldKeepScreenOn(timeoutMode,playing,dapMode,lockSurfaceShowing)
        update(keep,Inputs(dapMode,timeoutMode,playing,lockSurfaceShowing),"inputs")
    }
    private fun update(keep:Boolean,inputs:Inputs,reason:String) {
        val held=window.attributes.flags and LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        if(keep==held) return
        if(keep) window.addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)
        Timber.tag("DapDisplay").i("keep=%s dap=%s timeout=%d playing=%s lock=%s reason=%s",keep,inputs.dap,inputs.timeout,inputs.playing,inputs.lock,reason)
    }
    suspend fun bind(dapMode:Flow<Boolean>,timeoutMode:Flow<Long>,playing:Flow<Boolean>,lockSurfaceShowing:Flow<Boolean>) {
        var last=Inputs(false,0,false,false)
        try {
            combine(dapMode,timeoutMode,playing,lockSurfaceShowing,::Inputs).distinctUntilChanged().collect {
                last=it
                apply(it.dap,it.timeout,it.playing,it.lock)
            }
        } finally {
            update(false,last,"stopped")
        }
    }
    private data class Inputs(val dap:Boolean,val timeout:Long,val playing:Boolean,val lock:Boolean)
}

object DapWindowBrightness {
    fun apply(window:Window,percent:Int,dapMode:Boolean) {
        val target=if(!dapMode || percent==DAP_BRIGHTNESS_FOLLOW_SYSTEM) LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else DapDeviceControlPolicy.sanitizeBrightnessPercent(percent)/100f
        val attrs=window.attributes
        if(attrs.screenBrightness != target) { attrs.screenBrightness=target;window.attributes=attrs }
    }
}
