package com.lostf1sh.pixelplayeross.data.repository

import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.data.database.toSong
import com.lostf1sh.pixelplayeross.data.model.Song
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class B625SongSharingTest {
    private fun row(id: Long) = SongEntity(id = id, title = "Synthetic $id", artistName = "Artist", artistId = 1, albumName = "Album", albumId = 1, contentUriString = "content://synthetic/$id", albumArtUriString = null, duration = 180000, genre = "Synthetic", filePath = "/synthetic/$id.flac", parentDirectoryPath = "/synthetic", dateAdded = 0, libraryItemId = "identity-$id")
    @Test fun fullReadersShareSongsWhileReducedFolderProjectionsKeepTheirOwnFields() {
        val rows = (1L..5000L).map { row(it).copy(parentDirectoryPath = "/synthetic/album", filePath = "/synthetic/album/$it.flac") }
        val fullReaders = List(3) { rows.map { it.toSong() } }
        val folderRows = rows.map { com.lostf1sh.pixelplayeross.data.database.FolderSongRow(it.id, it.parentDirectoryPath, it.title, it.albumArtUriString) }
        val folders = FolderTreeBuilder().buildFolderTreeForRoots(folderRows, setOf("/synthetic"))
        val folderSongs = folders.flatMap { it.songs }
        assertEquals(5000, folderSongs.size)
        val distinct = Collections.newSetFromMap(IdentityHashMap<Song, Boolean>())
        fullReaders.forEach { distinct.addAll(it) }; distinct.addAll(folderSongs)
        println("B625 synthetic mixed library fullReaders=3 folderReader=1 rows=5000 distinctSongs=${distinct.size}")
        assertEquals(10000, distinct.size)
        assertNotEquals(fullReaders[0][0], folderSongs[0])
    }

    @Test fun fourFullReadersShareIdenticalSongsButAnEditIsANewSnapshot() {
        val rows = (1L..5000L).map(::row)
        val readers = List(4) { rows.map { it.toSong() } }
        val distinct = Collections.newSetFromMap(IdentityHashMap<Song, Boolean>())
        readers.forEach { distinct.addAll(it) }
        println("B625 synthetic library readers=4 rows=5000 distinctSongs=${distinct.size}")
        assertEquals(5000, distinct.size)
        val edited = rows[0].copy(title = "Edited").toSong()
        assertNotSame(readers[0][0], edited)
        assertEquals("Synthetic 1", readers[0][0].title)
        assertEquals("Edited", edited.title)
        // Same locator with different artwork/favourite/identity/lyrics is never collapsed.
        for (changed in listOf(rows[0].copy(isFavorite = true), rows[0].copy(libraryItemId = "new-identity"), rows[0].copy(lyrics = "Invented line"))) {
            assertNotEquals(readers[0][0], changed.toSong())
        }
    }
}
