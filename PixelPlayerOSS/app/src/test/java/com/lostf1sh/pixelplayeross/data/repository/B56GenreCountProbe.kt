package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.FavoritesDao
import com.lostf1sh.pixelplayeross.data.database.GenreCountRow
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SearchHistoryDao
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * B56 phase 5.2 report: the Genres count of a synthetic library of 5,000 songs, as `MusicRepositoryImpl.getGenres()` lists it. It uses nothing the batch added, so the same file
 * runs against the tree before it ("before") and against this one ("after"); the numbers are printed (`GENRES_REPORT`). The tags are made up (no listener's data): a base list of genres
 * written the ways real tags are written (case, hyphens, `&` and "and", lists with `;` `/` `,` `|`) and a tail of genres of one or two songs.
 */
@ExperimentalCoroutinesApi
class B56GenreCountProbe {
    private val dao: MusicDao = mockk()
    private val prefs: UserPreferencesRepository = mockk()
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    companion object {
        /** The made-up library: tag value -> songs. Fixed seed, so before and after read the same tags. */
        fun syntheticTags(): Map<String, Int> {
            val random = Random(56)
            val tags = LinkedHashMap<String, Int>()
            fun add(tag: String, songs: Int) { tags.merge(tag, songs, Int::plus) }
            val families = listOf(
                listOf("Hip Hop", "Hip-Hop", "hip hop", "HIP HOP", "Hip Hop/Rap", "Rap; Hip Hop") to 700,
                listOf("R&B", "R & B", "R and B", "r&b", "R&B; Soul", "Soul") to 420,
                listOf("Electronic", "electronic", "Electronica", "Electronic; Dance", "Dance") to 380,
                listOf("Rock", "rock", "Rock, Pop", "Pop/Rock", "Rock | Alternative", "Alternative Rock", "Alternative") to 900,
                listOf("Pop", "pop", "POP", "Pop; Latin", "Latin Pop", "Latin") to 800,
                listOf("Drum & Bass", "Drum and Bass", "drum & bass", "DnB") to 120,
                listOf("Jazz", "jazz", "Jazz; Blues", "Blues") to 260,
                listOf("Classical", "classical", "Classical; Soundtrack", "Soundtrack") to 180,
                listOf("Metal", "metal", "Heavy-Metal", "Heavy Metal") to 210,
                listOf("Country", "country", "Country; Folk", "Folk") to 150,
            )
            for ((spellings, total) in families) {
                var left = total
                spellings.forEachIndexed { index, tag ->
                    val share = if (index == spellings.lastIndex) left else minOf(left, maxOf(1, (total * (0.45 / (index + 1))).toInt()))
                    add(tag, share); left -= share
                }
            }
            add("R&B/Soul", 60)                                   // a name with a separator on purpose
            add("Singer/Songwriter", 40)
            add("", 90)                                           // no genre tag
            // a tail of rare genres: one or two songs each
            for (n in 1..180) add("Rare ${random.nextInt(60)}-${n % 9}", 1 + random.nextInt(2))
            return tags
        }
    }

    @Test
    fun `the genres of the synthetic library`() = runTest(dispatcher) {
        val tags = syntheticTags()
        coEvery { prefs.allowedDirectoriesFlow } returns flowOf(emptySet())
        coEvery { prefs.blockedDirectoriesFlow } returns flowOf(setOf("/dummy"))
        coEvery { prefs.initialSetupDoneFlow } returns flowOf(true)
        coEvery { prefs.isFolderFilterActiveFlow } returns flowOf(false)
        every { dao.getAllArtistsRaw() } returns flowOf(listOf(ArtistEntity(1L, "A", 1, null)))
        coEvery { dao.getDistinctParentDirectories() } returns listOf("/music")
        every { dao.getDistinctParentDirectoriesFlow() } returns flowOf(listOf("/music"))
        every { dao.hasUnknownGenre(any(), any()) } returns flowOf(true)
        every { dao.getUniqueGenres(any<List<String>>(), any<Boolean>()) } returns flowOf(tags.keys.filter { it.isNotBlank() })
        every { dao.getGenreCounts(any(), any()) } returns flowOf(tags.map { GenreCountRow(it.key.ifEmpty { null }, it.value) })
        every { dao.observeGenreImages() } returns flowOf(emptyList())
        val repository = MusicRepositoryImpl(
            context = mockk<Context>(relaxed = true), userPreferencesRepository = prefs,
            playlistPreferencesRepository = mockk<PlaylistPreferencesRepository>(relaxed = true), searchHistoryDao = mockk<SearchHistoryDao>(relaxed = true),
            musicDao = dao, lyricsRepository = mockk(relaxed = true), songRepository = mockk(relaxed = true), favoritesDao = mockk<FavoritesDao>(relaxed = true),
            artistImageRepository = mockk(relaxed = true), folderTreeBuilder = mockk(relaxed = true),
            libraryChangeSignal = com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal(),
        )

        val genres = repository.getGenres().toList().last()

        val songs = tags.values.sum()
        println("GENRES_REPORT songs=$songs tagValues=${tags.size} genres=${genres.size} with3OrMore=${genres.count { it.songCount >= 3 }} under3=${genres.count { it.songCount < 3 }}")
        println("GENRES_REPORT biggest=" + genres.sortedByDescending { it.songCount }.take(12).joinToString(", ") { "${it.name}:${it.songCount}" })
    }
}
