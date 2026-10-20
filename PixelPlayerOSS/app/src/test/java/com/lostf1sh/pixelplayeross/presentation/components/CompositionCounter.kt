package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Counts how many times each composable function ran (composed or recomposed), by name, with nothing added to the code under test
 * (B24-Q2). It is the Compose runtime's own tracing hook, the one `runtime-tracing` uses to name composables in a Perfetto trace:
 * the compiler puts a trace marker (`traceEventStart(key, dirty1, dirty2, "<function> (<file>:<line>)")`) at the top of every
 * non-inline composable of a debug build, and the runtime calls the installed tracer only while it says a trace is in progress.
 *
 * Use [count] around the thing being measured; [counts] is the whole table. The tracer is process-wide, so [install] in a test and
 * [uninstall] after it. [uninstall] leaves a tracer that says nothing is in progress, which is what an app without tracing has.
 */
@OptIn(InternalComposeTracingApi::class)
internal class CompositionCounter : CompositionTracer {
    private val table = ConcurrentHashMap<String, Int>()

    @Volatile
    private var armed = false

    override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        table.merge(info.substringBefore(" ("), 1, Int::plus)
    }

    override fun traceEventEnd() = Unit

    override fun isTraceInProgress(): Boolean = armed

    fun install() {
        armed = true
        Composer.setTracer(this)
    }

    fun uninstall() {
        armed = false
    }

    /** Every composable that ran, by its fully qualified name, since [install] (or since [clear]). */
    fun counts(): Map<String, Int> = table.toMap()

    fun clear() = table.clear()

    /** How many times composables whose name ends with [simpleName] ran. */
    fun count(simpleName: String): Int = table.entries.filter { it.key.substringAfterLast('.') == simpleName }.sumOf { it.value }

    /** The runs since [before] was taken with [counts], by simple name; zero entries left out. */
    fun since(before: Map<String, Int>): Map<String, Int> {
        val delta = HashMap<String, Int>()
        for ((name, now) in table) {
            val ran = now - (before[name] ?: 0)
            if (ran > 0) delta[name.substringAfterLast('.')] = (delta[name.substringAfterLast('.')] ?: 0) + ran
        }
        return delta
    }
}
