package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.data.stats.StatsEngine
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsRankMode
import com.lostf1sh.pixelplayeross.data.stats.StatsReport
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.data.stats.TopNReport
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import com.lostf1sh.pixelplayeross.presentation.stats.StatsLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** The two faces of the Stats screen: the summary of a range, and the "Top N" of a period. */
enum class StatsLayer { SUMMARY, TOP }

/** What the "Top N" face shows and what it can do. */
data class TopUiState(
    val size: Int = DEFAULT_TOP_SIZE,
    val from: YearMonth,
    val to: YearMonth,
    val isLoading: Boolean = false,
    val report: StatsReport? = null,
    val isSaving: Boolean = false,
    /** The name of the playlist the list was just saved as; cleared when the list changes. */
    val savedAs: String? = null,
) {
    val period: StatsPeriod.Months get() = StatsPeriod.Months(from, to)

    /** The report in hand is the one of the period the face is on. */
    val isCurrent: Boolean get() = report?.period == period

    companion object {
        const val DEFAULT_TOP_SIZE = 50
        val Sizes = listOf(25, 50, 100)
    }
}

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.Week,
    val rankMode: StatsRankMode = StatsRankMode.PLAYS,
    val layer: StatsLayer = StatsLayer.SUMMARY,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val failed: Boolean = false,
    val report: StatsReport? = null,
    /** What the rows can open (B32-Q2), from the library the report was built over. */
    val links: StatsLinks = StatsLinks.Empty,
    val top: TopUiState,
) {
    /** The report is the one of the period the screen is on (a stale one is never shown under another period's selector). */
    val current: StatsReport? get() = report?.takeIf { it.period == period }

    val topList: TopNReport?
        get() = top.report?.takeIf { top.isCurrent }?.let {
            StatsEngine.topList(it, top.period, top.size, rankMode)
        }
}

/**
 * The state of the Stats screen and the one source of Home's card (B28).
 *
 * ## An answer that is not for the current selection is never shown (K-02)
 *
 * Every load carries a token taken when it starts, and the request it replaces is cancelled; a result is published only if its
 * token is still the latest. A slow load for one period that returns after the listener has moved to another therefore changes
 * nothing, whether it was asked for by a tap or by an automatic refresh.
 *
 * Plays/Time is not a load: every ranking is ordered from the same rows by the mode at the moment it is read, so the selector can
 * neither wait for nor race a computation.
 *
 * Nothing here runs on the main thread but the state updates: the ledger is read and the report built on the default dispatcher.
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val ledgers: LedgerProvider,
    private val playlists: PlaylistPreferencesRepository,
    private val librarySignal: LibraryChangeSignal,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    /** The clock and the zone, replaceable by a test. */
    internal var clock: () -> Long = System::currentTimeMillis
    internal var zone: () -> ZoneId = ZoneId::systemDefault

    private val _uiState = MutableStateFlow(StatsUiState(top = defaultTop()))
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    private val _homeOverview = MutableStateFlow<StatsReport?>(null)

    /** Home's card: the first of week, month, year and all time that has any listening, as numbers and shape only. */
    val homeOverview: StateFlow<StatsReport?> = _homeOverview.asStateFlow()

    private var summaryToken = 0L
    private var summaryJob: Job? = null
    private var topToken = 0L
    private var topJob: Job? = null
    private var overviewToken = 0L
    private var overviewJob: Job? = null

    init {
        loadSummary(silent = false)
        loadOverview()
        observeChanges()
    }

    // --- the summary ----------------------------------------------------------------------------------------

    fun onRangeSelected(range: StatsTimeRange) = onPeriodSelected(StatsPeriod.Preset(range))

    fun onPeriodSelected(period: StatsPeriod) {
        if (period == _uiState.value.period && _uiState.value.current != null) return
        _uiState.update { it.copy(period = period) }
        loadSummary(silent = false)
    }

    /** Only re-orders what is loaded. */
    fun onRankModeSelected(mode: StatsRankMode) {
        if (mode == _uiState.value.rankMode) return
        _uiState.update { it.copy(rankMode = mode, top = it.top.copy(savedAs = null)) }
    }

    fun onLayerSelected(layer: StatsLayer) {
        if (layer == _uiState.value.layer) return
        _uiState.update { it.copy(layer = layer) }
        if (layer == StatsLayer.TOP && !_uiState.value.top.isCurrent) loadTop(silent = false)
    }

    private fun loadSummary(silent: Boolean) {
        val token = ++summaryToken
        summaryJob?.cancel()
        val period = _uiState.value.period
        _uiState.update {
            if (silent && it.current != null) it.copy(isRefreshing = true, failed = false) else it.copy(isLoading = true, isRefreshing = false, failed = false)
        }
        summaryJob = viewModelScope.launch {
            val result = runCatching {
                withContext(dispatchers.default) {
                    val ledger = ledgers.ledger()
                    StatsEngine.report(ledger, period, clock(), zone()) to StatsLinks.of(ledger.catalog)
                }
            }
            if (token != summaryToken) return@launch
            result.exceptionOrNull()?.let { Timber.e(it, "Failed to build the stats for %s", period) }
            _uiState.update {
                // The answer is for the period it was asked for; the selection may not have moved, but it is checked anyway.
                if (it.period != period) it else it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    failed = result.isFailure,
                    report = result.getOrNull()?.first ?: it.report?.takeIf { old -> old.period == period },
                    links = result.getOrNull()?.second ?: it.links,
                )
            }
        }
    }

    // --- the Top N ------------------------------------------------------------------------------------------

    fun onTopSizeSelected(size: Int) {
        if (size == _uiState.value.top.size) return
        _uiState.update { it.copy(top = it.top.copy(size = size, savedAs = null)) }
    }

    /** A whole year: January to December. The current year is "so far". */
    fun onTopYearSelected(year: Int) = onTopMonthsSelected(YearMonth.of(year, 1), YearMonth.of(year, 12))

    fun onTopMonthsSelected(from: YearMonth, to: YearMonth) {
        val (start, end) = if (to.isBefore(from)) to to from else from to to
        if (start == _uiState.value.top.from && end == _uiState.value.top.to && _uiState.value.top.isCurrent) return
        _uiState.update { it.copy(top = it.top.copy(from = start, to = end, report = null, savedAs = null)) }
        loadTop(silent = false)
    }

    private fun loadTop(silent: Boolean) {
        val token = ++topToken
        topJob?.cancel()
        val period = _uiState.value.top.period
        _uiState.update { it.copy(top = it.top.copy(isLoading = !silent || !it.top.isCurrent)) }
        topJob = viewModelScope.launch {
            val result = runCatching {
                withContext(dispatchers.default) {
                    val ledger = ledgers.ledger()
                    StatsEngine.report(ledger, period, clock(), zone()) to StatsLinks.of(ledger.catalog)
                }
            }
            if (token != topToken) return@launch
            result.exceptionOrNull()?.let { Timber.e(it, "Failed to build the top list for %s", period) }
            _uiState.update {
                if (it.top.period != period) it else it.copy(
                    top = it.top.copy(isLoading = false, report = result.getOrNull()?.first ?: it.top.report),
                    links = result.getOrNull()?.second ?: it.links,
                )
            }
        }
    }

    /**
     * Saves the list on screen as a user playlist called [name]: a snapshot of the songs in the order shown, deletable like any
     * other. The playlist does not follow the history afterwards.
     */
    fun saveTopAsPlaylist(name: String) {
        val list = _uiState.value.topList ?: return
        if (list.rows.isEmpty() || _uiState.value.top.isSaving) return
        _uiState.update { it.copy(top = it.top.copy(isSaving = true, savedAs = null)) }
        viewModelScope.launch {
            val saved = runCatching {
                playlists.createPlaylist(name = name, songIds = list.rows.map { it.item.locator })
            }
            saved.exceptionOrNull()?.let { Timber.e(it, "Failed to save the top list as a playlist") }
            _uiState.update { it.copy(top = it.top.copy(isSaving = false, savedAs = if (saved.isSuccess) name else null)) }
        }
    }

    /** The songs of the list on screen, in the order shown: what "play it" queues. */
    fun topSongsInOrder(): List<com.lostf1sh.pixelplayeross.data.model.Song> =
        _uiState.value.topList?.rows?.map { it.item.song }.orEmpty()

    // --- Home's card ----------------------------------------------------------------------------------------

    fun refreshHomeOverview() = loadOverview()

    private fun loadOverview() {
        val token = ++overviewToken
        overviewJob?.cancel()
        overviewJob = viewModelScope.launch {
            val result = runCatching {
                withContext(dispatchers.default) {
                    val ledger = ledgers.ledger()
                    val now = clock()
                    val zone = zone()
                    HomeOverviewRanges.asSequence()
                        .map { StatsEngine.report(ledger, StatsPeriod.Preset(it), now, zone, StatsEngine.Detail.OVERVIEW) }
                        .firstOrNull { !it.isEmpty }
                }
            }
            if (token != overviewToken) return@launch
            result.exceptionOrNull()?.let { Timber.e(it, "Failed to build Home's stats overview") }
            _homeOverview.value = result.getOrNull()
        }
    }

    // --- following the history and the library --------------------------------------------------------------

    /**
     * A listen that ends, or a change of the library, refreshes what is on screen, a moment after the last one: a screen that is
     * open follows what is being played, and a run of changes costs one rebuild.
     */
    @OptIn(FlowPreview::class)
    private fun observeChanges() {
        viewModelScope.launch {
            ledgers.changes.drop(1).debounce(RefreshDebounceMs).collect {
                loadSummary(silent = true)
                loadOverview()
                if (_uiState.value.layer == StatsLayer.TOP || _uiState.value.top.report != null) loadTop(silent = true)
            }
        }
    }

    /** Settings > regenerate: read the library and the history again. */
    fun forceRegenerateStats() {
        librarySignal.changed("stats regenerated by the user")
    }

    /** The screen came back to the front: take whatever changed meanwhile. */
    fun requestStatsRefresh() {
        loadSummary(silent = true)
    }

    private fun defaultTop(): TopUiState {
        val year = YearMonth.from(java.time.Instant.ofEpochMilli(clock()).atZone(zone()))
        return TopUiState(from = YearMonth.of(year.year, 1), to = YearMonth.of(year.year, 12))
    }

    private companion object {
        val HomeOverviewRanges = listOf(
            StatsTimeRange.WEEK,
            StatsTimeRange.MONTH,
            StatsTimeRange.YEAR,
            StatsTimeRange.ALL,
        )
        const val RefreshDebounceMs = 2_000L
    }
}
