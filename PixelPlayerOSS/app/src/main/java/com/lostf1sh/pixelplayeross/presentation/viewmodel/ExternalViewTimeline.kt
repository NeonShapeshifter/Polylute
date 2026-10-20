package com.lostf1sh.pixelplayeross.presentation.viewmodel

/**
 * FIX-1c: where the time of one external VIEW goes, in one log line.
 *
 * A VIEW into a freshly started process took about 5.8 s to play in some runs and about 0.7 s in
 * others, and nothing in the log said which step ate the difference: the song's own build, the
 * MediaController connecting (the load waits for it), the process becoming the foreground, or the
 * play itself. Every step marks itself once, in milliseconds after the request, and the line is
 * written when the song is playing (or when the attempt ends), e.g.
 *
 * `External VIEW timeline: process_age_at_request=1184ms built=212ms handed=530ms controller=655ms loaded=655ms foreground=655ms play=657ms playing=701ms`
 *
 * A step that did not happen is simply absent. Marks are first-write-wins, so a second `play()` after
 * a retry does not move the first one. Pure: the clock is an argument.
 */
internal class ExternalViewTimeline(
    /** How old the process was when the request arrived: 0-2 s means a cold start. */
    private val processAgeAtRequestMs: Long,
    private val requestedAtMs: Long,
) {
    private val marks = LinkedHashMap<String, Long>()

    @Synchronized
    fun mark(step: String, nowMs: Long) {
        if (step !in marks) marks[step] = (nowMs - requestedAtMs).coerceAtLeast(0L)
    }

    @Synchronized
    fun has(step: String): Boolean = step in marks

    @Synchronized
    fun describe(): String = buildString {
        append("process_age_at_request=").append(processAgeAtRequestMs).append("ms")
        for ((step, ms) in marks) append(' ').append(step).append('=').append(ms).append("ms")
    }

    companion object {
        const val BUILT = "built"
        const val HANDED = "handed"
        const val CONTROLLER = "controller"
        const val LOADED = "loaded"
        const val FOREGROUND = "foreground"
        const val PLAY = "play"
        const val PLAYING = "playing"
    }
}
