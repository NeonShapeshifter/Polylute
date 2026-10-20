package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-108: every play/pause/transition captured the queue snapshot by walking all queued MediaItems
 * on the main thread (List(count, getMediaItemAt) plus one snapshot object per item — 5k with the
 * library as queue), although the queue had not changed. The items are now rebuilt only when the
 * player's Timeline (or the engine's full queue) is a different object, which is what a queue change
 * produces; the same list instance also lets the store skip its equality walk.
 */
class IdentityMemoTest {
    @Test
    fun `the same key objects reuse the built value`() {
        val memo = IdentityMemo<List<Int>>()
        val timeline = Any()
        var builds = 0
        val first = memo.get(timeline, false, null) { builds++; listOf(1, 2, 3) }
        val second = memo.get(timeline, false, null) { builds++; listOf(1, 2, 3) }
        assertThat(builds).isEqualTo(1)
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun `a new timeline, or an equal but different object, rebuilds`() {
        val memo = IdentityMemo<String>()
        var builds = 0
        memo.get(Any(), false) { builds++; "a" }
        memo.get(Any(), false) { builds++; "b" }
        val fullQueue = listOf("x")
        memo.get("t", true, fullQueue) { builds++; "c" }
        memo.get("t", true, listOf("x")) { builds++; "d" }  // equal content, different object
        assertThat(builds).isEqualTo(4)
    }
}
