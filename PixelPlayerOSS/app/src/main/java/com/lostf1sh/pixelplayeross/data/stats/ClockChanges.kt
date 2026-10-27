package com.lostf1sh.pixelplayeross.data.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DailyMixSchedule
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * That the device's clock moved under a screen that is open (B42-fix1, item 4): the local day passed, or the date, the time or the time zone
 * was changed. The screens that are a function of "now" (Stats' Today and Week to date, the months Top offers) read the clock whenever they
 * load, but nothing asked them to load again while they sat open, so past midnight a Stats page left on screen went on calling yesterday
 * "Today". This is what asks.
 *
 * Cold: the receiver and the timer exist only while something collects it.
 */
interface ClockChanges {
    val changes: Flow<Unit>
}

/**
 * The timer half of [ClockChanges], apart from Android so a test can drive it on virtual time: it emits at each local midnight, as [clock] and
 * [zone] have it **at the time of arming**, and emits again, arming anew, every time [signals] says the date, the time or the zone changed (a
 * zone or clock change moves the next midnight; the timer of the old one is thrown away).
 */
internal fun clockChangesFlow(
    clock: () -> Long,
    zone: () -> TimeZone,
    signals: Flow<Unit>,
    marginMs: Long = MIDNIGHT_MARGIN_MS,
): Flow<Unit> = channelFlow {
    var timer: Job? = null
    fun arm() {
        timer?.cancel()
        timer = launch {
            while (true) {
                delay(DailyMixSchedule.msUntilNextLocalMidnight(clock(), zone()) + marginMs)
                send(Unit)
            }
        }
    }
    arm()
    signals.collect {
        send(Unit)
        arm()
    }
}

/** Past midnight, so the day read on waking is already the new one. */
internal const val MIDNIGHT_MARGIN_MS = 1_000L

/** The device's own: the date, time and zone broadcasts, and the local midnight. */
@Singleton
class SystemClockChanges @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClockChanges {
    override val changes: Flow<Unit> = clockChangesFlow(System::currentTimeMillis, TimeZone::getDefault, systemSignals())

    private fun systemSignals(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.conflate()
}
