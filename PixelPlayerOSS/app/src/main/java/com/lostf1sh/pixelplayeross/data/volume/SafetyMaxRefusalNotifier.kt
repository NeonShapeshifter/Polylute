package com.lostf1sh.pixelplayeross.data.volume

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.safety.SafetyOverrideConfirmActivity
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
 *
 * B69, with the screen on and another app in front (the owner's S22, 2026-11-23), where that notification never comes into view:
 * - **The visible notice** is a text [Toast], once per ceiling episode ([SafetyMaxRefusal.visibleNotice]). A Toast rather than a heads-up
 *   because it needs no notification permission or channel the listener may have turned off (Android 13+ asks for notifications; a
 *   refusal must still be seen), it makes no sound and no vibration by nature, it goes away by itself, and text toasts from the
 *   background are allowed (Android 11+ blocks only custom-view ones). It cannot carry an action, which is what the notification is for.
 * - **The action** ("Raise the limit", [SafetyMaxRefusal.offer]) once the episode reached the ask: over another app as a heads-up on its
 *   own silent channel ([OFFER_CHANNEL_ID], IMPORTANCE_HIGH, no sound, no vibration), so it can be tapped where the listener is; on the
 *   lock screen in the F-07 notification itself, behind an unlock. It opens [SafetyOverrideConfirmActivity], which only asks.
 * Both use the same id: a notice is replaced, never stacked.
 */
class SafetyMaxRefusalNotifier(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** B69: one refusal from [SafetyVolumeController.refusals]: the toast when asked for, then the notification. */
    fun onRefusal(refusal: SafetyMaxRefusal) {
        if (refusal.visibleNotice) showVisibleNotice()
        post(refusal.offer)
    }

    /** B69: "Safety Max: volume limit reached", short, on top of the app in front. */
    fun showVisibleNotice() {
        val appContext = context.applicationContext
        mainHandler.post {
            runCatching { Toast.makeText(appContext, R.string.safety_max_limit_reached, Toast.LENGTH_SHORT).show() }
                .onFailure { Timber.tag(TAG).w(it, "Unable to show the Safety Max notice") }
        }
    }

    // canPost() is the POST_NOTIFICATIONS check (and notifications being enabled) and the call is wrapped; lint
    // cannot follow a guard through a helper, so it reads notify() as unguarded (audit §5.2).
    @SuppressLint("MissingPermission")
    fun post(offer: SafetyMaxOutsideNotice.OfferStyle? = null) {
        val appContext = context.applicationContext
        val manager = NotificationManagerCompat.from(appContext)
        if (!canPost(appContext, manager)) return
        ensureChannel(appContext)
        val notification = if (offer == null) noticeBuilder(appContext).build() else offerBuilder(appContext, offer).build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Timber.tag(TAG).w(it, "Unable to post the Safety Max notice") }
    }

    /** What every Safety Max notice shares: the Polylute glyph, a public status notice, one alert, gone when tapped. */
    private fun baseBuilder(appContext: Context, channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_stat_polylute)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)

    /** F-07, as it always was. */
    private fun noticeBuilder(appContext: Context): NotificationCompat.Builder =
        baseBuilder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.safety_max_refused_notice))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setTimeoutAfter(TIMEOUT_MS)

    /**
     * B69: the notice with "Raise the limit". Over another app it peeks once (its own silent high-importance channel; not `setSilent`,
     * which would also stop the peek); on the lock screen it is the F-07 notice with the action added. The action needs an unlocked
     * device: `setAuthenticationRequired` from API 31; below that, an activity started from a lock-screen notification already asks for
     * the unlock first (it is not shown over the lock screen), and the activity itself closes if it ever finds the device locked.
     */
    private fun offerBuilder(appContext: Context, offer: SafetyMaxOutsideNotice.OfferStyle): NotificationCompat.Builder {
        val action = NotificationCompat.Action.Builder(
            0,
            appContext.getString(R.string.safety_max_raise_limit),
            confirmationIntent(appContext),
        ).setAuthenticationRequired(true).build()
        return when (offer) {
            SafetyMaxOutsideNotice.OfferStyle.LOCK_SCREEN -> noticeBuilder(appContext).setTimeoutAfter(OFFER_TIMEOUT_MS)
            SafetyMaxOutsideNotice.OfferStyle.HEADS_UP -> baseBuilder(appContext, OFFER_CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.safety_max_limit_reached))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setTimeoutAfter(OFFER_TIMEOUT_MS)
        }.addAction(action)
    }

    private fun confirmationIntent(appContext: Context): PendingIntent = PendingIntent.getActivity(
        appContext,
        NOTIFICATION_ID,
        Intent(appContext, SafetyOverrideConfirmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_HISTORY),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun canPost(context: Context, manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
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
        if (manager.getNotificationChannel(OFFER_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    OFFER_CHANNEL_ID,
                    context.getString(R.string.safety_max_offer_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    companion object {
        private const val TAG = "SafetyMaxNotice"
        const val CHANNEL_ID = "safety_max_notice"

        /** B69: the heads-up with "Raise the limit" over another app. Silent: no sound, no vibration, no light. */
        const val OFFER_CHANNEL_ID = "safety_max_offer"
        const val NOTIFICATION_ID = 0x5AFE
        const val TIMEOUT_MS = 6_000L

        /** B69: the action stays as long as an unlock still shows the confirmation in the app (FIX-5's grace). */
        const val OFFER_TIMEOUT_MS = ScreenOffOverridePolicy.UNLOCK_GRACE_MS

        /** B69: the confirmation was answered (or found nothing to confirm): the action goes away. */
        fun cancel(context: Context) {
            runCatching { NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID) }
        }
    }
}
