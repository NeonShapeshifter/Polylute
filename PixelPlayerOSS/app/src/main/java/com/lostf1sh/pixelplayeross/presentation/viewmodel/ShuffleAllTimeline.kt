package com.lostf1sh.pixelplayeross.presentation.viewmodel

/**
 * B31 P2.2: **where the time between a "Shuffle" press and the first timeline goes**, logged once per press.
 *
 * "Shuffle all" took 0.93–1.02 s from the press to the first `Timeline changed` on the debug build, against 0.29 s for a plain tap on a
 * row, and nobody had the phases. They are marked here in the order the path takes them; each is the time since the one before it:
 *
 * | Phase | What it ends |
 * |---|---|
 * | `ids` | the database's answer: the list's ids in the order the Library shows it |
 * | `resolve` | the ids turned into songs (the library's own cache, the database for what it lacks) |
 * | `draw` | the random start and the order the whole list plays in |
 * | `block` | the start song and its neighbours built as media items |
 * | `order` | the plan: the block anchored to the draw |
 * | `load` | the block put in the player (the call returns; the timeline is published to the session after it) |
 * | `timeline` | the player's first `onTimelineChanged` after it |
 *
 * The line (`ShuffleAll: tap→first timeline N ms (…)`) is one `Timber` `i` line from `PlayerViewModel`; the clock is injected so the format and the
 * arithmetic are tested on the JVM.
 */
class ShuffleAllTimeline(private val queueName: String, private val clock: () -> Long) {
    private val tapAt = clock()
    private val marks = LinkedHashMap<String, Long>()

    /** The phase ended now. Marked once; a second mark of the same phase is ignored. */
    fun mark(phase: String) {
        if (phase !in marks) marks[phase] = clock()
    }

    /** `ShuffleAll[queue]: tap→first timeline N ms (ids a ms, resolve b ms, …)`; a phase that never ended is left out. */
    fun line(): String {
        var previous = tapAt
        val parts = ArrayList<String>()
        for ((phase, at) in marks) {
            parts += "$phase ${at - previous} ms"
            previous = at
        }
        val total = (marks[LAST] ?: marks.values.lastOrNull() ?: tapAt) - tapAt
        val label = if (LAST in marks) "tap→first timeline" else "tap→${marks.keys.lastOrNull() ?: "nothing"} (no timeline seen)"
        return "ShuffleAll[$queueName]: $label $total ms (${parts.joinToString(", ")})"
    }

    companion object {
        const val LAST = "timeline"
    }
}
