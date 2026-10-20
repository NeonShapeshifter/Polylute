package com.lostf1sh.pixelplayeross.data.collections.auto

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.Kit.DAY
import com.lostf1sh.pixelplayeross.data.stats.Kit.MIN
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.data.stats.Kit.song
import com.lostf1sh.pixelplayeross.data.stats.ListenAudioFacts
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository.PlaybackEvent
import org.junit.jupiter.api.Test

/**
 * One rule per collection, each over a history made for it: a small library, a large one, no history at all, and the change of day.
 * Nothing here is a listener's data.
 */
class AutoCollectionEngineTest {
    private val now = at(2026, 10, 15, 20)

    private var seq = 0
    private fun play(id: Long, endMs: Long) = Kit.play(id, endMs, occurrence = "o${seq++}")

    private fun ledger(events: List<PlaybackEvent>, catalog: LibraryCatalog) = ListeningLedger.build(events, catalog)

    private fun all(ledger: ListeningLedger, order: List<String>? = null) = AutoCollectionEngine.compute(ledger, now, Kit.zone, order)

    private fun ids(c: AutoCollection) = c.items.map { it.identity }

    private fun lib(n: Int) = Kit.catalog((1..n).map { song(it.toLong()) }, firstSeen = (1..n).associate { "item-$it" to now - 400 * DAY })

    // --- En repetición ---------------------------------------------------------------------------------------

    @Test
    fun `on repeat lists what was played twice or more in the last fourteen days, most played first`() {
        val events = listOf(
            play(1, now - 1 * DAY), play(1, now - 2 * DAY), play(1, now - 3 * DAY),
            play(2, now - 1 * DAY), play(2, now - 5 * DAY),
            play(3, now - 2 * DAY), // once
            play(4, now - 30 * DAY), play(4, now - 31 * DAY), play(4, now - 32 * DAY), // too old
        )
        val c = all(ledger(events, lib(4)))[AutoCollectionId.ON_REPEAT]!!
        assertThat(ids(c)).containsExactly("item-1", "item-2").inOrder()
        assertThat(c.emptyReason).isNull()
        assertThat(c.days).isEqualTo(14)
    }

    @Test
    fun `on repeat is empty for the right reason`() {
        assertThat(all(ledger(emptyList(), lib(3)))[AutoCollectionId.ON_REPEAT]!!.emptyReason).isEqualTo(EmptyReason.NO_HISTORY)
        val onePlay = all(ledger(listOf(play(1, now - DAY)), lib(3)))[AutoCollectionId.ON_REPEAT]!!
        assertThat(onePlay.emptyReason).isEqualTo(EmptyReason.NOTHING_MATCHES)
    }

    // --- Redescubre ------------------------------------------------------------------------------------------

    @Test
    fun `rediscover wants several plays and a silence that is long for this history`() {
        // 60 days of history: the silence asked is a third of it, but at least three weeks.
        assertThat(AutoCollectionEngine.rediscoverSilenceDays(60)).isEqualTo(21)
        assertThat(AutoCollectionEngine.rediscoverSilenceDays(240)).isEqualTo(80)
        assertThat(AutoCollectionEngine.rediscoverSilenceDays(2000)).isEqualTo(90)
        val events = listOf(
            play(1, now - 60 * DAY), play(1, now - 55 * DAY), play(1, now - 50 * DAY), play(1, now - 48 * DAY), // loved, silent
            play(2, now - 58 * DAY), play(2, now - 40 * DAY), play(2, now - 30 * DAY), play(2, now - DAY), // loved, not silent
            play(3, now - 59 * DAY), play(3, now - 45 * DAY), // too few plays
        )
        val c = all(ledger(events, lib(3)))[AutoCollectionId.REDISCOVER]!!
        assertThat(ids(c)).containsExactly("item-1")
    }

    @Test
    fun `rediscover says the history is too short when it is`() {
        val events = listOf(play(1, now - 10 * DAY), play(1, now - 9 * DAY), play(1, now - 8 * DAY))
        val c = all(ledger(events, lib(1)))[AutoCollectionId.REDISCOVER]!!
        assertThat(c.emptyReason).isEqualTo(EmptyReason.TOO_LITTLE_HISTORY)
        assertThat(c.needDays).isEqualTo(21)
        // Ten days since the first listen: the age of the history is what it has been observed for, not the two days between its events.
        assertThat(c.haveDays).isEqualTo(10)
    }

    // --- Favoritos olvidados ---------------------------------------------------------------------------------

    @Test
    fun `forgotten favorites are favorites without a play for thirty days, never played ones first`() {
        val songs = listOf(
            song(1, favourite = true), song(2, favourite = true), song(3, favourite = true), song(4, favourite = false),
        )
        val events = listOf(
            play(1, now - 40 * DAY), // favourite, silent for 40 days
            play(3, now - 2 * DAY), // favourite, played lately
            play(4, now - 60 * DAY), // not a favourite
        )
        val c = all(ledger(events, Kit.catalog(songs)))[AutoCollectionId.FORGOTTEN_FAVORITES]!!
        // 2 was never played in a 58-day history: forgotten, and first.
        assertThat(ids(c)).containsExactly("item-2", "item-1").inOrder()
    }

    @Test
    fun `with no favorites the empty state says so, and a short history does not call a favorite forgotten`() {
        val none = all(ledger(listOf(play(1, now - DAY)), Kit.catalog(listOf(song(1)))))[AutoCollectionId.FORGOTTEN_FAVORITES]!!
        assertThat(none.emptyReason).isEqualTo(EmptyReason.NO_FAVORITES)

        val oneFavourite = Kit.catalog(listOf(song(1, favourite = true), song(2)))
        val short = all(ledger(listOf(play(2, now - 3 * DAY), play(2, now - DAY)), oneFavourite))[AutoCollectionId.FORGOTTEN_FAVORITES]!!
        assertThat(short.emptyReason).isEqualTo(EmptyReason.TOO_LITTLE_HISTORY)
    }

    // --- Caras B ---------------------------------------------------------------------------------------------

    @Test
    fun `deep cuts are the barely played tracks of the albums played most`() {
        val songs = (1..4).map { song(it.toLong(), album = "Loved", albumId = 1, track = it) } +
            (5..8).map { song(it.toLong(), album = "Quiet", albumId = 2, track = it - 4) }
        val events = (1..6).map { play(1, now - it * DAY) } + (1..6).map { play(2, now - it * 2 * DAY) } + // 12 plays of Loved
            play(4, now - 3 * DAY) + // one play: a deep cut
            listOf(play(5, now - DAY), play(6, now - DAY)) // Quiet has 2 plays: not an album played most
        val c = all(ledger(events, Kit.catalog(songs)))[AutoCollectionId.DEEP_CUTS]!!
        assertThat(ids(c)).containsExactly("item-3", "item-4").inOrder()
    }

    // --- Nuevo sin explorar ----------------------------------------------------------------------------------

    @Test
    fun `new and unexplored are recent arrivals with at most one play, unplayed first`() {
        val songs = (1..5).map { song(it.toLong()) }
        val firstSeen = mapOf(
            "item-1" to now - 5 * DAY, "item-2" to now - 3 * DAY, "item-3" to now - 2 * DAY,
            "item-4" to now - 90 * DAY, // not new
            // item-5 has no first-seen date at all
        )
        val events = listOf(play(2, now - DAY), play(3, now - DAY), play(3, now - 2 * DAY), play(4, now - 60 * DAY))
        val c = all(ledger(events, Kit.catalog(songs, firstSeen)))[AutoCollectionId.NEW_UNEXPLORED]!!
        // 1 has no plays, 2 has one, 3 has two (explored).
        assertThat(ids(c)).containsExactly("item-1", "item-2").inOrder()
    }

    // --- Madrugada -------------------------------------------------------------------------------------------

    @Test
    fun `late night lists what was played between midnight and five`() {
        val events = listOf(
            play(1, at(2026, 10, 14, 2, 10)), play(1, at(2026, 10, 13, 3, 0)),
            play(2, at(2026, 10, 14, 14, 0)),
            play(3, at(2026, 10, 12, 4, 50)),
            play(4, at(2026, 10, 12, 5, 0)), // five o'clock is out
        )
        val c = all(ledger(events, lib(4)))[AutoCollectionId.NIGHT]!!
        assertThat(ids(c)).containsExactly("item-1", "item-3").inOrder()
    }

    // --- Alta resolución -------------------------------------------------------------------------------------

    @Test
    fun `high resolution comes from the library's rate or from what was heard, and says which`() {
        val songs = listOf(song(1, sampleRate = 96_000), song(2), song(3), song(4, sampleRate = 44_100))
        val hi = ListenAudioFacts(sourceRateHz = 48_000, sourceBits = 24, sourceCodec = "audio/flac")
        val cd = ListenAudioFacts(sourceRateHz = 44_100, sourceBits = 16, sourceCodec = "audio/flac")
        val events = listOf(
            Kit.event(2, now - 10 * MIN, 3 * MIN, identity = "item-2", occurrence = "a", audio = hi),
            Kit.event(3, now - 20 * MIN, 3 * MIN, identity = "item-3", occurrence = "b", audio = cd),
        )
        val c = all(ledger(events, Kit.catalog(songs)))[AutoCollectionId.HIGH_RESOLUTION]!!
        assertThat(ids(c)).containsExactly("item-1", "item-2")
        assertThat(c.fromLibrary).isEqualTo(1)
        assertThat(c.sinceMs).isEqualTo(now - 20 * MIN)
    }

    @Test
    fun `high resolution with no data at all says there is none`() {
        val c = all(ledger(listOf(play(1, now - DAY)), lib(3)))[AutoCollectionId.HIGH_RESOLUTION]!!
        assertThat(c.emptyReason).isEqualTo(EmptyReason.NO_RESOLUTION_DATA)
    }

    // --- Nunca escuchadas ------------------------------------------------------------------------------------

    @Test
    fun `never played is the library minus anything with a play, in the library's order`() {
        val catalog = lib(5)
        val order = listOf("5", "4", "3", "2", "1")
        val c = all(ledger(listOf(play(2, now - DAY), play(4, now - DAY)), catalog), order)[AutoCollectionId.NEVER_PLAYED]!!
        assertThat(ids(c)).containsExactly("item-5", "item-3", "item-1").inOrder()
    }

    @Test
    fun `never played is empty when everything was played and says so`() {
        val c = all(ledger(listOf(play(1, now - DAY), play(2, now - DAY)), lib(2)))[AutoCollectionId.NEVER_PLAYED]!!
        assertThat(c.emptyReason).isEqualTo(EmptyReason.EVERYTHING_PLAYED)
        assertThat(all(ledger(emptyList(), LibraryCatalog.Empty))[AutoCollectionId.NEVER_PLAYED]!!.emptyReason).isEqualTo(EmptyReason.EMPTY_LIBRARY)
    }

    // --- Hace un año -----------------------------------------------------------------------------------------

    @Test
    fun `a year ago lists what was played around this date, once the history is that long`() {
        val events = listOf(
            play(1, at(2025, 10, 14, 12)), play(1, at(2025, 10, 16, 12)),
            play(2, at(2025, 10, 15, 12)),
            play(3, at(2025, 10, 25, 12)), // outside the window
            play(4, at(2026, 10, 1, 12)),
        )
        val c = all(ledger(events, lib(4)))[AutoCollectionId.A_YEAR_AGO]!!
        assertThat(ids(c)).containsExactly("item-1", "item-2").inOrder()

        val short = all(ledger(listOf(play(1, now - 30 * DAY)), lib(1)))[AutoCollectionId.A_YEAR_AGO]!!
        assertThat(short.emptyReason).isEqualTo(EmptyReason.TOO_LITTLE_HISTORY)
        assertThat(short.needDays).isEqualTo(365)
    }

    // --- Mix diario ------------------------------------------------------------------------------------------

    private fun bigLibraryHistory(): Pair<ListeningLedger, Map<AutoCollectionId, AutoCollection>> {
        val songs = (1..300).map { song(it.toLong(), artist = "Artist ${it % 12}", album = "Album ${it / 10}", albumId = (it / 10).toLong(), track = it % 10) }
        val firstSeen = (1..300).associate { "item-$it" to if (it > 250) now - 5 * DAY else now - 200 * DAY }
        val events = buildList {
            for (it in 1..120) repeat(1 + it % 5) { n -> add(play(it.toLong(), now - (3 + (it * 3 + n * 7) % 150) * DAY)) }
            for (it in 1..20) repeat(3) { n -> add(play(it.toLong(), now - (1 + n) * DAY)) }
        }
        val ledger = ledger(events, Kit.catalog(songs, firstSeen))
        return ledger to all(ledger)
    }

    @Test
    fun `the mix of the day is the same all day and another the next`() {
        val (ledger, sources) = bigLibraryHistory()
        val today = AutoCollectionRepository.localEpochDay(now, Kit.zone)
        val a = AutoCollectionEngine.dailyMix(ledger, sources, today)
        val sameDay = AutoCollectionEngine.dailyMix(ledger, sources, today)
        val tomorrow = AutoCollectionEngine.dailyMix(ledger, sources, today + 1)
        assertThat(a.items).hasSize(AutoCollectionEngine.DAILY_SIZE)
        assertThat(ids(a)).isEqualTo(ids(sameDay))
        assertThat(ids(a)).isNotEqualTo(ids(tomorrow))
        assertThat(ids(a).toSet()).hasSize(a.items.size)
    }

    @Test
    fun `the mix blends the other collections and never lists a track twice or two by one artist in a row`() {
        val (ledger, sources) = bigLibraryHistory()
        val mix = AutoCollectionEngine.dailyMix(ledger, sources, 20_000)
        val fromSources = sources.values.flatMap { ids(it) }.toSet()
        assertThat(ids(mix).count { it in fromSources }).isAtLeast(AutoCollectionEngine.DAILY_SIZE - 5)
        assertThat(mix.items.zipWithNext().count { (x, y) -> x.artists.firstOrNull() == y.artists.firstOrNull() }).isEqualTo(0)
    }

    @Test
    fun `the second list never repeats the first`() {
        val (ledger, sources) = bigLibraryHistory()
        val daily = AutoCollectionEngine.dailyMix(ledger, sources, 20_000)
        val yours = AutoCollectionEngine.dailyMix(ledger, sources, 20_000, yours = true, excluding = ids(daily).toSet())
        assertThat(yours.items.map { it.identity }.intersect(ids(daily).toSet())).isEmpty()
        assertThat(yours.items.size).isAtLeast(AutoCollectionEngine.DAILY_SIZE)
    }

    @Test
    fun `with no history the mix is made of what is new, and with nothing at all it is empty`() {
        val fresh = lib(10).let { Kit.catalog(it.items.map { i -> i.song }, firstSeen = it.items.associate { i -> i.identity to now - 2 * DAY }) }
        val newOnly = ledger(emptyList(), fresh)
        val mix = AutoCollectionEngine.dailyMix(newOnly, all(newOnly), 20_000)
        assertThat(mix.items).isNotEmpty()

        val old = lib(10)
        val nothing = ledger(emptyList(), old)
        val empty = AutoCollectionEngine.dailyMix(nothing, all(nothing), 20_000)
        assertThat(empty.items).isEmpty()
        assertThat(empty.emptyReason).isEqualTo(EmptyReason.NO_HISTORY)
    }

    @Test
    fun `a library with no tracks has no mix and says the library is empty`() {
        val none = ledger(emptyList(), LibraryCatalog.Empty)
        val mix = AutoCollectionEngine.dailyMix(none, all(none), 20_000)
        assertThat(mix.emptyReason).isEqualTo(EmptyReason.EMPTY_LIBRARY)
    }

    @Test
    fun `unattributed history never feeds a collection`() {
        // Plays of a locator the library does not hold, and of an external file.
        val events = (1..5).map { Kit.event(999, now - it * DAY, 3 * MIN, identity = null, occurrence = "u$it") }
        val sources = all(ledger(events, lib(3)))
        assertThat(sources.getValue(AutoCollectionId.ON_REPEAT).items).isEmpty()
        assertThat(sources.getValue(AutoCollectionId.NEVER_PLAYED).items).hasSize(3)
    }
}
