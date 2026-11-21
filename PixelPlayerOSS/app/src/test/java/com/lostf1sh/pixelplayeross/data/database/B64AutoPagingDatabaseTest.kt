package com.lostf1sh.pixelplayeross.data.database

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B64AutoPagingDatabaseTest {
    @Test fun albumArtistAndPlaylistQueriesBoundRealFiveThousandRowRoomData() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),PixelPlayerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao=db.musicDao()
            db.withTransaction {
                dao.insertArtists(listOf(ArtistEntity(1,"Synthetic artist",5000)))
                dao.insertAlbums(listOf(AlbumEntity(7,"Synthetic album","Synthetic artist",1,null,5000,0,2026)))
                val rows=(1L..5000L).map { id -> Kit.song(id,title="Song %04d".format(id),albumId=7,track=id.toInt()).copy(artistId=1)
                    .toEntity("/synthetic/$id.flac","/synthetic").copy(albumArtistId=1) }
                rows.chunked(500).forEach { dao.insertSongs(it) }
                dao.insertSongArtistCrossRefs((1L..5000L).map { SongArtistCrossRef(it,1,true) })
                db.localPlaylistDao().upsertPlaylist(PlaylistEntity("synthetic","Synthetic playlist"))
                db.localPlaylistDao().replacePlaylistSongs("synthetic",(5000L downTo 1L).map(Long::toString))
            }
            for ((album,artist,albumArtist) in listOf(Triple(7L,null,false),Triple(null,1L,false),Triple(null,1L,true))) {
                val started=System.nanoTime()
                val rows=dao.getAutoSourceSongsPage(album,artist,albumArtist,emptyList(),false,100,4900)
                val ms=(System.nanoTime()-started)/1_000_000.0
                assertThat(rows).hasSize(100)
                assertThat(rows.map { it.id }).containsExactlyElementsIn((4901L..5000L).toList()).inOrder()
                println("B64 Room 5000 album=$album artist=$artist albumArtist=$albumArtist returned=${rows.size} ms=$ms")
            }
            val ids=db.localPlaylistDao().getPlaylistLocatorsResolvedPage("synthetic",25,100)
            assertThat(ids).containsExactlyElementsIn((4900L downTo 4876L).toList()).inOrder()
            assertThat(db.localPlaylistDao().getPlaylistsPage(1,0)).hasSize(1)
        } finally { db.close() }
    }
}
