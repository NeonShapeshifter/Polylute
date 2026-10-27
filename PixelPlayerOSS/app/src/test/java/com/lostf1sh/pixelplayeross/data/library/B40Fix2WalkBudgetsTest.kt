package com.lostf1sh.pixelplayeross.data.library

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.library.LibraryFileWalk.Indexed
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test

/**
 * B40-fix2 item 4: the walk's time and file limits are met per file, inside a folder, not only between folders, and a walk they stop
 * continues at the next file. `an_audio_file_budget_is_respected_inside_a_single_folder` and `a_time_budget_is_respected_inside_a_single_folder`
 * are the outside review's cases of B40-fix1 (`ReviewB40Fix1Test`), ported as they came: both failed on the fix1 code, which looked at
 * `maxAudioFiles` and `maxMillis` only before each folder (with a limit of two files it dealt with ten).
 */
class B40Fix2WalkBudgetsTest {
    private val tree: File = Files.createTempDirectory("walk-budgets").toFile()

    @After
    fun cleanUp() { tree.deleteRecursively() }

    // ── the review's cases, ported as they came ──────────────────────────────────────────────────

    @Test fun an_audio_file_budget_is_respected_inside_a_single_folder() {
        val root = "/storage/emulated/0/Music/Fixture"
        val entries = (1..10).map { LibraryFileWalk.Entry("$it.flac", false, 4096, 1000) }
        val index = entries.associate { "$root/${it.name}" to LibraryFileWalk.Indexed(4096, 1) }
        val report = LibraryFileWalk.walk(LibraryFolders.Rules(setOf(root), setOf("/")), index,
            fs = object : LibraryFileWalk.Fs { override fun list(directory: String) = entries },
            limits = LibraryFileWalk.Limits(maxAudioFiles = 2), now = { 1000L })
        assertThat(report.audioFiles).isAtMost(2)
        assertThat(report.truncated).isTrue()
        assertThat(report.pending).isNotEmpty()
    }

    @Test fun a_time_budget_is_respected_inside_a_single_folder() {
        val root = "/storage/emulated/0/Music/Fixture"
        var clock = 0L
        val entries = (1..10).map { LibraryFileWalk.Entry("$it.flac", false, 4096, 1000) }
        val report = LibraryFileWalk.walk(LibraryFolders.Rules(setOf(root), setOf("/")), emptyMap(),
            fs = object : LibraryFileWalk.Fs { override fun list(directory: String) = entries },
            limits = LibraryFileWalk.Limits(maxMillis = 2), now = { clock },
            skip = { clock += 10; true })
        assertThat(report.audioFiles).isAtMost(1)
        assertThat(report.truncated).isTrue()
    }

    // ── which limit stopped it ───────────────────────────────────────────────────────────────────

    private fun rules(folder: String) = LibraryFolders.Rules(allowed = setOf(File(tree, folder).path), blocked = setOf(LibraryFolders.NOTHING_BY_DEFAULT))

    private fun file(path: String): File = File(tree, path).also {
        it.parentFile.mkdirs()
        it.writeBytes(ByteArray(100))
        it.setLastModified(1_700_000_000_000L)
    }

    private fun indexed(vararg files: File) = files.associate { it.path to Indexed(it.length(), it.lastModified() / 1000L) }

    @Test
    fun the_walk_says_which_limit_stopped_it() {
        val a = file("Music/a.flac"); val b = file("Music/b.flac"); val c = file("Music/c.flac")
        val index = indexed(a, b, c)
        assertThat(LibraryFileWalk.walk(rules("Music"), index, limits = LibraryFileWalk.Limits(maxAudioFiles = 2)).stoppedBy)
            .isEqualTo(LibraryFileWalk.Limit.AUDIO_FILES)
        var clock = 0L
        val slow = object : LibraryFileWalk.Fs {
            override fun list(directory: String): List<LibraryFileWalk.Entry>? { clock += 5; return LibraryFileWalk.JavaFs.list(directory) }
        }
        val timed = LibraryFileWalk.walk(rules("Music"), index, fs = slow, limits = LibraryFileWalk.Limits(maxMillis = 0), now = { clock })
        assertThat(timed.stoppedBy).isNotNull()
        assertThat(LibraryFileWalk.walk(rules("Music"), emptyMap(), limits = LibraryFileWalk.Limits(maxCandidates = 2)).stoppedBy)
            .isEqualTo(LibraryFileWalk.Limit.CANDIDATES)
        val finished = LibraryFileWalk.walk(rules("Music"), index)
        assertThat(finished.stoppedBy).isNull()
        assertThat(finished.truncated).isFalse()
    }

    // ── a folder a limit interrupted is completed by the next walk: no file twice, none skipped ──

    private fun chain(
        rules: LibraryFolders.Rules,
        index: Map<String, Indexed>,
        limits: LibraryFileWalk.Limits,
        now: () -> Long = System::currentTimeMillis,
        skip: (LibraryFileWalk.Candidate) -> Boolean = { false },
        maxWalks: Int = 40,
    ): Pair<List<String>, List<LibraryFileWalk.Result>> {
        val asked = mutableListOf<String>()
        val walks = mutableListOf<LibraryFileWalk.Result>()
        var resume: List<String>? = null
        do {
            val result = LibraryFileWalk.walk(rules, index, limits = limits, now = now, skip = skip, resume = resume)
            walks += result
            asked += result.candidates.map { it.path.removePrefix(tree.path + "/") }
            resume = result.pending.takeIf { result.truncated }
        } while (resume != null && walks.size < maxWalks)
        return asked to walks
    }

    @Test
    fun a_folder_interrupted_by_the_file_limit_is_completed_by_the_next_walks_without_repeats_or_gaps() {
        val names = (1..10).map { "Music/song-%02d.flac".format(it) }
        names.forEach { file(it) }
        val (asked, walks) = chain(rules("Music"), emptyMap(), LibraryFileWalk.Limits(maxAudioFiles = 3))
        assertThat(asked).containsExactlyElementsIn(names).inOrder()
        assertThat(walks.map { it.audioFiles }.all { it <= 3 }).isTrue()
        assertThat(walks.dropLast(1).all { it.stoppedBy == LibraryFileWalk.Limit.AUDIO_FILES }).isTrue()
        assertThat(walks.last().truncated).isFalse()
    }

    @Test
    fun the_file_limit_also_stops_a_folder_of_files_that_are_all_indexed_and_the_walk_still_gets_to_the_end() {
        val files = (1..10).map { file("Music/done-%02d.flac".format(it)) }
        val (asked, walks) = chain(rules("Music"), indexed(*files.toTypedArray()), LibraryFileWalk.Limits(maxAudioFiles = 2))
        assertThat(asked).isEmpty()
        // five walks of two files each, the last one finishing the folder
        assertThat(walks.size).isAtLeast(5)
        assertThat(walks.sumOf { it.audioFiles }).isEqualTo(10)
        assertThat(walks.first().truncated).isTrue()
        assertThat(walks.last().truncated).isFalse()
    }

    @Test
    fun a_folder_interrupted_by_the_time_limit_is_completed_by_the_next_walks_without_repeats_or_gaps() {
        val names = (1..9).map { "Music/song-%02d.flac".format(it) }
        names.forEach { file(it) }
        var clock = 0L
        // every file costs 10 time units (charged where a candidate is looked at); a walk may use 25
        val (asked, walks) = chain(
            rules("Music"), emptyMap(), LibraryFileWalk.Limits(maxMillis = 25), now = { clock },
            skip = { clock += 10; false },
        )
        assertThat(asked).containsExactlyElementsIn(names).inOrder()
        assertThat(walks.size).isAtLeast(3)
        assertThat(walks.dropLast(1).all { it.stoppedBy == LibraryFileWalk.Limit.TIME }).isTrue()
    }

    @Test
    fun a_limit_reached_in_the_second_folder_resumes_in_that_folder_and_keeps_the_others() {
        val names = listOf("Music/A/a1.flac", "Music/A/a2.flac", "Music/A/a3.flac", "Music/B/b1.flac", "Music/B/b2.flac", "Music/B/b3.flac", "Music/C/c1.flac")
        names.forEach { file(it) }
        val (asked, walks) = chain(rules("Music"), emptyMap(), LibraryFileWalk.Limits(maxAudioFiles = 4))
        assertThat(asked).containsExactlyElementsIn(names)
        assertThat(asked.toSet()).hasSize(names.size)
        assertThat(walks.size).isAtLeast(2)
    }

    @Test
    fun each_walk_deals_with_at_least_one_file_so_a_resumed_walk_always_moves_on() {
        val names = (1..4).map { "Music/s-%d.flac".format(it) }
        names.forEach { file(it) }
        // a budget that is over before the first file: one file is still dealt with per walk
        var clock = 0L
        val (asked, walks) = chain(
            rules("Music"), emptyMap(), LibraryFileWalk.Limits(maxMillis = 0), now = { clock },
            skip = { clock += 10; false }, maxWalks = 12,
        )
        assertThat(asked).containsExactlyElementsIn(names).inOrder()
        assertThat(walks.size).isAtMost(12)
    }

    @Test
    fun the_scanner_logs_which_limit_stopped_the_walk() {
        val source = File("src/main/java/com/lostf1sh/pixelplayeross/data/library/LibraryFileScanner.kt").readText().replace("\r\n", "\n")
        assertThat(source).contains("LibraryFileWalk.Limit.AUDIO_FILES -> \", stopped at the limit on files looked at")
        assertThat(source).contains("LibraryFileWalk.Limit.TIME -> \", stopped at the time limit")
        assertThat(source).contains("LibraryFileWalk.Limit.CANDIDATES -> \", stopped at the limit on files to ask for")
    }
}
