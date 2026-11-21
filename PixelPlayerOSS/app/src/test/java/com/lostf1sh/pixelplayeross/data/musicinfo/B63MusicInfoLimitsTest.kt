package com.lostf1sh.pixelplayeross.data.musicinfo

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.*
import org.junit.Assert.*
import org.junit.Test

class B63MusicInfoLimitsTest {
    @Test fun `unknown length JSON stops within a bounded response`() = runBlocking {
        var consumed = 0L
        val source = object : Source {
            private var remaining = 9L * 1024 * 1024
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (remaining == 0L) return -1L
                val n = minOf(byteCount, remaining).toInt()
                sink.write(ByteArray(n) { 32 }); remaining -= n; consumed += n
                return n.toLong()
            }
            override fun close() {}
            override fun timeout() = Timeout.NONE
        }
        val body = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            private val buffered = source.buffer()
            override fun source() = buffered
        }
        val http = MusicInfoHttp(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build(), minIntervalMs = 0, nowMs = { 0 }, sleep = {})
        assertTrue(runCatching { http.json("https://example.org/json".toHttpUrl()) }.isFailure)
        assertTrue("JSON must stop before consuming the whole body", consumed <= 4L*1024*1024+8192)
    }
    @Test fun `request diagnostics have a fixed retention bound`() = runBlocking {
        val http = MusicInfoHttp(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("absent").body(ResponseBody.EMPTY).build()
        }.build(), minIntervalMs = 0, nowMs = { 0 }, sleep = {})
        repeat(1024) { http.imageExists("https://example.org/$it".toHttpUrl()) }
        assertTrue(http.sent.size <= 256)
        assertTrue(http.sent.last().endsWith("1023"))
    }
}
