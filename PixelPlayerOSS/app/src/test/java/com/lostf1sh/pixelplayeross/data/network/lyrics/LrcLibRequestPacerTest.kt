package com.lostf1sh.pixelplayeross.data.network.lyrics

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LrcLibRequestPacerTest {
    private fun rateLimit(retryAfter: String? = null): HttpException {
        val response = okhttp3.Response.Builder().request(Request.Builder().url("https://lrclib.net/api/search").build())
            .protocol(Protocol.HTTP_1_1).code(429).message("Too Many Requests")
        retryAfter?.let { response.header("Retry-After", it) }
        return HttpException(Response.error<Any>("".toResponseBody(), response.build()))
    }
    @Test fun `exact and search calls share one process gate and start at least half a second apart`() = runTest {
        val starts = mutableListOf<Long>()
        val raw = mockk<LrcLibApiService>()
        coEvery { raw.getLyrics(any(), any(), any(), any()) } coAnswers { starts += testScheduler.currentTime; null }
        coEvery { raw.searchLyrics(any(), any(), any(), any()) } coAnswers { starts += testScheduler.currentTime; emptyArray() }
        val service = PacedLrcLibApiService(raw, LrcLibRequestPacer({ testScheduler.currentTime }, { 0L }, { delay(it) }))
        listOf(async { service.getLyrics("one", "artist", "album", 10) },
            async { service.searchLyrics("two") }, async { service.searchLyrics("three") }).awaitAll()
        assertThat(starts).containsExactly(0L, 500L, 1000L).inOrder()
    }
    @Test fun `rate limit response delays the next caller by Retry After without a retry storm`() = runTest {
        val pacer = LrcLibRequestPacer({ testScheduler.currentTime }, { 0L }, { delay(it) })
        try { pacer.request<Unit> { throw rateLimit("12") } } catch (_: HttpException) { }
        val start = pacer.request { testScheduler.currentTime }
        assertThat(start).isEqualTo(12000L)
    }
    @Test fun `HTTP date Retry After is respected as well`() = runTest {
        val pacer = LrcLibRequestPacer({ testScheduler.currentTime }, { 0L }, { delay(it) })
        try { pacer.request<Unit> { throw rateLimit("Thu, 1 Jan 1970 00:00:20 GMT") } } catch (_: HttpException) { }
        assertThat(pacer.request { testScheduler.currentTime }).isEqualTo(20000L)
    }
    @Test fun `repeated rate limits back off even without a valid header`() = runTest {
        val pacer = LrcLibRequestPacer({ testScheduler.currentTime }, { 0L }, { delay(it) })
        val starts = mutableListOf<Long>()
        repeat(3) { try { pacer.request<Unit> { starts += testScheduler.currentTime; throw rateLimit("invalid") } } catch (_: HttpException) { } }
        assertThat(starts).containsExactly(0L, 5000L, 15000L).inOrder()
    }
    @Test fun `a cancelled waiter sends nothing and does not leave the gate locked`() = runTest {
        val pacer = LrcLibRequestPacer({ testScheduler.currentTime }, { 0L }, { delay(it) })
        pacer.request { }
        var called = false
        val cancelled = async { pacer.request { called = true } }
        testScheduler.runCurrent()
        cancelled.cancel()
        testScheduler.runCurrent()
        assertThat(called).isFalse()
        assertThat(pacer.request { testScheduler.currentTime }).isEqualTo(500L)
    }
}
