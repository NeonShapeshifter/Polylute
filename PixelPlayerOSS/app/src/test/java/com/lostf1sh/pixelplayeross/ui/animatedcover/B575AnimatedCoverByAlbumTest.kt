package com.lostf1sh.pixelplayeross.ui.animatedcover

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * B57.5 2.1 (#65): the animated cover is the **album's**, not the folder's. "If your album is this one, you take THAT cover." The finder looks for `cover.mp4` > `cover.webp` > `cover.gif` in the
 * folders of every song of the song's album (the library's `album_id`; the folders are the songs' paths already in the library: no file walk, no new permission). If more than one folder has
 * a cover, the folder holding the most songs of the album wins; on a tie the song's own folder wins, then the path order. The answer is cached per album.
 *
 * The shape of the owner's case: Album One has 11 songs in `DAP music/Artist One/[2023] Album One/` (with `cover.mp4`) and 4 loose in `DAP music/` (Loose 1, Loose 2, Loose 3, Loose 4).
 */
class B575AnimatedCoverByAlbumTest {
    private val albumOne = "/storage/emulated/0/DAP music/Artist One/[2023] Album One"
    private val loose = "/storage/emulated/0/DAP music"

    /** Folders as name lists; counts the listings; a folder in [unreadable] fails to read; a folder in [outside] is one the directory can never read. */
    private class FakeDirectory : AnimatedCoverDirectory {
        val folders = HashMap<String, List<String>>()
        val unreadable = HashSet<String>()
        val outside = HashSet<String>()
        val listed = mutableListOf<String>()
        val failOnce = HashSet<String>()

        override fun names(dir: File): List<String>? {
            listed += dir.path
            if (dir.path in outside) return null
            if (dir.path in unreadable) return null
            if (failOnce.remove(dir.path)) return null
            return folders[dir.path]
        }

        override fun stamp(dir: File): Long = 1L
        override fun sizeOf(file: File): Long = 5_000L + file.name.length
        override fun modifiedOf(file: File): Long = 42L
        override fun isUnreachable(dir: File): Boolean = dir.path in outside
    }

    /** The library's album→folders map, counting how often it is asked. */
    private class FakeAlbums : AlbumFolders {
        val byAlbum = HashMap<Long, List<AlbumFolder>>()
        val asked = mutableListOf<Long>()
        override fun foldersOf(albumId: Long): List<AlbumFolder> {
            asked += albumId
            return byAlbum[albumId].orEmpty()
        }
    }

    private class Rig(val dir: FakeDirectory = FakeDirectory(), val albums: FakeAlbums = FakeAlbums()) {
        val lines = mutableListOf<String>()
        var version = 0L
        val finder = AnimatedCoverFinder(Dispatchers.Unconfined, dir, AlbumArtUtils::shouldTrustDirectoryArtwork, { version }, albums, { lines += it })
    }

    private fun rig(block: Rig.() -> Unit = {}) = Rig().apply(block)

    private fun albumOneRig() = rig {
        dir.folders[albumOne] = listOf("cover.mp4", "Track 1.flac")
        dir.folders[loose] = listOf("Loose 1.flac", "Loose 2.flac")
        albums.byAlbum[7L] = listOf(AlbumFolder(albumOne, 11), AlbumFolder(loose, 4))
    }

    // ── the owner's rule ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a song that sits loose in another folder takes the cover of the folder its album is mostly in`() = runBlocking {
        val r = albumOneRig()
        val cover = r.finder.find("$loose/Loose 1.flac", 7L)
        assertThat(cover?.file?.path).isEqualTo("$albumOne/cover.mp4")
        assertThat(cover?.kind).isEqualTo(AnimatedCoverKind.VIDEO)
        // and the songs inside the folder get the same cover (the same identity: skipping inside the album never changes the decoder)
        assertThat(r.finder.find("$albumOne/Track 1.flac", 7L)?.identity).isEqualTo(cover?.identity)
    }

    @Test
    fun `before this batch the loose song had no cover (the folder rule), which is the owner's complaint`() = runBlocking {
        // the folder rule is what an album id of 0 still does: the song's own folder and nothing else
        val r = albumOneRig()
        assertThat(r.finder.find("$loose/Loose 1.flac")).isNull()
        assertThat(r.finder.find("$loose/Loose 1.flac", 0L)).isNull()
        assertThat(r.albums.asked).isEmpty()
    }

    @Test
    fun `when several folders have a cover the one with the most songs of the album wins, whichever folder the song is in`() = runBlocking {
        val r = rig {
            dir.folders["/m/Disc 1"] = listOf("cover.gif")
            dir.folders["/m/Disc 2"] = listOf("cover.mp4")
            dir.folders["/m/Disc 3"] = listOf("cover.webp")
            albums.byAlbum[9L] = listOf(AlbumFolder("/m/Disc 1", 3), AlbumFolder("/m/Disc 2", 9), AlbumFolder("/m/Disc 3", 5))
        }
        for (own in listOf("/m/Disc 1/a.flac", "/m/Disc 2/a.flac", "/m/Disc 3/a.flac", "/m/Elsewhere/a.flac")) {
            assertThat(r.finder.find(own, 9L)?.file?.path).isEqualTo("/m/Disc 2/cover.mp4")
        }
    }

    @Test
    fun `on a tie the song's own folder wins, then the path order`() = runBlocking {
        val r = rig {
            dir.folders["/m/B side"] = listOf("cover.gif")
            dir.folders["/m/A side"] = listOf("cover.webp")
            albums.byAlbum[3L] = listOf(AlbumFolder("/m/B side", 6), AlbumFolder("/m/A side", 6))
        }
        assertThat(r.finder.find("/m/B side/1.flac", 3L)?.file?.path).isEqualTo("/m/B side/cover.gif")
        assertThat(r.finder.find("/m/A side/1.flac", 3L)?.file?.path).isEqualTo("/m/A side/cover.webp")
        // a song in neither (a loose one): the path order
        assertThat(r.finder.find("/m/Loose/1.flac", 3L)?.file?.path).isEqualTo("/m/A side/cover.webp")
    }

    @Test
    fun `a folder with more songs but no cover does not hide the cover of a smaller folder`() = runBlocking {
        val r = rig {
            dir.folders["/m/Big"] = listOf("1.flac")
            dir.folders["/m/Small"] = listOf("cover.mp4")
            albums.byAlbum[4L] = listOf(AlbumFolder("/m/Big", 10), AlbumFolder("/m/Small", 2))
        }
        assertThat(r.finder.find("/m/Big/1.flac", 4L)?.file?.path).isEqualTo("/m/Small/cover.mp4")
    }

    @Test
    fun `an album with no cover anywhere has none, and the answer is remembered`() = runBlocking {
        val r = rig {
            dir.folders["/m/A"] = listOf("1.flac", "cover.jpg")
            dir.folders["/m/B"] = listOf("2.flac")
            albums.byAlbum[5L] = listOf(AlbumFolder("/m/A", 3), AlbumFolder("/m/B", 1))
        }
        repeat(6) { assertThat(r.finder.find("/m/A/${it + 1}.flac", 5L)).isNull() }
        assertThat(r.dir.listed).containsExactly("/m/A", "/m/B")
        assertThat(r.albums.asked).containsExactly(5L)
    }

    @Test
    fun `an album spread over a folder outside the chosen tree uses the folders inside it, and none outside means the still cover`() = runBlocking {
        val r = rig {
            dir.outside += "/outside/Album"
            dir.folders["/inside/Album"] = listOf("cover.mp4")
            albums.byAlbum[6L] = listOf(AlbumFolder("/outside/Album", 8), AlbumFolder("/inside/Album", 2))
            albums.byAlbum[8L] = listOf(AlbumFolder("/outside/Other", 4))
            dir.outside += "/outside/Other"
        }
        // the folder outside has more songs, but it cannot be read: the one inside is the cover
        assertThat(r.finder.find("/outside/Album/1.flac", 6L)?.file?.path).isEqualTo("/inside/Album/cover.mp4")
        // every folder outside: the still cover, and that is final (not asked again)
        assertThat(r.finder.find("/outside/Other/1.flac", 8L)).isNull()
        val listed = r.dir.listed.size
        assertThat(r.finder.find("/outside/Other/2.flac", 8L)).isNull()
        assertThat(r.dir.listed).hasSize(listed)
    }

    // ── cached per album ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an album costs one question to the library and one listing per folder, then nothing`() = runBlocking {
        val r = albumOneRig()
        repeat(15) { n ->
            val folder = if (n < 11) albumOne else loose
            assertThat(r.finder.find("$folder/$n.flac", 7L)?.file?.name).isEqualTo("cover.mp4")
        }
        assertThat(r.albums.asked).containsExactly(7L)
        assertThat(r.finder.albumQueries).isEqualTo(1)
        // the winner is the first folder asked and it has a cover: one listing
        assertThat(r.dir.listed).containsExactly(albumOne)
        assertThat(r.finder.listings).isEqualTo(1)
    }

    @Test
    fun `another album is another question, and a library change makes both look again`() = runBlocking {
        val r = albumOneRig()
        r.dir.folders["/m/Gaia"] = listOf("cover.gif")
        r.albums.byAlbum[2L] = listOf(AlbumFolder("/m/Gaia", 12))
        r.finder.find("$loose/Loose 1.flac", 7L)
        r.finder.find("/m/Gaia/1.flac", 2L)
        assertThat(r.albums.asked).containsExactly(7L, 2L).inOrder()
        r.version++
        r.finder.find("$loose/Loose 1.flac", 7L)
        assertThat(r.albums.asked).containsExactly(7L, 2L, 7L).inOrder()
        assertThat(r.dir.listed).containsExactly(albumOne, "/m/Gaia", albumOne).inOrder()
    }

    @Test
    fun `a song with no album id behaves as before, its own folder, and never asks the library`() = runBlocking {
        val r = albumOneRig()
        for (id in listOf(0L, -1L)) {
            assertThat(r.finder.find("$albumOne/Track 1.flac", id)?.file?.path).isEqualTo("$albumOne/cover.mp4")
            assertThat(r.finder.find("$loose/Loose 1.flac", id)).isNull()
        }
        assertThat(r.albums.asked).isEmpty()
        assertThat(r.finder.albumQueries).isEqualTo(0)
    }

    @Test
    fun `an album the library cannot say anything about falls back to the song's own folder`() = runBlocking {
        val r = rig {
            dir.folders["/m/Gaia"] = listOf("cover.mp4")
        }
        assertThat(r.finder.find("/m/Gaia/1.flac", 99L)?.file?.path).isEqualTo("/m/Gaia/cover.mp4")
        // a library that throws is the same, and is asked again next time (the failure is not remembered)
        val broken = AnimatedCoverFinder(Dispatchers.Unconfined, r.dir, AlbumArtUtils::shouldTrustDirectoryArtwork, { 0L }, AlbumFolders { error("db closed") }, {})
        assertThat(broken.find("/m/Gaia/1.flac", 99L)?.file?.path).isEqualTo("/m/Gaia/cover.mp4")
        assertThat(broken.find("/m/Gaia/1.flac", 99L)?.file?.path).isEqualTo("/m/Gaia/cover.mp4")
        assertThat(broken.albumQueries).isEqualTo(2)
    }

    @Test
    fun `a streamed song and a content-only song have no folder`() = runBlocking {
        val r = albumOneRig()
        assertThat(r.finder.find("https://example.invalid/a.flac", 7L)).isNull()
        assertThat(r.finder.find("content://media/external/audio/media/5", 7L)).isNull()
        assertThat(r.albums.asked).isEmpty()
    }

    @Test
    fun `a mixed folder like Music is not trusted even when the album has most of its songs there`() = runBlocking {
        val r = rig {
            dir.folders["/storage/emulated/0/Music"] = listOf("cover.mp4")
            dir.folders["/storage/emulated/0/Music/Gaia"] = listOf("cover.gif")
            albums.byAlbum[1L] = listOf(AlbumFolder("/storage/emulated/0/Music", 20), AlbumFolder("/storage/emulated/0/Music/Gaia", 2))
        }
        assertThat(r.finder.find("/storage/emulated/0/Music/Gaia/1.flac", 1L)?.file?.path).isEqualTo("/storage/emulated/0/Music/Gaia/cover.gif")
    }

    // ── a folder that could not be read is not "no cover" (B57.5 2.2) ────────────────────────────────

    @Test
    fun `a folder that failed to read is asked again, and a folder outside the tree is final`() = runBlocking {
        val r = rig {
            dir.folders["/m/Gaia"] = listOf("cover.mp4")
            dir.failOnce += "/m/Gaia"
        }
        // the provider errored once: no cover for now, but not remembered as "none"
        assertThat(r.finder.find("/m/Gaia/1.flac")).isNull()
        assertThat(r.finder.find("/m/Gaia/2.flac")?.file?.path).isEqualTo("/m/Gaia/cover.mp4")
        assertThat(r.dir.listed).hasSize(2)
        // the cover is now remembered
        r.finder.find("/m/Gaia/3.flac")
        assertThat(r.dir.listed).hasSize(2)
    }

    // ── the log: one line per answer that took work, none for a cached one ───────────────────────────

    @Test
    fun `an answer that took work says which folders were asked, what each said and the winner, once`() = runBlocking {
        val r = albumOneRig()
        r.finder.find("$loose/Loose 1.flac", 7L)
        assertThat(r.lines).hasSize(1)
        val line = r.lines.single()
        assertThat(line).contains("album=7")
        assertThat(line).contains("Loose 1.flac")
        assertThat(line).contains("$albumOne/cover.mp4")
        assertThat(line).contains("$albumOne (11 songs): cover found")
        repeat(5) { r.finder.find("$albumOne/$it.flac", 7L) }
        assertThat(r.lines).hasSize(1)
    }

    @Test
    fun `a miss says why, so a reviewer can tell an untrusted folder from an unreadable one from one with no cover`() = runBlocking {
        val r = rig {
            dir.outside += "/out/A"
            dir.unreadable += "/m/B"
            dir.folders["/m/C"] = listOf("1.flac")
            dir.folders["/storage/emulated/0/Music"] = listOf("cover.mp4")
            albums.byAlbum[1L] = listOf(AlbumFolder("/out/A", 4), AlbumFolder("/m/B", 3), AlbumFolder("/m/C", 2), AlbumFolder("/storage/emulated/0/Music", 1))
        }
        assertThat(r.finder.find("/m/C/1.flac", 1L)).isNull()
        val line = r.lines.single()
        assertThat(line).contains("no animated cover")
        assertThat(line).contains("/out/A (4 songs): outside the chosen folder")
        assertThat(line).contains("/m/B (3 songs): could not be read")
        assertThat(line).contains("/m/C (2 songs): no cover.mp4/webp/gif")
        assertThat(line).contains("not trusted")
    }

    // ── a sync still clears it ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a library sync makes an album look again, through the signal the app uses`() = runBlocking {
        val signal = LibraryChangeSignal()
        val dir = FakeDirectory().apply { folders["/m/Gaia"] = listOf("cover.gif") }
        val albums = FakeAlbums().apply { byAlbum[2L] = listOf(AlbumFolder("/m/Gaia", 3)) }
        val finder = AnimatedCoverFinder(Dispatchers.Unconfined, dir, AlbumArtUtils::shouldTrustDirectoryArtwork, { signal.version.value }, albums, {})
        assertThat(finder.find("/m/Gaia/1.flac", 2L)?.kind).isEqualTo(AnimatedCoverKind.GIF)
        dir.folders["/m/Gaia"] = listOf("cover.gif", "cover.mp4")
        signal.changed("test sync")
        assertThat(finder.find("/m/Gaia/1.flac", 2L)?.kind).isEqualTo(AnimatedCoverKind.VIDEO)
        assertThat(albums.asked).hasSize(2)
    }
}
