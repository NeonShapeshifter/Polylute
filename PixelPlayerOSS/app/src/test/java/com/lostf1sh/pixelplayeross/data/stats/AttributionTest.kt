package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit.MIN
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.data.stats.Kit.event
import com.lostf1sh.pixelplayeross.data.stats.Kit.song
import org.junit.jupiter.api.Test

/**
 * R-04: an event belongs to the track it recorded, not to whatever the locator names today. The closure list of the review: a renumber,
 * a reuse, a vanish and a return, legacy events with no identity, and the library changing under an open screen.
 */
class AttributionTest {
    private val now = at(2026, 10, 1)

    private fun statsOf(ledger: ListeningLedger, identity: String) = ledger.stats(identity)

    @Test
    fun `a renumber moves the track to a new locator and its plays go with it`() {
        // The event was written when A was number 7. Now A is 99 and B holds 7.
        val catalog = Kit.catalog(listOf(song(99, identity = "A", title = "A"), song(7, identity = "B", title = "B")))
        val events = listOf(Kit.play(7, now - HOURS, identity = "A", occurrence = "o1"))
        val ledger = Kit.ledger(events, catalog)

        assertThat(statsOf(ledger, "A")?.plays).isEqualTo(1)
        assertThat(statsOf(ledger, "B")).isNull()
        assertThat(ledger.coverage.identityEvents).isEqualTo(1)
    }

    @Test
    fun `a locator reused by another track credits nobody but the track that was heard`() {
        // A is gone from the library; B was given its old number 7 later on.
        val catalog = Kit.catalog(
            songs = listOf(song(7, identity = "B", title = "B")),
            firstSeen = mapOf("B" to now - DAYS), // B arrived after the event
            withoutFile = setOf("A"),
        )
        val events = listOf(Kit.play(7, now - 3 * DAYS, identity = "A", occurrence = "o1"))
        val ledger = Kit.ledger(events, catalog)

        assertThat(statsOf(ledger, "B")).isNull()
        // The listening is counted, as time of a track the library has no file for.
        assertThat(ledger.coverage.goneMs).isEqualTo(3 * MIN)
        assertThat(ledger.coverage.totalMs).isEqualTo(3 * MIN)
    }

    @Test
    fun `a legacy event whose locator names a track that arrived after it is not credited to it`() {
        val catalog = Kit.catalog(listOf(song(7, identity = "B")), firstSeen = mapOf("B" to now - DAYS))
        val legacy = listOf(Kit.play(7, now - 3 * DAYS, identity = null, occurrence = null))
        val ledger = Kit.ledger(legacy, catalog)

        assertThat(statsOf(ledger, "B")).isNull()
        assertThat(ledger.coverage.unattributedMs).isEqualTo(3 * MIN)
    }

    @Test
    fun `a track that vanishes and comes back gets its whole history back`() {
        val events = listOf(
            Kit.play(7, now - 5 * DAYS, identity = "A", occurrence = "o1"),
            Kit.play(7, now - 4 * DAYS, identity = "A", occurrence = "o2"),
        )
        val whileGone = Kit.ledger(events, Kit.catalog(emptyList(), withoutFile = setOf("A")))
        assertThat(whileGone.coverage.goneMs).isEqualTo(6 * MIN)
        assertThat(whileGone.catalog.item("A")).isNull()

        val back = Kit.ledger(events, Kit.catalog(listOf(song(120, identity = "A"))))
        assertThat(statsOf(back, "A")?.plays).isEqualTo(2)
        assertThat(back.coverage.goneMs).isEqualTo(0L)
    }

    @Test
    fun `a legacy event with no identity is tied to the track at its locator while that is plausible`() {
        val catalog = Kit.catalog(listOf(song(3, identity = "C", durationMs = 4 * MIN)), firstSeen = mapOf("C" to now - 30 * DAYS))
        val ledger = Kit.ledger(listOf(Kit.play(3, now - DAYS, identity = null, occurrence = null)), catalog)

        assertThat(statsOf(ledger, "C")?.plays).isEqualTo(1)
        assertThat(ledger.coverage.locatorEvents).isEqualTo(1)
        assertThat(ledger.coverage.identityEvents).isEqualTo(0)
    }

    @Test
    fun `an event that recorded its length is not tied to a track of another length`() {
        val catalog = Kit.catalog(listOf(song(3, identity = "C", durationMs = 10 * MIN)))
        val event = Kit.event(3, now, 3 * MIN, identity = null, itemMs = 4 * MIN)
        val ledger = Kit.ledger(listOf(event), catalog)

        assertThat(statsOf(ledger, "C")).isNull()
        assertThat(ledger.coverage.unattributedEvents).isEqualTo(1)
    }

    @Test
    fun `a locator no track holds and an external file are not attributed and still count in the total`() {
        val catalog = Kit.catalog(listOf(song(1)))
        val external = Kit.event(0, now, 2 * MIN).copy(songId = "external:content://x/1")
        val ledger = Kit.ledger(listOf(Kit.event(55, now - HOURS, MIN), external), catalog)

        assertThat(ledger.coverage.unattributedEvents).isEqualTo(2)
        assertThat(ledger.coverage.unattributedMs).isEqualTo(3 * MIN)
        assertThat(ledger.coverage.totalMs).isEqualTo(3 * MIN)
        val report = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), now + MIN, Kit.zone)
        assertThat(report.totals.listenedMs).isEqualTo(3 * MIN)
        assertThat(report.rankings.songCount).isEqualTo(0)
    }

    @Test
    fun `the library changing under the ledger re-attributes the history`() {
        // The same events, read against the library before and after a sync that renumbered the track.
        val events = listOf(Kit.play(7, now - HOURS, identity = null, occurrence = null))
        val before = Kit.ledger(events, Kit.catalog(listOf(song(7, identity = "A")), firstSeen = mapOf("A" to now - 30 * DAYS)))
        val after = Kit.ledger(events, Kit.catalog(listOf(song(8, identity = "A")), firstSeen = mapOf("A" to now - 30 * DAYS)))

        assertThat(before.stats("A")?.plays).isEqualTo(1)
        assertThat(after.stats("A")).isNull()
        assertThat(after.coverage.unattributedEvents).isEqualTo(1)
    }

    private companion object {
        const val HOURS = 60 * MIN
        const val DAYS = 24 * HOURS
    }
}
