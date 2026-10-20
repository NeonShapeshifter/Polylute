package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** B29-L11: one extraction per cover, however many ask for it at once. */
class SingleFlightTest {

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `fifty concurrent requests for eight covers run eight extractions`() = runBlocking<Unit> {
        val flight = SingleFlight<String, String>(scope())
        val runs = ConcurrentHashMap<String, AtomicInteger>()
        val results = (0 until 50).map { n ->
            val key = "cover-${n % 8}"
            async(Dispatchers.Default) {
                flight.run(key) {
                    runs.getOrPut(key) { AtomicInteger() }.incrementAndGet()
                    delay(80)
                    "palette-of-$key"
                }
            }
        }.awaitAll()
        assertThat(results.toSet()).hasSize(8)
        assertThat(runs.values.map { it.get() }).containsExactly(1, 1, 1, 1, 1, 1, 1, 1)
        assertThat(flight.inFlight).isEqualTo(0)
    }

    @Test
    fun `a caller that is cancelled stops waiting and nothing else, the others still get the palette`() = runBlocking<Unit> {
        val flight = SingleFlight<String, String>(scope())
        val runs = AtomicInteger()
        val first = launch(Dispatchers.Default) { flight.run("c") { runs.incrementAndGet(); delay(200); "palette" } }
        delay(30)
        val second = async(Dispatchers.Default) { flight.run("c") { runs.incrementAndGet(); delay(200); "second" } }
        delay(30)
        first.cancel() // a fast skip: the song that asked for it is gone
        assertThat(second.await()).isEqualTo("palette")
        first.join()
        assertThat(runs.get()).isEqualTo(1)
    }

    @Test
    fun `once the work is done the key is free, and the next request runs again`() = runBlocking<Unit> {
        val flight = SingleFlight<String, Int>(scope())
        val runs = AtomicInteger()
        val a = flight.run("c") { runs.incrementAndGet() }
        // the completion handler frees the key; give it the instant it needs
        repeat(50) { if (flight.inFlight != 0) delay(5) }
        val b = flight.run("c") { runs.incrementAndGet() }
        assertThat(listOf(a, b)).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `different covers do not wait for each other`() = runBlocking<Unit> {
        val flight = SingleFlight<String, String>(scope())
        val started = AtomicInteger()
        val jobs = (0 until 4).map { n -> launch(Dispatchers.Default) { flight.run("c$n") { started.incrementAndGet(); delay(150); "x" } } }
        delay(60)
        assertThat(started.get()).isEqualTo(4)
        jobs.joinAll()
    }
}
