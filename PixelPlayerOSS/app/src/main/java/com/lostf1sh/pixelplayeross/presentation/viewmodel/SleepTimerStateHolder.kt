package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.os.SystemClock
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.service.MusicNotificationProvider
import com.lostf1sh.pixelplayeross.data.service.MusicService
import com.lostf1sh.pixelplayeross.data.service.SleepTimerReceiver
import com.lostf1sh.pixelplayeross.data.EotStateHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages sleep timer state and operations.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - Duration-based sleep timer with AlarmManager
 * - End-of-track (EOT) timer functionality
 * - Counted play functionality
 * - Timer display state
 */
@Singleton
class SleepTimerStateHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _sleepTimerEndTimeMillis = MutableStateFlow<Long?>(null)

    private val _isEndOfTrackTimerActive = MutableStateFlow(value = false)
    val isEndOfTrackTimerActive: StateFlow<Boolean> = _isEndOfTrackTimerActive.asStateFlow()

    private val _activeTimerValueDisplay = MutableStateFlow<String?>(null)
    val activeTimerValueDisplay: StateFlow<String?> = _activeTimerValueDisplay.asStateFlow()

    /** When non-null, a duration-based sleep timer is active with this many minutes (matches [predefinedTimes]). */
    private val _activeTimerDurationMinutes = MutableStateFlow<Int?>(null)
    val activeTimerDurationMinutes: StateFlow<Int?> = _activeTimerDurationMinutes.asStateFlow()

    private val _playCount = MutableStateFlow(0f)
    val playCount: StateFlow<Float> = _playCount.asStateFlow()

    private var sleepTimerJob: Job? = null
    private var countedStateJob: Job? = null
    private var eotSongMonitorJob: Job? = null

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private var scope: CoroutineScope? = null
    private var toastEmitter: (suspend (String) -> Unit)? = null
    private var mediaControllerProvider: (() -> MediaController?)? = null
    private var currentSongIdProvider: (() -> StateFlow<String?>)? = null
    private var songTitleResolver: ((String?) -> String)? = null

    private fun sleepTimerPendingIntent(): PendingIntent {
        val intent = Intent(context, SleepTimerReceiver::class.java).apply {
            // UI and service must schedule/cancel the same OS alarm, including session commands.
            action = MusicService.ACTION_SLEEP_TIMER_EXPIRED
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Initialize with dependencies from ViewModel.
     * Must be called before using timer functions.
     */
    fun initialize(
        scope: CoroutineScope,
        toastEmitter: suspend (String) -> Unit,
        mediaControllerProvider: () -> MediaController?,
        currentSongIdProvider: () -> StateFlow<String?>,
        songTitleResolver: (String?) -> String
    ) {
        this.scope = scope
        this.toastEmitter = toastEmitter
        this.mediaControllerProvider = mediaControllerProvider
        this.currentSongIdProvider = currentSongIdProvider
        this.songTitleResolver = songTitleResolver
        countedStateJob?.cancel()
        countedStateJob = scope.launch {
            com.lostf1sh.pixelplayeross.data.CountedPlayStateHolder.activeTarget.collect { target ->
                _playCount.value = target?.toFloat() ?: 0f
            }
        }
    }

    /**
     * Set a duration-based sleep timer.
     * Uses AlarmManager for reliability + coroutine for UI state clearing.
     */
    fun setSleepTimer(durationMinutes: Int) {
        require(durationMinutes in 1..1439)
        cancelCountedPlay()
        val scope = this.scope ?: return

        if (_isEndOfTrackTimerActive.value) {
            eotSongMonitorJob?.cancel()
            cancelSleepTimer(suppressDefaultToast = true)
        }

        sleepTimerJob?.cancel()
        val durationMillis = TimeUnit.MINUTES.toMillis(durationMinutes.toLong())
        val endTime = System.currentTimeMillis() + durationMillis
        _sleepTimerEndTimeMillis.value = endTime
        _activeTimerDurationMinutes.value = durationMinutes
        _activeTimerValueDisplay.value = context.getString(
            R.string.sleep_timer_n_minutes_format,
            durationMinutes
        )

        val pendingIntent = sleepTimerPendingIntent()
        // The alarm runs on elapsed time (AUD-050): a wall-clock alarm moved with every clock or
        // time-zone change. `endTime` above is only the countdown shown.
        val triggerAtElapsed = SystemClock.elapsedRealtime() + durationMillis

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtElapsed,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtElapsed,
                        pendingIntent,
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsed,
                    pendingIntent,
                )
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to schedule exact alarm for sleep timer")
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtElapsed,
                pendingIntent,
            )
        }

        scope.launch {
            toastEmitter?.invoke(
                context.getString(R.string.sleep_timer_set_for_minutes_toast, durationMinutes)
            )
        }
    }

    /**
     * Repeat the current song N times, including the playback in progress.
     */
    fun playCounted(count: Int) {
        require(count in 1..10)
        cancelSleepTimer(suppressDefaultToast = true)
        val args = Bundle().apply { putInt("count", count) }
        mediaControllerProvider?.invoke()?.sendCustomCommand(
            SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_COUNTED_PLAY, Bundle.EMPTY),
            args
        )
    }

    /**
     * Cancel counted play mode.
     */
    fun cancelCountedPlay() {
        val args = Bundle()
        _playCount.value = 0f
        mediaControllerProvider?.invoke()?.sendCustomCommand(
            SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_CANCEL_COUNTED_PLAY, Bundle.EMPTY),
            args
        )
    }

    /**
     * Set or cancel end-of-track timer.
     */
    fun setEndOfTrackTimer(enable: Boolean, currentSongId: String?) {
        val scope = this.scope ?: return

        if (enable) {
            if (currentSongId == null) {
                scope.launch {
                    toastEmitter?.invoke(context.getString(R.string.sleep_timer_eot_no_song_toast))
                }
                return
            }

            cancelCountedPlay()
            cancelSleepTimer(suppressDefaultToast = true)
            _activeTimerDurationMinutes.value = null
            _activeTimerValueDisplay.value = context.getString(R.string.sleep_timer_display_eot)
            _isEndOfTrackTimerActive.value = true
            EotStateHolder.setEotTargetSong(currentSongId)
            // One end-of-track timer, the service's (AUD-017): it sees every natural end, also with
            // the UI gone. This side keeps only the display and the toasts.
            sendEndOfTrackCommand(enabled = true)

            sleepTimerJob?.cancel()
            _sleepTimerEndTimeMillis.value = null

            eotSongMonitorJob?.cancel()
            eotSongMonitorJob = scope.launch {
                currentSongIdProvider?.invoke()
                    ?.filterNotNull()
                    ?.collect { newSongId ->
                        if (_isEndOfTrackTimerActive.value &&
                            (EotStateHolder.eotTargetSongId.value != null) &&
                            (newSongId != EotStateHolder.eotTargetSongId.value)
                        ) {

                        val oldSongTitle = songTitleResolver?.invoke(EotStateHolder.eotTargetSongId.value)
                            ?: context.getString(R.string.sleep_timer_label_previous_track)
                        val newSongTitle = songTitleResolver?.invoke(newSongId)
                            ?: context.getString(R.string.sleep_timer_label_current_track)

                        toastEmitter?.invoke(
                            context.getString(
                                R.string.sleep_timer_eot_song_changed_toast,
                                oldSongTitle,
                                newSongTitle
                            )
                        )
                        cancelSleepTimer(suppressDefaultToast = true)

                        eotSongMonitorJob?.cancel()
                        eotSongMonitorJob = null
                    }
                }
            }

            scope.launch {
                toastEmitter?.invoke(context.getString(R.string.sleep_timer_eot_stop_at_end_toast))
            }
        } else {
            eotSongMonitorJob?.cancel()
            if (_isEndOfTrackTimerActive.value && EotStateHolder.eotTargetSongId.value != null) {
                cancelSleepTimer()
            }
        }
    }

    /**
     * Cancel any active sleep timer or EOT timer.
     */
    fun cancelSleepTimer(overrideToastMessage: String? = null, suppressDefaultToast: Boolean = false) {
        val scope = this.scope ?: return
        val wasAnythingActive = _activeTimerValueDisplay.value != null

        val pendingIntent = sleepTimerPendingIntent()
        alarmManager.cancel(pendingIntent)

        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerEndTimeMillis.value = null

        eotSongMonitorJob?.cancel()
        eotSongMonitorJob = null
        if (_isEndOfTrackTimerActive.value) sendEndOfTrackCommand(enabled = false)
        _isEndOfTrackTimerActive.value = false
        EotStateHolder.setEotTargetSong(null)

        _activeTimerDurationMinutes.value = null
        _activeTimerValueDisplay.value = null

        when {
            overrideToastMessage != null -> scope.launch { toastEmitter?.invoke(overrideToastMessage) }
            !suppressDefaultToast && wasAnythingActive -> scope.launch {
                toastEmitter?.invoke(context.getString(R.string.sleep_timer_cancelled_toast))
            }
        }
    }

    private fun sendEndOfTrackCommand(enabled: Boolean) {
        val args = Bundle().apply { putBoolean(MusicNotificationProvider.EXTRA_END_OF_TRACK_ENABLED, enabled) }
        mediaControllerProvider?.invoke()?.sendCustomCommand(
            SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_SET_SLEEP_TIMER_END_OF_TRACK, Bundle.EMPTY),
            args
        )
    }

    /**
     * Cleanup when ViewModel is cleared.
     */
    fun onCleared() {
        countedStateJob?.cancel()
        sleepTimerJob?.cancel()
        eotSongMonitorJob?.cancel()
        scope = null
        toastEmitter = null
        mediaControllerProvider = null
        currentSongIdProvider = null
        songTitleResolver = null
    }

}
