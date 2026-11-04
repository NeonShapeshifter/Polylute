package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionRepository
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Home's way to the mix of the day and "Tu mix de hoy", and the thing that notices the day change (B28).
 *
 * The lists are the automatic collections' ([AutoCollectionRepository]), as they are shown in Playlists: this class
 * only turns them into the songs Home plays and decides when to ask for a new day's. It builds nothing of its own.
 *
 * In Dedicated Mode the app is HOME under Lock Task and can run for days without an activity start (AUD-132). The day change reaches
 * it three ways: a wake-up at the next local midnight (a coroutine delay does not advance while the phone sleeps, so it can fire
 * late), the system's date, time and zone broadcasts, and the first use of the day (`MainActivity.onResume`).
 */
@Singleton
class DailyMixStateHolder @Inject constructor(
    private val collections: AutoCollectionRepository,
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
) {
    val homeMixes = collections.homeMixes
    fun refreshSuggestionsPreview(): Job = appScope.launch { collections.refreshSuggestionsPreview() }

    private var midnightJob: Job? = null
    private var dayChangeReceiver: BroadcastReceiver? = null

    /** The clock and the zone the midnight timer reads, replaceable by a test. */
    internal var clock: () -> Long = System::currentTimeMillis
    internal var zone: () -> TimeZone = TimeZone::getDefault

    /** Both ask within milliseconds of each other at a start; one check is enough. */
    private val dayCheckMutex = Mutex()

    /** The mix of the day: the same list all day. */
    val dailyMixSongs: StateFlow<ImmutableList<Song>> = collections.homeMixes
        .map { mixes -> mixes.daily.map { it.song }.toImmutableList() }
        .stateIn(appScope, SharingStarted.Eagerly, persistentListOf())

    /** "Tu mix de hoy": the same blend leaning on rediscovery and the unexplored, never repeating the mix of the day. */
    val yourMixSongs: StateFlow<ImmutableList<Song>> = collections.homeMixes
        .map { mixes -> mixes.yours.map { it.song }.toImmutableList() }
        .stateIn(appScope, SharingStarted.Eagerly, persistentListOf())

    /**
     * Whether the mixes have been built at least once in this process, so a surface can tell "not built yet" from "built and empty": an
     * honestly empty mix showed a spinner forever before.
     */
    val hasGenerated: StateFlow<Boolean> = collections.homeMixes
        .map { it.isReady }
        .stateIn(appScope, SharingStarted.Eagerly, false)

    /** Takes a track out of today's mix, after a file deletion, for the rest of the day. */
    fun removeFromDailyMix(locator: String) {
        appScope.launch { collections.removeFromDailyMix(locator) }
    }

    /** Builds the collections if this process has not, or if they are old. The rest of the time it costs nothing. */
    fun updateDailyMix() {
        appScope.launch { collections.ensureFresh() }
    }

    /** A first look at the mixes when the app starts. */
    fun loadPersistedDailyMix() = updateDailyMix()

    /** Builds them again regardless of the day: a user-initiated refresh. */
    fun forceUpdate() {
        appScope.launch { collections.refresh("user requested a refresh", regenerate = true) }
    }

    /**
     * The day-boundary check.
     *
     * B42-fix1: it asks the collections whether the mix they hold is **for today**, which is the day they stored it for (`Built.epochDay`, in
     * the zone in force when it was made) against today's in the zone in force now. It used to compare the day of *when the last build ran*
     * with today's, both in the current zone, so a time-zone change that moves the local date without the build's instant straddling midnight
     * (built 10:00 in Madrid, the phone put on Pacific time the same afternoon) left a mix that was another day's.
     */
    fun checkAndUpdateIfNeeded() {
        appScope.launch {
            dayCheckMutex.withLock {
                collections.refreshIfDayChanged()
            }
        }
    }

    /** Renews the mix at the day boundary while the app stays open. Idempotent. */
    fun startDayBoundaryWatch() {
        if (dayChangeReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    checkAndUpdateIfNeeded()
                    // A zone or clock change moves the next midnight.
                    scheduleMidnight()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            dayChangeReceiver = receiver
        }
        scheduleMidnight()
    }

    private fun scheduleMidnight() {
        midnightJob?.cancel()
        midnightJob = appScope.launch {
            while (true) {
                val wait = DailyMixSchedule.msUntilNextLocalMidnight(clock(), zone())
                delay(wait + MIDNIGHT_MARGIN_MS)
                checkAndUpdateIfNeeded()
            }
        }
    }

    fun onCleared() {
        midnightJob?.cancel()
        dayChangeReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        dayChangeReceiver = null
    }

    private companion object {
        /** Past midnight, so the day computed on waking is already the new one. */
        const val MIDNIGHT_MARGIN_MS = 1_000L
    }
}
