package com.lostf1sh.pixelplayeross.presentation.components

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/**
 * What a Compose test needs of the display (B14-4, audit T-08).
 *
 * The device these run on times its screen out in 30 s and enters its always-on display, and a Compose rule
 * launches its Activity before the test body. An Activity launched, or left running, with the display off is
 * STOPPED: the rule then finds no compose hierarchy ("No compose hierarchies found") and cannot destroy it at the
 * end ("expected DESTROYED, was STOPPED"). The tests used to set KEEP_SCREEN_ON from inside the Activity, which
 * only keeps a screen that is already on.
 *
 * So the display is woken first, then held on for the Activity, and the Activity is allowed to show over a
 * keyguard. The dedicated-device task lock is released as before: it stops the Activity being destroyed.
 *
 * Call it from the test thread (a `@Before`), never from the main thread. It touches nothing of the device's own
 * settings: no `svc power stayon`, no timeout change.
 */
internal object TestScreen {
    fun keepAwake(activity: ComponentActivity) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        UiDevice.getInstance(instrumentation).wakeUp()
        instrumentation.runOnMainSync {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            runCatching { activity.stopLockTask() }
        }
        instrumentation.waitForIdleSync()
    }

    fun release(activity: ComponentActivity) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { runCatching { activity.stopLockTask() } }
    }
}
