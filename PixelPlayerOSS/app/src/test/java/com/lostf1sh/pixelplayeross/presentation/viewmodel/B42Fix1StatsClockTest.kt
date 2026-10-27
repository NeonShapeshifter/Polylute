package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.ClockChanges
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.Kit.MIN
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import io.mockk.mockk
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * B42-fix1, item 4: the Stats view model follows the device's clock and zone **while the screen stays open** (see `B42Fix1ClockTest` for the
 * layer under). Before this batch every load read the clock afresh, but nothing asked it to load again: a Stats page left open past local
 * midnight went on calling yesterday "Today" until a song ended or the page was left and come back to, the year Top opened on never became the
 * new year, and the months it offers were read from the clock in composition.
 *
 * The clock the screen follows moves here by hand, and the signal that says so ([ClockChanges]) is sent by the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class B42Fix1StatsClockTest {
    private val dispatcher = StandardTestDispatcher()
    private val utc = ZoneId.of("UTC")
    private val kiritimati = ZoneId.of("Pacific/Kiritimati")

    private val catalog = Kit.catalog((1..3).map { Kit.song(it.toLong()) })

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = dispatcher
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
    }

    private class Provider(var ledger: ListeningLedger) : LedgerProvider {
        override val changes: Flow<Long> = emptyFlow()
        override suspend fun ledger(): ListeningLedger = ledger
    }

    private class Ticks : ClockChanges {
        val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override val changes: Flow<Unit> = flow
    }

    /** The clock and the zone of the screen: a test moves them, and then sends the signal. */
    private class Clock(var ms: Long, var zone: ZoneId)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(clock: Clock, ticks: Ticks, ledger: ListeningLedger): StatsViewModel =
        StatsViewModel(Provider(ledger), mockk<PlaylistPreferencesRepository>(relaxed = true), LibraryChangeSignal(), dispatchers, ticks).also {
            it.clock = { clock.ms }
            it.zone = { clock.zone }
        }

    private fun ledgerOf(vararg playsAt: Long) =
        ListeningLedger.build(playsAt.mapIndexed { i, end -> Kit.play((1 + i % 3).toLong(), end, occurrence = "o$i") }, catalog)

    private fun kotlinx.coroutines.test.TestScope.moveTheClock(ticks: Ticks) {
        ticks.flow.tryEmit(Unit)
        advanceUntilIdle()
    }

    // ── Stats: Today, the week, the month ────────────────────────────────────────────────────────────

    @Test
    fun today_rolls_over_at_local_midnight_without_the_page_being_left() = runTest(dispatcher) {
        val clock = Clock(at(2026, 10, 15, 23, 50), utc)
        val ticks = Ticks()
        val vm = viewModel(clock, ticks, ledgerOf(at(2026, 10, 15, 23, 40)))
        advanceUntilIdle()
        vm.onRangeSelected(StatsTimeRange.DAY)
        advanceUntilIdle()
        val before = vm.uiState.value.current!!
        assertThat(before.fromMs).isEqualTo(at(2026, 10, 15, 0))
        assertThat(before.totals.plays).isEqualTo(1)

        // midnight passes: the page is still open, nothing was played, nothing was tapped
        clock.ms = at(2026, 10, 16, 0, 5)
        moveTheClock(ticks)

        val after = vm.uiState.value.current!!
        assertThat(after.fromMs).isEqualTo(at(2026, 10, 16, 0))
        assertThat(after.totals.plays).isEqualTo(0)
    }

    @Test
    fun a_time_zone_change_moves_the_day_the_page_is_about() = runTest(dispatcher) {
        // 20:00 UTC on the 15th, and two plays: 05:00 UTC and 15:00 UTC the same day
        val clock = Clock(at(2026, 10, 15, 20), utc)
        val ticks = Ticks()
        val vm = viewModel(clock, ticks, ledgerOf(at(2026, 10, 15, 5), at(2026, 10, 15, 15)))
        advanceUntilIdle()
        vm.onRangeSelected(StatsTimeRange.DAY)
        advanceUntilIdle()
        assertThat(vm.uiState.value.current!!.totals.plays).isEqualTo(2)

        // the phone is put on Kiritimati time (UTC+14): it is 10:00 on the 16th there, and "today" began at 10:00 UTC on the 15th, after the first play
        clock.zone = kiritimati
        moveTheClock(ticks)
        val inKiritimati = vm.uiState.value.current!!
        assertThat(inKiritimati.fromMs).isEqualTo(at(2026, 10, 15, 10))
        assertThat(inKiritimati.totals.plays).isEqualTo(1)

        // and back on UTC, today is the 15th from 00:00 again
        clock.zone = utc
        moveTheClock(ticks)
        assertThat(vm.uiState.value.current!!.fromMs).isEqualTo(at(2026, 10, 15, 0))
        assertThat(vm.uiState.value.current!!.totals.plays).isEqualTo(2)
    }

    @Test
    fun the_month_changing_moves_the_month_the_page_is_on() = runTest(dispatcher) {
        val clock = Clock(at(2026, 10, 31, 23, 50), utc)
        val ticks = Ticks()
        val vm = viewModel(clock, ticks, ledgerOf(at(2026, 10, 31, 23, 40)))
        advanceUntilIdle()
        vm.onRangeSelected(StatsTimeRange.MONTH)
        moveTheClock(ticks) // the first look at the clock, in October
        assertThat(vm.uiState.value.month).isEqualTo(YearMonth.of(2026, 10))
        assertThat(vm.uiState.value.current!!.fromMs).isEqualTo(at(2026, 10, 1, 0))
        assertThat(vm.uiState.value.current!!.totals.plays).isEqualTo(1)

        clock.ms = at(2026, 11, 1, 0, 5)
        moveTheClock(ticks)
        assertThat(vm.uiState.value.month).isEqualTo(YearMonth.of(2026, 11))
        val november = vm.uiState.value.current!!
        assertThat(november.fromMs).isEqualTo(at(2026, 11, 1, 0))
        assertThat(november.totals.plays).isEqualTo(0)
    }

    @Test
    fun homes_card_is_the_first_range_with_listening_in_the_new_days_terms() = runTest(dispatcher) {
        // Sunday 23:50, a play at 23:40: the week (from Monday the 12th) has it. At Monday 00:05 the week is empty, and the month is the card.
        val clock = Clock(at(2026, 10, 18, 23, 50), utc)
        val ticks = Ticks()
        val vm = viewModel(clock, ticks, ledgerOf(at(2026, 10, 18, 23, 40)))
        advanceUntilIdle()
        assertThat(vm.homeOverview.value!!.period).isEqualTo(StatsPeriod.Preset(StatsTimeRange.WEEK))

        clock.ms = at(2026, 10, 19, 0, 5)
        moveTheClock(ticks)
        assertThat(vm.homeOverview.value!!.period).isEqualTo(StatsPeriod.Preset(StatsTimeRange.MONTH))
    }

    // ── Top N: this year, and the months offered ─────────────────────────────────────────────────────

    @Test
    fun the_year_top_opened_on_becomes_the_new_year_when_the_year_changes() = runTest(dispatcher) {
        val clock = Clock(at(2026, 12, 31, 23, 50), utc)
        val ticks = Ticks()
        val vm = viewModel(clock, ticks, ledgerOf(at(2026, 12, 31, 23, 40)))
        advanceUntilIdle()
        moveTheClock(ticks) // the first look, in 2026
        assertThat(vm.uiState.value.top.from).isEqualTo(YearMonth.of(2026, 1))
        assertThat(vm.uiState.value.top.to).isEqualTo(YearMonth.of(2026, 12))

        clock.ms = at(2027, 1, 1, 0, 5)
        moveTheClock(ticks)
        assertThat(vm.uiState.value.month).isEqualTo(YearMonth.of(2027, 1))
        assertThat(vm.uiState.value.top.from).isEqualTo(YearMonth.of(2027, 1))
        assertThat(vm.uiState.value.top.to).isEqualTo(YearMonth.of(2027, 12))
    }

    @Test
    fun a_choice_of_months_the_listener_made_is_never_moved_by_the_clock() = runTest(dispatcher) {
        val clock = Clock(at(2026, 12, 31, 23, 50), utc)
        val ticks = Ticks()
        val vm = viewModel(clock, ticks, ledgerOf(at(2026, 12, 31, 23, 40)))
        advanceUntilIdle()
        vm.onTopMonthsSelected(YearMonth.of(2026, 3), YearMonth.of(2026, 8))
        advanceUntilIdle()

        clock.ms = at(2027, 1, 1, 0, 5)
        moveTheClock(ticks)
        assertThat(vm.uiState.value.month).isEqualTo(YearMonth.of(2027, 1))
        assertThat(vm.uiState.value.top.from).isEqualTo(YearMonth.of(2026, 3))
        assertThat(vm.uiState.value.top.to).isEqualTo(YearMonth.of(2026, 8))
    }

    @Test
    fun a_clock_that_did_not_change_the_month_leaves_the_pickers_where_they_are() = runTest(dispatcher) {
        val clock = Clock(at(2026, 10, 15, 12), utc)
        val ticks = Ticks()
        val vm = viewModel(clock, ticks, ledgerOf(at(2026, 10, 15, 11)))
        advanceUntilIdle()
        moveTheClock(ticks)
        val top = vm.uiState.value.top
        clock.ms += 20 * MIN
        moveTheClock(ticks)
        assertThat(vm.uiState.value.top.from).isEqualTo(top.from)
        assertThat(vm.uiState.value.top.to).isEqualTo(top.to)
        assertThat(vm.uiState.value.month).isEqualTo(YearMonth.of(2026, 10))
    }
}
