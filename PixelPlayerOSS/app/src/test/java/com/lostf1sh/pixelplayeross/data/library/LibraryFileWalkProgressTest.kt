package com.lostf1sh.pixelplayeross.data.library

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.library.LibraryFileWalk.Indexed
import com.lostf1sh.pixelplayeross.data.library.LibraryFileWalk.Why
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B40-fix1 (items 4 and 5), the walk and the scanner over a real folder tree and over a made-up one:
 *  - a row Android indexed **without a duration** (right size and time) is asked about again, once;
 *  - declined files are skipped **while walking**, so a thousand of them first in order no longer hide the file behind them;
 *  - a walk a limit stopped goes on where it stopped, inside a folder too, whether or not the memory of declined files remembers.
 */
class LibraryFileWalkProgressTest {
    private val root: File = Files.createTempDirectory("walk-progress").toFile()

    @AfterEach
    fun cleanUp() { root.deleteRecursively() }

    private fun file(path: String, bytes: Int = 100): File = File(root, path).also {
        it.parentFile.mkdirs()
        it.writeBytes(ByteArray(bytes))
        it.setLastModified(1_700_000_000_000L)
    }

    private fun rules(folder: String) = LibraryFolders.Rules(allowed = setOf(File(root, folder).path), blocked = setOf(LibraryFolders.NOTHING_BY_DEFAULT))

    // ── item 4: a row without a duration ─────────────────────────────────────────────────────────

    @Test
    fun `a row with the right size and time but no duration is asked about again`() {
        val song = file("Music/a.flac", 4096)
        val index = mapOf(song.path to Indexed(song.length(), song.lastModified() / 1000L, complete = false))
        val result = LibraryFileWalk.walk(rules("Music"), index)
        assertThat(result.candidates.map { it.path to it.why }).containsExactly(song.path to Why.INCOMPLETE)
    }

    @Test
    fun `a complete row with the right size and time is not asked about`() {
        val song = file("Music/a.flac", 4096)
        val index = mapOf(song.path to Indexed(song.length(), song.lastModified() / 1000L))
        assertThat(LibraryFileWalk.walk(rules("Music"), index).candidates).isEmpty()
    }

    @Test
    fun `a changed file is stale before it is incomplete`() {
        val song = file("Music/a.flac", 4096)
        val index = mapOf(song.path to Indexed(size = 1, modifiedSeconds = 1, complete = false))
        assertThat(LibraryFileWalk.walk(rules("Music"), index).candidates.single().why).isEqualTo(Why.STALE)
    }

    // ── item 5: the limit ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a thousand declined files first in order do not hide the file behind them`() {
        (0 until 1000).forEach { file("Music/decline-%04d.flac".format(it)) }
        val fresh = file("Music/zz-new.flac")
        val result = LibraryFileWalk.walk(rules("Music"), emptyMap(), skip = { it.path != fresh.path })
        assertThat(result.candidates.map { it.path }).containsExactly(fresh.path)
        assertThat(result.skippedKnown).isEqualTo(1000)
        assertThat(result.truncated).isFalse()
        // the same tree without the skip is what used to happen: the cap is full of the others and the new file is not reached
        val before = LibraryFileWalk.walk(rules("Music"), emptyMap())
        assertThat(before.candidates).hasSize(1000)
        assertThat(before.candidates.map { it.path }).doesNotContain(fresh.path)
        assertThat(before.truncated).isTrue()
    }

    @Test
    fun `a walk stopped by the candidate limit goes on at the next file of the same folder`() {
        val names = (1..10).map { "song-%02d.flac".format(it) }
        names.forEach { file("Music/$it") }
        val limits = LibraryFileWalk.Limits(maxCandidates = 3)
        val asked = mutableListOf<String>()
        var resume: List<String>? = null
        repeat(6) {
            val result = LibraryFileWalk.walk(rules("Music"), emptyMap(), limits = limits, resume = resume)
            asked += result.candidates.map { File(it.path).name }
            resume = result.pending.takeIf { result.truncated }
        }
        // 3 + 3 + 3 + 1, in order, no file twice; then the next cycle starts from the folder again
        assertThat(asked.take(10)).containsExactlyElementsIn(names).inOrder()
        assertThat(asked.drop(10).first()).isEqualTo(names.first())
    }

    @Test
    fun `a walk stopped by the limit continues in the folders that were not reached`() {
        val names = listOf("A/one.flac", "A/two.flac", "B/three.flac", "C/four.flac", "C/five.flac")
        names.forEach { file("Music/$it") }
        val limits = LibraryFileWalk.Limits(maxCandidates = 2)
        val asked = mutableListOf<String>()
        var resume: List<String>? = null
        do {
            val result = LibraryFileWalk.walk(rules("Music"), emptyMap(), limits = limits, resume = resume)
            asked += result.candidates.map { it.path.removePrefix(File(root, "Music").path + "/") }
            resume = result.pending.takeIf { result.truncated }
        } while (resume != null)
        assertThat(asked).containsExactlyElementsIn(names)
    }

    @Test
    fun `a finished walk leaves nothing to resume`() {
        file("Music/a.flac")
        val result = LibraryFileWalk.walk(rules("Music"), emptyMap())
        assertThat(result.truncated).isFalse()
        assertThat(result.pending).isEmpty()
    }

    // ── the scanner: memory of declined files, and the resume kept in it ─────────────────────────

    private class Remembering : LibraryFileScanner.Memory {
        val declinedKeys = mutableSetOf<String>()
        var resume: List<String>? = null
        var resumeFor: String? = null
        private fun key(c: LibraryFileWalk.Candidate) = "${c.path}|${c.size}|${c.modifiedMs}"
        override fun lastWalkAtMs() = 0L
        override fun noteWalk(atMs: Long) = Unit
        override fun declined(candidate: LibraryFileWalk.Candidate) = key(candidate) in declinedKeys
        override fun decline(candidate: LibraryFileWalk.Candidate) { declinedKeys += key(candidate) }
        override fun resumePoint(signature: String) = resume.takeIf { resumeFor == signature }
        override fun saveResumePoint(signature: String, pending: List<String>?) { resume = pending; resumeFor = signature.takeIf { pending != null } }
    }

    private fun scanner(
        memory: LibraryFileScanner.Memory,
        entries: Map<String, List<LibraryFileWalk.Entry>>,
        index: () -> Map<String, Indexed>,
        scan: (List<String>) -> Set<String>,
        limits: LibraryFileWalk.Limits = LibraryFileWalk.Limits(),
    ) = LibraryFileScanner(
        context = mockk<Context>(relaxed = true),
        memory = memory,
        fs = object : LibraryFileWalk.Fs { override fun list(directory: String) = entries[directory] ?: emptyList() },
        limits = limits,
        scan = { paths, _ -> scan(paths) },
        readIndex = { index() },
    )

    private val rootDir = "/storage/emulated/0/Music/Review"
    private val reviewRules = LibraryFolders.Rules(setOf(rootDir), setOf("/"))

    @Test
    fun `rejected files do not starve later files across manual rescans`() = runBlocking<Unit> {
        val entries = (0..1000).map { LibraryFileWalk.Entry("fixture-%04d.flac".format(it), false, 4096, 1000) }
        val asked = mutableSetOf<String>()
        val s = scanner(Remembering(), mapOf(rootDir to entries), { emptyMap() }, { paths -> asked += paths; paths.toSet() })
        repeat(3) { s.run(reviewRules) }
        assertThat(asked).contains("$rootDir/fixture-1000.flac")
    }

    @Test
    fun `the new file is asked on the first update when a thousand files before it are declined`() = runBlocking<Unit> {
        val memory = Remembering()
        val entries = (0..1000).map { LibraryFileWalk.Entry("fixture-%04d.flac".format(it), false, 4096, 1000) }
        // the thousand are remembered as declined already
        entries.take(1000).forEach { memory.decline(LibraryFileWalk.Candidate("$rootDir/${it.name}", 4096, 1000, Why.MISSING)) }
        val asked = mutableListOf<String>()
        val s = scanner(memory, mapOf(rootDir to entries), { emptyMap() }, { paths -> asked += paths; emptySet() })
        s.run(reviewRules)
        assertThat(asked).containsExactly("$rootDir/fixture-1000.flac")
    }

    @Test
    fun `progress does not depend on the memory of declined files`() = runBlocking<Unit> {
        // a memory that remembers nothing, and Android declines every file: only the resume point moves the walk forward
        val forgetful = object : LibraryFileScanner.Memory {
            var resume: List<String>? = null
            var resumeFor: String? = null
            override fun lastWalkAtMs() = 0L
            override fun noteWalk(atMs: Long) = Unit
            override fun declined(candidate: LibraryFileWalk.Candidate) = false
            override fun decline(candidate: LibraryFileWalk.Candidate) = Unit
            override fun resumePoint(signature: String) = resume.takeIf { resumeFor == signature }
            override fun saveResumePoint(signature: String, pending: List<String>?) { resume = pending; resumeFor = signature.takeIf { pending != null } }
        }
        val entries = (1..7).map { LibraryFileWalk.Entry("f$it.flac", false, 10, 1000) }
        val asked = mutableListOf<String>()
        val s = scanner(
            forgetful, mapOf(rootDir to entries), { emptyMap() }, { paths -> asked += paths; paths.toSet() },
            limits = LibraryFileWalk.Limits(maxCandidates = 2),
        )
        repeat(4) { s.run(reviewRules) }
        assertThat(asked.map { it.substringAfterLast('/') }).containsExactly("f1.flac", "f2.flac", "f3.flac", "f4.flac", "f5.flac", "f6.flac", "f7.flac").inOrder()
    }

    @Test
    fun `a resume point for other folders is not used`() = runBlocking<Unit> {
        val memory = Remembering().also { it.saveResumePoint("/somewhere/else", listOf("/somewhere/else/x")) }
        val entries = listOf(LibraryFileWalk.Entry("a.flac", false, 10, 1000))
        val asked = mutableListOf<String>()
        scanner(memory, mapOf(rootDir to entries), { emptyMap() }, { paths -> asked += paths; emptySet() }).run(reviewRules)
        assertThat(asked).containsExactly("$rootDir/a.flac")
    }

    @Test
    fun `a file that stays without a duration is asked once and then remembered, and asked again when it changes`() = runBlocking<Unit> {
        val memory = Remembering()
        var size = 4096L
        val asked = mutableListOf<String>()
        // the file on disk and the row in the index agree on size and time, and the row has no duration: "incomplete", not "stale"
        val disk = object : LibraryFileWalk.Fs {
            override fun list(directory: String) = listOf(LibraryFileWalk.Entry("broken.flac", false, size, 1000))
        }
        val s = LibraryFileScanner(
            context = mockk<Context>(relaxed = true), memory = memory, fs = disk,
            scan = { paths, _ -> asked += paths; emptySet() },
            readIndex = { mapOf("$rootDir/broken.flac" to Indexed(size, 1L, complete = false)) },
        )
        s.run(reviewRules)
        s.run(reviewRules)
        s.run(reviewRules)
        assertThat(asked).containsExactly("$rootDir/broken.flac")
        // the file is replaced (a different size): it is a new question
        size = 8192L
        s.run(reviewRules)
        assertThat(asked).hasSize(2)
    }
}
