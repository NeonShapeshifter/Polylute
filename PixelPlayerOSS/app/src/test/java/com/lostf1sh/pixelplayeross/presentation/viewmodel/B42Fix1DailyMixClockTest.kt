package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.collections.auto.AutoCollectionRepository
import com.lostf1sh.pixelplayeross.data.collections.auto.HomeMixes
import com.lostf1sh.pixelplayeross.data.stats.Kit.HOUR
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.data.stats.SystemClockChanges
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * B42-fix1, item 4: the mix of the day and the system's clock broadcasts, with the real receivers (Robolectric) and virtual time.
 *
 *  - [SystemClockChanges] is what tells Stats the date, the time or the zone changed; it holds a receiver only while collected.
 *  - [DailyMixStateHolder] renews "Tu mix de hoy" at local midnight and on those same broadcasts, and, since this batch, by asking whether the mix
 *    it holds is **for today** (the day it was stored for) rather than whether the last build's instant is on today's date.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B42Fix1DailyMixClockTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun send(action: String) {
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clockReceivers() = shadowOf(app).registeredReceivers.filter {
        it.intentFilter.hasAction(Intent.ACTION_TIMEZONE_CHANGED)
    }

    @Test
    fun the_system_clock_changes_come_from_the_broadcasts_and_the_receiver_goes_with_the_collector() = runTest {
        val changes = SystemClockChanges(app)
        var seen = 0
        val job = backgroundScope.launch { changes.changes.collect { seen++ } }
        runCurrent()
        assertThat(clockReceivers()).hasSize(1)

        send(Intent.ACTION_TIMEZONE_CHANGED)
        runCurrent()
        assertThat(seen).isEqualTo(1)
        send(Intent.ACTION_DATE_CHANGED)
        runCurrent()
        assertThat(seen).isEqualTo(2)
        send(Intent.ACTION_TIME_CHANGED)
        runCurrent()
        assertThat(seen).isEqualTo(3)

        job.cancel()
        runCurrent()
        assertThat(clockReceivers()).isEmpty()
    }

    // ── the mix of the day ──────────────────────────────────────────────────────────────────────────

    private class HolderRig(val holder: DailyMixStateHolder, val repository: AutoCollectionRepository, var zone: TimeZone)

    private fun TestScope.holderRig(startMs: Long): HolderRig {
        val repository = mockk<AutoCollectionRepository>(relaxed = true)
        every { repository.homeMixes } returns MutableStateFlow(HomeMixes.Initial)
        val holder = DailyMixStateHolder(repository, app, backgroundScope)
        val rig = HolderRig(holder, repository, TimeZone.getTimeZone("UTC"))
        holder.clock = { startMs + testScheduler.currentTime }
        holder.zone = { rig.zone }
        return rig
    }

    @Test
    fun a_zone_broadcast_asks_whether_the_mix_is_for_today() = runTest {
        val rig = holderRig(at(2026, 10, 15, 20))
        rig.holder.startDayBoundaryWatch()
        runCurrent()
        coVerify(exactly = 0) { rig.repository.refreshIfDayChanged() }

        send(Intent.ACTION_TIMEZONE_CHANGED)
        runCurrent()
        coVerify(exactly = 1) { rig.repository.refreshIfDayChanged() }
        // it asks the collections about the day; it does not rebuild by itself on a guess about the instant of the last build
        coVerify(exactly = 0) { rig.repository.refresh(any()) }
        rig.holder.onCleared()
    }

    @Test
    fun midnight_asks_and_so_does_the_next_one() = runTest {
        val rig = holderRig(at(2026, 10, 15, 20))
        rig.holder.startDayBoundaryWatch()
        runCurrent()
        advanceTimeBy(4 * HOUR - 60_000L)
        runCurrent()
        coVerify(exactly = 0) { rig.repository.refreshIfDayChanged() }
        advanceTimeBy(60_000L + 2_000L)
        runCurrent()
        coVerify(exactly = 1) { rig.repository.refreshIfDayChanged() }
        advanceTimeBy(24 * HOUR)
        runCurrent()
        coVerify(exactly = 2) { rig.repository.refreshIfDayChanged() }
        rig.holder.onCleared()
    }

    @Test
    fun a_zone_change_moves_the_midnight_it_waits_for() = runTest {
        val rig = holderRig(at(2026, 10, 15, 20))
        rig.holder.startDayBoundaryWatch()
        runCurrent()
        // Kiritimati: 10:00 on the 16th there, so its midnight is 14 hours away, not the 4 the old zone had left
        rig.zone = TimeZone.getTimeZone("Pacific/Kiritimati")
        send(Intent.ACTION_TIMEZONE_CHANGED)
        runCurrent()
        coVerify(exactly = 1) { rig.repository.refreshIfDayChanged() }
        advanceTimeBy(4 * HOUR + 5_000L)
        runCurrent()
        coVerify(exactly = 1) { rig.repository.refreshIfDayChanged() }
        advanceTimeBy(10 * HOUR)
        runCurrent()
        coVerify(exactly = 2) { rig.repository.refreshIfDayChanged() }
        rig.holder.onCleared()
    }
}
