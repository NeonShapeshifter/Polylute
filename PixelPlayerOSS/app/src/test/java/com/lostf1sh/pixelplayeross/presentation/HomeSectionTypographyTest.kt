package com.lostf1sh.pixelplayeross.presentation

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-131: on Home, "TU MIX" had "Tu mix de hoy" under it at Heading size while "MIX DIARIO" had
 * "Basado en el historial" at Caption size — two sections, two hierarchies.
 *
 * B25-S7: and each of them said its name twice. A tracked label over a heading, both of them naming the same mix, is gone from
 * both: every Home mix section is one DapType.Heading line in the primary text colour, with no label above it.
 */
class HomeSectionTypographyTest {
    private val base = "src/main/java/com/lostf1sh/pixelplayeross/presentation"
    private val yourMix = File("$base/screens/HomeScreen.kt").readText()
        .substringAfter("fun YourMixHeader(").substringBefore("\n}\n")
    private val dailyMix = File("$base/components/DailyMixSection.kt").readText()
        .substringAfter("fun DailyMixHeader(").substringBefore("\n}\n")

    private fun titleLine(section: String, textExpr: String): String =
        section.substringAfter("text = $textExpr").substringBefore("modifier")

    @Test
    fun `both mix sections are one heading each, in the same title style, with no label above it`() {
        listOf(yourMix, dailyMix).forEach { assertThat(it).doesNotContain("DapSectionHeader(") }
        // the old label strings are gone from the code, not just from the layout
        listOf(yourMix, dailyMix).forEach {
            assertThat(it).doesNotContain("home_your_mix_title")
            assertThat(it).doesNotContain("presentation_batch_g_daily_mix_heading")
        }
        val yourTitle = titleLine(yourMix, "song,")
        val dailyTitle = titleLine(dailyMix, "stringResource(R.string.home_daily_mix_title),")
        listOf(yourTitle, dailyTitle).forEach {
            assertThat(it).contains("style = DapType.Heading,")
            assertThat(it).contains("color = Dap.primaryText,")
        }
    }

    /**
     * AUD-132: "Basado en el historial" broke into two lines on the phone (360 dp) because three
     * 32 dp covers shared its row, leaving the title ~200 dp for ~290 dp of text.
     *
     * The covers are back, on a row of their own under the title: the title still has its line to
     * itself, so it cannot break in two again, and the covers keep the 32 dp size and the rounded
     * surface they always had.
     */
    @Test
    fun `the Daily Mix title is one line and the covers sit on their own row below it`() {
        val title = titleLine(dailyMix, "stringResource(R.string.home_daily_mix_title),")
        assertThat(title).contains("maxLines = 1,")
        assertThat(title).doesNotContain("maxLines = 2")
        // No weight next to a row of covers: nothing shares the line with it.
        assertThat(dailyMix).doesNotContain("Modifier.weight(1f)")
        val underTheTitle = dailyMix.substringAfter("overflow = TextOverflow.Ellipsis,")
        assertThat(underTheTitle).contains("thumbnails.forEach")
        assertThat(underTheTitle).contains(".size(32.dp)")
        assertThat(underTheTitle).contains("SmartImage(")
        assertThat(underTheTitle).contains("RoundedCornerShape(DapRadius.Surface)")
        val card = File("$base/components/DailyMixSection.kt").readText()
        assertThat(card).contains("DailyMixHeader(thumbnails = headerSongs)")
        assertThat(card).contains("val headerSongs = remember(songs) { songs.take(3).toImmutableList() }")
    }
}
