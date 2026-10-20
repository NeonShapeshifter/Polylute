package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit.DAY
import com.lostf1sh.pixelplayeross.data.stats.Kit.HOUR
import com.lostf1sh.pixelplayeross.data.stats.Kit.MIN
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import org.junit.jupiter.api.Test

/**
 * How long a history has been observed, which is not the time between its events (R28-04). Synthetic data only.
 */
class LedgerObservationTest {
    private val first = at(2026, 9, 1, 15)

    private fun ledger(vararg endsMs: Long): ListeningLedger = ListeningLedger.build(
        endsMs.mapIndexed { index, end -> Kit.play(1, end, occurrence = "o$index") },
        Kit.catalog(listOf(Kit.song(1))),
    )

    @Test
    fun `the span is the time between the events and the observation runs on to the clock`() {
        val ledger = ledger(first, first + HOUR)

        assertThat(ledger.spanDays()).isEqualTo(0)
        assertThat(ledger.observedDays(first + 10 * DAY)).isEqualTo(10)
        assertThat(ledger.observedDays(first + 45 * DAY)).isEqualTo(45)
    }

    @Test
    fun `it starts where the first listen started and no earlier`() {
        // Kit.play lasts three minutes: the first listen began three minutes before it ended.
        val ledger = ledger(first)

        assertThat(ledger.firstMs).isEqualTo(first - 3 * MIN)
        assertThat(ledger.observedDays(first - 3 * MIN + 7 * DAY - 1)).isEqualTo(6)
        assertThat(ledger.observedDays(first - 3 * MIN + 7 * DAY)).isEqualTo(7)
    }

    @Test
    fun `a clock behind the history cannot make it younger than its own events, and an empty history observes nothing`() {
        val ledger = ledger(first, first + 5 * DAY)

        // Before the first listen and between the first and the last: the history itself, 5 days, never less and never negative.
        assertThat(ledger.observedDays(first - 10 * DAY)).isEqualTo(ledger.spanDays())
        assertThat(ledger.observedDays(first + 2 * DAY)).isEqualTo(ledger.spanDays())
        assertThat(ledger.spanDays()).isEqualTo(5)
        assertThat(ListeningLedger.Empty.observedDays(first)).isEqualTo(0)
    }
}
