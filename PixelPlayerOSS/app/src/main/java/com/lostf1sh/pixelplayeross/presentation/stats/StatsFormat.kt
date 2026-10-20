package com.lostf1sh.pixelplayeross.presentation.stats

import com.lostf1sh.pixelplayeross.data.stats.AudioFormatKey
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Text the Stats screens make from numbers, in one place so the screen, the Top N title and the tests say the same thing.
 * Nothing here is a sentence: sentences are string resources, and these are their arguments.
 */
internal object StatsFormat {

    /** A share as a whole percent: `68%`. */
    fun percent(share: Float): String = "${(share * 100f).roundToInt()}%"

    /** A sample rate in kHz without a trailing zero: 44100 → `44.1`, 48000 → `48`, 88200 → `88.2`. */
    fun kilohertz(rateHz: Int): String {
        val khz = rateHz / 1000.0
        val text = String.format(Locale.US, "%.1f", khz)
        return text.removeSuffix(".0")
    }

    /** `44.1 kHz / 16 bit`, or `96 kHz` when the depth is not known (a compressed source reports none). */
    fun formatLine(key: AudioFormatKey): String = buildString {
        key.rateHz?.let { append(kilohertz(it)).append(" kHz") }
        key.bits?.let { if (isNotEmpty()) append(" / "); append(it).append(" bit") }
    }

    /** The hour of day as the player's clock draws it: `02:00`. */
    fun hour(hour: Int): String = String.format(Locale.US, "%02d:00", hour)

    /** A date as the listener's locale writes it, short: `17 Oct 2026`. */
    fun date(epochMs: Long, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())

    fun date(day: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)

    fun monthShort(month: Month, locale: Locale): String = month.getDisplayName(TextStyle.SHORT, locale)

    /** `oct 2026`: one month, as a stepper shows it. */
    fun month(month: YearMonth, locale: Locale): String = "${monthShort(month.month, locale)} ${month.year}"

    /**
     * The period of a "Top N" as it is written in its title: `2026` for a whole year, `mar–ago 2026` inside a year,
     * `oct 2025–mar 2026` across two. One month is `mar 2026`.
     */
    fun periodLabel(period: StatsPeriod.Months, locale: Locale): String = when {
        period.isWholeYear -> period.from.year.toString()
        period.from == period.to -> month(period.from, locale)
        period.from.year == period.to.year ->
            "${monthShort(period.from.month, locale)}–${monthShort(period.to.month, locale)} ${period.from.year}"
        else -> "${month(period.from, locale)}–${month(period.to, locale)}"
    }

    /** The one or two initials an artist's avatar shows, upper case: `Yung Lean` → `YL`, `!!!` → `!`. */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val letters = words.take(2).mapNotNull { word -> word.codePoints().toArray().firstOrNull()?.let { String(Character.toChars(it)) } }
        return letters.joinToString("").uppercase(Locale.ROOT)
    }
}
