package com.lostf1sh.pixelplayeross.data.collections

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.metadata.DuplicateDetector
import com.lostf1sh.pixelplayeross.data.stats.dogfoodLedgers
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The built-in collections, run against the **real library on this device**.
 *
 * Read-only: it seeds nothing into the database and modifies nothing. Collections are constructed in
 * memory from [BuiltInCollections] and executed through the ordinary repository, so what is reported
 * is exactly what a user would see — not an approximation assembled for the report.
 *
 * It does not grade its own output. Counts and sample rows are printed so a human can check them
 * against the database, and the invariants that *are* asserted are the ones that must hold whatever
 * the library contains: no duplicates, no dormant items, correct ordering.
 */
@RunWith(AndroidJUnit4::class)
class SmartCollectionDogfoodTest {

    @Test
    fun report_the_built_in_collections_over_the_real_library() = runTest(timeout = 5.minutes) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = StringBuilder()
        val dbFile = context.getDatabasePath("pixelplayer_database")
        if (!dbFile.exists()) {
            write(context, report.apply { appendLine("No library database on this device.") })
            return@runTest
        }

        val db = Room.databaseBuilder(context, PixelPlayerDatabase::class.java, "pixelplayer_database")
            .addMigrations(
                com.lostf1sh.pixelplayeross.data.database.MIGRATION_12_13,
                com.lostf1sh.pixelplayeross.data.database.MIGRATION_11_12,
                com.lostf1sh.pixelplayeross.data.database.MIGRATION_10_11,
            )
            .allowMainThreadQueries()
            .build()

        try {
            val ledgers = dogfoodLedgers(context, db)
            val repository = SmartCollectionRepository(
                dao = db.smartCollectionDao(),
                candidateDao = db.collectionCandidateDao(),
                engine = SmartCollectionEngine(),
                duplicateDetector = DuplicateDetector(),
                ledgers = ledgers,
            )
            val now = System.currentTimeMillis()
            val candidates = repository.candidates()

            report.appendLine("library: ${candidates.size} playable tracks")
            report.appendLine("stores collections: ${db.smartCollectionDao().count()}")
            report.appendLine()

            BuiltInCollections.all.forEach { builtIn ->
                val result = repository.execute(builtIn.collection, now)
                val ids = result.entries.map { it.libraryItemId }

                report.appendLine("== ${builtIn.name} ==")
                report.appendLine("   candidates=${result.candidateCount} matched=${result.matchedCount} " +
                    "returned=${result.entries.size} limit=${result.limitApplied ?: "none"}")
                report.appendLine("   duplicate identities: ${ids.size - ids.toSet().size}")
                report.appendLine("   needs attention: ${result.needsAttention}")
                if (result.entries.isEmpty()) {
                    report.appendLine("   (empty — a true answer for this library today)")
                }

                // No dormant or otherwise unplayable row can be offered as a result. Checked against
                // the same guard playback uses, not against the query that produced the list.
                val unplayable = result.entries.count { db.musicDao().getActiveSongByLocator(it.locator) == null }
                report.appendLine("   entries without a live projection: $unplayable")

                result.entries.take(3).forEach { entry ->
                    // The plays are the history's, judged per listen, as the collections themselves read them.
                    val plays = ledgers.ledger().stats(entry.libraryItemId)?.plays ?: 0
                    report.appendLine("   - ${entry.title} — ${entry.trackArtist ?: "(no artist)"}")
                    report.appendLine("       plays=$plays " +
                        "firstSeen=${java.time.Instant.ofEpochMilli(entry.firstSeenAt)} " +
                        "year=${entry.releaseYear ?: "-"} genres=${entry.genres}")
                }
                report.appendLine()
            }

            // Invariants that must hold whatever this library contains.
            BuiltInCollections.all.forEach { builtIn ->
                val result = repository.execute(builtIn.collection, now)
                val ids = result.entries.map { it.libraryItemId }
                assertTrue("${builtIn.name} produced a duplicate identity", ids.size == ids.toSet().size)
                result.entries.forEach { entry ->
                    assertTrue(
                        "${builtIn.name} offered an unplayable track",
                        db.musicDao().getActiveSongByLocator(entry.locator) != null,
                    )
                }
            }

            // Ordering is deterministic: the same definition twice gives the same order.
            val first = repository.execute(BuiltInCollections.all.first().collection, now).entries.map { it.libraryItemId }
            val second = repository.execute(BuiltInCollections.all.first().collection, now).entries.map { it.libraryItemId }
            assertTrue("collection ordering is not deterministic", first == second)

            report.appendLine("Nothing was written: definitions were built in memory, not seeded.")
        } finally {
            db.close()
        }

        write(context, report)
    }

    private fun write(context: Context, report: StringBuilder) {
        File(context.filesDir, "collection_dogfood.txt").writeText(report.toString())
    }
}
