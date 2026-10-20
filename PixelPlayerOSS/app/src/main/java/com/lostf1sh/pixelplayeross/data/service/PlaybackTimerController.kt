package com.lostf1sh.pixelplayeross.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import timber.log.Timber

/**
 * Schedules and cancels the OS alarm backing the duration sleep timer. Trigger times are
 * [SystemClock.elapsedRealtime] based (AUD-050): a wall-clock alarm moved with every clock or
 * time-zone change.
 */
interface SleepTimerAlarmScheduler {
    fun schedule(triggerAtMillis: Long)
    fun cancel()
}

/**
 * [SleepTimerAlarmScheduler] backed by [AlarmManager], falling back to inexact alarms when
 * exact-alarm permission is unavailable or denied.
 */
class AlarmManagerSleepTimerScheduler(private val context: Context) : SleepTimerAlarmScheduler {

    private val alarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    private fun createPendingIntent(): PendingIntent {
        val intent = Intent(context, SleepTimerReceiver::class.java).apply {
            action = MusicService.ACTION_SLEEP_TIMER_EXPIRED
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun schedule(triggerAtMillis: Long) {
        val pendingIntent = createPendingIntent()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent,
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            }
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "Exact alarm denied; using inexact sleep timer")
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    override fun cancel() {
        alarmManager.cancel(createPendingIntent())
    }

    private companion object {
        const val TAG = "SleepTimerScheduler"
    }
}

/**
 * Owns the playback-stopping timers extracted from [MusicService]:
 *
 * - the duration sleep timer (an [SleepTimerAlarmScheduler]-backed alarm that pauses playback),
 * - the end-of-track sleep timer (pause when the current song finishes), and
 * - counted play (repeat the current song N times, then pause).
 *
 * The service forwards player callbacks via [handleMediaItemTransition] and
 * [onDurationSleepTimerExpired]; everything else is driven through the public setters.
 */
class PlaybackTimerController(
    private val playerProvider: () -> Player,
    private val alarmScheduler: SleepTimerAlarmScheduler,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val onCountedPlayChanged: (Int?) -> Unit = {},
) {

    private var endOfTrackTimerSongId: String? = null

    private var countedPlayActive = false
    private var countedPlayTarget = 0
    private var countedPlayCount = 0
    private var countedOriginalId: String? = null
    private var countedPlayListener: Player.Listener? = null
    /** The player the counted-play listener is on, so a swap can move it (AUD-051). */
    private var countedPlayer: Player? = null
    /** The listener's own repeat mode before counted play forced REPEAT_ONE (AUD-051). */
    private var repeatModeBeforeCountedPlay = Player.REPEAT_MODE_OFF

    fun setDurationSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            cancelSleepTimers()
            return
        }
        stopCountedPlay()
        endOfTrackTimerSongId = null
        alarmScheduler.schedule(clock() + (minutes * 60_000L))
        Timber.tag(TAG).d("Sleep timer set for %d minutes", minutes)
    }

    fun setEndOfTrackSleepTimer(enabled: Boolean) {
        if (!enabled) {
            endOfTrackTimerSongId = null
            Timber.tag(TAG).d("End-of-track timer disabled")
            return
        }
        stopCountedPlay()
        alarmScheduler.cancel()
        val currentSongId = playerProvider().currentMediaItem?.mediaId
        if (currentSongId.isNullOrBlank()) {
            endOfTrackTimerSongId = null
            Timber.tag(TAG).d("End-of-track timer ignored: no active song")
            return
        }
        endOfTrackTimerSongId = currentSongId
        Timber.tag(TAG).d("End-of-track timer set for mediaId=%s", currentSongId)
    }

    fun cancelSleepTimers() {
        alarmScheduler.cancel()
        endOfTrackTimerSongId = null
        Timber.tag(TAG).d("Sleep timers cancelled")
    }

    /** The scheduled duration alarm fired: stop playback. */
    fun onDurationSleepTimerExpired() {
        alarmScheduler.cancel()
        playerProvider().pause()
    }

    /** Clears the end-of-track timer without touching the duration alarm (e.g. STATE_ENDED). */
    fun clearEndOfTrackTimer() {
        endOfTrackTimerSongId = null
    }

    /**
     * End-of-track handling: pause at the start of the next item when the tracked song
     * finished naturally; drop the timer when the user manually changes songs.
     */
    fun handleMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val eotTargetSongId = endOfTrackTimerSongId
        if (eotTargetSongId.isNullOrBlank()) return

        val player = playerProvider()
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            val previousSongId = player.run {
                if (previousMediaItemIndex != C.INDEX_UNSET) {
                    runCatching { getMediaItemAt(previousMediaItemIndex).mediaId }.getOrNull()
                } else {
                    null
                }
            }
            if (previousSongId == eotTargetSongId) {
                endOfTrackTimerSongId = null
                player.seekTo(0L)
                player.pause()
                Timber.tag(TAG).d("Paused playback at end of track timer")
            }
        } else if (mediaItem?.mediaId != eotTargetSongId) {
            endOfTrackTimerSongId = null
            Timber.tag(TAG).d("Cleared end-of-track timer after manual track change")
        }
    }

    fun startCountedPlay(count: Int) {
        require(count in 1..10)
        val player = playerProvider()
        val currentItem = player.currentMediaItem ?: return

        stopCountedPlay()
        cancelSleepTimers()

        countedPlayTarget = count
        countedPlayCount = 1
        countedOriginalId = currentItem.mediaId
        countedPlayActive = true

        repeatModeBeforeCountedPlay = player.repeatMode
        player.repeatMode = Player.REPEAT_MODE_ONE

        val listener = object : Player.Listener {

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (!countedPlayActive) return

                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    countedPlayCount++

                    if (countedPlayCount > countedPlayTarget) {
                        // The current player, not the one counted play started on (AUD-051).
                        playerProvider().pause()
                        stopCountedPlay()
                        return
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!countedPlayActive) return

                if (mediaItem?.mediaId != countedOriginalId) {
                    stopCountedPlay()
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (countedPlayActive && repeatMode != Player.REPEAT_MODE_ONE) {
                    stopCountedPlay(restoreRepeatMode = false)
                }
            }
        }

        countedPlayListener = listener
        countedPlayer = player
        player.addListener(listener)
        onCountedPlayChanged(count)
    }

    fun stopCountedPlay(restoreRepeatMode: Boolean = true) {
        if (!countedPlayActive) return

        countedPlayActive = false
        onCountedPlayChanged(null)
        countedPlayTarget = 0
        countedPlayCount = 0
        countedOriginalId = null

        countedPlayListener?.let {
            (countedPlayer ?: playerProvider()).removeListener(it)
        }
        countedPlayListener = null
        countedPlayer = null

        if (restoreRepeatMode) {
            // The listener's own mode back, not a forced OFF (AUD-051).
            playerProvider().repeatMode = repeatModeBeforeCountedPlay
        }
    }

    /**
     * The session player was replaced (master rebuild, crossfade). Counted play's listener was left
     * on the old one and stopped counting (AUD-051); it moves to the current player.
     */
    fun onPlayerSwapped() {
        val listener = countedPlayListener ?: return
        val current = playerProvider()
        if (current === countedPlayer) return
        countedPlayer?.removeListener(listener)
        current.addListener(listener)
        countedPlayer = current
    }

    /** Cancels every timer; call from the service's teardown path. */
    fun release() {
        alarmScheduler.cancel()
        stopCountedPlay()
        endOfTrackTimerSongId = null
    }

    private companion object {
        const val TAG = "PlaybackTimerController"
    }
}
