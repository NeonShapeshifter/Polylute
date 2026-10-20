package com.lostf1sh.pixelplayeross.data.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** The ranges the Stats screen offers. Each one runs from its start to now. */
enum class StatsTimeRange(val displayName: String) {
    DAY("Today"),
    WEEK("Week to Date"),
    MONTH("Month to Date"),
    YEAR("Year to Date"),
    ALL("All Time")
}

/**
 * A stretch of the history to report on: one of the [StatsTimeRange]s, or a run of whole months.
 *
 * Month precision is deliberate (the "Top N" of Stats is "2026" or "mar-ago 2026", never "3 March to 17 August"), so [Months]
 * holds two months and the period it names is from the first instant of [Months.from] to the last of [Months.to]. A period that
 * contains the current month stops at now and says so ([Bounds.inProgress]).
 */
sealed interface StatsPeriod {

    /** A preset range, from its start to now. */
    data class Preset(val range: StatsTimeRange) : StatsPeriod

    /** Whole months, [from] to [to] inclusive; a whole year is January to December. */
    data class Months(val from: YearMonth, val to: YearMonth) : StatsPeriod {
        init {
            require(!to.isBefore(from)) { "a month range does not end before it starts: $from..$to" }
        }

        /** True for exactly one calendar year. */
        val isWholeYear: Boolean get() = from.year == to.year && from.monthValue == 1 && to.monthValue == 12

        companion object {
            fun year(year: Int) = Months(YearMonth.of(year, 1), YearMonth.of(year, 12))
        }
    }

    /**
     * The instants a period covers: [fromMs] inclusive, [toMs] exclusive, so two neighbouring periods never share one.
     * [fromMs] is null for "all time" on an empty history (there is no beginning to name).
     */
    class Bounds(val fromMs: Long?, val toMs: Long, val inProgress: Boolean) {
        /** The calendar days from the start to the end, at least one. */
        fun days(zone: ZoneId, firstMs: Long): Long {
            val start = Instant.ofEpochMilli(fromMs ?: firstMs).atZone(zone).toLocalDate()
            val end = Instant.ofEpochMilli(toMs - 1).atZone(zone).toLocalDate()
            return (java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1).coerceAtLeast(1L)
        }
    }

    /**
     * Resolves the period against [nowMs]. [firstEventMs] is when the history begins, which is where "all time" starts.
     * The end is exclusive: a listen that ended exactly at a boundary belongs to the period before it.
     */
    fun bounds(nowMs: Long, zone: ZoneId, firstEventMs: Long?): Bounds {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val end = nowMs + 1
        return when (this) {
            is Preset -> when (range) {
                StatsTimeRange.DAY -> Bounds(now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(), end, true)
                StatsTimeRange.WEEK -> Bounds(
                    now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .atStartOfDay(zone).toInstant().toEpochMilli(),
                    end,
                    true,
                )
                StatsTimeRange.MONTH -> Bounds(
                    YearMonth.from(now).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(), end, true,
                )
                StatsTimeRange.YEAR -> Bounds(
                    now.toLocalDate().withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli(), end, true,
                )
                StatsTimeRange.ALL -> Bounds(firstEventMs, end, true)
            }
            is Months -> {
                val start = from.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val after = to.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val inProgress = nowMs < after
                Bounds(start, if (inProgress) end else after, inProgress)
            }
        }
    }

    companion object {
        val Week = Preset(StatsTimeRange.WEEK)
    }
}
