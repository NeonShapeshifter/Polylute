package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.LyricsDao
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.network.lyrics.LrcLibApiService
import com.lostf1sh.pixelplayeross.data.network.lyrics.LrcLibResponse
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

/**
 * B41-fix2 (the outside review of B41-fix1, R2 and R3; ported from its `ReviewB41Fix1LyricsVersionsTest`, whose four cases are here under
 * their own names, and extended).
 *
 *  - R2: the search LRCLIB is asked is shared between the files that are the same song to it (same artist and title, brackets stripped), but
 *    **what is chosen from its answer is chosen per file**: by duration and version. A 180 s file and a 360 s file with the same title no longer get
 *    each other's lyrics, and "nothing fits this file" is not "nothing for the song".
 *  - R3: "LRCLIB has nothing" is remembered for the session only when **every** search that was made answered, and answered empty. A search
 *    that partly failed is a failure, asked again after the cooldown.
 *
 * It names nothing the batch added except the clock the cooldown reads (so a test does not wait thirty seconds); the same cases against the tree as
 * B41-fix1 left it are in `B41-fix2-evidence/` (the review's own file, with its real 30.1 s wait).
 */
class B41Fix2LyricsSharedSearchTest {

    private class Calls {
        val byTrackAndArtist = AtomicInteger()
        val byQuery = AtomicInteger()
        val other = AtomicInteger()
        val total: Int get() = byTrackAndArtist.get() + byQuery.get() + other.get()
    }

    private fun song(id: String, title: String = "Some Song", artist: String = "Some Artist") = Song(
        id = id, title = title, artist = artist, artistId = 5L, album = "Album", albumId = 8L, path = "", contentUriString = "",
        albumArtUriString = null, duration = 180_000L, lyrics = null, mimeType = "audio/mpeg", bitrate = 320_000, sampleRate = 44_100,
    )

    private fun found(song: Song) = LrcLibResponse(
        id = song.title.hashCode(), name = song.title, artistName = song.artist, albumName = "Album", duration = 180.0,
        plainLyrics = null, syncedLyrics = "[00:01.00]First line\n[00:05.00]Second line",
    )

    private fun repository(api: LrcLibApiService, filesDir: File = Files.createTempDirectory("b41fix2-lyrics").toFile()): LyricsRepositoryImpl =
        LyricsRepositoryImpl(
            context = mockk<Context>(relaxed = true) { every { this@mockk.filesDir } returns filesDir },
            lrcLibApiService = api,
            lyricsDao = mockk<LyricsDao>(relaxed = true),
            okHttpClient = mockk<OkHttpClient>(relaxed = true),
            userPreferencesRepository = mockk { every { this@mockk.externalLyricsEnabledFlow } returns flowOf(true) },
        )

    private fun api(calls: Calls, gate: CompletableDeferred<Unit>? = null, answer: (Song?) -> Array<LrcLibResponse>?): LrcLibApiService = mockk {
        coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
            val query = firstArg<String?>()
            val track = secondArg<String?>()
            val artist = thirdArg<String?>()
            when {
                query != null -> calls.byQuery.incrementAndGet()
                track != null && artist != null -> calls.byTrackAndArtist.incrementAndGet()
                else -> calls.other.incrementAndGet()
            }
            gate?.await()
            answer(null)
        }
    }

    @Test
    fun explicit_offline_download_survives_a_cold_restart_with_automatic_downloads_off() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("manual-offline-lyrics").toFile()
        try {
            val calls = Calls()
            val song = song("901")
            val remote = api(calls) { arrayOf(found(song)) }
            fun make() = LyricsRepositoryImpl(
                context = mockk<Context>(relaxed = true) { every { filesDir } returns dir },
                lrcLibApiService = remote, lyricsDao = mockk<LyricsDao>(relaxed = true),
                okHttpClient = mockk<OkHttpClient>(relaxed = true),
                userPreferencesRepository = mockk { every { externalLyricsEnabledFlow } returns flowOf(false) },
            )
            val downloaded = make().prepareForOffline(song)
            assertThat(downloaded?.synced?.first()?.line).isEqualTo("First line")
            val count = calls.total
            assertThat(make().getStoredLyrics(song)?.first?.synced?.first()?.line).isEqualTo("First line")
            assertThat(calls.total).isEqualTo(count)
        } finally { dir.deleteRecursively() }
    }

    /** A clock the cooldown reads, that a test moves. */
    private class Clock(var ms: Long = 1_000_000L)

    // ── R2: the answer is shared, the choice is per file ────────────────────────────────────────────

    @Test
    fun two_versions_must_rank_the_shared_search_results_for_their_own_duration() = runBlocking<Unit> {
        val calls = Calls()
        val studio = song("101").copy(duration = 180_000L)
        val live = song("102").copy(duration = 360_000L)
        val studioAnswer = found(studio).copy(id = 1, duration = 180.0, syncedLyrics = "[00:01.00]Studio version")
        val liveAnswer = found(live).copy(id = 2, duration = 360.0, syncedLyrics = "[00:01.00]Live version")
        val gate = CompletableDeferred<Unit>()
        val repo = repository(api(calls, gate) { arrayOf(studioAnswer, liveAnswer) })
        val first = async { repo.getLyrics(studio, LyricsSourcePreference.API_FIRST) }
        withTimeout(10_000) { while (calls.total < 2) delay(5) }
        val second = async { repo.getLyrics(live, LyricsSourcePreference.API_FIRST) }
        delay(300)
        gate.complete(Unit)
        assertThat(first.await()?.synced?.first()?.line).isEqualTo("Studio version")
        assertThat(second.await()?.synced?.first()?.line).isEqualTo("Live version")
        // and they shared the one pair of requests
        assertThat(calls.byTrackAndArtist.get()).isEqualTo(1)
        assertThat(calls.byQuery.get()).isEqualTo(1)
    }

    @Test
    fun no_matching_duration_for_one_version_must_not_hide_the_other_version() = runBlocking<Unit> {
        val calls = Calls()
        val studio = song("201").copy(duration = 180_000L)
        val live = song("202").copy(duration = 360_000L)
        val liveAnswer = found(live).copy(id = 2, duration = 360.0, syncedLyrics = "[00:01.00]Live version")
        val repo = repository(api(calls) { arrayOf(liveAnswer) })
        assertThat(repo.getLyrics(studio, LyricsSourcePreference.API_FIRST)).isNull()
        assertThat(repo.getLyrics(live, LyricsSourcePreference.API_FIRST)?.synced?.first()?.line).isEqualTo("Live version")
    }

    @Test
    fun positive_control_a_single_version_is_ranked_correctly() = runBlocking<Unit> {
        val calls = Calls()
        val live = song("301").copy(duration = 360_000L)
        val liveAnswer = found(live).copy(id = 2, duration = 360.0, syncedLyrics = "[00:01.00]Live version")
        val repo = repository(api(calls) { arrayOf(found(song("302")).copy(id = 1), liveAnswer) })
        assertThat(repo.getLyrics(live, LyricsSourcePreference.API_FIRST)?.synced?.first()?.line).isEqualTo("Live version")
    }

    @Test
    fun the_version_that_does_not_fit_is_not_asked_again_for_itself_but_the_song_is_not_missing() = runBlocking<Unit> {
        val calls = Calls()
        val studio = song("211").copy(duration = 180_000L)
        val live = song("212").copy(duration = 360_000L)
        val liveAnswer = found(live).copy(id = 2, duration = 360.0, syncedLyrics = "[00:01.00]Live version")
        val repo = repository(api(calls) { arrayOf(liveAnswer) })
        assertThat(repo.getLyrics(studio, LyricsSourcePreference.API_FIRST)).isNull()
        val afterFirst = calls.total
        // the 180 s file asked again (the full player, the sheet): nothing fits it, and that is remembered for it
        repeat(3) { assertThat(repo.getLyrics(studio, LyricsSourcePreference.API_FIRST)).isNull() }
        assertThat(calls.total).isEqualTo(afterFirst)
        // the 360 s file has not been refused: the song is not "missing", and it gets its lyrics
        assertThat(repo.getLyrics(live, LyricsSourcePreference.API_FIRST)?.synced?.first()?.line).isEqualTo("Live version")
        // ... and the listener's refresh asks about the 180 s one again
        assertThat(repo.getLyrics(studio, LyricsSourcePreference.API_FIRST, forceRefresh = true)).isNull()
        assertThat(calls.total).isGreaterThan(afterFirst)
    }

    @Test
    fun a_server_with_nothing_at_all_is_a_miss_for_every_version_of_the_song() = runBlocking<Unit> {
        val calls = Calls()
        val studio = song("221").copy(duration = 180_000L)
        val live = song("222").copy(duration = 360_000L)
        val repo = repository(api(calls) { emptyArray() })
        assertThat(repo.getLyrics(studio, LyricsSourcePreference.API_FIRST)).isNull()
        val afterFirst = calls.total
        assertThat(repo.getLyrics(live, LyricsSourcePreference.API_FIRST)).isNull()
        assertThat(calls.total).isEqualTo(afterFirst)
    }

    // ── R3: a search that partly failed is not "nothing" ────────────────────────────────────────────

    @Test
    fun a_failed_query_and_an_empty_strategy_are_not_a_permanent_missing_answer() = runBlocking<Unit> {
        val s = song("401")
        var broken = true
        val clock = Clock()
        val api = mockk<LrcLibApiService> {
            coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
                if (firstArg<String?>() == null) emptyArray()
                else if (broken) throw IllegalStateException("transient strategy failure")
                else arrayOf(found(s))
            }
        }
        val repo = repository(api).also { it.clockMs = { clock.ms } }
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        broken = false
        // not asked again within the cooldown ...
        clock.ms += 10_000
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        // ... and asked again after it (30.1 s: past the declared error cooldown)
        clock.ms += 20_100
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNotNull()
    }

    @Test
    fun when_every_strategy_answers_and_all_are_empty_it_is_a_session_miss() = runBlocking<Unit> {
        val calls = Calls()
        val s = song("411")
        val clock = Clock()
        val repo = repository(api(calls) { emptyArray() }).also { it.clockMs = { clock.ms } }
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        val afterFirst = calls.total
        clock.ms += 3_600_000
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        assertThat(calls.total).isEqualTo(afterFirst)
    }

    @Test
    fun when_every_strategy_fails_it_is_the_cooldown_and_then_it_is_asked_again() = runBlocking<Unit> {
        val calls = Calls()
        val s = song("421")
        val clock = Clock()
        var failing = true
        val api = mockk<LrcLibApiService> {
            coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
                calls.byTrackAndArtist.incrementAndGet()
                if (failing) throw IllegalStateException("the server is down") else arrayOf(found(s))
            }
        }
        val repo = repository(api).also { it.clockMs = { clock.ms } }
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        val afterError = calls.total
        repeat(3) { repo.getLyrics(s, LyricsSourcePreference.API_FIRST) }
        assertThat(calls.total).isEqualTo(afterError)
        failing = false
        clock.ms += 30_100
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNotNull()
    }

    @Test
    fun a_fallback_search_that_fails_after_the_others_came_back_empty_is_not_a_miss() = runBlocking<Unit> {
        // a title with a separator makes the search try once more with the part before it, without the artist
        val s = song("431", title = "Some Song - Remastered")
        val clock = Clock()
        var fallbackWorks = false
        val api = mockk<LrcLibApiService> {
            coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
                val query = firstArg<String?>()
                val artist = thirdArg<String?>()
                if (query == null && artist == null) {
                    // the one without an artist: the fallback
                    if (fallbackWorks) arrayOf(found(song("431", title = "Some Song"))) else throw IllegalStateException("the fallback failed")
                } else emptyArray()
            }
        }
        val repo = repository(api).also { it.clockMs = { clock.ms } }
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        fallbackWorks = true
        clock.ms += 30_100
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNotNull()
    }
}
