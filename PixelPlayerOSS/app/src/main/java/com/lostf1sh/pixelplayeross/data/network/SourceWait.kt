package com.lostf1sh.pixelplayeross.data.network

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SourceWaitKind { RATE_LIMIT, UNAVAILABLE }
data class SourceWait(val source: String, val kind: SourceWaitKind, val retryAt: Long)
class SourceRequestException(val wait: SourceWait, val status: Int) : IOException("HTTP $status from ${wait.source}")

object SourceWaits {
    private val state = MutableStateFlow<Map<String, SourceWait>>(emptyMap())
    val waits = state.asStateFlow()
    fun note(wait: SourceWait) { state.update { it + (wait.source to wait) } }
    fun clear(source: String) { state.update { it - source } }
    fun active(source: String, now: Long = System.currentTimeMillis()): SourceWait? = state.value[source]?.takeIf { it.retryAt > now }
}

internal fun musicInfoSource(host: String): String = when {
    host == "musicbrainz.org" -> "MusicBrainz"
    host == "lrclib.net" -> "LRCLIB"
    host.contains("coverartarchive") || host.endsWith("archive.org") -> "Cover Art Archive"
    host == "www.wikidata.org" -> "Wikidata"
    host == "commons.wikimedia.org" || host == "upload.wikimedia.org" -> "Wikimedia Commons"
    host.endsWith("wikipedia.org") -> "Wikipedia"
    else -> host
}

internal fun sourceRetryAt(header: String?, now: Long = System.currentTimeMillis(), fallbackMs: Long = 30_000): Long {
    val seconds = header?.trim()?.toLongOrNull()?.coerceIn(0, Long.MAX_VALUE / 2_000)
    return if (seconds != null) now + seconds * 1000 else header?.let {
        runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceAtLeast(now) }.getOrNull()
    } ?: (now + fallbackMs)
}

suspend fun noteSourceWait(wait: SourceWait) {
    SourceWaits.note(wait)
    currentCoroutineContext()[LookupFailures]?.let { it.failed = true; it.wait = wait }
}
