package com.lostf1sh.pixelplayeross.data.collections.auto

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.Kit.DAY
import com.lostf1sh.pixelplayeross.data.stats.Kit.HOUR
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.data.stats.Kit.song
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import org.junit.jupiter.api.Test

/**
 * What the collections that need a long enough history do while the listener is silent (R28-04).
 *
 * The history stops growing when nobody listens, but the time it has been observed does not: a collection that asks for three
 * weeks of history has them three weeks after the first listen, whether or not there was a listen in between. Every test here
 * builds one ledger and only moves the clock. Nothing is a listener's data.
 */
class AutoCollectionMaturityTest {
    private val firstListen = at(2026, 9, 1, 15)
    private var seq = 0

    /** Three plays of song 1 in one afternoon, and a library of three. The only listening there will ever be. */
    private fun afternoon(favourite: Boolean = false): ListeningLedger {
        val events = listOf(
            Kit.play(1, firstListen, occurrence = "o${seq++}"),
            Kit.play(1, firstListen + HOUR, occurrence = "o${seq++}"),
            Kit.play(1, firstListen + 2 * HOUR, occurrence = "o${seq++}"),
        )
        val catalog = Kit.catalog(
            listOf(song(1), song(2, favourite = favourite), song(3)),
            firstSeen = (1..3).associate { "item-$it" to firstListen - 90 * DAY },
        )
        return ListeningLedger.build(events, catalog)
    }

    private fun compute(ledger: ListeningLedger, nowMs: Long) = AutoCollectionEngine.compute(ledger, nowMs, Kit.zone)

    // --- Redescubre -------------------------------------------------------------------------------------------

    @Test
    fun `rediscover matures as the clock advances over a history that does not change`() {
        val ledger = afternoon()

        val early = compute(ledger, firstListen + 20 * DAY)[AutoCollectionId.REDISCOVER]!!
        assertThat(early.items).isEmpty()
        assertThat(early.emptyReason).isEqualTo(EmptyReason.TOO_LITTLE_HISTORY)
        assertThat(early.needDays).isEqualTo(21)
        assertThat(early.haveDays).isEqualTo(20)

        val mature = compute(ledger, firstListen + 28 * DAY)[AutoCollectionId.REDISCOVER]!!
        assertThat(mature.items.map { it.identity }).containsExactly("item-1")
        assertThat(mature.emptyReason).isNull()

        // And it stays: nothing was listened to, nothing came off the list.
        val later = compute(ledger, firstListen + 120 * DAY)[AutoCollectionId.REDISCOVER]!!
        assertThat(later.items.map { it.identity }).containsExactly("item-1")
    }

    @Test
    fun `the age shown for a collection that waits for history keeps growing while nobody listens`() {
        val ledger = afternoon()

        val week = compute(ledger, firstListen + 7 * DAY)[AutoCollectionId.REDISCOVER]!!
        val fortnight = compute(ledger, firstListen + 14 * DAY)[AutoCollectionId.REDISCOVER]!!

        assertThat(week.haveDays).isEqualTo(7)
        assertThat(fortnight.haveDays).isEqualTo(14)
        // The gap between the events of this history is two hours; it is not the age of the history.
        assertThat(ledger.spanDays()).isEqualTo(0)
    }

    // --- Favoritos olvidados ----------------------------------------------------------------------------------

    @Test
    fun `a favorite never played is forgotten once the history has been observed for thirty days and not before`() {
        val ledger = afternoon(favourite = true)

        val early = compute(ledger, firstListen + 29 * DAY)[AutoCollectionId.FORGOTTEN_FAVORITES]!!
        assertThat(early.items).isEmpty()
        assertThat(early.emptyReason).isEqualTo(EmptyReason.TOO_LITTLE_HISTORY)
        assertThat(early.haveDays).isEqualTo(29)

        val mature = compute(ledger, firstListen + 31 * DAY)[AutoCollectionId.FORGOTTEN_FAVORITES]!!
        assertThat(mature.items.map { it.identity }).containsExactly("item-2")
        assertThat(mature.emptyReason).isNull()
    }

    // --- what the observation does not claim -------------------------------------------------------------------

    @Test
    fun `the observation starts at the first listen the history holds and never reaches back before it`() {
        // A history that begins five days ago, over a library the app has known for a year: it has been observed for five days.
        val now = at(2026, 10, 15, 12)
        val events = listOf(
            Kit.play(1, now - 5 * DAY, occurrence = "a"),
            Kit.play(1, now - 5 * DAY + HOUR, occurrence = "b"),
            Kit.play(1, now - 5 * DAY + 2 * HOUR, occurrence = "c"),
        )
        val catalog = Kit.catalog(listOf(song(1), song(2, favourite = true)), firstSeen = mapOf("item-1" to now - 400 * DAY, "item-2" to now - 400 * DAY))
        val ledger = ListeningLedger.build(events, catalog)

        val rediscover = compute(ledger, now)[AutoCollectionId.REDISCOVER]!!
        assertThat(rediscover.emptyReason).isEqualTo(EmptyReason.TOO_LITTLE_HISTORY)
        assertThat(rediscover.haveDays).isEqualTo(5)
        val forgotten = compute(ledger, now)[AutoCollectionId.FORGOTTEN_FAVORITES]!!
        assertThat(forgotten.emptyReason).isEqualTo(EmptyReason.TOO_LITTLE_HISTORY)
        assertThat(forgotten.haveDays).isEqualTo(5)
    }

    @Test
    fun `a clock behind the history observes nothing instead of a negative time`() {
        val ledger = afternoon()

        val collections = compute(ledger, firstListen - 3 * DAY)
        assertThat(collections[AutoCollectionId.REDISCOVER]!!.haveDays).isEqualTo(0)
        assertThat(collections[AutoCollectionId.REDISCOVER]!!.emptyReason).isEqualTo(EmptyReason.TOO_LITTLE_HISTORY)
    }

    @Test
    fun `a year ago says how long the history has been observed`() {
        val ledger = afternoon()

        val c = compute(ledger, firstListen + 70 * DAY)[AutoCollectionId.A_YEAR_AGO]!!

        assertThat(c.emptyReason).isEqualTo(EmptyReason.TOO_LITTLE_HISTORY)
        assertThat(c.needDays).isEqualTo(365)
        assertThat(c.haveDays).isEqualTo(70)
    }
}
