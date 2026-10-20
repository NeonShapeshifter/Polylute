package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The capture writer must not be able to take the app down, and must not lose lines.
 *
 * ## What went wrong
 *
 * `writeSnapshot()` emitted ~90 lines by calling `write()` once per line. `write()` rewrites the
 * whole file rather than appending — that is what makes the line bound exact — so one snapshot
 * became ~90 full read-modify-write cycles of a ~220 KB file, each allocating a multi-megabyte
 * joined string. On 2026-09-24 that threw an uncaught `OutOfMemoryError` and **killed the app
 * process** in the middle of a measurement run, which is the most expensive failure a diagnostic
 * can have: it destroys the thing it was measuring and reports nothing.
 *
 * The same structure hides a second defect. `write()` was reachable from the sampler thread and
 * from the broadcast receiver with nothing serializing them, and it is a read-modify-write — so two
 * interleaved calls lose one of the two appends. An entire run's `EVENT` lines were lost that way,
 * and a missing event is indistinguishable from an event that never happened.
 *
 * ## What these tests pin
 *
 * Source assertions, like the rest of this suite: the claim is about *how many* file operations a
 * snapshot performs and *what serializes* them, which is exactly what the source says. They do not
 * exercise the writer under load — a green run here is a guard against regression, not a load test.
 */
class DirectPathCaptureBatchingBoundaryTest {

    private val source: String by lazy {
        // Located against the module root, the same way the renderer/media boundary test does it,
        // rather than against a guessed working directory.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("Could not find the app module root from ${File(".").absolutePath}")
        val file = File(root, RELATIVE_PATH)
        if (!file.isFile) error("Could not locate $RELATIVE_PATH under ${root.absolutePath}")
        file.readText()
    }

    private companion object {
        const val RELATIVE_PATH =
            "src/debug/java/com/lostf1sh/pixelplayeross/dap/admin/DirectPathCapture.kt"
    }

    /** The body of one function, bounded by the next declaration rather than by a name. */
    private fun bodyOf(signature: String, nextSignature: String): String =
        source.substringAfter(signature).substringBefore(nextSignature)

    @Test
    fun `a snapshot reaches the file as one batch, not one write per line`() {
        val snapshot = bodyOf("private fun writeSnapshot", "// ── File plumbing")

        // The whole snapshot is handed over once.
        assertThat(snapshot).contains("writeLines(context, lines)")

        // The regression this guards: per-line writing, which is what multiplied one snapshot into
        // ~90 full-file operations.
        assertThat(snapshot).doesNotContain("forEach { write(")
        assertThat(snapshot).doesNotContain("write(context,")
    }

    @Test
    fun `the read-modify-write is serialized across its two callers`() {
        val writeLines = bodyOf("private fun writeLines", "private fun write(context")

        // The sampler thread and the broadcast receiver both reach this; without the lock their
        // read-modify-writes interleave and one append is silently dropped.
        assertThat(writeLines).contains("synchronized(fileLock)")

        // Batching alone would only narrow the window, so the lock is the load-bearing part.
        assertThat(writeLines).contains("readLines()")
        assertThat(writeLines).contains("writeText(")
    }

    @Test
    fun `the event drain cursor is absolute, so a wrapped ring cannot stall it`() {
        val drain = bodyOf("private fun drainEvents", "private fun writeSnapshot")

        // The ring only holds its most recent N events. A cursor compared against the *size* of the
        // returned list saturates at that N and then reports "nothing new" forever.
        assertThat(drain).contains("DirectPathTelemetry.eventsRecorded")
        assertThat(drain).doesNotContain("events.size <= eventsWritten")
    }

    @Test
    fun `events are drained as one batch too`() {
        val drain = bodyOf("private fun drainEvents", "private fun writeSnapshot")

        assertThat(drain).contains("writeLines(context, lines)")
    }
}
