package com.lostf1sh.pixelplayeross.data.media

import android.app.Application
import androidx.work.ListenableWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B63EditorAlbumTest {
    private lateinit var rig: EditorIdentityRig
    @Before fun setup() { rig = EditorIdentityRig() }
    @After fun close() { rig.close() }

    private suspend fun seed() {
        for (id in 1L..4L) rig.addFixture(id, "tone.flac", "Synthetic $id", artist = "Group", album = "Release", track = id.toInt())
        rig.firstScan()
        // Past editor saved all file/MediaStore tags, but left one projection grouped separately.
        val song = rig.dao.getSongByLocatorOnce(1)!!
        val album = rig.dao.getAllAlbumsList(emptyList(), false, 0).single()
        rig.dao.insertAlbums(listOf(album.copy(id = 991, artistName = "Group; Guest", albumArtist = "Group; Guest", groupingKey = "alb:release|group; guest", songCount = 1)))
        rig.dao.updateSongs(listOf(song.copy(albumId = 991, albumArtist = "Group; Guest", albumGroupingKey = "alb:release|group; guest")))
    }

    private suspend fun save(album: String = "Release", albumArtist: String = "Group", date: String? = null) =
        rig.editor().editSongMetadata(1, "Synthetic 1", "Group", album, albumArtist,
            newGenre = "Rock", newLyrics = "", newTrackNumber = 1, newDiscNumber = null, newDate = date)

    @Test fun `resaving stale album artist merges four songs and keeps user state`() = runBlocking {
        seed()
        val item = rig.giveUserState(1)
        assertTrue(save().success)
        val song = rig.dao.getSongByLocatorOnce(1)!!
        assertEquals("Group", song.albumArtist)
        assertEquals("alb:release|group", song.albumGroupingKey)
        val albums = rig.dao.getAllAlbumsList(emptyList(), false, 0)
        assertEquals(1, albums.size)
        assertEquals(4, albums.single().songCount)
        assertEquals(albums.single().id, song.albumId)
        assertEquals(item, song.libraryItemId)
        assertEquals(1L, song.id)
        assertEquals(rig.expectedUserState, rig.userStateOf(item))
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)
        assertEquals("Group", rig.dao.getSongByLocatorOnce(1)!!.albumArtist)
        assertEquals(1, rig.dao.getAllAlbumsList(emptyList(), false, 0).size)
        assertEquals(rig.expectedUserState, rig.userStateOf(item))
    }

    @Test fun `new album title moves one song without changing its identity`() = runBlocking {
        seed()
        assertTrue(save().success)
        val item = rig.dao.getSongByLocatorOnce(1)!!.libraryItemId
        assertTrue(save(album = "Another Release", date = "2026").success)
        val song = rig.dao.getSongByLocatorOnce(1)!!
        val albums = rig.dao.getAllAlbumsList(emptyList(), false, 0)
        assertEquals(2, albums.size)
        assertEquals("Another Release", albums.single { it.id == song.albumId }.title)
        assertEquals(1, albums.single { it.id == song.albumId }.songCount)
        assertEquals(2026, albums.single { it.id == song.albumId }.year)
        assertEquals(3, albums.single { it.id != song.albumId }.songCount)
        assertEquals(item, song.libraryItemId)
        assertEquals(1L, song.id)
    }

    @Test fun `compilation and album artist id follow the scanner rules`() = runBlocking {
        seed()
        assertTrue(save(albumArtist = "Various Artists").success)
        val song = rig.dao.getSongByLocatorOnce(1)!!
        assertTrue(song.isCompilation)
        assertEquals("alb:release|various artists", song.albumGroupingKey)
        val artists = rig.dao.getAllArtistsListRaw()
        assertEquals("Various Artists", artists.single { it.id == song.albumArtistId }.name)
        val album = rig.dao.getAllAlbumsList(emptyList(), false, 0).single { it.id == song.albumId }
        assertTrue(album.isCompilation)
    }
    @Test fun `unsafe save failure reaches the editor result in all three languages`() = runBlocking {
        seed()
        val file = rig.dao.getSongByLocatorOnce(1)!!.filePath
        val before = java.io.File(file).readBytes()
        for(language in listOf("en","es","sv")) {
            val config = android.content.res.Configuration(rig.context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val context = rig.context.createConfigurationContext(config)
            val editor = SongMetadataEditor(context,rig.dao,rig.prefs,tagLibEnabled=false,
                rescanner=MediaRescanner {},replaceMetadataFile={ _,_ -> throw SafeMetadataWriteUnavailable(java.io.IOException("EPERM")) })
            val result = editor.editSongMetadata(1,"Synthetic 1","Group","Release","Group",newGenre="Rock",newLyrics="",newTrackNumber=1,newDiscNumber=null)
            assertFalse(result.success)
            assertEquals(MetadataEditError.SAFE_WRITE_UNAVAILABLE,result.error)
            assertEquals(context.getString(com.lostf1sh.pixelplayeross.R.string.metadata_edit_safe_write_failed),result.errorMessage)
            assertArrayEquals(before,java.io.File(file).readBytes())
        }
    }
    @Test fun `incremental sync repairs only stale edited rows without reading the entire library`() = runBlocking {
        seed()
        val stale = rig.dao.getSongByLocatorOnce(1)!!
        rig.dao.updateSongs(listOf(stale.copy(albumUserEdited=true,titleUserEdited=true,artistUserEdited=true,genreUserEdited=true)))
        val item = rig.giveUserState(1)
        val beforeBytes = java.io.File(stale.filePath).readBytes()
        val tagReads = mutableSetOf<String>()
        io.mockk.every { AudioMetadataReader.read(any<java.io.File>(),any(),any()) } answers {
            tagReads += firstArg<java.io.File>().path
            null
        }
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)
        val repaired = rig.dao.getSongByLocatorOnce(1)!!
        assertEquals("Group",repaired.albumArtist)
        assertEquals("alb:release|group",repaired.albumGroupingKey)
        assertEquals(1,rig.dao.getAllAlbumsList(emptyList(),false,0).size)
        assertEquals(4,rig.dao.getAllAlbumsList(emptyList(),false,0).single().songCount)
        assertEquals(item,repaired.libraryItemId)
        assertEquals(rig.expectedUserState,rig.userStateOf(item))
        assertArrayEquals(beforeBytes,java.io.File(stale.filePath).readBytes())
        assertEquals(setOf(stale.filePath),tagReads)
        tagReads.clear()
        assertTrue(rig.rescan() is ListenableWorker.Result.Success)
        assertTrue("a repaired unchanged library needs no further tag reads",tagReads.isEmpty())
    }
}
