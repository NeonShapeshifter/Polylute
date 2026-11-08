package com.lostf1sh.pixelplayeross.data.worker

import android.app.Application
import java.io.File
import kotlinx.coroutines.runBlocking
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B58 2 (#68): the measurement, not a check. Opt-in (`B58_SYNC_BENCH=1`; `B58_SYNC_SONGS` sets the library size, 5,000 by default; `B58_SYNC_OUT` is the file it writes; `B58_SYNC_LABEL` names
 * the run). It runs the real worker over a synthetic library with the library screens' queries observed, three times - the first sync of a fresh database, "Update library" with nothing
 * changed, and a Full Rescan - and writes each run's phase breakdown, how many times the observed tables were invalidated and how many list queries ran again. Then the Full Rescan again
 * with nobody observing, so the cost of the observers is its own number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B58SyncSpeedBench {
    @Test
    fun measure() = runBlocking {
        Assume.assumeTrue("opt-in: B58_SYNC_BENCH=1", System.getenv("B58_SYNC_BENCH") == "1")
        val songs = System.getenv("B58_SYNC_SONGS")?.toInt() ?: 5000
        val label = System.getenv("B58_SYNC_LABEL") ?: "run"
        val out = File(System.getenv("B58_SYNC_OUT") ?: "../../../analysis/audit-2026-09-27/batches/B58-gates/sync-timing-$label.txt")
        out.parentFile?.mkdirs()
        val report = StringBuilder("B58 sync timing - $label - $songs songs, this host's JVM (Robolectric SQLite), all times in ms\n")

        val rig = B58SyncRig(songs)
        rig.includeMusic()
        var watchers = B58Observers(rig)
        watchers.start()
        watchers.settle()

        fun record(scenario: String, wallMs: Long, observed: Boolean) {
            val phases = rig.phaseMs()
            report.append("\n[$scenario] wall=${wallMs}ms observers=${if (observed) "on" else "off"}\n")
            phases.forEach { (name, ms) -> report.append("  %-16s %8d\n".format(name, ms)) }
            if (observed) {
                report.append("  invalidations of the library tables: ${watchers.invalidations.get()}\n")
                report.append("  list queries run again: ${watchers.queriesRun()} (paging loads ${watchers.pagingLoads.get()})\n")
                report.append("  per query: ${watchers.emissions.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value.get()}" }}\n")
            }
            report.append("  progress reports: ${rig.progress.size}; last=${rig.progress.lastOrNull()}\n")
            report.append("  ${rig.lines.lastOrNull { it.startsWith("sync done") }}\n")
        }

        suspend fun run(scenario: String, mode: String, deep: Boolean, observed: Boolean) {
            watchers.resetCounts()
            val t0 = System.nanoTime()
            val result = rig.sync(mode, deep)
            val wall = (System.nanoTime() - t0) / 1_000_000
            if (observed) watchers.settle()
            check(result is androidx.work.ListenableWorker.Result.Success) { "$scenario failed: $result" }
            record(scenario, wall, observed)
            out.writeText(report.toString())
        }

        run("first sync (fresh database)", "INCREMENTAL", deep = false, observed = true)
        run("Update library, nothing changed", "INCREMENTAL", deep = false, observed = true)
        run("Full Rescan", "FULL", deep = true, observed = true)
        watchers.stop()
        run("Full Rescan, nobody observing", "FULL", deep = true, observed = false)

        // The two storms the HONOR's log points at (#68): every song's folder artwork changes at the end of the sync (one database write per song, before B58), and every file renumbered
        // so every song is reattached to its identity (one transaction per song, before B58).
        val watchers2 = B58Observers(rig)
        watchers2.start()
        watchers2.settle()
        io.mockk.mockkObject(AlbumArtUtils)
        try {
            io.mockk.every { AlbumArtUtils.needsFolderArtworkRefresh(any(), any(), any(), any(), any()) } returns true
            io.mockk.every {
                AlbumArtUtils.refreshFolderArtwork(any(), any(), any(), any(), any(), any(), any(), any<com.lostf1sh.pixelplayeross.utils.FolderArtworkSession>(), any(), any())
            } answers { AlbumArtUtils.FolderArtworkResult("file:///cache/${arg<Long>(1)}.jpg", true) }
            val musicInfo = io.mockk.mockk<com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository>(relaxed = true)
            val colors = io.mockk.mockk<com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemeProcessor>(relaxed = true)
            rig.folderArtwork = com.lostf1sh.pixelplayeross.data.repository.FolderArtworkRefresh(rig.context, rig.dao, rig.prefs, musicInfo, colors)
            watchers = watchers2
            run("Full Rescan, every song's folder artwork changes", "FULL", deep = true, observed = true)
        } finally {
            io.mockk.unmockkObject(AlbumArtUtils)
        }
        rig.folderArtwork = null
        rig.provider.sql.execSQL("UPDATE audio SET _id = _id + 100000")
        run("Update library, every file renumbered (reattach all)", "INCREMENTAL", deep = false, observed = true)
        watchers2.stop()
        rig.close()
        println(report)
    }
}
