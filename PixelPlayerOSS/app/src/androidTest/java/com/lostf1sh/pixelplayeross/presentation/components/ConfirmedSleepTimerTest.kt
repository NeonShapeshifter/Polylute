package com.lostf1sh.pixelplayeross.presentation.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.*

class ConfirmedSleepTimerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun prepare() { TestScreen.keepAwake(compose.activity) }
    @After fun cleanup() { TestScreen.release(compose.activity) }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun choosingAnIntervalDoesNothingUntilConfirmedAndClosingPreservesActiveTimer() {
        val calls = mutableListOf<String>()
        compose.setContent { DapTheme { TimerOptionsBottomSheet(
            onPlayCounter = { calls += "repeat:$it" }, activeTimerValueDisplay = "30 min",
            activeTimerDurationMinutes = 30, playCount = 0f, isEndOfTrackTimerActive = false,
            onDismiss = { calls += "close" }, onCancelCountedPlay = { calls += "cancelRepeat" },
            onSetPredefinedTimer = { calls += "duration:$it" }, onSetEndOfTrackTimer = { calls += "end:$it" },
            onOpenCustomTimePicker = {}, onCancelTimer = { calls += "cancelTimer" }) } }
        compose.onNodeWithText(context.getString(R.string.dap_timeout_value_minutes, 15)).performClick()
        compose.runOnIdle { Assert.assertTrue(calls.isEmpty()) }
        compose.onNodeWithText(context.getString(R.string.timer_close)).performClick()
        compose.runOnIdle { Assert.assertEquals(listOf("close"), calls); calls.clear() }
        compose.onNodeWithText(context.getString(R.string.timer_start)).performClick()
        compose.runOnIdle { Assert.assertEquals(listOf("cancelRepeat", "cancelTimer", "duration:15", "close"), calls) }
    }
    @Test fun uiAndServiceCancelTheSameNativeAlarmOperation() {
        // Compare actual Android PendingIntent identities from both production factories.
        // Different actions, components or request codes would leave two independent alarms.
        val holder = com.lostf1sh.pixelplayeross.presentation.viewmodel.SleepTimerStateHolder(context)
        val scheduler = com.lostf1sh.pixelplayeross.data.service.AlarmManagerSleepTimerScheduler(context)
        val uiFactory = holder.javaClass.getDeclaredMethod("sleepTimerPendingIntent").apply { isAccessible = true }
        val serviceFactory = scheduler.javaClass.getDeclaredMethod("createPendingIntent").apply { isAccessible = true }
        Assert.assertEquals(uiFactory.invoke(holder), serviceFactory.invoke(scheduler))
    }

}
