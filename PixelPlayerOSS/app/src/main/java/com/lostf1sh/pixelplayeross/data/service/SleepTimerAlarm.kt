package com.lostf1sh.pixelplayeross.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import timber.log.Timber

/**
 * B40-F9c: the alarm behind the duration sleep timer, without the exact-alarm permission.
 *
 * `SCHEDULE_EXACT_ALARM` is a special access Android 12 and later hands out only to apps whose job is an alarm or a calendar, and Google
 * Play asks an app that declares it to justify it. A sleep timer ("stop in 30 minutes") is not that job, and the code already fell back
 * to an inexact alarm whenever the permission was missing, which on a new install of an app targeting Android 13+ is always. So the
 * permission is gone and this is the one way the timer is armed:
 *
 *  1. **An inexact alarm that is allowed in Doze** (`setAndAllowWhileIdle`, on elapsed time, so a change of the clock or time zone does not
 *     move it): Android fires it at about the right time, later if the phone is in deep sleep. It needs no permission. It is the backstop
 *     for a process that is not running.
 *  2. **The process's own timer** on the main looper, for the right second: while music plays the playback service is in the foreground and
 *     the CPU is awake, so this fires when it should. It sends the same `PendingIntent` the alarm would, so there is one way to expire.
 *
 * Whichever comes first expires the timer: the service pauses and calls [cancel], which takes the other down, so a late alarm cannot
 * pause a song the listener started again after the timer had stopped the first.
 *
 * One timer for the process: the view model's sleep timer and the service's session command both arm it, and they armed the same
 * `PendingIntent` before (the later replaced the earlier); now they share the in-process half too.
 */
object SleepTimerAlarm {
    private const val TAG = "SleepTimerScheduler"
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var armed: PendingIntent? = null

    private val expire = Runnable {
        val intent = armed ?: return@Runnable
        Timber.tag(TAG).i("Sleep timer expired in the process")
        runCatching { intent.send() }.onFailure { Timber.tag(TAG).w(it, "Could not send the sleep timer's expiry") }
    }

    /** The one `PendingIntent` for the timer: the alarm, the in-process timer and [cancel] all use this identity. */
    fun pendingIntent(context: Context): PendingIntent {
        val app = context.applicationContext
        val intent = Intent(app, SleepTimerReceiver::class.java).apply {
            action = MusicService.ACTION_SLEEP_TIMER_EXPIRED
            setPackage(app.packageName)
        }
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Arms the timer to expire at [triggerAtElapsedMs] on [SystemClock.elapsedRealtime]; replaces a timer that was armed. */
    fun schedule(context: Context, triggerAtElapsedMs: Long) {
        val app = context.applicationContext
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = pendingIntent(app)
        armed = intent
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, intent)
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "The inexact alarm was refused; the in-process timer stands alone")
        }
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, (triggerAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    /** Takes the timer down: the alarm and the in-process half. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(app))
        handler.removeCallbacks(expire)
        armed = null
    }
}
