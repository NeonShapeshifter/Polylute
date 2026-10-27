package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.LyricsDao
import com.lostf1sh.pixelplayeross.data.database.LyricsEntity
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.network.lyrics.LrcLibApiService
import com.lostf1sh.pixelplayeross.data.network.lyrics.LrcLibResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
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

/**
 * B42-fix1, item 5: the two open faults of the outside review of B41-fix2 (`B41-fix2-revision-codex.md`, R1 and R2), whose three cases are here
 * under the names the review gave them (ported from its `ReviewB41Fix2LyricsEdgesTest`), and what they need to be true.
 *
 *  - R1: nothing that fits this file, in a search where a strategy failed or was not examined, is not a permanent miss: after the cooldown it asks
 *    again. A complete search with nothing that fits stays a miss of that file for the session.
 *  - R2: an answer that finished after its only caller was cancelled (a song change) is kept by the repository, for a while and a few of them, so
 *    the listener coming back is not asked of LRCLIB again.
 */
class B42Fix1LyricsEdgesTest {

    private class Calls {
        val byTrackAndArtist = AtomicInteger()
        val byQuery = AtomicInteger()
        val other = AtomicInteger()
        val total: Int get() = byTrackAndArtist.get() + byQuery.get() + other.get()
    }

    private fun song(id: String, title: String = "Some Song", artist: String = "Some Artist", seconds: Long = 180L) = Song(
        id = id, title = title, artist = artist, artistId = 5L, album = "Album", albumId = 8L, path = "", contentUriString = "",
        albumArtUriString = null, duration = seconds * 1000L, lyrics = null, mimeType = "audio/mpeg", bitrate = 320_000, sampleRate = 44_100,
    )

    private fun found(song: Song, seconds: Double = song.duration / 1000.0, id: Int = song.title.hashCode()) = LrcLibResponse(
        id = id, name = song.title, artistName = song.artist, albumName = "Album", duration = seconds,
        plainLyrics = null, syncedLyrics = "[00:01.00]First line\n[00:05.00]Second line",
    )

    /** A DAO that keeps what is inserted and gives it back by identity, as the database does (a relaxed mock would drop the writes). */
    private fun storingDao(): LyricsDao {
        val saved = ConcurrentHashMap<Long, LyricsEntity>()
        val dao = mockk<LyricsDao>(relaxed = true)
        coEvery { dao.insert(any()) } coAnswers { val value = firstArg<LyricsEntity>(); saved[value.songId] = value; Unit }
        coEvery { dao.getLyricsResolvedByIdentity(any()) } coAnswers { saved[firstArg<Long>()] }
        return dao
    }

    private fun repository(
        api: LrcLibApiService,
        lyricsDao: LyricsDao = mockk(relaxed = true),
        now: AtomicLong? = null,
        filesDir: File = Files.createTempDirectory("b42fix1-lyrics").toFile(),
    ): LyricsRepositoryImpl =
        LyricsRepositoryImpl(
            context = mockk<Context>(relaxed = true) { every { this@mockk.filesDir } returns filesDir },
            lrcLibApiService = api,
            lyricsDao = lyricsDao,
            okHttpClient = mockk<OkHttpClient>(relaxed = true),
            userPreferencesRepository = mockk { every { this@mockk.externalLyricsEnabledFlow } returns flowOf(true) },
        ).also { repo -> if (now != null) repo.clockMs = now::get }

    /** Both strategies of a plain title answer the same, after the gate. */
    private fun gatedApi(calls: Calls, gate: CompletableDeferred<Unit>?, answer: (Int) -> Array<LrcLibResponse>): LrcLibApiService = mockk {
        coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
            val query = firstArg<String?>()
            val track = secondArg<String?>()
            val artist = thirdArg<String?>()
            val n = when {
                query != null -> calls.byQuery.incrementAndGet()
                track != null && artist != null -> calls.byTrackAndArtist.incrementAndGet()
                else -> calls.other.incrementAndGet()
            }
            gate?.await()
            answer(n)
        }
    }

    // ── R1: a miss with a failed strategy is not a permanent miss ───────────────────────────────────

    @Test
    fun failed_query_plus_results_for_the_wrong_duration_must_be_retried_after_cooldown() = runBlocking<Unit> {
        val s = song("501", seconds = 360)
        val wrong = found(s, seconds = 180.0, id = 1)
        val correct = found(s, seconds = 360.0, id = 2)
        var broken = true
        val api = mockk<LrcLibApiService> {
            coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
                if (!broken) arrayOf(correct)
                else if (firstArg<String?>() != null) throw IllegalStateException("query failed")
                else {
                    delay(100) // let the failed strategy finish before this non-matching answer
                    arrayOf(wrong)
                }
            }
        }
        val now = AtomicLong(1_000_000L)
        val repo = repository(api, now = now)
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        broken = false
        now.addAndGet(30_100L)
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNotNull()
    }

    @Test
    fun the_same_case_in_the_other_order_the_non_matching_answer_first_and_the_failure_after() = runBlocking<Unit> {
        // The review's order has the failure arrive first. Here the wrong answer arrives first, and the failed strategy would be cancelled by a
        // run that came back on it: that it did not answer is still a failure, and it is asked again.
        val s = song("502", seconds = 360)
        val wrong = found(s, seconds = 180.0, id = 1)
        val correct = found(s, seconds = 360.0, id = 2)
        var broken = true
        val api = mockk<LrcLibApiService> {
            coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
                if (!broken) arrayOf(correct)
                else if (firstArg<String?>() == null) arrayOf(wrong)
                else {
                    delay(100)
                    throw IllegalStateException("query failed")
                }
            }
        }
        val now = AtomicLong(1_000_000L)
        val repo = repository(api, now = now)
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        broken = false
        now.addAndGet(30_100L)
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNotNull()
    }

    @Test
    fun an_incomplete_search_with_no_fit_is_not_asked_again_inside_the_cooldown() = runBlocking<Unit> {
        val s = song("503", seconds = 360)
        val calls = Calls()
        val wrong = found(s, seconds = 180.0, id = 1)
        val api = mockk<LrcLibApiService> {
            coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
                calls.other.incrementAndGet()
                if (firstArg<String?>() != null) throw IllegalStateException("query failed") else arrayOf(wrong)
            }
        }
        val now = AtomicLong(1_000_000L)
        val repo = repository(api, now = now)
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        val afterFirst = calls.total
        now.addAndGet(10_000L)
        repeat(3) { assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull() }
        assertThat(calls.total).isEqualTo(afterFirst)
    }

    @Test
    fun a_later_strategy_that_fits_is_used_after_an_earlier_one_that_does_not() = runBlocking<Unit> {
        // The answer that comes first has only a 180 s version; the other strategy, a moment later, has the 360 s one this file needs.
        val s = song("511", seconds = 360)
        val wrong = found(s, seconds = 180.0, id = 1)
        val correct = found(s, seconds = 360.0, id = 2)
        val api = mockk<LrcLibApiService> {
            coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
                if (firstArg<String?>() == null) arrayOf(wrong)
                else {
                    delay(100)
                    arrayOf(correct)
                }
            }
        }
        val repo = repository(api)
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNotNull()
    }

    @Test
    fun a_complete_search_with_nothing_that_fits_is_still_a_miss_of_that_file_for_the_session() = runBlocking<Unit> {
        val s = song("521", seconds = 360)
        val calls = Calls()
        val now = AtomicLong(1_000_000L)
        val repo = repository(gatedApi(calls, null) { arrayOf(found(s, seconds = 180.0, id = 1)) }, now = now)
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        val afterFirst = calls.total
        now.addAndGet(3_600_000L)
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        assertThat(calls.total).isEqualTo(afterFirst)
    }

    // ── R2: an answer that finished with nobody waiting is kept ─────────────────────────────────────

    /** The job of the search that is running now: it outlives its caller. Observed without a sleep and without changing the repository. */
    private fun runningSearch(repo: LyricsRepositoryImpl): Deferred<*> {
        val field = repo.javaClass.getDeclaredField("remoteSearches").apply { isAccessible = true }
        val map = field.get(repo) as Map<*, *>
        return map.values.map { it as Deferred<*> }.single { !it.isCompleted }
    }

    /** The only caller asks, is cancelled (a song change) before the answer, and the answer then arrives and the job ends. */
    private suspend fun askAndCancelBeforeTheAnswer(repo: LyricsRepositoryImpl, calls: Calls, gate: CompletableDeferred<Unit>, s: Song) = coroutineScope {
        val before = calls.total
        val first = launch { repo.getLyrics(s, LyricsSourcePreference.API_FIRST) }
        withTimeout(10_000) { while (calls.total < before + 2) delay(5) }
        val pending = runningSearch(repo)
        first.cancelAndJoin()
        gate.complete(Unit)
        pending.join()
    }

    private suspend fun cancelledCallerLeavesAnAnswer(withLyrics: Boolean) {
        val calls = Calls()
        val s = song(if (withLyrics) "601" else "602")
        val gate = CompletableDeferred<Unit>()
        val repo = repository(gatedApi(calls, gate) { if (withLyrics) arrayOf(found(s)) else emptyArray() }, lyricsDao = storingDao())
        askAndCancelBeforeTheAnswer(repo, calls, gate, s)
        val initial = calls.total
        val answer = repo.getLyrics(s, LyricsSourcePreference.API_FIRST)
        if (withLyrics) assertThat(answer).isNotNull() else assertThat(answer).isNull()
        assertThat(calls.total).isEqualTo(initial)
    }

    @Test
    fun a_completed_success_after_the_only_caller_cancelled_is_not_fetched_again() = runBlocking<Unit> {
        cancelledCallerLeavesAnAnswer(withLyrics = true)
    }

    @Test
    fun a_completed_missing_answer_after_the_only_caller_cancelled_is_not_fetched_again() = runBlocking<Unit> {
        cancelledCallerLeavesAnAnswer(withLyrics = false)
    }

    @Test
    fun a_kept_answer_is_chosen_from_by_the_file_that_comes_back_not_by_the_one_that_asked() = runBlocking<Unit> {
        // The 180 s file asked and was cancelled; the 360 s version of the song comes back later and gets its own lyrics from the kept answer.
        val calls = Calls()
        val studio = song("611", seconds = 180)
        val live = song("612", seconds = 360)
        val gate = CompletableDeferred<Unit>()
        val studioAnswer = found(studio, seconds = 180.0, id = 1).copy(syncedLyrics = "[00:01.00]Studio version")
        val liveAnswer = found(live, seconds = 360.0, id = 2).copy(syncedLyrics = "[00:01.00]Live version")
        val repo = repository(gatedApi(calls, gate) { arrayOf(studioAnswer, liveAnswer) }, lyricsDao = storingDao())
        askAndCancelBeforeTheAnswer(repo, calls, gate, studio)
        val initial = calls.total
        assertThat(repo.getLyrics(live, LyricsSourcePreference.API_FIRST)?.synced?.first()?.line).isEqualTo("Live version")
        assertThat(repo.getLyrics(studio, LyricsSourcePreference.API_FIRST)?.synced?.first()?.line).isEqualTo("Studio version")
        assertThat(calls.total).isEqualTo(initial)
    }

    @Test
    fun a_kept_answer_is_forgotten_after_ten_minutes() = runBlocking<Unit> {
        val calls = Calls()
        val s = song("621")
        val gate = CompletableDeferred<Unit>()
        val now = AtomicLong(1_000_000L)
        val repo = repository(gatedApi(calls, gate) { emptyArray() }, lyricsDao = storingDao(), now = now)
        askAndCancelBeforeTheAnswer(repo, calls, gate, s)
        val initial = calls.total
        now.addAndGet(9 * 60_000L)
        // still kept: no request (and this reading is what makes it a miss for the session)
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        assertThat(calls.total).isEqualTo(initial)

        // a second song, read after the limit: asked again
        val t = song("622", title = "Another Song")
        val gate2 = CompletableDeferred<Unit>()
        val repo2 = repository(gatedApi(calls, gate2) { emptyArray() }, lyricsDao = storingDao(), now = now)
        askAndCancelBeforeTheAnswer(repo2, calls, gate2, t)
        val kept = calls.total
        now.addAndGet(10 * 60_000L + 1)
        assertThat(repo2.getLyrics(t, LyricsSourcePreference.API_FIRST)).isNull()
        assertThat(calls.total).isGreaterThan(kept)
    }

    @Test
    fun only_sixteen_answers_are_kept() = runBlocking<Unit> {
        val calls = Calls()
        val songs = (1..17).map { song("7${it.toString().padStart(2, '0')}", title = "Song number $it", artist = "Artist $it") }
        var gate = CompletableDeferred<Unit>()
        val repo = repository(
            mockk {
                coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
                    calls.other.incrementAndGet()
                    gate.await()
                    emptyArray()
                }
            },
            lyricsDao = storingDao(),
        )
        for (s in songs) {
            gate = CompletableDeferred()
            askAndCancelBeforeTheAnswer(repo, calls, gate, s)
        }
        val all = calls.total
        // the last one is still kept
        assertThat(repo.getLyrics(songs.last(), LyricsSourcePreference.API_FIRST)).isNull()
        assertThat(calls.total).isEqualTo(all)
        // the first one fell out of the bounded memory: it is asked again
        gate = CompletableDeferred<Unit>().also { it.complete(Unit) }
        assertThat(repo.getLyrics(songs.first(), LyricsSourcePreference.API_FIRST)).isNull()
        assertThat(calls.total).isGreaterThan(all)
    }

    @Test
    fun a_refresh_asks_again_even_when_an_answer_is_kept() = runBlocking<Unit> {
        val calls = Calls()
        val s = song("631")
        val gate = CompletableDeferred<Unit>()
        val repo = repository(gatedApi(calls, gate) { emptyArray() }, lyricsDao = storingDao())
        askAndCancelBeforeTheAnswer(repo, calls, gate, s)
        val kept = calls.total
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST, forceRefresh = true)).isNull()
        assertThat(calls.total).isGreaterThan(kept)
    }

    @Test
    fun an_incomplete_kept_answer_is_not_replayed_after_the_cooldown() = runBlocking<Unit> {
        // R1 and R2 together: the caller is cancelled, the answer is incomplete and fits nothing. The comeback is inside the cooldown (nothing is
        // asked); after it, the search is made again and finds the lyrics, instead of reading the kept incomplete answer once more.
        val s = song("641", seconds = 360)
        val wrong = found(s, seconds = 180.0, id = 1)
        val correct = found(s, seconds = 360.0, id = 2)
        val calls = Calls()
        val gate = CompletableDeferred<Unit>()
        var broken = true
        val api = mockk<LrcLibApiService> {
            coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
                calls.other.incrementAndGet()
                gate.await()
                if (!broken) arrayOf(correct)
                else if (firstArg<String?>() != null) throw IllegalStateException("query failed")
                else arrayOf(wrong)
            }
        }
        val now = AtomicLong(1_000_000L)
        val repo = repository(api, lyricsDao = storingDao(), now = now)
        askAndCancelBeforeTheAnswer(repo, calls, gate, s)
        val initial = calls.total
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        assertThat(calls.total).isEqualTo(initial)
        broken = false
        now.addAndGet(30_100L)
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNotNull()
        assertThat(calls.total).isGreaterThan(initial)
    }
}
