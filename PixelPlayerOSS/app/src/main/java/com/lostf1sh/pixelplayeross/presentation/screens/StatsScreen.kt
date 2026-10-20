package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsRankMode
import com.lostf1sh.pixelplayeross.data.stats.StatsReport
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.presentation.stats.StatsFormat
import com.lostf1sh.pixelplayeross.presentation.stats.StatsList
import com.lostf1sh.pixelplayeross.presentation.stats.StatsOpener
import com.lostf1sh.pixelplayeross.presentation.stats.StatsTarget
import com.lostf1sh.pixelplayeross.presentation.stats.route
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.stats.StatsLook
import com.lostf1sh.pixelplayeross.presentation.stats.TopActions
import com.lostf1sh.pixelplayeross.presentation.stats.AlbumRankRow
import com.lostf1sh.pixelplayeross.presentation.stats.ArtistRankRow
import com.lostf1sh.pixelplayeross.presentation.stats.GenreRankRow
import com.lostf1sh.pixelplayeross.presentation.stats.SongRankRow
import com.lostf1sh.pixelplayeross.presentation.stats.rankingTitle
import com.lostf1sh.pixelplayeross.presentation.stats.statsSummary
import com.lostf1sh.pixelplayeross.presentation.stats.statsTop
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsLayer
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapEmptyState
import com.lostf1sh.pixelplayeross.ui.ds.DapSegmentedPicker
import com.lostf1sh.pixelplayeross.ui.ds.DapSegmentedRow
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSkeletonList
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapTitleMeta
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.utils.currentLocale
import java.time.YearMonth
import java.time.ZoneId

/**
 * Stats, rebuilt (B28): the listening history read per occurrence and per durable identity, in the app's own look.
 *
 * Two faces, one screen: a summary of a range (numbers first, then the shape, then who, then how it sounded, then habits) and a
 * "Top N" of a period. Plays/Time is one switch for both. Everything the screen draws comes from `StatsReport`; this file only
 * decides what is shown when (loading, no history, nothing in this range, failed) and what a tap does.
 */
@Composable
fun StatsScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    statsViewModel: StatsViewModel = hiltViewModel(),
) {
    val ui by statsViewModel.uiState.collectAsStateWithLifecycle()
    var seeAll by rememberSaveable { mutableStateOf<StatsList?>(null) }
    BackHandler(enabled = seeAll != null) { seeAll = null }
    // Coming back to the screen takes whatever was played meanwhile.
    LaunchedEffect(Unit) { statsViewModel.requestStatsRefresh() }

    val locale = currentLocale()
    val topTitle = ui.topList?.let { stringResource(R.string.stats_top_title, it.size, StatsFormat.periodLabel(it.period, locale)) }.orEmpty()
    val topSource = ListenSource(ListenSourceKind.TOP_LIST, ui.top.period.let { "${it.from}..${it.to}" })
    val actions = remember(statsViewModel, playerViewModel, topTitle) {
        TopActions(
            onSize = statsViewModel::onTopSizeSelected,
            onYear = statsViewModel::onTopYearSelected,
            onMonths = statsViewModel::onTopMonthsSelected,
            onPlay = {
                val songs = statsViewModel.topSongsInOrder()
                if (songs.isNotEmpty()) playerViewModel.playSongs(songs, songs.first(), topTitle, source = topSource)
            },
            onShuffle = {
                val songs = statsViewModel.topSongsInOrder()
                if (songs.isNotEmpty()) playerViewModel.playSongsShuffled(songs, topTitle, startAtZero = true, source = topSource)
            },
            onSave = statsViewModel::saveTopAsPlaylist,
        )
    }
    StatsContent(
        ui = ui,
        seeAll = seeAll,
        zone = ZoneId.systemDefault(),
        onSeeAll = { seeAll = it },
        onRange = statsViewModel::onRangeSelected,
        onMode = statsViewModel::onRankModeSelected,
        onLayer = statsViewModel::onLayerSelected,
        onRetry = statsViewModel::requestStatsRefresh,
        topActions = actions,
        // B32-Q2: a row opens what it names, on top of this screen, so Back returns here: the range, the face and the scroll are
        // this screen's (its view model and its saved list state), and nothing here resets them on the way back.
        onOpen = { target -> navController.navigateSafely(target.route()) },
    )
}

/** The screen as a function of its state: what the render tests draw and the route above feeds. */
@Composable
internal fun StatsContent(
    ui: StatsUiState,
    seeAll: StatsList?,
    zone: ZoneId,
    onSeeAll: (StatsList) -> Unit,
    onRange: (StatsTimeRange) -> Unit,
    onMode: (StatsRankMode) -> Unit,
    onLayer: (StatsLayer) -> Unit,
    onRetry: () -> Unit,
    topActions: TopActions,
    /** Where a tap on a row goes; null where nothing is wired (the rows are text). */
    onOpen: ((StatsTarget) -> Unit)? = null,
) {
    val locale = currentLocale()
    val look = StatsLook(ui.rankMode, zone, locale, StatsOpener(ui.links, onOpen))
    val report = ui.current
    // The list stops where the navigation bar begins because the scaffold does that for every pushed screen (B29-L9; it was done here, per
    // list, in B28-fix2 E4): its rows never run through the strip between the shell's mini player and the gesture bar.
    DapBackScaffold(Modifier.fillMaxSize()) {
        if (seeAll != null && report != null) {
            FullRanking(seeAll, report, look)
            return@DapBackScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DapSpacing.Gutter,
                end = DapSpacing.Gutter,
                top = DapSpacing.Sm,
                bottom = LocalDapPlayerShelfHeight.current + 24.dp,
            ),
        ) {
            item(key = "title") { DapScreenTitle(text = stringResource(R.string.stats_title)) }
            item(key = "layer") {
                val labels = StatsLayer.entries.associateWith { stringResource(layerLabel(it)) }
                DapSegmentedPicker(
                    options = StatsLayer.entries,
                    selected = ui.layer,
                    onSelect = onLayer,
                    label = { labels.getValue(it) },
                    fillWidth = true,
                    modifier = Modifier.padding(top = DapSpacing.Lg),
                )
            }
            if (ui.layer == StatsLayer.SUMMARY) {
                summaryFace(ui, report, look, onRange, onMode, onSeeAll, onRetry)
            } else {
                topFace(ui, look, onMode, topActions)
            }
        }
    }
}

@Composable
private fun layerLabel(layer: StatsLayer): Int = when (layer) {
    StatsLayer.SUMMARY -> R.string.stats_layer_summary
    StatsLayer.TOP -> R.string.stats_layer_top
}

private fun LazyListScope.summaryFace(
    ui: StatsUiState,
    report: StatsReport?,
    look: StatsLook,
    onRange: (StatsTimeRange) -> Unit,
    onMode: (StatsRankMode) -> Unit,
    onSeeAll: (StatsList) -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "range") {
        val selected = (ui.period as? StatsPeriod.Preset)?.range ?: StatsTimeRange.WEEK
        val labels = StatsTimeRange.entries.associateWith { stringResource(rangeLabel(it)) }
        DapSegmentedPicker(
            options = StatsTimeRange.entries,
            selected = selected,
            onSelect = onRange,
            label = { labels.getValue(it) },
            fillWidth = true,
            modifier = Modifier.padding(top = DapSpacing.Md),
        )
    }
    item(key = "sort") { SortRow(ui.rankMode, onMode) }

    when {
        report == null && ui.failed -> item(key = "failed") {
            DapEmptyState(
                title = stringResource(R.string.stats_state_error_title),
                message = stringResource(R.string.stats_state_error_body),
                modifier = Modifier.padding(vertical = DapSpacing.GroupGap),
                action = { DapTextAction(label = stringResource(R.string.stats_state_retry), onClick = onRetry) },
            )
        }
        report == null -> item(key = "loading") {
            Column(Modifier.fillMaxWidth().padding(top = DapSpacing.SectionGap)) { DapSkeletonList(count = 7) }
        }
        report.historyStartMs == null -> item(key = "no-history") {
            DapEmptyState(
                title = stringResource(R.string.stats_state_empty_title),
                message = stringResource(R.string.stats_state_empty_body),
                modifier = Modifier.padding(vertical = DapSpacing.GroupGap),
            )
        }
        report.isEmpty -> item(key = "range-empty") {
            DapEmptyState(
                title = stringResource(R.string.stats_state_range_empty_title),
                message = stringResource(R.string.stats_state_range_empty_body),
                modifier = Modifier.padding(vertical = DapSpacing.GroupGap),
            )
        }
        else -> statsSummary(report, look, onSeeAll)
    }
}

private fun LazyListScope.topFace(
    ui: StatsUiState,
    look: StatsLook,
    onMode: (StatsRankMode) -> Unit,
    actions: TopActions,
) {
    item(key = "sort") { SortRow(ui.rankMode, onMode) }
    // The months the history spans: from its first one to the end of the current year, so a whole year can be asked for and shown as it goes.
    val now = YearMonth.now(look.zone)
    val first = ui.top.report?.historyStartMs?.let { YearMonth.from(java.time.Instant.ofEpochMilli(it).atZone(look.zone)) }
        ?: ui.current?.historyStartMs?.let { YearMonth.from(java.time.Instant.ofEpochMilli(it).atZone(look.zone)) }
        ?: ui.top.from.coerceAtMost(now)
    statsTop(
        state = ui.top,
        list = ui.topList,
        look = look,
        firstMonth = first.coerceAtMost(ui.top.from),
        lastMonth = YearMonth.of(now.year, 12),
        actions = actions,
    )
}

private fun YearMonth.coerceAtMost(other: YearMonth): YearMonth = if (isAfter(other)) other else this

private fun rangeLabel(range: StatsTimeRange): Int = when (range) {
    StatsTimeRange.DAY -> R.string.stats_range_day
    StatsTimeRange.WEEK -> R.string.stats_range_week
    StatsTimeRange.MONTH -> R.string.stats_range_month
    StatsTimeRange.YEAR -> R.string.stats_range_year
    StatsTimeRange.ALL -> R.string.stats_range_all
}

/** The one Plays/Time switch: it orders every ranking and sets what the timeline and the clock measure. */
@Composable
private fun SortRow(mode: StatsRankMode, onMode: (StatsRankMode) -> Unit) {
    val plays = stringResource(R.string.stats_sort_plays)
    val time = stringResource(R.string.stats_sort_time)
    DapSegmentedRow(
        label = stringResource(R.string.stats_sort_by),
        options = StatsRankMode.entries,
        selected = mode,
        onSelect = onMode,
        optionLabel = { if (it == StatsRankMode.PLAYS) plays else time },
        divider = false,
        modifier = Modifier.padding(top = DapSpacing.Xs),
    )
}

/** The whole of one ranking: the rows "see all" leads to. */
@Composable
private fun FullRanking(kind: StatsList, report: StatsReport, look: StatsLook) {
    val total = when (kind) {
        StatsList.SONGS -> report.rankings.songCount
        StatsList.ARTISTS -> report.rankings.artistCount
        StatsList.ALBUMS -> report.rankings.albumCount
        StatsList.GENRES -> report.rankings.genreCount
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = DapSpacing.Gutter,
            end = DapSpacing.Gutter,
            top = DapSpacing.Sm,
            bottom = LocalDapPlayerShelfHeight.current + 24.dp,
        ),
    ) {
        item(key = "full-title") {
            DapScreenTitle(
                text = rankingTitle(kind),
                trailing = { DapTitleMeta(total.toString()) },
            )
            Spacer(Modifier.height(DapSpacing.Sm))
        }
        when (kind) {
            StatsList.SONGS -> itemsIndexed(report.rankings.songs(look.mode), key = { _, row -> row.item.identity }) { index, row -> SongRankRow(index + 1, row, look) }
            StatsList.ARTISTS -> itemsIndexed(report.rankings.artists(look.mode), key = { _, row -> row.name }) { index, row -> ArtistRankRow(index + 1, row, look) }
            StatsList.ALBUMS -> itemsIndexed(report.rankings.albums(look.mode), key = { _, row -> row.key }) { index, row -> AlbumRankRow(index + 1, row, look) }
            StatsList.GENRES -> itemsIndexed(report.rankings.genres(look.mode), key = { _, row -> row.name }) { index, row -> GenreRankRow(index + 1, row, look) }
        }
    }
}
