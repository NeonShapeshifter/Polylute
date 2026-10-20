package com.lostf1sh.pixelplayeross.presentation.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.stats.ListeningClock
import com.lostf1sh.pixelplayeross.data.stats.StatsRankMode
import com.lostf1sh.pixelplayeross.data.stats.Timeline
import com.lostf1sh.pixelplayeross.data.stats.TimelineBucket
import com.lostf1sh.pixelplayeross.data.stats.TimelineUnit
import com.lostf1sh.pixelplayeross.presentation.components.OptimizedAlbumArt
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapArtworkPlaceholder
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapStateKind
import com.lostf1sh.pixelplayeross.ui.ds.DapStateMark
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.utils.formatListeningDurationCompact
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/*
 * The pieces the Stats screen is made of (B28). All of them are stateless: numbers in, pixels out, so the screen, the Top N and
 * the render tests draw the same thing. Colour follows the design system: the accent is "applied", so it paints data
 * (bars, filled cells) and nothing else; text is primary for what is ranked and tertiary for the rest; rows have no radius.
 */

/** A row of a ranking: its place, a picture, the name, and both numbers. The measure the list is ranked by is the brighter one. */
@Composable
internal fun RankRow(
    rank: Int,
    title: String,
    subtitle: String?,
    plays: Int,
    timeMs: Long,
    mode: StatsRankMode,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    /** B32-Q2: what a tap does; the row's name opens the song, the album, the artist or the genre it names. */
    open: RowOpen = RowOpen.None,
    art: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .statsRowOpen(open)
                .padding(vertical = DapSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rank.toString(),
                style = DapType.ValueSmall,
                color = Dap.tertiaryText,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(22.dp),
            )
            Spacer(Modifier.width(DapSpacing.Sm))
            art()
            Spacer(Modifier.width(DapSpacing.Md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = DapType.MediaRow,
                    color = Dap.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = DapType.Note,
                        color = Dap.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(DapSpacing.Md))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = pluralStringResource(R.plurals.stats_row_plays, plays, plays),
                    style = DapType.Value,
                    color = if (mode == StatsRankMode.PLAYS) Dap.primaryText else Dap.tertiaryText,
                    maxLines = 1,
                )
                Text(
                    text = formatListeningDurationCompact(timeMs),
                    style = DapType.Value,
                    color = if (mode == StatsRankMode.TIME) Dap.primaryText else Dap.tertiaryText,
                    maxLines = 1,
                )
            }
        }
        if (divider) DapDivider()
    }
}

/** Square cover art for a row: the song's or album's own, or the design's placeholder. */
@Composable
internal fun RowCover(uri: String?, title: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(DapSizes.ThumbRow)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        if (uri.isNullOrBlank()) {
            DapArtworkPlaceholder(Modifier.fillMaxSize())
        } else {
            OptimizedAlbumArt(
                uri = uri,
                title = title,
                targetSize = coil.size.Size(96, 96),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** An artist's avatar: the initials on the raised surface. */
@Composable
internal fun InitialsAvatar(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(DapSizes.ThumbRow)
            .clip(CircleShape)
            .background(Dap.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = StatsFormat.initials(name),
            style = DapType.Value,
            color = Dap.secondaryText,
            maxLines = 1,
        )
    }
}

/** A label, a mono value, and a hairline bar that fills to [share]. Used for every "how much of the whole" line. */
@Composable
internal fun ShareRow(
    label: String,
    value: String,
    share: Float,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = DapSpacing.Sm)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = DapType.Body,
                    color = Dap.primaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!detail.isNullOrBlank()) {
                    Text(text = detail, style = DapType.Note, color = Dap.tertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(DapSpacing.Md))
            Text(text = value, style = DapType.Value, color = Dap.primaryText, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Bar(share)
    }
}

/** The hairline track and its fill. 3 dp, so a one-percent share is still something you can see. */
@Composable
internal fun Bar(share: Float, modifier: Modifier = Modifier, color: Color = LocalDapAccent.current) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(Dap.surfaceRaised),
    ) {
        val clamped = share.coerceIn(0f, 1f)
        if (clamped > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(clamped.coerceAtLeast(0.01f))
                    .height(3.dp)
                    .background(color),
            )
        }
    }
}

/** A note about the data, with the grey mark of "this is how it is". */
@Composable
internal fun StatsNote(text: String, modifier: Modifier = Modifier, title: String? = null, kind: DapStateKind = DapStateKind.Bypassed) {
    Row(modifier.fillMaxWidth().padding(vertical = DapSpacing.Sm), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp)) { DapStateMark(kind) }
        Spacer(Modifier.width(DapSpacing.Sm))
        Column {
            if (title != null) {
                Text(text = title, style = DapType.CaptionMedium, color = Dap.primaryText)
            }
            Text(text = text, style = DapType.Caption, color = Dap.secondaryText)
        }
    }
}

/** A small heading inside a section ("Signal", "Route"). */
@Composable
internal fun SubHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = DapType.Action,
        color = Dap.secondaryText,
        modifier = modifier.fillMaxWidth().padding(top = DapSpacing.Lg, bottom = DapSpacing.Xs),
    )
}

/** A figure: a number and what it counts. */
@Composable
internal fun Figure(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(text = value, style = DapType.HeadingSmall, color = Dap.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = label, style = DapType.Note, color = Dap.tertiaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// --- the timeline ----------------------------------------------------------------------------------------

/**
 * Bars over time: 24 for a day, 7 for a week, a month's days, 12 months. The height is plays or time, by [mode];
 * the axis names the bars that carry a label at this width and the line under the chart names the highest one.
 */
@Composable
internal fun TimelineChart(
    timeline: Timeline,
    mode: StatsRankMode,
    zone: ZoneId,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val buckets = timeline.buckets
    if (buckets.isEmpty()) return
    val values = remember(buckets, mode) {
        buckets.map { if (mode == StatsRankMode.PLAYS) it.plays.toFloat() else it.timeMs.toFloat() }
    }
    val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val peakIndex = values.indexOf(values.max()).takeIf { values.max() > 0f }
    val accent = LocalDapAccent.current
    val stub = Dap.surfaceRaised
    val baseline = Dap.divider
    val count = buckets.size
    val peak = peakIndex?.let { buckets[it] }
    val peakText = peak?.let {
        val value = if (mode == StatsRankMode.PLAYS) pluralStringResource(R.plurals.stats_row_plays, it.plays, it.plays) else formatListeningDurationCompact(it.timeMs)
        stringResource(R.string.stats_timeline_peak, bucketName(timeline.unit, it, zone, locale), value)
    }
    Column(modifier.fillMaxWidth().semantics { if (peakText != null) contentDescription = peakText }) {
        Canvas(Modifier.fillMaxWidth().height(88.dp)) {
            val gap = if (count > 20) 2.dp.toPx() else 4.dp.toPx()
            val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
            val corner = CornerRadius(1.5.dp.toPx())
            values.forEachIndexed { index, value ->
                val x = index * (barWidth + gap)
                val h = if (value <= 0f) 2.dp.toPx() else (size.height * (value / max)).coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    color = if (value <= 0f) stub else accent,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = corner,
                )
            }
            drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
            val gap = if (count > 20) 2.dp else 4.dp
            val step = (maxWidth - gap * (count - 1)) / count + gap
            axisIndices(timeline.unit, count).forEach { index ->
                Text(
                    text = axisLabel(timeline.unit, count, buckets[index], zone, locale),
                    style = DapType.Axis,
                    color = Dap.tertiaryText,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.offset(x = step * index).widthIn(max = step * 4),
                )
            }
        }
        if (peakText != null) {
            Spacer(Modifier.height(DapSpacing.Sm))
            Text(text = peakText, style = DapType.Caption, color = Dap.secondaryText, maxLines = 2)
        }
    }
}

/** Which bars carry a label: every bar of a week, every sixth hour, a month's first day and every seventh after. */
internal fun axisIndices(unit: TimelineUnit, count: Int): List<Int> = when (unit) {
    TimelineUnit.HOUR -> (0 until count step 6).toList()
    TimelineUnit.DAY -> if (count <= 7) (0 until count).toList() else (0 until count step 7).toList()
    TimelineUnit.MONTH -> if (count <= 12) (0 until count).toList() else (0 until count step 3).toList()
}

private fun axisLabel(unit: TimelineUnit, count: Int, bucket: TimelineBucket, zone: ZoneId, locale: Locale): String {
    val date = Instant.ofEpochMilli(bucket.startMs).atZone(zone)
    return when (unit) {
        TimelineUnit.HOUR -> String.format(Locale.US, "%02d", date.hour)
        // A week labels its days by name; a month, by date.
        TimelineUnit.DAY ->
            if (count <= 7) date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) else date.dayOfMonth.toString()
        TimelineUnit.MONTH -> date.month.getDisplayName(TextStyle.SHORT, locale)
    }
}

/** A week's bars are named by weekday, a month's by date, a year's by month: what the axis and the peak line call them. */
internal fun bucketName(unit: TimelineUnit, bucket: TimelineBucket, zone: ZoneId, locale: Locale): String {
    val date = Instant.ofEpochMilli(bucket.startMs).atZone(zone)
    return when (unit) {
        TimelineUnit.HOUR -> StatsFormat.hour(date.hour)
        TimelineUnit.DAY -> "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${date.dayOfMonth}"
        TimelineUnit.MONTH -> StatsFormat.month(java.time.YearMonth.from(date), locale)
    }
}

// --- the listening clock -----------------------------------------------------------------------------------

/**
 * Hour of day across, day of week down: how the listening is spread over a week. 24 cells of ~12 dp at a phone's width, each one
 * painted in the accent at one of five strengths by plays or time, so a night peak reads at a glance and an empty hour stays quiet.
 */
@Composable
internal fun ClockGrid(
    clock: ListeningClock,
    mode: StatsRankMode,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val accent = LocalDapAccent.current
    val empty = Dap.surface
    val max = if (mode == StatsRankMode.PLAYS) clock.maxPlays.toFloat() else clock.maxTimeMs.toFloat()
    val days = remember(locale) { DayOfWeek.entries.map { it.getDisplayName(TextStyle.NARROW, locale) } }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                days.forEach { name ->
                    Box(Modifier.height(12.dp), contentAlignment = Alignment.CenterStart) {
                        Text(text = name, style = DapType.Axis, color = Dap.tertiaryText, maxLines = 1)
                    }
                }
            }
            Canvas(Modifier.weight(1f).height((12 * 7 + 2 * 6).dp)) {
                val gap = 2.dp.toPx()
                val cell = ((size.width - gap * 23) / 24f).coerceAtLeast(1f)
                val rowHeight = 12.dp.toPx()
                for (day in 0 until 7) {
                    for (hour in 0 until 24) {
                        val raw = if (mode == StatsRankMode.PLAYS) clock.plays[day * 24 + hour].toFloat() else clock.timeMs[day * 24 + hour].toFloat()
                        val level = if (max <= 0f || raw <= 0f) 0 else (1 + ((raw / max) * 4f - 0.0001f).toInt()).coerceIn(1, 5)
                        drawRoundRect(
                            color = if (level == 0) empty else accent.copy(alpha = 0.18f + 0.82f * (level - 1) / 4f),
                            topLeft = Offset(hour * (cell + gap), day * (rowHeight + gap)),
                            size = Size(cell, rowHeight),
                            cornerRadius = CornerRadius(1.5.dp.toPx()),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp).height(14.dp)) {
            val gap = 2.dp
            val step = (maxWidth - gap * 23) / 24 + gap
            listOf(0, 6, 12, 18, 23).forEach { hour ->
                Text(
                    text = String.format(Locale.US, "%02d", hour),
                    style = DapType.Axis,
                    color = Dap.tertiaryText,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.offset(x = step * hour - if (hour == 23) 4.dp else 0.dp),
                )
            }
        }
    }
}

/** A tappable text row at the end of a list: "See all (24)". */
@Composable
internal fun SeeAllRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = DapType.Action, color = Dap.secondaryText, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        com.lostf1sh.pixelplayeross.ui.ds.DapChevron()
    }
}

/** A value row of the Habits and Audio sections: a prose label, a mono value. */
@Composable
internal fun ValueLine(label: String, value: String, modifier: Modifier = Modifier, divider: Boolean = true) {
    com.lostf1sh.pixelplayeross.ui.ds.DapValueRow(label = label, modifier = modifier, divider = divider) {
        com.lostf1sh.pixelplayeross.ui.ds.DapValue(text = value)
    }
}

internal val StatsGroupGap: Dp = DapSpacing.SectionGap
