package com.lostf1sh.pixelplayeross.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.database.SongArtistCrossRef
import com.lostf1sh.pixelplayeross.data.database.SongComposerEntity
import com.lostf1sh.pixelplayeross.data.database.SongSearchRow
import com.lostf1sh.pixelplayeross.data.database.toEntity
import com.lostf1sh.pixelplayeross.data.library.folderDiscSongsInOrder
import com.lostf1sh.pixelplayeross.data.model.MusicFolder
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.SearchFilterType
import com.lostf1sh.pixelplayeross.data.model.SearchResultItem
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.presentation.utils.SearchRelevance
import com.lostf1sh.pixelplayeross.presentation.utils.primaryText
import com.lostf1sh.pixelplayeross.presentation.utils.searchResultsLayout
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LibraryTabSearch
import com.lostf1sh.pixelplayeross.utils.TextFold
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B74AccentBlindSearchTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao get() = db.musicDao()

    @After
    fun closeDb() {
        db.close()
    }

    private fun repository(playlists: List<Playlist> = emptyList()): MusicRepositoryImpl {
        val preferences = mockk<UserPreferencesRepository>(relaxed = true)
        every { preferences.allowedDirectoriesFlow } returns flowOf(emptySet())
        every { preferences.blockedDirectoriesFlow } returns flowOf(emptySet())
        every { preferences.artistDelimitersFlow } returns flowOf(listOf("/", ";", ",", "+", "&"))
        every { preferences.minTracksPerAlbumFlow } returns flowOf(1)
        val playlistRepo = mockk<PlaylistPreferencesRepository>(relaxed = true)
        every { playlistRepo.userPlaylistsFlow } returns flowOf(playlists)
        return MusicRepositoryImpl(
            context = context,
            userPreferencesRepository = preferences,
            playlistPreferencesRepository = playlistRepo,
            searchHistoryDao = db.searchHistoryDao(),
            musicDao = dao,
            lyricsRepository = mockk(relaxed = true),
            songRepository = mockk(relaxed = true),
            favoritesDao = db.favoritesDao(),
            artistImageRepository = mockk(relaxed = true),
            folderTreeBuilder = mockk(relaxed = true),
            libraryChangeSignal = mockk(relaxed = true),
        )
    }

    @Test
    fun `search ignores accents and case across songs, albums, artists, playlists, composers, Best match, tab search, and folder filter`(): Unit = runBlocking {
        val artists = listOf(
            ArtistEntity(1L, "Arcángel", 1),
            ArtistEntity(2L, "Arcangelo Valsiglio", 1),
            ArtistEntity(4L, "Beyoncé", 1),
            ArtistEntity(5L, "Håkan", 1),
            ArtistEntity(6L, "Été Ensemble", 1),
        )
        val albums = listOf(
            AlbumEntity(10L, "Canción", "Arcángel", 1L, null, 1, 0L, 2020),
            AlbumEntity(11L, "Arcangelo Album", "Arcangelo Valsiglio", 2L, null, 1, 0L, 2020),
            AlbumEntity(13L, "Renaissance", "Beyoncé", 4L, null, 1, 0L, 2022),
            AlbumEntity(14L, "Vår", "Håkan", 5L, null, 1, 0L, 2021),
            AlbumEntity(15L, "été", "Été Ensemble", 6L, null, 1, 0L, 2023),
        )
        val songs = listOf(
            Song.emptySong().copy(id = "101", title = "La Canción", artist = "Arcángel", artistId = 1L, album = "Canción", albumId = 10L, path = "/Music/101.flac")
                .toEntity("/Music/101.flac", "/Music"),
            Song.emptySong().copy(id = "102", title = "Arcangelo", artist = "Arcangelo Valsiglio", artistId = 2L, album = "Arcangelo Album", albumId = 11L, path = "/Music/102.flac")
                .toEntity("/Music/102.flac", "/Music"),
            Song.emptySong().copy(id = "104", title = "Halo", artist = "Beyoncé", artistId = 4L, album = "Renaissance", albumId = 13L, path = "/Music/104.flac")
                .toEntity("/Music/104.flac", "/Music"),
            Song.emptySong().copy(id = "105", title = "Valborg", artist = "Håkan", artistId = 5L, album = "Vår", albumId = 14L, path = "/Music/105.flac")
                .toEntity("/Music/105.flac", "/Music"),
            Song.emptySong().copy(id = "106", title = "été", artist = "Été Ensemble", artistId = 6L, album = "été", albumId = 15L, path = "/Music/106.flac")
                .toEntity("/Music/106.flac", "/Music"),
        )
        dao.insertArtistsIgnoreConflicts(artists)
        dao.insertAlbumsIgnoreConflicts(albums)
        dao.insertSongsIgnoreConflicts(songs)
        dao.insertSongArtistCrossRefs(
            listOf(
                SongArtistCrossRef(101L, 1L, isPrimary = true),
                SongArtistCrossRef(102L, 2L, isPrimary = true),
                SongArtistCrossRef(104L, 4L, isPrimary = true),
                SongArtistCrossRef(105L, 5L, isPrimary = true),
                SongArtistCrossRef(106L, 6L, isPrimary = true),
            ),
        )
        dao.upsertComposers(
            listOf(SongComposerEntity(songId = 101L, composer = "Antonín Dvořák", readAt = 0L)),
        )

        val repo = repository(
            playlists = listOf(Playlist(id = "pl-1", name = "Música", songIds = listOf("101"))),
        )

        // 1. "arcangel", "ARCANGEL", "Arcángel", "arcÁngel" all find "Arcángel" and Best match is "Arcángel", not "Arcangelo Valsiglio"
        for (q in listOf("arcangel", "ARCANGEL", "Arcángel", "arcÁngel")) {
            val all = repo.searchAll(q, SearchFilterType.ALL).first()
            val artistNames = all.filterIsInstance<SearchResultItem.ArtistItem>().map { it.artist.name }
            assertThat(artistNames).contains("Arcángel")
            val layout = searchResultsLayout(all, q, SearchFilterType.ALL)
            assertThat(layout.best.first()).isInstanceOf(SearchResultItem.ArtistItem::class.java)
            assertThat(layout.best.first().primaryText).isEqualTo("Arcángel")
            assertThat(SearchRelevance.rank("Arcángel", q)).isEqualTo(SearchRelevance.EXACT)
            assertThat(SearchRelevance.rank("Arcangelo Valsiglio", q)).isEqualTo(SearchRelevance.PREFIX)
        }

        // 2. "arcángel" finds an artist stored as "Arcangel"
        dao.insertArtistsIgnoreConflicts(listOf(ArtistEntity(3L, "Arcangel", 1)))
        dao.insertAlbumsIgnoreConflicts(listOf(AlbumEntity(12L, "Single", "Arcangel", 3L, null, 1, 0L, 2020)))
        dao.insertSongsIgnoreConflicts(
            listOf(
                Song.emptySong().copy(id = "103", title = "Stored Unaccented", artist = "Arcangel", artistId = 3L, album = "Single", albumId = 12L, path = "/Music/103.flac")
                    .toEntity("/Music/103.flac", "/Music"),
            ),
        )
        dao.insertSongArtistCrossRefs(listOf(SongArtistCrossRef(103L, 3L, isPrimary = true)))
        assertThat(repo.searchArtists("arcángel").first().map { it.name }).contains("Arcangel")

        // 3. "beyonce" finds "Beyoncé" (preserving original accent in display name)
        assertThat(repo.searchArtists("beyonce").first().map { it.name }).containsExactly("Beyoncé")

        // 4. "cancion" finds album "Canción"
        assertThat(repo.searchAlbums("cancion", 1).first().map { it.title }).containsExactly("Canción")

        // 5. Playlist "Música" is found by "musica"
        assertThat(repo.searchPlaylists("musica").map { it.name }).containsExactly("Música")
        assertThat(
            repo.searchAll("musica", SearchFilterType.PLAYLISTS).first()
                .filterIsInstance<SearchResultItem.PlaylistItem>()
                .map { it.playlist.name },
        ).containsExactly("Música")

        // 6. "hakan" finds "Håkan"
        assertThat(repo.searchArtists("hakan").first().map { it.name }).containsExactly("Håkan")

        // 7. Upper-case non-ASCII: "ÉTÉ" finds "été"
        assertThat(repo.searchSongs("ÉTÉ").first().map { it.title }).containsExactly("été")
        assertThat(repo.searchAlbums("ÉTÉ", 1).first().map { it.title }).containsExactly("été")

        // 8. Composer "Antonín Dvořák" is found by "dvorak"
        assertThat(repo.searchComposers("dvorak").first().map { it.name }).containsExactly("Antonín Dvořák")

        // 9. LibraryTabSearch and folder filter use the same fold
        assertThat(LibraryTabSearch.matchesQuery("beyonce", "Beyoncé")).isTrue()
        assertThat(LibraryTabSearch.matchesQuery("ÉTÉ", "été")).isTrue()
        val folder = MusicFolder(
            path = "/Music",
            name = "Music",
            songs = listOf(Song.emptySong().copy(id = "101", title = "Canción", path = "/Music/101.flac")).toImmutableList(),
        )
        assertThat(folderDiscSongsInOrder(folder, SortOption.FolderNameAZ, query = "cancion").map { it.title })
            .containsExactly("Canción")
    }

    @Test
    fun `timing search over 5000 songs, 1500 artists and 900 albums stays well under one frame`() {
        val repo = repository()
        val songRows = (1..5000).map { i ->
            val title = if (i == 4200) "Canción Especial" else "Track Number $i"
            val artist = if (i % 50 == 0) "Artist $i" else "Various Performers"
            SongSearchRow(i.toLong(), title, artist)
        }
        val artists = (1..1500).map { i ->
            if (i == 1200) "Arcángel" else "Artist Name $i"
        }
        val albums = (1..900).map { i ->
            val t = if (i == 750) "Canción Álbum" else "Album Title $i"
            val a = if (i == 750) "Arcángel" else "Album Artist $i"
            t to a
        }

        // Warmup JIT
        repeat(5) {
            val needle = TextFold.fold("arcangel")
            repo.rankAndSelectSongSearchIds(songRows, needle, titleOnly = false, limit = 100)
            artists.filter { needle in TextFold.fold(it) }
            albums.filter { needle in TextFold.fold(it.first) || needle in TextFold.fold(it.second) }
        }

        val samplesMs = (0 until 20).map {
            val start = System.nanoTime()
            val needleSong = TextFold.fold("cancion")
            val songIds = repo.rankAndSelectSongSearchIds(songRows, needleSong, titleOnly = false, limit = 100)
            val needleArtist = TextFold.fold("arcangel")
            val artistHits = artists.filter { needleArtist in TextFold.fold(it) }
            val albumHits = albums.filter { needleSong in TextFold.fold(it.first) || needleSong in TextFold.fold(it.second) }
            val elapsedMs = (System.nanoTime() - start) / 1e6
            assertThat(songIds).contains(4200L)
            assertThat(artistHits).containsExactly("Arcángel")
            assertThat(albumHits).hasSize(1)
            elapsedMs
        }.sorted()

        val median = samplesMs[samplesMs.size / 2]
        val p95 = samplesMs[(samplesMs.size * 0.95).toInt().coerceAtMost(samplesMs.lastIndex)]
        println("B74-SEARCH-BENCH: songs=5000 artists=1500 albums=900 medianMs=${"%.3f".format(median)} p95Ms=${"%.3f".format(p95)} maxMs=${"%.3f".format(samplesMs.last())}")
        assertThat(median).isLessThan(16.0)
    }
}
