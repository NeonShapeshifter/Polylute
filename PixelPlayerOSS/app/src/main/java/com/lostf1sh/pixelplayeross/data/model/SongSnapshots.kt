package com.lostf1sh.pixelplayeross.data.model

import java.lang.ref.WeakReference

/**
 * Shares equal, immutable Room projections between library, stats, search and queue readers.
 * Equality includes every Song field: an edit, another identity or a reduced projection is a
 * different snapshot. Weak values cannot keep a library alive; the bounded index cannot grow
 * with uptime either. This is allocation reuse, never an authority for library identity.
 */
internal object SongSnapshots {
    private const val MAX_ENTRIES = 8192
    private val entries = object : LinkedHashMap<String, WeakReference<Song>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WeakReference<Song>>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun share(song: Song): Song {
        val existing = entries[song.id]?.get()
        if (existing == song) return existing
        entries[song.id] = WeakReference(song)
        return song
    }
}
