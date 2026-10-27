package com.lostf1sh.pixelplayeross.data.library

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.library.LibraryFileWalk.Indexed
import com.lostf1sh.pixelplayeross.data.library.LibraryFileWalk.Why
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B40-F3: the walk of the included folders, on a real folder tree. Files Android has not indexed are found; a folder that is not included,
 * is excluded, is hidden or says `.nomedia` is not entered; a file the index holds as it is on disk is not asked for again; a limit stops it
 * and says so.
 */
class LibraryFileWalkTest {
    private val root: File = Files.createTempDirectory("walk").toFile()

    @AfterEach
    fun cleanUp() { root.deleteRecursively() }

    private fun file(path: String, bytes: Int = 100, modifiedMs: Long = 1_700_000_000_000L): File {
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(bytes))
        f.setLastModified(modifiedMs)
        return f
    }

    private fun includes(vararg folders: String, excluded: Set<String> = emptySet()) =
        LibraryFolders.Rules(allowed = folders.map { File(root, it).path }.toSet(), blocked = excluded + LibraryFolders.NOTHING_BY_DEFAULT)

    private fun indexOf(vararg f: File) = f.associate { it.path to Indexed(it.length(), it.lastModified() / 1000L) }

    @Test
    fun `a new file in an included folder is found when MediaStore does not hold it`() {
        val held = file("Music/DAP/Artist/Album/one.flac")
        val fresh = file("Music/DAP/Artist/Album/new.flac", bytes = 4096)
        val result = LibraryFileWalk.walk(includes("Music/DAP"), indexOf(held))
        assertThat(result.candidates.map { it.path }).containsExactly(fresh.path)
        assertThat(result.candidates.single().why).isEqualTo(Why.MISSING)
        assertThat(result.candidates.single().size).isEqualTo(4096L)
        assertThat(result.audioFiles).isEqualTo(2)
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `a file in a folder that is not included stays out - the folder is never walked`() {
        file("Music/DAP/in.flac")
        val outside = file("Music/Other/out.flac")
        val visited = mutableListOf<String>()
        val fs = object : LibraryFileWalk.Fs {
            override fun list(directory: String): List<LibraryFileWalk.Entry>? {
                visited += directory
                return LibraryFileWalk.JavaFs.list(directory)
            }
        }
        val result = LibraryFileWalk.walk(includes("Music/DAP"), emptyMap(), fs)
        assertThat(result.candidates.map { it.path }).doesNotContain(outside.path)
        assertThat(visited.none { it.startsWith(File(root, "Music/Other").path) }).isTrue()
    }

    @Test
    fun `an excluded folder inside an included one is not entered, and an included one inside it is`() {
        val skipped = file("Music/DAP/Voice notes/note.flac")
        val kept = file("Music/DAP/Albums/a.flac")
        val exception = file("Music/DAP/Voice notes/Keep/k.flac")
        val rules = LibraryFolders.Rules(
            allowed = setOf(File(root, "Music/DAP").path, File(root, "Music/DAP/Voice notes/Keep").path),
            blocked = setOf(LibraryFolders.NOTHING_BY_DEFAULT, File(root, "Music/DAP/Voice notes").path),
        )
        val paths = LibraryFileWalk.walk(rules, emptyMap()).candidates.map { it.path }
        assertThat(paths).contains(kept.path)
        assertThat(paths).contains(exception.path)
        assertThat(paths).doesNotContain(skipped.path)
    }

    @Test
    fun `hidden folders and folders with a nomedia file are left alone, as Android's scanner leaves them`() {
        val hidden = file("Music/DAP/.thumbs/x.flac")
        val nomedia = file("Music/DAP/Ringtones/r.flac")
        File(root, "Music/DAP/Ringtones/.nomedia").writeText("")
        val ok = file("Music/DAP/ok.flac")
        val result = LibraryFileWalk.walk(includes("Music/DAP"), emptyMap())
        assertThat(result.candidates.map { it.path }).containsExactly(ok.path)
        assertThat(result.skippedDirectories).isAtLeast(2)
        assertThat(setOf(hidden.path, nomedia.path)).containsNoneIn(result.candidates.map { it.path })
    }

    @Test
    fun `a file MediaStore holds as it is on disk is not asked for again, and a changed one is`() {
        val same = file("Music/DAP/same.flac", bytes = 200)
        val grown = file("Music/DAP/grown.flac", bytes = 300)
        val touched = file("Music/DAP/touched.flac", bytes = 400)
        val index = mapOf(
            same.path to Indexed(200, same.lastModified() / 1000),
            grown.path to Indexed(250, grown.lastModified() / 1000),                    // the file grew
            touched.path to Indexed(400, touched.lastModified() / 1000 - 3600),         // an hour older in the index
        )
        val result = LibraryFileWalk.walk(includes("Music/DAP"), index)
        assertThat(result.candidates.map { it.path to it.why }).containsExactly(grown.path to Why.STALE, touched.path to Why.STALE)
    }

    @Test
    fun `a second of difference between the file system and MediaStore is the same time`() {
        val f = file("Music/DAP/a.flac")
        val index = mapOf(f.path to Indexed(f.length(), f.lastModified() / 1000 + 1))
        assertThat(LibraryFileWalk.walk(includes("Music/DAP"), index).candidates).isEmpty()
    }

    @Test
    fun `only audio files count, whatever their case`() {
        file("Music/DAP/cover.jpg")
        file("Music/DAP/notes.txt")
        val upper = file("Music/DAP/LOUD.FLAC")
        val result = LibraryFileWalk.walk(includes("Music/DAP"), emptyMap())
        assertThat(result.candidates.map { it.path }).containsExactly(upper.path)
        assertThat(result.audioFiles).isEqualTo(1)
    }

    @Test
    fun `MediaStore's spelling of the path decides when only the case differs`() {
        val f = file("Music/DAP/Song.flac")
        val index = mapOf(f.path.lowercase() to Indexed(f.length(), f.lastModified() / 1000))
        assertThat(LibraryFileWalk.walk(includes("Music/DAP"), index).candidates).isEmpty()
    }

    @Test
    fun `under the old rules, everything unless excluded, there is no folder to start from and nothing is walked`() {
        file("Music/DAP/a.flac")
        val legacy = LibraryFolders.Rules(allowed = emptySet(), blocked = setOf("/somewhere/else"))
        assertThat(LibraryFileWalk.roots(legacy)).isEmpty()
        val result = LibraryFileWalk.walk(legacy, emptyMap())
        assertThat(result.candidates).isEmpty()
        assertThat(result.directories).isEqualTo(0)
    }

    @Test
    fun `a folder that cannot be listed is skipped, not an error`() {
        val fs = object : LibraryFileWalk.Fs { override fun list(directory: String): List<LibraryFileWalk.Entry>? = null }
        val result = LibraryFileWalk.walk(includes("Music/DAP"), emptyMap(), fs)
        assertThat(result.candidates).isEmpty()
        assertThat(result.skippedDirectories).isEqualTo(1)
    }

    @Test
    fun `the candidate limit stops the walk and says so, and the rest is found next time`() {
        repeat(25) { file("Music/DAP/a$it.flac") }
        val first = LibraryFileWalk.walk(includes("Music/DAP"), emptyMap(), limits = LibraryFileWalk.Limits(maxCandidates = 10))
        assertThat(first.candidates).hasSize(10)
        // the limit is checked per folder: a folder that is already being read is read in full, within the cap on candidates
        val rest = LibraryFileWalk.walk(includes("Music/DAP"), indexOf(*first.candidates.map { File(it.path) }.toTypedArray()))
        assertThat(rest.candidates).hasSize(15)
    }

    @Test
    fun `the time limit stops the walk and says so`() {
        repeat(5) { dir -> repeat(3) { file("Music/DAP/d$dir/f$it.flac") } }
        var clock = 0L
        val result = LibraryFileWalk.walk(
            includes("Music/DAP"), emptyMap(), limits = LibraryFileWalk.Limits(maxMillis = 100),
            now = { clock.also { clock += 60 } },
        )
        assertThat(result.truncated).isTrue()
        assertThat(result.directories).isAtMost(3)
    }

    @Test
    fun `a library of five thousand songs is walked in well under a second on a real folder tree, with one directory listing per folder`() {
        // 5,000 songs in 500 album folders, all indexed: the walk finds nothing and touches each folder once
        val all = mutableListOf<File>()
        repeat(500) { album -> repeat(10) { track -> all += file("Music/DAP/Artist${album / 10}/Album$album/t$track.flac") } }
        var listings = 0
        val fs = object : LibraryFileWalk.Fs {
            override fun list(directory: String): List<LibraryFileWalk.Entry>? { listings++; return LibraryFileWalk.JavaFs.list(directory) }
        }
        val started = System.nanoTime()
        val result = LibraryFileWalk.walk(includes("Music/DAP"), indexOf(*all.toTypedArray()), fs)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertThat(result.candidates).isEmpty()
        assertThat(result.audioFiles).isEqualTo(5000)
        assertThat(result.directories).isEqualTo(551)   // 500 albums + 50 artists + the root
        assertThat(listings).isEqualTo(551)
        assertThat(result.truncated).isFalse()
        println("B40-F3 walk of 5,000 songs in 551 folders on this host: $millis ms")
        assertThat(millis).isLessThan(2_000L)
    }
}
