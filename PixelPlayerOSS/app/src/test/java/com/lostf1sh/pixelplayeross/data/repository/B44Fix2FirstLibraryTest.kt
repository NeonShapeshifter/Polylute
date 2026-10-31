package com.lostf1sh.pixelplayeross.data.repository

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.paging.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.*
import com.lostf1sh.pixelplayeross.data.model.*
import com.lostf1sh.pixelplayeross.data.observer.MediaStoreObserver
import com.lostf1sh.pixelplayeross.data.preferences.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LibraryStateHolder
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B44Fix2FirstLibraryTest {
    private class Presented<T : Any> : PagingDataPresenter<T>(Dispatchers.Unconfined) {
        override suspend fun presentPagingDataEvent(event: PagingDataEvent<T>) = Unit
    }
    @Test fun `first permission folders and sync immediately populate Library pages and state`(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        Shadows.shadowOf(app).denyPermissions(permission)
        val db = Room.inMemoryDatabaseBuilder(app, PixelPlayerDatabase::class.java).build()
        val dao = db.musicDao()
        val allowed = MutableStateFlow(emptySet<String>())
        val blocked = MutableStateFlow(emptySet<String>())
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.allowedDirectoriesFlow } returns allowed
        every { prefs.blockedDirectoriesFlow } returns blocked
        every { prefs.songsSortOptionFlow } returns flowOf("SongTitleAZ")
        every { prefs.albumsSortOptionFlow } returns flowOf("AlbumTitleAZ")
        every { prefs.artistsSortOptionFlow } returns flowOf("ArtistNameAZ")
        every { prefs.foldersSortOptionFlow } returns flowOf("FolderNameAZ")
        every { prefs.likedSongsSortOptionFlow } returns flowOf("LikedSongDateLiked")
        every { prefs.lastStorageFilterFlow } returns flowOf(StorageFilter.ALL)
        every { prefs.minTracksPerAlbumFlow } returns flowOf(1)
        every { prefs.groupByAlbumArtistFlow } returns flowOf(false)
        every { prefs.isFolderFilterActiveFlow } returns flowOf(true)
        every { prefs.foldersSourceFlow } returns flowOf(FolderSource.INTERNAL)
        val songRepo = MediaStoreSongRepository(app, mockk<MediaStoreObserver>(relaxed = true), db.favoritesDao(), dao, prefs)
        val music = MusicRepositoryImpl(app, prefs, mockk(relaxed = true), db.searchHistoryDao(), dao,
            mockk(relaxed = true), songRepo, db.favoritesDao(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val holder = LibraryStateHolder(music, prefs)
        holder.initialize(scope)
        holder.startObservingLibraryData()
        val songs = Presented<Song>()
        val albums = Presented<Album>()
        scope.launch { holder.songsPagingFlow.cachedIn(scope).collectLatest { songs.collectFrom(it) } }
        scope.launch { holder.albumsPagingFlow.cachedIn(scope).collectLatest { albums.collectFrom(it) } }
        suspend fun settled(stage: String) {
            println("B44-fix2 HOST awaiting empty Paging generation sdk=${Build.VERSION.SDK_INT} stage=$stage")
            withTimeout(30_000) {
                while (songs.loadStateFlow.value?.refresh !is LoadState.NotLoading || albums.loadStateFlow.value?.refresh !is LoadState.NotLoading) {
                    (songs.loadStateFlow.value?.refresh as? LoadState.Error)?.let { throw it.error }
                    (albums.loadStateFlow.value?.refresh as? LoadState.Error)?.let { throw it.error }
                    delay(10)
                }
            }
        }
        try {
            settled("before permission")
            assertThat(songs.snapshot().items).isEmpty()
            Shadows.shadowOf(app).grantPermissions(permission)
            allowed.value = setOf("/storage/emulated/0/Music/Prueba", "/storage/0000-0000/Music")
            blocked.value = setOf("/storage")
            // Wait until the folders preference has been consumed while Room still has no rows.
            delay(200)
            settled("folders saved before sync")
            dao.insertArtistsIgnoreConflicts(listOf(ArtistEntity(1, "Artista", 6)))
            dao.insertAlbumsIgnoreConflicts(listOf(AlbumEntity(10, "Album", "Artista", 1, null, 6, 0, 2026)))
            val rows = (1..6).map { id ->
                val parent = if (id == 6) "/storage/0000-0000/Music" else "/storage/emulated/0/Music/Prueba"
                Song.emptySong().copy(id = "$id", libraryItemId = "item-$id", title = "Song $id", artist = "Artista",
                    artistId = 1, album = "Album", albumId = 10, duration = 20000, path = "$parent/$id.flac")
                    .toEntity("$parent/$id.flac", parent)
            }
            dao.insertSongsIgnoreConflicts(rows)
            // This is the existing sync-completed reset path; it must not require a process restart.
            holder.loadSongsFromRepository()
            holder.loadAlbumsFromRepository()
            withTimeoutOrNull(5000) {
                while (songs.snapshot().items.size != 6 || albums.snapshot().items.size != 1 || holder.allSongs.value.size != 6) delay(10)
            }
            println("B44-fix2 HOST first-run sdk=${Build.VERSION.SDK_INT} Room=${dao.getAllSongs(emptyList(), false).first().size} songPage=${songs.snapshot().items.size} albumPage=${albums.snapshot().items.size} libraryState=${holder.allSongs.value.size}")
            assertThat(songs.snapshot().items.map { it.id }).containsExactly("1", "2", "3", "4", "5", "6")
            assertThat(albums.snapshot().items).hasSize(1)
            assertThat(holder.allSongs.value).hasSize(6)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            val repositoryScope = music.javaClass.getDeclaredField("repositoryScope")
            repositoryScope.isAccessible = true
            (repositoryScope.get(music) as CoroutineScope).coroutineContext.job.cancelAndJoin()
            db.close()
        }
    }
}
