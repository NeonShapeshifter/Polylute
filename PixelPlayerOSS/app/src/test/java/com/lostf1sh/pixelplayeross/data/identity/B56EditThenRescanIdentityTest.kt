package com.lostf1sh.pixelplayeross.data.identity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B56 phase 8, hard rule: **the song keeps its identity after an edit.** `LibraryIdentity.computeKey` takes title, artist, album, track, disc and the file's size, and a tag write
 * changes all of them. This is the proof the prompt asks for, made with the real key and signature functions and the real resolver: the library item is known with the evidence its
 * file had before the edit; MediaStore then reports the file as the edit left it (same locator, new tags, and a size that grew, shrank or stayed), and the resolver decides.
 *
 * The three sizes are the three things a tag write does to a file: FLAC padding absorbs the new tags (same size), a longer Vorbis comment or a cover grows the file, a shorter tag
 * shrinks it.
 */
class B56EditThenRescanIdentityTest {
    private val duration = 245_000L
    private val sizeBefore = 31_457_280L

    private fun evidence(title: String, artist: String, album: String, track: Int, disc: Int, size: Long) = Triple(
        LibraryIdentity.computeKey(duration, size, title, artist, album, track, disc),
        LibraryIdentity.computeFileSignature(duration, size),
        size,
    )

    private val before = evidence("Cine", "Gemini Sound", "Noche", 3, 1, sizeBefore)

    private fun rescanAfterEdit(sizeAfter: Long, storedSignature: String = before.second): IdentityResolution {
        val after = evidence("Cine (Remastered)", "Gemini Sound feat. A", "Noche (Deluxe)", 3, 1, sizeAfter)
        val known = KnownItem(LibraryItemId("ITEM-1"), mediaStoreId = 4242L, fingerprint = before.first, fileSignature = storedSignature)
        val scanned = ScannedItem(mediaStoreId = 4242L, fingerprint = after.first, fileSignature = after.second)
        return LibraryIdentityResolver.resolve(listOf(known), listOf(scanned), generateId = { "MINTED" }).single()
    }

    @Test
    fun `the edit changes the key, as the prompt says`() {
        val after = evidence("Cine (Remastered)", "Gemini Sound feat. A", "Noche (Deluxe)", 3, 1, sizeBefore)
        assertThat(after.first).isNotEqualTo(before.first)
        assertThat(evidence("Cine", "Gemini Sound", "Noche", 3, 1, sizeBefore + 4096).first).isNotEqualTo(before.first)
    }

    @Test
    fun `edit and rescan with the size unchanged keeps the item`() {
        val result = rescanAfterEdit(sizeAfter = sizeBefore)
        assertThat(result.libraryItemId.value).isEqualTo("ITEM-1")
        assertThat(result.retainsIdentity).isTrue()
    }

    // ── The P0 B56 found, and the fix (B57 1.1) ──────────────────────────────────────────────────────────
    //
    // B56 showed the two tests below failing: the editor wrote the tags and the `songs` row but never the item's stored evidence, so the next scan read "same locator, different fingerprint,
    // different file signature, stored evidence not empty" as a reused number. B57 1.1 makes the editor record the evidence a scan would now read (`EditedEvidence`, same `computeKey` /
    // `fingerprintPosition` / `computeFileSignature`, in the same transaction as the `songs` row), so the stored side of these two tests is what the editor leaves behind. The resolver is
    // untouched: `B57EditorIdentityTest` runs the real editor, scan and database end to end; these two keep the resolver-level statement.

    /** What the editor records after a successful write, for a file MediaStore now reports with [sizeAfter] bytes. */
    private fun recordedByEditor(sizeAfter: Long): ItemEvidence = EditedEvidence.of(
        EditedFileFacts(duration, sizeAfter, "Cine (Remastered)", "Gemini Sound feat. A", "Noche (Deluxe)", EditedEvidence.rawTrack(3, 1))
    )

    /** What the scan reads for the same file: the same arithmetic, written the way `SyncWorker` writes it. */
    private fun scannedAfterEdit(sizeAfter: Long): ScannedItem {
        val (keyTrack, keyDisc) = LibraryIdentity.fingerprintPosition(1003)
        return ScannedItem(
            4242L,
            LibraryIdentity.computeKey(duration, sizeAfter, "Cine (Remastered)", "Gemini Sound feat. A", "Noche (Deluxe)", keyTrack, keyDisc),
            LibraryIdentity.computeFileSignature(duration, sizeAfter),
        )
    }

    private fun rescanWithEditorEvidence(sizeAfter: Long): IdentityResolution {
        val recorded = recordedByEditor(sizeAfter)
        val known = KnownItem(LibraryItemId("ITEM-1"), mediaStoreId = 4242L, fingerprint = recorded.stableKey, fileSignature = recorded.fileSignature)
        return LibraryIdentityResolver.resolve(listOf(known), listOf(scannedAfterEdit(sizeAfter)), generateId = { "MINTED" }).single()
    }

    @Test
    fun `edit and rescan with the file grown by the new tags keeps the item`() {
        val result = rescanWithEditorEvidence(sizeAfter = sizeBefore + 6_144L)
        assertThat(result.libraryItemId.value).isEqualTo("ITEM-1")
        assertThat(result.retainsIdentity).isTrue()
    }

    @Test
    fun `edit and rescan with the file shrunk by the new tags keeps the item`() {
        val result = rescanWithEditorEvidence(sizeAfter = sizeBefore - 2_048L)
        assertThat(result.libraryItemId.value).isEqualTo("ITEM-1")
        assertThat(result.retainsIdentity).isTrue()
    }

    @Test
    fun `an item left with no evidence by a half-finished edit is kept whatever the file became`() {
        // The editor parks the evidence before it writes: a crash or a failed commit leaves exactly this.
        for (sizeAfter in listOf(sizeBefore + 6_144L, sizeBefore - 2_048L, sizeBefore)) {
            val known = KnownItem(LibraryItemId("ITEM-1"), 4242L, fingerprint = ItemEvidence.NONE.stableKey, fileSignature = ItemEvidence.NONE.fileSignature)
            val result = LibraryIdentityResolver.resolve(listOf(known), listOf(scannedAfterEdit(sizeAfter)), generateId = { "MINTED" }).single()
            assertThat(result.libraryItemId.value).isEqualTo("ITEM-1")
            assertThat(result.retainsIdentity).isTrue()
        }
    }

    /**
     * Replaces B56's characterization test ("today an edit that changes the file's size mints a new identity ... which is the P0"). That test pinned the resolver's decision for evidence the
     * editor had left stale. The owner accepted B56's option 1, which keeps the resolver's reuse rule exactly as it was, so that decision is unchanged and is still asserted here, as the
     * rule it is: **evidence that was not updated, a changed file and other tags is a reused number**. What changed is the editor: it no longer leaves the evidence stale (the tests above, and
     * `B57EditorIdentityTest`, which fails against the B56 editor).
     */
    @Test
    fun `the reuse rule is unchanged evidence that was not updated plus a changed file is still a new item`() {
        for (sizeAfter in listOf(sizeBefore + 6_144L, sizeBefore - 2_048L)) {
            val known = KnownItem(LibraryItemId("ITEM-1"), 4242L, before.first, before.second)
            val plan = SyncIdentityPlan.build(listOf(known), listOf(scannedAfterEdit(sizeAfter)), generateId = { "MINTED" })
            assertThat(plan.created.map { it.libraryItemId.value }).containsExactly("MINTED")
            assertThat(plan.absent.map { it.libraryItemId.value }).containsExactly("ITEM-1")
            assertThat(plan.preservedIdentities).isEmpty()
        }
    }
}
