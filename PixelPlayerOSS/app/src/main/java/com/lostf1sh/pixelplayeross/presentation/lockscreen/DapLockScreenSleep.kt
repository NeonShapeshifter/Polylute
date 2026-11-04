package com.lostf1sh.pixelplayeross.presentation.lockscreen

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.lostf1sh.pixelplayeross.dap.admin.DapDeviceAdminReceiver
import timber.log.Timber

/** Standard Android APIs only. Lack of admin authority is reported rather than pretending a black view is screen-off. */
class DapLockScreenSleep(private val context:Context) {
    private val admin=ComponentName(context,DapDeviceAdminReceiver::class.java)
    private val policy=context.getSystemService(DevicePolicyManager::class.java)
    private val recovery=context.getSharedPreferences("dap_lock_display_restore",Context.MODE_PRIVATE)
    private var originalTimeout:Int?=if(recovery.contains(PREVIOUS_TIMEOUT)) recovery.getInt(PREVIOUS_TIMEOUT,0) else null
    private var prepared=false
    private var fallbackReady=false
    init { restorePendingTimeout() }
    private fun canLock()=policy?.isAdminActive(admin)==true
    // A Boolean commit result is required before changing Android's global timeout.
    @android.annotation.SuppressLint("UseKtx")
    fun begin() {
        if(prepared) return
        restorePendingTimeout()
        prepared=true
        if(canLock()) return
        val old=Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_OFF_TIMEOUT,60_000)
        if(old==LockSurfaceIdleTimer.TIMEOUT_MS.toInt()) { fallbackReady=true; return }
        if(!Settings.System.canWrite(context)) return
        // Preserve the original before changing a global setting, including across process death.
        if(!recovery.edit().putInt(PREVIOUS_TIMEOUT,old).commit()) return
        originalTimeout=old
        fallbackReady=runCatching {
            Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_OFF_TIMEOUT,LockSurfaceIdleTimer.TIMEOUT_MS.toInt())
        }.getOrDefault(false)
    }
    fun end() {
        prepared=false
        fallbackReady=false
        restorePendingTimeout()
    }
    // KTX edit returns Unit; recovery needs commit's Boolean result to retain a failed removal for retry.
    @android.annotation.SuppressLint("UseKtx")
    private fun restorePendingTimeout() {
        val previous=originalTimeout ?: return
        // A user's/system's intervening change wins over our temporary value.
        if(Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_OFF_TIMEOUT,0)==LockSurfaceIdleTimer.TIMEOUT_MS.toInt()) {
            if(!Settings.System.canWrite(context)) return
            val restored=runCatching {
                Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_OFF_TIMEOUT,previous)
            }.getOrDefault(false)
            if(!restored) return
        }
        // Retain recovery data if permissions or disk writes fail; a later host can retry.
        if(recovery.edit().remove(PREVIOUS_TIMEOUT).commit()) originalTimeout=null
    }
    fun sleep():Result {
        val result=if(canLock()) {
            try { policy!!.lockNow();Result.FORCED } catch(failure:SecurityException) {
                Timber.tag("DapDisplay").w(failure,"lockNow refused")
                Result.UNAVAILABLE
            }
        } else if(fallbackReady) Result.SYSTEM_TIMEOUT else Result.UNAVAILABLE
        Timber.tag("DapDisplay").i("lock idle=%d result=%s admin=%s writeSettings=%s",LockSurfaceIdleTimer.TIMEOUT_MS,result,canLock(),Settings.System.canWrite(context))
        return result
    }
    enum class Result { FORCED,SYSTEM_TIMEOUT,UNAVAILABLE }
    private companion object { const val PREVIOUS_TIMEOUT="previous_timeout" }
}
