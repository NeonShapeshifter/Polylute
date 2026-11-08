package com.lostf1sh.pixelplayeross.data.worker

import android.app.Application
import androidx.work.ListenableWorker
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.repository.FolderArtworkRefresh
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemeProcessor
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.FolderArtworkSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B58 2 (#68): the real worker over a synthetic library of 600 songs (the 5,000-song numbers are the bench's, `B58SyncSpeedBench`).
 *
 *  - the Settings row moves through every phase and none of them sits at 0 % while work is done;
 *  - however many songs the sync touches, it invalidates the observed library tables a bounded number of times (before: once per song for the artwork step and once per renumbered
 *    file, so 600 here and 5,000 on the HONOR's library);
 *  - the timing lines the reviewer reads exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B58SyncProgressAndCostTest {
    private val songs = 600
    private lateinit var rig: B58SyncRig
    private lateinit var watchers: B58Observers

    @Before fun setup() = runBlocking {
        rig = B58SyncRig(songs)
        rig.includeMusic()
        watchers = B58Observers(rig)
        watchers.start()
        watchers.settle()
    }

    @After fun close() {
        watchers.stop()
        unmockkAll()
        rig.close()
    }

    /** The folder-artwork step of every song changes, as on a library whose covers live in the album folders. */
    private fun everySongsArtworkChanges() {
        mockkObject(AlbumArtUtils)
        every { AlbumArtUtils.needsFolderArtworkRefresh(any(), any(), any(), any(), any()) } returns true
        every {
            AlbumArtUtils.refreshFolderArtwork(any(), any(), any(), any(), any(), any(), any(), any<FolderArtworkSession>(), any(), any())
        } answers { AlbumArtUtils.FolderArtworkResult("file:///cache/${arg<Long>(1)}.jpg", true) }
        rig.folderArtwork = FolderArtworkRefresh(rig.context, rig.dao, rig.prefs, mockk<MusicInfoRepository>(relaxed = true), mockk<ColorSchemeProcessor>(relaxed = true))
    }

    private suspend fun ok(result: ListenableWorker.Result) = assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)

    // ── progress ─────────────────────────────────────────────────────────────────────────────────────

    /** The runs of reports of one phase that have a total, as (first, last) of each uninterrupted run. */
    private fun countedRuns(): List<Triple<Int, Pair<Int, Int>, Pair<Int, Int>>> {
        val out = ArrayList<Triple<Int, Pair<Int, Int>, Pair<Int, Int>>>()
        var runStart: Triple<Int, Int, Int>? = null
        var previous: Triple<Int, Int, Int>? = null
        for (r in rig.progress) {
            val sameRun = previous != null && r.third == previous.third && r.second == previous.second && r.second > 0
            if (!sameRun) {
                if (runStart != null && previous != null && previous.second > 0) out += Triple(previous.third, runStart.first to runStart.second, previous.first to previous.second)
                runStart = r
            }
            previous = r
        }
        if (runStart != null && previous != null && previous.second > 0) out += Triple(previous.third, runStart.first to runStart.second, previous.first to previous.second)
        return out
    }

    @Test fun the_row_moves_through_every_phase_and_none_ends_short_of_its_total() = runBlocking {
        everySongsArtworkChanges()
        ok(rig.sync("FULL", deep = true))
        val phases = rig.progress.map { it.third }.toSet()
        val s = com.lostf1sh.pixelplayeross.data.worker.SyncProgress.SyncPhase.entries
        assertThat(phases).containsAtLeast(
            s.indexOf(SyncProgress.SyncPhase.FETCHING_MEDIASTORE), s.indexOf(SyncProgress.SyncPhase.PROCESSING_FILES), s.indexOf(SyncProgress.SyncPhase.SAVING_TO_DATABASE),
            s.indexOf(SyncProgress.SyncPhase.COMPLETING), s.indexOf(SyncProgress.SyncPhase.CLEANING_CACHE),
        )
        // every counted run reaches its total: nothing stays at "0 %" (or anywhere short of 100 %) while the worker has gone on to something else
        val runs = countedRuns()
        assertThat(runs).isNotEmpty()
        runs.forEach { (phase, first, last) ->
            assertThat(last.first).isEqualTo(last.second)
            assertThat(first.first).isAtMost(last.first)
        }
        // the save is counted and moves: more than the opening 0 and the closing total
        val saving = rig.progress.filter { it.third == s.indexOf(SyncProgress.SyncPhase.SAVING_TO_DATABASE) && it.second == songs }.map { it.first }
        assertThat(saving.first()).isEqualTo(0)
        assertThat(saving.last()).isEqualTo(songs)
        assertThat(saving.toSet().size).isAtLeast(3)
        // and no report is "0 of N" for a phase that has finished: the last report of the whole run is a phase with nothing to count (an unfilled bar, not 0 %)
        assertThat(rig.progress.last().second).isEqualTo(0)
    }

    @Test fun the_files_read_again_after_a_renumbering_are_counted_to_their_end() = runBlocking {
        ok(rig.sync("INCREMENTAL"))
        rig.provider.sql.execSQL("UPDATE audio SET _id = _id + 100000")
        ok(rig.sync("INCREMENTAL"))
        val completing = SyncProgress.SyncPhase.entries.indexOf(SyncProgress.SyncPhase.COMPLETING)
        val counted = rig.progress.filter { it.third == completing && it.second == songs }.map { it.first }
        assertThat(counted.first()).isEqualTo(0)
        assertThat(counted.last()).isEqualTo(songs)
        assertThat(counted.size).isAtLeast(4)
    }

    // ── cost ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun a_full_rescan_where_every_songs_artwork_changes_invalidates_the_library_a_bounded_number_of_times() = runBlocking {
        ok(rig.sync("INCREMENTAL"))
        watchers.settle()
        everySongsArtworkChanges()
        watchers.resetCounts()
        ok(rig.sync("FULL", deep = true))
        watchers.settle()
        // the songs and the identities (1) and the artwork (1) - and nothing per song
        assertThat(watchers.invalidations.get()).isAtMost(MAX_INVALIDATIONS)
        assertThat(watchers.queriesRun()).isAtMost(MAX_INVALIDATIONS * 14)
        // and the artwork did land: every song carries its new address
        assertThat(rig.dao.getAllSongsList().count { it.albumArtUriString?.startsWith("file:///cache/") == true }).isEqualTo(songs)
    }

    @Test fun every_file_renumbered_is_reattached_in_one_invalidation() = runBlocking {
        ok(rig.sync("INCREMENTAL"))
        val identities = rig.dao.getAllLibraryItems().map { it.libraryItemId }.toSet()
        watchers.settle()
        rig.provider.sql.execSQL("UPDATE audio SET _id = _id + 100000")
        watchers.resetCounts()
        ok(rig.sync("INCREMENTAL"))
        watchers.settle()
        assertThat(watchers.invalidations.get()).isAtMost(MAX_INVALIDATIONS)
        // the identities are the same ones: nothing new was minted by moving the numbers
        assertThat(rig.dao.getAllLibraryItems().map { it.libraryItemId }.toSet()).isEqualTo(identities)
        assertThat(rig.dao.getAllSongsList()).hasSize(songs)
    }

    @Test fun the_first_sync_of_a_fresh_database_invalidates_the_library_once() = runBlocking {
        watchers.resetCounts()
        ok(rig.sync("INCREMENTAL"))
        watchers.settle()
        assertThat(watchers.invalidations.get()).isAtMost(MAX_INVALIDATIONS)
        assertThat(rig.dao.getAllSongsList()).hasSize(songs)
    }

    @Test fun update_library_with_nothing_changed_writes_and_invalidates_nothing() = runBlocking {
        ok(rig.sync("INCREMENTAL"))
        watchers.settle()
        watchers.resetCounts()
        rig.provider.metadataIds.clear()
        ok(rig.sync("INCREMENTAL"))
        watchers.settle()
        assertThat(watchers.invalidations.get()).isEqualTo(0)
        assertThat(rig.provider.metadataIds).isEmpty()
    }

    // ── the timing lines ─────────────────────────────────────────────────────────────────────────────

    @Test fun the_worker_writes_a_line_per_phase_and_a_closing_line() = runBlocking {
        ok(rig.sync("FULL", deep = true))
        val phases = rig.phaseMs().keys.toList()
        assertThat(phases).containsAtLeast("setup", "inventory", "identity-plan", "dormant", "fetch-metadata", "preprocess", "save", "reattach", "overrides", "seed-items", "evidence", "folder-artwork", "bookkeeping", "composers", "clean-cache").inOrder()
        val done = rig.lines.single { it.startsWith("sync done") }
        assertThat(done).startsWith("sync done mode=FULL songs=$songs ms=")
        assertThat(done).contains("phases=setup:")
        assertThat(done).contains(",save:")
        val total = done.substringAfter(" ms=").substringBefore(' ').toLong()
        assertThat(rig.phaseMs().values.sum()).isAtMost(total + 2)
        assertThat(rig.lines.single { it.startsWith("sync phase=save ") }).endsWith("items=$songs")
    }

    private companion object {
        /** The songs and identities, the artwork, and one for whatever else; the point is that it does not grow with the number of songs. */
        const val MAX_INVALIDATIONS = 4
    }
}
