package com.lostf1sh.pixelplayeross.data.media

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.database.SongComposerEntity
import com.lostf1sh.pixelplayeross.data.database.toEntity
import com.lostf1sh.pixelplayeross.data.model.SearchFilterType
import com.lostf1sh.pixelplayeross.data.model.SearchResultItem
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * B56 phase 6: the backfill reads the composer of every song once, search finds a composer by any part of the name (a tag with two people is two composers), a composer's page lists
 * their songs, and composers are not artists. Synthetic songs and files; the tag reader is a fake (no native library on the host).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B56ComposerSearchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = Files.createTempDirectory("b56-composers").toFile()
    private val db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java).allowMainThreadQueries().build()
    private val dao get() = db.musicDao()

    @After fun close() { db.close(); dir.deleteRecursively() }

    private val tags = mapOf(1 to "Ludwig van Beethoven", 2 to "Beethoven; Wolfgang Amadeus Mozart", 3 to "Wolfgang Amadeus Mozart", 4 to "", 5 to "Johann Sebastian Bach")

    private suspend fun library() {
        dao.insertArtistsIgnoreConflicts(listOf(ArtistEntity(1, "Orquesta", 5)))
        dao.insertAlbumsIgnoreConflicts(listOf(AlbumEntity(1, "Clásicos", "Orquesta", 1, null, 5, 0, 2020)))
        val songs = tags.keys.map { id ->
            val file = File(dir, "$id.flac").apply { writeText("not audio") }
            Song.emptySong().copy(id = "$id", title = "Obra $id", artistId = 1, albumId = 1, path = file.absolutePath)
                .toEntity(file.absolutePath, dir.absolutePath)
        }
        dao.insertSongsIgnoreConflicts(songs)
    }

    private fun backfill(reads: MutableList<String> = mutableListOf()) = ComposerBackfill(dao, read = { file ->
        reads += file.name
        tags[file.nameWithoutExtension.toInt()].let { if (it.isNullOrEmpty()) ComposerRead.None else ComposerRead.Found(it) }
    })

    private fun repository(): MusicRepositoryImpl {
        val preferences = mockk<UserPreferencesRepository>(relaxed = true)
        every { preferences.allowedDirectoriesFlow } returns flowOf(emptySet())
        every { preferences.blockedDirectoriesFlow } returns flowOf(emptySet())
        every { preferences.artistDelimitersFlow } returns flowOf(listOf(";", "/"))
        every { preferences.minTracksPerAlbumFlow } returns flowOf(1)
        val playlists = mockk<com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository>(relaxed = true)
        every { playlists.userPlaylistsFlow } returns flowOf(emptyList())
        return MusicRepositoryImpl(context, preferences, playlists, db.searchHistoryDao(), dao, mockk(relaxed = true), mockk(relaxed = true),
            db.favoritesDao(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `the backfill reads each song once and records no composer as none`(): Unit = runBlocking {
        library()
        val reads = mutableListOf<String>()

        assertThat(backfill(reads).runOnce()).isEqualTo(5)
        assertThat(backfill(reads).runOnce()).isEqualTo(0)                                  // nothing left: one query

        assertThat(reads).hasSize(5)
        assertThat(dao.countSongsNeedingComposer()).isEqualTo(0)
    }

    @Test
    fun `a batch is bounded, so the first sync after the update reads the library over several syncs`(): Unit = runBlocking {
        library()

        assertThat(backfill().runOnce(limit = 2)).isEqualTo(2)
        assertThat(dao.countSongsNeedingComposer()).isEqualTo(3)
        assertThat(backfill().runOnce(limit = 2)).isEqualTo(2)
        assertThat(backfill().runOnce(limit = 2)).isEqualTo(1)
        assertThat(dao.countSongsNeedingComposer()).isEqualTo(0)
    }

    @Test
    fun `a file that is gone is recorded as no composer and does not block the others`(): Unit = runBlocking {
        library()
        File(dir, "3.flac").delete()

        backfill().runOnce()

        assertThat(dao.countSongsNeedingComposer()).isEqualTo(0)
        assertThat(dao.searchComposerValues("Mozart", emptyList(), false).first().map { it.composer }).containsExactly("Beethoven; Wolfgang Amadeus Mozart")
    }

    @Test
    fun `searching Beethoven finds the composer in both tags, and Mozart is one composer over two tags`(): Unit = runBlocking {
        library()
        backfill().runOnce()
        val music = repository()

        val beethoven = music.searchComposers("Beethoven").first()
        val mozart = music.searchComposers("mozart").first()

        assertThat(beethoven.map { it.name }).containsExactly("Ludwig van Beethoven", "Beethoven")
        assertThat(mozart.single().name).isEqualTo("Wolfgang Amadeus Mozart")
        assertThat(mozart.single().songCount).isEqualTo(2)
    }

    @Test
    fun `the Composers group is in the all-results search and the filter shows only composers`(): Unit = runBlocking {
        library()
        backfill().runOnce()
        val music = repository()

        val all = music.searchAll("bach", SearchFilterType.ALL).first()
        val only = music.searchAll("bach", SearchFilterType.COMPOSERS).first()

        assertThat(all.filterIsInstance<SearchResultItem.ComposerItem>().map { it.name }).containsExactly("Johann Sebastian Bach")
        assertThat(only).containsExactly(SearchResultItem.ComposerItem("Johann Sebastian Bach", 1))
    }

    @Test
    fun `a composer's page lists their songs in title order and a tag with two people serves both`(): Unit = runBlocking {
        library()
        backfill().runOnce()
        val music = repository()

        val mozart = music.getSongsByComposer("Wolfgang Amadeus Mozart").first()
        val beethoven = music.getSongsByComposer("Beethoven").first()

        assertThat(mozart.map { it.title }).containsExactly("Obra 2", "Obra 3").inOrder()
        assertThat(beethoven.map { it.title }).containsExactly("Obra 2")
        assertThat(music.getSongsByComposer("Nobody").first()).isEmpty()
    }

    @Test
    fun `composers do not become artists`(): Unit = runBlocking {
        library()
        backfill().runOnce()

        repository().searchComposers("Beethoven").first()

        assertThat(dao.getAllArtistsRaw().first().map { it.name }).containsExactly("Orquesta")
    }

    @Test
    fun `a song the scan reads again is read again`(): Unit = runBlocking {
        library()
        backfill().runOnce()

        dao.forgetComposers(listOf(3))

        assertThat(dao.countSongsNeedingComposer()).isEqualTo(1)
        assertThat(dao.songsNeedingComposer(10).single().songId).isEqualTo(3)
    }
}
