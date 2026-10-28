package com.lostf1sh.pixelplayeross.data.musicinfo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class B43AlbumLookupTest {
    private fun brainz(answer: (HttpUrl) -> String): MusicBrainzClient {
        val http = MusicInfoHttp(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer(chain.request().url).toResponseBody()).build()
        }.build(), minIntervalMs = 0, nowMs = { 0L }, sleep = {})
        return MusicBrainzClient(http)
    }
    @Test fun `a partial album credit searches the main artist`() = runBlocking<Unit> {
        val queries = mutableListOf<String>()
        val client = brainz { url ->
            val query = url.queryParameter("query").orEmpty(); queries += query
            if (query.contains("artist:\"A\"")) """{"release-groups":[{"id":"right","title":"Album","score":100,"artist-credit":[{"artist":{"name":"A"}}]}]}"""
            else """{"release-groups":[]}"""
        }
        assertThat(client.findReleaseGroup("Album", "A, B")?.id).isEqualTo("right")
        assertThat(queries.first()).contains("artist:\"A\"")
    }
    @Test fun `a title only retry rejects the wrong artist and accepts a credited match`() = runBlocking<Unit> {
        var correct = false
        val client = brainz { url ->
            if (url.queryParameter("query")!!.contains(" AND artist:")) """{"release-groups":[]}"""
            else """{"release-groups":[{"id":"x","title":"Album","score":100,"artist-credit":[{"artist":{"name":"${if (correct) "A" else "Other"}"}}]}]}"""
        }
        assertThat(client.findReleaseGroup("Album", "A feat. B")).isNull()
        correct = true
        assertThat(client.findReleaseGroup("Album", "A feat. B")?.id).isEqualTo("x")
    }
}
