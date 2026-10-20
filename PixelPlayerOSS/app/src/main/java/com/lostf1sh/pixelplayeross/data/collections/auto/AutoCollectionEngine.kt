package com.lostf1sh.pixelplayeross.data.collections.auto

import com.lostf1sh.pixelplayeross.data.stats.CatalogItem
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The rules of the automatic collections (B28), one each, over the ledger.
 *
 * Every rule reads **plays as the ledger defines them** (a play is an occurrence listened to for half the track or four
 * minutes), only from history that is tied to a track, and only lists tracks that are in the library now: unattributed history
 * feeds nothing. Nothing here touches a file, a database or the clock: the ledger, the time and the zone come in, a list comes out,
 * so each rule is checked against a history made for it.
 *
 * | Collection | Rule | Order |
 * |---|---|---|
 * | En repetición | at least [ON_REPEAT_MIN_PLAYS] plays in the last [ON_REPEAT_DAYS] days | plays, most recent play |
 * | Redescubre | at least [REDISCOVER_MIN_PLAYS] plays in all, none for [rediscoverSilenceDays] days | plays, longest silence |
 * | Favoritos olvidados | a favourite with no play for [FORGOTTEN_DAYS] days (or none ever, once the history is that long) | never played first, then longest silence |
 * | Caras B | at most [DEEP_CUT_MAX_PLAYS] play, on one of the [DEEP_CUT_ALBUMS] albums with the most plays (each at least [DEEP_CUT_MIN_ALBUM_PLAYS]) | the albums' rank, then track order |
 * | Nuevo sin explorar | first seen in the last [NEW_DAYS] days, at most [NEW_MAX_PLAYS] play | unplayed first, newest first |
 * | Madrugada | played between 00:00 and 05:00 | plays at night, most recent |
 * | Alta resolución | above 48 kHz, or above 16 bit, in the library's metadata or in what was listened to | the library's order |
 * | Nunca escuchadas | no play in the history | the library's order |
 * | Hace un año | played within [YEAR_AGO_WINDOW_DAYS] days of today's date a year ago | plays |
 * | Mix diario | a blend of the first five, seeded by the local date | interleaved, no artist twice in a row |
 *
 * The history is only as old as the app's record of it: what was played before it began is not in it, and a collection
 * whose rule needs more history than there is says that ([EmptyReason.TOO_LITTLE_HISTORY]) instead of answering anyway. How much
 * there is, is the time the history has been *observed* ([ListeningLedger.observedDays]: from the first listen to now), not the time
 * between its events, so a collection that waits for three weeks of history gets them three weeks after the first listen even if
 * nobody listened in between (R28-04). It never reaches back before the first listen.
 */
object AutoCollectionEngine {

    const val ON_REPEAT_DAYS = 14
    const val ON_REPEAT_MIN_PLAYS = 2
    const val ON_REPEAT_MAX = 50

    const val REDISCOVER_MIN_PLAYS = 3
    const val REDISCOVER_MIN_SILENCE_DAYS = 21
    const val REDISCOVER_MAX_SILENCE_DAYS = 90
    const val REDISCOVER_MAX = 50

    const val FORGOTTEN_DAYS = 30

    const val DEEP_CUT_ALBUMS = 15
    const val DEEP_CUT_MIN_ALBUM_PLAYS = 6
    const val DEEP_CUT_MAX_PLAYS = 1
    const val DEEP_CUT_MAX = 60

    const val NEW_DAYS = 30
    const val NEW_MAX_PLAYS = 1
    const val NEW_MAX = 100

    /** Local hours [NIGHT_FROM_HOUR, NIGHT_TO_HOUR). */
    const val NIGHT_FROM_HOUR = 0
    const val NIGHT_TO_HOUR = 5
    const val NIGHT_MAX = 100

    const val HIGH_RES_ABOVE_HZ = 48_000
    const val HIGH_RES_ABOVE_BITS = 16

    const val YEAR_AGO_WINDOW_DAYS = 3
    const val YEAR_AGO_MAX = 100

    const val DAILY_SIZE = 30
    const val YOUR_MIX_SIZE = 60

    /** How far down a source's list the day's seed may reach for its share of the mix: three times the share. */
    private const val SEED_POOL_FACTOR = 3

    /** How long a track must have been silent to be "rediscovered": a third of the history's length, between three and thirteen weeks. */
    fun rediscoverSilenceDays(historyDays: Long): Int =
        (historyDays / 3).toInt().coerceIn(REDISCOVER_MIN_SILENCE_DAYS, REDISCOVER_MAX_SILENCE_DAYS)

    /**
     * Every collection but the daily mix, which is built from them ([dailyMix]).
     * [libraryOrder] is the locators of the library in the order the Songs list shows them; null falls back to the title.
     */
    fun compute(
        ledger: ListeningLedger,
        nowMs: Long,
        zone: ZoneId,
        libraryOrder: List<String>? = null,
    ): Map<AutoCollectionId, AutoCollection> {
        val result = LinkedHashMap<AutoCollectionId, AutoCollection>()
        result[AutoCollectionId.ON_REPEAT] = onRepeat(ledger, nowMs)
        result[AutoCollectionId.REDISCOVER] = rediscover(ledger, nowMs)
        result[AutoCollectionId.FORGOTTEN_FAVORITES] = forgottenFavorites(ledger, nowMs)
        result[AutoCollectionId.DEEP_CUTS] = deepCuts(ledger)
        result[AutoCollectionId.NEW_UNEXPLORED] = newUnexplored(ledger, nowMs)
        result[AutoCollectionId.NIGHT] = night(ledger, zone)
        result[AutoCollectionId.HIGH_RESOLUTION] = highResolution(ledger, libraryOrder)
        result[AutoCollectionId.NEVER_PLAYED] = neverPlayed(ledger, libraryOrder)
        result[AutoCollectionId.A_YEAR_AGO] = yearAgo(ledger, nowMs, zone)
        return result
    }

    // --- the rules --------------------------------------------------------------------------------------------

    private fun onRepeat(ledger: ListeningLedger, nowMs: Long): AutoCollection {
        val window = playsBetween(ledger, nowMs - days(ON_REPEAT_DAYS), nowMs + 1)
        val items = window.byItem
            .filterValues { it.plays >= ON_REPEAT_MIN_PLAYS }
            .mapNotNull { (id, w) -> ledger.catalog.item(id)?.let { it to w } }
            .sortedWith(compareByDescending<Pair<CatalogItem, Window>> { it.second.plays }
                .thenByDescending { it.second.lastMs }
                .thenBy { it.first.sortKey() })
            .take(ON_REPEAT_MAX)
            .map { it.first }
        return AutoCollection(
            AutoCollectionId.ON_REPEAT, items, days = ON_REPEAT_DAYS,
            emptyReason = if (items.isNotEmpty()) null else if (ledger.isEmpty) EmptyReason.NO_HISTORY else EmptyReason.NOTHING_MATCHES,
        )
    }

    private fun rediscover(ledger: ListeningLedger, nowMs: Long): AutoCollection {
        val observed = ledger.observedDays(nowMs)
        val silence = rediscoverSilenceDays(observed)
        if (ledger.isEmpty) return AutoCollection(AutoCollectionId.REDISCOVER, emptyList(), days = silence, emptyReason = EmptyReason.NO_HISTORY)
        if (observed < silence) {
            return AutoCollection(
                AutoCollectionId.REDISCOVER, emptyList(), days = silence,
                emptyReason = EmptyReason.TOO_LITTLE_HISTORY, needDays = silence, haveDays = observed.toInt(),
            )
        }
        val cutoff = nowMs - days(silence)
        val items = ledger.catalog.items
            .mapNotNull { item -> ledger.stats(item.identity)?.takeIf { it.plays >= REDISCOVER_MIN_PLAYS }?.let { item to it } }
            .filter { (_, stats) -> (stats.lastPlayMs ?: Long.MAX_VALUE) < cutoff }
            .sortedWith(compareByDescending<Pair<CatalogItem, com.lostf1sh.pixelplayeross.data.stats.ItemStats>> { it.second.plays }
                .thenBy { it.second.lastPlayMs ?: 0L }
                .thenBy { it.first.sortKey() })
            .take(REDISCOVER_MAX)
            .map { it.first }
        return AutoCollection(
            AutoCollectionId.REDISCOVER, items, days = silence,
            emptyReason = if (items.isNotEmpty()) null else EmptyReason.NOTHING_MATCHES,
        )
    }

    private fun forgottenFavorites(ledger: ListeningLedger, nowMs: Long): AutoCollection {
        val favourites = ledger.catalog.items.filter { it.song.isFavorite }
        if (favourites.isEmpty()) {
            return AutoCollection(AutoCollectionId.FORGOTTEN_FAVORITES, emptyList(), days = FORGOTTEN_DAYS, emptyReason = EmptyReason.NO_FAVORITES)
        }
        val observed = ledger.observedDays(nowMs)
        val cutoff = nowMs - days(FORGOTTEN_DAYS)
        // A favourite with no play in the record is "never played" only once the record is long enough to say it was not played for the whole
        // period; before that, nothing about it is known.
        val items = favourites
            .filter { item ->
                val last = ledger.stats(item.identity)?.lastPlayMs
                if (last != null) last < cutoff else observed >= FORGOTTEN_DAYS
            }
            .sortedWith(compareBy<CatalogItem> { ledger.stats(it.identity)?.lastPlayMs != null }
                .thenBy { ledger.stats(it.identity)?.lastPlayMs ?: 0L }
                .thenBy { it.sortKey() })
        val reason = when {
            items.isNotEmpty() -> null
            observed < FORGOTTEN_DAYS && favourites.any { ledger.stats(it.identity)?.lastPlayMs == null } -> EmptyReason.TOO_LITTLE_HISTORY
            else -> EmptyReason.NOTHING_MATCHES
        }
        return AutoCollection(
            AutoCollectionId.FORGOTTEN_FAVORITES, items, days = FORGOTTEN_DAYS, emptyReason = reason,
            needDays = FORGOTTEN_DAYS, haveDays = observed.toInt(),
        )
    }

    private fun deepCuts(ledger: ListeningLedger): AutoCollection {
        if (ledger.isEmpty) return AutoCollection(AutoCollectionId.DEEP_CUTS, emptyList(), emptyReason = EmptyReason.NO_HISTORY)
        val albums = ledger.catalog.tracksByAlbum.entries
            .map { (key, tracks) -> Triple(key, tracks, tracks.sumOf { ledger.stats(it.identity)?.plays ?: 0 }) }
            .filter { it.third >= DEEP_CUT_MIN_ALBUM_PLAYS }
            .sortedWith(compareByDescending<Triple<String, List<CatalogItem>, Int>> { it.third }.thenBy { it.first })
            .take(DEEP_CUT_ALBUMS)
        val items = albums
            .flatMap { (_, tracks, _) -> tracks.filter { (ledger.stats(it.identity)?.plays ?: 0) <= DEEP_CUT_MAX_PLAYS } }
            .take(DEEP_CUT_MAX)
        return AutoCollection(
            AutoCollectionId.DEEP_CUTS, items, days = DEEP_CUT_MIN_ALBUM_PLAYS,
            emptyReason = if (items.isNotEmpty()) null else EmptyReason.NOTHING_MATCHES,
        )
    }

    private fun newUnexplored(ledger: ListeningLedger, nowMs: Long): AutoCollection {
        if (ledger.catalog.isEmpty) return AutoCollection(AutoCollectionId.NEW_UNEXPLORED, emptyList(), days = NEW_DAYS, emptyReason = EmptyReason.EMPTY_LIBRARY)
        val since = nowMs - days(NEW_DAYS)
        val items = ledger.catalog.items
            .filter { it.firstSeenAtMs in since..nowMs && (ledger.stats(it.identity)?.plays ?: 0) <= NEW_MAX_PLAYS }
            .sortedWith(compareBy<CatalogItem> { ledger.stats(it.identity)?.plays ?: 0 }
                .thenByDescending { it.firstSeenAtMs }
                .thenBy { it.sortKey() })
            .take(NEW_MAX)
        return AutoCollection(
            AutoCollectionId.NEW_UNEXPLORED, items, days = NEW_DAYS,
            emptyReason = if (items.isNotEmpty()) null else EmptyReason.NOTHING_MATCHES,
        )
    }

    private fun night(ledger: ListeningLedger, zone: ZoneId): AutoCollection {
        if (ledger.isEmpty) return AutoCollection(AutoCollectionId.NIGHT, emptyList(), days = NIGHT_TO_HOUR, emptyReason = EmptyReason.NO_HISTORY)
        val rules = zone.rules
        val window = playsBetween(ledger, Long.MIN_VALUE, Long.MAX_VALUE) { o ->
            val hour = Math.floorMod(
                Math.floorDiv(o.endMs + rules.getOffset(Instant.ofEpochMilli(o.endMs)).totalSeconds * 1000L, 3_600_000L), 24L,
            ).toInt()
            hour in NIGHT_FROM_HOUR until NIGHT_TO_HOUR
        }
        val items = window.byItem
            .mapNotNull { (id, w) -> ledger.catalog.item(id)?.let { it to w } }
            .sortedWith(compareByDescending<Pair<CatalogItem, Window>> { it.second.plays }
                .thenByDescending { it.second.lastMs }
                .thenBy { it.first.sortKey() })
            .take(NIGHT_MAX)
            .map { it.first }
        return AutoCollection(
            AutoCollectionId.NIGHT, items, days = NIGHT_TO_HOUR,
            emptyReason = if (items.isNotEmpty()) null else EmptyReason.NOTHING_MATCHES,
        )
    }

    private fun highResolution(ledger: ListeningLedger, libraryOrder: List<String>?): AutoCollection {
        if (ledger.catalog.isEmpty) return AutoCollection(AutoCollectionId.HIGH_RESOLUTION, emptyList(), emptyReason = EmptyReason.EMPTY_LIBRARY)
        var fromLibrary = 0
        val matches = ledger.catalog.items.filter { item ->
            val library = (item.song.sampleRate ?: 0) > HIGH_RES_ABOVE_HZ
            val stats = ledger.stats(item.identity)
            val heard = (stats?.maxRateHz ?: 0) > HIGH_RES_ABOVE_HZ || (stats?.maxBits ?: 0) > HIGH_RES_ABOVE_BITS
            if (library) fromLibrary++
            library || heard
        }
        val items = inLibraryOrder(matches, libraryOrder)
        val libraryKnowsRates = ledger.catalog.items.any { (it.song.sampleRate ?: 0) > 0 }
        val reason = when {
            items.isNotEmpty() -> null
            !libraryKnowsRates && ledger.audioSinceMs == null -> EmptyReason.NO_RESOLUTION_DATA
            else -> EmptyReason.NOTHING_MATCHES
        }
        return AutoCollection(
            AutoCollectionId.HIGH_RESOLUTION, items, emptyReason = reason,
            sinceMs = ledger.audioSinceMs, fromLibrary = fromLibrary,
        )
    }

    private fun neverPlayed(ledger: ListeningLedger, libraryOrder: List<String>?): AutoCollection {
        if (ledger.catalog.isEmpty) return AutoCollection(AutoCollectionId.NEVER_PLAYED, emptyList(), emptyReason = EmptyReason.EMPTY_LIBRARY)
        val unplayed = ledger.catalog.items.filter { (ledger.stats(it.identity)?.plays ?: 0) == 0 }
        val items = inLibraryOrder(unplayed, libraryOrder)
        return AutoCollection(
            AutoCollectionId.NEVER_PLAYED, items,
            emptyReason = if (items.isNotEmpty()) null else EmptyReason.EVERYTHING_PLAYED,
        )
    }

    private fun yearAgo(ledger: ListeningLedger, nowMs: Long, zone: ZoneId): AutoCollection {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val target = today.minusYears(1)
        val from = target.minusDays(YEAR_AGO_WINDOW_DAYS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = target.plusDays(YEAR_AGO_WINDOW_DAYS + 1L).atStartOfDay(zone).toInstant().toEpochMilli()
        val first = ledger.firstMs
        if (first == null || first >= to) {
            val have = ledger.observedDays(nowMs).toInt()
            return AutoCollection(
                AutoCollectionId.A_YEAR_AGO, emptyList(), days = YEAR_AGO_WINDOW_DAYS,
                emptyReason = if (ledger.isEmpty) EmptyReason.NO_HISTORY else EmptyReason.TOO_LITTLE_HISTORY,
                needDays = 365, haveDays = have,
            )
        }
        val window = playsBetween(ledger, from, to)
        val items = window.byItem
            .mapNotNull { (id, w) -> ledger.catalog.item(id)?.let { it to w } }
            .sortedWith(compareByDescending<Pair<CatalogItem, Window>> { it.second.plays }.thenBy { it.first.sortKey() })
            .take(YEAR_AGO_MAX)
            .map { it.first }
        return AutoCollection(
            AutoCollectionId.A_YEAR_AGO, items, days = YEAR_AGO_WINDOW_DAYS,
            emptyReason = if (items.isNotEmpty()) null else EmptyReason.NOTHING_MATCHES,
            needDays = 365, haveDays = ledger.observedDays(nowMs).toInt(),
        )
    }

    // --- the daily mix ----------------------------------------------------------------------------------------

    /** The five collections the mix is made of, in the order they take turns, and how many tracks of [DAILY_SIZE] each is asked for. */
    private val dailyShares = listOf(
        AutoCollectionId.ON_REPEAT to 9,
        AutoCollectionId.REDISCOVER to 6,
        AutoCollectionId.NEW_UNEXPLORED to 7,
        AutoCollectionId.DEEP_CUTS to 5,
        AutoCollectionId.FORGOTTEN_FAVORITES to 3,
    )

    /** The second list Home shows ("Tu mix de hoy"): the same blend, leaning on rediscovery and the unexplored, and never repeating the first. */
    private val yoursShares = listOf(
        AutoCollectionId.ON_REPEAT to 12,
        AutoCollectionId.REDISCOVER to 14,
        AutoCollectionId.NEW_UNEXPLORED to 16,
        AutoCollectionId.DEEP_CUTS to 14,
        AutoCollectionId.FORGOTTEN_FAVORITES to 4,
    )

    /**
     * The mix of the day: a blend of En repetición, Redescubre, Nuevo sin explorar, Caras B and Favoritos olvidados, with each one's share of
     * the list taken from the top of its own list by a seed made of the local date, so it is the same all day and another the next. A source that
     * has too little gives its share to the others, and what is still missing is made up from the most played tracks. The tracks take turns by
     * source, and no artist is heard twice in a row where the list allows it.
     *
     * [excluding] are identities the list must not hold (the first mix, when this is the second). With no history at all there is nothing to
     * blend and the list is empty, but a library with new tracks is not: those are in Nuevo sin explorar.
     */
    fun dailyMix(
        ledger: ListeningLedger,
        sources: Map<AutoCollectionId, AutoCollection>,
        epochDay: Long,
        yours: Boolean = false,
        excluding: Set<String> = emptySet(),
    ): AutoCollection {
        val shares = if (yours) yoursShares else dailyShares
        val size = if (yours) YOUR_MIX_SIZE else DAILY_SIZE
        val seedBase = (if (yours) "yours" else "daily") + ":" + epochDay
        val taken = LinkedHashMap<AutoCollectionId, MutableList<CatalogItem>>()
        val chosen = HashSet<String>(excluding)
        var shortfall = 0

        for ((id, share) in shares) {
            val candidates = sources[id]?.items.orEmpty().filter { it.identity !in chosen }
            val picked = seededPick(candidates, share, "$seedBase:${id.key}")
            picked.forEach { chosen += it.identity }
            taken[id] = picked.toMutableList()
            shortfall += share - picked.size
        }
        // What a short source could not fill goes to the others that have more, in the order they take turns.
        if (shortfall > 0) {
            for ((id, _) in shares) {
                if (shortfall <= 0) break
                val more = sources[id]?.items.orEmpty().filter { it.identity !in chosen }
                val extra = seededPick(more, shortfall, "$seedBase:${id.key}:more")
                extra.forEach { chosen += it.identity }
                taken.getValue(id) += extra
                shortfall -= extra.size
            }
        }
        // Still short: the most played tracks of all, which every history has.
        if (shortfall > 0) {
            val familiar = ledger.catalog.items
                .mapNotNull { item -> ledger.stats(item.identity)?.takeIf { it.plays > 0 }?.let { item to it } }
                .filter { it.first.identity !in chosen }
                .sortedWith(compareByDescending<Pair<CatalogItem, com.lostf1sh.pixelplayeross.data.stats.ItemStats>> { it.second.plays }
                    .thenByDescending { it.second.lastPlayMs ?: 0L }
                    .thenBy { it.first.sortKey() })
                .map { it.first }
            val extra = seededPick(familiar, shortfall, "$seedBase:familiar")
            taken[shares.first().first]!! += extra
        }

        val mixed = interleave(taken.values.toList())
        val items = separateArtists(mixed).take(size)
        val reason = when {
            items.isNotEmpty() -> null
            ledger.catalog.isEmpty -> EmptyReason.EMPTY_LIBRARY
            ledger.isEmpty -> EmptyReason.NO_HISTORY
            else -> EmptyReason.NOTHING_MATCHES
        }
        return AutoCollection(AutoCollectionId.DAILY_MIX, items, emptyReason = reason)
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private class Window(var plays: Int = 0, var lastMs: Long = 0L)

    private class Plays(val byItem: Map<String, Window>)

    /** Plays that ended in [fromMs, toMs) and satisfy [accept], per item, from the ledger's occurrences (which are in order of their end). */
    private fun playsBetween(
        ledger: ListeningLedger,
        fromMs: Long,
        toMs: Long,
        accept: (com.lostf1sh.pixelplayeross.data.stats.Occurrence) -> Boolean = { true },
    ): Plays {
        val occurrences = ledger.occurrences
        var lo = 0
        var hi = occurrences.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (occurrences[mid].endMs >= fromMs) hi = mid else lo = mid + 1
        }
        val by = HashMap<String, Window>()
        for (index in lo until occurrences.size) {
            val o = occurrences[index]
            if (o.endMs >= toMs) break
            val identity = o.identity ?: continue
            if (!o.isPlay || !accept(o)) continue
            val w = by.getOrPut(identity) { Window() }
            w.plays++
            w.lastMs = maxOf(w.lastMs, o.endMs)
        }
        return Plays(by)
    }

    private fun days(n: Int): Long = TimeUnit.DAYS.toMillis(n.toLong())

    private fun CatalogItem.sortKey(): String = title.lowercase(Locale.ROOT)

    private fun inLibraryOrder(items: List<CatalogItem>, libraryOrder: List<String>?): List<CatalogItem> {
        if (libraryOrder == null) return items.sortedBy { it.sortKey() }
        val rank = HashMap<String, Int>(libraryOrder.size * 2)
        libraryOrder.forEachIndexed { index, locator -> rank.putIfAbsent(locator, index) }
        return items.sortedWith(compareBy<CatalogItem> { rank[it.locator] ?: Int.MAX_VALUE }.thenBy { it.sortKey() })
    }

    /** Takes [count] of the first `count × 3` of [candidates], in the order the seed puts them. Deterministic by specification (FNV-1a). */
    private fun seededPick(candidates: List<CatalogItem>, count: Int, seed: String): List<CatalogItem> {
        if (count <= 0 || candidates.isEmpty()) return emptyList()
        return candidates.take(count * SEED_POOL_FACTOR)
            .sortedBy { fnv("$seed|${it.identity}") }
            .take(count)
    }

    /** FNV-1a 64-bit offset basis, as a signed Long. */
    private val FNV_OFFSET = 0xcbf29ce484222325uL.toLong()

    private fun fnv(input: String): Long {
        var hash = FNV_OFFSET
        for (char in input) {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash and Long.MAX_VALUE
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>(lists.sumOf { it.size })
        var index = 0
        while (out.size < lists.sumOf { it.size }) {
            for (list in lists) if (index < list.size) out += list[index]
            index++
        }
        return out
    }

    /** Takes the tracks in order, each time the first one whose first artist is not the one just taken, or the first left when there is none. */
    private fun separateArtists(items: List<CatalogItem>): List<CatalogItem> {
        fun artist(item: CatalogItem) = item.artists.firstOrNull()?.lowercase(Locale.ROOT)
        val remaining = items.toMutableList()
        val out = ArrayList<CatalogItem>(items.size)
        var last: String? = null
        while (remaining.isNotEmpty()) {
            val index = remaining.indexOfFirst { last == null || artist(it) != last }.takeIf { it >= 0 } ?: 0
            val item = remaining.removeAt(index)
            out += item
            last = artist(item)
        }
        return out
    }
}
