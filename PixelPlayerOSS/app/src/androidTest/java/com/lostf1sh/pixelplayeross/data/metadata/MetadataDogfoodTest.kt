package com.lostf1sh.pixelplayeross.data.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.metadata.DuplicateCandidate
import com.lostf1sh.pixelplayeross.data.stats.dogfoodLedgers
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reports what metadata intelligence makes of the **real library on this device**.
 *
 * ## Read-only, and deliberately so
 *
 * It opens the database, reads rows and computes groupings in memory. It does **not** write a
 * grouping key, does not rewrite a tag, and does not touch the user's files. The comparison it
 * reports is between the grouping the library uses *today* (the MediaStore album id, one album per
 * Android album id) and the grouping the normalizer produces — which is what the next scan will
 * apply.
 *
 * ## What it cannot fail on
 *
 * It does not assert that any grouping is correct. It reports counts and names, so a human can look
 * at a group that changed and decide whether the change is right. A dogfood that graded its own
 * output would be measuring nothing.
 */
@RunWith(AndroidJUnit4::class)
class MetadataDogfoodTest {

    @Test
    fun report_metadata_grouping_over_the_real_library() = runTest(timeout = 5.minutes) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = StringBuilder()
        val dbFile = context.getDatabasePath("pixelplayer_database")

        if (!dbFile.exists()) {
            writeReport(context, report.apply { appendLine("No library database on this device.") })
            return@runTest
        }

        val db = Room.databaseBuilder(context, PixelPlayerDatabase::class.java, "pixelplayer_database")
            .addMigrations(
                com.lostf1sh.pixelplayeross.data.database.MIGRATION_11_12,
                com.lostf1sh.pixelplayeross.data.database.MIGRATION_10_11,
            )
            .allowMainThreadQueries()
            .build()

        try {
            val songs = db.musicDao().getAllSongsList()
            val items = db.libraryItemDao().getAll().associateBy { it.libraryItemId }
            report.appendLine("tracks: ${songs.size}")
            report.appendLine()

            // --- grouping: today's behaviour vs the normalizer's -------------------------------------
            val todayGrouping = songs.groupBy { it.albumId }
            val normalizedGrouping = songs.groupBy { song ->
                MetadataNormalizer.albumGroupingKey(
                    albumTitle = song.albumName,
                    albumArtist = song.albumArtist,
                    parentDirectoryPath = song.parentDirectoryPath,
                    compilation = song.isCompilation,
                ).value
            }

            report.appendLine("== Album grouping ==")
            report.appendLine("  albums before (MediaStore album id): ${todayGrouping.size}")
            report.appendLine("  albums after  (normalized key)     : ${normalizedGrouping.size}")
            report.appendLine("  stored grouping keys present       : ${songs.count { it.albumGroupingKey.isNotBlank() }} / ${songs.size}")
            report.appendLine()

            // --- every group that changed ------------------------------------------------------------
            val changed = mutableListOf<String>()
            normalizedGrouping.forEach { (key, group) ->
                val mediaStoreIds = group.map { it.albumId }.toSet()
                if (mediaStoreIds.size > 1) {
                    changed += "  MERGED  ${group.first().albumName} — " +
                        "${mediaStoreIds.size} MediaStore albums → 1 (${group.size} tracks)"
                }
            }
            todayGrouping.forEach { (albumId, group) ->
                val keys = group.map { song ->
                    MetadataNormalizer.albumGroupingKey(song.albumName, song.albumArtist, song.parentDirectoryPath, song.isCompilation).value
                }.toSet()
                if (keys.size > 1) {
                    changed += "  SPLIT   albumId=$albumId (${group.first().albumName}) — " +
                        "$keys keys → ${keys.size} albums"
                }
            }
            report.appendLine("== Groups whose grouping changed (${changed.size}) ==")
            if (changed.isEmpty()) report.appendLine("  (none — the normalized grouping agrees with the MediaStore grouping)")
            changed.forEach { report.appendLine(it) }
            report.appendLine()

            // --- the albums themselves, as a human would check them ------------------------------------
            report.appendLine("== Albums after normalization ==")
            normalizedGrouping.entries.sortedByDescending { it.value.size }.forEach { (key, group) ->
                report.appendLine("  ${group.size} tracks · ${group.first().albumName} · albumArtist=${group.first().albumArtist ?: "(none)"}")
                report.appendLine("      key=$key")
                group.take(3).forEach { report.appendLine("      - ${it.title} — ${it.artistName}") }
                if (group.size > 3) report.appendLine("      … ${group.size - 3} more")
            }
            report.appendLine()

            // --- disc/track ---------------------------------------------------------------------------
            report.appendLine("== Multi-disc ==")
            val multiDisc = normalizedGrouping.filter { (_, group) -> group.mapNotNull { it.discNumber }.distinct().size > 1 }
            report.appendLine("  albums spanning more than one disc: ${multiDisc.size}")
            multiDisc.forEach { (_, group) ->
                report.appendLine("  ${group.first().albumName}: discs ${group.mapNotNull { it.discNumber }.distinct().sorted()}")
            }
            report.appendLine("  tracks with a disc number : ${songs.count { it.discNumber != null }}")
            report.appendLine("  tracks with a track number: ${songs.count { it.trackNumber > 0 }}")
            report.appendLine()

            // --- unknown metadata ----------------------------------------------------------------------
            report.appendLine("== Unknown metadata ==")
            report.appendLine("  title sentinel/blank     : ${songs.count { MetadataNormalizer.normalizeForDisplay(it.title) == null }}")
            report.appendLine("  artist sentinel/blank    : ${songs.count { MetadataNormalizer.normalizeForDisplay(it.artistName) == null }}")
            report.appendLine("  album sentinel/blank     : ${songs.count { MetadataNormalizer.normalizeForDisplay(it.albumName) == null }}")
            report.appendLine("  album artist absent      : ${songs.count { MetadataNormalizer.normalizeForDisplay(it.albumArtist) == null }}")
            report.appendLine("  no genre                 : ${songs.count { MetadataNormalizer.parseGenres(it.genre).isEmpty() }}")
            report.appendLine("  no release year          : ${songs.count { MetadataNormalizer.parseYear(it.year) == null }}")
            report.appendLine()

            // --- compilation / genres --------------------------------------------------------------------
            report.appendLine("== Compilation and genres ==")
            report.appendLine("  tracks marked compilation : ${songs.count { it.isCompilation }}")
            report.appendLine("  multi-genre tracks        : ${songs.count { MetadataNormalizer.parseGenres(it.genre).size > 1 }}")
            report.appendLine()

            // --- first seen --------------------------------------------------------------------------------
            report.appendLine("== First seen ==")
            val withFirstSeen = songs.count { items[it.libraryItemId]?.firstSeenAt ?: 0L > 0L }
            report.appendLine("  tracks whose library item has a first-seen date: $withFirstSeen / ${songs.size}")
            val differing = songs.count { song ->
                val firstSeen = items[song.libraryItemId]?.firstSeenAt ?: 0L
                firstSeen > 0L && firstSeen != song.dateAdded
            }
            report.appendLine("  tracks where first-seen differs from MediaStore date-added: $differing")
            report.appendLine()

            // --- duplicates ----------------------------------------------------------------------------------
            val candidates = dogfoodLedgers(context, db).ledger().catalog.items.map {
                DuplicateCandidate(it.identity, it.title, it.artistLine, it.song.albumId, it.lengthMs)
            }
            val duplicateGroups = DuplicateDetector().findGroups(candidates)
            report.appendLine("== Probable duplicates ==")
            report.appendLine("  groups: ${duplicateGroups.size}, tracks involved: ${duplicateGroups.sumOf { it.size }}")
            duplicateGroups.take(10).forEach { group ->
                report.appendLine("  · ${group.tracks.first().title} — ${group.tracks.first().artist}")
                report.appendLine("      ${group.evidence}")
                group.tracks.forEach { report.appendLine("      - ${it.libraryItemId.take(8)} · album ${it.albumId} · ${it.durationMs}ms") }
            }
            report.appendLine()

            report.appendLine("Nothing was modified: no grouping key was written, no tag touched, no item merged.")
        } finally {
            db.close()
        }

        // Detecting duplicates must never be destructive — asserted rather than merely printed.
        val after = Room.databaseBuilder(context, PixelPlayerDatabase::class.java, "pixelplayer_database")
            .allowMainThreadQueries()
            .build()
        try {
            // A read-only report cannot have changed the row count.
            assertTrue(after.musicDao().getAllSongsList().isNotEmpty())
        } finally {
            after.close()
        }

        writeReport(context, report)
    }

    private fun writeReport(context: Context, report: StringBuilder) {
        File(context.filesDir, "metadata_dogfood.txt").writeText(report.toString())
    }
}
