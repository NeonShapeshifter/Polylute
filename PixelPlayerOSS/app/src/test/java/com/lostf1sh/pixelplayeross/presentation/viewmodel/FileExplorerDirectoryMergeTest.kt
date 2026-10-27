package com.lostf1sh.pixelplayeross.presentation.viewmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class FileExplorerDirectoryMergeTest {

    @Test
    fun `merge preserves filesystem directories missing from MediaStore index`() {
        val unindexedAlbum = RawDirectoryEntry(
            file = File("/storage/emulated/0/Music/New Album"),
            directAudioCount = -1,
            totalAudioCount = -1,
            canonicalPath = "/storage/emulated/0/Music/New Album"
        )
        val indexedAlbum = RawDirectoryEntry(
            file = File("/storage/emulated/0/Music/Indexed Album"),
            directAudioCount = -1,
            totalAudioCount = -1,
            canonicalPath = "/storage/emulated/0/Music/Indexed Album"
        )
        val indexedAlbumCounts = indexedAlbum.copy(
            directAudioCount = 3,
            totalAudioCount = 3
        )

        val merged = mergeDirectoryEntryLists(
            filesystemEntries = listOf(unindexedAlbum, indexedAlbum),
            mediaStoreEntries = listOf(indexedAlbumCounts)
        )

        assertEquals(
            listOf(
                "/storage/emulated/0/Music/Indexed Album",
                "/storage/emulated/0/Music/New Album"
            ),
            merged.map { it.canonicalPath }
        )
        assertEquals(3, merged.first { it.canonicalPath.endsWith("Indexed Album") }.totalAudioCount)
        // B39-A1: kept, and answered: with the index in hand "no row" is zero songs, not "still scanning" (it used to stay at -1 for good).
        assertEquals(0, merged.first { it.canonicalPath.endsWith("New Album") }.totalAudioCount)
        assertEquals(0, merged.first { it.canonicalPath.endsWith("New Album") }.directAudioCount)
    }

    @Test
    fun `folders with no music in them are not left scanning once MediaStore has answered`() {
        // The phone's system folders, as the sheet listed them on the HONOR (2026-10-22 23:26): no audio under any of them.
        val systemFolders = listOf("Alarms", "Android", "Audiobooks", "DCIM", "Documents", "MyDocuments").map {
            RawDirectoryEntry(
                file = File("/storage/emulated/0/$it"),
                directAudioCount = -1,
                totalAudioCount = -1,
                canonicalPath = "/storage/emulated/0/$it",
            )
        }
        val music = systemFolders.first().copy(
            file = File("/storage/emulated/0/Music"),
            canonicalPath = "/storage/emulated/0/Music",
            directAudioCount = 12,
            totalAudioCount = 4931,
        )

        val merged = mergeDirectoryEntryLists(
            filesystemEntries = systemFolders + music.copy(directAudioCount = -1, totalAudioCount = -1),
            mediaStoreEntries = listOf(music),
        )

        assertEquals(emptyList<String>(), merged.filter { it.totalAudioCount < 0 || it.directAudioCount < 0 }.map { it.canonicalPath })
        assertEquals(4931, merged.first { it.canonicalPath.endsWith("Music") }.totalAudioCount)
        assertEquals(0, merged.first { it.canonicalPath.endsWith("DCIM") }.totalAudioCount)
    }

    @Test
    fun `a count that MediaStore did give is never replaced by the zero of a missing row`() {
        val withCounts = RawDirectoryEntry(File("/s/Music"), directAudioCount = 3, totalAudioCount = 9, canonicalPath = "/s/Music")
        val merged = mergeDirectoryEntryLists(filesystemEntries = listOf(withCounts), mediaStoreEntries = emptyList())
        // already known (from an earlier index): kept as it was
        assertEquals(9, merged.single().totalAudioCount)
        assertEquals(3, merged.single().directAudioCount)
    }
}
