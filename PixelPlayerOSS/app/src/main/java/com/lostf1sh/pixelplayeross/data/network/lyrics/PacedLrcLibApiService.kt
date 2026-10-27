package com.lostf1sh.pixelplayeross.data.network.lyrics

import android.os.SystemClock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/** App policy, not a claim about LRCLIB's published quota: one request a second across all lyrics callers. */
internal class LrcLibRequestPacer(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val wallNowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val gate = Mutex()
    private var nextAt = 0L
    private var consecutiveRateLimits = 0

    suspend fun <T> request(block: suspend () -> T): T = gate.withLock {
        val wait = nextAt - nowMs()
        if (wait > 0) sleep(wait)
        nextAt = nowMs() + 1_000L
        try {
            block().also { consecutiveRateLimits = 0 }
        } catch (error: HttpException) {
            if (error.code() == 429) {
                consecutiveRateLimits = (consecutiveRateLimits + 1).coerceAtMost(5)
                val backoff = (5_000L shl (consecutiveRateLimits - 1)).coerceAtMost(60_000L)
                val header = error.response()?.headers()?.get("Retry-After")
                val retryMs = header?.toLongOrNull()?.coerceIn(0L, Long.MAX_VALUE / 2_000L)?.times(1_000L)
                    ?: header?.let { runCatching {
                        (ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - wallNowMs()).coerceAtLeast(0L)
                    }.getOrNull() }
                nextAt = maxOf(nextAt, nowMs() + maxOf(backoff, retryMs ?: 0L))
            }
            throw error
        }
    }
}

/** The singleton DI instance wraps both manual searches and offline preparation, including every fallback strategy. */
internal class PacedLrcLibApiService(
    private val delegate: LrcLibApiService,
    private val pacer: LrcLibRequestPacer = LrcLibRequestPacer(),
) : LrcLibApiService {
    override suspend fun getLyrics(trackName: String, artistName: String, albumName: String, duration: Int): LrcLibResponse? =
        pacer.request { delegate.getLyrics(trackName, artistName, albumName, duration) }

    override suspend fun searchLyrics(query: String?, trackName: String?, artistName: String?, albumName: String?): Array<LrcLibResponse>? =
        pacer.request { delegate.searchLyrics(query, trackName, artistName, albumName) }
}
