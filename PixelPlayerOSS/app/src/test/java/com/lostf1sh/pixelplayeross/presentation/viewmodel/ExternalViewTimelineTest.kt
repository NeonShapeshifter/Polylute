package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ExternalViewTimelineTest {

    @Test
    fun `every step is written in milliseconds after the request, in the order it happened`() {
        val timeline = ExternalViewTimeline(processAgeAtRequestMs = 1_184L, requestedAtMs = 10_000L)
        timeline.mark(ExternalViewTimeline.BUILT, 10_212L)
        timeline.mark(ExternalViewTimeline.HANDED, 10_530L)
        timeline.mark(ExternalViewTimeline.CONTROLLER, 15_655L)
        timeline.mark(ExternalViewTimeline.LOADED, 15_655L)
        timeline.mark(ExternalViewTimeline.PLAY, 15_700L)
        assertThat(timeline.describe()).isEqualTo(
            "process_age_at_request=1184ms built=212ms handed=530ms controller=5655ms loaded=5655ms play=5700ms",
        )
    }

    @Test
    fun `a step that did not happen is absent and the first mark of a step stays`() {
        val timeline = ExternalViewTimeline(processAgeAtRequestMs = 90_000L, requestedAtMs = 1_000L)
        timeline.mark(ExternalViewTimeline.PLAY, 1_300L)
        timeline.mark(ExternalViewTimeline.PLAY, 1_900L) // a retry after a refusal
        assertThat(timeline.has(ExternalViewTimeline.PLAYING)).isFalse()
        assertThat(timeline.describe()).isEqualTo("process_age_at_request=90000ms play=300ms")
    }

    @Test
    fun `a clock reading before the request is clamped to zero, never negative`() {
        val timeline = ExternalViewTimeline(processAgeAtRequestMs = 0L, requestedAtMs = 500L)
        timeline.mark(ExternalViewTimeline.BUILT, 400L)
        assertThat(timeline.describe()).isEqualTo("process_age_at_request=0ms built=0ms")
    }
}
