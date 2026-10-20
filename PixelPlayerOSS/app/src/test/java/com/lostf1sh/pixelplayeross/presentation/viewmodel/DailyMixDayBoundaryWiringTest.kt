package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-132 wiring: the rules in [DailyMixSchedule] only help if something asks them. The check used
 * to run from MainActivity.onStart alone, which a HOME app under Lock Task rarely sees.
 */
class DailyMixDayBoundaryWiringTest {
    private val base = "src/main/java/com/lostf1sh/pixelplayeross"
    private val holder = File("$base/presentation/viewmodel/DailyMixStateHolder.kt").readText()
    private val viewModel = File("$base/presentation/viewmodel/PlayerViewModel.kt").readText()
    private val activity = File("$base/MainActivity.kt").readText()

    @Test
    fun `the holder decides the day with the schedule rules`() {
        assertThat(holder).contains("DailyMixSchedule.isNewDay(")
        assertThat(holder).doesNotContain("fun localEpochDay(")
    }

    @Test
    fun `a running app renews at the next local midnight and on a date, time or zone change`() {
        assertThat(holder).contains("DailyMixSchedule.msUntilNextLocalMidnight(")
        assertThat(holder).contains("Intent.ACTION_DATE_CHANGED")
        assertThat(holder).contains("Intent.ACTION_TIME_CHANGED")
        assertThat(holder).contains("Intent.ACTION_TIMEZONE_CHANGED")
        assertThat(viewModel).contains("dailyMixStateHolder.startDayBoundaryWatch(")
    }

    @Test
    fun `the first use of the day checks, not only an activity start`() {
        val onResume = activity.substringAfter("override fun onResume()").substringBefore("override fun ")
        assertThat(onResume).contains("playerViewModel.onMainActivityResume()")
        val resumeHook = viewModel.substringAfter("fun onMainActivityResume()").substringBefore("\n    }")
        // B23-P1: the resume still asks, through the one object that keeps a wake's start and resume out of the burst of the wake
        // (ActivityDayCheckTest); what it asks is the same check.
        assertThat(resumeHook).contains("activityDayCheck.onActivityResume()")
        assertThat(viewModel).contains("ActivityDayCheck(viewModelScope) { checkAndUpdateDailyMixIfNeeded() }")
    }
}
