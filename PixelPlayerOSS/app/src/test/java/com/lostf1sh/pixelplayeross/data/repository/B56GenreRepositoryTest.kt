package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.FavoritesDao
import com.lostf1sh.pixelplayeross.data.database.GenreCountRow
import com.lostf1sh.pixelplayeross.data.database.GenreImageEntity
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SearchHistoryDao
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** B56 phase 5.2: the repository lists the genres as the library reads them, and a stored image follows a merged genre. */
@ExperimentalCoroutinesApi
class B56GenreRepositoryTest {
    private val dao: MusicDao = mockk()
    private val prefs: UserPreferencesRepository = mockk()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: MusicRepositoryImpl

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { prefs.allowedDirectoriesFlow } returns flowOf(emptySet())
        coEvery { prefs.blockedDirectoriesFlow } returns flowOf(setOf("/dummy"))
        coEvery { prefs.initialSetupDoneFlow } returns flowOf(true)
        coEvery { prefs.isFolderFilterActiveFlow } returns flowOf(false)
        every { prefs.mockGenresEnabledFlow } returns flowOf(false)
        every { dao.getAllArtistsRaw() } returns flowOf(listOf(ArtistEntity(101L, "A", 1, null)))
        coEvery { dao.getDistinctParentDirectories() } returns listOf("/music")
        every { dao.getDistinctParentDirectoriesFlow() } returns flowOf(listOf("/music"))
        every { dao.hasUnknownGenre(any(), any()) } returns flowOf(false)
        repository = MusicRepositoryImpl(
            context = mockk<Context>(relaxed = true), userPreferencesRepository = prefs,
            playlistPreferencesRepository = mockk<PlaylistPreferencesRepository>(relaxed = true), searchHistoryDao = mockk<SearchHistoryDao>(relaxed = true),
            musicDao = dao, lyricsRepository = mockk(relaxed = true), songRepository = mockk(relaxed = true), favoritesDao = mockk<FavoritesDao>(relaxed = true),
            artistImageRepository = mockk(relaxed = true), folderTreeBuilder = mockk(relaxed = true),
            libraryChangeSignal = com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal(),
        )
    }

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    private fun library(vararg tagValues: Pair<String, Int>, images: List<GenreImageEntity> = emptyList()) {
        every { dao.getUniqueGenres(any<List<String>>(), any<Boolean>()) } returns flowOf(tagValues.map { it.first })
        every { dao.getGenreCounts(any(), any()) } returns flowOf(tagValues.map { GenreCountRow(it.first, it.second) })
        every { dao.observeGenreImages() } returns flowOf(images)
    }

    /** The listing once the counts have arrived: the flow starts with an empty one, as the app's does. */
    private suspend fun lastListing() = repository.getGenres().toList().last()

    @Test
    fun `spellings and lists become the merged genres with their counts`() = runTest(dispatcher) {
        library("Hip Hop" to 10, "Hip-Hop" to 3, "hip hop" to 2, "Pop; Latin" to 5, "Pop" to 4, "Latin" to 1, "Rock, Pop" to 2)

        val genres = lastListing()

        assertEquals(listOf("Hip Hop", "Latin", "Pop", "Rock"), genres.map { it.name })
        assertEquals(15, genres.single { it.name == "Hip Hop" }.songCount)
        assertEquals(6, genres.single { it.name == "Latin" }.songCount)                 // 5 + 1
        assertEquals(11, genres.single { it.name == "Pop" }.songCount)                  // 5 + 4 + 2
        assertEquals(2, genres.single { it.name == "Rock" }.songCount)
        assertEquals("hip_hop", genres.first().id)
    }

    @Test
    fun `an image chosen on an older spelling follows the merged genre`() = runTest(dispatcher) {
        coEvery { dao.moveGenreImage(any(), any()) } returns Unit
        library("Hip Hop" to 10, "Hip-Hop" to 3, images = listOf(GenreImageEntity("hip-hop", "/files/art.jpg", 1L)))

        val genre = lastListing().single()

        assertEquals("/files/art.jpg", genre.imagePath)
        // once per listing that still sees the image under the old key (the real database has moved it after the first)
        coVerify(atLeast = 1) { dao.moveGenreImage("hip-hop", "hip_hop") }
    }

    @Test
    fun `a genre that already has its own image keeps it and nothing moves`() = runTest(dispatcher) {
        library("Hip Hop" to 10, "Hip-Hop" to 3, images = listOf(GenreImageEntity("hip_hop", "/files/own.jpg", 2L), GenreImageEntity("hip-hop", "/files/old.jpg", 1L)))

        val genre = lastListing().single()

        assertEquals("/files/own.jpg", genre.imagePath)
        coVerify(exactly = 0) { dao.moveGenreImage(any(), any()) }
    }

    @Test
    fun `the songs of a merged genre are those of every tag value that names it`() = runTest(dispatcher) {
        every { prefs.mockGenresEnabledFlow } returns flowOf(false)
        every { dao.getGenreCounts(any(), any()) } returns flowOf(
            listOf("Hip Hop", "Hip-Hop", "Pop; Latin", "Rock").map { GenreCountRow(it, 1) },
        )
        val asked = mutableListOf<List<String>>()
        every { dao.getSongsByGenreValues(any(), any(), any()) } answers { asked += firstArg<List<String>>(); flowOf(emptyList()) }

        repository.getMusicByGenre("hip-hop").first()
        repository.getMusicByGenre("Latin").first()

        assertEquals(listOf(listOf("Hip Hop", "Hip-Hop"), listOf("Pop; Latin")), asked)
        assertFalse(asked.any { "Rock" in it })
        assertTrue(asked.isNotEmpty())
    }
}
