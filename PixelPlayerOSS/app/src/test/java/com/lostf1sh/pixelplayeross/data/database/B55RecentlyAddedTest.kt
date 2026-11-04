package com.lostf1sh.pixelplayeross.data.database

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LibraryStateHolder
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.presentation.model.methodLabelRes
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class B55RecentlyAddedTest {
    @Test fun `recently added sorts Songs Albums and both artist groupings by newest member track`():Unit = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),PixelPlayerDatabase::class.java).build()
        try {
            val dao=db.musicDao()
            dao.insertArtists(listOf(ArtistEntity(1,"Alpha",2),ArtistEntity(2,"Beta",2),ArtistEntity(3,"Gamma",2)))
            dao.insertAlbums((1L..3L).map { AlbumEntity(it,"Album $it","Artist $it",it,null,2,listOf(0L,10L,30L,40L)[it.toInt()],2026) })
            val artist=listOf(1L,2L,3L,3L,1L)
            val dates=listOf(10L,30L,20L,40L,5L)
            val songs=(1L..5L).map { n -> Kit.song(n).copy(artistId=artist[n.toInt()-1],albumId=artist[n.toInt()-1],dateAdded=dates[n.toInt()-1],libraryItemId="b55-added-$n")
                .toEntity("/synthetic/$n.flac","/synthetic").copy(albumArtistId=artist[n.toInt()-1]) }
            dao.insertSongs(songs)
            dao.insertSongArtistCrossRefs(songs.map { SongArtistCrossRef(it.id,it.artistId,true) }+SongArtistCrossRef(4,2,false))
            assertThat(dao.getSongsPage(emptyList(),false,"song_date_added",0,100,0).map { it.id }).containsExactly(4L,2L,3L,1L,5L).inOrder()
            assertThat(dao.getAlbumsPage(emptyList(),false,0,"album_date_added",1,100,0).map { it.id }).containsExactly(3L,2L,1L).inOrder()
            assertThat(dao.getArtistsPage(emptyList(),false,0,"artist_date_added",100,0).map { it.id }).containsExactly(2L,3L,1L).inOrder()
            val artists=dao.getArtistsPaginated(emptyList(),false,0,"artist_date_added").load(PagingSource.LoadParams.Refresh(null,100,false))
            assertThat((artists as PagingSource.LoadResult.Page).data.map { it.id }).containsExactly(2L,3L,1L).inOrder()
            val albumArtists=dao.getArtistsPaginatedByAlbumArtist(emptyList(),false,0,"artist_date_added").load(PagingSource.LoadParams.Refresh(null,100,false))
            assertThat((albumArtists as PagingSource.LoadResult.Page).data.map { it.id }).containsExactly(3L,2L,1L).inOrder()
            val repo=mockk<MusicRepository>()
            every { repo.getGenres() } returns flowOf(emptyList())
            val preferences=mockk<UserPreferencesRepository>()
            every { preferences.minTracksPerAlbumFlow } returns flowOf(1)
            val holder=LibraryStateHolder(repo,preferences)
            @Suppress("UNCHECKED_CAST")
            val cache=holder.javaClass.getDeclaredField("_allSongs").apply { isAccessible=true }.get(holder) as MutableStateFlow<ImmutableList<Song>>
            cache.value=(1L..5L).map { n -> Kit.song(n).copy(artistId=if(n==4L) 1L else artist[n.toInt()-1],dateAdded=dates[n.toInt()-1],
                artists=listOf(ArtistRef(artist[n.toInt()-1],"Primary",true)) + if(n==4L) listOf(ArtistRef(2,"Beta")) else emptyList()) }.toImmutableList()
            val option=SortOption.ARTISTS.single { it.storageKey=="artist_date_added" }
            val sort=holder.javaClass.getDeclaredMethod("sortArtistsList",Iterable::class.java,SortOption::class.java).apply { isAccessible=true }
            @Suppress("UNCHECKED_CAST")
            fun cachedOrder()=(sort.invoke(holder,listOf(Artist(1,"Alpha",2),Artist(2,"Beta",2),Artist(3,"Gamma",2)),option) as List<Artist>).map { it.id }
            assertThat(cachedOrder()).containsExactly(2L,3L,1L).inOrder()
            cache.value=cache.value.map { if(it.id=="1") it.copy(dateAdded=50) else it }.toImmutableList()
            assertThat(cachedOrder()).containsExactly(1L,2L,3L).inOrder()
        } finally { db.close() }
    }
    @Test fun `all three tabs use the same Recently added label in English Spanish and Swedish`() {
        val artist=SortOption.ARTISTS.singleOrNull { it.storageKey=="artist_date_added" }
        assertThat(artist).isNotNull()
        val context=ApplicationProvider.getApplicationContext<Context>()
        for((lang,label) in listOf("en" to "Recently added","es" to "Añadidos recientemente","sv" to "Nyligen tillagda")) {
            val resources=context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }).resources
            for(option in listOf(SortOption.SongDateAdded,SortOption.AlbumDateAdded,artist!!)) assertThat(resources.getString(option.methodLabelRes())).isEqualTo(label)
        }
    }
}
