package com.lostf1sh.pixelplayeross.utils

import java.util.Collections

/** Disposable memory hints only. Eviction permits another lookup, never a change to stored data. */
internal fun <K, V> boundedMemoryMap(maxEntries: Int): MutableMap<K, V> {
    require(maxEntries > 0)
    return Collections.synchronizedMap(object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    })
}

internal fun <K> boundedMemorySet(maxEntries: Int): MutableSet<K> =
    Collections.newSetFromMap(boundedMemoryMap<K, Boolean>(maxEntries))
