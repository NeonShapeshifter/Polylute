package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** FIX-1: which indexed rows queue up behind the file that was opened. */
class FolderSiblingsTest {

    private fun row(id: Long, name: String) = IndexedFolderRow(
        id = id,
        uri = "content://media/external/audio/media/$id",
        displayName = name,
        title = null, artist = null, artistId = null, album = null, albumId = null,
        durationMs = null, track = null, year = null, dateAddedSeconds = null, dataPath = null, mimeType = null,
    )

    private val rows = listOf(row(1, "a.flac"), row(2, "b.flac"), row(3, "c.flac"), row(4, "d.flac"))

    @Test
    fun `the rows after the opened file, in listing order`() {
        val after = FolderSiblings.after(rows, "content://media/external/audio/media/2", "b.flac")
        assertThat(after.map { it.id }).containsExactly(3L, 4L).inOrder()
    }

    @Test
    fun `the opened file is found by name when its uri is not the listed one`() {
        val after = FolderSiblings.after(rows, "content://elsewhere/2", " B.FLAC ")
        assertThat(after.map { it.id }).containsExactly(3L, 4L).inOrder()
    }

    @Test
    fun `a file that is not in the listing queues the whole folder behind it`() {
        val after = FolderSiblings.after(rows, "content://elsewhere/9", "z.flac")
        assertThat(after.map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `the last file has nothing behind it, and an empty folder has nothing`() {
        assertThat(FolderSiblings.after(rows, "content://media/external/audio/media/4", "d.flac")).isEmpty()
        assertThat(FolderSiblings.after(emptyList(), "content://x", null)).isEmpty()
    }
}
