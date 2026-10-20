package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import com.lostf1sh.pixelplayeross.utils.QueueUtils
import java.io.File
import kotlin.random.Random
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * B31 P2.2 / P2.7: **what the phases of "shuffle all" cost on the host**, for a 4,870-song library (the owner's), cold (the first call, before
 * the JIT has seen the code: the nearest the JVM gets to a debug build that is never AOT-compiled) and warm (a release-like steady state).
 *
 * It is a measurement, not a check: it asserts nothing about time and writes its table to `$B31_BENCH_OUT` (default under the evidence
 * folder) so the numbers in the result can be reproduced. The phone's numbers are the host's cold ones times about 21–24 (B19's calibration)
 * for the debug build; the release build has not been measured on the phone.
 *
 * The phases are the real functions of the shuffle path: the order the draw produces ([QueueUtils.buildAnchoredShuffleQueueSuspending]), the
 * list's id strings, the play order matched by ids ([PlayOrder.fromSequence], what the player was given until B31) and handed over directly
 * as the draw's own indices (what it is given now), the immutable copy the UI state holds, and the UI state's equality on a queue that is
 * the same list again (what `MutableStateFlow.update` pays when the loaded queue is the list as shown).
 */
class ShuffleAllPhasesBench {
    private fun song(i: Int) = Song(
        id = (1_000_000L + i * 7).toString(), title = "Cancion numero $i de la biblioteca", artist = "Artista $i", artistId = i / 10L,
        album = "Album ${i / 12}", albumId = i / 12L, path = "/storage/emulated/0/Music/Artista $i/Album ${i / 12}/$i.flac",
        contentUriString = "content://media/external/audio/media/$i", albumArtUriString = "content://media/external/audio/albumart/${i / 12}",
        duration = 200_000L + i, mimeType = "audio/flac", bitrate = 1_000_000, sampleRate = 44_100,
    )

    private val library: List<Song> = (0 until 4_870).map(::song)
    private val sortedIds: List<Long> = library.map { it.id.toLong() }
    private val byId: Map<String, Song> = library.associateBy { it.id }

    private inline fun <T> timed(block: () -> T): Pair<T, Double> {
        val t0 = System.nanoTime()
        val value = block()
        return value to (System.nanoTime() - t0) / 1e6
    }

    private class Row(val phase: String, val cold: Double, val warm: Double)

    private fun phases(path: String): List<Row> {
        val rows = ArrayList<Row>()
        fun measure(phase: String, block: () -> Unit) {
            val (_, cold) = timed(block)
            repeat(WARMUP) { block() }
            val samples = DoubleArray(RUNS) { timed(block).second }.sorted()
            rows += Row(phase, cold, samples[RUNS / 2])
        }
        measure("ids → strings and lookups (resolvePlaybackQueueFromSortedIds)") {
            val ids = sortedIds.map(Long::toString)
            val out = ArrayList<Song>(ids.size)
            for (id in ids) byId[id]?.let(out::add)
        }
        if (path == "before") {
            // What the press did until B31: a random song, its index by id, the draw mapped back to a second list of songs, and that list
            // and the list's own matched by id strings to find the play order.
            measure("random start + indexOfFirst + the draw as a list of songs") {
                val start = library.random()
                val index = library.indexOfFirst { it.id == start.id }.coerceAtLeast(0)
                runBlocking { QueueUtils.buildAnchoredShuffleQueueSuspending(library, index, startAtZero = true) }
            }
            val sequence = runBlocking { QueueUtils.buildAnchoredShuffleQueueSuspending(library, 100, startAtZero = true) }
            measure("play order matched by ids (PlayOrder.fromSequence: two id lists and a map of queues)") {
                PlayOrder.fromSequence(library.map { it.id }, sequence.map { it.id })
            }
        } else {
            // What it does now: a random index, the draw as the indices it is, handed to the play order as they are.
            measure("random index + the draw as list indices (QueueUtils.dealOrder)") {
                runBlocking { QueueUtils.dealOrder(library.size, Random.nextInt(library.size), startAtZero = true) }
            }
        }
        measure("original order copy (setOriginalQueueOrder)") { library.toList() }
        measure("the immutable copy of the queue (toPersistentList; both paths)") { library.toPersistentList() }
        return rows
    }

    @Test
    fun `the phases of shuffle all on a 4,870-song library, cold and warm`() {
        // Opt-in: a measurement is only worth taking on a quiet host (B31_BENCH=1), and must not add noise to a gate run.
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("B31_BENCH") == "1")
        val path = System.getenv("B31_BENCH_PATH") ?: "after"
        val rows = phases(path)
        val table = buildString {
            appendLine("ShuffleAll phases on the host, $path B31, 4,870 songs (ms; cold = first call, warm = median of $RUNS after $WARMUP)")
            for (row in rows) appendLine("%-78s cold %8.2f   warm %8.3f".format(row.phase, row.cold, row.warm))
        }
        println(table)
        val out = File(System.getenv("B31_BENCH_OUT") ?: "../../../analysis/audit-2026-09-27/batches/B31-evidence/logs/shuffle-all-phases-host-$path.txt")
        out.parentFile?.mkdirs()
        out.writeText(table)
    }

    private companion object {
        const val WARMUP = 30
        const val RUNS = 31
    }
}
