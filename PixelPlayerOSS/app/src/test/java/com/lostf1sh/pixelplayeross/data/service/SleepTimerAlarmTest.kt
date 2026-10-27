package com.lostf1sh.pixelplayeross.data.service

import android.app.AlarmManager
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * B40-F9c: the sleep timer without the exact-alarm permission still fires, and at the right time.
 *
 * Two halves, one expiry: an inexact alarm that Android allows in Doze (the backstop for a process that is not running) and the process's
 * own timer (for the right second). Whichever comes first sends the `PendingIntent` the service pauses on; cancelling takes both down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SleepTimerAlarmTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @After
    fun tearDown() = SleepTimerAlarm.cancel(context)

    private fun expiries() = shadowOf(context as android.app.Application).broadcastIntents
        .filter { it.action == MusicService.ACTION_SLEEP_TIMER_EXPIRED }

    @Test
    fun `an alarm is armed on elapsed time, allowed in Doze, to the receiver the service listens through`() {
        val at = SystemClock.elapsedRealtime() + 30 * 60_000L
        SleepTimerAlarm.schedule(context, at)
        val alarm = alarms.peekNextScheduledAlarm()!!
        assertThat(alarm.type).isEqualTo(AlarmManager.ELAPSED_REALTIME_WAKEUP)
        assertThat(alarm.triggerAtMs).isEqualTo(at)
        assertThat(alarm.allowWhileIdle).isTrue()
        val sent = shadowOf(alarm.operation).savedIntent
        assertThat(sent.component!!.className).isEqualTo(SleepTimerReceiver::class.java.name)
        assertThat(sent.action).isEqualTo(MusicService.ACTION_SLEEP_TIMER_EXPIRED)
    }

    @Test
    fun `the process's own timer expires it on time, not before`() {
        SleepTimerAlarm.schedule(context, SystemClock.elapsedRealtime() + 10_000L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9_900))
        assertThat(expiries()).isEmpty()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertThat(expiries()).hasSize(1)
    }

    @Test
    fun `cancelling takes down the alarm and the in-process timer`() {
        SleepTimerAlarm.schedule(context, SystemClock.elapsedRealtime() + 10_000L)
        SleepTimerAlarm.cancel(context)
        assertThat(alarms.peekNextScheduledAlarm()).isNull()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertThat(expiries()).isEmpty()
    }

    @Test
    fun `arming it again replaces the first one - one alarm, one expiry, at the new time`() {
        SleepTimerAlarm.schedule(context, SystemClock.elapsedRealtime() + 5_000L)
        SleepTimerAlarm.schedule(context, SystemClock.elapsedRealtime() + 20_000L)
        assertThat(alarms.scheduledAlarms).hasSize(1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertThat(expiries()).isEmpty()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertThat(expiries()).hasSize(1)
    }

    @Test
    fun `a time already past fires at once`() {
        SleepTimerAlarm.schedule(context, SystemClock.elapsedRealtime() - 1_000L)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(expiries()).hasSize(1)
    }

    @Test
    fun `the service's scheduler is the same timer, so what the view model arms the service can cancel`() {
        val service = AlarmManagerSleepTimerScheduler(context)
        SleepTimerAlarm.schedule(context, SystemClock.elapsedRealtime() + 10_000L)
        service.cancel()
        assertThat(alarms.peekNextScheduledAlarm()).isNull()
        service.schedule(SystemClock.elapsedRealtime() + 10_000L)
        assertThat(alarms.scheduledAlarms).hasSize(1)
    }
}
