package com.lostf1sh.pixelplayeross.presentation.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.stats.AlbumProgress
import com.lostf1sh.pixelplayeross.data.stats.AlbumRow
import com.lostf1sh.pixelplayeross.data.stats.ArtistRow
import com.lostf1sh.pixelplayeross.data.stats.AudioStats
import com.lostf1sh.pixelplayeross.data.stats.GenreRow
import com.lostf1sh.pixelplayeross.data.stats.Habits
import com.lostf1sh.pixelplayeross.data.stats.ListenRoute
import com.lostf1sh.pixelplayeross.data.stats.SkipRow
import com.lostf1sh.pixelplayeross.data.stats.SongRow
import com.lostf1sh.pixelplayeross.data.stats.StatsRankMode
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsReport
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapStateKind
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.utils.formatListeningDurationCompact
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** What a "see all" opens: the whole of one ranking. */
enum class StatsList { SONGS, ARTISTS, ALBUMS, GENRES }

/** How many rows of a ranking the summary shows before "see all". */
internal const val SUMMARY_ROWS = 5

/** Everything the summary needs to draw that is not in the report: the mode, the clock's zone and the language. */
internal class StatsLook(
    val mode: StatsRankMode,
    val zone: ZoneId,
    val locale: Locale,
    /** B32-Q2: what the rows open (nothing, where it is not wired). */
    val opener: StatsOpener = StatsOpener.None,
)

/**
 * The summary of one period, as the items of the screen's list (B28).
 *
 * Reading order is the hierarchy: how much first (the headline numbers), what can't be trusted next (the unattributed note), then the
 * shape of it (timeline), who (songs, artists, albums, genres), how concentrated, how it sounded (audio), and the habits.
 * A section with nothing to show says so in a line; it does not disappear, so the page keeps the same shape from one range to the next.
 */
internal fun LazyListScope.statsSummary(
    report: StatsReport,
    look: StatsLook,
    onSeeAll: (StatsList) -> Unit,
) {
    item(key = "headline") { Headline(report) }
    item(key = "notes") { AttributionNotes(report) }

    item(key = "timeline") {
        Section(stringResource(R.string.stats_timeline_title)) {
            TimelineChart(report.timeline, look.mode, look.zone, look.locale)
        }
    }

    rankingSection(StatsList.SONGS, report, look, onSeeAll)
    rankingSection(StatsList.ARTISTS, report, look, onSeeAll)
    rankingSection(StatsList.ALBUMS, report, look, onSeeAll)
    rankingSection(StatsList.GENRES, report, look, onSeeAll)

    item(key = "concentration") { Concentration(report) }
    report.audio?.let { audio -> item(key = "audio") { Audio(audio, report, look) } }
    report.habits?.let { habits -> habitsSections(habits, report, look) }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(DapSpacing.SectionGap))
        DapGroup(title = title, content = content)
    }
}

// --- the headline ------------------------------------------------------------------------------------------

/** Time listened as the one big number, then plays, the daily average and the days with listening. */
@Composable
internal fun Headline(report: StatsReport) {
    val totals = report.totals
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.stats_figure_time),
            style = DapType.Note,
            color = Dap.tertiaryText,
        )
        Text(
            text = formatListeningDurationCompact(totals.listenedMs),
            style = DapType.Title,
            color = Dap.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DapSpacing.Md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DapSpacing.Md)) {
            Figure(
                value = totals.plays.toString(),
                label = stringResource(R.string.stats_figure_plays),
                modifier = Modifier.weight(1f),
            )
            Figure(
                value = formatListeningDurationCompact(totals.avgPerDayMs),
                label = stringResource(R.string.stats_figure_per_day),
                modifier = Modifier.weight(1f),
            )
            Figure(
                value = stringResource(R.string.stats_figure_days_value, totals.activeDays, totals.days),
                label = stringResource(R.string.stats_figure_days),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** What part of the listening cannot be tied to a track, said before anyone reads a ranking as the whole. */
@Composable
internal fun AttributionNotes(report: StatsReport) {
    val note = report.note
    if (note.unattributedMs <= 0L && note.goneMs <= 0L && note.locatorMs <= 0L) return
    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Lg)) {
        if (note.unattributedMs > 0L) {
            StatsNote(
                title = stringResource(R.string.stats_note_unattributed_title),
                text = stringResource(R.string.stats_note_unattributed_body, formatListeningDurationCompact(note.unattributedMs)),
                kind = DapStateKind.Attention,
            )
        }
        if (note.goneMs > 0L) {
            StatsNote(text = stringResource(R.string.stats_note_gone_body, formatListeningDurationCompact(note.goneMs)))
        }
        if (note.locatorMs > 0L) {
            StatsNote(text = stringResource(R.string.stats_note_locator_body, formatListeningDurationCompact(note.locatorMs)))
        }
    }
}

// --- rankings ----------------------------------------------------------------------------------------------

private fun LazyListScope.rankingSection(kind: StatsList, report: StatsReport, look: StatsLook, onSeeAll: (StatsList) -> Unit) {
    item(key = "ranking-${kind.name}") {
        val total = when (kind) {
            StatsList.SONGS -> report.rankings.songCount
            StatsList.ARTISTS -> report.rankings.artistCount
            StatsList.ALBUMS -> report.rankings.albumCount
            StatsList.GENRES -> report.rankings.genreCount
        }
        Section(rankingTitle(kind)) {
            if (total == 0) {
                Text(
                    text = stringResource(R.string.stats_ranking_empty),
                    style = DapType.Caption,
                    color = Dap.tertiaryText,
                    modifier = Modifier.padding(vertical = DapSpacing.Sm),
                )
            } else {
                Column(Modifier.fillMaxWidth()) {
                    RankingRows(kind, report, look, limit = SUMMARY_ROWS)
                    if (total > SUMMARY_ROWS) {
                        SeeAllRow(stringResource(R.string.stats_see_all, total), onClick = { onSeeAll(kind) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun rankingTitle(kind: StatsList): String = stringResource(
    when (kind) {
        StatsList.SONGS -> R.string.stats_songs
        StatsList.ARTISTS -> R.string.stats_artists
        StatsList.ALBUMS -> R.string.stats_albums
        StatsList.GENRES -> R.string.stats_genres
    },
)

/** The rows of one ranking, up to [limit] (all of them for the full list). */
@Composable
internal fun RankingRows(kind: StatsList, report: StatsReport, look: StatsLook, limit: Int) {
    when (kind) {
        StatsList.SONGS -> report.rankings.songs(look.mode).take(limit).forEachIndexed { index, row -> SongRankRow(index + 1, row, look) }
        StatsList.ARTISTS -> report.rankings.artists(look.mode).take(limit).forEachIndexed { index, row -> ArtistRankRow(index + 1, row, look) }
        StatsList.ALBUMS -> report.rankings.albums(look.mode).take(limit).forEachIndexed { index, row -> AlbumRankRow(index + 1, row, look) }
        StatsList.GENRES -> report.rankings.genres(look.mode).take(limit).forEachIndexed { index, row -> GenreRankRow(index + 1, row, look) }
    }
}

@Composable
internal fun SongRankRow(rank: Int, row: SongRow, look: StatsLook) {
    RankRow(
        rank = rank,
        title = row.item.title,
        subtitle = row.item.artistLine.ifBlank { stringResource(R.string.stats_unknown_artist) },
        plays = row.plays,
        timeMs = row.timeMs,
        mode = look.mode,
        open = look.opener.song(row.item),
    ) { RowCover(row.item.song.albumArtUriString, row.item.title) }
}

@Composable
internal fun ArtistRankRow(rank: Int, row: ArtistRow, look: StatsLook) {
    val name = row.name.ifBlank { stringResource(R.string.stats_unknown_artist) }
    RankRow(
        rank = rank,
        title = name,
        subtitle = pluralStringResource(R.plurals.stats_row_songs, row.songs, row.songs),
        plays = row.plays,
        timeMs = row.timeMs,
        mode = look.mode,
        open = look.opener.artist(row.name),
    ) { InitialsAvatar(row.name.ifBlank { "?" }) }
}

@Composable
internal fun AlbumRankRow(rank: Int, row: AlbumRow, look: StatsLook) {
    RankRow(
        rank = rank,
        title = row.title.ifBlank { stringResource(R.string.stats_unknown_album) },
        subtitle = row.artist.ifBlank { stringResource(R.string.stats_unknown_artist) },
        plays = row.plays,
        timeMs = row.timeMs,
        mode = look.mode,
        open = look.opener.album(row.key),
    ) { RowCover(row.artUri, row.title) }
}

@Composable
internal fun GenreRankRow(rank: Int, row: GenreRow, look: StatsLook) {
    RankRow(
        rank = rank,
        title = row.name.ifBlank { stringResource(R.string.stats_unknown_genre) },
        subtitle = pluralStringResource(R.plurals.stats_row_artists, row.artists, row.artists),
        plays = row.plays,
        timeMs = row.timeMs,
        mode = look.mode,
        open = look.opener.genre(row.name),
    ) { InitialsAvatar(row.name.ifBlank { "?" }) }
}

// --- concentration -----------------------------------------------------------------------------------------

/** How much of the time the biggest songs take, over every song heard in the period, in either mode. */
@Composable
internal fun Concentration(report: StatsReport) {
    val c = report.concentration
    Section(stringResource(R.string.stats_concentration_title)) {
        if (c.population == 0) {
            Text(
                text = stringResource(R.string.stats_ranking_empty),
                style = DapType.Caption,
                color = Dap.tertiaryText,
                modifier = Modifier.padding(vertical = DapSpacing.Sm),
            )
        } else {
            Column(Modifier.fillMaxWidth()) {
                listOf(1 to c.top1, 3 to c.top3, 10 to c.top10).forEach { (n, share) ->
                    if (c.population >= n || n == 1) {
                        ShareRow(
                            label = pluralStringResource(R.plurals.stats_concentration_top, n, n),
                            value = StatsFormat.percent(share),
                            share = share,
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.stats_concentration_basis,
                        c.population,
                        formatListeningDurationCompact(c.denominatorMs),
                    ),
                    style = DapType.Note,
                    color = Dap.tertiaryText,
                    modifier = Modifier.fillMaxWidth().padding(vertical = DapSpacing.Sm),
                )
            }
        }
    }
}

// --- audio -------------------------------------------------------------------------------------------------

@Composable
private fun routeName(route: ListenRoute): String = stringResource(
    when (route) {
        ListenRoute.USB_DIRECT -> R.string.stats_route_usb_direct
        ListenRoute.ANDROID_USB -> R.string.stats_route_android_usb
        ListenRoute.SPEAKER -> R.string.stats_route_speaker
        ListenRoute.BLUETOOTH -> R.string.stats_route_bluetooth
        ListenRoute.WIRED -> R.string.stats_route_wired
        ListenRoute.OTHER -> R.string.stats_route_other
    },
)

/**
 * How the period sounded: signal mode, bit-perfect, the way out, the formats. Only what the app recorded while it played:
 * older listening has no audio facts and is shown as not recorded, with the date they begin, never as a guess.
 */
@Composable
internal fun Audio(audio: AudioStats, report: StatsReport, look: StatsLook) {
    Section(stringResource(R.string.stats_audio_title)) {
        val since = audio.sinceMs?.let { StatsFormat.date(it, look.zone, look.locale) }
        Column(Modifier.fillMaxWidth()) {
            if (!audio.hasData) {
                StatsNote(
                    title = stringResource(R.string.stats_audio_none_title),
                    text = if (since != null) {
                        stringResource(R.string.stats_audio_none_body_since, since)
                    } else {
                        stringResource(R.string.stats_audio_none_body)
                    },
                )
                return@Column
            }
            val base = audio.recordedMs.coerceAtLeast(1L).toFloat()
            fun share(ms: Long) = ms / base

            if (since != null) {
                Text(
                    text = stringResource(R.string.stats_audio_since, since),
                    style = DapType.Note,
                    color = Dap.tertiaryText,
                    modifier = Modifier.fillMaxWidth().padding(vertical = DapSpacing.Sm),
                )
            }

            SubHeader(stringResource(R.string.stats_audio_signal))
            if (audio.purityMs > 0) ShareRow(stringResource(R.string.stats_audio_purity), time(audio.purityMs, share(audio.purityMs)), share(audio.purityMs))
            if (audio.resonanceMs > 0) ShareRow(stringResource(R.string.stats_audio_resonance), time(audio.resonanceMs, share(audio.resonanceMs)), share(audio.resonanceMs))
            if (audio.noSignalMs > 0) ShareRow(stringResource(R.string.stats_audio_no_signal), time(audio.noSignalMs, share(audio.noSignalMs)), share(audio.noSignalMs))

            SubHeader(stringResource(R.string.stats_audio_bit_perfect))
            if (audio.bitPerfectMs > 0) ShareRow(stringResource(R.string.stats_audio_bp_yes), time(audio.bitPerfectMs, share(audio.bitPerfectMs)), share(audio.bitPerfectMs))
            if (audio.notBitPerfectMs > 0) ShareRow(stringResource(R.string.stats_audio_bp_no), time(audio.notBitPerfectMs, share(audio.notBitPerfectMs)), share(audio.notBitPerfectMs))
            if (audio.unknownBitPerfectMs > 0) ShareRow(stringResource(R.string.stats_audio_bp_unknown), time(audio.unknownBitPerfectMs, share(audio.unknownBitPerfectMs)), share(audio.unknownBitPerfectMs))

            SubHeader(stringResource(R.string.stats_audio_route))
            audio.routes.forEach { route ->
                ShareRow(routeName(route.route), time(route.timeMs, share(route.timeMs)), share(route.timeMs))
            }
            if (audio.devices.isNotEmpty()) {
                SubHeader(stringResource(R.string.stats_audio_devices))
                audio.devices.forEach { device ->
                    ShareRow(device.name, time(device.timeMs, share(device.timeMs)), share(device.timeMs))
                }
            }

            SubHeader(stringResource(R.string.stats_audio_formats))
            audio.formats.take(FORMAT_ROWS).forEach { format ->
                val line = StatsFormat.formatLine(format.key)
                val codec = format.key.codecLabel
                val label = when {
                    line.isNotEmpty() && codec != null -> "$line · $codec"
                    line.isNotEmpty() -> line
                    codec != null -> codec
                    else -> stringResource(R.string.stats_format_unknown)
                }
                ShareRow(label, time(format.timeMs, share(format.timeMs)), share(format.timeMs))
            }

            audio.highest?.let { highest ->
                SubHeader(stringResource(R.string.stats_audio_highest))
                val line = StatsFormat.formatLine(highest.key) + (highest.key.codecLabel?.let { " · $it" } ?: "")
                MediaLine(
                    title = highest.item?.title ?: stringResource(R.string.stats_audio_highest_gone),
                    subtitle = highest.item?.artistLine?.ifBlank { null },
                    value = line,
                    open = look.opener.song(highest.item),
                )
            }

            if (audio.mixedMs > 0 || audio.notRecordedMs > 0) {
                SubHeader(stringResource(R.string.stats_audio_not_recorded))
                if (audio.mixedMs > 0) {
                    ValueLine(stringResource(R.string.stats_audio_mixed), formatListeningDurationCompact(audio.mixedMs))
                }
                if (audio.notRecordedMs > 0) {
                    ValueLine(
                        label = if (since != null) stringResource(R.string.stats_audio_before, since) else stringResource(R.string.stats_audio_not_recorded),
                        value = formatListeningDurationCompact(audio.notRecordedMs),
                    )
                }
            }
        }
    }
}

private const val FORMAT_ROWS = 6

@Composable
private fun time(ms: Long, share: Float): String = "${formatListeningDurationCompact(ms)} · ${StatsFormat.percent(share)}"

/** A line with a title, an optional second line and a value at the end. */
@Composable
internal fun MediaLine(title: String, subtitle: String?, value: String, modifier: Modifier = Modifier, open: RowOpen = RowOpen.None) {
    DapMediaRow(
        title = title,
        subtitle = subtitle,
        modifier = if (open.unavailable) modifier.alpha(UnavailableRowAlpha) else modifier,
        onClick = open.onClick,
        trailing = { Text(text = value, style = DapType.ValueSmall, color = Dap.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        divider = false,
    )
}

// --- habits ------------------------------------------------------------------------------------------------

private fun LazyListScope.habitsSections(habits: Habits, report: StatsReport, look: StatsLook) {
    item(key = "habits-clock") { ClockSection(habits, look) }
    item(key = "habits-streaks") { StreaksAndSessions(habits, report.period, look) }
    item(key = "habits-discoveries") { Discoveries(habits, report, look) }
    item(key = "habits-albums") { AlbumsHeard(habits, look) }
    item(key = "habits-skips") { Skips(habits, look) }
}

@Composable
private fun ClockSection(habits: Habits, look: StatsLook) {
    Section(stringResource(R.string.stats_clock_title)) {
        Column(Modifier.fillMaxWidth()) {
            ClockGrid(habits.clock, look.mode, look.locale)
            val hour = habits.clock.busiestHour()
            val day = habits.clock.busiestDay()
            if (hour != null && day != null) {
                Spacer(Modifier.height(DapSpacing.Sm))
                Text(
                    text = stringResource(
                        R.string.stats_clock_busiest,
                        StatsFormat.hour(hour),
                        day.getDisplayName(TextStyle.FULL, look.locale),
                    ),
                    style = DapType.Caption,
                    color = Dap.secondaryText,
                )
            }
        }
    }
}

/**
 * What the longest streak is called. "Current" runs back over the whole history, to today, while the longest is counted inside the range
 * (B28-fix2 E3); in a range that is not all time, "Current 27 days" next to "Longest 7 days" reads as a contradiction, so the longest
 * says it is the range's. Over all time nothing is cut and the longest is never shorter than the current one.
 */
@androidx.annotation.StringRes
internal fun streakLongestLabel(period: StatsPeriod): Int =
    if (period == StatsPeriod.Preset(StatsTimeRange.ALL)) R.string.stats_streak_longest else R.string.stats_streak_longest_in_period

@Composable
private fun StreaksAndSessions(habits: Habits, period: StatsPeriod, look: StatsLook) {
    Section(stringResource(R.string.stats_streak_title)) {
        Column(Modifier.fillMaxWidth()) {
            habits.streaks.current?.let { current ->
                ValueLine(
                    stringResource(R.string.stats_streak_current),
                    pluralStringResource(R.plurals.stats_days, current, current),
                )
            }
            val longest = habits.streaks.longest
            ValueLine(
                stringResource(streakLongestLabel(period)),
                if (longest == 0) "—" else pluralStringResource(R.plurals.stats_days, longest, longest),
                divider = false,
            )
            val start = habits.streaks.longestStart
            val end = habits.streaks.longestEnd
            if (start != null && end != null && longest > 1) {
                Text(
                    text = "${StatsFormat.date(start, look.locale)} – ${StatsFormat.date(end, look.locale)}",
                    style = DapType.Note,
                    color = Dap.tertiaryText,
                )
            }
        }
    }
    Section(stringResource(R.string.stats_sessions_title)) {
        Column(Modifier.fillMaxWidth()) {
            val sessions = habits.sessions
            ValueLine(stringResource(R.string.stats_sessions_count), sessions.count.toString())
            ValueLine(stringResource(R.string.stats_sessions_average), formatListeningDurationCompact(sessions.averageMs))
            ValueLine(stringResource(R.string.stats_sessions_longest), formatListeningDurationCompact(sessions.longestMs), divider = false)
            Text(
                text = stringResource(R.string.stats_sessions_note),
                style = DapType.Note,
                color = Dap.tertiaryText,
                modifier = Modifier.fillMaxWidth().padding(vertical = DapSpacing.Sm),
            )
        }
    }
}

@Composable
private fun Discoveries(habits: Habits, report: StatsReport, look: StatsLook) {
    val d = habits.discoveries
    Section(stringResource(R.string.stats_discoveries_title)) {
        Column(Modifier.fillMaxWidth()) {
            ValueLine(stringResource(R.string.stats_songs), d.songCount.toString())
            ValueLine(stringResource(R.string.stats_artists), d.artistCount.toString())
            ValueLine(stringResource(R.string.stats_albums), d.albumCount.toString(), divider = false)
            d.songs.take(SUMMARY_ROWS).forEach { discovered ->
                MediaLine(
                    title = discovered.item.title,
                    subtitle = discovered.item.artistLine.ifBlank { stringResource(R.string.stats_unknown_artist) },
                    value = StatsFormat.date(discovered.firstPlayMs, look.zone, look.locale),
                    open = look.opener.song(discovered.item),
                )
            }
            report.historyStartMs?.let { start ->
                Text(
                    text = stringResource(R.string.stats_discoveries_note, StatsFormat.date(start, look.zone, look.locale)),
                    style = DapType.Note,
                    color = Dap.tertiaryText,
                    modifier = Modifier.fillMaxWidth().padding(vertical = DapSpacing.Sm),
                )
            }
        }
    }
}

@Composable
private fun AlbumsHeard(habits: Habits, look: StatsLook) {
    val heard = habits.albums
    Section(stringResource(R.string.stats_albums_title)) {
        Column(Modifier.fillMaxWidth()) {
            ValueLine(
                stringResource(R.string.stats_albums_complete),
                heard.completeCount.toString(),
                divider = heard.complete.isNotEmpty() || heard.partial.isNotEmpty(),
            )
            heard.complete.take(SUMMARY_ROWS).forEach { album -> AlbumProgressRow(album, look.opener.album(album.key)) }
            heard.partial.forEach { album -> AlbumProgressRow(album, look.opener.album(album.key)) }
            Text(
                text = stringResource(R.string.stats_albums_note, com.lostf1sh.pixelplayeross.data.stats.StatsEngine.MIN_ALBUM_TRACKS),
                style = DapType.Note,
                color = Dap.tertiaryText,
                modifier = Modifier.fillMaxWidth().padding(vertical = DapSpacing.Sm),
            )
        }
    }
}

@Composable
private fun AlbumProgressRow(album: AlbumProgress, open: RowOpen) {
    Column(Modifier.fillMaxWidth().then(if (open.unavailable) Modifier.alpha(UnavailableRowAlpha) else Modifier)) {
        DapMediaRow(
            title = album.title.ifBlank { stringResource(R.string.stats_unknown_album) },
            subtitle = album.artist.ifBlank { null },
            onClick = open.onClick,
            leading = { RowCover(album.artUri, album.title) },
            trailing = {
                Text(
                    text = stringResource(R.string.stats_albums_progress, album.tracksPlayed, album.tracks),
                    style = DapType.Value,
                    color = if (album.isComplete) Dap.primaryText else Dap.secondaryText,
                    maxLines = 1,
                )
            },
            divider = false,
        )
        Bar(album.tracksPlayed.toFloat() / album.tracks.coerceAtLeast(1))
        Spacer(Modifier.height(DapSpacing.Xs))
    }
}

@Composable
private fun Skips(habits: Habits, look: StatsLook) {
    val skips = habits.skips
    Section(stringResource(R.string.stats_skips_title)) {
        Column(Modifier.fillMaxWidth()) {
            val since = skips.sinceMs?.let { StatsFormat.date(it, look.zone, look.locale) }
            if (since == null) {
                StatsNote(
                    title = stringResource(R.string.stats_audio_none_title),
                    text = stringResource(R.string.stats_skips_none_body),
                )
                return@Column
            }
            Text(
                text = stringResource(R.string.stats_skips_since, since),
                style = DapType.Note,
                color = Dap.tertiaryText,
                modifier = Modifier.fillMaxWidth().padding(vertical = DapSpacing.Sm),
            )
            if (skips.songs.isEmpty() && skips.artists.isEmpty()) {
                Text(
                    text = stringResource(R.string.stats_skips_nothing, com.lostf1sh.pixelplayeross.data.stats.StatsEngine.MIN_SONG_SKIP_SAMPLE),
                    style = DapType.Caption,
                    color = Dap.secondaryText,
                    modifier = Modifier.fillMaxWidth().padding(vertical = DapSpacing.Sm),
                )
            }
            if (skips.songs.isNotEmpty()) {
                SubHeader(stringResource(R.string.stats_songs))
                skips.songs.take(SUMMARY_ROWS).forEach { SkipLine(it, look.opener.song(it.item)) }
            }
            if (skips.artists.isNotEmpty()) {
                SubHeader(stringResource(R.string.stats_artists))
                skips.artists.take(SUMMARY_ROWS).forEach { SkipLine(it, look.opener.artist(it.name)) }
            }
        }
    }
}

@Composable
private fun SkipLine(row: SkipRow, open: RowOpen) {
    MediaLine(
        title = row.name.ifBlank { stringResource(R.string.stats_unknown_artist) },
        subtitle = row.artist,
        value = stringResource(R.string.stats_skips_row, row.skips, row.starts, StatsFormat.percent(row.rate)),
        open = open,
    )
}
