package com.lostf1sh.pixelplayeross.dap.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeController
import com.lostf1sh.pixelplayeross.dap.lifecycle.DapRecoveryTrigger
import com.lostf1sh.pixelplayeross.dap.lifecycle.DapUpdateReturnPolicy
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ScreenTimeoutDriftGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/** Restores Device Owner policy after supported boot and own-package replacement events. */
class DapPolicyRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        runCatching {
            val trigger = if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                DapRecoveryTrigger.MY_PACKAGE_REPLACED
            } else {
                DapRecoveryTrigger.BOOT_COMPLETED
            }
            val result = ProductModeController(context).restoreDedicatedPoliciesIfActive(trigger)
            bringHomeBackAfterUpdate(context, trigger, result)
            result
        }.onSuccess { result ->
            Log.i(TAG, "Policy restore action=${intent.action} result=$result")
        }.onFailure { error ->
            Log.e(TAG, "Policy restore failed for ${intent.action}", error)
        }

        // FIX-2 (B11-fix): at boot, a screen timeout that something changed since the app wrote it is
        // put back on the appliance (never on a phone). The stored choice is in DataStore, so this
        // reads it off the main thread and holds the broadcast open until it is done.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val stored = UserPreferencesRepository.readDapScreenTimeoutMode(context)
                    val owner = DapDeviceOwnerController(context).currentState()
                    ScreenTimeoutDriftGuard.reconcile(
                        context.applicationContext,
                        stored,
                        dapMode = owner.isDeviceOwner && owner.isPixelPlayerHome,
                        trigger = "boot",
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Screen timeout reconcile failed at boot", error)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    /**
     * B34-fix2 R3: the update killed the process and the Lock Task it held, and nothing else starts the DAP again ([DapUpdateReturnPolicy]
     * says when it should). The HOME intent for this package only, so it can only ever reach this app's own home activity, and its own
     * `onResume` is what asks Lock Task: the same path a Home press takes. A refusal that throws is logged and leaves the device where the
     * update left it; a platform that drops the start without saying so is seen on the screen, not in this log.
     */
    private fun bringHomeBackAfterUpdate(context: Context, trigger: DapRecoveryTrigger, result: DapPolicyRestoreResult) {
        val state = DapDeviceOwnerController(context).currentState()
        if (!DapUpdateReturnPolicy.shouldStartHome(trigger, result, state.isPixelPlayerHome, state.lockTaskModeState)) return
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(home) }
            // a start the platform refuses from the background is dropped without an exception: this line says it was asked, the screen says it worked
            .onSuccess { Timber.tag(TAG).i("Home activity start requested after the package was replaced") }
            .onFailure { error -> Timber.tag(TAG).w(error, "Home activity start refused after the package was replaced") }
    }

    private companion object {
        const val TAG = "DapPolicyRestore"
    }
}
