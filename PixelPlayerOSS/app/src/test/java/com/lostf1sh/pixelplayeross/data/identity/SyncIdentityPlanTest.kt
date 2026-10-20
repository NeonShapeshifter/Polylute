package com.lostf1sh.pixelplayeross.data.identity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The sync-level identity decisions, end to end.
 *
 * These are the §11 cases: not "is the resolver correct?" but "what does a scan actually do?" —
 * including the one that matters most, that nothing is treated as gone until identity has been
 * resolved.
 */
class SyncIdentityPlanTest {

    private fun known(
        identity: String,
        locator: Long,
        fingerprint: String = "F",
        fileSignature: String = "S",
    ) = KnownItem(LibraryItemId(identity), locator, fingerprint, fileSignature)

    private fun scanned(
        locator: Long,
        fingerprint: String = "F",
        fileSignature: String = "S",
    ) = ScannedItem(locator, fingerprint, fileSignature)

    private fun ids(vararg values: String): () -> String {
        var index = 0
        return { values[index++] }
    }

    private fun plan(known: List<KnownItem>, scanned: List<ScannedItem>, vararg newIds: String) =
        SyncIdentityPlan.build(known, scanned, ids(*newIds))

    // --- A. RENUMBER ------------------------------------------------------------------------------

    @Test
    fun `A - a renumbered item is reattached, not treated as deleted plus new`() {
        val result = plan(
            known = listOf(known("L1", locator = 11L, fingerprint = "F")),
            scanned = listOf(scanned(locator = 22L, fingerprint = "F")),
        )

        assertThat(result.reattached.map { it.libraryItemId.value }).containsExactly("L1")
        assertThat(result.reattached.single().mediaStoreId).isEqualTo(22L)
        // The critical assertion: it is NOT in the absent list, so cleanup will never touch it.
        assertThat(result.absent).isEmpty()
        assertThat(result.newIdentityCount).isEqualTo(0)
    }

    @Test
    fun `A - the identity is preserved across the renumber, so its state follows it`() {
        val result = plan(
            known = listOf(known("L1", locator = 11L, fingerprint = "F")),
            scanned = listOf(scanned(locator = 22L, fingerprint = "F")),
        )
        assertThat(result.preservedIdentities).containsExactly("L1")
        assertThat(result.mintedIdentities).isEmpty()
        // No duplicate logical item: one identity in, one identity out.
        assertThat(result.preservedIdentities intersect result.mintedIdentities).isEmpty()
    }

    // --- B. LOCATOR REUSE -------------------------------------------------------------------------

    @Test
    fun `B - a reused locator with conflicting evidence inherits nothing`() {
        val result = plan(
            known = listOf(known("L1", locator = 11L, fingerprint = "F1", fileSignature = "S1")),
            scanned = listOf(scanned(locator = 11L, fingerprint = "F2", fileSignature = "S2")),
            newIds = *arrayOf("L2"),
        )

        assertThat(result.created.map { it.libraryItemId.value }).containsExactly("L2")
        assertThat(result.reattached).isEmpty()
        // Nothing preserves L1 for this row, so no old state is reachable from it.
        assertThat(result.preservedIdentities).isEmpty()
        // And L1 itself is not destroyed — it simply stops being present.
        assertThat(result.absent.map { it.libraryItemId.value }).containsExactly("L1")
    }

    // --- C. AMBIGUOUS -----------------------------------------------------------------------------

    @Test
    fun `C - duplicate evidence produces no arbitrary inheritance`() {
        val result = plan(
            known = listOf(
                known("L1", locator = 11L, fingerprint = "F", fileSignature = "S"),
                known("L2", locator = 12L, fingerprint = "F", fileSignature = "S"),
            ),
            scanned = listOf(scanned(locator = 33L, fingerprint = "F", fileSignature = "S")),
            newIds = *arrayOf("L3"),
        )

        assertThat(result.ambiguous.map { it.libraryItemId.value }).containsExactly("L3")
        assertThat(result.ambiguous.single().candidateCount).isEqualTo(2)
        assertThat(result.reattached).isEmpty()
        // Both originals survive as absent rather than being merged into the new row.
        assertThat(result.absent.map { it.libraryItemId.value }).containsExactly("L1", "L2")
    }

    // --- D. METADATA CHANGE ON A KNOWN ITEM --------------------------------------------------------

    @Test
    fun `D - a re-tagged item keeps its identity and is never treated as new`() {
        val result = plan(
            known = listOf(known("L1", locator = 11L, fingerprint = "F1", fileSignature = "S1")),
            scanned = listOf(scanned(locator = 11L, fingerprint = "F2", fileSignature = "S1")),
        )

        assertThat(result.unchanged.map { it.libraryItemId.value }).containsExactly("L1")
        assertThat(result.unchanged.single().fingerprintUpdated).isTrue()
        assertThat(result.newIdentityCount).isEqualTo(0)
        assertThat(result.absent).isEmpty()
    }

    // --- E. SESSION ACROSS SYNC -------------------------------------------------------------------

    @Test
    fun `E - an identity survives a full sync, so a live session still resolves to its own track`() {
        // The session captured L1 while the locator was 11. After the sync the locator is 77 and
        // the identity is unchanged, which is what lets the finalizing event land on L1.
        val before = plan(
            known = listOf(known("L1", locator = 11L)),
            scanned = listOf(scanned(locator = 11L)),
        )
        val after = plan(
            known = listOf(known("L1", locator = 11L)),
            scanned = listOf(scanned(locator = 77L)),
        )

        assertThat(before.preservedIdentities).containsExactly("L1")
        assertThat(after.preservedIdentities).containsExactly("L1")
        assertThat(after.reattached.single().previousMediaStoreId).isEqualTo(11L)
        assertThat(after.reattached.single().mediaStoreId).isEqualTo(77L)
    }

    // --- F. ORDERING ------------------------------------------------------------------------------

    @Test
    fun `F - an absent item is never also a reattached one, so cleanup cannot race the matcher`() {
        // The two sets are disjoint by construction: anything matched under a new locator is
        // carried as reattached and can never appear in the list cleanup would act on.
        val result = plan(
            known = listOf(
                known("L1", locator = 11L, fingerprint = "F1"),
                known("L2", locator = 12L, fingerprint = "F2"),
                known("L3", locator = 13L, fingerprint = "F3"),
            ),
            scanned = listOf(
                scanned(locator = 21L, fingerprint = "F1"),
                scanned(locator = 12L, fingerprint = "F2"),
            ),
        )

        val reattachedOrPresent = result.preservedIdentities
        val absent = result.absent.map { it.libraryItemId.value }.toSet()
        assertThat(absent intersect reattachedOrPresent).isEmpty()
        assertThat(absent).containsExactly("L3")
        assertThat(reattachedOrPresent).containsExactly("L1", "L2")
    }

    @Test
    fun `F - the sync deletes only after the plan has been built`() {
        val source = File("src/main/java/com/lostf1sh/pixelplayeross/data/worker/SyncWorker.kt")
            .let { if (it.isFile) it else File("app/${it.path}") }.readText()

        val planIndex = source.indexOf("SyncIdentityPlan.build(")
        val deleteIndex = source.indexOf("deleteSongsAndRelatedData(")

        assertThat(planIndex).isGreaterThan(-1)
        assertThat(deleteIndex).isGreaterThan(-1)
        // Identity is resolved before anything is destroyed. This is the ordering guarantee.
        assertThat(planIndex).isLessThan(deleteIndex)
    }

    // --- Orphan / dormant policy --------------------------------------------------------------------

    @Test
    fun `an absent item keeps its identity, because absent is not the same as deleted`() {
        // An unmounted card, a revoked permission and a MediaStore rebuild all look exactly like
        // this. Destroying history on that evidence would lose a listener's records whenever they
        // ejected a card, so an absent item is carried, not condemned.
        val result = plan(
            known = listOf(known("L1", locator = 11L)),
            scanned = emptyList(),
        )

        assertThat(result.absent.map { it.libraryItemId.value }).containsExactly("L1")
        assertThat(result.absent.single().libraryItemId.value).isEqualTo("L1")
    }

    @Test
    fun `a whole library disappearing produces absences and no new identities`() {
        val known = (1L..50L).map { known("L$it", locator = it) }
        val result = SyncIdentityPlan.build(known, emptyList(), ids())

        assertThat(result.absent).hasSize(50)
        assertThat(result.newIdentityCount).isEqualTo(0)
    }
}
