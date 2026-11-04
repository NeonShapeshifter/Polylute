package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsRankMode
import com.lostf1sh.pixelplayeross.data.stats.StatsReport
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.data.stats.TimelineUnit
import com.lostf1sh.pixelplayeross.presentation.stats.displayNameRes
import com.lostf1sh.pixelplayeross.utils.currentLocale
import com.lostf1sh.pixelplayeross.utils.formatListeningDurationCompact
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import androidx.compose.runtime.remember
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsOverviewCard(
    modifier: Modifier = Modifier,
    summary: StatsReport?,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DapSpacing.Gutter),
        shape = RoundedCornerShape(DapRadius.Surface),
        // over the cover glass a frosted surface (B42-fix3, item 8); the theme's own surface otherwise
        colors = CardDefaults.cardColors(containerColor = Dap.panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick
    ) {
        // No accent wash: accent means "active or applied", and a summary card is neither.
        Box {
            Column(Modifier.padding(DapSpacing.Lg)) {
                com.lostf1sh.pixelplayeross.ui.ds.DapFeatureHeader(
                    title = stringResource(R.string.presentation_batch_g_stats_overview_title),
                    subtitle = stringResource(((summary?.period as? StatsPeriod.Preset)?.range ?: StatsTimeRange.WEEK).displayNameRes()),
                    onClick = onClick,
                )

                Spacer(Modifier.height(DapSpacing.Md))

                Crossfade(targetState = summary) { currentSummary ->
                    if (currentSummary == null) {
                        PlaceholderOverviewContent()
                    } else {
                        OverviewContent(currentSummary)
                    }
                }
            }
        }
    }
}

/**
 * The three headline numbers on one line, then the top track, then the week's shape.
 *
 * The numbers are measurements, so they are mono and they share a baseline row. The listening total
 * is the largest thing on the card because it is the answer to the question the card exists for;
 * everything else is one step down.
 */
@Composable
private fun OverviewContent(summary: StatsReport) {
    Column(verticalArrangement = Arrangement.spacedBy(DapSpacing.Md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            // Equal columns share the number's baseline independently of a label wrapping below it.
            horizontalArrangement = Arrangement.spacedBy(DapSpacing.Sm),
        ) {
            StatFigure(
                value = formatListeningDurationCompact(summary.totals.listenedMs, androidx.compose.ui.platform.LocalResources.current),
                label = stringResource(R.string.presentation_batch_g_stats_overview_total_time),
                emphasis = true,
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
            StatFigure(
                value = summary.totals.plays.toString(),
                label = stringResource(R.string.presentation_batch_g_stats_overview_total_plays),
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
            StatFigure(
                value = formatListeningDurationCompact(summary.totals.avgPerDayMs, androidx.compose.ui.platform.LocalResources.current),
                label = stringResource(R.string.presentation_batch_g_stats_overview_avg_per_day),
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
        }

        summary.rankings.songs(StatsRankMode.PLAYS).firstOrNull()?.let { topTrack ->
            Column {
                Text(
                    text = stringResource(R.string.presentation_batch_g_stats_overview_top_track),
                    style = DapType.Note,
                    color = Dap.tertiaryText,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = topTrack.item.title,
                    style = DapType.MediaRow,
                    color = Dap.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.presentation_batch_g_stats_overview_top_track_line,
                        topTrack.item.artistLine,
                        topTrack.plays
                    ),
                    style = DapType.Caption,
                    color = Dap.tertiaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        MiniListeningTimeline(summary)
    }
}

/** Wide enough for the longest single word a label carries ("Reproducciones"), not for two. */
private val StatFigureMaxWidth = 100.dp

/** One headline number and what it measures. */
@Composable
private fun StatFigure(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
) {
    // Capped so a two-word label wraps between its words instead of crowding its neighbour.
    Column(modifier.widthIn(max = StatFigureMaxWidth)) {
        Text(
            text = value,
            style = if (emphasis) DapType.HeadingSmall else DapType.RowSmall,
            color = Dap.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Note, not SectionHeader: a label under a number is prose, and the header's 10% tracking
        // is what broke "Reproducciones" mid-word at a third of 360 dp.
        Text(
            text = label,
            style = DapType.Note,
            color = Dap.tertiaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlaceholderOverviewContent() {
    Column(verticalArrangement = Arrangement.spacedBy(DapSpacing.Md)) {
        PlaceholderLine(width = 150.dp)
        PlaceholderLine(width = 110.dp)
        MiniListeningTimeline(null)
    }
}

/** One bar of the card's sparkline: what it is called and how tall it is. */
internal data class MiniBar(val label: String, val value: Long)

/**
 * The bars of the card for [report]: the last seven days of a week or a month's last week, a month as its weeks, a year as
 * its months. Future buckets are left out, so the sparkline ends where the listening does.
 */
internal fun miniBars(report: StatsReport, zone: ZoneId, locale: java.util.Locale, weekLabel: (Int) -> String): List<MiniBar> {
    val now = report.generatedAtMs
    if (report.period == StatsPeriod.Preset(StatsTimeRange.WEEK)) {
        val monday = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val byDate = report.timeline.buckets.associateBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
        return (0L..6L).map { offset ->
            val date=monday.plusDays(offset)
            MiniBar(date.dayOfWeek.getDisplayName(TextStyle.SHORT,locale),byDate[date]?.timeMs ?: 0)
        }
    }
    val buckets = report.timeline.buckets.filter { it.startMs <= now }
    return when (report.timeline.unit) {
        TimelineUnit.HOUR -> buckets.map { MiniBar(Instant.ofEpochMilli(it.startMs).atZone(zone).hour.toString(), it.timeMs) }.takeLast(7)
        TimelineUnit.MONTH -> buckets.map {
            MiniBar(Instant.ofEpochMilli(it.startMs).atZone(zone).month.getDisplayName(TextStyle.SHORT, locale), it.timeMs)
        }.takeLast(7)
        TimelineUnit.DAY ->
            if (report.period == StatsPeriod.Preset(StatsTimeRange.MONTH)) {
                buckets.chunked(7).mapIndexed { index, week -> MiniBar(weekLabel(index + 1), week.sumOf { it.timeMs }) }
            } else {
                buckets.map {
                    MiniBar(Instant.ofEpochMilli(it.startMs).atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, locale), it.timeMs)
                }.takeLast(7)
            }
    }
}

/**
 * The shape of the period, as a compact bar row.
 *
 * This is a sparkline, not a chart: 56 dp tall, no axis, no gridlines. Its job is to give the
 * numbers above a shape, and at that job a taller version was only costing the card height.
 */
@Composable
private fun MiniListeningTimeline(summary: StatsReport?) {
    val locale = currentLocale()
    val weekLabels = (1..6).map { stringResource(R.string.stats_timeline_week, it) }
    val bars = if (summary == null) {
        emptyList()
    } else {
        remember(summary, locale) { miniBars(summary, ZoneId.systemDefault(), locale) { weekLabels[it - 1] } }
    }
    if (summary?.period == StatsPeriod.Preset(StatsTimeRange.MONTH) && bars.isNotEmpty()) {
        MonthlyHorizontalListeningTimeline(bars)
        return
    }
    val maxDuration = bars.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MiniTimelineHeight),
        horizontalArrangement = Arrangement.spacedBy(DapSpacing.Sm),
        verticalAlignment = Alignment.Bottom
    ) {
        val entries: List<MiniBar?> = if (bars.isEmpty()) List(7) { null } else bars.takeLast(minOf(7, bars.size))
        entries.forEach { entry ->
            val heightFraction = entry
                ?.let { it.value.toFloat() / maxDuration.toFloat() }
                ?.coerceIn(0f, 1f) ?: 0.1f
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((MiniTimelineBarHeight * heightFraction).coerceAtLeast(3.dp))
                        .clip(RoundedCornerShape(DapRadius.Surface))
                        .background(if ((entry?.value ?: 0) > 0) LocalDapAccent.current else Dap.trackStroke)
                )
                if (!entry?.label.isNullOrBlank()) {
                    Spacer(Modifier.height(DapSpacing.Xs))
                    Text(
                        text = entry.label,
                        style = DapType.Axis,
                        color = Dap.tertiaryText,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthlyHorizontalListeningTimeline(bars: List<MiniBar>) {
    val maxDuration = bars.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1L
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MiniTimelineHeight)
            .testTag("stats-month-chart"),
        verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs)
    ) {
        bars.takeLast(6).forEach { entry ->
            val fraction = (entry.value.toFloat() / maxDuration.toFloat()).coerceIn(0f, 1f)
            Row(Modifier.heightIn(min = DapSpacing.SectionGap),verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.label,
                    style = DapType.Axis,
                    color = Dap.tertiaryText,
                    modifier = Modifier.width(DapSizes.ThumbFeature + DapSpacing.Gutter),
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(DapRadius.Surface))
                        .background(LocalDapAccent.current.copy(alpha = 0.25f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(LocalDapAccent.current)
                    )
                }
            }
        }
    }
}

private val MiniTimelineHeight = 56.dp
private val MiniTimelineBarHeight = 34.dp

@Composable
private fun PlaceholderLine(width: Dp) {
    Box(
        Modifier
            .width(width)
            .height(14.dp)
            .clip(RoundedCornerShape(DapRadius.Surface))
            .background(Dap.panelRaised)
    )
}
