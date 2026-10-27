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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

/**
 * B41-fix1 (B8): a song load searches LRCLIB **once**. On the HONOR one load made about four identical pairs of searches within a minute
 * (`api/search?track_name=...&artist_name=...` and `api/search?q=...`), in B40 and in B41: every caller (the full player, the lyrics sheet and
 * the rest) asked `getLyrics`; a newer ask cancelled the older one in the middle of its search and started the same search again; and "not
 * found" was remembered nowhere. A pair is the two strategies that always run for a song with no brackets or "feat." in its title.
 *
 * It names nothing the batch added (the public `getLyrics`, the API's `searchLyrics`), so it compiles against the tree as it was before the
 * batch, where the first, second and fourth tests **fail** (`before-b8-lyrics.log` in `B41-fix1-evidence/`).
 */
class B41Fix1LyricsSearchOnceTest {

    /** One call of the search, by which strategy asked: `track_name` + `artist_name`, `q`, or anything else (the simplified ones). */
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

    private fun repository(api: LrcLibApiService, remoteLyricsEnabled: Boolean = true, filesDir: File = Files.createTempDirectory("b41fix1-lyrics").toFile()): LyricsRepositoryImpl =
        LyricsRepositoryImpl(
            context = mockk<Context>(relaxed = true) { every { this@mockk.filesDir } returns filesDir },
            lrcLibApiService = api,
            lyricsDao = mockk<LyricsDao>(relaxed = true),
            okHttpClient = mockk<OkHttpClient>(relaxed = true),
            userPreferencesRepository = mockk { every { this@mockk.externalLyricsEnabledFlow } returns flowOf(remoteLyricsEnabled) },
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
    fun three_callers_for_one_song_make_one_pair_of_searches() = runBlocking {
        val calls = Calls()
        val s = song("1")
        val gate = CompletableDeferred<Unit>()
        val repo = repository(api(calls, gate) { arrayOf(found(s)) })
        // the full player, the lyrics sheet and a prefetch ask at once; none of them has an answer yet
        val callers = (1..3).map { async { repo.getLyrics(s, LyricsSourcePreference.API_FIRST) } }
        withTimeout(10_000) { while (calls.total < 2) delay(5) }
        delay(300)   // the other two callers have had every chance to start their own
        gate.complete(Unit)
        val results = callers.awaitAll()
        assertThat(results.all { it != null && it.synced?.size == 2 }).isTrue()
        assertThat(calls.byTrackAndArtist.get()).isEqualTo(1)
        assertThat(calls.byQuery.get()).isEqualTo(1)
        assertThat(calls.other.get()).isEqualTo(0)
    }

    @Test
    fun not_found_is_not_asked_again_in_the_session_unless_the_listener_asks_for_a_refresh() = runBlocking {
        val calls = Calls()
        val s = song("2")
        val repo = repository(api(calls) { emptyArray() })
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        val afterFirst = calls.total
        assertThat(afterFirst).isAtLeast(2)           // the pair
        // the player asks again, and again, as it does when the lyrics sheet opens or the song is picked up again
        repeat(3) { assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull() }
        assertThat(calls.total).isEqualTo(afterFirst)
        // a refresh is the listener asking: it is asked again
        repo.getLyrics(s, LyricsSourcePreference.API_FIRST, forceRefresh = true)
        assertThat(calls.total).isGreaterThan(afterFirst)
    }

    @Test
    fun with_the_switch_off_nothing_is_asked_at_all() = runBlocking {
        val calls = Calls()
        val s = song("3")
        val repo = repository(api(calls) { arrayOf(found(s)) }, remoteLyricsEnabled = false)
        repeat(3) { assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull() }
        assertThat(calls.total).isEqualTo(0)
    }

    @Test
    fun a_caller_that_is_cancelled_neither_stops_the_search_for_the_next_nor_makes_it_start_again() = runBlocking {
        val calls = Calls()
        val s = song("4")
        val gate = CompletableDeferred<Unit>()
        val repo = repository(api(calls, gate) { arrayOf(found(s)) })
        val first = launch { repo.getLyrics(s, LyricsSourcePreference.API_FIRST) }
        withTimeout(10_000) { while (calls.total < 2) delay(5) }
        // the player moves on (a newer ask cancels the older one in the middle of its search) and asks again for the same song
        first.cancelAndJoin()
        val second = async { repo.getLyrics(s, LyricsSourcePreference.API_FIRST) }
        delay(300)
        gate.complete(Unit)
        val lyrics = second.await()
        assertThat(lyrics).isNotNull()
        assertThat(calls.byTrackAndArtist.get()).isEqualTo(1)
        assertThat(calls.byQuery.get()).isEqualTo(1)
        // and what was found is kept: asking once more is no search
        val before = calls.total
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNotNull()
        assertThat(calls.total).isEqualTo(before)
    }

    @Test
    fun a_different_song_is_a_different_search() = runBlocking {
        val calls = Calls()
        val repo = repository(api(calls) { emptyArray() })
        repo.getLyrics(song("5", title = "First Song"), LyricsSourcePreference.API_FIRST)
        val afterOne = calls.total
        repo.getLyrics(song("6", title = "Second Song"), LyricsSourcePreference.API_FIRST)
        assertThat(calls.total).isAtLeast(afterOne * 2)
    }

    @Test
    fun an_error_is_not_remembered_as_nothing_found_and_is_not_hammered() = runBlocking {
        val calls = Calls()
        val s = song("7")
        var failing = true
        val api = mockk<LrcLibApiService> {
            coEvery { searchLyrics(any(), any(), any(), any()) } coAnswers {
                calls.byTrackAndArtist.incrementAndGet()
                if (failing) throw IllegalStateException("the server is down") else arrayOf(found(s))
            }
        }
        val repo = repository(api)
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST)).isNull()
        val afterError = calls.total
        // not hammered while it keeps failing ...
        repeat(3) { repo.getLyrics(s, LyricsSourcePreference.API_FIRST) }
        assertThat(calls.total).isEqualTo(afterError)
        // ... and it is not "nothing found": the listener's refresh asks again, and the answer is the lyrics
        failing = false
        assertThat(repo.getLyrics(s, LyricsSourcePreference.API_FIRST, forceRefresh = true)).isNotNull()
    }
}
