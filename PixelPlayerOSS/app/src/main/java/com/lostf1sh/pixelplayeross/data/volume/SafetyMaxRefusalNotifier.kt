package com.lostf1sh.pixelplayeross.data.volume

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lostf1sh.pixelplayeross.R
import timber.log.Timber

/**
 * F-07: tells the listener that Safety Max refused a volume raise, where there is no screen to say
 * it on.
 *
 * With the phone locked or the screen off, the hardware keys reach the media session's remote
 * volume, and at the ceiling that path used to change nothing and say nothing: the override gesture
 * (three presses, then a confirmation) only exists in the unlocked app, so a refused press looked
 * like a dead key. This is the lock-screen counterpart of the in-app readout's "refused" state: a
 * quiet, silent, public notification that names the limit and says what to do. It lifts the ceiling
 * for nobody; the clamp is [SafetyVolumePolicy.requestedRaw], and this class never touches volume.
 *
 * One notification, one id: repeated presses update it in place instead of stacking, and it times
 * itself out. No sound, no vibration, no heads-up (IMPORTANCE_LOW), and nothing when notifications
 * are off or not granted: the refusal is then simply as silent as it always was.
 */
class SafetyMaxRefusalNotifier(private val context: Context) {

    // canPost() is the POST_NOTIFICATIONS check (and notifications being enabled) and the call is wrapped; lint
    // cannot follow a guard through a helper, so it reads notify() as unguarded (audit §5.2).
    @SuppressLint("MissingPermission")
    fun post() {
        val appContext = context.applicationContext
        val manager = NotificationManagerCompat.from(appContext)
        if (!canPost(appContext, manager)) return
        ensureChannel(appContext)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.monochrome_player)
            .setContentTitle(appContext.getString(R.string.safety_max_refused_notice))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Timber.tag(TAG).w(it, "Unable to post the Safety Max notice") }
    }

    private fun canPost(context: Context, manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
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

    companion object {
        private const val TAG = "SafetyMaxNotice"
        const val CHANNEL_ID = "safety_max_notice"
        const val NOTIFICATION_ID = 0x5AFE
        const val TIMEOUT_MS = 6_000L
    }
}
