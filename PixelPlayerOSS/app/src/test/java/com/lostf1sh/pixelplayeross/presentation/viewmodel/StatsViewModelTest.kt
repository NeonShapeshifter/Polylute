package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.Kit.MIN
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsRankMode
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import io.mockk.coVerify
import io.mockk.mockk
import java.time.YearMonth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * K-02: an answer that is not for the current selection never overwrites it. The provider here answers each request after a delay the
 * test chooses, on virtual time, so the order the answers come back in is the test's to set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = at(2026, 10, 15, 20)

    private class SlowProvider(val ledger: ListeningLedger) : LedgerProvider {
        val delays = ArrayDeque<Long>()
        var calls = 0
        val changeFlow = MutableSharedFlow<Long>(extraBufferCapacity = 8)
        override val changes: Flow<Long> = changeFlow
        override suspend fun ledger(): ListeningLedger {
            calls++
            delay(delays.removeFirstOrNull() ?: 0L)
            return ledger
        }
    }

    private val catalog = Kit.catalog((1..3).map { Kit.song(it.toLong()) })
    private val ledger = ListeningLedger.build(
        listOf(
            Kit.play(1, now - 10 * MIN, occurrence = "a"),
            Kit.play(2, at(2026, 3, 10, 12), occurrence = "b"),
            Kit.play(3, at(2026, 4, 10, 12), occurrence = "c"),
        ),
        catalog,
    )
    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = dispatcher
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(provider: SlowProvider, playlists: PlaylistPreferencesRepository = mockk(relaxed = true)) =
        StatsViewModel(provider, playlists, LibraryChangeSignal(), dispatchers).also {
            it.clock = { now }
            it.zone = { Kit.zone }
        }

    @Test
    fun `a slow answer for the period that was left does not overwrite the one that was chosen`() = runTest(dispatcher) {
        val provider = SlowProvider(ledger)
        val vm = viewModel(provider)
        advanceUntilIdle()
        assertThat(vm.uiState.value.current?.period).isEqualTo(StatsPeriod.Week)

        provider.delays += listOf(1_000L, 0L) // MONTH answers late, YEAR at once
        vm.onRangeSelected(StatsTimeRange.MONTH)
        runCurrent()
        vm.onRangeSelected(StatsTimeRange.YEAR)
        advanceTimeBy(10)
        runCurrent()
        assertThat(vm.uiState.value.period).isEqualTo(StatsPeriod.Preset(StatsTimeRange.YEAR))
        assertThat(vm.uiState.value.current?.period).isEqualTo(StatsPeriod.Preset(StatsTimeRange.YEAR))

        advanceTimeBy(5_000)
        runCurrent()
        assertThat(vm.uiState.value.period).isEqualTo(StatsPeriod.Preset(StatsTimeRange.YEAR))
        assertThat(vm.uiState.value.report?.period).isEqualTo(StatsPeriod.Preset(StatsTimeRange.YEAR))
        assertThat(vm.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `an automatic refresh that comes back late does not take the screen back to the old period`() = runTest(dispatcher) {
        val provider = SlowProvider(ledger)
        val vm = viewModel(provider)
        advanceUntilIdle()

        provider.delays += listOf(2_000L, 0L) // the refresh of WEEK is slow; the tap on MONTH is not
        provider.changeFlow.tryEmit(1L)
        provider.changeFlow.tryEmit(2L)
        advanceTimeBy(2_500) // past the debounce: the refresh of WEEK is under way
        vm.onRangeSelected(StatsTimeRange.MONTH)
        advanceTimeBy(100)
        runCurrent()
        assertThat(vm.uiState.value.current?.period).isEqualTo(StatsPeriod.Preset(StatsTimeRange.MONTH))

        advanceTimeBy(10_000)
        runCurrent()
        assertThat(vm.uiState.value.period).isEqualTo(StatsPeriod.Preset(StatsTimeRange.MONTH))
        assertThat(vm.uiState.value.report?.period).isEqualTo(StatsPeriod.Preset(StatsTimeRange.MONTH))
    }

    @Test
    fun `plays and time is only a different order of the same report and starts no load`() = runTest(dispatcher) {
        val provider = SlowProvider(ledger)
        val vm = viewModel(provider)
        advanceUntilIdle()
        val callsBefore = provider.calls
        val report = vm.uiState.value.report

        vm.onRankModeSelected(StatsRankMode.TIME)
        runCurrent()
        assertThat(vm.uiState.value.rankMode).isEqualTo(StatsRankMode.TIME)
        assertThat(vm.uiState.value.report).isSameInstanceAs(report)
        assertThat(provider.calls).isEqualTo(callsBefore)
    }

    @Test
    fun `the top list for a period that was left does not replace the one asked for`() = runTest(dispatcher) {
        val provider = SlowProvider(ledger)
        val vm = viewModel(provider)
        advanceUntilIdle()

        provider.delays += listOf(1_000L, 0L)
        vm.onTopMonthsSelected(YearMonth.of(2026, 3), YearMonth.of(2026, 3))
        runCurrent()
        vm.onTopMonthsSelected(YearMonth.of(2026, 4), YearMonth.of(2026, 4))
        advanceTimeBy(5_000)
        runCurrent()
        val list = vm.uiState.value.topList
        assertThat(list?.period).isEqualTo(StatsPeriod.Months(YearMonth.of(2026, 4), YearMonth.of(2026, 4)))
        assertThat(list?.rows?.map { it.item.identity }).containsExactly("item-3")
    }

    @Test
    fun `saving the top list makes a playlist of its songs in the order shown`() = runTest(dispatcher) {
        val provider = SlowProvider(ledger)
        val playlists = mockk<PlaylistPreferencesRepository>(relaxed = true)
        val vm = viewModel(provider, playlists)
        advanceUntilIdle()
        vm.onTopMonthsSelected(YearMonth.of(2026, 3), YearMonth.of(2026, 4))
        advanceUntilIdle()

        vm.saveTopAsPlaylist("Top 50 · mar–abr 2026")
        advanceUntilIdle()

        coVerify { playlists.createPlaylist(name = "Top 50 · mar–abr 2026", songIds = listOf("2", "3")) }
        assertThat(vm.uiState.value.top.savedAs).isEqualTo("Top 50 · mar–abr 2026")
    }
}
