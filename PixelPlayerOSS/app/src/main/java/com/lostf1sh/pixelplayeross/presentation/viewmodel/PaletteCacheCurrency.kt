package com.lostf1sh.pixelplayeross.presentation.viewmodel

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * B31 P1.7 (auditor R30-F1-03): **which generation of a cover's palette may write the cache.**
 *
 * B30-fix1 made the *holder* reject a stale regeneration, but the processor still wrote every result it computed into the cache entry
 * for the cover, in memory and in Room, whatever had happened since. Two regenerations of the same cover and settings (a cover
 * replaced under a stable URI) that finished in the wrong order left the **older** one's colours in the cache, and the next ordinary
 * request read them and published them as current.
 *
 * Per cache key (cover + style + accuracy):
 * - a **forced** generation may write only if no later forced generation (or invalidation) has begun since, so of two regenerations the
 *   later *to begin* wins, whichever ends first;
 * - an **ordinary** generation (a cache miss) may write only if no forced generation began, ended or is running since it began: while a
 *   forced one is in flight the ordinary result may be the old cover's, and one that began before a forced result was written may be older
 *   than it, so neither is put where the forced result goes;
 * - an invalidation of a cover ([invalidate]) is a new version for every key of it, so a generation that began before it cannot
 *   resurrect what was just removed.
 *
 * The write itself is made under the key's [Mutex] ([Entry.writeLock]) so the check and the write are one step.
 */
class PaletteCacheCurrency {
    class Entry internal constructor() {
        /** How many forced generations (and invalidations) of this key have begun. A forced generation writes only if no other began after it. */
        @Volatile internal var begins = 0L

        /** Moves whenever a forced generation begins or ends (and on an invalidation): an ordinary one writes only if it did not move. */
        @Volatile internal var epoch = 0L
        @Volatile internal var forcedInFlight = 0
        val writeLock = Mutex()
    }

    /** What a generation noted when it began; give it back to [mayWrite] and [end]. */
    class Ticket internal constructor(internal val key: String, internal val begins: Long, internal val epoch: Long, internal val forced: Boolean)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun entry(key: String): Entry = entries.getOrPut(key) { Entry() }

    fun begin(key: String, forced: Boolean): Ticket {
        val entry = entry(key)
        synchronized(entry) {
            if (forced) {
                entry.begins++
                entry.epoch++
                entry.forcedInFlight++
            }
            return Ticket(key, entry.begins, entry.epoch, forced)
        }
    }

    /** True while [ticket] is still the generation whose result belongs in the cache. Call with [Entry.writeLock] held, right before writing. */
    fun mayWrite(ticket: Ticket): Boolean {
        val entry = entry(ticket.key)
        synchronized(entry) {
            return if (ticket.forced) {
                entry.begins == ticket.begins
            } else {
                // No forced generation began, ended or is running since this one began: what it read cannot be older than what one wrote.
                entry.epoch == ticket.epoch && entry.forcedInFlight == 0
            }
        }
    }

    /** The generation is over, whatever it came to. */
    fun end(ticket: Ticket) {
        if (!ticket.forced) return
        val entry = entry(ticket.key)
        synchronized(entry) {
            entry.forcedInFlight--
            entry.epoch++
        }
    }

    /** Everything cached for [keyPrefix] was removed: no generation that began before this may write it back. */
    fun invalidate(keyPrefix: String) {
        entries.forEach { (key, entry) ->
            if (key.startsWith(keyPrefix)) {
                synchronized(entry) {
                    entry.begins++
                    entry.epoch++
                }
            }
        }
    }
}
