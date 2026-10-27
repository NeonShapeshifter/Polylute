package com.lostf1sh.pixelplayeross.data.volume

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lostf1sh.pixelplayeross.R
import timber.log.Timber

/**
 * B39-S1: says that a play was held for the fixed-output notice when nothing on screen can show it (a headset button, the lock screen,
 * the app out of sight). Same manners as [SafetyMaxRefusalNotifier]: one quiet notification with one id, no sound and no heads-up, and
 * nothing at all when notifications are off. Tapping it opens the app, where the notice itself is.
 */
class FixedOutputNoticeNotifier(private val context: Context) {

    // canPost() is the POST_NOTIFICATIONS check; lint cannot follow a guard through a helper.
    @SuppressLint("MissingPermission")
    fun post(dacName: String) {
        val appContext = context.applicationContext
        val manager = NotificationManagerCompat.from(appContext)
        if (!canPost(appContext, manager)) return
        ensureChannel(appContext)
        val open = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.let {
            PendingIntent.getActivity(appContext, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(appContext, SafetyMaxRefusalNotifier.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_polylute)
            .setContentTitle(appContext.getString(R.string.fixed_output_notification_title))
            .setContentText(appContext.getString(R.string.fixed_output_notification_text, dacName))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Timber.tag(TAG).w(it, "Unable to post the fixed-output notice") }
    }

    fun cancel() {
        runCatching { NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID) }
    }

    private fun canPost(context: Context, manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    // The volume-limit channel is the right home (a quiet notice about the level); it is created here too, so the order of the two does not matter.
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(SafetyMaxRefusalNotifier.CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                SafetyMaxRefusalNotifier.CHANNEL_ID,
                context.getString(R.string.safety_max_notice_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private companion object {
        const val TAG = "FixedOutputNotice"
        const val NOTIFICATION_ID = 0x5AFF
        const val TIMEOUT_MS = 30_000L
    }
}
