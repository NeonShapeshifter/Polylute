package com.lostf1sh.pixelplayeross.presentation.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.stats.StatsReport
import com.lostf1sh.pixelplayeross.data.stats.TopNReport
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TopUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapButton
import com.lostf1sh.pixelplayeross.ui.ds.DapButtonVariant
import com.lostf1sh.pixelplayeross.ui.ds.DapCollectionActions
import com.lostf1sh.pixelplayeross.ui.ds.DapEmptyState
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapSegmentedPicker
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSkeletonList
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapState
import com.lostf1sh.pixelplayeross.ui.ds.DapStateKind
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.utils.formatListeningDurationCompact
import java.time.YearMonth
import java.time.format.TextStyle

/** What the Top N face needs from outside: the months the history spans, and what the listener does with the list. */
internal class TopActions(
    val onSize: (Int) -> Unit,
    val onYear: (Int) -> Unit,
    val onMonths: (YearMonth, YearMonth) -> Unit,
    val onPlay: () -> Unit,
    val onShuffle: () -> Unit,
    val onSave: (name: String) -> Unit,
)

/**
 * The "Top N" face of Stats: a list of the songs of a period, and a summary of the same period.
 *
 * It is a mode of the screen, not a separate experience: the period is a year or a run of months, the size is 25, 50 or 100, the list
 * is ranked by the same Plays/Time switch as everything else and carries both numbers on every row, and the summary under it is made
 * by the very functions the rest of the screen uses (`StatsEngine`), so the numbers cannot disagree with the Summary face.
 */
internal fun LazyListScope.statsTop(
    state: TopUiState,
    list: TopNReport?,
    look: StatsLook,
    firstMonth: YearMonth,
    lastMonth: YearMonth,
    actions: TopActions,
    isFailed: Boolean = false,
) {
    item(key = "top-title") { TopTitle(state, look) }
    item(key = "top-period") { PeriodControls(state, look, firstMonth, lastMonth, actions) }
    item(key = "top-size") {
        Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Lg)) {
            Text(text = stringResource(R.string.stats_top_size), style = DapType.Note, color = Dap.tertiaryText)
            Spacer(Modifier.height(DapSpacing.Xs))
            DapSegmentedPicker(
                options = TopUiState.Sizes,
                selected = state.size,
                onSelect = actions.onSize,
                label = { it.toString() },
                fillWidth = true,
            )
        }
    }

    if (list == null) {
        item(key = "top-loading") {
            if (isFailed) {
                DapEmptyState(
                    title = stringResource(R.string.stats_state_error_title),
                    message = stringResource(R.string.stats_state_error_body),
                    modifier = Modifier.padding(vertical = DapSpacing.GroupGap),
                )
            } else {
                Column(Modifier.padding(top = DapSpacing.SectionGap)) { DapSkeletonList(count = 6) }
            }
        }
        return
    }

    item(key = "top-actions") { TopActionsRow(state, list, actions, look) }

    if (list.rows.isEmpty()) {
        item(key = "top-empty") {
            DapEmptyState(
                title = stringResource(R.string.stats_top_empty_title),
                message = stringResource(R.string.stats_top_empty_body),
                modifier = Modifier.padding(vertical = DapSpacing.GroupGap),
            )
        }
    } else {
        if (list.isShort) {
            item(key = "top-short") {
                StatsNote(
                    text = pluralStringResource(R.plurals.stats_top_short, list.eligible, list.eligible, list.size),
                    modifier = Modifier.padding(top = DapSpacing.Sm),
                )
            }
        }
        item(key = "top-rows-spacer") { Spacer(Modifier.height(DapSpacing.Sm)) }
        items(count = list.rows.size, key = { "top-row-$it-${list.rows[it].item.identity}" }) { index ->
            SongRankRow(index + 1, list.rows[index], look)
        }
    }

    item(key = "top-summary") { TopSummary(list.report, look) }
}

/** "Top 50 · 2026", and "so far" when the period is not over. */
@Composable
private fun TopTitle(state: TopUiState, look: StatsLook) {
    val period = state.period
    val label = StatsFormat.periodLabel(period, look.locale)
    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Lg)) {
        Text(
            text = stringResource(R.string.stats_top_title, state.size, label),
            style = DapType.Title,
            color = Dap.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val inProgress = state.report?.inProgress ?: !period.to.isBefore(YearMonth.now())
        if (inProgress) {
            Spacer(Modifier.height(DapSpacing.Xs))
            DapState(kind = DapStateKind.Connected, label = stringResource(R.string.stats_top_in_progress))
        }
    }
}

/**
 * The period: a year in one tap, or any run of months with a stepper at each end.
 * Month precision only, so there is nothing to type and nothing that can be mistyped.
 */
@Composable
private fun PeriodControls(
    state: TopUiState,
    look: StatsLook,
    firstMonth: YearMonth,
    lastMonth: YearMonth,
    actions: TopActions,
) {
    val years = (firstMonth.year..lastMonth.year).toList().reversed()
    val period = state.period
    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Lg)) {
        if (years.isNotEmpty()) {
            DapSegmentedPicker(
                options = years,
                // A selection that is not a whole year highlights none of them (-1 is no year): the steppers below say what it is.
                selected = years.firstOrNull { period.isWholeYear && period.from.year == it } ?: -1,
                onSelect = actions.onYear,
                label = { it.toString() },
                fillWidth = true,
            )
        }
        MonthStepper(
            label = stringResource(R.string.stats_top_from),
            value = state.from,
            locale = look.locale,
            canBack = state.from.isAfter(firstMonth),
            canForward = state.from.isBefore(state.to),
            onBack = { actions.onMonths(state.from.minusMonths(1), state.to) },
            onForward = { actions.onMonths(state.from.plusMonths(1), state.to) },
        )
        MonthStepper(
            label = stringResource(R.string.stats_top_to),
            value = state.to,
            locale = look.locale,
            canBack = state.to.isAfter(state.from),
            canForward = state.to.isBefore(YearMonth.of(lastMonth.year, 12)),
            onBack = { actions.onMonths(state.from, state.to.minusMonths(1)) },
            onForward = { actions.onMonths(state.from, state.to.plusMonths(1)) },
        )
    }
}

/** `Desde  ‹  mar 2026  ›` with a full touch target on each arrow. */
@Composable
private fun MonthStepper(
    label: String,
    value: YearMonth,
    locale: java.util.Locale,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().defaultMin48(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = DapType.Body, color = Dap.secondaryText, modifier = Modifier.weight(1f))
        StepButton(back = true, enabled = canBack, onClick = onBack, description = stringResource(R.string.stats_top_prev_month))
        Text(
            text = StatsFormat.month(value, locale),
            style = DapType.Value,
            color = Dap.primaryText,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(96.dp),
        )
        StepButton(back = false, enabled = canForward, onClick = onForward, description = stringResource(R.string.stats_top_next_month))
    }
}

private fun Modifier.defaultMin48(): Modifier = this.then(Modifier.height(DapSizes.MinTouchTarget))

@Composable
private fun StepButton(back: Boolean, enabled: Boolean, onClick: () -> Unit, description: String) {
    Box(
        Modifier
            .size(DapSizes.MinTouchTarget)
            .clip(RoundedCornerShape(4.dp))
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DapIcon(
            kind = if (back) DapIconKind.Chevron else DapIconKind.ChevronRight,
            tint = if (enabled) Dap.secondaryText else Dap.disabledText,
            size = 16.dp,
        )
    }
}

/** Play it (in the order shown), shuffle it (random only on top), and save it as a playlist. */
@Composable
private fun TopActionsRow(state: TopUiState, list: TopNReport, actions: TopActions, look: StatsLook) {
    val title = stringResource(R.string.stats_top_title, state.size, StatsFormat.periodLabel(state.period, look.locale))
    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DapCollectionActions(
                onPlay = actions.onPlay,
                onShuffle = actions.onShuffle,
                playLabel = stringResource(R.string.presentation_batch_b_play_it),
                shuffleLabel = stringResource(R.string.shortcut_shuffle_short),
            )
            Spacer(Modifier.weight(1f))
            DapButton(
                label = stringResource(R.string.stats_top_save),
                onClick = { actions.onSave(title) },
                variant = DapButtonVariant.Secondary,
                enabled = list.rows.isNotEmpty() && !state.isSaving,
            )
        }
        state.savedAs?.let { name ->
            StatsNote(
                text = stringResource(R.string.stats_top_saved, name),
                kind = DapStateKind.Confirmed,
                modifier = Modifier.padding(top = DapSpacing.Xs),
            )
        }
    }
}

/** The period in numbers, from the same report the Summary face reads. */
@Composable
private fun TopSummary(report: StatsReport, look: StatsLook) {
    Section(stringResource(R.string.stats_top_summary_title)) {
        Column(Modifier.fillMaxWidth()) {
            val totals = report.totals
            ValueLine(stringResource(R.string.stats_figure_time), formatListeningDurationCompact(totals.listenedMs))
            ValueLine(stringResource(R.string.stats_figure_plays), totals.plays.toString())
            report.rankings.artists(look.mode).take(3).let { top ->
                if (top.isNotEmpty()) {
                    SubHeader(stringResource(R.string.stats_artists))
                    top.forEach { row ->
                        ValueLine(
                            label = row.name.ifBlank { stringResource(R.string.stats_unknown_artist) },
                            value = pluralStringResource(R.plurals.stats_row_plays, row.plays, row.plays) +
                                " · " + formatListeningDurationCompact(row.timeMs),
                            divider = false,
                            modifier = Modifier.statsRowOpen(look.opener.artist(row.name)),
                        )
                    }
                }
            }
            report.rankings.albums(look.mode).take(3).let { top ->
                if (top.isNotEmpty()) {
                    SubHeader(stringResource(R.string.stats_albums))
                    top.forEach { row ->
                        ValueLine(
                            label = row.title.ifBlank { stringResource(R.string.stats_unknown_album) },
                            value = pluralStringResource(R.plurals.stats_row_plays, row.plays, row.plays) +
                                " · " + formatListeningDurationCompact(row.timeMs),
                            divider = false,
                            modifier = Modifier.statsRowOpen(look.opener.album(row.key)),
                        )
                    }
                }
            }
            report.habits?.let { habits ->
                SubHeader(stringResource(R.string.stats_discoveries_title))
                ValueLine(
                    stringResource(R.string.stats_songs),
                    habits.discoveries.songCount.toString(),
                )
                ValueLine(
                    stringResource(R.string.stats_artists),
                    habits.discoveries.artistCount.toString(),
                )
                ValueLine(
                    stringResource(R.string.stats_albums),
                    habits.discoveries.albumCount.toString(),
                    divider = false,
                )
                val hour = habits.clock.busiestHour()
                val day = habits.clock.busiestDay()
                if (hour != null && day != null) {
                    SubHeader(stringResource(R.string.stats_clock_title))
                    ValueLine(
                        label = stringResource(R.string.stats_top_busiest_hour),
                        value = StatsFormat.hour(hour),
                    )
                    ValueLine(
                        label = stringResource(R.string.stats_top_busiest_day),
                        value = day.getDisplayName(TextStyle.FULL, look.locale),
                        divider = false,
                    )
                }
                val longest = habits.streaks.longest
                if (longest > 0) {
                    SubHeader(stringResource(R.string.stats_streak_title))
                    ValueLine(
                        stringResource(R.string.stats_streak_longest),
                        pluralStringResource(R.plurals.stats_days, longest, longest),
                        divider = false,
                    )
                }
            }
            report.audio?.takeIf { it.hasData }?.let { audio ->
                SubHeader(stringResource(R.string.stats_audio_title))
                val base = audio.recordedMs.coerceAtLeast(1L).toFloat()
                if (audio.purityMs + audio.resonanceMs > 0) {
                    ValueLine(
                        label = stringResource(R.string.stats_audio_purity),
                        value = StatsFormat.percent(audio.purityMs / base),
                    )
                    ValueLine(
                        label = stringResource(R.string.stats_audio_resonance),
                        value = StatsFormat.percent(audio.resonanceMs / base),
                    )
                }
                audio.devices.firstOrNull()?.let { device ->
                    ValueLine(stringResource(R.string.stats_audio_devices), device.name)
                }
                audio.formats.firstOrNull()?.let { format ->
                    val line = StatsFormat.formatLine(format.key) + (format.key.codecLabel?.let { " · $it" } ?: "")
                    ValueLine(stringResource(R.string.stats_audio_formats), line.ifEmpty { "—" }, divider = false)
                }
            }
        }
    }
}
