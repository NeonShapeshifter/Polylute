package com.lostf1sh.pixelplayeross.data.volume

import android.os.Build
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel

/**
 * Pure decisions for the in-context Safety Max notification permission ask and its quiet settings link.
 */
object SafetyMaxNotificationPromptPolicy {
    /**
     * Whether to show the explanation sheet and ask for `POST_NOTIFICATIONS` right after a Safety Max ceiling is saved.
     *
     * - Only on API 33+ where `POST_NOTIFICATIONS` is a runtime permission.
     * - Only when the permission is missing and a ceiling was just saved.
     * - At most once: after "Not now" or any system response, [alreadyAsked] stays true.
     * - Never in Pinned mode (`LOCK_TASK_MODE_PINNED`), which blocks other apps' dialogs and cannot temporarily lift pinning.
     *   In Dedicated mode (`LOCK_TASK_MODE_LOCKED`), the host lifts Lock Task around the system dialog and restores it on return.
     */
    fun shouldAskAfterCeilingSaved(
        androidApi: Int,
        permissionGranted: Boolean,
        ceilingJustSaved: Boolean,
        alreadyAsked: Boolean,
        dapLevel: ProductLevel?,
    ): Boolean =
        androidApi >= Build.VERSION_CODES.TIRAMISU &&
            !permissionGranted &&
            ceilingJustSaved &&
            !alreadyAsked &&
            dapLevel != ProductLevel.PINNED

    /**
     * Whether the quiet "notifications are off" line and its Settings action should show on API 33+.
     */
    fun shouldShowNotificationsOffNotice(
        androidApi: Int,
        permissionGranted: Boolean,
    ): Boolean = androidApi >= Build.VERSION_CODES.TIRAMISU && !permissionGranted
}
