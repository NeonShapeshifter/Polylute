package com.lostf1sh.pixelplayeross.data.worker

import com.lostf1sh.pixelplayeross.data.database.LibraryItemEntity
import com.lostf1sh.pixelplayeross.data.identity.ScannedItem
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B41-fix1 (B10): an incremental sync reads a row again that MediaStore says did not change only while the evidence the library holds for it
 * disagrees with MediaStore's **and it has not been handled for that evidence**. On the HONOR the same 26 rows were read and upserted on every
 * sync (`MediaStore raw=26, songsToProcess=26`, `Hash: -266633018` each time), about half a second.
 *
 * The identity key is not touched by any of this: nothing here writes `stable_key`.
 */
class B41Fix1PendingProjectionsTest {
    private fun scanned(id: Long, fingerprint: String = "k$id", signature: String = "s$id") = ScannedItem(id, fingerprint, signature)

    private fun known(id: Long, stableKey: String = "k$id", signature: String = "s$id") =
        LibraryItemEntity(libraryItemId = "item-$id", stableKey = stableKey, fileSignature = signature, currentLocator = id)

    private fun library(vararg items: LibraryItemEntity) = items.associateBy { it.currentLocator!! }

    @Test
    fun `a row whose evidence agrees is not pending, and a row with no projection or no item is`() {
        val rows = listOf(scanned(1), scanned(2), scanned(3))
        val result = PendingProjections.compute(rows, localSongIds = setOf(1L, 3L), knownByLocator = library(known(1), known(2)), handled = emptyMap())
        // 1 agrees; 2 has an item but no projection; 3 has a projection but no item behind its locator
        assertEquals(setOf(2L, 3L), result.pending)
        assertTrue(result.disagreeing.isEmpty())
    }

    @Test
    fun `a row that disagrees is pending once, and not again when MediaStore says the same`() {
        // the library holds another key for it than MediaStore gives, and handling it does not make them agree
        val rows = listOf(scanned(7, fingerprint = "now"))
        val items = library(known(7, stableKey = "was"))

        val first = PendingProjections.compute(rows, setOf(7L), items, handled = emptyMap())
        assertEquals(setOf(7L), first.pending)

        // the sync wrote its projections and recorded what it handled
        val second = PendingProjections.compute(rows, setOf(7L), items, handled = first.disagreeing)
        assertEquals(emptySet<Long>(), second.pending)
        // and the record is kept for as long as it disagrees
        assertEquals(first.disagreeing, second.disagreeing)
    }

    @Test
    fun `a signature that disagrees is the same`() {
        val rows = listOf(scanned(9, signature = "new-size"))
        val items = library(known(9, signature = "old-size"))
        val first = PendingProjections.compute(rows, setOf(9L), items, emptyMap())
        assertEquals(setOf(9L), first.pending)
        assertEquals(emptySet<Long>(), PendingProjections.compute(rows, setOf(9L), items, first.disagreeing).pending)
    }

    @Test
    fun `a real change to the row in MediaStore makes it pending again`() {
        val items = library(known(7, stableKey = "was"))
        val handled = PendingProjections.compute(listOf(scanned(7, fingerprint = "now")), setOf(7L), items, emptyMap()).disagreeing

        // the file was re-tagged: MediaStore now gives another fingerprint
        val retagged = PendingProjections.compute(listOf(scanned(7, fingerprint = "retagged")), setOf(7L), items, handled)
        assertEquals(setOf(7L), retagged.pending)
        // or it was replaced by a file of another size
        val resized = PendingProjections.compute(listOf(scanned(7, fingerprint = "now", signature = "other")), setOf(7L), items, handled)
        assertEquals(setOf(7L), resized.pending)
    }

    @Test
    fun `a row that agrees again leaves the record`() {
        val rows = listOf(scanned(7, fingerprint = "now"))
        val handled = mapOf(7L to HandledEvidence.token(rows[0]))
        // the library's evidence was brought up to date (by this sync's own write): nothing disagrees, nothing is kept
        val result = PendingProjections.compute(rows, setOf(7L), library(known(7, stableKey = "now")), handled)
        assertEquals(emptySet<Long>(), result.pending)
        assertTrue(result.disagreeing.isEmpty())
    }

    @Test
    fun `a handled row that loses its projection or its item is pending again`() {
        val rows = listOf(scanned(7, fingerprint = "now"))
        val handled = mapOf(7L to HandledEvidence.token(rows[0]))
        assertEquals(setOf(7L), PendingProjections.compute(rows, emptySet(), library(known(7, stableKey = "was")), handled).pending)
        assertEquals(setOf(7L), PendingProjections.compute(rows, setOf(7L), library(), handled).pending)
    }

    // ── the record ──────────────────────────────────────────────────────────────────────────────────

    private fun newFile(): File = File(createTempDirectory("handled-evidence").toFile(), HandledEvidence.FILE_NAME)

    @Test
    fun `the record is what was saved, in a file of the app's own`() {
        val file = newFile()
        val store = HandledEvidence(file)
        assertEquals(emptyMap<Long, String>(), store.load())
        store.save(mapOf(7L to "a|b", 12L to "c|d"))
        assertEquals(mapOf(7L to "a|b", 12L to "c|d"), HandledEvidence(file).load())
        store.save(mapOf(12L to "e|f"))
        assertEquals(mapOf(12L to "e|f"), store.load())
        // nothing to keep: no file
        store.save(emptyMap())
        assertFalse(file.exists())
    }

    @Test
    fun `a damaged record reads as empty, which is what B41 did every time`() {
        val file = newFile()
        file.writeText("not a record\n\u0000\u0000\n12\n\tonly a token\nabc\tx")
        assertEquals(emptyMap<Long, String>(), HandledEvidence(file).load())
        file.writeText("5\tok\nbroken line")
        assertEquals(mapOf(5L to "ok"), HandledEvidence(file).load())
    }

    // ── the worker uses it, and keeps the identity key ─────────────────────────────────────────────

    private val worker = File("src/main/java/com/lostf1sh/pixelplayeross/data/worker/SyncWorker.kt").readText().replace("\r\n", "\n")

    @Test
    fun `the worker asks PendingProjections, and records what it handled only after the evidence was written`() {
        assertTrue(worker.contains("PendingProjections.compute(scannedItems, localSongIds, knownByLocator, handledEvidence.load())"))
        val written = worker.indexOf("musicDao.updateLibraryItemEvidenceBatch(")
        val recorded = worker.indexOf("handledEvidence.save(disagreeingEvidence)")
        assertTrue(written in 1 until recorded, "the record is made after the projections and the evidence are written")
        // a failure to record never stops the sync
        assertTrue(worker.substring(recorded - 24, recorded + 80).contains("runCatching"))
    }

    @Test
    fun `nothing in the new code writes the library's identity key`() {
        val code = File("src/main/java/com/lostf1sh/pixelplayeross/data/worker/PendingProjections.kt").readText()
        assertFalse(code.contains("stableKey ="), "PendingProjections only reads the stored key")
        assertFalse(code.contains(".copy("))
        assertFalse(code.contains("computeKey"))
    }
}
