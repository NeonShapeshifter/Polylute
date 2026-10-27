package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.LyricsDao
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.network.lyrics.LrcLibApiService
import com.lostf1sh.pixelplayeross.data.network.lyrics.LrcLibResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

/** The cooldown starts when a shared search finishes, even when its only caller has already left. */
class B42Fix2LyricsCooldownTest {
    private val song = Song(
        id = "901", title = "Some Song", artist = "Some Artist", artistId = 5L, album = "Album", albumId = 8L,
        path = "", contentUriString = "", albumArtUriString = null, duration = 360_000L,
        mimeType = "audio/mpeg", bitrate = 320_000, sampleRate = 44_100,
    )

    private fun lyrics(seconds: Double) = LrcLibResponse(
        id = seconds.toInt(), name = song.title, artistName = song.artist, albumName = song.album, duration = seconds,
        plainLyrics = null, syncedLyrics = "[00:01.00]First line\n[00:05.00]Second line",
    )

    private inner class Search(val partial: Boolean = false, val useful: Boolean = false, val conclusive: Boolean = false) {
        val now = AtomicLong(1_000_000L)
        val calls = AtomicInteger()
        val repaired = AtomicBoolean(false)
        val gate = CompletableDeferred<Unit>()
        private val filesDir = Files.createTempDirectory("b42fix2-lyrics").toFile()
        private val api = mockk<LrcLibApiService> {
            coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
                calls.incrementAndGet()
                gate.await()
                when {
                    repaired.get() -> arrayOf(lyrics(360.0))
                    conclusive -> emptyArray()
                    (partial || useful) && firstArg<String?>() == null -> arrayOf(lyrics(if (useful) 360.0 else 180.0))
                    else -> throw IllegalStateException("temporarily offline")
                }
            }
        }
        val repo = LyricsRepositoryImpl(
            context = mockk<Context>(relaxed = true) { every { this@mockk.filesDir } returns this@Search.filesDir },
            lrcLibApiService = api,
            lyricsDao = mockk<LyricsDao>(relaxed = true),
            okHttpClient = mockk<OkHttpClient>(relaxed = true),
            userPreferencesRepository = mockk { every { this@mockk.externalLyricsEnabledFlow } returns flowOf(true) },
        ).also { it.clockMs = now::get }

        suspend fun leaveBeforeTheAnswer() = coroutineScope {
            val caller = launch { read() }
            withTimeout(10_000) { while (calls.get() < 2) delay(5) }
            val field = repo.javaClass.getDeclaredField("remoteSearches").apply { isAccessible = true }
            val pending = (field.get(repo) as Map<*, *>).values.single() as Deferred<*>
            caller.cancelAndJoin()
            gate.complete(Unit)
            pending.join()
        }

        suspend fun read() = repo.getLyrics(song, LyricsSourcePreference.API_FIRST)

        fun close() { filesDir.deleteRecursively() }
    }

    @Test
    fun an_orphaned_empty_failure_is_retried_on_the_first_return_after_the_cooldown() = runBlocking<Unit> {
        val search = Search()
        try {
            search.leaveBeforeTheAnswer()
            search.now.addAndGet(30_100L)
            search.repaired.set(true)
            assertThat(search.read()).isNotNull()
            assertThat(search.calls.get()).isEqualTo(4)
        } finally { search.close() }
    }

    @Test
    fun an_orphaned_partial_no_fit_is_retried_on_the_first_return_after_the_cooldown() = runBlocking<Unit> {
        val search = Search(partial = true)
        try {
            search.leaveBeforeTheAnswer()
            search.now.addAndGet(30_100L)
            search.repaired.set(true)
            assertThat(search.read()).isNotNull()
            assertThat(search.calls.get()).isEqualTo(4)
        } finally { search.close() }
    }

    @Test
    fun reading_a_failure_at_twenty_seconds_does_not_restart_its_cooldown() = runBlocking<Unit> {
        val search = Search()
        try {
            search.leaveBeforeTheAnswer()
            search.now.addAndGet(20_000L)
            search.repaired.set(true)
            repeat(3) { assertThat(search.read()).isNull() }
            assertThat(search.calls.get()).isEqualTo(2)
            search.now.addAndGet(10_100L)
            assertThat(search.read()).isNotNull()
            assertThat(search.calls.get()).isEqualTo(4)
        } finally { search.close() }
    }

    @Test
    fun an_old_incomplete_answer_that_fits_is_reused_without_another_request() = runBlocking<Unit> {
        val search = Search(useful = true)
        try {
            search.leaveBeforeTheAnswer()
            search.now.addAndGet(30_100L)
            assertThat(search.read()).isNotNull()
            assertThat(search.calls.get()).isEqualTo(2)
        } finally { search.close() }
    }

    @Test
    fun a_retry_that_still_fails_starts_one_new_cooldown_without_looping() = runBlocking<Unit> {
        val search = Search()
        try {
            search.leaveBeforeTheAnswer()
            search.now.addAndGet(30_100L)
            assertThat(search.read()).isNull()
            assertThat(search.calls.get()).isEqualTo(4)
            repeat(3) { assertThat(search.read()).isNull() }
            assertThat(search.calls.get()).isEqualTo(4)
        } finally { search.close() }
    }

    @Test
    fun a_conclusive_empty_answer_is_still_reused_after_thirty_seconds() = runBlocking<Unit> {
        val search = Search(conclusive = true)
        try {
            search.leaveBeforeTheAnswer()
            search.now.addAndGet(30_100L)
            search.repaired.set(true)
            assertThat(search.read()).isNull()
            assertThat(search.calls.get()).isEqualTo(2)
        } finally { search.close() }
    }
}
